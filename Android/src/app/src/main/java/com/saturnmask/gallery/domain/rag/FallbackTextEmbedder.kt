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
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AGFallbackEmbedder"

/**
 * Tries [active] (a neural embedder — either the built-in EmbeddingGemma download or a
 * user-supplied third-party model, per [EmbedderSettingsStore]); if it's not ready — model not
 * downloaded/imported yet, OOM, unsupported device, whatever — silently falls back to [fallback]
 * (the hashing embedder) so RAG never breaks, it just degrades in quality. Once [active] fails it
 * stops retrying for the rest of the process lifetime (until [refreshActiveEmbedder] is called
 * again), to avoid retrying a doomed model load on every single chunk.
 *
 * [id]/[dimension] reflect whichever embedder actually served the *last* call, so
 * [RagEngineImpl]'s stale-index guard can detect a switch (hashing<->neural, or built-in<->custom)
 * against the persisted index.
 */
@Singleton
class FallbackTextEmbedder
@Inject
constructor(
  private val builtIn: TfLiteTextEmbedder,
  private val fallback: HashingTextEmbedder,
  private val embedderSettingsStore: EmbedderSettingsStore,
  @ApplicationContext private val context: Context,
) : TextEmbedder {

  @Volatile private var active: TextEmbedder = builtIn
  @Volatile private var primaryUnavailable = false

  init {
    refreshActiveEmbedder()
  }

  // Must be live-computed, not a `val` snapshot at construction time — otherwise RagEngineImpl's
  // stale-index guard (persisted.embedderId != embedder.id) can never detect a hashing<->neural or
  // built-in<->custom switch.
  override val dimension: Int
    get() = if (primaryUnavailable) fallback.dimension else active.dimension

  override val id: String
    get() = if (primaryUnavailable) fallback.id else active.id

  override suspend fun embed(text: String, kind: EmbeddingKind): FloatArray {
    if (!primaryUnavailable) {
      try {
        return active.embed(text, kind)
      } catch (t: Throwable) {
        Log.w(TAG, "Neural embedder unavailable, falling back to hashing embedder", t)
        primaryUnavailable = true
      }
    }
    val raw = fallback.embed(text, kind)
    // Read `dimension` only now, after primaryUnavailable is settled to true above — so this is
    // always fallback.dimension here, consistent with the `id` a caller reads for this same call.
    // (Reading it before the catch block, while it could still resolve to active.dimension, would
    // let one transitional chunk get padded to a different size than every chunk after it within
    // the same document — silently breaking cosine similarity between them.)
    val targetDimension = dimension
    return when {
      raw.size == targetDimension -> raw
      raw.size > targetDimension -> raw.copyOf(targetDimension)
      else -> raw.copyOf(targetDimension) // zero-padded
    }
  }

  /**
   * Re-reads [EmbedderSettingsStore] and repoints [active] at either the built-in embedder or a
   * user-imported custom one, and resets [primaryUnavailable] so the next [embed] call retries
   * fresh. Call this after: a successful built-in model download (`RagManagerViewModel
   * .downloadEmbeddingModel`'s `onEmbeddingModelStatusUpdated`), after the user picks/clears a
   * custom embedder (`RagManagerViewModel.importCustomEmbedder`/`useBuiltInEmbedder`/
   * `clearCustomEmbedder`), and once at construction time so a previously-selected custom embedder
   * is restored on process start.
   */
  fun refreshActiveEmbedder() {
    // Applies regardless of which source ends up selected below — builtIn is a Hilt singleton
    // that can't be torn down and reconstructed the way the custom embedder below is, so its
    // accelerator has to be changed in place.
    val accelerator = embedderSettingsStore.getAccelerator()
    builtIn.reconfigureAccelerator(accelerator)
    active =
      if (embedderSettingsStore.getSelectedSource() == EmbedderSource.CUSTOM) {
        val modelPath = embedderSettingsStore.getCustomModelPath()
        val tokenizerPath = embedderSettingsStore.getCustomTokenizerPath()
        if (modelPath != null && tokenizerPath != null) {
          TfLiteTextEmbedder(
            context = context,
            modelFile = File(modelPath),
            tokenizerFile = File(tokenizerPath),
            dimension = embedderSettingsStore.getCustomDimension(),
            id = "custom-embedder",
            queryPrefix = "",
            documentPrefix = "",
            accelerator = accelerator,
          )
        } else {
          builtIn
        }
      } else {
        builtIn
      }
    primaryUnavailable = false
  }
}
