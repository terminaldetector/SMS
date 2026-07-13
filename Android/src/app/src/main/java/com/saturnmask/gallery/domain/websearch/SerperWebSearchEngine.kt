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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AGSerperSearch"
private const val ENDPOINT = "https://google.serper.dev/search"
private const val CONNECT_TIMEOUT_MS = 8_000
private const val READ_TIMEOUT_MS = 8_000

// --- Request/response shape, per Serper's published API reference. Only the fields we use are
// modeled; Moshi ignores the rest. ---
@JsonClass(generateAdapter = true) internal data class SerperRequest(val q: String, val num: Int)

@JsonClass(generateAdapter = true)
internal data class SerperResponse(val organic: List<SerperResult>?)

@JsonClass(generateAdapter = true)
internal data class SerperResult(val title: String?, val link: String?, val snippet: String?)

/**
 * [WebSearchEngine] backed by the Serper.dev API (https://serper.dev), a thin wrapper over real
 * Google search results — not a scraper, an official metered API.
 *
 * Picked as the second provider (alongside [BraveWebSearchEngine]) because its free tier is a
 * one-time 2,500-query pool with no card required up front — closer to "free and no billing
 * surprise" than Brave's metered pay-as-you-go, per the roadmap's Web section. Once the pool is
 * exhausted, Serper requires a paid plan; there's no auto-renewing free monthly credit the way
 * Brave has.
 *
 * ⚠️ Not live-tested: written from Serper's published API reference, not verified against a real
 * API key/response from this environment (no network access here). The response shape matches
 * their docs; if Serper changes their schema, only [SerperResponse] and friends need updating.
 */
@OptIn(ExperimentalStdlibApi::class)
@Singleton
class SerperWebSearchEngine
@Inject
constructor(private val settingsStore: WebSearchSettingsStore) : WebSearchEngine {

  private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
  private val requestAdapter = moshi.adapter<SerperRequest>()
  private val responseAdapter = moshi.adapter<SerperResponse>()

  override suspend fun search(query: String, maxResults: Int): Result<List<WebSearchResult>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val apiKey =
          settingsStore.getApiKey(WebSearchProvider.SERPER)
            ?: error(
              "No Serper.dev API key configured. Add one in Settings > Web Search to enable " +
                "this tool."
            )

        // Same shared daily cap as Brave — see WebSearchSettingsStore.tryConsumeDailyCall's doc
        // comment for why it applies regardless of which provider is active.
        if (!settingsStore.tryConsumeDailyCall()) {
          error(
            "Daily web search limit reached ($DAILY_WEB_SEARCH_CALL_LIMIT calls). This protects " +
              "your Serper.dev quota from a runaway loop — resets at midnight."
          )
        }

        val count = maxResults.coerceIn(1, 20)
        val requestBody = requestAdapter.toJson(SerperRequest(q = query, num = count))

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-API-KEY", apiKey)

        try {
          connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

          val responseCode = connection.responseCode
          if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
            val hint =
              when (responseCode) {
                401,
                403 -> "Check that your Serper.dev API key is correct."
                429 -> "Rate limit or query pool exhausted."
                else -> "HTTP $responseCode"
              }
            error("Web search failed: $hint${errorBody?.let { " ($it)" } ?: ""}")
          }

          val body = connection.inputStream.bufferedReader().use { it.readText() }
          val parsed =
            responseAdapter.fromJson(body) ?: error("Empty/unparseable response from Serper.dev")

          parsed.organic
            .orEmpty()
            .mapNotNull { r ->
              val title = r.title
              val resultUrl = r.link
              if (title.isNullOrBlank() || resultUrl.isNullOrBlank()) {
                null
              } else {
                WebSearchResult(title = title, url = resultUrl, snippet = r.snippet.orEmpty())
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
