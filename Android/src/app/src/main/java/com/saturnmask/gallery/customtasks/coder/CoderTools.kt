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
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.saturnmask.gallery.common.AgentAction
import com.saturnmask.gallery.common.AskFileWritePermissionAction
import com.saturnmask.gallery.common.PermissionResult
import com.saturnmask.gallery.common.SkillProgressAgentAction
import com.saturnmask.gallery.domain.rag.RagEngine
import com.saturnmask.gallery.domain.rag.ragEngineFrom
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val PREVIEW_MAX_CHARS = 4000

interface CoderTools : ToolSet {
  var context: Context
  var projectRootUri: Uri?
  /** Set from AgentChatScreen via CoderViewModel.sessionIdFor(projectRootUri) — the RAG partition key. */
  var ragSessionId: String?

  @Tool(
    description =
      "Reads a text file from the project. Path is relative to the project root, e.g. \"src/Main.kt\"."
  )
  fun readFile(@ToolParam(description = "Path relative to the project root.") path: String): Map<String, String>

  @Tool(
    description =
      "Creates a new file or completely overwrites an existing file in the project with the " +
        "given content. Requires user confirmation. Prefer applyPatch for small edits to " +
        "existing files — writeFile replaces the ENTIRE file."
  )
  fun writeFile(
    @ToolParam(description = "Path relative to the project root.") path: String,
    @ToolParam(description = "The full new content of the file.") content: String,
  ): Map<String, String>

  @Tool(
    description =
      "Makes a precise edit to an existing file: oldString must occur EXACTLY ONCE in the " +
        "file's current content (include enough surrounding context to make it unique), and is " +
        "replaced with newString. Requires user confirmation. Fails if oldString is missing or " +
        "appears more than once."
  )
  fun applyPatch(
    @ToolParam(description = "Path relative to the project root.") path: String,
    @ToolParam(description = "Exact text to find; must be unique in the file.") oldString: String,
    @ToolParam(description = "Text to replace it with.") newString: String,
  ): Map<String, String>

  @Tool(
    description =
      "Searches the indexed project files for text relevant to a query. Only searches files " +
        "that were indexed when the project folder was picked/reindexed — if results seem stale " +
        "after external edits, tell the user to tap Reindex."
  )
  fun searchInProject(
    @ToolParam(description = "The search query.") query: String,
    @ToolParam(description = "How many top results to return, e.g. 5.") topK: Int,
  ): Map<String, String>

  fun sendAgentAction(action: AgentAction)
}

/**
 * Not Hilt-constructed — same reasoning as [com.saturnmask.gallery.customtasks.agentchat
 * .AgentToolsImpl]: it's a `val` field on a [CoderTask] instance, constructed once, and rewired
 * per-composition via the `context`/`projectRootUri`/`ragSessionId` vars, the same
 * imperative-assignment pattern AgentChatScreen.kt uses for `agentTools.context = context` etc.
 *
 * File-write permission prompts and progress updates are funneled through
 * [com.saturnmask.gallery.customtasks.agentchat.AgentTools.sendAgentAction] — the same action
 * channel `AgentTools` already owns and AgentChatScreen.kt already consumes — via [sendAction],
 * rather than this class owning a second `Channel<AgentAction>` and requiring a second consumer
 * loop. [com.saturnmask.gallery.customtasks.coder.CoderTaskModule]'s `CoderTask` wires this as
 * `{ action -> agentTools.sendAgentAction(action) }`.
 */
class CoderToolsImpl(private val sendAction: (AgentAction) -> Unit) : CoderTools {
  override lateinit var context: Context
  override var projectRootUri: Uri? = null
  override var ragSessionId: String? = null

  private val ragEngine: RagEngine by lazy { ragEngineFrom(context) }

  override fun readFile(path: String): Map<String, String> {
    val root = rootDocOrNull() ?: return noProjectError()
    CoderFileResolver.rejectionReasonFor(path)?.let { return mapOf("error" to it, "status" to "failed") }
    val doc =
      CoderFileResolver.resolveExisting(root, path)
        ?: return mapOf("error" to "File not found: $path", "status" to "failed")
    val text =
      runCatching { CoderFileResolver.readText(context, doc.uri) }
        .getOrElse { return mapOf("error" to (it.message ?: "Failed to read file"), "status" to "failed") }
    return mapOf("result" to text, "path" to path)
  }

  override fun writeFile(path: String, content: String): Map<String, String> =
    runBlocking(Dispatchers.Default) {
      val root = rootDocOrNull() ?: return@runBlocking noProjectError()
      CoderFileResolver.rejectionReasonFor(path)?.let {
        return@runBlocking mapOf("error" to it, "status" to "failed")
      }
      val existing = CoderFileResolver.resolveExisting(root, path)
      val oldContent =
        existing?.let { runCatching { CoderFileResolver.readText(context, it.uri) }.getOrNull() }

      if (!confirmWrite(path = path, oldContent = oldContent, newContent = content, isPatch = false)) {
        return@runBlocking mapOf("error" to "Permission denied by user", "status" to "failed")
      }

      runCatching { CoderFileResolver.writeText(context, root, path, existing, content) }
        .fold(
          onSuccess = {
            sendAction(SkillProgressAgentAction(label = "Wrote $path", inProgress = false))
            mapOf("result" to "File written: $path", "status" to "succeeded")
          },
          onFailure = { e -> mapOf("error" to (e.message ?: "Write failed"), "status" to "failed") },
        )
    }

