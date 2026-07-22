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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saturnmask.gallery.R
import com.saturnmask.gallery.common.PermissionResult

/**
 * Prompts for permission before a Coder tab writeFile/applyPatch call touches disk. Mirrors
 * [com.saturnmask.gallery.customtasks.agentchat.McpToolCallPermissionDialog]'s structure, but with
 * only two buttons — deliberately no "Always Allow" tier, unlike the MCP dialog: every
 * writeFile/applyPatch call re-prompts.
 */
@Composable
fun FileWritePermissionDialog(path: String, preview: String, onResult: (PermissionResult) -> Unit) {
  AlertDialog(
    onDismissRequest = { onResult(PermissionResult.DENY) },
    title = {
      Text(
        stringResource(R.string.file_write_permission_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 22.sp, stepSize = 1.sp),
      )
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.file_write_path_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
          )
          Text(text = path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.file_write_preview_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
          )
          Text(text = preview, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
      }
    },
    confirmButton = {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { onResult(PermissionResult.ALLOW_ONCE) }, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.file_write_allow))
        }
        OutlinedButton(onClick = { onResult(PermissionResult.DENY) }, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.file_write_dont_allow))
        }
      }
    },
  )
}
