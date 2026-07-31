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
import android.text.Html
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Extracts text from `.epub` files without `epublib` (the common Java EPUB library) — it hasn't
 * been meaningfully maintained in years and, like Apache POI, brings `java.awt`-era assumptions
 * that don't hold on Android.
 *
 * An EPUB is a ZIP containing: `META-INF/container.xml` (points to the OPF package file) → the
 * OPF's `<manifest>` (id → file mapping) + `<spine>` (reading order by id) → each spine item is
 * an XHTML chapter. We read all three by hand with `XmlPullParser`, then let Android's built-in
 * `Html.fromHtml` do the actual HTML-to-text conversion per chapter — deliberately NOT a
 * hand-rolled tag-stripping regex, since `Html.fromHtml` correctly handles malformed markup and
 * HTML entities (`&amp;`, `&#8217;`, etc.), which a regex approach reliably gets wrong on real
 * ebook files.
 */
class EpubTextExtractor : DocumentTextExtractor {

  override fun supports(mimeType: String?, displayName: String): Boolean =
    mimeType == "application/epub+zip" || displayName.endsWith(".epub", ignoreCase = true)

  override suspend fun extractText(context: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
      val entries =
        context.contentResolver.openInputStream(uri)?.use { readAllZipEntries(it) }
          ?: error("Could not open EPUB")

      val containerXml =
        entries["META-INF/container.xml"] ?: error("Not a valid .epub (missing container.xml)")
      val opfPath = findOpfPath(containerXml) ?: error("Not a valid .epub (no OPF reference)")
      val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
      val opfXml = entries[opfPath] ?: error("Not a valid .epub (missing $opfPath)")

      val (manifest, spine) = parseOpf(opfXml)
      val chapterHrefs = spine.mapNotNull { idref -> manifest[idref] }

      chapterHrefs
        .mapNotNull { href ->
          val fullPath = if (opfDir.isEmpty()) href else "$opfDir/$href"
          entries[fullPath]
        }
        .joinToString("\n\n") { html -> Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString() }
    }

  private fun readAllZipEntries(input: java.io.InputStream): Map<String, String> {
    val result = mutableMapOf<String, String>()
    ZipInputStream(input).use { zip ->
      var entry = zip.nextEntry
      while (entry != null) {
        if (!entry.isDirectory) {
          // EPUB content is XHTML/XML/text; skip binary assets (images, fonts) — reading them as
          // text would just produce garbage that gets thrown away anyway.
          if (entry.name.endsWith(".xhtml") ||
              entry.name.endsWith(".html") ||
              entry.name.endsWith(".htm") ||
              entry.name.endsWith(".xml") ||
              entry.name.endsWith(".opf")) {
            result[entry.name] = zip.bufferedReader(Charsets.UTF_8).readText()
          }
        }
        entry = zip.nextEntry
      }
    }
    return result
  }

  private fun findOpfPath(containerXml: String): String? {
    val parser = newParser(containerXml)
    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
      if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
        return parser.getAttributeValue(null, "full-path")
      }
      eventType = parser.next()
    }
    return null
  }

  /** Returns (manifest id->href map, spine idref list in reading order). */
  private fun parseOpf(opfXml: String): Pair<Map<String, String>, List<String>> {
    val manifest = mutableMapOf<String, String>()
    val spine = mutableListOf<String>()
    val parser = newParser(opfXml)
    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
      if (eventType == XmlPullParser.START_TAG) {
        when (parser.name) {
          "item" -> {
            val id = parser.getAttributeValue(null, "id")
            val href = parser.getAttributeValue(null, "href")
            if (id != null && href != null) manifest[id] = href
          }
          "itemref" -> {
            parser.getAttributeValue(null, "idref")?.let { spine.add(it) }
          }
        }
      }
      eventType = parser.next()
    }
    return manifest to spine
  }

  private fun newParser(xml: String): XmlPullParser {
    val factory = XmlPullParserFactory.newInstance()
    factory.isNamespaceAware = true
    val parser = factory.newPullParser()
    parser.setInput(xml.reader())
    return parser
  }
}
