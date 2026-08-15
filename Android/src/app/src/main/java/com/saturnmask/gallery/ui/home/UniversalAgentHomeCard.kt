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

package com.saturnmask.gallery.ui.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.saturnmask.gallery.R
import com.saturnmask.gallery.customtasks.universalagent.InstalledAppPickerBottomSheet
import com.saturnmask.gallery.customtasks.universalagent.UniversalAgentDisclaimerDialog
import com.saturnmask.gallery.customtasks.universalagent.isUniversalAgentAccessibilityServiceEnabled
import com.saturnmask.gallery.customtasks.universalagent.requestIgnoreBatteryOptimizations
import com.saturnmask.gallery.customtasks.universalagent.universalAgentEnablementStateFrom
import com.saturnmask.gallery.customtasks.universalagent.universalAgentSettingsStoreFrom

/**
 * "Universal Agent mode" entry point on the home screen — mirrors [RecentChatsSection]'s compact
 * row + expandable-sheet shape, but always visible (this is a feature entry point, not a dynamic
 * list, so there's no early-return-if-empty). Shares its enablement state with Agent Chat's own
 * "+" menu toggle via [com.saturnmask.gallery.customtasks.universalagent.UniversalAgentEnablementState]
 * — toggling here or there updates the same value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UniversalAgentHomeCard(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val enablementState = remember { universalAgentEnablementStateFrom(context) }
  val settingsStore = remember { universalAgentSettingsStoreFrom(context) }
  val requested by enablementState.requested.collectAsState()
  var serviceEnabled by remember { mutableStateOf(isUniversalAgentAccessibilityServiceEnabled(context)) }
  var scopedPackageName by remember { mutableStateOf(settingsStore.getScopedPackageName()) }
  var scopedAppLabel by remember { mutableStateOf(settingsStore.getScopedAppLabel()) }
  var showExpanded by remember { mutableStateOf(false) }
  var showDisclaimer by remember { mutableStateOf(false) }
  var showAppPicker by remember { mutableStateOf(false) }

  // Re-checked whenever this card enters composition (e.g. navigating back to Home after a
  // Settings round-trip) — the same live OS state AgentChatScreen re-polls on ON_RESUME.
  LaunchedEffect(Unit) { serviceEnabled = isUniversalAgentAccessibilityServiceEnabled(context) }

  val statusText =
    when {
      !requested -> stringResource(R.string.universal_agent_home_card_status_off)
      !serviceEnabled -> stringResource(R.string.universal_agent_home_card_status_enable_in_settings)
      scopedPackageName == null -> stringResource(R.string.universal_agent_home_card_status_full_device)
      else ->
        stringResource(
          R.string.universal_agent_home_card_status_scoped,
          scopedAppLabel.ifEmpty { scopedPackageName ?: "" },
        )
    }

  Column(modifier = modifier.padding(horizontal = 24.dp)) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
          .clickable { showExpanded = true }
          .padding(vertical = 12.dp, horizontal = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.Rounded.TouchApp, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(stringResource(R.string.universal_agent_home_card_title), style = MaterialTheme.typography.bodyMedium)
        Text(
          statusText,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }

  if (showExpanded) {
    ModalBottomSheet(onDismissRequest = { showExpanded = false }) {
      Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            stringResource(R.string.universal_agent_home_card_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
          )
          IconButton(onClick = { showExpanded = false }) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_icon))
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(stringResource(R.string.universal_agent_home_card_title), modifier = Modifier.weight(1f))
          Switch(
            checked = requested,
            onCheckedChange = { enabled ->
              if (enabled) {
                showDisclaimer = true
              } else {
                enablementState.requested.value = false
              }
            },
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              stringResource(R.string.universal_agent_home_card_scope_row_label),
              style = MaterialTheme.typography.bodyMedium,
            )
            Text(
              if (scopedPackageName != null) scopedAppLabel.ifEmpty { scopedPackageName ?: "" }
              else stringResource(R.string.universal_agent_home_card_scope_none),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          TextButton(onClick = { showAppPicker = true }) {
            Text(stringResource(R.string.universal_agent_home_card_change))
          }
          if (scopedPackageName != null) {
            TextButton(
              onClick = {
                settingsStore.clearScopedApp()
                scopedPackageName = null
                scopedAppLabel = ""
              }
            ) {
              Text(stringResource(R.string.universal_agent_home_card_clear))
            }
          }
        }

        Text(
          stringResource(R.string.universal_agent_home_card_explanation),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
    }
  }

  if (showDisclaimer) {
    UniversalAgentDisclaimerDialog(
      onDismiss = { showDisclaimer = false },
      onConfirm = {
        showDisclaimer = false
        enablementState.requested.value = true
        if (!serviceEnabled) {
          context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        // The AccessibilityService itself has no foreground-service protection — ask to be
        // exempted from OEM battery-optimization killing too, or aggressive battery managers can
        // silently kill it in the background. No-op if already exempted.
        requestIgnoreBatteryOptimizations(context)
      },
    )
  }

  if (showAppPicker) {
    InstalledAppPickerBottomSheet(
      onDismiss = { showAppPicker = false },
      onAppSelected = { packageName, label ->
        settingsStore.setScopedApp(packageName, label)
        scopedPackageName = packageName
        scopedAppLabel = label
        showAppPicker = false
      },
    )
  }
}
