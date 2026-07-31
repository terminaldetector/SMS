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

import android.content.Context
import android.net.Uri
import android.util.Log
import com.saturnmask.gallery.domain.rag.extract.DocumentTextExtractorRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AGRagEngine"
private const val CHUNK_SIZE = 512
private const val CHUNK_OVERLAP = 50
private const val INDEX_FILE_NAME = "rag_index.json"

// Caps how many onProgress calls a single addDocument() emits, regardless of chunk count, so a
// huge document (thousands of chunks) doesn't flood the UI thread with StateFlow updates. A short
// document just reports every chunk (step ends up being 1).
private const val MAX_PROGRESS_UPDATES = 30
private const val PREVIEW_LENGTH = 150

internal data class PersistedChunk(val index: Int, val text: String, val vector: FloatArray) {
  override fun equals(other: Any?) = this === other
  override fun hashCode() = System.identityHashCode(this)
}

internal data class PersistedDocument(
  val id: String,
  val name: String,
  val sizeBytes: Long,
  val addedAtMillis: Long,
  val chunks: List<PersistedChunk>,
)

internal data class PersistedIndex(val embedderId: String?, val documents: List<PersistedDocument>)

@OptIn(ExperimentalStdlibApi::class)
@Singleton
class RagEngineImpl
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val embedder: TextEmbedder,
  private val textExtractorRegistry: DocumentTextExtractorRegistry,
) : RagEngine {

  private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
  private val indexAdapter = moshi.adapter<PersistedIndex>()
  private val lock = ReentrantLock()

  private val indexFile: File
    get() = File(context.filesDir, INDEX_FILE_NAME)

  private var cache: MutableList<PersistedDocument>? = null

  // Dynamic (session-scoped) documents: in-memory only, never written to indexFile. Deliberately
  // not persisted — a dynamic doc is meant to be discarded on process death or session deletion,
  // which falls out for free from living only in this map (see RagEngine.kt's RagMode doc).
  private val dynamicDocs = ConcurrentHashMap<String, MutableList<PersistedDocument>>()

  @Volatile var staleIndexDetected: Boolean = false
    private set

  private fun loadLocked(): MutableList<PersistedDocument> {
    cache?.let {
      return it
    }
    val persisted =
      if (indexFile.exists()) {
        runCatching { indexAdapter.fromJson(indexFile.readText()) }.getOrNull()
      } else {
        null
      }

    val loaded =
      if (persisted != null && persisted.embedderId != null && persisted.embedderId != embedder.id) {
        Log.w(
          TAG,
          "RAG index was built with embedder '${persisted.embedderId}', active embedder is " +
            "'${embedder.id}'. Ignoring ${persisted.documents.size} stale document(s) until " +
            "they're re-added.",
        )
        staleIndexDetected = true
        mutableListOf()
      } else {
        persisted?.documents?.toMutableList() ?: mutableListOf()
      }
    cache = loaded
    return loaded
  }

  private fun persistLocked(documents: List<PersistedDocument>) {
    cache = documents.toMutableList()
    staleIndexDetected = false
    runCatching {
        indexFile.writeText(indexAdapter.toJson(PersistedIndex(embedder.id, documents)))
      }
      .onFailure { Log.e(TAG, "Failed to persist RAG index", it) }
  }

  override suspend fun addDocument(
    uri: Uri,
    displayName: String,
    mode: RagMode,
    sessionId: String?,
    onProgress: (chunksDone: Int, chunksTotal: Int) -> Unit,
  ): Result<RagDocumentInfo> =
    withContext(Dispatchers.IO) {
      runCatching {
        val text = textExtractorRegistry.extractText(context, uri, displayName)
        if (text.isBlank()) error("$displayName has no extractable text")

        val chunkTexts = chunk(text)
        val total = chunkTexts.size
        // Report 0/total up front so the UI can switch from "starting…" to a real bar
        // immediately, even before the (potentially slow, e.g. neural-model-init) first chunk
        // finishes embedding.
        onProgress(0, total)
        val progressStep = (total / MAX_PROGRESS_UPDATES).coerceAtLeast(1)

        val chunks =
          chunkTexts.mapIndexed { i, chunkText ->
            val vector = embedder.embed(chunkText, EmbeddingKind.DOCUMENT)
            val done = i + 1
            if (done % progressStep == 0 || done == total) {
              onProgress(done, total)
            }
            PersistedChunk(index = i, text = chunkText, vector = vector)
          }

        val doc =
          PersistedDocument(
            id = UUID.randomUUID().toString(),
            name = displayName,
            sizeBytes = text.length.toLong(),
            addedAtMillis = System.currentTimeMillis(),
            chunks = chunks,
          )

        val scope =
          if (mode == RagMode.DYNAMIC && sessionId != null) {
            dynamicDocs.getOrPut(sessionId) { mutableListOf() }.add(doc)
            RagMode.DYNAMIC
          } else {
            lock.withLock {
              val docs = loadLocked()
              docs.add(doc)
              persistLocked(docs)
            }
            RagMode.STATIC
          }

        doc.toInfo(scope)
      }
    }

  override suspend fun removeDocument(documentId: String) =
    withContext(Dispatchers.IO) {
      val removedFromStatic =
        lock.withLock {
          val docs = loadLocked()
          val before = docs.size
          docs.removeAll { it.id == documentId }
          val removed = docs.size != before
          if (removed) persistLocked(docs)
          removed
        }
      if (!removedFromStatic) {
        for (docs in dynamicDocs.values) {
          if (docs.removeAll { it.id == documentId }) break
        }
      }
    }

  override suspend fun clear() =
    withContext(Dispatchers.IO) { lock.withLock { persistLocked(emptyList()) } }

  override suspend fun listDocuments(sessionId: String?): List<RagDocumentInfo> =
    withContext(Dispatchers.IO) {
      val staticDocs = lock.withLock { loadLocked().toList() }.map { it.toInfo(RagMode.STATIC) }
      val dynamicForSession =
        sessionId?.let { dynamicDocs[it]?.toList() }.orEmpty().map { it.toInfo(RagMode.DYNAMIC) }
      staticDocs + dynamicForSession
    }

  override suspend fun search(query: String, topK: Int, sessionId: String?): List<RagSearchResult> =
    withContext(Dispatchers.Default) {
      val queryVector = embedder.embed(query, EmbeddingKind.QUERY)
      val staticDocs = lock.withLock { loadLocked().toList() }
      val dynamicForSession = sessionId?.let { dynamicDocs[it]?.toList() }.orEmpty()

      (staticDocs + dynamicForSession)
        .asSequence()
        .flatMap { doc -> doc.chunks.asSequence().map { chunk -> doc to chunk } }
        .map { (doc, chunk) ->
          RagSearchResult(
            documentId = doc.id,
            documentName = doc.name,
            chunkIndex = chunk.index,
            text = chunk.text,
            similarity = cosineSimilarity(queryVector, chunk.vector),
          )
        }
        .sortedByDescending { it.similarity }
        .take(topK)
        .toList()
    }

  private fun PersistedDocument.toInfo(scope: RagMode) =
    RagDocumentInfo(
      id = id,
      name = name,
      chunkCount = chunks.size,
      sizeBytes = sizeBytes,
      addedAtMillis = addedAtMillis,
      previewText = buildPreview(chunks.firstOrNull()?.text.orEmpty()),
      scope = scope,
    )

  private fun buildPreview(text: String): String {
    val collapsed = text.replace(Regex("\\s+"), " ").trim()
    return if (collapsed.length <= PREVIEW_LENGTH) collapsed
    else collapsed.take(PREVIEW_LENGTH).trimEnd() + "…"
  }

  /** Splits [text] into overlapping fixed-size chunks (char-based, so Cyrillic is unaffected). */
  private fun chunk(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val step = (CHUNK_SIZE - CHUNK_OVERLAP).coerceAtLeast(1)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
      val end = (start + CHUNK_SIZE).coerceAtMost(text.length)
      chunks.add(text.substring(start, end))
      if (end == text.length) break
      start += step
    }
    return chunks
  }

  private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size) {
      Log.e(TAG, "Vector size mismatch (${a.size} vs ${b.size}); treating as no match")
      return 0f
    }
    var dot = 0f
    for (i in a.indices) dot += a[i] * b[i]
    return dot.coerceIn(0f, 1f)
  }
}
