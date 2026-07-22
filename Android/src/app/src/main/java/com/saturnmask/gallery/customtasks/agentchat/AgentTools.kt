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

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

import android.content.Context
import android.util.Log
import com.saturnmask.gallery.common.AgentAction
import com.saturnmask.gallery.common.AskInfoAgentAction
import com.saturnmask.gallery.common.AskMcpToolCallPermissionAction
import com.saturnmask.gallery.common.CallJsAgentAction
import com.saturnmask.gallery.common.CallJsSkillResult
import com.saturnmask.gallery.common.CallJsSkillResultImage
import com.saturnmask.gallery.common.CallJsSkillResultWebview
import com.saturnmask.gallery.common.LOCAL_URL_BASE
import com.saturnmask.gallery.common.PermissionResult
import com.saturnmask.gallery.common.RequestPermissionAgentAction
import com.saturnmask.gallery.common.SkillProgressAgentAction
import com.saturnmask.gallery.common.convertStringToJsonObject
import com.saturnmask.gallery.proto.Skill
import com.saturnmask.gallery.domain.rag.RagEngine
import com.saturnmask.gallery.domain.rag.ragEngineFrom
import com.saturnmask.gallery.domain.websearch.WebSearchEngine
import com.saturnmask.gallery.domain.websearch.webSearchEngineFrom
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking

private const val TAG = "AGAgentTools"
const val SKILL_INSTRUCTIONS_TEMPLATE = "---\nname: %s\ndescription: %s\n---\n\n%s"

fun Skill.getSkillContent(): String {
  return SKILL_INSTRUCTIONS_TEMPLATE.format(name, description, instructions)
}

interface AgentTools : ToolSet {
  var context: Context
  var skillManagerViewModel: SkillManagerViewModel
  var mcpManagerViewModel: McpManagerViewModel
  var taskId: String
  var sessionId: String
  val actionChannel: ReceiveChannel<AgentAction>
  var resultImageToShow: CallJsSkillResultImage?
  var resultWebviewToShow: CallJsSkillResultWebview?

  @Tool(description = "Loads a skill.")
  fun loadSkill(
    @ToolParam(description = "The name of the skill to load.") skillName: String
  ): Map<String, String>

  @Tool(
    description =
      "Returns a skill's short description, without its full instructions. Call this when a " +
        "skill's name alone doesn't tell you whether it's relevant to the current task — cheaper " +
        "than loadSkill, which fetches full instructions. Use loadSkill once you've decided to " +
        "actually use a skill."
  )
  fun getSkillDescription(
    @ToolParam(description = "The name of the skill to look up.") skillName: String
  ): Map<String, String>

  @Tool(description = "Run a MCP tool")
  fun runMcpTool(
    @ToolParam(description = "The name of the tool to run.") toolName: String,
    @ToolParam(description = "The parameters passed to tool as input") input: String,
  ): Map<String, String>

  @Tool(description = "Runs JS script")
  fun runJs(
    @ToolParam(description = "The name of skill") skillName: String,
    @ToolParam(description = "The script name to run. Use 'index.html' if not provided by user")
    scriptName: String,
    @ToolParam(
      description = "The data to pass to the script. Use empty string if not provided by user"
    )
    data: String,
  ): Map<String, Any>

  @Tool(
    description =
      "Run an Android intent. It is used to interact with the app to perform certain actions."
  )
  fun runIntent(
    @ToolParam(description = "The intent to run.") intent: String,
    @ToolParam(
      description = "A JSON string containing the parameter values required for the intent."
    )
    parameters: String,
  ): Map<String, String>

  @Tool(
    description =
      "Searches the user's locally indexed documents (RAG) for chunks relevant to a query. " +
        "Use this whenever the user asks about content from documents they've added, or when " +
        "you need grounded context before answering. Returns top matching chunks with source " +
        "document names and similarity scores."
  )
  fun ragSearch(
    @ToolParam(description = "The search query.") query: String,
    @ToolParam(description = "How many top results to return, e.g. 5.") topK: Int,
  ): Map<String, String>

  @Tool(description = "Lists documents currently indexed for RAG search, with chunk counts.")
  fun ragListDocuments(): Map<String, String>

  @Tool(
    description =
      "Searches the public web for up-to-date information the model doesn't already know or " +
        "that isn't in the user's local documents — current events, prices, recent releases, " +
        "anything time-sensitive or outside training data. Returns titles, URLs, and snippets. " +
        "Requires the user to have picked a search provider and configured its API key in " +
        "Settings > Web Search; if not configured, tell the user how to add one rather than " +
        "guessing an answer."
  )
  fun webSearch(
    @ToolParam(description = "The search query.") query: String,
    @ToolParam(description = "How many results to return, e.g. 5.") maxResults: Int,
  ): Map<String, String>

