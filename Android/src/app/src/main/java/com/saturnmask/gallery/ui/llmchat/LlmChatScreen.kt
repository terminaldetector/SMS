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

import androidx.hilt.navigation.compose.hiltViewModel

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.saturnmask.gallery.data.Model
import com.saturnmask.gallery.data.ModelCapability
import com.saturnmask.gallery.data.Task
import com.saturnmask.gallery.ui.common.chat.ChatMessage
import com.saturnmask.gallery.ui.common.chat.ChatMessageAudioClip
import com.saturnmask.gallery.ui.common.chat.ChatMessageImage
import com.saturnmask.gallery.ui.common.chat.ChatMessageText
import com.saturnmask.gallery.ui.common.chat.ChatSide
import com.saturnmask.gallery.ui.common.chat.ChatView
import com.saturnmask.gallery.ui.common.chat.SendMessageTrigger
import com.saturnmask.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message

private const val TAG = "AGLlmChatScreen"

@Composable
fun LlmChatScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  taskId: String,
  onFirstToken: (Model) -> Unit = {},
  onGenerateResponseDone: (Model) -> Unit = {},
  universalAgentEnabled: Boolean = false,
  onUniversalAgentToggled: (Boolean) -> Unit = {},
  onResetSessionClickedOverride: ((Task, Model, List<ChatMessage>, Boolean, () -> Unit) -> Unit)? =
    null,
  composableBelowMessageList: @Composable (Model) -> Unit = {},
  viewModel: LlmChatViewModel = hiltViewModel(),
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String) -> Unit = {},
  emptyStateComposable: @Composable (Model) -> Unit = {},
  sendMessageTrigger: SendMessageTrigger? = null,
  showImagePicker: Boolean = false,
  showAudioPicker: Boolean = false,
  getActiveSkills: () -> List<String> = { emptyList() },
  mcpCount: Int = 0,
  mcpToolsCount: Int = 0,
  hideModelSelector: Boolean = false,
  onFilePicked: (Uri) -> Unit = {},
) {
  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    taskId = taskId,
    navigateUp = navigateUp,
    modifier = modifier,
    universalAgentEnabled = universalAgentEnabled,
    onUniversalAgentToggled = onUniversalAgentToggled,
    onFirstToken = onFirstToken,
    onGenerateResponseDone = onGenerateResponseDone,
    onResetSessionClickedOverride = onResetSessionClickedOverride,
    composableBelowMessageList = composableBelowMessageList,
    allowEditingSystemPrompt = allowEditingSystemPrompt,
    curSystemPrompt = curSystemPrompt,
    mcpCount = mcpCount,
    mcpToolsCount = mcpToolsCount,
    onSystemPromptChanged = onSystemPromptChanged,
    emptyStateComposable = emptyStateComposable,
    sendMessageTrigger = sendMessageTrigger,
    showImagePicker = showImagePicker,
    showAudioPicker = showAudioPicker,
    getActiveSkills = getActiveSkills,
    hideModelSelector = hideModelSelector,
    onFilePicked = onFilePicked,
  )
}

