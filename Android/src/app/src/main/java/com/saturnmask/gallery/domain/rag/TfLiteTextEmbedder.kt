/*
 * Copyright 2026 Google LLC
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

package com.saturnmask.gallery.domain.rag

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tflite.gpu.support.TfLiteGpu
import com.google.android.gms.tflite.java.TfLite
import com.saturnmask.gallery.data.Accelerator
import com.saturnmask.gallery.data.backendHealthStoreFrom
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterApi.Options.TfLiteRuntime
// GpuDelegateFactory dispatches to org.tensorflow.lite.gpu (bundled with the standalone runtime)
// or com.google.android.gms.tflite.gpu (Play Services) depending on which TfLiteRuntime the
// InterpreterApi.Options were built with — PREFER_SYSTEM_OVER_APPLICATION here means it resolves
// to the Play Services GPU delegate at runtime despite the org.tensorflow.lite import path.
import org.tensorflow.lite.gpu.GpuDelegateFactory

private const val TAG = "AGTfLiteTextEmbedder"

// EmbeddingGemma's documented asymmetric-retrieval prompt templates — the model expects a
// different instruction prefix for a search query vs. an indexed document chunk (see
// https://huggingface.co/google/embeddinggemma-300m's usage instructions). Unverified on-device
// (no emulator/device access this session). Public so RagEngineModule.kt can pass them explicitly
// for the built-in instance; a custom (third-party) embedder defaults to empty prefixes instead,
// since that convention isn't assumed to generalize to arbitrary embedding models.
const val EMBEDDING_GEMMA_QUERY_PREFIX = "task: search result | query: "
const val EMBEDDING_GEMMA_DOCUMENT_PREFIX = "title: none | text: "

/**
 * Play Services TFLite `InterpreterApi` + DJL `HuggingFaceTokenizer` implementation of
 * [TextEmbedder] — runs any `.tflite` embedding model + `tokenizer.json` pair whose tensor I/O
 * matches the layout assumed below. Originally EmbeddingGemma-specific (see git history); Round 7
 * parameterized `id`/prompt prefixes so the same class backs both the built-in EmbeddingGemma
 * embedder (`RagEngineModule.kt`'s `RagEmbeddingFilesModule`) and a user-supplied third-party
 * embedder (`FallbackTextEmbedder`'s settings-driven `active` embedder).
 *
 * Proven so far: `com.google.android.gms.tflite.java.TfLite` / `org.tensorflow.lite.InterpreterApi`
 * (from `libs.tflite`) alongside `ai.djl.huggingface.tokenizers.HuggingFaceTokenizer` (needs the
 * additional `ai.djl.android:tokenizer-native` artifact for its native library to load on Android
 * — see `app/build.gradle.kts`) compile and package without a duplicate-class/duplicate-native-lib
 * conflict, the failure mode that killed two earlier attempts at this feature.
 *
 * NOT proven: real inference correctness for either the built-in or a custom model. No
 * emulator/device access this session. [ensureInitialized] throws if [modelFile]/[tokenizerFile]
 * don't exist or fail to load, and [FallbackTextEmbedder] catches that and degrades to
 * [HashingTextEmbedder]. The exact input/output tensor layout below (`run(inputBuffer,
 * outputBuffer)`, plain int32 token-id buffer in / float32 vector out, no attention-mask input, no
 * batching) is an educated guess — verify via Netron against any specific model file before
 * trusting its embedding quality, built-in or custom.
 */