  @Tool(
    description =
      "Fetches a web page by URL and returns its readable text content, with HTML tags and " +
        "scripts stripped. Use this to read the full content of a page found via webSearch " +
        "(or one the user linked directly) instead of relying on the search snippet alone. " +
        "Does not require an API key."
  )
  fun readUrl(
    @ToolParam(description = "The full URL to fetch, including scheme (https://...).") url: String
  ): Map<String, String>

  fun sendAgentAction(action: AgentAction)
}

open class AgentToolsImpl : AgentTools {
  override lateinit var context: Context
  override lateinit var skillManagerViewModel: SkillManagerViewModel
  override lateinit var mcpManagerViewModel: McpManagerViewModel
  override lateinit var taskId: String
  override lateinit var sessionId: String

  private val _actionChannel = Channel<AgentAction>(Channel.UNLIMITED)
  override val actionChannel: ReceiveChannel<AgentAction> = _actionChannel
  override var resultImageToShow: CallJsSkillResultImage? = null
  override var resultWebviewToShow: CallJsSkillResultWebview? = null

  // Fetches the same Hilt-singleton RagEngine instance used by RagManagerViewModel, so
  // documents added via the UI are immediately visible to this tool (see RagEngineModule.kt).
  // Lazy because `context` is a lateinit var set right after this object is constructed
  // (see AgentChatScreen.kt: agentTools.context = context).
  private val ragEngine: RagEngine by lazy { ragEngineFrom(context) }

  // Same lazy-EntryPoint pattern as ragEngine above — see WebSearchEngineModule.kt.
  private val webSearchEngine: WebSearchEngine by lazy { webSearchEngineFrom(context) }

