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

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.saturnmask.gallery.R

/**
 * Shown every time Universal Agent is toggled on (not persisted-as-seen-once — a stricter
 * posture than [com.saturnmask.gallery.customtasks.agentchat.AddMcpDisclaimerDialog], which this
 * mirrors structurally), since enabling it grants the agent full-device screen-read and control
 * scope once the accompanying AccessibilityService is also enabled in system Settings.
 */
@Composable
fun UniversalAgentDisclaimerDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.universal_agent_disclaimer_dialog_title)) },
    text = { Text(stringResource(R.string.universal_agent_disclaimer_dialog_content)) },
    confirmButton = {
      Button(onClick = onConfirm) { Text(stringResource(R.string.disclaimer_dialog_agree)) }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
    },
  )
}
