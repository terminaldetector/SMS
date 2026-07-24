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

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saturnmask.gallery.data.DataStoreRepository
import com.saturnmask.gallery.data.DownloadRepository
import com.saturnmask.gallery.data.ModelDownloadStatus
import com.saturnmask.gallery.data.ModelDownloadStatusType
import com.saturnmask.gallery.domain.rag.EMBEDDING_GEMMA_MODEL
import com.saturnmask.gallery.domain.rag.EMBEDDING_GEMMA_TOKENIZER_DATA_FILE_NAME
import com.saturnmask.gallery.domain.rag.EmbedderSettingsStore
import com.saturnmask.gallery.domain.rag.EmbedderSource
import com.saturnmask.gallery.domain.rag.FallbackTextEmbedder
import com.saturnmask.gallery.domain.rag.RagDocumentInfo
import com.saturnmask.gallery.domain.rag.RagDocumentPriority
import com.saturnmask.gallery.domain.rag.RagEngine
import com.saturnmask.gallery.domain.rag.RagMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RagManagerUiState(
  val documents: List<RagDocumentInfo> = emptyList(),
  val importing: Boolean = false,
  // Non-null exactly while `importing` is true. `total == 0` means "still parsing/chunking, no
  // per-chunk progress yet" — the UI should show an indeterminate bar for that brief window and
  // switch to determinate once the first onProgress(0, total) callback lands.
  val importProgress: ImportProgress? = null,
  val error: String? = null,
  // Mode new documents are added under (via the sheet's "Add" button or the chat "+" menu's
  // "Attach file"). Doesn't affect already-indexed documents — see RagDocumentInfo.scope.
  val selectedMode: RagMode = RagMode.STATIC,
  val embeddingModelDownload: EmbeddingModelDownloadUiState = EmbeddingModelDownloadUiState(),
  val embedderSource: EmbedderSource = EmbedderSource.BUILT_IN,
  val customModelFileName: String? = null,
  val customTokenizerFileName: String? = null,
  val customEmbedderImporting: Boolean = false,
  val customEmbedderError: String? = null,
) {
  /** Convenience for UI: 0f..1f, or null while progress is still indeterminate. */
  val importFraction: Float?
    get() = importProgress?.let { if (it.total <= 0) null else it.done.toFloat() / it.total }
}

data class ImportProgress(val done: Int, val total: Int)

data class EmbeddingModelDownloadUiState(
  val status: ModelDownloadStatusType = ModelDownloadStatusType.NOT_DOWNLOADED,
  val receivedBytes: Long = 0L,
  val totalBytes: Long = 0L,
  val errorMessage: String? = null,
)

