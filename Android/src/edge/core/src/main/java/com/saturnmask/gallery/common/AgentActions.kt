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

package com.saturnmask.gallery.common

import kotlinx.coroutines.CompletableDeferred

/**
 * Host-agnostic agent action bus types.
 *
 * Distilled from Gallery into `:edge:core` so tool modules (`:edge:tools-coder`, etc.) and host
 * apps (Gallery, Chatter Triangle) can share the same permission/progress protocol without pulling
 * Compose UI types.
 */
open class AgentAction(val name: AgentActionName)

class CallJsAgentAction(
  val url: String,
  val data: String,
  val secret: String = "",
  val result: CompletableDeferred<String> = CompletableDeferred(),
) : AgentAction(name = AgentActionName.CALL_JS_SKILL)

class AskInfoAgentAction(
  val dialogTitle: String,
  val fieldLabel: String,
  val result: CompletableDeferred<String> = CompletableDeferred(),
) : AgentAction(name = AgentActionName.ASK_INFO)

class SkillProgressAgentAction(
  val label: String,
  val inProgress: Boolean,
  val addItemTitle: String = "",
  val addItemDescription: String = "",
  val customData: Any? = null,
) : AgentAction(name = AgentActionName.SKILL_PROGRESS)

/** Request Android permission to perform certain actions, e.g. read calendar events. */
class RequestPermissionAgentAction(
  val permission: String,
  val result: CompletableDeferred<Boolean> = CompletableDeferred(),
) : AgentAction(name = AgentActionName.REQUEST_PERMISSION)

/** Represents the result of a permission request in [AskMcpToolCallPermissionAction]. */
enum class PermissionResult {
  DENY,
  ALLOW_ONCE,
  ALWAYS_ALLOW,
}

/** An [AgentAction] to request user permission for a specific MCP tool call. */
class AskMcpToolCallPermissionAction(
  val toolName: String,
  val argument: String,
  val result: CompletableDeferred<PermissionResult> = CompletableDeferred(),
) : AgentAction(name = AgentActionName.ASK_MCP_TOOL_CALL_PERMISSION)

/**
 * An [AgentAction] to request user permission before writing/patching a project file (Coder tab).
 * Unlike [AskMcpToolCallPermissionAction] there is no [PermissionResult.ALWAYS_ALLOW] persistence
 * path for this action, by design — every writeFile/applyPatch call re-prompts.
 */
class AskFileWritePermissionAction(
  val path: String,
  val preview: String,
  val result: CompletableDeferred<PermissionResult> = CompletableDeferred(),
) : AgentAction(name = AgentActionName.ASK_FILE_WRITE_PERMISSION)

/**
 * An [AgentAction] to request user permission before a Universal Agent tap/swipe/type/goBack/
 * goHome/openApp call runs. Like [AskFileWritePermissionAction], there is no
 * [PermissionResult.ALWAYS_ALLOW] tier — every call re-prompts.
 */
class AskUniversalAgentActionPermissionAction(
  val actionDescription: String,
  val result: CompletableDeferred<PermissionResult> = CompletableDeferred(),
) : AgentAction(name = AgentActionName.ASK_UNIVERSAL_AGENT_ACTION_PERMISSION)

enum class AgentActionName {
  CALL_JS_SKILL,
  SKILL_PROGRESS,
  ASK_INFO,
  REQUEST_PERMISSION,
  ASK_MCP_TOOL_CALL_PERMISSION,
  ASK_FILE_WRITE_PERMISSION,
  ASK_UNIVERSAL_AGENT_ACTION_PERMISSION,
}
