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
 * Character-based overlapping chunker used by [RagEngineImpl].
 *
 * Exposed as a distilled unit for Chatter Triangle hosts that want the same chunk boundaries
 * without pulling the full RAG persistence stack.
 */
object TextChunker {
  const val DEFAULT_CHUNK_SIZE = 512
  const val DEFAULT_CHUNK_OVERLAP = 50

  fun chunk(
    text: String,
    chunkSize: Int = DEFAULT_CHUNK_SIZE,
    chunkOverlap: Int = DEFAULT_CHUNK_OVERLAP,
  ): List<String> {
    if (text.isEmpty()) return emptyList()
    val step = (chunkSize - chunkOverlap).coerceAtLeast(1)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
      val end = (start + chunkSize).coerceAtMost(text.length)
      chunks.add(text.substring(start, end))
      if (end == text.length) break
      start += step
    }
    return chunks
  }
}
