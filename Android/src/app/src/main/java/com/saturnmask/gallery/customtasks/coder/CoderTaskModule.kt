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

package com.saturnmask.gallery.customtasks.coder

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.runtime.Composable
import com.saturnmask.gallery.customtasks.agentchat.AgentTools
import com.saturnmask.gallery.customtasks.agentchat.AgentToolsImpl
import com.saturnmask.gallery.customtasks.agentchat.AgentChatScreen
import com.saturnmask.gallery.customtasks.common.CustomTask
import com.saturnmask.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.saturnmask.gallery.data.BuiltInTaskId
import com.saturnmask.gallery.data.Category
import com.saturnmask.gallery.data.Model
import com.saturnmask.gallery.data.Task
import com.saturnmask.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.tool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Names kept in sync manually with the general-purpose models tagged for llm_agent_chat in the
// upstream allowlist (model_allowlists/1_0_15.json) — a brand-new task id has no models attached
// automatically, since that allowlist is fetched from the upstream google-ai-edge/gallery repo,
// not this fork, so `taskTypes` there can never mention "llm_coder". Task.modelNames is the only
// mechanism available to attach existing allowlist models to a new task (see
// ModelManagerViewModel.loadModelAllowlist's modelNames-matching loop).
private val CODER_MODEL_NAMES =
  listOf(
    "Gemma-4-E2B-it",
    "Gemma-4-E4B-it",
    "Gemma-3n-E2B-it",
    "Gemma-3n-E4B-it",
    "Gemma3-1B-IT",
    "Qwen2.5-1.5B-Instruct",
    "DeepSeek-R1-Distill-Qwen-1.5B",
  )

const val CODER_DEFAULT_SYSTEM_PROMPT =
  """
  You are a coding assistant with direct access to the user's project files via tools: readFile,
  writeFile, applyPatch, and searchInProject. Prefer applyPatch over writeFile for small edits to
  existing files. Always read a file before patching it so oldString matches exactly. Explain what
  you changed and why after each write.
  """

class CoderTask @Inject constructor() : CustomTask {
  private val agentTools: AgentTools = AgentToolsImpl()
  private val coderTools: CoderTools = CoderToolsImpl(sendAction = { action -> agentTools.sendAgentAction(action) })

  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_CODER,
      label = "Coder",
      category = Category.LLM,
      icon = Icons.Outlined.Code,
      newFeature = true,
      models = mutableListOf(),
      modelNames = CODER_MODEL_NAMES,
      description =
        "Chat with an on-device LLM that can read, write, and search files in a project " +
          "folder you pick on your device.",
      shortDescription = "Read, write & search a project folder",
      defaultSystemPrompt = CODER_DEFAULT_SYSTEM_PROMPT.trimIndent(),
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    val initialSystemPrompt = systemInstruction?.toString() ?: task.defaultSystemPrompt
    coroutineScope.launch(Dispatchers.Default) {
      val hasProject = coderSettingsStoreFrom(context).getProjectRootUri() != null
      LlmChatModelHelper.initialize(
        context = context,
        model = model,
        taskId = task.id,
        // Same model-agnostic gating as AgentChatTask — see its initializeModelFn comment.
        supportImage = model.llmSupportImage,
        supportAudio = model.llmSupportAudio,
        onDone = onDone,
        systemInstruction = Contents.of(initialSystemPrompt),
        tools = listOfNotNull(tool(agentTools), if (hasProject) tool(coderTools) else null),
        enableConversationConstrainedDecoding = false,
      )
    }
  }

  override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
    LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    AgentChatScreen(
      task = task,
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      agentTools = agentTools,
      coderTools = coderTools,
      initialQuery = myData.initialQuery,
      sessionId = myData.sessionId,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object CoderTaskModule {
  @Provides @IntoSet fun provideCoderTask(): CustomTask = CoderTask()
}
