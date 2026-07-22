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
package com.saturnmask.gallery.customtasks.agentchat

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.saturnmask.gallery.customtasks.coder.CoderTools
import com.saturnmask.gallery.customtasks.mobileactions.MobileActionsTools
import com.saturnmask.gallery.customtasks.universalagent.UniversalAgentTools
import com.saturnmask.gallery.domain.rag.RagDocumentInfo
import com.saturnmask.gallery.proto.Skill

/**
 * The single composition engine for AI Chat.
 *
 * Agent Skills and Mobile Actions used to be assembled independently in the UI, which allowed the
 * prompt, capability switches and LiteRT tool list to drift apart. This engine owns that boundary:
 * callers provide one [UnifiedAgentConfiguration], then use [compileInstruction] and [toolProviders]
 * from the same instance.
 *
 * Existing ToolSets remain adapters for model compatibility (notably MobileActions fine-tunes);
 * they are no longer independent engines. Future capabilities should be added here rather than
 * appended directly in AgentChatScreen.
 */
class UnifiedAgentEngine(
  private val agentTools: AgentTools,
  private val mobileActionsTools: MobileActionsTools? = null,
  private val coderTools: CoderTools? = null,
  private val universalAgentTools: UniversalAgentTools? = null,
  private val superAgentExtension: SuperAgentExtension? = null,
) {
  fun compileInstruction(
    baseSystemPrompt: String,
    skills: List<Skill>,
    toolsPrompt: String,
    ragDocuments: List<RagDocumentInfo>,
    configuration: UnifiedAgentConfiguration,
  ): Contents {
    val base =
      compileSkillsAndMcpPrompt(
        baseSystemPrompt = baseSystemPrompt,
        skills = skills,
        toolsPrompt = toolsPrompt,
        actionsEnabled = configuration.actionsEnabled,
        ragEnabled = configuration.ragEnabled,
        mobileActionsEnabled = configuration.mobileActionsEnabled,
        ragDocuments = ragDocuments,
        universalAgentEnabled = configuration.universalAgentEnabled,
        webSearchEnabled = configuration.webSearchEnabled,
      )

    val addenda =
      buildList {
        if (configuration.programmingEnabled) add(PROGRAMMING_INSTRUCTION)
        if (configuration.superAgentEnabled) {
          superAgentExtension?.systemInstruction?.takeIf { it.isNotBlank() }?.let(::add)
            ?: add(SUPER_AGENT_FOUNDATION_INSTRUCTION)
        }
      }

    return Contents.of(
      (listOf(base) + addenda).filter { it.isNotBlank() }.joinToString("\n\n")
    )
  }

  /**
   * One authoritative LiteRT tool registry for the chat session.
   *
   * Agent Skills/MCP/RAG/Web are exposed by [agentTools]. Mobile Actions is an adapter in this same
   * engine and remains hard-gated for safety. Coder, Universal Agent and future Super Agent
   * extensions follow the same opt-in pattern.
   */
  fun toolProviders(configuration: UnifiedAgentConfiguration): List<ToolProvider> =
    buildList {
      add(tool(agentTools))
      if (configuration.mobileActionsEnabled) mobileActionsTools?.let { add(tool(it)) }
      if (configuration.programmingEnabled) coderTools?.let { add(tool(it)) }
      if (configuration.universalAgentEnabled) universalAgentTools?.let { add(tool(it)) }
      if (configuration.superAgentEnabled) addAll(superAgentExtension?.toolProviders().orEmpty())
    }
}

data class UnifiedAgentConfiguration(
  val actionsEnabled: Boolean = true,
  val ragEnabled: Boolean = true,
  val webSearchEnabled: Boolean = true,
  val mobileActionsEnabled: Boolean = false,
  val universalAgentEnabled: Boolean = false,
  val programmingEnabled: Boolean = true,
  val superAgentEnabled: Boolean = false,
)

/**
 * Stable extension point for a future Super Agent implementation.
 *
 * The base app does not grant any Super Agent tools yet. A future implementation must explicitly
 * provide tools, a system instruction and a user-controlled enablement gate.
 */
interface SuperAgentExtension {
  val systemInstruction: String

  fun toolProviders(): List<ToolProvider>
}

/**
 * General programming guidance for unified chat. File writes remain unavailable unless CoderTools
 * is explicitly supplied, so the assistant must never pretend it edited a project.
 */
const val PROGRAMMING_INSTRUCTION =
  """
  --- PROGRAMMING GUIDANCE ---
  For programming requests: clarify missing requirements, reason from the supplied code and errors,
  prefer minimal safe changes, preserve existing APIs unless a breaking change is requested, and
  include focused verification steps. Put code in fenced blocks with the correct language. Never
  claim that files, commands, builds, or tests were changed or run unless the corresponding tools
  were actually available and successfully used.
  """

/**
 * Prompt-only foundation until a [SuperAgentExtension] is installed. It deliberately grants no
 * authority; this prevents a model from assuming future autonomous capabilities exist.
 */
const val SUPER_AGENT_FOUNDATION_INSTRUCTION =
  """
  --- SUPER AGENT FOUNDATION ---
  Super Agent capabilities are not installed. Do not claim autonomous planning, background
  execution, delegation, or unrestricted device access. Future Super Agent implementations must be
  user-enabled, permission-scoped, observable, cancellable, and registered through the unified
  agent engine.
  """
