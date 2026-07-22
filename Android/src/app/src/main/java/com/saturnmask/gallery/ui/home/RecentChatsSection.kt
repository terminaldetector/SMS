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

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.saturnmask.gallery.proto.ChatSessionProto
import com.saturnmask.gallery.ui.common.chat.ChatHistorySideSheetContent

/**
 * "Recent chats" preview strip for the home screen, plus a "Show all" sheet reusing
 * [ChatHistorySideSheetContent] as-is (same list, same delete/clear-all UI you already know from
 * inside a chat screen's history drawer).
 *
 * Reads from the same global `UserData.chatSessionsList` every chat task writes to (see
 * [RecentChatsViewModel]) — sessions from every model/task show up here together, each labeled
 * with which model it used.
 *
 * Tapping a session invokes [onSessionClicked], which the caller (`HomeScreen.kt`) wires to
 * navigate straight into that session's model/task with a `sessionId` nav arg — skipping the model
 * picker, since the tapped [ChatSessionProto] already carries `originalModel`/`taskId`.
 * `AgentChatScreen` picks the arg up and calls the same `ChatViewModel.loadSession` the in-chat
 * history drawer's `onHistoryItemClicked` (in `ChatView.kt`) uses, so both entry points share one
 * "load session by id" implementation rather than duplicating it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentChatsSection(
  onSessionClicked: (ChatSessionProto) -> Unit,
  onNewChatClicked: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: RecentChatsViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val recentSessions by viewModel.recentSessions.collectAsState()
  val fullHistory by viewModel.fullHistory.collectAsState()
  var showAllSheet by remember { mutableStateOf(false) }

  if (recentSessions.isEmpty()) return // Nothing to show yet — no history section at all.

  Column(modifier = modifier.padding(horizontal = 24.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Recent chats",
        style =
          MaterialTheme.typography.headlineSmall.copy(fontWeight = MaterialTheme.typography.titleMedium.fontWeight),
        color = MaterialTheme.colorScheme.onSurface,
      )
      TextButton(onClick = { showAllSheet = true }) { Text("Show all") }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      for (session in recentSessions) {
        RecentChatRow(session = session, onClick = { onSessionClicked(session) })
      }
    }
  }

  if (showAllSheet) {
    ModalBottomSheet(onDismissRequest = { showAllSheet = false }) {
      ChatHistorySideSheetContent(
        history = fullHistory,
        onHistoryItemClicked = { sessionId ->
          val session = fullHistory.firstOrNull { it.sessionId == sessionId }
          showAllSheet = false
          if (session != null) onSessionClicked(session)
        },
        onHistoryItemDeleted = { sessionId -> viewModel.deleteSession(sessionId, context) },
        onHistoryItemsDeleteAll = {
          viewModel.clearAllSessions(context)
          showAllSheet = false
        },
        onNewChatClicked = {
          showAllSheet = false
          onNewChatClicked()
        },
        onDismissed = { showAllSheet = false },
      )
    }
  }
}

@Composable
private fun RecentChatRow(session: ChatSessionProto, onClick: () -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        .clickable(onClick = onClick)
        .padding(vertical = 10.dp, horizontal = 14.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = session.title,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (session.originalModel.isNotBlank()) {
        Text(
          text = session.originalModel,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (session.timestampMs > 0) {
      Text(
        text =
          DateUtils.getRelativeTimeSpanString(
              session.timestampMs,
              System.currentTimeMillis(),
              DateUtils.MINUTE_IN_MILLIS,
            )
            .toString(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
