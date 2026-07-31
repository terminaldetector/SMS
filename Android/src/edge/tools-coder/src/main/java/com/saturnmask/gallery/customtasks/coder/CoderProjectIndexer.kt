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
import com.saturnmask.gallery.domain.rag.RagEngine
import com.saturnmask.gallery.domain.rag.RagMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val IGNORED_DIR_NAMES =
  setOf(".git", "node_modules", "build", ".gradle", ".idea", "dist", "target", ".venv", "__pycache__")
private const val MAX_INDEXABLE_FILE_SIZE_BYTES = 512 * 1024L // skip large binaries/lockfiles

/**
 * Walks a picked project's [DocumentFile] tree and indexes it via [RagEngine], powering the
 * Coder tab's `searchInProject` tool.
 *
 * Uses [RagMode.DYNAMIC], not STATIC: STATIC documents have no `sessionId` scoping in
 * [RagEngine.search]/[RagEngine.listDocuments] — indexing hundreds of project files as STATIC
 * would leak them into the user's regular `ragSearch` results and RAG document list, with no bulk
 * remove-by-scope API (only `clear()`, wiping everything). DYNAMIC's `sessionId` is an arbitrary
 * partition key unrelated to actual chat sessions, so a stable per-project key isolates project
 * files from the user's own RAG documents cleanly. Trade-off: DYNAMIC lives only in memory, so
 * re-indexing is needed after process death — see [CoderViewModel.reindexIfNeeded].
 */
@Singleton
class CoderProjectIndexer @Inject constructor(
  @ApplicationContext private val context: Context,
  private val ragEngine: RagEngine,
) {

  suspend fun indexProject(rootUri: Uri, sessionId: String, onProgress: (filesDone: Int, filesTotal: Int) -> Unit) {
    val root = DocumentFile.fromTreeUri(context, rootUri) ?: return
    val files = mutableListOf<Pair<DocumentFile, String>>() // (file, relative path)
    collect(root, "", files)
    val total = files.size
    onProgress(0, total)
    files.forEachIndexed { i, (doc, relPath) ->
      runCatching {
        ragEngine.addDocument(uri = doc.uri, displayName = relPath, mode = RagMode.DYNAMIC, sessionId = sessionId)
      }
      onProgress(i + 1, total)
    }
  }

  private fun collect(dir: DocumentFile, prefix: String, out: MutableList<Pair<DocumentFile, String>>) {
    for (child in dir.listFiles()) {
      val name = child.name ?: continue
      if (child.isDirectory) {
        if (name in IGNORED_DIR_NAMES) continue
        collect(child, "$prefix$name/", out)
      } else if (child.isFile && child.length() in 1..MAX_INDEXABLE_FILE_SIZE_BYTES) {
        out.add(child to "$prefix$name")
      }
    }
  }
}
