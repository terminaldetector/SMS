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

private const val PREFS_NAME = "model_capability_overrides"

/**
 * [Model.llmSupportImage]/[Model.llmSupportAudio] are declared from allowlist JSON (or an
 * optimistic default on manual import) but aren't authoritative — the model file is.
 * `LlmChatModelHelper.initialize()` already detects a mismatch at engine-creation time (a
 * `VISION_ENCODER`/`AUDIO_ENCODER` failure) and corrects the flags, but only on the in-memory
 * [Model] object — every fresh app launch previously repeated the same doomed engine-creation
 * attempt before recovering. This store persists that correction so later loads start from the
 * known-true value instead.
 *
 * Keyed on `name::downloadFileName::version` rather than just `name`: for allowlist models,
 * `version` is a commit hash, so bumping the allowlist entry to a new file version naturally
 * invalidates a stale override (the retry loop re-probes it once, as a fresh cache miss). For
 * manually-imported models, `version` is always `"_"` (no per-file version signal exists) — a
 * user who deletes an imported model and re-imports a *different* file under the identical
 * filename would otherwise inherit a stale override; [ModelManagerViewModel.deleteModel] clears
 * the override in its existing imported-model-removal branch to close that gap.
 */
@Singleton
class ModelCapabilityOverrideStore @Inject constructor(@ApplicationContext context: Context) {

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  data class Override(val supportImage: Boolean, val supportAudio: Boolean)

  fun getOverride(model: Model): Override? {
    val key = keyFor(model)
    if (!prefs.contains(imageKey(key))) return null
    return Override(
      supportImage = prefs.getBoolean(imageKey(key), false),
      supportAudio = prefs.getBoolean(audioKey(key), false),
    )
  }

  /** No-op if no override was ever recorded for this model's key. */
  fun applyOverride(model: Model) {
    val override = getOverride(model) ?: return
    model.llmSupportImage = override.supportImage
    model.llmSupportAudio = override.supportAudio
  }

  fun saveOverride(model: Model, supportImage: Boolean, supportAudio: Boolean) {
    val key = keyFor(model)
    prefs.edit().putBoolean(imageKey(key), supportImage).putBoolean(audioKey(key), supportAudio).apply()
  }

  fun clearOverride(model: Model) {
    val key = keyFor(model)
    prefs.edit().remove(imageKey(key)).remove(audioKey(key)).apply()
  }

  private fun keyFor(model: Model): String = "${model.name}::${model.downloadFileName}::${model.version}"

  private fun imageKey(key: String) = "$key::image"

  private fun audioKey(key: String) = "$key::audio"
}

/**
 * [ui.llmchat.LlmChatModelHelper] is a plain Kotlin `object`, not Hilt-managed, so it can't take a
 * constructor-injected store — same situation as [com.saturnmask.gallery.customtasks.agentchat
 * .AgentToolsImpl] in `WebSearchEngineModule.kt`, solved the same way here.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ModelCapabilityOverrideEntryPoint {
  fun modelCapabilityOverrideStore(): ModelCapabilityOverrideStore
}

fun modelCapabilityOverrideStoreFrom(context: Context): ModelCapabilityOverrideStore =
  EntryPointAccessors.fromApplication(
      context.applicationContext,
      ModelCapabilityOverrideEntryPoint::class.java,
    )
    .modelCapabilityOverrideStore()