  override fun applyPatch(path: String, oldString: String, newString: String): Map<String, String> =
    runBlocking(Dispatchers.Default) {
      val root = rootDocOrNull() ?: return@runBlocking noProjectError()
      CoderFileResolver.rejectionReasonFor(path)?.let {
        return@runBlocking mapOf("error" to it, "status" to "failed")
      }
      val existing =
        CoderFileResolver.resolveExisting(root, path)
          ?: return@runBlocking mapOf("error" to "File not found: $path", "status" to "failed")
      val currentContent =
        runCatching { CoderFileResolver.readText(context, existing.uri) }
          .getOrElse {
            return@runBlocking mapOf("error" to (it.message ?: "Failed to read file"), "status" to "failed")
          }

      val occurrences = Regex(Regex.escape(oldString)).findAll(currentContent).count()
      if (occurrences == 0) {
        return@runBlocking mapOf("error" to "oldString not found in $path", "status" to "failed")
      }
      if (occurrences > 1) {
        return@runBlocking mapOf(
          "error" to
            "oldString appears $occurrences times in $path — include more context to make it unique",
          "status" to "failed",
        )
      }
      val newContent = currentContent.replaceFirst(oldString, newString)

      if (!confirmWrite(path = path, oldContent = oldString, newContent = newString, isPatch = true)) {
        return@runBlocking mapOf("error" to "Permission denied by user", "status" to "failed")
      }

      runCatching { CoderFileResolver.writeText(context, root, path, existing, newContent) }
        .fold(
          onSuccess = {
            sendAction(SkillProgressAgentAction(label = "Patched $path", inProgress = false))
            mapOf("result" to "File patched: $path", "status" to "succeeded")
          },
          onFailure = { e -> mapOf("error" to (e.message ?: "Write failed"), "status" to "failed") },
        )
    }

  override fun searchInProject(query: String, topK: Int): Map<String, String> =
    runBlocking(Dispatchers.Default) {
      val sid = ragSessionId ?: return@runBlocking noProjectError()
      sendAction(SkillProgressAgentAction(label = "Searching project for \"$query\"", inProgress = true))
      val results = ragEngine.search(query = query, topK = topK.coerceIn(1, 20), sessionId = sid)
      sendAction(SkillProgressAgentAction(label = "Found ${results.size} result(s)", inProgress = false))
      if (results.isEmpty()) {
        return@runBlocking mapOf(
          "result" to
            "No indexed project files matched. If the project was just picked or files changed, " +
              "ask the user to tap Reindex."
        )
      }
      mapOf(
        "result" to
          results.joinToString("\n\n") { r ->
            "[${r.documentName}, chunk ${r.chunkIndex}, similarity ${"%.2f".format(r.similarity)}]\n${r.text}"
          }
      )
    }

  override fun sendAgentAction(action: AgentAction) {
    sendAction(action)
  }

  /** Blocks (via CompletableDeferred.await) until the UI resolves the FileWritePermissionDialog. */
  private fun confirmWrite(path: String, oldContent: String?, newContent: String, isPatch: Boolean): Boolean {
    val preview = buildWritePreview(oldContent, newContent, isPatch)
    val action = AskFileWritePermissionAction(path = path, preview = preview)
    sendAction(action)
    // No ALWAYS_ALLOW for file writes by design — every call re-prompts.
    return runBlocking { action.result.await() } == PermissionResult.ALLOW_ONCE
  }

  private fun rootDocOrNull(): DocumentFile? = projectRootUri?.let { DocumentFile.fromTreeUri(context, it) }

  private fun noProjectError() = mapOf("error" to "No project folder selected", "status" to "failed")
}

fun buildWritePreview(oldContent: String?, newContent: String, isPatch: Boolean): String = buildString {
  if (isPatch) {
    appendLine("--- old ---")
    appendLine(oldContent.orEmpty().take(PREVIEW_MAX_CHARS))
    appendLine()
    appendLine("+++ new +++")
    appendLine(newContent.take(PREVIEW_MAX_CHARS))
  } else if (oldContent == null) {
    appendLine("(new file)")
    append(newContent.take(PREVIEW_MAX_CHARS))
  } else {
    appendLine("--- current content (${oldContent.length} chars) ---")
    appendLine(oldContent.take(PREVIEW_MAX_CHARS))
    appendLine()
    appendLine("+++ new content (${newContent.length} chars) +++")
    append(newContent.take(PREVIEW_MAX_CHARS))
  }
}
