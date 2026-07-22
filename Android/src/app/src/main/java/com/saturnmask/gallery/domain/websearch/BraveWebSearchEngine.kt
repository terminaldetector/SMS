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

import android.util.Log
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AGBraveSearch"
private const val ENDPOINT = "https://api.search.brave.com/res/v1/web/search"
private const val CONNECT_TIMEOUT_MS = 8_000
private const val READ_TIMEOUT_MS = 8_000

// --- Response shape, per Brave's published API reference. Only the fields we use are modeled;
// Moshi ignores the rest. ---
@JsonClass(generateAdapter = true)
internal data class BraveSearchResponse(val web: BraveWebResults?)

@JsonClass(generateAdapter = true) internal data class BraveWebResults(val results: List<BraveResult>?)

@JsonClass(generateAdapter = true)
internal data class BraveResult(val title: String?, val url: String?, val description: String?)

/**
 * [WebSearchEngine] backed by the Brave Search API (https://api.search.brave.com).
 *
 * Chosen over scraping a search results page directly because: it's a stable, documented,
 * ToS-compliant JSON API (scraping Google/Bing SERPs violates their Terms of Service and breaks
 * whenever they change markup); its onboarding doesn't require a Google Cloud project + Custom
 * Search Engine ID the way Google's official Custom Search JSON API does; and unlike SerpAPI-style
 * "scrape a real SERP for you" resellers, Brave queries its own independent index directly.
 *
 * ⚠️ Pricing note (as of when this was written): Brave moved to metered pay-as-you-go billing —
 * roughly $5 of free credit per month (~1,000 queries), a payment method required up front, no
 * default spend cap on Brave's side. [WebSearchSettingsBottomSheet] surfaces that expectation, and
 * [WebSearchSettingsStore.tryConsumeDailyCall] enforces [DAILY_WEB_SEARCH_CALL_LIMIT] as a local
 * safety rail against a runaway loop burning through it.
 *
 * ⚠️ Not live-tested: written from Brave's published API reference + quickstart samples, not
 * verified against a real API key/response from this environment (no network access here). The
 * response shape matches their docs; if Brave changes their schema, only [BraveSearchResponse]
 * and friends need updating.
 */
@OptIn(ExperimentalStdlibApi::class)
@Singleton
class BraveWebSearchEngine
@Inject
constructor(private val settingsStore: WebSearchSettingsStore) : WebSearchEngine {

  private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
  private val responseAdapter = moshi.adapter<BraveSearchResponse>()

  override suspend fun search(query: String, maxResults: Int): Result<List<WebSearchResult>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val apiKey =
          settingsStore.getApiKey(WebSearchProvider.BRAVE)
            ?: error(
              "No Brave Search API key configured. Add one in Settings > Web Search to enable " +
                "this tool."
            )

        // Checked (and consumed) before spending a real network call — see
        // WebSearchSettingsStore.tryConsumeDailyCall's doc comment for why the limit exists and
        // why it's consumed here rather than only on success.
        if (!settingsStore.tryConsumeDailyCall()) {
          error(
            "Daily web search limit reached ($DAILY_WEB_SEARCH_CALL_LIMIT calls). This protects " +
              "your Brave Search billing from a runaway loop — resets at midnight."
          )
        }

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val count = maxResults.coerceIn(1, 20)
        val url = URL("$ENDPOINT?q=$encodedQuery&count=$count")

        val connection = (url.openConnection() as HttpURLConnection)
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("X-Subscription-Token", apiKey)

        try {
          val responseCode = connection.responseCode
          if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
            val hint =
              when (responseCode) {
                401 -> "Check that your Brave Search API key is correct."
                429 -> "Rate limit or monthly quota exceeded."
                else -> "HTTP $responseCode"
              }
            error("Web search failed: $hint${errorBody?.let { " ($it)" } ?: ""}")
          }

          val body = connection.inputStream.bufferedReader().use { it.readText() }
          val parsed =
            responseAdapter.fromJson(body) ?: error("Empty/unparseable response from Brave Search")

          parsed.web
            ?.results
            .orEmpty()
            .mapNotNull { r ->
              val title = r.title
              val resultUrl = r.url
              if (title.isNullOrBlank() || resultUrl.isNullOrBlank()) {
                null
              } else {
                WebSearchResult(title = title, url = resultUrl, snippet = r.description.orEmpty())
              }
            }
            .take(count)
        } finally {
          connection.disconnect()
        }
      }
        .onFailure { e -> Log.w(TAG, "Web search failed for query \"$query\"", e) }
    }
}
