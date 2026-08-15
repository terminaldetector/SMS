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

package com.saturnmask.gallery.common

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.saturnmask.gallery.BuildConfig
import com.saturnmask.gallery.data.backendHealthStoreFrom
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AGDiagnosticsExporter"

/**
 * Bundles whatever diagnostic data is actually available — persisted crash records ([CrashLogger]),
 * a best-effort recent-logcat capture, and a [com.saturnmask.gallery.data.BackendHealthStore]
 * summary — into a single text file and shares it, mirroring [shareBitmap]'s exact
 * FileProvider/ACTION_SEND pattern so a bug report can come with real evidence instead of a
 * description nobody can act on without an adb-attached device.
 */
suspend fun Context.exportDiagnostics(dispatcher: CoroutineDispatcher = Dispatchers.IO) {
  withContext(dispatcher) {
    try {
      val content = buildDiagnosticsText(this@exportDiagnostics)
      val diagnosticsDir = File(cacheDir, "diagnostics").apply { mkdirs() }
      val file = File(diagnosticsDir, "diagnostics_${System.currentTimeMillis()}.txt")
      file.writeText(content)

      val contentUri = FileProvider.getUriForFile(this@exportDiagnostics, "$packageName.provider", file)
      val shareIntent =
        Intent().apply {
          action = Intent.ACTION_SEND
          putExtra(Intent.EXTRA_STREAM, contentUri)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          type = "text/plain"
          clipData = ClipData.newRawUri("", contentUri)
        }
      startActivity(Intent.createChooser(shareIntent, "Export Diagnostics"))
    } catch (e: Exception) {
      Log.e(TAG, "Failed to export diagnostics", e)
    }
  }
}

private fun buildDiagnosticsText(context: Context): String = buildString {
  val timestamp = System.currentTimeMillis()
  val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))

  appendLine("=== Diagnostics export ===")
  appendLine("Exported: $formattedTime ($timestamp)")
  appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
  appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android SDK ${Build.VERSION.SDK_INT}")
  appendLine()

  appendLine("=== Backend health ===")
  appendLine(backendHealthStoreFrom(context).summarizeAll())
  appendLine()

  val crashLogs = CrashLogger.listCrashLogs(context)
  appendLine("=== Persisted crash records (${crashLogs.size}) ===")
  if (crashLogs.isEmpty()) {
    appendLine("(none recorded)")
  } else {
    for (crashLog in crashLogs) {
      appendLine("--- ${crashLog.name} ---")
      appendLine(runCatching { crashLog.readText() }.getOrDefault("(failed to read this file)"))
      appendLine()
    }
  }

  appendLine("=== Recent logcat (this process only, best-effort) ===")
  appendLine(CrashLogger.captureRecentLogcat() ?: "(unavailable on this device)")
}
