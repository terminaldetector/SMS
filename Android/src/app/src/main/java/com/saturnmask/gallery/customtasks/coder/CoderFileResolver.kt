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

package com.saturnmask.gallery.customtasks.coder

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile

/**
 * Resolves/creates POSIX-style relative paths (e.g. "src/Main.kt") against a SAF tree root.
 *
 * Sandboxing here is structural, not a runtime check that could be bypassed: this resolver only
 * ever walks *downward* from [root] one named-child lookup at a time via
 * [DocumentFile.findFile]/[DocumentFile.createDirectory] — there is no "go to parent" or
 * "resolve absolute path" operation anywhere in it, and `findFile` does a plain literal-name
 * comparison against `listFiles()` with zero special interpretation of `".."`/`"."`. A path like
 * `"../../etc/passwd"` isn't rejected, it just fails to resolve, exactly like a segment named
 * `"nonexistent-child"` would — there is nothing to escape *to*, regardless of what a
 * prompt-injected model passes as the path string. [rejectionReasonFor] is a defense-in-depth
 * safety net on top of that (closes the one non-escaping rough edge — see its own doc — and gives
 * a clean error instead of a confusing filesystem side effect), not the security boundary itself.
 */
object CoderFileResolver {

  fun resolveExisting(root: DocumentFile, path: String): DocumentFile? {
    if (rejectionReasonFor(path) != null) return null
    var current = root
    for (segment in segmentsOf(path)) {
      current = current.findFile(segment) ?: return null
    }
    return current
  }

  /** Finds/creates all parent directories for [path], returning (parentDir, leafFileName). */
  fun resolveOrCreateParent(root: DocumentFile, path: String): Pair<DocumentFile, String>? {
    if (rejectionReasonFor(path) != null) return null
    val segments = segmentsOf(path)
    if (segments.isEmpty()) return null
    var current = root
    for (segment in segments.dropLast(1)) {
      current = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
    }
    return current to segments.last()
  }

  /**
   * Human-readable rejection reason if [path] has an unsafe segment (empty, ".", ".."), else
   * null. A clean-error safety net (closes [resolveOrCreateParent]'s `createDirectory("..")` case
   * — the one place an unmatched ".." segment would otherwise silently create a confusingly-named
   * but still fully-contained child directory) — NOT the sandboxing mechanism itself, which is
   * already structural (see class doc above).
   */
  fun rejectionReasonFor(path: String): String? {
    val bad = path.trim('/').split('/').firstOrNull { it.isEmpty() || it == "." || it == ".." }
    return bad?.let { "Invalid path segment \"$it\" in \"$path\" — refusing to resolve." }
  }

  fun readText(context: Context, uri: Uri): String =
    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
      ?: error("Failed to open file for reading")

  /**
   * [existing] non-null truncates-overwrites in place; null creates the file (and any missing
   * parent directories). "wt" = truncate-then-write — the first SAF *write-back* in this app (the
   * only prior SAF usage, in SkillManagerViewModel's copyDocumentFile, only ever reads SAF files
   * and writes app-private java.io.File output).
   */
  fun writeText(context: Context, root: DocumentFile, path: String, existing: DocumentFile?, content: String) {
    val target =
      existing
        ?: run {
          val (parent, leafName) =
            resolveOrCreateParent(root, path) ?: error("Invalid path: $path")
          parent.createFile(guessMimeType(leafName), leafName) ?: error("Failed to create $path")
        }
    context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(content.toByteArray()) }
      ?: error("Failed to open output stream for $path")
  }

  private fun segmentsOf(path: String) = path.trim('/').split('/').filter { it.isNotEmpty() }

  private fun guessMimeType(fileName: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "")) ?: "text/plain"
}
