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

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.saturnmask.gallery.R

/** Folder-picker + reindex row rendered below the Coder tab's message list, mirroring
 *  AgentModeTriggers' chip-row placement in AgentChatScreen.kt. */
@Composable
fun CoderProjectPickerRow(uiState: CoderUiState, onPick: (Uri) -> Unit, onReindex: () -> Unit) {
  // Best-effort, provider-dependent bias toward Downloads — OpenDocumentTree() forwards a
  // non-null contract input as DocumentsContract.EXTRA_INITIAL_URI, but some storage providers
  // may ignore it. The isolation guarantee (see CoderFileResolver) applies equally regardless of
  // which folder the user actually picks or creates.
  val downloadsUri = remember {
    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")
  }
  val pickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) onPick(uri)
    }

  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text =
          uiState.projectRootUri?.let { uiState.projectDisplayName.ifEmpty { it.toString() } }
            ?: stringResource(R.string.coder_no_project_selected),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      if (uiState.indexing) {
        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(16.dp))
      } else if (uiState.projectRootUri != null) {
        TextButton(onClick = onReindex) { Text(stringResource(R.string.coder_reindex)) }
      }
      TextButton(onClick = { pickerLauncher.launch(downloadsUri) }) {
        Text(
          stringResource(
            if (uiState.projectRootUri == null) R.string.coder_pick_project else R.string.coder_change_project
          )
        )
      }
    }
    if (uiState.projectRootUri == null) {
      Text(
        text = stringResource(R.string.coder_project_folder_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
