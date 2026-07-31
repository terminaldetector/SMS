/*
 * Copyright 2026 Saturn Mask / GEDGE distillation
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

package com.saturnmask.edge.distilled.settings

import com.saturnmask.gallery.domain.rag.EmbedderSettingsStore
import com.saturnmask.gallery.domain.rag.EmbedderSource
import com.saturnmask.gallery.domain.websearch.WebSearchProvider
import com.saturnmask.gallery.domain.websearch.WebSearchSettingsStore

/**
 * Host port for HuggingFace / model-download credentials. Gallery binds this to its DataStore
 * protos; Chatter Triangle can use EncryptedSharedPreferences or its own secure store.
 */
interface AccessTokenStore {
  fun readAccessToken(): String?

  fun saveAccessToken(accessToken: String, refreshToken: String = "", expiresAt: Long = 0L)

  fun clearAccessToken()
}

/**
 * Distilled settings façade (no UI): HF token, embedder selection, web-search provider/key.
 * Model download/allowlist management stays host-owned (WorkManager + catalog).
 */
class SettingsManager(
  private val accessTokenStore: AccessTokenStore,
  private val embedderSettingsStore: EmbedderSettingsStore,
  private val webSearchSettingsStore: WebSearchSettingsStore,
) {

  // --- HuggingFace ---

  fun getHuggingFaceAccessToken(): String? = accessTokenStore.readAccessToken()

  fun setHuggingFaceAccessToken(token: String, refreshToken: String = "", expiresAt: Long = 0L) {
    accessTokenStore.saveAccessToken(token, refreshToken, expiresAt)
  }

  fun clearHuggingFaceAccessToken() = accessTokenStore.clearAccessToken()

  // --- Embedder ---

  fun getEmbedderSource(): EmbedderSource = embedderSettingsStore.getSelectedSource()

  fun setEmbedderSource(source: EmbedderSource) = embedderSettingsStore.setSelectedSource(source)

  fun getCustomEmbedderModelPath(): String? = embedderSettingsStore.getCustomModelPath()

  fun getCustomEmbedderTokenizerPath(): String? = embedderSettingsStore.getCustomTokenizerPath()

  fun setCustomEmbedder(
    modelPath: String,
    tokenizerPath: String,
    dimension: Int = 768,
  ) {
    embedderSettingsStore.setCustomEmbedderFiles(
      modelPath = modelPath,
      tokenizerPath = tokenizerPath,
      dimension = dimension,
    )
    embedderSettingsStore.setSelectedSource(EmbedderSource.CUSTOM)
  }

  // --- Web search ---

  fun getWebSearchProvider(): WebSearchProvider = webSearchSettingsStore.getSelectedProvider()

  fun setWebSearchProvider(provider: WebSearchProvider) =
    webSearchSettingsStore.setSelectedProvider(provider)

  fun hasWebSearchApiKey(provider: WebSearchProvider = getWebSearchProvider()): Boolean =
    webSearchSettingsStore.hasApiKey(provider)

  fun setWebSearchApiKey(provider: WebSearchProvider, apiKey: String) =
    webSearchSettingsStore.setApiKey(provider, apiKey)

  fun clearWebSearchApiKey(provider: WebSearchProvider) =
    webSearchSettingsStore.clearApiKey(provider)
}
