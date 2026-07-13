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

package com.saturnmask.gallery.domain.websearch

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes to whichever [WebSearchProvider] the user picked in `WebSearchSettingsBottomSheet`.
 * [BraveWebSearchEngine] and [SerperWebSearchEngine] are plain Hilt-injectable singletons (no
 * `@Binds` of their own) — this is the only [WebSearchEngine] the rest of the app ever sees, so
 * adding a third provider means adding one more `when` branch here, not touching every call site.
 *
 * With [WebSearchProvider.NONE] selected (the default) or no key set for the chosen provider,
 * [search] fails with a message telling the model/user to configure one — per the roadmap, there's
 * deliberately no default provider that "just works" out of the box.
 */
@Singleton
class SelectingWebSearchEngine
@Inject
constructor(
  private val settingsStore: WebSearchSettingsStore,
  private val braveWebSearchEngine: BraveWebSearchEngine,
  private val serperWebSearchEngine: SerperWebSearchEngine,
) : WebSearchEngine {

  override suspend fun search(query: String, maxResults: Int): Result<List<WebSearchResult>> =
    when (settingsStore.getSelectedProvider()) {
      WebSearchProvider.BRAVE -> braveWebSearchEngine.search(query, maxResults)
      WebSearchProvider.SERPER -> serperWebSearchEngine.search(query, maxResults)
      WebSearchProvider.NONE ->
        Result.failure(
          IllegalStateException(
            "Web search isn't configured. Open Settings > Web Search and pick a provider."
          )
        )
    }
}

/** Standard Hilt binding: makes [WebSearchEngine] injectable (e.g. into a settings ViewModel). */
@Module
@InstallIn(SingletonComponent::class)
abstract class WebSearchEngineModule {
  @Binds abstract fun bindWebSearchEngine(impl: SelectingWebSearchEngine): WebSearchEngine
}

/**
 * Same pattern as `RagEngineEntryPoint` in RagEngineModule.kt: [AgentToolsImpl] isn't
 * Hilt-constructed, so it pulls the singleton [WebSearchEngine] (and settings store, to check
 * "is a key configured" without making a network call) through this entry point instead of
 * constructor injection.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WebSearchEntryPoint {
  fun webSearchEngine(): WebSearchEngine

  fun webSearchSettingsStore(): WebSearchSettingsStore
}

fun webSearchEngineFrom(context: Context): WebSearchEngine =
  EntryPointAccessors.fromApplication(context.applicationContext, WebSearchEntryPoint::class.java)
    .webSearchEngine()

fun webSearchSettingsStoreFrom(context: Context): WebSearchSettingsStore =
  EntryPointAccessors.fromApplication(context.applicationContext, WebSearchEntryPoint::class.java)
    .webSearchSettingsStore()