class TfLiteTextEmbedder(
  private val context: Context,
  private val modelFile: File,
  private val tokenizerFile: File,
  override val dimension: Int = 768,
  override val id: String = "embeddinggemma-300m-tflite",
  private val queryPrefix: String = EMBEDDING_GEMMA_QUERY_PREFIX,
  private val documentPrefix: String = EMBEDDING_GEMMA_DOCUMENT_PREFIX,
  @Volatile private var accelerator: Accelerator = Accelerator.CPU,
) : TextEmbedder {

  private val initLock = ReentrantLock()
  @Volatile private var interpreter: InterpreterApi? = null
  @Volatile private var tokenizer: HuggingFaceTokenizer? = null

  private fun ensureInitialized() {
    if (interpreter != null && tokenizer != null) return
    initLock.withLock {
      if (interpreter != null && tokenizer != null) return
      if (!modelFile.exists() || !tokenizerFile.exists()) {
        error(
          "Embedder model/tokenizer files not present (expected ${modelFile.path} / " +
            "${tokenizerFile.path}). FallbackTextEmbedder should catch this and use " +
            "HashingTextEmbedder instead."
        )
      }
      Log.d(TAG, "Initializing Play Services TFLite runtime")
      Tasks.await(TfLite.initialize(context))
      fun baseOptions() =
        InterpreterApi.Options().setRuntime(TfLiteRuntime.PREFER_SYSTEM_OVER_APPLICATION)

      // GPU is opt-in per EmbedderSettingsStore, independent of the main chat model's own
      // accelerator — this embedder is a separate InterpreterApi instance entirely. Skip a GPU
      // attempt this device already knows is broken (isKnownBad) or left mid-attempt uncleared
      // last time (hadUnclearedAttempt, the crash-loop guard — see BackendHealthStore's doc
      // comment) and go straight to CPU instead of repeating a doomed init every load.
      val healthStore = backendHealthStoreFrom(context)
      val attemptGpu =
        accelerator == Accelerator.GPU &&
          !healthStore.isKnownBad(id, Accelerator.GPU.label) &&
          !healthStore.hadUnclearedAttempt(id, Accelerator.GPU.label)
      interpreter =
        if (attemptGpu) {
          healthStore.markAttemptStarted(id, Accelerator.GPU.label)
          try {
            val gpuOptions = baseOptions()
            if (Tasks.await(TfLiteGpu.isGpuDelegateAvailable(context))) {
              gpuOptions.addDelegateFactory(GpuDelegateFactory())
            }
            InterpreterApi.create(loadModelBuffer(modelFile), gpuOptions).also {
              healthStore.markAttemptSucceeded(id, Accelerator.GPU.label)
            }
          } catch (e: Exception) {
            Log.w(TAG, "GPU delegate failed for embedder '$id'; falling back to CPU.", e)
            healthStore.markBad(id, Accelerator.GPU.label)
            InterpreterApi.create(loadModelBuffer(modelFile), baseOptions())
          }
        } else {
          InterpreterApi.create(loadModelBuffer(modelFile), baseOptions())
        }
      tokenizer = HuggingFaceTokenizer.newInstance(tokenizerFile.toPath())
      Log.d(TAG, "Interpreter + tokenizer ready ($id, accelerator=$accelerator)")
    }
  }

  /**
   * Lets an already-constructed instance — namely the Hilt-singleton built-in embedder, which
   * can't just be torn down and reconstructed like the settings-driven custom one — pick up an
   * accelerator change from [EmbedderSettingsStore] without a full app restart. A no-op if
   * [newAccelerator] matches what's already configured.
   */
  fun reconfigureAccelerator(newAccelerator: Accelerator) {
    initLock.withLock {
      if (accelerator == newAccelerator) return
      accelerator = newAccelerator
      interpreter = null
      tokenizer = null
    }
  }

  private fun loadModelBuffer(file: File): MappedByteBuffer {
    FileInputStream(file).use { stream ->
      return stream.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
    }
  }

  override suspend fun embed(text: String, kind: EmbeddingKind): FloatArray {
    ensureInitialized()
    val prefixed = if (kind == EmbeddingKind.QUERY) queryPrefix + text else documentPrefix + text
    val ids = tokenizer!!.encode(prefixed).ids

    val inputBuffer =
      ByteBuffer.allocateDirect(ids.size * 4).order(ByteOrder.nativeOrder())
    for (tokenId in ids) inputBuffer.putInt(tokenId.toInt())
    inputBuffer.rewind()

    val outputBuffer =
      ByteBuffer.allocateDirect(dimension * 4).order(ByteOrder.nativeOrder())
    interpreter!!.run(inputBuffer, outputBuffer)
    outputBuffer.rewind()

    val vector = FloatArray(dimension) { outputBuffer.float }
    return l2Normalize(vector)
  }

  private fun l2Normalize(vector: FloatArray): FloatArray {
    var sumOfSquares = 0f
    for (component in vector) sumOfSquares += component * component
    val norm = sqrt(sumOfSquares)
    if (norm <= 0f) return vector
    return FloatArray(vector.size) { vector[it] / norm }
  }
}
