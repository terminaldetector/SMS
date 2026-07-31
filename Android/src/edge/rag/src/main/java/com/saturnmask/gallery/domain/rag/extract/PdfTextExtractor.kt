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
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// PDFBox-Android needs its font/resource loader initialized once per process before ANY PDFBox
// call (PDDocument.load included) — see https://github.com/TomRoush/PdfBox-Android#getting-started.
// Guarded so it's safe to call from multiple concurrent addDocument() calls.
private val pdfBoxInitialized = AtomicBoolean(false)

/**
 * Extracts text from `.pdf` files via PdfBox-Android (`com.tom-roush:pdfbox-android`), a
 * maintained Android port of Apache PDFBox — NOT plain Apache PDFBox/PDFBox-java, which depends
 * on `java.awt`/font APIs that don't exist on Android and won't run there.
 *
 * Only extracts text layer content — scanned/image-only PDFs (no embedded text layer) will yield
 * an empty or near-empty string, since this is a text extractor, not OCR. That's a reasonable
 * scope boundary for a RAG indexer; flagging it rather than silently returning nothing with no
 * explanation is the only thing added here for that case (see [extractText]).
 */
class PdfTextExtractor : DocumentTextExtractor {

  override fun supports(mimeType: String?, displayName: String): Boolean =
    mimeType == "application/pdf" || displayName.endsWith(".pdf", ignoreCase = true)

  override suspend fun extractText(context: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
      if (pdfBoxInitialized.compareAndSet(false, true)) {
        PDFBoxResourceLoader.init(context.applicationContext)
      }

      val text =
        context.contentResolver.openInputStream(uri)?.use { input ->
          PDDocument.load(input).use { document ->
            if (document.isEncrypted) {
              error("This PDF is password-protected and can't be indexed.")
            }
            PDFTextStripper().getText(document)
          }
        } ?: error("Could not open PDF")

      if (text.isBlank()) {
        error(
          "No extractable text found in this PDF — it may be a scanned/image-only document " +
            "(OCR is not supported)."
        )
      }
      text
    }
}
