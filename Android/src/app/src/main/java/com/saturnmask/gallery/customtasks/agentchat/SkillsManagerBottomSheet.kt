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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.saturnmask.gallery.R
import kotlinx.coroutines.launch

/**
 * The unified "Skills" entry point from the roadmap consolidation: what used to be a separate
 * "Actions" toggle plus standalone "Skills"/"MCP" buttons plus a "Mobile Actions" overflow-menu
 * item are all reachable from here — Mobile Actions as a direct row with its own switch, and
 * Skills/MCP as two tabs sharing one sheet instead of two.
 *
 * `readUrl` is surfaced here only as an informational line, not a toggle: it's actually gated by
 * the "Web search" trigger chip's own on/off state (see AgentModeTriggers.kt), not by anything in
 * this sheet. Showing it here (for visibility, per the roadmap's "anything controllable should be
 * visible" principle) while gating it there (where its suppression logic already lives) is the
 * honest resolution — a toggle here would either be redundant with or contradict the Web Search
 * chip's own switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsManagerBottomSheet(
  agentTools: AgentTools,
  skillManagerViewModel: SkillManagerViewModel,
  mcpManagerViewModel: McpManagerViewModel,
  mobileActionsEnabled: Boolean,
  onMobileActionsToggled: (Boolean) -> Unit,
  skillCount: Int,
  mcpToolsCount: Int,
  onDismiss: (contentChanged: Boolean) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  var selectedTabIndex by remember { mutableIntStateOf(0) }

  var savedSelectedSkillsNamesAndDescriptions by remember { mutableStateOf("") }
  var savedSelectedMcpsAndToolsSummary by remember { mutableStateOf("") }

  LaunchedEffect(Unit) {
    savedSelectedSkillsNamesAndDescriptions =
      skillManagerViewModel.getSelectedSkillsNamesAndDescriptions()
    savedSelectedMcpsAndToolsSummary = mcpManagerViewModel.getSelectedMcpsAndToolsSummary()
  }

  fun hasContentChanged(): Boolean =
    savedSelectedSkillsNamesAndDescriptions !=
      skillManagerViewModel.getSelectedSkillsNamesAndDescriptions() ||
      savedSelectedMcpsAndToolsSummary != mcpManagerViewModel.getSelectedMcpsAndToolsSummary()

  val requestDismiss = {
    scope.launch {
      sheetState.hide()
      onDismiss(hasContentChanged())
    }
    Unit
  }

  ModalBottomSheet(
    onDismissRequest = { onDismiss(hasContentChanged()) },
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      // Title row.
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          stringResource(R.string.skills),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = requestDismiss) {
          Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_icon))
        }
      }

      // Mobile Actions row — moved out of the "+" overflow menu so it's a directly visible,
      // directly toggleable entry rather than something hidden behind a discovery step.
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Filled.Bolt, contentDescription = null)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
          Text("Mobile Actions", style = MaterialTheme.typography.bodyLarge)
          Text(
            "Contacts, email, and calendar actions on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(checked = mobileActionsEnabled, onCheckedChange = onMobileActionsToggled)
      }

      // Informational only — readUrl's actual on/off gating lives on the Web Search trigger chip,
      // not here (see kdoc above).
      Text(
        "Reading web pages (readUrl) is controlled by the \"Web search\" toggle, not by Skills.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
      )

      TabRow(selectedTabIndex = selectedTabIndex) {
        Tab(
          selected = selectedTabIndex == 0,
          onClick = { selectedTabIndex = 0 },
          text = { Text("${stringResource(R.string.skills)} ($skillCount)") },
        )
        Tab(
          selected = selectedTabIndex == 1,
          onClick = { selectedTabIndex = 1 },
          text = { Text("MCP ($mcpToolsCount)") },
        )
      }

      // Wrapped in a weighted Box so each tab's Modifier.fillMaxSize() root resolves against a
      // bounded height (the sheet's remaining space) instead of the Column's own wrap-content
      // sizing.
      Box(modifier = Modifier.weight(1f)) {
        when (selectedTabIndex) {
          0 ->
            SkillManagerBottomSheetContent(
              agentTools = agentTools,
              skillManagerViewModel = skillManagerViewModel,
              showHeader = false,
              onRequestDismiss = requestDismiss,
              mobileActionsEnabled = mobileActionsEnabled,
            )
          1 ->
            McpManagerBottomSheetContent(
              mcpManagerViewModel = mcpManagerViewModel,
              showHeader = false,
              onRequestDismiss = requestDismiss,
            )
        }
      }
    }
  }
}
