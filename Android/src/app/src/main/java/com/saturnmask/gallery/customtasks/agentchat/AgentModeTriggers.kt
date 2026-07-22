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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.saturnmask.gallery.domain.rag.RagDocumentInfo

/**
 * The agent "triggers" (Skills / RAG / Web search) rendered as a compact, minimalist Material
 * Design 3 icon-toggle row that lives right next to the message input, instead of the earlier
 * bulky two-line chip row that sat at the top of the screen above the message list.
 *
 * Each control is a single circular icon toggle:
 * - tap toggles the feature on/off (filled tonal when on, quiet when off);
 * - long-press opens that feature's settings sheet (Skills / RAG / Web search).
 *
 * Skills settings also cover MCP tools and Mobile Actions (the unified Skills sheet), so the whole
 * RAG / Web search / MCP / Agent-actions system is reachable from these three icons.
 *
 * Reasoning is deliberately NOT one of these — it's driven by the pre-existing native "Enable
 * thinking" per-model switch (`ConfigKeys.ENABLE_THINKING`).
 */
@Composable
fun AgentModeTriggers(
  actionsEnabled: Boolean,
  ragEnabled: Boolean,
  onActionsToggled: (Boolean) -> Unit,
  // Opens the unified Skills sheet (SkillsManagerBottomSheet) — long-press on the Skills icon.
  onSkillsSettingsClicked: () -> Unit,
  onRagToggled: (Boolean) -> Unit,
  // Opens the consolidated RAG settings sheet (RagManagerBottomSheet) — long-press on the RAG icon.
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
  // plain spinner instead of a fraction.
  ragIndexingDone: Int = 0,
  ragIndexingTotal: Int = 0,
) {
  Row(
    modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    TriggerIconToggle(
      selected = actionsEnabled,
      onToggle = { onActionsToggled(!actionsEnabled) },
      onSettings = onSkillsSettingsClicked,
      iconSelected = Icons.Filled.Bolt,
      iconUnselected = Icons.Outlined.Bolt,
      contentDescription =
        "Skills ${if (actionsEnabled) "enabled" else "disabled"} " +
          "(${skillsCaption(actionsEnabled, skillCount, mcpToolsCount, mobileActionsEnabled)}). " +
          "Tap to toggle, long-press to manage skills, MCP servers, and Mobile Actions.",
    )

    TriggerIconToggle(
      selected = ragEnabled,
      onToggle = { onRagToggled(!ragEnabled) },
      onSettings = onRagSettingsClicked,
      iconSelected = Icons.Filled.MenuBook,
      iconUnselected = Icons.Outlined.MenuBook,
      showProgress = ragIndexing,
      progress =
        if (ragIndexing && ragIndexingTotal > 0) ragIndexingDone.toFloat() / ragIndexingTotal
        else null,
      contentDescription =
        "R A G ${if (ragEnabled) "enabled" else "disabled"} " +
          "(${ragCaption(ragEnabled, ragDocumentCount, ragChunkCount, ragIndexing, ragIndexingDone, ragIndexingTotal)}). " +
          "Tap to toggle, long-press for RAG settings.",
    )

    TriggerIconToggle(
      selected = webSearchEnabled,
      onToggle = { onWebSearchToggled(!webSearchEnabled) },
      onSettings = onWebSearchSettingsClicked,
      iconSelected = Icons.Filled.Search,
      iconUnselected = Icons.Outlined.Search,
      contentDescription =
        "Web search ${if (webSearchEnabled) "enabled" else "disabled"} ($webSearchCaption). " +
          "Tap to toggle, long-press for web search settings.",
    )
  }
}

/**
 * A single circular Material 3 icon toggle: filled-tonal container when selected, quiet/transparent
 * when not. Tap toggles; long-press opens settings. An optional small progress ring is overlaid for
 * the RAG indexing state.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TriggerIconToggle(
  selected: Boolean,
  onToggle: () -> Unit,
  onSettings: () -> Unit,
  iconSelected: ImageVector,
  iconUnselected: ImageVector,
  contentDescription: String,
  modifier: Modifier = Modifier,
  showProgress: Boolean = false,
  progress: Float? = null,
) {
  val containerColor =
    if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
  val contentColor =
    if (selected) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

  Box(
    modifier =
      modifier
        .size(40.dp)
        .clip(CircleShape)
        .background(containerColor)
        .combinedClickable(onClick = onToggle, onLongClick = onSettings)
        .semantics {
          role = Role.Switch
          this.contentDescription = contentDescription
        },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = if (selected) iconSelected else iconUnselected,
      contentDescription = null,
      tint = contentColor,
      modifier = Modifier.size(20.dp),
    )
    if (showProgress) {
      if (progress != null) {
        CircularProgressIndicator(
          progress = { progress },
          modifier = Modifier.size(34.dp),
          strokeWidth = 2.dp,
          color = MaterialTheme.colorScheme.primary,
        )
      } else {
        CircularProgressIndicator(
          modifier = Modifier.size(34.dp),
          strokeWidth = 2.dp,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
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
 * A horizontally-scrollable row of Material 3 input chips for the documents currently attached to
 * the chat (RAG). Rendered right above the input so the user can see — and remove — the files that
 * are in context for their next message. Renders nothing when there are no attached documents.
 */
@Composable
fun AttachedFilesRow(
  documents: List<RagDocumentInfo>,
  onRemove: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (documents.isEmpty()) return
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (doc in documents) {
      InputChip(
        selected = true,
        onClick = { onRemove(doc.id) },
        label = { Text(doc.name, maxLines = 1) },
        leadingIcon = {
          Icon(
            imageVector = Icons.Outlined.Description,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
        },
        trailingIcon = {
          Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Remove ${doc.name}",
            modifier = Modifier.size(16.dp).clickable { onRemove(doc.id) },
          )
        },
      )
    }
  }
}
