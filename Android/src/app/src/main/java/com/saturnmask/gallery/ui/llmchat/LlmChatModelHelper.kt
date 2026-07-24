/*
 * Copyright 2025 Google LLC
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

package com.saturnmask.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.saturnmask.gallery.common.cleanUpMediapipeTaskErrorMessage
import com.saturnmask.gallery.data.Accelerator
import com.saturnmask.gallery.data.ConfigKeys
import com.saturnmask.gallery.data.DEFAULT_MAX_TOKEN
import com.saturnmask.gallery.data.DEFAULT_TEMPERATURE
import com.saturnmask.gallery.data.DEFAULT_TOPK
import com.saturnmask.gallery.data.DEFAULT_TOPP
import com.saturnmask.gallery.data.DEFAULT_VISION_ACCELERATOR
import com.saturnmask.gallery.data.Model
import com.saturnmask.gallery.data.ModelCapability
import com.saturnmask.gallery.data.modelCapabilityOverrideStoreFrom
import com.saturnmask.gallery.data.THOUGHT_CHANNEL
import com.saturnmask.gallery.runtime.CleanUpListener
import com.saturnmask.gallery.runtime.LlmModelHelper
import com.saturnmask.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope

private const val TAG = "AGLlmChatModelHelper"

data class LlmModelInstance(val engine: Engine, var conversation: Conversation)

object LlmChatModelHelper : LlmModelHelper {
  // Indexed by model name.
  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
  override fun initialize(
    context: Context,
    model: Model,
    taskId: String,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    coroutineScope: CoroutineScope?,
    initialMessages: List<Message>,
  ) {
    // Prepare options.
    val maxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
    val temperature =
      model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
    // 0 means "let the native engine pick its own default" (Backend.CPU's own documented
    // semantics for null/0) — only applies to the CPU backend, ignored by GPU/NPU.
    val numThreads = model.getIntConfigValue(key = ConfigKeys.NUM_THREADS, defaultValue = 0)
    val seed = model.getIntConfigValue(key = ConfigKeys.SEED, defaultValue = 0)
    val accelerator =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    val visionAccelerator =
      model.getStringConfigValue(
        key = ConfigKeys.VISION_ACCELERATOR,
        defaultValue = DEFAULT_VISION_ACCELERATOR.label,
      )
    val visionBackend =
      when (visionAccelerator) {
        Accelerator.CPU.label -> Backend.CPU(numOfThreads = numThreads)
        Accelerator.GPU.label -> Backend.GPU()
        Accelerator.NPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        Accelerator.TPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        else -> Backend.GPU()
      }
    // Mutable: corrected below if the model file turns out not to actually have the encoder
    // (declared support in the allowlist/import metadata is a claim, not a guarantee).
    var shouldEnableImage = supportImage
    var shouldEnableAudio = supportAudio

    fun backendFor(acc: Accelerator): Backend =
      when (acc) {
        Accelerator.CPU -> Backend.CPU(numOfThreads = numThreads)
        Accelerator.GPU -> Backend.GPU()
        Accelerator.NPU,
        Accelerator.TPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
      }
    val requestedAccelerator =
      Accelerator.values().firstOrNull { it.label == accelerator } ?: Accelerator.CPU
    // Manual NPU->GPU->CPU fallback: LiteRT-LM's Backend.GPU()/Backend.NPU() have no automatic
    // cross-backend fallback of their own (see e.g. google-ai-edge/LiteRT-LM#2114 — a GPU compiler
    // crash on some devices surfaces straight to the user with no retry). model.accelerators is
    // already the correct, per-device-adjusted list of backends this model supports (Pixel's
    // NPU->TPU rename and Pixel 10's GPU removal are baked in via ModelAllowlist.kt), so the
    // fallback only tries backends this model actually declares, with CPU always guaranteed as the
    // unconditional last resort (LiteRT-LM's CPU backend runs any model file, just slower). The
    // user's requested backend is always tried first — never silently overridden.
    val backendPriority = listOf(Accelerator.NPU, Accelerator.TPU, Accelerator.GPU, Accelerator.CPU)
    val fallbackOrder =
      (listOf(requestedAccelerator) + backendPriority.filter { it in model.accelerators })
        .distinct()
        .let { if (Accelerator.CPU in it) it else it + Accelerator.CPU }
    var backendIndex = 0
    var preferredBackend = backendFor(fallbackOrder[backendIndex])
    Log.d(TAG, "Preferred backend: $preferredBackend (fallback order: $fallbackOrder)")

    val modelPath = model.getPath(context = context)
    fun buildEngineConfig() =
      EngineConfig(
        modelPath = modelPath,
        backend = preferredBackend,
        visionBackend = if (shouldEnableImage) visionBackend else null, // must be GPU for Gemma 3n
        audioBackend =
          if (shouldEnableAudio) Backend.CPU(numOfThreads = numThreads) else null, // must be CPU for Gemma 3n
        maxNumTokens = maxTokens,
        cacheDir =
          if (modelPath.startsWith("/data/local/tmp"))
            context.getExternalFilesDir(null)?.absolutePath
          else null,
      )

    // Check if the model file supports speculative decoding.
    var supportsSpeculativeDecoding = false
    // Check if the model file supports speculative decoding.
    try {
      com.google.ai.edge.litertlm.Capabilities(modelPath).use {
        supportsSpeculativeDecoding = it.hasSpeculativeDecodingSupport()
      }
    } catch (e: Exception) {
      // Ignore exceptions and assume not supported.
    }
    // Create an instance of LiteRT LM engine and conversation.
    try {
      var speculativeDecoding = false
      // Check if the model supports speculative decoding for the given task type and if the
      // speculative decoding is enabled in the settings.
      if (
        supportsSpeculativeDecoding &&
          model.capabilityToTaskTypes[ModelCapability.SPECULATIVE_DECODING]?.contains(taskId) ==
            true
      ) {
        speculativeDecoding =
          model.getBooleanConfigValue(
            key = ConfigKeys.ENABLE_SPECULATIVE_DECODING,
            defaultValue = false,
          )
      }
      ExperimentalFlags.enableSpeculativeDecoding = speculativeDecoding
      Log.d(TAG, "Speculative decoding enabled: $speculativeDecoding")

      // No public API tells us up front whether a .task/.litertlm file actually has a vision or
      // audio encoder built in (litertlm's Capabilities class only exposes speculative-decoding
      // support) — the only signal is engine creation itself failing with a NOT_FOUND error
      // naming the missing encoder. So: try with what was declared, and if that specific failure
      // shows up, drop that one modality and retry rather than surfacing a hard crash. At most
      // two modality retries (one per modality) plus at most fallbackOrder.size-1 backend
      // fallbacks, so this can't loop forever on an unrelated failure.
      lateinit var engine: Engine
      while (true) {
        try {
          engine = Engine(buildEngineConfig())
          engine.initialize()
          break
        } catch (e: Exception) {
          val message = e.message ?: ""
          if (shouldEnableImage && message.contains("VISION_ENCODER", ignoreCase = true)) {
            Log.w(TAG, "Model '${model.name}' has no vision encoder; retrying without image support.")
            shouldEnableImage = false
          } else if (shouldEnableAudio && message.contains("AUDIO_ENCODER", ignoreCase = true)) {
            Log.w(TAG, "Model '${model.name}' has no audio encoder; retrying without audio support.")
            shouldEnableAudio = false
          } else if (backendIndex < fallbackOrder.lastIndex) {
            Log.w(
              TAG,
              "Backend ${fallbackOrder[backendIndex]} failed to initialize for model " +
                "'${model.name}' (${e.message}); falling back to ${fallbackOrder[backendIndex + 1]}.",
            )
            backendIndex++
            preferredBackend = backendFor(fallbackOrder[backendIndex])
          } else {
            throw e
          }
        }
      }
      model.lastActiveAccelerator = fallbackOrder[backendIndex].label
      // Correct the in-memory Model (so the UI's attach-image/attach-audio buttons stop offering
      // a modality this model can't do, this session) and persist it via ModelCapabilityOverrideStore
      // so later app launches start from the known-true value instead of repeating this same
      // failed engine-creation attempt every time.
      model.llmSupportImage = shouldEnableImage
      model.llmSupportAudio = shouldEnableAudio
      modelCapabilityOverrideStoreFrom(context).saveOverride(model, shouldEnableImage, shouldEnableAudio)
      ExperimentalFlags.enableSpeculativeDecoding = false

      ExperimentalFlags.enableConversationConstrainedDecoding =
        enableConversationConstrainedDecoding
      val conversation =
        engine.createConversation(
          ConversationConfig(
            samplerConfig =
              if (preferredBackend is Backend.NPU) {
                null
              } else {
                SamplerConfig(
                  topK = topK,
                  topP = topP.toDouble(),
                  temperature = temperature.toDouble(),
                  seed = seed,
                )
              },
            systemInstruction = systemInstruction,
            tools = tools,
            initialMessages = initialMessages,
          )
        )
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      model.instance = LlmModelInstance(engine = engine, conversation = conversation)
    } catch (e: Exception) {
      onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      return
    }
    onDone("")
  }

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    initialMessages: List<Message>,
  ) {
    try {
      Log.d(TAG, "Resetting conversation for model '${model.name}'")

      val instance = model.instance as LlmModelInstance? ?: return
      instance.conversation.close()

      val engine = instance.engine
      val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
      val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
      val temperature =
        model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
      val seed = model.getIntConfigValue(key = ConfigKeys.SEED, defaultValue = 0)
      val shouldEnableImage = supportImage
      val shouldEnableAudio = supportAudio
      Log.d(TAG, "Enable image: $shouldEnableImage, enable audio: $shouldEnableAudio")

      val accelerator =
        model.getStringConfigValue(
          key = ConfigKeys.ACCELERATOR,
          defaultValue = Accelerator.GPU.label,
        )
      // Use the backend the engine was actually created with, not the requested config value —
      // they can disagree once the NPU->GPU->CPU fallback chain in initialize() has kicked in.
      val effectiveAccelerator = model.lastActiveAccelerator ?: accelerator
      ExperimentalFlags.enableConversationConstrainedDecoding =
        enableConversationConstrainedDecoding
      val newConversation =
        engine.createConversation(
          ConversationConfig(
            samplerConfig =
              if (
                effectiveAccelerator == Accelerator.NPU.label ||
                  effectiveAccelerator == Accelerator.TPU.label
              ) {
                null
              } else {
                SamplerConfig(
                  topK = topK,
                  topP = topP.toDouble(),
                  temperature = temperature.toDouble(),
                  seed = seed,
                )
              },
            systemInstruction = systemInstruction,
            tools = tools,
            initialMessages = initialMessages,
          )
        )
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      instance.conversation = newConversation

      Log.d(TAG, "Resetting done")
    } catch (e: Exception) {
      Log.d(TAG, "Failed to reset conversation", e)
    }
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    if (model.instance == null) {
      return
    }

    val instance = model.instance as LlmModelInstance

    try {
      instance.conversation.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the conversation: ${e.message}")
    }

    try {
      instance.engine.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the engine: ${e.message}")
    }

    val onCleanUp = cleanUpListeners.remove(model.name)
    if (onCleanUp != null) {
      onCleanUp()
    }
    model.instance = null

    onDone()
    Log.d(TAG, "Clean up done.")
  }

  override fun stopResponse(model: Model) {
    val instance = model.instance as? LlmModelInstance ?: return
    instance.conversation.cancelProcess()
  }

  override fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    coroutineScope: CoroutineScope?,
    extraContext: Map<String, String>?,
  ) {
    val instance = model.instance as? LlmModelInstance
    if (instance == null) {
      onError("LlmModelInstance is not initialized.")
      return
    }

    // Set listener.
    if (!cleanUpListeners.containsKey(model.name)) {
      cleanUpListeners[model.name] = cleanUpListener
    }

    val conversation = instance.conversation

    val contents = mutableListOf<Content>()
    for (image in images) {
      contents.add(Content.ImageBytes(image.toPngByteArray()))
    }
    for (audioClip in audioClips) {
      contents.add(Content.AudioBytes(audioClip))
    }
    // add the text after image and audio for the accurate last token
    if (input.trim().isNotEmpty()) {
      contents.add(Content.Text(input))
    }

    conversation.sendMessageAsync(
      Contents.of(contents),
      object : MessageCallback {
        override fun onMessage(message: Message) {
          resultListener(message.toString(), false, message.channels[THOUGHT_CHANNEL])
        }

        override fun onDone() {
          resultListener("", true, null)
        }

        override fun onError(throwable: Throwable) {
          if (throwable is CancellationException) {
            Log.i(TAG, "The inference is cancelled.")
            resultListener("", true, null)
          } else {
            Log.e(TAG, "onError", throwable)
            onError("Error: ${throwable.message}")
          }
        }
      },
      extraContext ?: emptyMap(),
    )
  }

  private fun Bitmap.toPngByteArray(): ByteArray {
    val stream = ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }
}
