/*
 * Copyright 2025 Google LLC
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

package com.saturnmask.gallery

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import com.saturnmask.gallery.data.DataStoreRepository
import com.saturnmask.gallery.notifications.NotificationScheduleManager
import com.saturnmask.gallery.proto.Theme
import com.saturnmask.gallery.ui.theme.ThemeSettings
import com.google.firebase.FirebaseApp
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository
  @Inject lateinit var notificationScheduleManager: NotificationScheduleManager

  override fun onCreate() {
    super.onCreate()
    // Initialize the notification schedule manager to load the scheduled notifications from the
    // disk.
    notificationScheduleManager.initialize()

    // Load saved theme.
    val theme = dataStoreRepository.readTheme()
    ThemeSettings.themeOverride.value = theme

    // Declare our night mode to the OS. This is important on OEM ROMs with a "force dark / dark
    // mode for all apps" feature: by owning our night mode the ROM stops auto-inverting the app
    // (which otherwise left the chat area white and turned message text white-on-white, making
    // messages invisible). Also applies the user's persisted Dark/Light choice at launch.
    applySystemNightMode(theme)

    FirebaseApp.initializeApp(this)
  }

  private fun applySystemNightMode(theme: Theme) {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return
    val mode =
      when (theme) {
        Theme.THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO
        Theme.THEME_DARK -> UiModeManager.MODE_NIGHT_YES
        else -> UiModeManager.MODE_NIGHT_AUTO
      }
    try {
      uiModeManager.setApplicationNightMode(mode)
    } catch (_: Exception) {
      // setApplicationNightMode can throw on some OEM implementations; ignore and fall back to the
      // in-app Compose theme.
    }
  }
}
