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

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

private const val EMBEDDING_DIM = 384

// Minimal RU/EN stopword list. Not exhaustive by design — this only trims the most frequent
// low-signal tokens so they don't dominate the bag-of-words vector.
private val STOPWORDS =
  setOf(
    "the", "a", "an", "and", "or", "is", "are", "was", "were", "to", "of", "in", "on", "for",
    "it", "this", "that", "with", "as", "at", "by", "be", "from",
    "и", "в", "на", "с", "не", "что", "как", "это", "к", "по", "из", "у", "о", "за", "от", "для",
    "то", "же", "но", "а", "бы", "он", "она", "они",
  )

/**
 * Zero-dependency, zero-network, zero-model-download embedder. Good enough to bootstrap semantic
 * search and used as the guaranteed fallback when a neural embedder isn't available (model not
 * downloaded yet, unsupported device, init failure, etc.) — see [FallbackTextEmbedder].
 *
 * Deterministic across runs/devices because it relies only on [String.hashCode], whose algorithm
 * is fixed by the Java language spec.
 */
@Singleton
class HashingTextEmbedder @Inject constructor() : TextEmbedder {

  override val id: String = "hashing-bow-v1"
  override val dimension: Int = EMBEDDING_DIM

  override suspend fun embed(text: String, kind: EmbeddingKind): FloatArray {
    val tokens =
      Regex("[\\p{L}\\p{N}]+")
        .findAll(text.lowercase())
        .map { it.value }
        .filter { it.length > 1 && it !in STOPWORDS }
        .toList()

    val vector = FloatArray(dimension)
    if (tokens.isEmpty()) return vector

    val freq = tokens.groupingBy { it }.eachCount()
    val totalTokens = tokens.size.toFloat()

    for ((token, count) in freq) {
      val weight = count / totalTokens
      val h = token.hashCode()
      // Spread each token across 3 positions to reduce collision noise.
      for (offset in 0..2) {
        val idx = Math.floorMod(h * (offset * 2 + 1), dimension)
        vector[idx] += weight
      }
    }

    val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
    if (norm > 0f) {
      for (i in vector.indices) vector[i] = vector[i] / norm
    }
    return vector
  }
}
