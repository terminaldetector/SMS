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

package com.saturnmask.gallery.domain.rag

import android.net.Uri

/**
 * Where a document's chunks live: [STATIC] is the persisted, cross-chat index (`rag_index.json`,
 * survives app restarts); [DYNAMIC] is scoped to a single chat session (in-memory only, discarded
 * when the process dies or the session is deleted). [RagEngine.search] and
 * [RagEngine.listDocuments] return the union of both when a `sessionId` is supplied.
 */
enum class RagMode {
  STATIC,
  DYNAMIC,
}

/**
 * How strongly a document's chunks should be favored in [RagEngine.search], on top of raw cosine
 * similarity. Settable manually (see [RagEngine.setDocumentPriority]) and set automatically —
 * documents attached as [RagMode.DYNAMIC] default to [HIGH] (the user just explicitly attached it
 * to this conversation, so it's clearly relevant right now), [RagMode.STATIC] library documents
 * default to [NORMAL]. [RagEngineImpl.search] also applies a small, capped automatic boost based
 * on how often a document's chunks have actually been retrieved before, independent of this field.
 */
enum class RagDocumentPriority {
  LOW,
  NORMAL,
  HIGH,
}

/**
 * Local, on-device RAG (retrieval-augmented generation) engine.
 *
 * Static documents are persisted to app-private storage (`filesDir/rag_index.json`) so they
 * survive process death; dynamic documents are in-memory only, scoped to a single chat session.
 */
interface RagEngine {

  /**
   * Reads [uri], chunks it, embeds every chunk, and indexes it under [displayName].
   *
   * [mode] selects [RagMode.STATIC] (persisted, shared across all chats — the default) or
   * [RagMode.DYNAMIC] (in-memory only, scoped to [sessionId], required when [mode] is DYNAMIC).
   *
   * [onProgress] is called after each chunk is embedded — `(chunksDone, chunksTotal)` — so the UI
   * can show a real progress bar instead of an indeterminate spinner. Embedding is the slow part
   * (especially with a neural [TextEmbedder] vs. the hashing fallback), so per-chunk granularity
   * is what actually matters here; chunking/parsing itself is comparatively instant. Defaults to
   * a no-op so existing call sites don't need to change.
   */
  suspend fun addDocument(
    uri: Uri,
    displayName: String,
    mode: RagMode = RagMode.STATIC,
    sessionId: String? = null,
    onProgress: (chunksDone: Int, chunksTotal: Int) -> Unit = { _, _ -> },
  ): Result<RagDocumentInfo>

  /**
   * Removes a previously added document and all of its chunks from the index, whether it's
   * static or dynamic — document IDs are UUIDs, unique across both stores, so no [RagMode]/
   * session disambiguation is needed here.
   */
  suspend fun removeDocument(documentId: String)

  /** Wipes the entire static index. Does not touch any session's dynamic documents. */
  suspend fun clear()

  /**
   * Manually sets [documentId]'s priority (see [RagDocumentPriority]) — works whether the document
   * is static or dynamic; a no-op if no document with that id exists.
   */
  suspend fun setDocumentPriority(documentId: String, priority: RagDocumentPriority)

  /**
   * Lists indexed documents (without their chunk contents, just metadata): static documents
   * always, plus [sessionId]'s dynamic documents if non-null.
   */
  suspend fun listDocuments(sessionId: String? = null): List<RagDocumentInfo>

  /**
   * Returns the [topK] most similar chunks to [query], searched across static documents plus
   * [sessionId]'s dynamic documents (if non-null) — static and dynamic results are ranked
   * together in one list, not returned as separate exclusive modes.
   */
  suspend fun search(query: String, topK: Int = 5, sessionId: String? = null): List<RagSearchResult>
}

data class RagDocumentInfo(
  val id: String,
  val name: String,
  val chunkCount: Int,
  val sizeBytes: Long,
  val addedAtMillis: Long,
  /**
   * First ~150 characters of the document's first chunk, whitespace-collapsed. Used to give the
   * model (and the UI) a rough sense of what's in a document without a full RAG search or a
   * separate summarization pass — see the "RAG DOCUMENTS AVAILABLE" section this feeds in
   * AgentChatTaskModule.kt.
   */
  val previewText: String,
  /** [RagMode.STATIC] (persisted, shared) or [RagMode.DYNAMIC] (this chat session only). */
  val scope: RagMode = RagMode.STATIC,
  /** See [RagDocumentPriority]. */
  val priority: RagDocumentPriority = RagDocumentPriority.NORMAL,
)

data class RagSearchResult(
  val documentId: String,
  val documentName: String,
  val chunkIndex: Int,
  val text: String,
  /** Cosine similarity in [0, 1], 1 = identical direction. */
  val similarity: Float,
)
