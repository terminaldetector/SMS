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

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.saturnmask.gallery.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "AGCrashLogger"
private const val CRASH_LOG_DIR_NAME = "crash_logs"
private const val MAX_CRASH_LOGS_KEPT = 10

/**
 * Persists a plain-text record of every uncaught exception to app-private storage before letting
 * the crash proceed normally, and offers a best-effort recent-logcat capture — together these are
 * what [exportDiagnostics] bundles up, so a crash report can come with real evidence instead of a
 * vague description nobody can act on without an adb-attached device.
 *
 * Deliberately does NOT retrofit the ~346 existing `Log.d/e/w` call sites across the app onto a new
 * wrapper — that would be a huge, risky diff for what this needs. Instead: capture crashes going
 * forward, and pull whatever's still in the OS's own logcat ring buffer at export time (which
 * already has all of that existing Log output verbatim, unmodified).
 */
object CrashLogger {

  /** Call once, as early as possible in [android.app.Application.onCreate]. */
  fun install(context: Context) {
    val appContext = context.applicationContext
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      try {
        writeCrashRecord(appContext, thread, throwable)
      } catch (t: Throwable) {
        // Must never throw from here — that would mask the real crash with a different one.
        Log.e(TAG, "Failed to persist crash record", t)
      } finally {
        previousHandler?.uncaughtException(thread, throwable)
      }
    }
  }

  private fun crashLogDir(context: Context): File =
    File(context.filesDir, CRASH_LOG_DIR_NAME).apply { mkdirs() }

  private fun writeCrashRecord(context: Context, thread: Thread, throwable: Throwable) {
    val dir = crashLogDir(context)
    val timestamp = System.currentTimeMillis()
    val file = File(dir, "crash_$timestamp.txt")
    val formattedTime =
      SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
    val content =
      buildString {
        appendLine("Time: $formattedTime ($timestamp)")
        appendLine("Thread: ${thread.name}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android SDK ${Build.VERSION.SDK_INT}")
        appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine()
        append(Log.getStackTraceString(throwable))
      }
    file.writeText(content)
    rotateOldCrashLogs(dir)
  }

  private fun rotateOldCrashLogs(dir: File, keepLast: Int = MAX_CRASH_LOGS_KEPT) {
    val files = dir.listFiles() ?: return
    files
      .sortedByDescending { it.lastModified() }
      .drop(keepLast)
      .forEach { it.delete() }
  }

  /** Newest-first. Used by [exportDiagnostics] to bundle every crash persisted so far. */
  fun listCrashLogs(context: Context): List<File> =
    crashLogDir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

  /**
   * Best-effort dump of this process's own recent logcat output — no special permission is needed
   * to read your own process's log (`READ_LOGS` restricts reading *other* apps'/system logs, not
   * your own), but this is unverified on a real device this session, so any failure here (OEM ROM
   * restrictions, `logcat` binary unavailable, etc.) is swallowed and reported as unavailable rather
   * than breaking the rest of the export.
   */
  fun captureRecentLogcat(): String? {
    return try {
      val pid = Process.myPid()
      val process =
        ProcessBuilder("logcat", "-d", "-t", "2000", "--pid=$pid", "-v", "threadtime")
          .redirectErrorStream(true)
          .start()
      val output = process.inputStream.bufferedReader().readText()
      process.waitFor()
      output
    } catch (e: Exception) {
      Log.w(TAG, "Recent logcat capture unavailable on this device", e)
      null
    }
  }
}
