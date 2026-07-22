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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The "triggers" row from the interface roadmap: Actions / RAG mode toggles on a single unified
 * chat screen, instead of separate tabs.
 *
 * v2: replaces the plain single-line [androidx.compose.material3.FilterChip]s from the first
 * pass with a two-line status chip — a bold on/off label plus a small caption line — because
 * user feedback was that a small chip in a row of controls doesn't communicate *what's actually
 * active* at a glance (e.g. "RAG is on, but did I actually index anything?"). Standard Material3
 * `FilterChip` only has room for one line of text, so this is a small custom `Surface`-based
 * toggle instead — same selection semantics (`Modifier.selectable`), same color tokens
 * (`MaterialTheme.colorScheme`), so it doesn't introduce a new visual language, just a taller one.
 *
 * Reasoning is deliberately NOT one of these chips — see the original rationale this comment
 * replaces: it's driven by the pre-existing native "Enable thinking" per-model switch
 * (`ConfigKeys.ENABLE_THINKING`). That's unchanged by this pass.
 */
@Composable
fun AgentModeTriggers(
  actionsEnabled: Boolean,
  ragEnabled: Boolean,
  onActionsToggled: (Boolean) -> Unit,
  // Opens the unified Skills sheet (SkillsManagerBottomSheet) — the gear icon zone of the chip,
  // independent from the main body's direct on/off tap zone. See the roadmap "Skills" merge.
  onSkillsSettingsClicked: () -> Unit,
  onRagToggled: (Boolean) -> Unit,
  // Opens the consolidated RAG settings sheet (RagManagerBottomSheet) via the chip's gear icon —
  // the main body now toggles ragEnabled directly instead of only opening the sheet. See the
  // roadmap "RAG/Web Search promotion" pass.
  onRagSettingsClicked: () -> Unit,
  webSearchEnabled: Boolean,
  webSearchCaption: String,
  onWebSearchToggled: (Boolean) -> Unit,
  onWebSearchSettingsClicked: () -> Unit,
  modifier: Modifier = Modifier,
  skillCount: Int = 0,
  mcpToolsCount: Int = 0,
  mobileActionsEnabled: Boolean = false,
  ragDocumentCount: Int = 0,
  ragChunkCount: Int = 0,
  ragIndexing: Boolean = false,
  // Chunk-level progress while ragIndexing is true — see RagManagerViewModel.importProgress.
  // total == 0 means "no per-chunk progress yet" (still parsing/loading a model), shown as a
  // plain "Indexing…" instead of a fraction.
  ragIndexingDone: Int = 0,
  ragIndexingTotal: Int = 0,
) {
  Row(
    modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    TriggerChip(
      selected = actionsEnabled,
      onClick = { onActionsToggled(!actionsEnabled) },
      onSettingsClick = onSkillsSettingsClicked,
      label = "Skills",
      caption = skillsCaption(actionsEnabled, skillCount, mcpToolsCount, mobileActionsEnabled),
      iconSelected = Icons.Filled.Bolt,
      iconUnselected = Icons.Outlined.Bolt,
      contentDescription =
        "Skills ${if (actionsEnabled) "enabled" else "disabled"}. Tap to " +
          (if (actionsEnabled) "disable" else "enable") +
          ", or use the settings button to manage skills, MCP servers, and Mobile Actions.",
    )

    TriggerChip(
      selected = ragEnabled,
      onClick = { onRagToggled(!ragEnabled) },
      onSettingsClick = onRagSettingsClicked,
      label = "RAG",
      caption =
        ragCaption(ragEnabled, ragDocumentCount, ragChunkCount, ragIndexing, ragIndexingDone, ragIndexingTotal),
      iconSelected = Icons.Filled.MenuBook,
      iconUnselected = Icons.Outlined.MenuBook,
      trailing = {
        AnimatedVisibility(visible = ragIndexing, enter = fadeIn(), exit = fadeOut()) {
          if (ragIndexingTotal > 0) {
            CircularProgressIndicator(
              progress = { ragIndexingDone.toFloat() / ragIndexingTotal },
              modifier = Modifier.size(12.dp),
              strokeWidth = 1.5.dp,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
          } else {
            CircularProgressIndicator(
              modifier = Modifier.size(12.dp),
              strokeWidth = 1.5.dp,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
          }
        }
      },
      contentDescription =
        "R A G ${if (ragEnabled) "enabled" else "disabled"}, " +
          "$ragDocumentCount documents, $ragChunkCount chunks indexed" +
          (if (ragIndexing) ", currently indexing $ragIndexingDone of $ragIndexingTotal chunks" else "") +
          ". Tap to toggle, or use the settings button to open RAG settings.",
    )

    TriggerChip(
      selected = webSearchEnabled,
      onClick = { onWebSearchToggled(!webSearchEnabled) },
      onSettingsClick = onWebSearchSettingsClicked,
      label = "Web search",
      caption = webSearchCaption,
      iconSelected = Icons.Filled.Search,
      iconUnselected = Icons.Outlined.Search,
      contentDescription =
        "Web search ${if (webSearchEnabled) "enabled" else "disabled"}. Tap to toggle, or use the " +
          "settings button to open web search settings.",
    )
  }
}

private fun ragCaption(
  enabled: Boolean,
  documentCount: Int,
  chunkCount: Int,
  indexing: Boolean,
  indexingDone: Int,
  indexingTotal: Int,
): String =
  when {
    indexing && indexingTotal > 0 -> "Indexing $indexingDone/$indexingTotal"
    indexing -> "Indexing…"
    !enabled -> "Off"
    documentCount == 0 -> "No documents yet"
    else -> "$documentCount doc${if (documentCount == 1) "" else "s"} · $chunkCount chunks"
  }

private fun skillsCaption(
  enabled: Boolean,
  skillCount: Int,
  mcpToolsCount: Int,
  mobileActionsEnabled: Boolean,
): String {
  if (!enabled) return "Off"
  val parts = mutableListOf<String>()
  if (skillCount > 0) parts.add("$skillCount skill${if (skillCount == 1) "" else "s"}")
  if (mcpToolsCount > 0) parts.add("$mcpToolsCount MCP tool${if (mcpToolsCount == 1) "" else "s"}")
  if (mobileActionsEnabled) parts.add("Mobile Actions")
  return if (parts.isEmpty()) "Tools enabled" else parts.joinToString(" · ")
}

/**
 * A two-line, tap-target-friendly toggle with two independently-tappable zones in one `Surface`:
 * the icon+label+caption body (tap to toggle on/off directly) and a small trailing gear button
 * (tap to open the feature's settings sheet) — the roadmap's "one-click gear-icon access to
 * settings" pass replacing the earlier design where RAG/Web Search chips only opened a sheet and
 * never toggled directly. Filled background + border when selected so the on/off state reads
 * clearly even at a glance.
 */
@Composable
private fun TriggerChip(
  selected: Boolean,
  onClick: () -> Unit,
  onSettingsClick: () -> Unit,
  label: String,
  caption: String,
  iconSelected: androidx.compose.ui.graphics.vector.ImageVector,
  iconUnselected: androidx.compose.ui.graphics.vector.ImageVector,
  contentDescription: String,
  modifier: Modifier = Modifier,
  trailing: @Composable (() -> Unit)? = null,
) {
  val containerColor =
    if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
  val contentColor =
    if (selected) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
  val borderColor =
    if (selected) MaterialTheme.colorScheme.secondary else Color.Transparent

  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(14.dp),
    color = containerColor,
    contentColor = contentColor,
    border = BorderStroke(1.dp, borderColor),
    tonalElevation = if (selected) 2.dp else 0.dp,
  ) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
      Row(
        modifier =
          Modifier.semantics { role = Role.Switch; this.contentDescription = contentDescription }
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = if (selected) iconSelected else iconUnselected,
          contentDescription = null, // described on the toggle body above instead
          modifier = Modifier.size(18.dp),
        )
        Column {
          Text(text = label, style = MaterialTheme.typography.labelLarge)
          Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.85f),
          )
        }
        trailing?.invoke()
      }

      VerticalDivider(modifier = Modifier.padding(vertical = 8.dp).size(width = 1.dp, height = 20.dp))

      IconButton(onClick = onSettingsClick, modifier = Modifier.size(36.dp)) {
        Icon(
          imageVector = Icons.Outlined.Settings,
          contentDescription = "$label settings",
          modifier = Modifier.size(16.dp),
        )
      }
    }
  }
}
