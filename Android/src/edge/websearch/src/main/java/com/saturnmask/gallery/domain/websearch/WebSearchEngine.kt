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

/**
 * External web search, used to ground the agent when local RAG / model knowledge isn't enough.
 *
 * Deliberately provider-agnostic (mirrors [com.saturnmask.gallery.domain.rag.RagEngine]'s
 * shape): [BraveWebSearchEngine] and [SerperWebSearchEngine] are two interchangeable
 * implementations, and `SelectingWebSearchEngine` (bound to this interface in
 * [WebSearchEngineModule]) routes to whichever the user picked — nothing else in the app needs to
 * know which provider is behind it.
 */
interface WebSearchEngine {
  /**
   * Runs a web search for [query]. Returns [Result.failure] on network errors, missing/invalid
   * API key, or a non-2xx response — callers (the agent tool) are expected to surface that as a
   * readable error to the model rather than crash.
   */
  suspend fun search(query: String, maxResults: Int = 5): Result<List<WebSearchResult>>

  /**
   * Fetches [url] and returns its readable text content (HTML tags/scripts/styles stripped). Not
   * a search-backend operation — a plain HTTP GET, so unlike [search] it needs no API key and
   * doesn't touch [WebSearchSettingsStore]'s daily call cap (that cap exists to protect a search
   * provider's quota/billing; fetching an arbitrary page has no such cost). Given a default
   * implementation here rather than duplicated per backend, since the fetch-and-strip logic isn't
   * provider-specific — every [WebSearchEngine] implementation gets this for free.
   */
  suspend fun fetchPage(url: String, maxChars: Int = 8_000): Result<String> =
    fetchWebPage(url = url, maxChars = maxChars)
}

data class WebSearchResult(val title: String, val url: String, val snippet: String)
