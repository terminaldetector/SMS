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

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

private const val TAG = "AGUniversalAgentA11y"

/**
 * Bound/started entirely by the Accessibility framework once the user enables it in
 * Settings > Accessibility — there is no programmatic enable API, see
 * [isUniversalAgentAccessibilityServiceEnabled]. Not Hilt-annotated, mirroring the app's one other
 * Service, [com.saturnmask.gallery.GalleryFcmMessagingService] (also plain, no `@AndroidEntryPoint`)
 * — reaches the rest of the app purely through [universalAgentServiceBridgeFrom]'s EntryPoint
 * accessor, the same direction [UniversalAgentToolsImpl] uses to reach back into this instance.
 */
class UniversalAgentAccessibilityService : AccessibilityService() {

  /** Package name of the frontmost window, best-effort — informational only, not load-bearing
   *  for tool correctness (listScreenElements always re-reads rootInActiveWindow directly). */
  @Volatile var lastKnownPackageName: String? = null
    private set

  override fun onServiceConnected() {
    super.onServiceConnected()
    Log.d(TAG, "onServiceConnected")
    universalAgentServiceBridgeFrom(applicationContext).attach(this)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
      lastKnownPackageName = event.packageName?.toString()
    }
  }

  override fun onInterrupt() {
    Log.w(TAG, "onInterrupt")
  }

  override fun onDestroy() {
    universalAgentServiceBridgeFrom(applicationContext).detach(this)
    super.onDestroy()
  }
}

/** The only way to detect enablement — there is no `requestAccessibilityPermission`-style API;
 *  the user must manually toggle this service on in Settings > Accessibility. */
fun isUniversalAgentAccessibilityServiceEnabled(context: Context): Boolean {
  val accessibilityManager =
    context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
  return accessibilityManager
    .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    .any {
      it.resolveInfo.serviceInfo.packageName == context.packageName &&
        it.resolveInfo.serviceInfo.name == UniversalAgentAccessibilityService::class.java.name
    }
}
