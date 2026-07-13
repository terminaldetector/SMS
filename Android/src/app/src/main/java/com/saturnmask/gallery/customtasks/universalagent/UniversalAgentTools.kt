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

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.saturnmask.gallery.common.AgentAction
import com.saturnmask.gallery.common.AskUniversalAgentActionPermissionAction
import com.saturnmask.gallery.common.PermissionResult
import com.saturnmask.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

private const val MAX_ELEMENTS = 500
private const val MAX_RESOLVE_CANDIDATES = 2000
private const val MAX_DEPTH = 50
private const val BOUNDS_TOLERANCE_PX = 20
private const val TAP_GESTURE_DURATION_MS = 60L
private const val SWIPE_GESTURE_DURATION_MS = 300L

/** A snapshot-scoped interactive element captured by [UniversalAgentToolsImpl.listScreenElements].
 *  [id] is only valid until the next `listScreenElements()` call. */
data class ScreenElement(
  val id: Int,
  val className: String?,
  val text: String?,
  val contentDescription: String?,
  val viewIdResourceName: String?,
  val boundsInScreen: Rect,
  val isClickable: Boolean,
  val isEditable: Boolean,
  val isCheckable: Boolean,
  val isChecked: Boolean,
  val isScrollable: Boolean,
  val packageName: String?,
)

private enum class SwipeDirection {
  UP,
  DOWN,
  LEFT,
  RIGHT;

  companion object {
    // No precedent anywhere in this codebase for a typed enum @ToolParam (IntentAction.from(...)
    // is the established string-to-enum pattern for tool-facing string params) — keep the tool
    // signature a plain String and parse/validate it here instead.
    fun from(value: String): SwipeDirection? = entries.find { it.name.equals(value.trim(), ignoreCase = true) }
  }
}

interface UniversalAgentTools : ToolSet {
  var context: Context

  @Tool(
    description =
      "Lists the interactive elements currently visible on screen, in any app. Returns an id " +
        "for each element — use these ids with tapElement/typeText. Call this before any blind " +
        "tap, and again after the screen might have changed."
  )
  fun listScreenElements(): Map<String, String>

  @Tool(
    description =
      "Taps an element by id from the most recent listScreenElements() call. Requires user " +
        "confirmation."
  )
  fun tapElement(
    @ToolParam(description = "Element id from the last listScreenElements() call.") elementId: Int
  ): Map<String, String>

  @Tool(description = "Swipes the screen in a direction. Requires user confirmation.")
  fun swipeScreen(
    @ToolParam(description = "One of: up, down, left, right.") direction: String
  ): Map<String, String>

  @Tool(
    description =
      "Replaces an editable element's entire text content (not an append). Requires user " +
        "confirmation."
  )
  fun typeText(
    @ToolParam(description = "Element id from the last listScreenElements() call.") elementId: Int,
    @ToolParam(description = "The full text to set — replaces the field's current content.")
    text: String,
  ): Map<String, String>

  @Tool(description = "Goes back, like pressing the system Back button. Requires user confirmation.")
  fun goBack(): Map<String, String>

  @Tool(description = "Goes to the home screen. Requires user confirmation.")
  fun goHome(): Map<String, String>

  @Tool(
    description = "Launches another app by its Android package name. Requires user confirmation."
  )
  fun openApp(
    @ToolParam(description = "Android package name, e.g. com.whatsapp.") packageName: String
  ): Map<String, String>

  fun sendAgentAction(action: AgentAction)
}

/**
 * Not Hilt-constructed — same reasoning as [com.saturnmask.gallery.customtasks.coder
 * .CoderToolsImpl]. [sendAction] funnels confirmation prompts and progress updates through
 * [com.saturnmask.gallery.customtasks.agentchat.AgentTools.sendAgentAction]'s existing action
 * channel rather than owning a second one — [com.saturnmask.gallery.customtasks.agentchat
 * .AgentChatTaskModule]'s `AgentChatTask` wires this as `{ action -> agentTools.sendAgentAction
 * (action) }`.
 */
class UniversalAgentToolsImpl(private val sendAction: (AgentAction) -> Unit) : UniversalAgentTools {
  override lateinit var context: Context

  private val bridge: UniversalAgentServiceBridge by lazy { universalAgentServiceBridgeFrom(context) }

  // Re-resolved by descriptor match at act-time rather than holding onto raw AccessibilityNodeInfo
  // references across calls — see the plan's rationale: cross-API-level recycling risk (minSdk 31
  // to compileSdk 37) and a stale cache's failure mode is a possible silent mis-click, versus a
  // clean "element not found, re-list" error from a fresh resolve.
  private var lastSnapshot: List<ScreenElement> = emptyList()

