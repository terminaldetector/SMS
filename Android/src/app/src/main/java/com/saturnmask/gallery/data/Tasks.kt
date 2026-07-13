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

package com.saturnmask.gallery.data

import androidx.annotation.StringRes
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import com.saturnmask.gallery.R

/**
 * Data class for a task displayed on the home screen
 *
 * Tasks are grouped into categories (see [category] field), which correspond to the tabs on the
 * home screen. The tab bar is hidden if only one category exists. Each task can have a list of
 * associated models (see [Model]], which are shown when the task is selected.
 *
 * To register a custom task, see [com.saturnmask.gallery.customtasks.common.CustomTask].
 */
data class Task(
  /**
   * The id of the task.
   *
   * The ids in [BuiltInTaskId] are reserved for built-in tasks.
   */
  val id: String,

  /** The label of the task, for display purpose. */
  val label: String,

  /**
   * The category of the task.
   *
   * We've pre-defined several categories in [Category]. Feel free to create your own category.
   */
  val category: CategoryInfo,

  /** Icon to be shown in the task tile. */
  val icon: ImageVector? = null,

  /** Vector resource id for the icon. This precedes the icon if both are set. */
  val iconVectorResourceId: Int? = null,

  /**
   * Description of the task.
   *
   * Will be shown at the top of the task screen.
   */
  val description: String,

  /** Shorter description (within 6 words) of the task. */
  val shortDescription: String = "",

  /**
   * (optional)
   *
   * Documentation url for the task.
   *
   * Will be shown below the description on the task screen.
   */
  val docUrl: String = "",

  /**
   * (optional)
   *
   * Source code url for the model-related functions.
   *
   * Will be shown below the description on the task screen.
   */
  val sourceCodeUrl: String = "",

  /** List of models for the task. */
  val models: MutableList<Model>,

  /**
   * List of model names for the task.
   *
   * If this field is non-empty, the task will try to find the models with the matching names from
   * the allowlist
   */
  val modelNames: List<String> = listOf(),

  /**
   * Whether to handel model config changes in task's screen itself. The default behavior is to
   * automatically re-initialize the model.
   */
  val handleModelConfigChangesInTask: Boolean = false,

  /** Whether the task is experimental. */
  val experimental: Boolean = false,

  /** Whether the task should have a "new" badge on home screen. */
  val newFeature: Boolean = false,

  /** Whether to use theme color instead of the task tint color. */
  val useThemeColor: Boolean = false,

  /** The default system prompt for this task. */
  val defaultSystemPrompt: String = "",

  // The following fields are only used for built-in tasks. Can ignore if you are creating your own
  // custom tasks.
  //

  /** Placeholder text for the name of the agent shown above chat messages. */
  @StringRes val agentNameRes: Int = R.string.chat_generic_agent_name,

  /** Placeholder text for the text input field. */
  @StringRes val textInputPlaceHolderRes: Int = R.string.chat_textinput_placeholder,

  // The following fields are managed by the app. Don't need to set manually.
  //

  var index: Int = -1,
  val updateTrigger: MutableState<Long> = mutableLongStateOf(0),
) {
  fun allowCapability(capability: ModelCapability, model: Model): Boolean {
    return model.capabilityToTaskTypes[capability]?.contains(id) == true
  }
}

object BuiltInTaskId {
  const val LLM_AGENT_CHAT = "llm_agent_chat"

  /** The Coder tab — chat with file read/write/patch/search tools scoped to a picked project folder. */
  const val LLM_CODER = "llm_coder"

  /**
   * Not a live Task id — the standalone "AI Chat" screen this used to back was deleted (AgentChat
   * is the only chat entry point now). Kept purely as a data tag: every general-purpose model in
   * the allowlist still carries "llm_chat" in its `taskTypes`, and it's the one such tag that's
   * universal across all of them (unlike "llm_agent_chat", which only a subset carry) while being
   * absent from narrow single-purpose finetunes (e.g. TinyGarden-270M, MobileActions-270M each
   * carry only their own dedicated tag). See ModelAllowlist.kt's `isLlmModel`, the only remaining
   * reader.
   */
  const val LLM_CHAT = "llm_chat"
}

fun isLegacyTasks(id: String): Boolean {
  // LLM_CODER also renders via AgentChatScreen's own self-contained scaffold (same as
  // LLM_AGENT_CHAT) rather than the generic CustomTaskScreen wrapper, so it needs the same
  // direct-MainScreen(CustomTaskDataForBuiltinTask) routing in GalleryNavGraph.kt.
  return id == BuiltInTaskId.LLM_AGENT_CHAT || id == BuiltInTaskId.LLM_CODER
}