  /** Loads skill. */
  @Tool(description = "Loads a skill.")
  override fun loadSkill(
    @ToolParam(description = "The name of the skill to load.") skillName: String
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      val skills = skillManagerViewModel.getSelectedSkills()
      val skill = skills.find { it.name == skillName.trim() }
      val skillContent =
        if (skill != null) {
          skill.getSkillContent()
        } else {
          "Skill not found"
        }
      Log.d(TAG, "load skill. Skill content:\n$skillContent")
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
      } else {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Failed to load skill \"$skillName\"",
            inProgress = false,
          )
        )
      }

      mapOf("skill_name" to skillName, "skill_instructions" to skillContent)
    }
  }

  @Tool(
    description =
      "Returns a skill's short description, without its full instructions. Call this when a " +
        "skill's name alone doesn't tell you whether it's relevant to the current task — cheaper " +
        "than loadSkill, which fetches full instructions. Use loadSkill once you've decided to " +
        "actually use a skill."
  )
  override fun getSkillDescription(
    @ToolParam(description = "The name of the skill to look up.") skillName: String
  ): Map<String, String> {
    val skills = skillManagerViewModel.getSelectedSkills()
    val skill = skills.find { it.name == skillName.trim() }
    return if (skill != null) {
      mapOf("skill_name" to skill.name, "description" to skill.description)
    } else {
      mapOf("error" to "Skill not found: $skillName")
    }
  }

  @Tool(description = "Run a MCP tool")
  override fun runMcpTool(
    @ToolParam(description = "The name of the tool to run.") toolName: String,
    @ToolParam(description = "The parameters passed to tool as input") input: String,
  ): Map<String, String> {
    Log.d(TAG, "Run MCP tool:\n- name: $toolName\n- input: $input")

    return runBlocking(Dispatchers.IO) {
      val serverState =
        mcpManagerViewModel.uiState.value.mcpServers.find { serverState ->
          serverState.mcpServer.toolsList.any { it.name == toolName }
        }

      if (serverState == null) {
        Log.w(TAG, "MCP server or tool not found for: $toolName")
        logMcpExecution(success = false, errorType = "tool_not_found")
        return@runBlocking guardMissingEntityWithSkillFallback(name = toolName, type = "Tool")
      }

      val client = serverState.client
      if (client == null) {
        logMcpExecution(success = false, errorType = "client_not_initialized")
        return@runBlocking mapOf("error" to "Client not initialized", "status" to "failed")
      }

      // Check if the MCP tool requires user permission. If not always allowed,
      // send an action to ask for permission and wait for the result.
      val mcpTool = serverState.mcpServer.toolsList.find { it.name == toolName }
      val isAlwaysAllow = mcpTool?.alwaysAllow ?: false

      if (!isAlwaysAllow) {
        val permissionAction = AskMcpToolCallPermissionAction(toolName = toolName, argument = input)
        _actionChannel.send(permissionAction)
        val permissionResult = permissionAction.result.await()
        if (permissionResult == PermissionResult.DENY) {
          _actionChannel.send(
            SkillProgressAgentAction(
              label = "Permission denied for MCP tool \"$toolName\"",
              inProgress = false,
            )
          )
          logMcpExecution(success = false, errorType = "permission_denied")
          return@runBlocking mapOf("error" to "Permission denied by user", "status" to "failed")
        }
      }

      try {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Calling MCP tool \"$toolName\"",
            inProgress = true,
            addItemTitle = "Call MCP tool: \"$toolName\"",
            addItemDescription = "- Input: $input",
          )
        )
        val result =
        client.callTool(
          request = CallToolRequest(
            CallToolRequestParams(
              name = toolName,
              arguments = kotlinx.serialization.json.Json.parseToJsonElement(input).jsonObject
            )
          )
        )

        if (result == null) {
          Log.d(TAG, "Tool execution returned null result")
          _actionChannel.send(
            SkillProgressAgentAction(
              label = "Failed to call MCP tool \"$toolName\"",
              inProgress = false,
            )
          )
          logMcpExecution(success = false, errorType = "null_result")
          return@runBlocking mapOf("error" to "Null result", "status" to "failed")
        }

        if (result.isError == true) {
          val errorText =
            result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text ?: "" }
          Log.e(TAG, "MCP tool \"$toolName\" failed: $errorText")
          _actionChannel.send(
            SkillProgressAgentAction(
              label = "Failed to call MCP tool \"$toolName\"",
              addItemTitle = "Call MCP tool \"$toolName\" failed",
              addItemDescription = errorText,
              inProgress = false,
            )
          )
          logMcpExecution(success = false, errorType = "tool_error")
          return@runBlocking mapOf("error" to errorText, "status" to "failed")
        } else {
          val successText =
            result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text ?: "" }
          Log.d(TAG, "MCP tool \"$toolName\" succeeded:\n$successText")
          _actionChannel.send(
            SkillProgressAgentAction(
              label = "Succeeded calling MCP tool \"$toolName\"",
              inProgress = true,
              addItemTitle = "Call MCP tool \"$toolName\" succeeded",
              addItemDescription = successText,
            )
          )
          logMcpExecution(success = true, errorType = "")
          return@runBlocking mapOf("result" to successText, "status" to "succeeded")
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error calling MCP tool", e)
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Error calling MCP tool \"$toolName\"",
            inProgress = false,
            addItemTitle = "Call MCP tool \"$toolName\" failed",
            addItemDescription = e.message ?: "Unknown error",
          )
        )
        logMcpExecution(success = false, errorType = "exception")
        return@runBlocking mapOf("error" to (e.message ?: "Unknown error"), "status" to "failed")
      }
    }
  }

  /** Call JS skill */
  @Tool(description = "Runs JS script")
  override fun runJs(
    @ToolParam(description = "The name of skill") skillName: String,
    @ToolParam(description = "The script name to run. Use 'index.html' if not provided by user")
    scriptName: String,
    @ToolParam(
      description = "The data to pass to the script. Use empty string if not provided by user"
    )
    data: String,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      Log.d(
        TAG,
        "runJS tool called with:" +
          "\n- skillName: ${skillName}\n- scriptName: ${scriptName}\n- data: ${data}\n",
      )

      val skills = skillManagerViewModel.getSelectedSkills()
      val skill = skills.find { it.name == skillName.trim() }

      if (skill == null) {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Failed to call skill \"$scriptName\"",
            inProgress = false,
          )
        )
        return@runBlocking mapOf(
          "error" to "Skill \"${scriptName}\" not found",
          "status" to "failed",
        )
      }

      // Check secret. If a skill requires a secret and the secret is not provided, show error.
      var secret = ""
      if (skill.requireSecret) {
        val savedSecret =
          skillManagerViewModel.dataStoreRepository.readSecret(
            key = getSkillSecretKey(skillName = skillName)
          )
        if (savedSecret == null || savedSecret.isEmpty()) {
          val action =
            AskInfoAgentAction(
              dialogTitle = "Enter secret",
              fieldLabel =
                skill.requireSecretDescription.ifEmpty {
                  "The JS script needs a secret (API key / token) to proceed:"
                },
            )
          _actionChannel.send(action)
          secret = action.result.await()
          if (secret.isNotEmpty()) {
            skillManagerViewModel.dataStoreRepository.saveSecret(
              key = getSkillSecretKey(skillName = skillName),
              value = secret,
            )
            Log.d(TAG, "Got Secret from ask info dialog: ${secret.substring(0, 3)}")
          } else {
            Log.d(TAG, "The ask info dialog got cancelled. No secret.")
          }
        } else {
          secret = savedSecret
        }
      }

      // Get the url for the skill.
      val url =
        skillManagerViewModel.getJsSkillUrl(skillName = skillName, scriptName = scriptName)
          ?: return@runBlocking mapOf(
            "result" to "JS Skill URL not set properly or skill not found"
          )
      Log.d(TAG, "Calling JS script.\n- url: $url\n- data: $data")

      // Update progress.
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Calling JS script \"${skillName}/${scriptName}\"",
          inProgress = true,
          addItemTitle = "Call JS script: \"${skillName}/${scriptName}\"",
          addItemDescription = "- URL: ${url.replace(LOCAL_URL_BASE, "")}\n- Data: $data",
          customData = skill,
        )
      )

      // Actually run it and wait for the result.
      val action =
        CallJsAgentAction(url = url, data = data.trim().ifEmpty { "{}" }, secret = secret)
      _actionChannel.send(action)
      val result = action.result.await()

      // Try to parse result to CallJsSkillResult.
      val moshi: Moshi = Moshi.Builder().build()
      val jsonAdapter: JsonAdapter<CallJsSkillResult> =
        moshi.adapter(CallJsSkillResult::class.java).failOnUnknown()
      val resultJson = runCatching { jsonAdapter.fromJson(result) }.getOrNull()
      val error = resultJson?.error

      // Failed to parse. Treat its whole as a result string.
      if (
        resultJson == null ||
          (resultJson.result == null && resultJson.webview == null && resultJson.image == null)
      ) {
        mapOf("result" to result, "status" to "succeeded")
      }
      // Error case.
      else if (error != null) {
        mapOf("error" to error, "status" to "failed")
      }
      // Non-error cases.
      else {
        // Handle image and webview in result.
        val image = resultJson.image
        val webview = resultJson.webview
        if (image != null) {
          Log.d(TAG, "Got an image response.")
          resultImageToShow = image
        }
        if (webview != null) {
          Log.d(TAG, "Got an webview response.")
          val webviewUrl =
            skillManagerViewModel.getJsSkillWebviewUrl(
              skillName = skillName,
              url = webview.url ?: "",
            )
          Log.d(TAG, "Webview url: $webviewUrl")
          resultWebviewToShow = webview.copy(url = webviewUrl)
        }
        Log.d(TAG, "Result: ${resultJson.result}")
        mapOf("result" to (resultJson.result ?: ""), "status" to "succeeded")
      }
    }
  }

  @Tool(
    description =
      "Run an Android intent. It is used to interact with the app to perform certain actions."
  )
  override fun runIntent(
    @ToolParam(description = "The intent to run.") intent: String,
    @ToolParam(
      description = "A JSON string containing the parameter values required for the intent."
    )
    parameters: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      if (IntentAction.from(intent) == null) {
        Log.w(TAG, "Intent not found: '$intent'")
        return@runBlocking guardMissingEntityWithSkillFallback(name = intent, type = "Intent")
      }
      Log.d(TAG, "Run intent. Intent: '$intent', parameters: '$parameters'")
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Executing intent \"$intent\"",
          inProgress = true,
          addItemTitle = "Execute intent \"$intent\"",
          addItemDescription = "Parameters: $parameters",
        )
      )
      val res =
        IntentHandler.handleAction(context, intent, parameters) { permission ->
          val permissionAction = RequestPermissionAgentAction(permission = permission)
          _actionChannel.send(permissionAction)
          permissionAction.result.await()
        }
      return@runBlocking mapOf("action" to intent, "parameters" to parameters, "result" to res)
    }
  }

  /**
   * Guards against missing entities (tools or intents) by checking if they exist as skills. Returns
   * a failure response with a specific hint to the model to try running it as a skill if it is
   * found in the allowed skills list. This helps guide the model when it gets confused and tries to
   * call a skill as a tool or intent.
   */
  private fun guardMissingEntityWithSkillFallback(name: String, type: String): Map<String, String> {
    val skills = skillManagerViewModel.getSelectedSkills()
    val isSkill = skills.any { it.name == name.trim() }
    val error = if (isSkill) "$type not found. Try to run it as a skill" else "Tool not found"
    return mapOf("error" to error, "status" to "failed")
  }

  @Tool(
    description =
      "Searches the user's locally indexed documents (RAG) for chunks relevant to a query."
  )
  override fun ragSearch(
    @ToolParam(description = "The search query.") query: String,
    @ToolParam(description = "How many top results to return, e.g. 5.") topK: Int,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      _actionChannel.send(
        SkillProgressAgentAction(label = "Searching documents for \"$query\"", inProgress = true)
      )
      val results =
        ragEngine.search(query = query, topK = topK.coerceIn(1, 20), sessionId = sessionId)
      if (results.isEmpty()) {
        _actionChannel.send(
          SkillProgressAgentAction(label = "No matching documents found", inProgress = false)
        )
        return@runBlocking mapOf("result" to "No indexed documents matched the query.")
      }
      val formatted =
        results.joinToString("\n\n") { r ->
          "[Source: ${r.documentName}, chunk ${r.chunkIndex}, similarity ${"%.2f".format(r.similarity)}]\n${r.text}"
        }
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Found ${results.size} relevant chunk(s)",
          inProgress = false,
          addItemTitle = "RAG search: \"$query\"",
          addItemDescription = "${results.size} chunk(s) returned",
        )
      )
      mapOf("result" to formatted)
    }
  }

  @Tool(description = "Lists documents currently indexed for RAG search, with chunk counts.")
  override fun ragListDocuments(): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      val docs = ragEngine.listDocuments(sessionId = sessionId)
      val formatted =
        if (docs.isEmpty()) "No documents indexed yet."
        else docs.joinToString("\n") { "- ${it.name} (${it.chunkCount} chunks)" }
      mapOf("result" to formatted)
    }
  }

  @Tool(
    description =
      "Searches the public web for up-to-date information not in the model's training data or " +
        "the user's local documents."
  )
  override fun webSearch(
    @ToolParam(description = "The search query.") query: String,
    @ToolParam(description = "How many results to return, e.g. 5.") maxResults: Int,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      _actionChannel.send(
        SkillProgressAgentAction(label = "Searching the web for \"$query\"", inProgress = true)
      )
      val result = webSearchEngine.search(query = query, maxResults = maxResults.coerceIn(1, 10))
      result.fold(
        onSuccess = { results ->
          if (results.isEmpty()) {
            _actionChannel.send(
              SkillProgressAgentAction(label = "No web results found", inProgress = false)
            )
            return@runBlocking mapOf("result" to "No web results found for that query.")
          }
          val formatted =
            results.joinToString("\n\n") { r -> "[${r.title}](${r.url})\n${r.snippet}" }
          _actionChannel.send(
            SkillProgressAgentAction(
              label = "Found ${results.size} web result(s)",
              inProgress = false,
              addItemTitle = "Web search: \"$query\"",
              addItemDescription = "${results.size} result(s) returned",
            )
          )
          mapOf("result" to formatted)
        },
        onFailure = { e ->
          _actionChannel.send(
            SkillProgressAgentAction(label = "Web search failed", inProgress = false)
          )
          mapOf("error" to (e.message ?: "Web search failed"))
        },
      )
    }
  }

  @Tool(
    description =
      "Fetches a web page by URL and returns its readable text content, with HTML tags and " +
        "scripts stripped."
  )
  override fun readUrl(
    @ToolParam(description = "The full URL to fetch, including scheme (https://...).") url: String
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      _actionChannel.send(SkillProgressAgentAction(label = "Reading $url", inProgress = true))
      val result = webSearchEngine.fetchPage(url = url)
      result.fold(
        onSuccess = { text ->
          _actionChannel.send(
            SkillProgressAgentAction(
              label = "Read page",
              inProgress = false,
              addItemTitle = "Read: $url",
              addItemDescription = "${text.length} character(s) fetched",
            )
          )
          mapOf("result" to text)
        },
        onFailure = { e ->
          _actionChannel.send(
            SkillProgressAgentAction(label = "Failed to read page", inProgress = false)
          )
          mapOf("error" to (e.message ?: "Failed to fetch page"))
        },
      )
    }
  }

  override fun sendAgentAction(action: AgentAction) {
    runBlocking(Dispatchers.Default) { _actionChannel.send(action) }
  }

  private fun logMcpExecution(success: Boolean, errorType: String) {
    Log.d(
      TAG,
      "Analytics: mcp_execution, capability_name=$taskId, success=$success, error_type=$errorType",
    )
  }
}

fun getSkillSecretKey(skillName: String): String {
  return "skill___${skillName}"
}