  override fun listScreenElements(): Map<String, String> {
    val walkResult =
      bridge.withService { service ->
        val root = service.rootInActiveWindow ?: return@withService null
        val collected = mutableListOf<ScreenElement>()
        walk(root, depth = 0, out = collected)
        @Suppress("DEPRECATION") root.recycle()
        collected
      }
    return walkResult.fold(
      onSuccess = { elements ->
        if (elements == null) {
          mapOf("error" to "No active window to read", "status" to "failed")
        } else {
          lastSnapshot = elements
          if (elements.isEmpty()) {
            mapOf("result" to "No interactive elements found on screen.")
          } else {
            val truncated = elements.size >= MAX_ELEMENTS
            val formatted = elements.joinToString("\n") { describeElement(it) }
            mapOf(
              "result" to
                formatted + if (truncated) "\n(truncated at $MAX_ELEMENTS elements)" else ""
            )
          }
        }
      },
      onFailure = { e -> mapOf("error" to (e.message ?: "Failed to read screen"), "status" to "failed") },
    )
  }

  override fun tapElement(elementId: Int): Map<String, String> {
    val descriptor =
      lastSnapshot.getOrNull(elementId)
        ?: return mapOf(
          "error" to "Unknown element id $elementId — call listScreenElements() first",
          "status" to "failed",
        )
    val node =
      resolveNode(descriptor)
        ?: return mapOf(
          "error" to "Element $elementId not found on screen anymore — call listScreenElements() again",
          "status" to "failed",
        )
    val label = descriptor.text ?: descriptor.contentDescription ?: descriptor.className ?: "element $elementId"
    if (!confirmAction("Tap \"$label\"")) {
      return mapOf("error" to "Permission denied by user", "status" to "failed")
    }
    // Prefer performAction over raw coordinates: more reliable than synthesizing a tap blindly.
    val clickable = nearestClickable(node)
    val performed =
      if (clickable != null) {
        clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      } else {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        dispatchGestureBlocking(tapGesture(bounds.centerX().toFloat(), bounds.centerY().toFloat()))
      }
    return if (performed) {
      sendAction(SkillProgressAgentAction(label = "Tapped \"$label\"", inProgress = false))
      mapOf("result" to "Tapped \"$label\"", "status" to "succeeded")
    } else {
      mapOf("error" to "Tap failed", "status" to "failed")
    }
  }

  override fun swipeScreen(direction: String): Map<String, String> {
    val dir =
      SwipeDirection.from(direction)
        ?: return mapOf(
          "error" to "Invalid direction: $direction (use up/down/left/right)",
          "status" to "failed",
        )
    if (!confirmAction("Swipe $direction")) {
      return mapOf("error" to "Permission denied by user", "status" to "failed")
    }
    val performed = dispatchGestureBlocking(swipeGesture(dir, screenBounds()))
    return if (performed) {
      sendAction(SkillProgressAgentAction(label = "Swiped $direction", inProgress = false))
      mapOf("result" to "Swiped $direction", "status" to "succeeded")
    } else {
      mapOf("error" to "Swipe failed", "status" to "failed")
    }
  }

