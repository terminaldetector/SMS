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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "coder_settings"
private const val KEY_PROJECT_ROOT_URI = "project_root_uri"
private const val KEY_PROJECT_DISPLAY_NAME = "project_display_name"

/**
 * Persists the picked project folder's tree URI so it survives app restart, mirroring
 * [com.saturnmask.gallery.domain.rag.EmbedderSettingsStore]'s shape. Pairs with
 * `takePersistableUriPermission` (called in [CoderViewModel.pickProjectFolder]) — without that
 * permission grant, persisting the URI string alone is useless since SAF otherwise revokes tree
 * access on process death.
 */
@Singleton
class CoderSettingsStore @Inject constructor(@ApplicationContext context: Context) {
  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun getProjectRootUri(): Uri? = prefs.getString(KEY_PROJECT_ROOT_URI, null)?.let(Uri::parse)

  fun getProjectDisplayName(): String = prefs.getString(KEY_PROJECT_DISPLAY_NAME, "") ?: ""

  fun setProjectRoot(uri: Uri, displayName: String) {
    prefs
      .edit()
      .putString(KEY_PROJECT_ROOT_URI, uri.toString())
      .putString(KEY_PROJECT_DISPLAY_NAME, displayName)
      .apply()
  }
}

/**
 * [ui.llmchat.LlmChatModelHelper]-style non-Hilt reachability is not actually needed for this
 * store (only Hilt-constructed [CoderViewModel]/[CoderTask] consume it), but [CoderTask] itself is
 * constructed via a plain `@Inject constructor()` used from a `@Provides @IntoSet` — not
 * field-injected — so it still needs this accessor to reach the store from [CoderTask
 * .initializeModelFn], same reasoning as [com.saturnmask.gallery.data.modelCapabilityOverrideStoreFrom].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CoderSettingsEntryPoint {
  fun coderSettingsStore(): CoderSettingsStore
}

fun coderSettingsStoreFrom(context: Context): CoderSettingsStore =
  EntryPointAccessors.fromApplication(context.applicationContext, CoderSettingsEntryPoint::class.java)
    .coderSettingsStore()
