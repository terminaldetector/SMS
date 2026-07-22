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

package com.saturnmask.gallery.domain.rag.extract

import android.content.Context
import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton

/** Extracts plain text from one document format. See [DocumentTextExtractorRegistry]. */
interface DocumentTextExtractor {
  /** Whether this extractor can handle a file with this MIME type and/or file name. */
  fun supports(mimeType: String?, displayName: String): Boolean

  /** Reads [uri] and returns its plain-text content, ready to be chunked. */
  suspend fun extractText(context: Context, uri: Uri): String
}

/**
 * Picks the right [DocumentTextExtractor] for a file, by MIME type first (what
 * `contentResolver.getType(uri)` reports) and file extension as a fallback (some content
 * providers — e.g. some file-picker backends — report a generic `application/octet-stream` MIME
 * type regardless of actual format).
 *
 * Kept as a single Hilt-injectable class with a hardcoded extractor list rather than a Dagger
 * multibinding `Set<DocumentTextExtractor>` — this project doesn't use multibindings elsewhere,
 * and four fixed formats don't need that flexibility yet. If a 5th format shows up and this
 * starts feeling cramped, that's the point to switch.
 */
@Singleton
class DocumentTextExtractorRegistry @Inject constructor() {

  private val extractors: List<DocumentTextExtractor> =
    listOf(PdfTextExtractor(), DocxTextExtractor(), EpubTextExtractor(), PlainTextExtractor())

  suspend fun extractText(context: Context, uri: Uri, displayName: String): String {
    val mimeType = context.contentResolver.getType(uri)
    val extractor =
      extractors.firstOrNull { it.supports(mimeType, displayName) }
        // PlainTextExtractor.supports() always returns true, so this is unreachable given the
        // list above — kept as an explicit error rather than silently returning "" just in case.
        ?: error("No text extractor available for '$displayName' (mime=$mimeType)")
    return extractor.extractText(context, uri)
  }
}