  override fun typeText(elementId: Int, text: String): Map<String, String> {
    val descriptor =
      lastSnapshot.getOrNull(elementId)
        ?: return mapOf(
          "error" to "Unknown element id $elementId — call listScreenElements() first",
          "status" to "failed",
        )
    val node =
      resolveNode(descriptor)
        ?: return mapOf(
          "error" to "Element $elementId not found on screen anymore — call listScreenElements() again",
          "status" to "failed",
        )
    if (!node.isEditable) {
      return mapOf("error" to "Element $elementId is not editable", "status" to "failed")
    }
    // Some WebView/Flutter/React-Native editable widgets don't implement ACTION_SET_TEXT at all —
    // fail cleanly here rather than attempting an unreliable focus-and-hope path.
    if (node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
      return mapOf(
        "error" to "This field doesn't support text entry via this tool",
        "status" to "failed",
      )
    }
    if (!confirmAction("Set text of element $elementId to \"$text\"")) {
      return mapOf("error" to "Permission denied by user", "status" to "failed")
    }
    val arguments =
      Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
      }
    val performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    return if (performed) {
      sendAction(SkillProgressAgentAction(label = "Typed text into element $elementId", inProgress = false))
      mapOf("result" to "Text set on element $elementId", "status" to "succeeded")
    } else {
      mapOf("error" to "Failed to set text", "status" to "failed")
    }
  }

  override fun goBack(): Map<String, String> {
    if (!confirmAction("Go back")) return mapOf("error" to "Permission denied by user", "status" to "failed")
    val result = bridge.withService { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
    return globalActionResultToMap(result, "Went back", "Failed to go back")
  }

  override fun goHome(): Map<String, String> {
    if (!confirmAction("Go to home screen")) {
      return mapOf("error" to "Permission denied by user", "status" to "failed")
    }
    val result = bridge.withService { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
    return globalActionResultToMap(result, "Went to home screen", "Failed to go home")
  }

  override fun openApp(packageName: String): Map<String, String> {
    if (!confirmAction("Open app: $packageName")) {
      return mapOf("error" to "Permission denied by user", "status" to "failed")
    }
    val intent =
      context.packageManager.getLaunchIntentForPackage(packageName)
        ?: return mapOf(
          "error" to "No launchable app found for package $packageName",
          "status" to "failed",
        )
    return runCatching {
        context.startActivity(intent)
        sendAction(SkillProgressAgentAction(label = "Opened $packageName", inProgress = false))
        mapOf("result" to "Opened $packageName", "status" to "succeeded")
      }
      .getOrElse { e -> mapOf("error" to (e.message ?: "Failed to open app"), "status" to "failed") }
  }

  override fun sendAgentAction(action: AgentAction) {
    sendAction(action)
  }

  /** Blocks (via CompletableDeferred.await) until the UI resolves the confirmation dialog. No
   *  ALWAYS_ALLOW tier for this tool set by design — every mutating call re-prompts. */
  private fun confirmAction(description: String): Boolean {
    val action = AskUniversalAgentActionPermissionAction(actionDescription = description)
    sendAction(action)
    return runBlocking { action.result.await() } == PermissionResult.ALLOW_ONCE
  }

  private fun globalActionResultToMap(
    result: Result<Boolean>,
    successMessage: String,
    failureMessage: String,
  ): Map<String, String> =
    if (result.getOrDefault(false)) {
      mapOf("result" to successMessage, "status" to "succeeded")
    } else {
      mapOf("error" to (result.exceptionOrNull()?.message ?: failureMessage), "status" to "failed")
    }

  private fun describeElement(e: ScreenElement): String = buildString {
    append("[${e.id}] ${e.className ?: "?"}")
    e.text?.let { append(" text=\"$it\"") }
    e.contentDescription?.let { append(" desc=\"$it\"") }
    e.viewIdResourceName?.let { append(" id=$it") }
    if (e.isEditable) append(" editable")
    if (e.isClickable) append(" clickable")
    if (e.isCheckable) append(" checked=${e.isChecked}")
    if (e.isScrollable) append(" scrollable")
  }

  /** Walks the CURRENT node tree top-down, collecting only visible+interactive nodes as
   *  [ScreenElement] snapshots. Recycles every child node right after reading it (a no-op on
   *  API 33+, load-bearing on this app's minSdk 31/32) — safe here since nothing outlives this
   *  function's return. */
  private fun walk(node: AccessibilityNodeInfo, depth: Int, out: MutableList<ScreenElement>) {
    if (out.size >= MAX_ELEMENTS || depth > MAX_DEPTH) return
    if (
      node.isVisibleToUser &&
        (node.isClickable || node.isEditable || node.isCheckable || node.isScrollable || node.isFocusable)
    ) {
      val bounds = Rect()
      node.getBoundsInScreen(bounds)
      out.add(
        ScreenElement(
          id = out.size,
          className = node.className?.toString(),
          text = node.text?.toString(),
          contentDescription = node.contentDescription?.toString(),
          viewIdResourceName = node.viewIdResourceName,
          boundsInScreen = bounds,
          isClickable = node.isClickable,
          isEditable = node.isEditable,
          isCheckable = node.isCheckable,
          isChecked = node.isChecked,
          isScrollable = node.isScrollable,
          packageName = node.packageName?.toString(),
        )
      )
    }
    val childCount = node.childCount
    for (i in 0 until childCount) {
      if (out.size >= MAX_ELEMENTS) break
      val child = node.getChild(i) ?: continue
      walk(child, depth + 1, out)
      @Suppress("DEPRECATION") child.recycle()
    }
  }

  /** Re-resolves [descriptor] against a FRESH node tree query — see [lastSnapshot]'s doc comment
   *  for why raw node references aren't cached across calls. Tiered match, first unique match
   *  wins; fails closed (returns null) on zero or ambiguous matches rather than guessing. */
  private fun resolveNode(descriptor: ScreenElement): AccessibilityNodeInfo? =
    bridge
      .withService { service ->
        val root = service.rootInActiveWindow ?: return@withService null
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectAll(root, candidates)
        val matched = pickMatch(candidates, descriptor)
        @Suppress("DEPRECATION") candidates.filter { it !== matched }.forEach { it.recycle() }
        matched
      }
      .getOrNull()

  private fun collectAll(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
    if (out.size >= MAX_RESOLVE_CANDIDATES || depth > MAX_DEPTH) return
    out.add(node)
    val childCount = node.childCount
    for (i in 0 until childCount) {
      val child = node.getChild(i) ?: continue
      collectAll(child, out, depth + 1)
    }
  }

  private fun pickMatch(candidates: List<AccessibilityNodeInfo>, descriptor: ScreenElement): AccessibilityNodeInfo? {
    if (descriptor.viewIdResourceName != null) {
      val tier1 =
        candidates.filter {
          it.viewIdResourceName == descriptor.viewIdResourceName &&
            it.className?.toString() == descriptor.className
        }
      tier1.singleOrNull()?.let {
        return it
      }
    }
    val tier2 =
      candidates.filter {
        it.className?.toString() == descriptor.className &&
          ((descriptor.text != null && it.text?.toString() == descriptor.text) ||
            (descriptor.contentDescription != null &&
              it.contentDescription?.toString() == descriptor.contentDescription))
      }
    tier2.singleOrNull()?.let {
      return it
    }
    val tier3 =
      candidates.filter {
        if (it.className?.toString() != descriptor.className) {
          false
        } else {
          val bounds = Rect()
          it.getBoundsInScreen(bounds)
          abs(bounds.left - descriptor.boundsInScreen.left) <= BOUNDS_TOLERANCE_PX &&
            abs(bounds.top - descriptor.boundsInScreen.top) <= BOUNDS_TOLERANCE_PX
        }
      }
    return tier3.singleOrNull()
  }

  private fun nearestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (node.isClickable) return node
    var parent = node.parent
    var depth = 0
    while (parent != null && depth < MAX_DEPTH) {
      if (parent.isClickable) return parent
      parent = parent.parent
      depth++
    }
    return null
  }

  private fun tapGesture(x: Float, y: Float): GestureDescription {
    val path = Path().apply { moveTo(x, y) }
    return GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_GESTURE_DURATION_MS))
      .build()
  }

  private fun swipeGesture(direction: SwipeDirection, bounds: Rect): GestureDescription {
    val centerX = bounds.centerX().toFloat()
    val centerY = bounds.centerY().toFloat()
    val nearTop = bounds.top + bounds.height() * 0.2f
    val nearBottom = bounds.top + bounds.height() * 0.8f
    val nearLeft = bounds.left + bounds.width() * 0.2f
    val nearRight = bounds.left + bounds.width() * 0.8f
    val path =
      Path().apply {
        when (direction) {
          SwipeDirection.UP -> {
            moveTo(centerX, nearBottom)
            lineTo(centerX, nearTop)
          }
          SwipeDirection.DOWN -> {
            moveTo(centerX, nearTop)
            lineTo(centerX, nearBottom)
          }
          SwipeDirection.LEFT -> {
            moveTo(nearRight, centerY)
            lineTo(nearLeft, centerY)
          }
          SwipeDirection.RIGHT -> {
            moveTo(nearLeft, centerY)
            lineTo(nearRight, centerY)
          }
        }
      }
    return GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_GESTURE_DURATION_MS))
      .build()
  }

  /** [GestureDescription.StrokeDescription]'s completion is async (`GestureResultCallback`) —
   *  bridge it to this ToolSet's synchronous methods via CompletableDeferred, the same
   *  blocking-bridge idiom used by CoderToolsImpl.confirmWrite/AgentToolsImpl.runJs. */
  private fun dispatchGestureBlocking(gesture: GestureDescription): Boolean {
    val deferred = CompletableDeferred<Boolean>()
    val dispatched =
      bridge
        .withService { service ->
          service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
              override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
              }

              override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
              }
            },
            null,
          )
        }
        .getOrDefault(false)
    if (!dispatched) return false
    return runBlocking { deferred.await() }
  }

  private fun screenBounds(): Rect {
    val fromWindow =
      bridge
        .withService { service ->
          val bounds = Rect()
          service.rootInActiveWindow?.getBoundsInScreen(bounds)
          bounds
        }
        .getOrNull()
    if (fromWindow != null && !fromWindow.isEmpty) return fromWindow
    val windowManager = context.getSystemService(WindowManager::class.java)
    return windowManager.currentWindowMetrics.bounds
  }
}
