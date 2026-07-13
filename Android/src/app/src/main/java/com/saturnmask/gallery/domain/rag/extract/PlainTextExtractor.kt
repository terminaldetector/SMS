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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the file as raw UTF-8 text. This is also the catch-all fallback: [supports] always
 * returns `true`, so it must be registered LAST in [DocumentTextExtractorRegistry]'s extractor
 * list — an unrecognized format is still better read as (possibly messy) plain text than
 * rejected outright, matching the original pre-parsers behavior.
 */
class PlainTextExtractor : DocumentTextExtractor {

  override fun supports(mimeType: String?, displayName: String): Boolean = true

  override suspend fun extractText(context: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
      context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        ?: error("Could not open file")
    }
}
