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
import com.saturnmask.gallery.runtime.LlmModelHelper
import com.saturnmask.gallery.runtime.ResultListener
import com.saturnmask.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.CoroutineScope

/**
 * Host-facing LiteRT inference façade for Chatter Triangle.
 *
 * Delegates to the existing [LlmModelHelper] implementations (LiteRT-LM / AICore) so hosts do not
 * need to know about Gallery UI packages. Backend selection is applied inside the helper via
 * [BackendSelector] / model config keys.
 */
class LiteRTInference(private val helper: LlmModelHelper? = null) {

  private fun resolve(model: Model): LlmModelHelper = helper ?: model.runtimeHelper

  fun initialize(
    context: Context,
    model: Model,
    taskId: String,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    onDone: (String) -> Unit,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = emptyList(),
    enableConversationConstrainedDecoding: Boolean = false,
    coroutineScope: CoroutineScope? = null,
  ) {
    ModelLoader.prepareForLoad(model)
    resolve(model)
      .initialize(
        context = context,
        model = model,
        taskId = taskId,
        supportImage = supportImage,
        supportAudio = supportAudio,
        onDone = onDone,
        systemInstruction = systemInstruction,
        tools = tools,
        enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
        coroutineScope = coroutineScope,
      )
  }

  fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit = {},
    images: List<Bitmap> = emptyList(),
    audioClips: List<ByteArray> = emptyList(),
    coroutineScope: CoroutineScope? = null,
    extraContext: Map<String, String>? = null,
  ) {
    resolve(model)
      .runInference(
        model = model,
        input = input,
        resultListener = resultListener,
        cleanUpListener = cleanUpListener,
        onError = onError,
        images = images,
        audioClips = audioClips,
        coroutineScope = coroutineScope,
        extraContext = extraContext,
      )
  }

  fun resetConversation(
    model: Model,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = emptyList(),
    enableConversationConstrainedDecoding: Boolean = false,
    initialMessages: List<Message> = emptyList(),
  ) {
    resolve(model)
      .resetConversation(
        model = model,
        supportImage = supportImage,
        supportAudio = supportAudio,
        systemInstruction = systemInstruction,
        tools = tools,
        enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
        initialMessages = initialMessages,
      )
  }

  fun stopResponse(model: Model) = resolve(model).stopResponse(model)

  fun cleanUp(model: Model, onDone: () -> Unit) = resolve(model).cleanUp(model, onDone)
}
