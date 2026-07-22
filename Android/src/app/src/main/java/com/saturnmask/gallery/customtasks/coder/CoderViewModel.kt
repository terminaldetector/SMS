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
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saturnmask.gallery.customtasks.agentchat.getDisplayName
import com.saturnmask.gallery.domain.rag.RagEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CoderUiState(
  val projectRootUri: Uri? = null,
  val projectDisplayName: String = "",
  val indexing: Boolean = false,
  val indexProgressDone: Int = 0,
  val indexProgressTotal: Int = 0,
)

@HiltViewModel
class CoderViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val coderSettingsStore: CoderSettingsStore,
  private val indexer: CoderProjectIndexer,
  private val ragEngine: RagEngine,
) : ViewModel() {

  private val _uiState =
    MutableStateFlow(
      CoderUiState(
        projectRootUri = coderSettingsStore.getProjectRootUri(),
        projectDisplayName = coderSettingsStore.getProjectDisplayName(),
      )
    )
  val uiState = _uiState.asStateFlow()

  fun sessionIdFor(uri: Uri): String = "coder_project_${uri.toString().hashCode()}"

  /**
   * Reindexes silently if this project's DYNAMIC RAG docs are empty (e.g. a fresh process after
   * an app restart; DYNAMIC docs don't survive process death by design — see
   * [CoderProjectIndexer]). No-ops if no project is picked or a reindex is already running. Safe
   * to call on every composition — the emptiness check makes repeat calls cheap no-ops.
   */
  fun reindexIfNeeded() {
    val uri = _uiState.value.projectRootUri ?: return
    if (_uiState.value.indexing) return
    viewModelScope.launch(Dispatchers.IO) {
      if (ragEngine.listDocuments(sessionId = sessionIdFor(uri)).isEmpty()) reindex()
    }
  }

  fun pickProjectFolder(uri: Uri) {
    context.contentResolver.takePersistableUriPermission(
      uri,
      Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
    val displayName = getDisplayName(context, uri)
    coderSettingsStore.setProjectRoot(uri, displayName)
    _uiState.update { it.copy(projectRootUri = uri, projectDisplayName = displayName) }
    reindex()
  }

  fun reindex() {
    val uri = _uiState.value.projectRootUri ?: return
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update { it.copy(indexing = true, indexProgressDone = 0, indexProgressTotal = 0) }
      indexer.indexProject(rootUri = uri, sessionId = sessionIdFor(uri)) { done, total ->
        _uiState.update { it.copy(indexProgressDone = done, indexProgressTotal = total) }
      }
      _uiState.update { it.copy(indexing = false) }
    }
  }
}
