/*
 * Copyright 2026 Saturn Mask / GEDGE distillation
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

private const val PREFS_NAME = "model_capability_overrides"

/**
 * Hilt-free port of the Gallery store of the same name.
 *
 * The original is a `@Singleton class ... @Inject constructor(@ApplicationContext context)`, reached
 * through a Hilt `EntryPoint`. Hilt earns that in an app that already has a Hilt graph; TriangleUI
 * has none, and adding one — plus kapt — to a React Native app in order to hand a class a Context
 * it could simply be given is a large amount of machinery for an argument. So this is the same
 * SharedPreferences, the same keys, the same behaviour, with a plain constructor.
 *
 * What it is for, unchanged from the original: `Model.llmSupportImage`/`llmSupportAudio` come from
 * allowlist JSON or an optimistic default on manual import, and neither is authoritative — the
 * model file is. `LlmChatModelHelper.initialize()` detects the mismatch at engine-creation time (a
 * VISION_ENCODER / AUDIO_ENCODER failure) and corrects the flags, but only in memory, so every
 * fresh launch repeated the same doomed attempt before recovering. Persisting the correction means
 * later loads start from the known-true value.
 *
 * Keyed on `name::downloadFileName::version` rather than name alone: for allowlist models `version`
 * is a commit hash, so bumping an entry to a new file version invalidates a stale override by
 * itself. Imported models have no version signal (`"_"`), which is why deleting one clears its
 * override rather than leaving it to be inherited by a different file of the same name.
 */
class ModelCapabilityOverrideStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    private fun keyFor(model: Model): String =
        "${model.name}::${model.downloadFileName}::${model.version}"

    private fun imageKey(key: String) = "$key::image"

    private fun audioKey(key: String) = "$key::audio"
}

// One instance per process, as @Singleton gave. SharedPreferences is itself process-wide and
// thread-safe, so this is about not re-opening the same file, not about correctness.
@Volatile private var instance: ModelCapabilityOverrideStore? = null

/** The accessor `LlmChatModelHelper` calls; replaces Hilt's EntryPointAccessors lookup. */
fun modelCapabilityOverrideStoreFrom(context: Context): ModelCapabilityOverrideStore =
    instance
        ?: synchronized(ModelCapabilityOverrideStore::class.java) {
            instance ?: ModelCapabilityOverrideStore(context).also { instance = it }
        }
