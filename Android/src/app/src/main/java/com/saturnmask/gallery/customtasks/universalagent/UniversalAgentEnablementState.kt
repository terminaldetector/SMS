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

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Whether the user has opted into Universal Agent ("requested" — the other half is
 * [isUniversalAgentAccessibilityServiceEnabled], the live OS accessibility-service state). Shared
 * across [com.saturnmask.gallery.customtasks.agentchat.AgentChatScreen]'s "+" menu toggle and the
 * Home screen's [com.saturnmask.gallery.ui.home.UniversalAgentHomeCard] — both need to read AND
 * write the same value, which plain `rememberSaveable` composable-local state can't do across two
 * separate screens.
 *
 * Deliberately **in-memory only** (mirrors [UniversalAgentServiceBridge]'s exact shape, not
 * persisted to `SharedPreferences`/disk) — this preserves the original, deliberate Round-11 safety
 * property that Universal Agent never silently stays enabled forever across app restarts; a
 * process-lifetime singleton resets to `false` on process death exactly the same way the old
 * per-screen `rememberSaveable` did, just shared instead of duplicated.
 */
@Singleton
class UniversalAgentEnablementState @Inject constructor() {
  val requested = MutableStateFlow(false)
}

/**
 * [UniversalAgentEnablementState] is read from both `AgentChatScreen.kt` (a `@Composable`, which
 * *can* use `hiltViewModel()`-adjacent injection) and, in principle, any non-Hilt call site — this
 * accessor keeps both paths uniform with [universalAgentServiceBridgeFrom]'s existing shape.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UniversalAgentEnablementEntryPoint {
  fun universalAgentEnablementState(): UniversalAgentEnablementState
}

fun universalAgentEnablementStateFrom(context: Context): UniversalAgentEnablementState =
  EntryPointAccessors.fromApplication(
      context.applicationContext,
      UniversalAgentEnablementEntryPoint::class.java,
    )
    .universalAgentEnablementState()
