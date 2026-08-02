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

package com.saturnmask.edge.distilled.tools

/**
 * Host ports so Agent Tools can run without Gallery ViewModels / protos.
 * Chatter Triangle (or Gallery adapters) supply these.
 */
data class SkillDescriptor(
  val name: String,
  val description: String,
  val instructions: String,
)

interface SkillHost {
  fun selectedSkills(): List<SkillDescriptor>

  fun jsSkillUrl(skillName: String, scriptName: String): String?

  fun jsSkillWebviewUrl(skillName: String, scriptName: String): String?

  fun readSecret(key: String): String?

  fun saveSecret(key: String, value: String)
}

data class McpCallResult(val success: Boolean, val text: String, val error: String? = null)

interface McpHost {
  /** Returns null when no connected server exposes [toolName]. */
  fun findTool(toolName: String): McpToolHandle?
}

interface McpToolHandle {
  val toolName: String
  val alwaysAllow: Boolean

  suspend fun call(inputJson: String): McpCallResult
}

interface IntentRunner {
  /**
   * Runs a named device intent. [requestPermission] must return true if the OS permission is
   * granted (host shows the system dialog when needed).
   */
  suspend fun run(
    intent: String,
    parametersJson: String,
    requestPermission: suspend (permission: String) -> Boolean,
  ): Map<String, String>
}

interface JsSkillRunner {
  /** Executes a JS skill and returns a raw result map / JSON string for the model. */
  suspend fun run(url: String, data: String, secret: String): String
}