@HiltViewModel
class RagManagerViewModel
@Inject
constructor(
  private val ragEngine: RagEngine,
  private val downloadRepository: DownloadRepository,
  private val dataStoreRepository: DataStoreRepository,
  private val fallbackTextEmbedder: FallbackTextEmbedder,
  private val embedderSettingsStore: EmbedderSettingsStore,
  @ApplicationContext private val context: Context,
) : ViewModel() {

  private val _uiState = MutableStateFlow(RagManagerUiState())
  val uiState = _uiState.asStateFlow()

  // The current chat's session ID, so dynamic (session-scoped) documents are attributed to the
  // right chat. Set once per composition from AgentChatScreen.kt via setSessionId — a plain var,
  // not a constructor param, because this ViewModel is a Hilt-scoped singleton-per-screen created
  // before the caller knows the session ID.
  private var sessionId: String? = null

  init {
    refresh()
    refreshEmbeddingModelDownloadStatus()
    refreshEmbedderSourceState()
  }

  private fun refreshEmbedderSourceState() {
    val modelPath = embedderSettingsStore.getCustomModelPath()
    val tokenizerPath = embedderSettingsStore.getCustomTokenizerPath()
    _uiState.update {
      it.copy(
        embedderSource = embedderSettingsStore.getSelectedSource(),
        customModelFileName = modelPath?.let { p -> File(p).name },
        customTokenizerFileName = tokenizerPath?.let { p -> File(p).name },
      )
    }
  }

  /** No-ops if [sessionId] hasn't changed, so calling this every recomposition is cheap. */
  fun setSessionId(sessionId: String) {
    if (this.sessionId == sessionId) return
    this.sessionId = sessionId
    refresh()
  }

  fun setMode(mode: RagMode) {
    _uiState.update { it.copy(selectedMode = mode) }
  }

  fun refresh() {
    viewModelScope.launch {
      _uiState.update { it.copy(documents = ragEngine.listDocuments(sessionId = sessionId)) }
    }
  }

  fun importDocument(uri: Uri, onResult: (Result<RagDocumentInfo>) -> Unit = {}) {
    importDocumentAs(uri, mode = _uiState.value.selectedMode, onResult = onResult)
  }

  /**
   * Always imports as [RagMode.DYNAMIC] (scoped to the current chat session), regardless of the
   * library sheet's [RagManagerUiState.selectedMode] toggle. Use this for the chat's own inline
   * "+ -> Attach file" flow — attaching a file directly to a message is inherently a per-chat
   * action and must not silently fall back to whatever mode the separate library-management sheet
   * last happened to be set to (that was the bug: in-chat attachments were getting folded
   * permanently into the persistent static index). Mirrors the explicit
   * `mode = RagMode.DYNAMIC` hardcode already used by `CoderProjectIndexer` for the same reason.
   */
  fun importDocumentForChat(uri: Uri, onResult: (Result<RagDocumentInfo>) -> Unit = {}) {
    importDocumentAs(uri, mode = RagMode.DYNAMIC, onResult = onResult)
  }

  private fun importDocumentAs(
    uri: Uri,
    mode: RagMode,
    onResult: (Result<RagDocumentInfo>) -> Unit,
  ) {
    viewModelScope.launch {
      _uiState.update { it.copy(importing = true, importProgress = null, error = null) }
      val displayName = queryDisplayName(uri) ?: "document"
      val result =
        ragEngine.addDocument(
          uri = uri,
          displayName = displayName,
          mode = mode,
          sessionId = if (mode == RagMode.DYNAMIC) sessionId else null,
        ) { done, total ->
          // onProgress fires from a background dispatcher (see RagEngineImpl) — StateFlow.update
          // is thread-safe, so no extra dispatching needed here.
          _uiState.update { it.copy(importProgress = ImportProgress(done, total)) }
        }
      result
        .onSuccess { refresh() }
        .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Import failed") } }
      _uiState.update { it.copy(importing = false, importProgress = null) }
      onResult(result)
    }
  }

  fun removeDocument(documentId: String) {
    viewModelScope.launch {
      ragEngine.removeDocument(documentId)
      refresh()
    }
  }

  fun setDocumentPriority(documentId: String, priority: RagDocumentPriority) {
    viewModelScope.launch {
      ragEngine.setDocumentPriority(documentId, priority)
      refresh()
    }
  }

  /** Seeds initial download-status state by checking whether the files already exist on disk. */
  private fun refreshEmbeddingModelDownloadStatus() {
    viewModelScope.launch(Dispatchers.IO) {
      val status = if (embeddingModelFilesExist()) ModelDownloadStatusType.SUCCEEDED
      else ModelDownloadStatusType.NOT_DOWNLOADED
      _uiState.update {
        it.copy(embeddingModelDownload = EmbeddingModelDownloadUiState(status = status))
      }
    }
  }

  private fun embeddingModelFilesExist(): Boolean {
    val tokenizerDataFile =
      EMBEDDING_GEMMA_MODEL.getExtraDataFile(EMBEDDING_GEMMA_TOKENIZER_DATA_FILE_NAME) ?: return false
    val modelFile =
      File(EMBEDDING_GEMMA_MODEL.getPath(context, EMBEDDING_GEMMA_MODEL.downloadFileName))
    val tokenizerFile =
      File(EMBEDDING_GEMMA_MODEL.getPath(context, tokenizerDataFile.downloadFileName))
    return modelFile.exists() && tokenizerFile.exists()
  }

  /**
   * Downloads the EmbeddingGemma weights + tokenizer via the same [DownloadRepository] the chat
   * models use. Requires a HuggingFace access token already saved (via the burger menu's
   * "HuggingFace access token" entry or the OAuth flow) — both repos are Gemma-license-gated. This
   * doesn't launch the full interactive token-acquisition flow itself; if no valid token is saved,
   * it reports an error pointing the user at where to add one.
   */
  fun downloadEmbeddingModel() {
    val tokenData = dataStoreRepository.readAccessTokenData()
    if (tokenData == null || tokenData.accessToken.isEmpty()) {
      _uiState.update {
        it.copy(
          embeddingModelDownload =
            EmbeddingModelDownloadUiState(
              status = ModelDownloadStatusType.FAILED,
              errorMessage =
                "Save a HuggingFace access token first (see \"HuggingFace access token\" in the " +
                  "app's menu) — embeddinggemma-300m is a gated model.",
            )
        )
      }
      return
    }
    EMBEDDING_GEMMA_MODEL.accessToken = tokenData.accessToken
    downloadRepository.downloadModel(
      task = null,
      model = EMBEDDING_GEMMA_MODEL,
      onStatusUpdated = { _, status -> onEmbeddingModelStatusUpdated(status) },
    )
  }

  fun cancelEmbeddingModelDownload() {
    downloadRepository.cancelDownloadModel(EMBEDDING_GEMMA_MODEL)
  }

  private fun onEmbeddingModelStatusUpdated(status: ModelDownloadStatus) {
    // DownloadRepository reports 0/0 on SUCCEEDED (see DefaultDownloadRepository's
    // WorkInfo.State.SUCCEEDED branch) — substitute the known total for a clean 100% display.
    val succeeded = status.status == ModelDownloadStatusType.SUCCEEDED
    _uiState.update {
      it.copy(
        embeddingModelDownload =
          EmbeddingModelDownloadUiState(
            status = status.status,
            receivedBytes = if (succeeded) EMBEDDING_GEMMA_MODEL.totalBytes else status.receivedBytes,
            totalBytes = if (succeeded) EMBEDDING_GEMMA_MODEL.totalBytes else status.totalBytes,
            errorMessage = status.errorMessage.ifEmpty { null },
          )
      )
    }
    if (succeeded) {
      // Undoes FallbackTextEmbedder's permanent degrade-to-hashing from before this download
      // existed, so the next RAG search actually tries the neural embedder instead of requiring
      // an app restart.
      fallbackTextEmbedder.refreshActiveEmbedder()
    }
  }

  fun useBuiltInEmbedder() {
    embedderSettingsStore.setSelectedSource(EmbedderSource.BUILT_IN)
    fallbackTextEmbedder.refreshActiveEmbedder()
    refreshEmbedderSourceState()
  }

  /**
   * Switches back to a previously-imported custom embedder without re-picking/re-copying its
   * files. No-op if none was ever imported (the "Custom" chip in the UI should only be reachable
   * once [importCustomEmbedder] has succeeded at least once).
   */
  fun useCustomEmbedder() {
    if (embedderSettingsStore.getCustomModelPath() == null) return
    if (embedderSettingsStore.getCustomTokenizerPath() == null) return
    embedderSettingsStore.setSelectedSource(EmbedderSource.CUSTOM)
    fallbackTextEmbedder.refreshActiveEmbedder()
    refreshEmbedderSourceState()
  }

  /**
   * Copies the user-picked model/tokenizer files into app-private storage (mirroring
   * `ModelImportDialog.kt`'s LLM-import pattern — a copy is more reliable across app restarts than
   * relying on a persisted content-uri permission), saves their paths, and switches RAG over to
   * this custom embedder. [dimension] is user-entered since a `.tflite` file's output tensor shape
   * isn't introspected here — see `TfLiteTextEmbedder`'s doc comment on unverified tensor layout.
   */
  fun importCustomEmbedder(modelUri: Uri, tokenizerUri: Uri, dimension: Int) {
    viewModelScope.launch {
      _uiState.update { it.copy(customEmbedderImporting = true, customEmbedderError = null) }
      val result =
        withContext(Dispatchers.IO) {
          runCatching {
            val importDir = File(context.getExternalFilesDir(null), "imports/embedder")
            importDir.mkdirs()
            val modelFile = copyUriToFile(modelUri, File(importDir, "model.tflite"))
            val tokenizerFile = copyUriToFile(tokenizerUri, File(importDir, "tokenizer.json"))
            Pair(modelFile, tokenizerFile)
          }
        }
      result
        .onSuccess { (modelFile, tokenizerFile) ->
          embedderSettingsStore.setCustomEmbedderFiles(modelFile.path, tokenizerFile.path, dimension)
          embedderSettingsStore.setSelectedSource(EmbedderSource.CUSTOM)
          fallbackTextEmbedder.refreshActiveEmbedder()
          refreshEmbedderSourceState()
        }
        .onFailure { e ->
          _uiState.update { it.copy(customEmbedderError = e.message ?: "Import failed") }
        }
      _uiState.update { it.copy(customEmbedderImporting = false) }
    }
  }

  fun clearCustomEmbedder() {
    embedderSettingsStore.clearCustomEmbedder()
    fallbackTextEmbedder.refreshActiveEmbedder()
    refreshEmbedderSourceState()
  }

  private fun copyUriToFile(uri: Uri, destination: File): File {
    val input =
      requireNotNull(context.contentResolver.openInputStream(uri)) { "Could not open picked file" }
    input.use { stream -> FileOutputStream(destination).use { output -> stream.copyTo(output) } }
    return destination
  }

  private fun queryDisplayName(uri: Uri): String? {
    return context.contentResolver
      .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor ->
        if (cursor.moveToFirst()) {
          val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (idx >= 0) cursor.getString(idx) else null
        } else {
          null
        }
      }
  }
}
