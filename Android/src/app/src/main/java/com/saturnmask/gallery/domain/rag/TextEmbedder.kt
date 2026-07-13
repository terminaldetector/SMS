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

/**
 * Turns text into a fixed-size numeric vector for semantic search.
 *
 * This is the single extension point the original [RagEngineImpl] comment pointed at: swap the
 * implementation, everything else (chunking, persistence, cosine search) stays the same.
 *
 * Implementations MUST be deterministic (same text -> same vector) and MUST return an
 * L2-normalized vector of exactly [dimension] floats, so that a plain dot product equals cosine
 * similarity in [RagEngineImpl.cosineSimilarity].
 */
interface TextEmbedder {
  /** Stable identifier persisted alongside the index so a dimension/model change is detectable. */
  val id: String

  /** Length of the vectors this embedder produces. */
  val dimension: Int

  /**
   * Embeds [text]. Must be safe to call from a background thread; may block.
   *
   * [kind] distinguishes a search query from an indexed document chunk. Asymmetric embedders
   * (like EmbeddingGemma, which uses different prompt prefixes for each) use it to improve
   * retrieval quality; symmetric embedders (like [HashingTextEmbedder]) can ignore it.
   */
  suspend fun embed(text: String, kind: EmbeddingKind = EmbeddingKind.DOCUMENT): FloatArray
}

enum class EmbeddingKind {
  QUERY,
  DOCUMENT,
}
