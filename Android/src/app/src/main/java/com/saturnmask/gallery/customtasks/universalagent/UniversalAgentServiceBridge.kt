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

/**
 * Connects [UniversalAgentToolsImpl] (constructed once per [com.saturnmask.gallery.customtasks
 * .agentchat.AgentChatTask], living inside the chat UI's coroutine scope) to the live
 * [UniversalAgentAccessibilityService] instance, which Android instantiates/destroys entirely on
 * its own binder lifecycle whenever the user toggles the service in Settings — independently of
 * any Activity. [UniversalAgentAccessibilityService.onServiceConnected]/`onDestroy` call
 * [attach]/[detach]; every tool method funnels through [withService], which fails cleanly instead
 * of crashing when the service isn't currently bound.
 */
@Singleton
class UniversalAgentServiceBridge @Inject constructor() {

  @Volatile private var service: UniversalAgentAccessibilityService? = null

  fun attach(instance: UniversalAgentAccessibilityService) {
    service = instance
  }

  fun detach(instance: UniversalAgentAccessibilityService) {
    if (service === instance) service = null
  }

  val isConnected: Boolean
    get() = service != null

  fun <T> withService(action: (UniversalAgentAccessibilityService) -> T): Result<T> {
    val current =
      service
        ?: return Result.failure(
          IllegalStateException(
            "Universal Agent accessibility service is not connected. Ask the user to enable it " +
              "in Settings > Accessibility."
          )
        )
    return runCatching { action(current) }
  }
}

/**
 * [UniversalAgentToolsImpl] isn't Hilt-constructed — same reasoning as [com.saturnmask.gallery
 * .customtasks.coder.CoderToolsImpl]: it's a `val` field on a plain-`@Inject constructor()`
 * [com.saturnmask.gallery.customtasks.agentchat.AgentChatTask] instance, not field-injected.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UniversalAgentServiceBridgeEntryPoint {
  fun universalAgentServiceBridge(): UniversalAgentServiceBridge
}

fun universalAgentServiceBridgeFrom(context: Context): UniversalAgentServiceBridge =
  EntryPointAccessors.fromApplication(
      context.applicationContext,
      UniversalAgentServiceBridgeEntryPoint::class.java,
    )
    .universalAgentServiceBridge()
