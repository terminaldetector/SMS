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

package com.saturnmask.gallery.data

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "backend_health"

/**
 * Tracks, per (model, accelerator) pair, whether that backend has actually failed on this device —
 * either at model-init time or during inference — so `LlmChatModelHelper`'s NPU->GPU->CPU fallback
 * chain can skip a backend it already knows is broken instead of re-attempting it every time this
 * model loads. `"CPU"` is deliberately never checked against this store (see call sites) — it's the
 * one backend with no compiled/native delegate to fail this way, so it always stays available.
 *
 * The [markAttemptStarted]/[markAttemptSucceeded] pair exists for exactly one reason: a true
 * native/JNI-level crash (a GPU/NPU compiled-delegate SIGSEGV, say) bypasses Kotlin `try`/`catch`
 * entirely and takes the whole process down — nothing in Kotlin can detect or recover from that on
 * the run where it happens. What *can* survive it is the next launch: if a backend was marked
 * "attempt started" but never reached "attempt succeeded", the process must have died mid-attempt,
 * so [hadUnclearedAttempt] treats that the same as a known-bad backend without ever needing to
 * catch the crash itself.
 */
@Singleton
class BackendHealthStore @Inject constructor(@ApplicationContext context: Context) {

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun isKnownBad(modelName: String, accelerator: String): Boolean =
    prefs.getBoolean(badKey(modelName, accelerator), false)

  fun markBad(modelName: String, accelerator: String) {
    prefs.edit().putBoolean(badKey(modelName, accelerator), true).apply()
  }

  /** The one deliberate way a previously-bad backend gets another chance: the user explicitly
   *  re-selects it in the Config dialog. Nothing else auto-clears this. */
  fun clearBad(modelName: String, accelerator: String) {
    prefs
      .edit()
      .remove(badKey(modelName, accelerator))
      .remove(attemptingKey(modelName, accelerator))
      .apply()
  }

  fun markAttemptStarted(modelName: String, accelerator: String) {
    prefs.edit().putBoolean(attemptingKey(modelName, accelerator), true).apply()
  }

  /** Also clears [isKnownBad] — a fresh successful init is real evidence this backend works now. */
  fun markAttemptSucceeded(modelName: String, accelerator: String) {
    prefs
      .edit()
      .remove(attemptingKey(modelName, accelerator))
      .remove(badKey(modelName, accelerator))
      .apply()
  }

  fun hadUnclearedAttempt(modelName: String, accelerator: String): Boolean =
    prefs.getBoolean(attemptingKey(modelName, accelerator), false)

  private fun badKey(modelName: String, accelerator: String) = "$modelName::$accelerator::bad"

  private fun attemptingKey(modelName: String, accelerator: String) =
    "$modelName::$accelerator::attempting"
}

/**
 * [ui.llmchat.LlmChatModelHelper] is a plain Kotlin `object`, not Hilt-managed — same situation as
 * [ModelCapabilityOverrideStore] in this same file's sibling, solved the same way here.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackendHealthEntryPoint {
  fun backendHealthStore(): BackendHealthStore
}

fun backendHealthStoreFrom(context: Context): BackendHealthStore =
  EntryPointAccessors.fromApplication(context.applicationContext, BackendHealthEntryPoint::class.java)
    .backendHealthStore()
