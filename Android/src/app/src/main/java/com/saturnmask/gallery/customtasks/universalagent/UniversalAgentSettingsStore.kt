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

package com.saturnmask.gallery.customtasks.universalagent

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "universal_agent_settings"
private const val KEY_SCOPED_PACKAGE_NAME = "scoped_package_name"
private const val KEY_SCOPED_APP_LABEL = "scoped_app_label"

/**
 * Persists the app Universal Agent is restricted to (null = unrestricted, today's full-device
 * behavior), mirroring [com.saturnmask.gallery.customtasks.coder.CoderSettingsStore]'s shape.
 * Plain (not encrypted) — a package name isn't a secret, same reasoning as
 * [com.saturnmask.gallery.domain.rag.EmbedderSettingsStore]'s doc comment for a local file path.
 * Read fresh on every [UniversalAgentToolsImpl] tool call, not cached — changing the scope here
 * takes effect on the very next tool call, no session reset needed.
 */
@Singleton
class UniversalAgentSettingsStore @Inject constructor(@ApplicationContext context: Context) {
  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun getScopedPackageName(): String? = prefs.getString(KEY_SCOPED_PACKAGE_NAME, null)

  fun getScopedAppLabel(): String = prefs.getString(KEY_SCOPED_APP_LABEL, "") ?: ""

  fun setScopedApp(packageName: String, label: String) {
    prefs
      .edit()
      .putString(KEY_SCOPED_PACKAGE_NAME, packageName)
      .putString(KEY_SCOPED_APP_LABEL, label)
      .apply()
  }

  fun clearScopedApp() {
    prefs.edit().remove(KEY_SCOPED_PACKAGE_NAME).remove(KEY_SCOPED_APP_LABEL).apply()
  }
}

/**
 * [UniversalAgentToolsImpl] isn't Hilt-constructed (same reasoning as
 * [com.saturnmask.gallery.customtasks.coder.CoderToolsImpl]), so it reaches this store through
 * this accessor rather than field injection — same shape as
 * [com.saturnmask.gallery.customtasks.universalagent.universalAgentServiceBridgeFrom].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UniversalAgentSettingsEntryPoint {
  fun universalAgentSettingsStore(): UniversalAgentSettingsStore
}

fun universalAgentSettingsStoreFrom(context: Context): UniversalAgentSettingsStore =
  EntryPointAccessors.fromApplication(
      context.applicationContext,
      UniversalAgentSettingsEntryPoint::class.java,
    )
    .universalAgentSettingsStore()
