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

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saturnmask.gallery.proto.ChatSessionProto
import com.saturnmask.gallery.proto.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val PREVIEW_COUNT = 5

/**
 * Backs the "Recent chats" section on the home screen. `UserData.chatSessionsList` is a single,
 * global list shared across every chat task/model (see [ChatSessionProto]'s `original_model` /
 * `task_id` fields) — same underlying data [ChatViewModel.historySessions] reads, just not
 * scoped to one particular task's ViewModel, since the home screen isn't task-scoped.
 */
@HiltViewModel
class RecentChatsViewModel @Inject constructor(private val userDataDataStore: DataStore<UserData>) :
  ViewModel() {

  private val allSessions: StateFlow<List<ChatSessionProto>> =
    userDataDataStore.data
      .map { userData -> userData.chatSessionsList.sortedByDescending { it.timestampMs } }
      .stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5000), initialValue = emptyList())

  /** Top [PREVIEW_COUNT] sessions, most recent first — for the home screen preview strip. */
  val recentSessions: StateFlow<List<ChatSessionProto>> =
    allSessions
      .map { it.take(PREVIEW_COUNT) }
      .stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5000), initialValue = emptyList())

  /** Every session — for the "Show all" sheet (reuses [ChatHistorySideSheetContent] as-is). */
  val fullHistory: StateFlow<List<ChatSessionProto>> = allSessions

  // Mirrors ChatViewModel.deleteSession/clearAllSessions exactly (same cache-file cleanup for
  // img_/audio_ prefixed files, same Dispatchers.IO) — duplicated rather than shared because
  // ChatViewModel is abstract and task-scoped, and this ViewModel intentionally isn't.
  fun deleteSession(sessionId: String, context: Context? = null) {
    viewModelScope.launch(Dispatchers.IO) {
      if (context != null) {
        val files = context.cacheDir.listFiles()
        files?.forEach { file ->
          if (
            file.name.startsWith("img_${sessionId}_") || file.name.startsWith("audio_${sessionId}_")
          ) {
            file.delete()
          }
        }
      }
      userDataDataStore.updateData { userData ->
        val currentSessions = userData.chatSessionsList.filter { it.sessionId != sessionId }
        userData.toBuilder().clearChatSessions().addAllChatSessions(currentSessions).build()
      }
    }
  }

  fun clearAllSessions(context: Context? = null) {
    viewModelScope.launch(Dispatchers.IO) {
      if (context != null) {
        val files = context.cacheDir.listFiles()
        files?.forEach { file ->
          if (file.name.startsWith("img_") || file.name.startsWith("audio_")) {
            file.delete()
          }
        }
      }
      userDataDataStore.updateData { userData -> userData.toBuilder().clearChatSessions().build() }
    }
  }
}
