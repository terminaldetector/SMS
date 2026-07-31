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
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Extracts text from `.docx` files by reading the OOXML directly, WITHOUT Apache POI.
 *
 * Why not POI: `.docx` support in the Apache POI world lives in `poi-ooxml`, which pulls in
 * `poi` core (AWT-based image/font handling), Xerces, and a sizeable dependency tree not designed
 * for Android — real-world reports of `NoClassDefFoundError` on `java.awt.*` and large method
 * count / multidex issues are common. Since a `.docx` is just a ZIP of XML files, and we only
 * need paragraph text (not styling, images, or track-changes), hand-parsing
 * `word/document.xml` with Android's built-in `XmlPullParser` avoids that dependency entirely —
 * smaller APK, no Android-compat risk, at the cost of not handling every OOXML edge case (tables
 * come through as run-together text without column structure, headers/footers are skipped,
 * footnotes are skipped). Good enough for RAG chunking; not a general-purpose DOCX converter.
 */
class DocxTextExtractor : DocumentTextExtractor {

  override fun supports(mimeType: String?, displayName: String): Boolean =
    mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
      displayName.endsWith(".docx", ignoreCase = true)

  override suspend fun extractText(context: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
      val documentXml =
        context.contentResolver.openInputStream(uri)?.use { input ->
          readZipEntry(input, "word/document.xml")
        } ?: error("Could not open DOCX")

      documentXml?.let { parseDocumentXml(it) }
        ?: error("Not a valid .docx file (missing word/document.xml)")
    }

  private fun readZipEntry(input: java.io.InputStream, entryName: String): String? {
    ZipInputStream(input).use { zip ->
      var entry = zip.nextEntry
      while (entry != null) {
        if (entry.name == entryName) {
          return zip.bufferedReader(Charsets.UTF_8).readText()
        }
        entry = zip.nextEntry
      }
    }
    return null
  }

  /**
   * WordprocessingML text lives in `<w:t>` elements; paragraphs are `<w:p>`. We only need the
   * reading order of text runs plus paragraph breaks — tabs (`<w:tab/>`) and explicit line breaks
   * (`<w:br/>`) are mapped to whitespace/newlines so at least basic layout isn't lost.
   */
  private fun parseDocumentXml(xml: String): String {
    val factory = XmlPullParserFactory.newInstance()
    factory.isNamespaceAware = true
    val parser = factory.newPullParser()
    parser.setInput(xml.reader())

    val sb = StringBuilder()
    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
      if (eventType == XmlPullParser.START_TAG) {
        when (parser.name) {
          "t" -> sb.append(parser.nextText())
          "tab" -> sb.append('\t')
          "br", "cr" -> sb.append('\n')
        }
      } else if (eventType == XmlPullParser.END_TAG && parser.name == "p") {
        sb.append('\n')
      }
      eventType = parser.next()
    }
    return sb.toString()
  }
}
