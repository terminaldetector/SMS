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

import android.content.Context
import android.util.Log
import com.saturnmask.gallery.common.AgentAction
import com.saturnmask.gallery.common.AskMcpToolCallPermissionAction
import com.saturnmask.gallery.common.PermissionResult
import com.saturnmask.gallery.common.RequestPermissionAgentAction
import com.saturnmask.gallery.common.SkillProgressAgentAction
import com.saturnmask.gallery.domain.rag.RagEngine
import com.saturnmask.gallery.domain.rag.ragEngineFrom
import com.saturnmask.gallery.domain.websearch.WebSearchEngine
import com.saturnmask.gallery.domain.websearch.webSearchEngineFrom
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking

private const val TAG = "EdgeDistilledAgentTools"

const val SKILL_INSTRUCTIONS_TEMPLATE = "---\nname: %s\ndescription: %s\n---\n\n%s"

fun SkillDescriptor.toSkillContent(): String =
  SKILL_INSTRUCTIONS_TEMPLATE.format(name, description, instructions)

/**
 * Distilled Agent Tools — ToolSet composition without Gallery UI / ViewModel types.
 *
 * Optional hosts ([SkillHost], [McpHost], [IntentRunner], [JsSkillRunner]) gate the corresponding
 * `@Tool` methods; RAG + Web Search work from [Context] EntryPoints alone.
 */
interface AgentTools : ToolSet {
  var context: Context
  var sessionId: String
  val actionChannel: ReceiveChannel<AgentAction>

  fun sendAgentAction(action: AgentAction)
}

