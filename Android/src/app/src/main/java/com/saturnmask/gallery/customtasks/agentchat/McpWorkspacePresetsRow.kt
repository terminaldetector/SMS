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

package com.saturnmask.gallery.customtasks.agentchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saturnmask.gallery.domain.mcp.McpWorkspacePreset
import com.saturnmask.gallery.domain.mcp.McpWorkspacePresets

/**
 * A row of one-tap presets for Google's official Workspace MCP servers (Gmail, Drive, Calendar,
 * Contacts, Chat) — drop this into [McpManagerBottomSheet], above or below the existing manual
 * "paste a server URL" field. Tapping a preset should call your EXISTING add-server function with
 * [McpWorkspacePreset.serverUrl] pre-filled — this composable doesn't call any MCP/OAuth API
 * itself, it only hands you the (name, url) pair; your existing add-server + OAuth flow (the one
 * already used for manually-pasted URLs) takes it from there unchanged.
 *
 * ⚠️ Google marks these as a **Developer Preview** feature (as of when this was written) — the
 * endpoints, required scopes, or availability could change without the usual GA stability
 * guarantees. Worth a "beta" label in the UI rather than presenting it as a stable integration.
 */
@Composable
fun McpWorkspacePresetsRow(
  onPresetSelected: (name: String, serverUrl: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    Text(
      text = "Google Workspace (beta)",
      style = MaterialTheme.typography.labelLarge,
    )
    Text(
      text = "Official Google servers — requires signing in with your Google account.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(bottom = 8.dp),
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      items(McpWorkspacePresets.ALL, key = { it.id }) { preset ->
        AssistChip(
          onClick = { onPresetSelected(preset.displayName, preset.serverUrl) },
          label = { Text(preset.displayName) },
        )
      }
    }
  }
}