@Composable
fun ChatViewWrapper(
  viewModel: LlmChatViewModelBase,
  modelManagerViewModel: ModelManagerViewModel,
  taskId: String,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  universalAgentEnabled: Boolean = false,
  onUniversalAgentToggled: (Boolean) -> Unit = {},
  onFirstToken: (Model) -> Unit = {},
  onGenerateResponseDone: (Model) -> Unit = {},
  onResetSessionClickedOverride: ((Task, Model, List<ChatMessage>, Boolean, () -> Unit) -> Unit)? =
    null,
  composableBelowMessageList: @Composable (Model) -> Unit = {},
  emptyStateComposable: @Composable (Model) -> Unit = {},
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String) -> Unit = {},
  sendMessageTrigger: SendMessageTrigger? = null,
  showImagePicker: Boolean = false,
  showAudioPicker: Boolean = false,
  getActiveSkills: () -> List<String> = { emptyList() },
  mcpCount: Int = 0,
  mcpToolsCount: Int = 0,
  hideModelSelector: Boolean = false,
  onFilePicked: (Uri) -> Unit = {},
) {
  val context = LocalContext.current
  val task = modelManagerViewModel.getTaskById(id = taskId)!!
  val scope = rememberCoroutineScope()

  ChatView(
    task = task,
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    onSendMessage = { model, messages ->
      for (message in messages) {
        viewModel.addMessage(model = model, message = message)
      }

      var text = ""
      val images: MutableList<Bitmap> = mutableListOf()
      val audioMessages: MutableList<ChatMessageAudioClip> = mutableListOf()
      var chatMessageText: ChatMessageText? = null
      for (message in messages) {
        if (message is ChatMessageText) {
          chatMessageText = message
          text = message.content
        } else if (message is ChatMessageImage) {
          images.addAll(message.bitmaps)
        } else if (message is ChatMessageAudioClip) {
          audioMessages.add(message)
        }
      }
      if ((text.isNotEmpty() && chatMessageText != null) || audioMessages.isNotEmpty()) {
        if (text.isNotEmpty()) {
          modelManagerViewModel.addTextInputHistory(text)
        }
        viewModel.generateResponse(
          model = model,
          input = text,
          images = images,
          audioMessages = audioMessages,
          onFirstToken = onFirstToken,
          onDone = { onGenerateResponseDone(model) },
          onError = { errorMessage ->
            viewModel.handleError(
              context = context,
              task = task,
              model = model,
              errorMessage = errorMessage,
              modelManagerViewModel = modelManagerViewModel,
            )
          },
          allowThinking = task.allowCapability(ModelCapability.LLM_THINKING, model),
        )

        val activeSkills = getActiveSkills()
        Log.d(
          TAG,
          "Analytics: generate_action, capability_name=${task.id}, active_skills=${activeSkills.joinToString(",")}, active_mcp_servers_count=$mcpCount, active_mcp_tools_count=$mcpToolsCount",
        )
      }
    },
    onRunAgainClicked = { model, message ->
      if (message is ChatMessageText) {
        viewModel.runAgain(
          model = model,
          message = message,
          onError = { errorMessage ->
            viewModel.handleError(
              context = context,
              task = task,
              model = model,
              errorMessage = errorMessage,
              modelManagerViewModel = modelManagerViewModel,
            )
          },
          allowThinking = task.allowCapability(ModelCapability.LLM_THINKING, model),
        )
      }
    },
    onBenchmarkClicked = { _, _, _, _ -> },
    onResetSessionClicked = { model, chatMessages, clearHistory, onDone ->
      val litertMessages = chatMessages.mapNotNull { convertToLitertMessage(it) }
      if (onResetSessionClickedOverride != null) {
        onResetSessionClickedOverride(task, model, chatMessages, clearHistory, onDone)
      } else {
        viewModel.resetSession(
          task = task,
          model = model,
          systemInstruction = Contents.of(curSystemPrompt),
          supportImage = showImagePicker,
          supportAudio = showAudioPicker,
          initialMessages = litertMessages,
          onDone = onDone,
          clearHistory = clearHistory,
        )
      }
    },
    showStopButtonInInputWhenInProgress = true,
    onStopButtonClicked = { model -> viewModel.stopResponse(model = model) },
    universalAgentEnabled = universalAgentEnabled,
    onUniversalAgentToggled = onUniversalAgentToggled,
    navigateUp = navigateUp,
    modifier = modifier,
    composableBelowMessageList = composableBelowMessageList,
    showImagePicker = showImagePicker,
    emptyStateComposable = emptyStateComposable,
    allowEditingSystemPrompt = allowEditingSystemPrompt,
    curSystemPrompt = curSystemPrompt,
    onSystemPromptChanged = onSystemPromptChanged,
    sendMessageTrigger = sendMessageTrigger,
    showAudioPicker = showAudioPicker,
    hideModelSelector = hideModelSelector,
    onFilePicked = onFilePicked,
  )
}

private fun convertToLitertMessage(chatMessage: ChatMessage): Message? {
  // TODO: Restore image and audio messages to the LLM context.
  // We are currently bypassing them because the image and audio encoder may take
  // too long during chat history loading, which can cause stalls or stream errors.
  if (chatMessage is ChatMessageText) {
    return when (chatMessage.side) {
      ChatSide.USER -> Message.user(chatMessage.content)
      ChatSide.AGENT -> Message.model(chatMessage.content)
      ChatSide.SYSTEM ->
        null // TODO: Support SYSTEM role once we can decide on which system prompt to use.
    }
  }
  return null
}