class AgentToolsImpl(
  private val skillHost: SkillHost? = null,
  private val mcpHost: McpHost? = null,
  private val intentRunner: IntentRunner? = null,
  private val jsSkillRunner: JsSkillRunner? = null,
) : AgentTools {
  override lateinit var context: Context
  override lateinit var sessionId: String

  private val _actionChannel = Channel<AgentAction>(Channel.UNLIMITED)
  override val actionChannel: ReceiveChannel<AgentAction> = _actionChannel

  private val ragEngine: RagEngine by lazy { ragEngineFrom(context) }
  private val webSearchEngine: WebSearchEngine by lazy { webSearchEngineFrom(context) }

  override fun sendAgentAction(action: AgentAction) {
    runBlocking(Dispatchers.Default) { _actionChannel.send(action) }
  }

  @Tool(description = "Loads a skill.")
  fun loadSkill(
    @ToolParam(description = "The name of the skill to load.") skillName: String
  ): Map<String, String> {
    val host = skillHost ?: return mapOf("error" to "Skills not configured in this host")
    return runBlocking(Dispatchers.Default) {
      val skill = host.selectedSkills().find { it.name == skillName.trim() }
      if (skill != null) {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Loading skill \"$skillName\"",
            inProgress = true,
            addItemTitle = "Load \"${skill.name}\"",
            addItemDescription = "Description: ${skill.description}",
            customData = skill,
          )
        )
        mapOf("skill_name" to skillName, "skill_instructions" to skill.toSkillContent())
      } else {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Failed to load skill \"$skillName\"",
            inProgress = false,
          )
        )
        mapOf("skill_name" to skillName, "skill_instructions" to "Skill not found")
      }
    }
  }

  @Tool(
    description =
      "Returns a skill's short description, without its full instructions. Call this when a " +
        "skill's name alone doesn't tell you whether it's relevant to the current task."
  )
  fun getSkillDescription(
    @ToolParam(description = "The name of the skill to look up.") skillName: String
  ): Map<String, String> {
    val host = skillHost ?: return mapOf("error" to "Skills not configured in this host")
    val skill = host.selectedSkills().find { it.name == skillName.trim() }
    return if (skill != null) {
      mapOf("skill_name" to skill.name, "description" to skill.description)
    } else {
      mapOf("error" to "Skill not found: $skillName")
    }
  }

  @Tool(description = "Run a MCP tool")
  fun runMcpTool(
    @ToolParam(description = "The name of the tool to run.") toolName: String,
    @ToolParam(description = "The parameters passed to tool as input") input: String,
  ): Map<String, String> {
    val host = mcpHost ?: return mapOf("error" to "MCP not configured in this host", "status" to "failed")
    return runBlocking(Dispatchers.IO) {
      val handle =
        host.findTool(toolName)
          ?: return@runBlocking mapOf("error" to "Tool not found: $toolName", "status" to "failed")

      if (!handle.alwaysAllow) {
        val permissionAction = AskMcpToolCallPermissionAction(toolName = toolName, argument = input)
        _actionChannel.send(permissionAction)
        if (permissionAction.result.await() == PermissionResult.DENY) {
          return@runBlocking mapOf("error" to "Permission denied by user", "status" to "failed")
        }
      }

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Calling MCP tool \"$toolName\"",
          inProgress = true,
          addItemTitle = "Call MCP tool: \"$toolName\"",
          addItemDescription = "- Input: $input",
        )
      )
      val result = handle.call(input)
      if (result.success) {
        mapOf("result" to result.text, "status" to "succeeded")
      } else {
        mapOf("error" to (result.error ?: result.text), "status" to "failed")
      }
    }
  }

  @Tool(description = "Runs JS script")
  fun runJs(
    @ToolParam(description = "The name of skill") skillName: String,
    @ToolParam(description = "The script name to run. Use 'index.html' if not provided by user")
    scriptName: String,
    @ToolParam(description = "The data to pass to the script.") data: String,
  ): Map<String, Any> {
    val skills = skillHost
    val runner = jsSkillRunner
    if (skills == null || runner == null) {
      return mapOf("error" to "JS skills not configured in this host")
    }
    return runBlocking(Dispatchers.Default) {
      val url =
        skills.jsSkillUrl(skillName, scriptName)
          ?: return@runBlocking mapOf("error" to "Skill script not found")
      val secret = skills.readSecret(skillName) ?: ""
      val raw = runner.run(url, data, secret)
      mapOf("result" to raw)
    }
  }

  @Tool(description = "Run an Android intent.")
  fun runIntent(
    @ToolParam(description = "The intent to run.") intent: String,
    @ToolParam(description = "A JSON string containing the parameter values.") parameters: String,
  ): Map<String, String> {
    val runner = intentRunner ?: return mapOf("error" to "Intents not configured in this host")
    return runBlocking(Dispatchers.Default) {
      runner.run(intent, parameters) { permission ->
        val action = RequestPermissionAgentAction(permission = permission)
        _actionChannel.send(action)
        action.result.await()
      }
    }
  }

  @Tool(
    description =
      "Searches the user's locally indexed documents (RAG) for chunks relevant to a query."
  )
  fun ragSearch(
    @ToolParam(description = "The search query.") query: String,
    @ToolParam(description = "How many top results to return, e.g. 5.") topK: Int,
  ): Map<String, String> =
    runBlocking(Dispatchers.Default) {
      val results = ragEngine.search(query = query, topK = topK, sessionId = sessionIdOrNull())
      val body =
        results.joinToString("\n---\n") {
          "[${it.documentName} #${it.chunkIndex} sim=${"%.3f".format(it.similarity)}]\n${it.text}"
        }
      mapOf("result" to body.ifEmpty { "No matching chunks" }, "count" to results.size.toString())
    }

  @Tool(description = "Lists documents currently indexed for RAG search, with chunk counts.")
  fun ragListDocuments(): Map<String, String> =
    runBlocking(Dispatchers.Default) {
      val docs = ragEngine.listDocuments(sessionId = sessionIdOrNull())
      val body =
        docs.joinToString("\n") {
          "- ${it.name} (${it.chunkCount} chunks, ${it.scope})"
        }
      mapOf("result" to body.ifEmpty { "No documents indexed" }, "count" to docs.size.toString())
    }

  @Tool(description = "Searches the public web for up-to-date information.")
  fun webSearch(
    @ToolParam(description = "The search query.") query: String,
    @ToolParam(description = "How many results to return, e.g. 5.") maxResults: Int,
  ): Map<String, String> =
    runBlocking(Dispatchers.IO) {
      webSearchEngine
        .search(query, maxResults)
        .fold(
          onSuccess = { hits ->
            val body =
              hits.joinToString("\n") { "- ${it.title}\n  ${it.url}\n  ${it.snippet}" }
            mapOf("result" to body.ifEmpty { "No results" }, "count" to hits.size.toString())
          },
          onFailure = { e ->
            mapOf("error" to (e.message ?: "Web search failed"), "status" to "failed")
          },
        )
    }

  @Tool(description = "Fetches a web page by URL and returns its readable text content.")
  fun readUrl(
    @ToolParam(description = "The full URL to fetch, including scheme (https://...).") url: String
  ): Map<String, String> =
    runBlocking(Dispatchers.IO) {
      _actionChannel.send(SkillProgressAgentAction(label = "Reading $url", inProgress = true))
      webSearchEngine
        .fetchPage(url)
        .fold(
          onSuccess = { text ->
            _actionChannel.send(
              SkillProgressAgentAction(label = "Read $url", inProgress = false)
            )
            mapOf("result" to text)
          },
          onFailure = { e ->
            Log.e(TAG, "readUrl failed", e)
            _actionChannel.send(
              SkillProgressAgentAction(label = "Failed to read $url", inProgress = false)
            )
            mapOf("error" to (e.message ?: "Fetch failed"), "status" to "failed")
          },
        )
    }

  private fun sessionIdOrNull(): String? =
    if (::sessionId.isInitialized && sessionId.isNotBlank()) sessionId else null
}
