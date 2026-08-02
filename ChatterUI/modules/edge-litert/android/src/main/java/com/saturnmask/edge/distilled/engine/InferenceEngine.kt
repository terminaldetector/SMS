/*
 * Copyright 2026 Saturn Mask / GEDGE distillation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.saturnmask.edge.distilled.engine

import android.content.Context
import android.graphics.Bitmap
import com.saturnmask.gallery.data.Model
import com.saturnmask.gallery.runtime.CleanUpListener
import com.saturnmask.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runtime family for the unified local-inference API (LiteRT Track A ↔ GGUF Track B). */
enum class EngineKind {
  LITERT,
  GGUF,
}

data class EngineLoadRequest(
  /** Host-resolved absolute path to weights (.litertlm / .gguf / …). */
  val modelPath: String,
  val modelId: String,
  val supportImage: Boolean = false,
  val supportAudio: Boolean = false,
  val taskId: String = "chat",
  /** Optional Gallery [Model] when loading through the LiteRT helper path. */
  val galleryModel: Model? = null,
  val systemInstruction: Contents? = null,
  val tools: List<ToolProvider> = emptyList(),
  val extra: Map<String, String> = emptyMap(),
)

data class EngineGenerateRequest(
  val prompt: String,
  val images: List<Bitmap> = emptyList(),
  val audioClips: List<ByteArray> = emptyList(),
  val extraContext: Map<String, String>? = null,
)

/**
 * Unified local inference contract for Chatter Triangle.
 *
 * - [EngineKind.LITERT] — implemented by [LiteRTInferenceEngine] (this module).
 * - [EngineKind.GGUF] — implemented by the ChatterUI / cui-llama.rn host bridge (JNI), not here.
 *
 * Hot-swap without process restart is owned by [EngineRegistry]: unload active → load other kind.
 */
interface InferenceEngine {
  val kind: EngineKind

  val isLoaded: Boolean

  suspend fun load(context: Context, request: EngineLoadRequest)

  suspend fun unload()

  /**
   * Streaming generate. Implementations must be cancellable via [stop].
   * [onPartial] may be called from a background thread.
   */
  fun generate(
    request: EngineGenerateRequest,
    onPartial: (text: String, done: Boolean) -> Unit,
    onError: (message: String) -> Unit = {},
    coroutineScope: CoroutineScope? = null,
  )

  fun stop()
}

/** LiteRT-LM backed engine — wraps [LiteRTInference] / Gallery [Model] helpers. */
class LiteRTInferenceEngine(
  private val inference: LiteRTInference = LiteRTInference()
) : InferenceEngine {
  override val kind: EngineKind = EngineKind.LITERT

  @Volatile private var loadedModel: Model? = null

  override val isLoaded: Boolean
    get() = loadedModel?.instance != null

  override suspend fun load(context: Context, request: EngineLoadRequest) {
    val model =
      request.galleryModel
        ?: error(
          "LiteRTInferenceEngine requires EngineLoadRequest.galleryModel " +
            "(GGUF loads use the host GgufInferenceEngine instead)"
        )
    unload()
    val initResult = CompletableDeferred<String?>()
    inference.initialize(
      context = context,
      model = model,
      taskId = request.taskId,
      supportImage = request.supportImage,
      supportAudio = request.supportAudio,
      onDone = { msg -> initResult.complete(msg.takeIf { it.isNotBlank() }) },
      systemInstruction = request.systemInstruction,
      tools = request.tools,
    )
    val error = initResult.await()
    if (error != null) error("LiteRT init failed: $error")
    loadedModel = model
  }

  override suspend fun unload() {
    val model = loadedModel ?: return
    val done = CompletableDeferred<Unit>()
    inference.cleanUp(model) { done.complete(Unit) }
    done.await()
    loadedModel = null
  }

  override fun generate(
    request: EngineGenerateRequest,
    onPartial: (text: String, done: Boolean) -> Unit,
    onError: (message: String) -> Unit,
    coroutineScope: CoroutineScope?,
  ) {
    val model = loadedModel ?: return onError("No LiteRT model loaded")
    val resultListener: ResultListener = { partial, done, _ -> onPartial(partial, done) }
    val cleanUpListener: CleanUpListener = {}
    inference.runInference(
      model = model,
      input = request.prompt,
      resultListener = resultListener,
      cleanUpListener = cleanUpListener,
      onError = onError,
      images = request.images,
      audioClips = request.audioClips,
      coroutineScope = coroutineScope,
      extraContext = request.extraContext,
    )
  }

  override fun stop() {
    loadedModel?.let { inference.stopResponse(it) }
  }
}

/**
 * Process-wide registry for hot-swapping LiteRT ↔ GGUF without killing the app.
 *
 * Chatter Triangle registers both engines at startup, then calls [activate] when the user
 * toggles engine kind. Active engine is unloaded before the next kind loads.
 */
class EngineRegistry {
  private val engines = mutableMapOf<EngineKind, InferenceEngine>()
  private val active = AtomicReference<InferenceEngine?>(null)
  private val mutex = Mutex()

  fun register(engine: InferenceEngine) {
    engines[engine.kind] = engine
  }

  fun get(kind: EngineKind): InferenceEngine? = engines[kind]

  fun active(): InferenceEngine? = active.get()

  suspend fun activate(kind: EngineKind): InferenceEngine {
    val next = engines[kind] ?: error("No InferenceEngine registered for $kind")
    mutex.withLock {
      val current = active.get()
      if (current === next) return next
      current?.unload()
      active.set(next)
      return next
    }
  }

  suspend fun loadActive(context: Context, request: EngineLoadRequest) {
    val engine = active.get() ?: error("No active InferenceEngine — call activate() first")
    engine.load(context, request)
  }

  suspend fun unloadActive() {
    mutex.withLock {
      active.get()?.unload()
    }
  }
}
