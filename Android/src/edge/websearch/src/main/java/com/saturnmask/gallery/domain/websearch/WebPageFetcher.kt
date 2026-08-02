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

import android.text.Html
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AGWebPageFetcher"
private const val CONNECT_TIMEOUT_MS = 8_000
private const val READ_TIMEOUT_MS = 8_000
private const val MAX_RESPONSE_BYTES = 2_000_000 // 2MB — plenty for an article page, not a video/binary.

// Identifies as an automated client, same spirit as a search-engine crawler or RSS reader —
// deliberately NOT a real browser's UA string. Presenting as a bot rather than impersonating a
// human is a policy requirement here, not just a style choice (see this project's roadmap
// "Явно не делаем" section).
private const val USER_AGENT = "Mozilla/5.0 (compatible; GEdgeAgent/1.0)"

/**
 * Fetches [url] over plain HTTP(S) GET and returns its readable text (HTML tags, `<script>`, and
 * `<style>` content stripped; entities decoded). Shared by every [WebSearchEngine] implementation
 * via its default `fetchPage` method — see that doc comment for why this isn't backend-specific.
 */
internal suspend fun fetchWebPage(url: String, maxChars: Int): Result<String> =
  withContext(Dispatchers.IO) {
    runCatching {
      val parsedUrl = URL(url)
      if (parsedUrl.protocol != "http" && parsedUrl.protocol != "https") {
        error("Unsupported URL scheme: ${parsedUrl.protocol}")
      }

      val connection = parsedUrl.openConnection() as HttpURLConnection
      connection.requestMethod = "GET"
      connection.connectTimeout = CONNECT_TIMEOUT_MS
      connection.readTimeout = READ_TIMEOUT_MS
      connection.instanceFollowRedirects = true
      connection.setRequestProperty("User-Agent", USER_AGENT)
      connection.setRequestProperty("Accept", "text/html,text/plain;q=0.9,*/*;q=0.1")

      try {
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
          error("Failed to fetch page: HTTP $responseCode")
        }

        val contentType = connection.contentType.orEmpty()
        if (!contentType.startsWith("text/") && !contentType.contains("html")) {
          error("Not a readable page (content-type: ${contentType.ifBlank { "unknown" }})")
        }

        val html =
          connection.inputStream.use { stream ->
            // InputStream.readNBytes(int) needs API 33; minSdk here is 31, so read manually
            // into a growable buffer instead, capped at MAX_RESPONSE_BYTES.
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8_192)
            var total = 0
            while (total < MAX_RESPONSE_BYTES) {
              val read = stream.read(chunk, 0, minOf(chunk.size, MAX_RESPONSE_BYTES - total))
              if (read == -1) break
              buffer.write(chunk, 0, read)
              total += read
            }
            buffer.toByteArray().toString(Charsets.UTF_8)
          }

        // Drop script/style content entirely — Html.fromHtml below strips markup and decodes
        // entities, but doesn't know that a <script>/<style> body isn't readable text.
        val withoutScriptsAndStyles =
          html
            .replace(Regex("(?is)<script.*?>.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?>.*?</style>"), " ")

        val text =
          Html.fromHtml(withoutScriptsAndStyles, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        if (text.isEmpty()) {
          error("Page fetched but contained no readable text.")
        }

        if (text.length > maxChars) {
          text.take(maxChars) + "\n\n[…truncated, ${text.length - maxChars} more characters]"
        } else {
          text
        }
      } finally {
        connection.disconnect()
      }
    }
      .onFailure { e -> Log.w(TAG, "Page fetch failed for \"$url\"", e) }
  }
