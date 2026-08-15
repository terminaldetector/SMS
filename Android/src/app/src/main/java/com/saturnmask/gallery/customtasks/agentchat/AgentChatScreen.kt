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

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.saturnmask.gallery.R
import com.saturnmask.gallery.common.AskFileWritePermissionAction
import com.saturnmask.gallery.common.AskInfoAgentAction
import com.saturnmask.gallery.common.AskMcpToolCallPermissionAction
import com.saturnmask.gallery.common.AskUniversalAgentActionPermissionAction
import com.saturnmask.gallery.common.CallJsAgentAction
import com.saturnmask.gallery.common.LOCAL_URL_BASE
import com.saturnmask.gallery.common.PermissionResult
import com.saturnmask.gallery.common.RequestPermissionAgentAction
import com.saturnmask.gallery.common.SkillProgressAgentAction
import com.saturnmask.gallery.data.AgentSkillsURLs
import com.saturnmask.gallery.data.BuiltInTaskId
import com.saturnmask.gallery.data.ConfigKeys
import com.saturnmask.gallery.data.DEFAULT_MAX_TOKEN
import com.saturnmask.gallery.data.Model
import com.saturnmask.gallery.data.ModelCapability
import com.saturnmask.gallery.data.Task
import com.saturnmask.gallery.domain.rag.RagDocumentInfo
import com.saturnmask.gallery.domain.websearch.WebSearchProvider
import com.saturnmask.gallery.ui.common.BaseGalleryWebViewClient
import com.saturnmask.gallery.ui.common.GalleryWebView
import com.saturnmask.gallery.ui.common.chat.ChatMessage
import com.saturnmask.gallery.ui.common.chat.ChatMessageCollapsableProgressPanel
import com.saturnmask.gallery.ui.common.chat.ChatMessageImage
import com.saturnmask.gallery.ui.common.chat.ChatMessageInfo
import com.saturnmask.gallery.ui.common.chat.ChatMessageText
import com.saturnmask.gallery.ui.common.chat.ChatMessageWarning
import com.saturnmask.gallery.ui.common.chat.ChatMessageType
import com.saturnmask.gallery.customtasks.coder.CoderProjectPickerRow
import com.saturnmask.gallery.customtasks.coder.CoderTools
import com.saturnmask.gallery.customtasks.coder.CoderViewModel
import com.saturnmask.gallery.customtasks.coder.FileWritePermissionDialog
import com.saturnmask.gallery.customtasks.mobileactions.MobileActionsTools
import com.saturnmask.gallery.customtasks.mobileactions.MobileActionsViewModel
import com.saturnmask.gallery.customtasks.universalagent.UniversalAgentActionPermissionDialog
import com.saturnmask.gallery.customtasks.universalagent.UniversalAgentDisclaimerDialog
import com.saturnmask.gallery.customtasks.universalagent.UniversalAgentTools
import com.saturnmask.gallery.customtasks.universalagent.isUniversalAgentAccessibilityServiceEnabled
import com.saturnmask.gallery.customtasks.universalagent.requestIgnoreBatteryOptimizations
import com.saturnmask.gallery.customtasks.universalagent.universalAgentEnablementStateFrom
import com.saturnmask.gallery.ui.common.chat.ChatMessageWebView
import com.saturnmask.gallery.ui.common.chat.ChatSide
import com.saturnmask.gallery.ui.common.chat.LogMessage
import com.saturnmask.gallery.ui.common.chat.LogMessageLevel
import com.saturnmask.gallery.ui.common.chat.SendMessageTrigger
import com.saturnmask.gallery.ui.common.chat.toLiteRtMessages
import com.saturnmask.gallery.ui.llmchat.LlmChatScreen
import com.saturnmask.gallery.ui.llmchat.LlmChatViewModel
import com.saturnmask.gallery.ui.modelmanager.ModelInitializationStatusType
import com.saturnmask.gallery.ui.modelmanager.ModelManagerViewModel
import java.lang.Exception
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

private const val TAG = "AGAgentChatScreen"
private val chatViewJavascriptInterface = ChatWebViewJavascriptInterface()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentChatScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  agentTools: AgentTools,
  viewModel: LlmChatViewModel = hiltViewModel(),
  skillManagerViewModel: SkillManagerViewModel = hiltViewModel(),
  mcpManagerViewModel: McpManagerViewModel = hiltViewModel(),
  // Non-null only for the Coder tab (see CoderTaskModule.kt) — merged into the session's tool
  // list once a project folder is picked (coderUiState.projectRootUri != null), same conditional
  // pattern as mobileActionsTools below.
  coderTools: CoderTools? = null,
  coderViewModel: CoderViewModel = hiltViewModel(),
  // Non-null only from AgentChatTask (see AgentChatTaskModule.kt) — never wired into CoderTask.
  // Merged into the session's tool list only once BOTH universalAgentRequested (user opted in via
  // the "+" menu) AND universalAgentServiceEnabled (the AccessibilityService is actually enabled
  // in system Settings) are true — see the wiring below.
  universalAgentTools: UniversalAgentTools? = null,
  initialQuery: String? = null,
  // A saved session to resume (e.g. tapping a home-screen Recent Chats entry) instead of starting
  // fresh. Mutually exclusive with initialQuery by construction — Recent Chats never sets query.
  sessionId: String? = null,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  agentTools.context = context
  agentTools.skillManagerViewModel = skillManagerViewModel
  agentTools.mcpManagerViewModel = mcpManagerViewModel
  agentTools.taskId = task.id
  agentTools.sessionId = viewModel.currentSessionId
  val coderUiState by coderViewModel.uiState.collectAsState()
  LaunchedEffect(Unit) { coderViewModel.reindexIfNeeded() }
  coderTools?.context = context
  coderTools?.projectRootUri = coderUiState.projectRootUri
  coderTools?.ragSessionId = coderUiState.projectRootUri?.let { coderViewModel.sessionIdFor(it) }
  universalAgentTools?.context = context
  val density = LocalDensity.current
  val windowInfo = LocalWindowInfo.current
  val screenWidthDp = remember { with(density) { windowInfo.containerSize.width.toDp() } }
  // Unified "Skills" entry point (roadmap merge) — replaces the former separate Skills/MCP
  // sheets, standalone toggle buttons, and Mobile Actions overflow-menu item.
  var showSkillsManagerBottomSheet by remember { mutableStateOf(false) }
  var showRagManagerBottomSheet by remember { mutableStateOf(false) }
  var actionsEnabled by rememberSaveable { mutableStateOf(true) }
  var ragEnabled by rememberSaveable { mutableStateOf(true) }
  // Independent on/off for the "Web search" trigger chip — previously there was no real state
  // here at all (the chip's "enabled" look was only ever derived from whether a provider was
  // configured); this is what actually gates webSearch/readUrl in buildModeGatingText now.
  var webSearchEnabled by rememberSaveable { mutableStateOf(true) }
  // Default OFF: Mobile Actions grants contacts/email/calendar tool access, so it's opt-in
  // and tucked behind the "+" overflow menu instead of a main-row chip.
  var mobileActionsEnabled by rememberSaveable { mutableStateOf(false) }
  // Same MobileActionsViewModel the standalone Mobile Actions screen uses — reusing it here
  // (rather than re-implementing action execution) means every action added there in the future
  // becomes available in Agent Chat automatically, with no duplicated logic.
  val mobileActionsViewModel: MobileActionsViewModel = hiltViewModel()
  val mobileActionsTools = remember {
    MobileActionsTools(
      onFunctionCalled = { action ->
        Log.d(TAG, "Mobile action: $action")
        // Previously a no-op (log only) — the model could "call" turnOnFlashlight() etc. and
        // get a success-shaped response with nothing actually happening on the device. Wired to
        // the real executor now, same one the standalone Mobile Actions screen already uses.
        val result = mobileActionsViewModel.performAction(action, context)
        Log.d(TAG, "Mobile action result: $result")
      }
    )
  }
  var showAskInfoDialog by remember { mutableStateOf(false) }
  var currentAskInfoAction by remember { mutableStateOf<AskInfoAgentAction?>(null) }
  var currentMcpPermissionAction by remember {
    mutableStateOf<AskMcpToolCallPermissionAction?>(null)
  }
  var currentFileWritePermissionAction by remember {
    mutableStateOf<AskFileWritePermissionAction?>(null)
  }
  var askInfoInputValue by remember { mutableStateOf("") }
  var webViewRef: WebView? by remember { mutableStateOf(null) }
  val chatWebViewClient = remember { ChatWebViewClient(context = context) }
  var curSystemPrompt by remember { mutableStateOf(task.defaultSystemPrompt) }
  val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)
  var sendMessageTrigger by remember { mutableStateOf<SendMessageTrigger?>(null) }
  var showAlertForDisabledSkill by remember { mutableStateOf(false) }
  var disabledSkillName by remember { mutableStateOf("") }

  var currentPermissionAction by remember { mutableStateOf<RequestPermissionAgentAction?>(null) }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permissionGranted ->
      currentPermissionAction?.result?.complete(permissionGranted)
      currentPermissionAction = null
    }

  LaunchedEffect(task) { viewModel.loadSystemPrompt(task) }
  val uiSystemPrompt by viewModel.uiSystemPrompt.collectAsState()

  // Collect UI states from view models. Ensure launched effect is triggered when the UI state is
  // updated.
  val llmChatUiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val skillUiState by skillManagerViewModel.uiState.collectAsState()
  val mcpUiState by mcpManagerViewModel.uiState.collectAsState()
  // Same RagManagerViewModel instance RagManagerBottomSheet uses (default hiltViewModel() scope
  // resolves to the nearest ViewModelStoreOwner, shared by both) — so document counts here and
  // the "RAG DOCUMENTS AVAILABLE" prompt section stay live without any extra plumbing.
  val ragManagerViewModel: RagManagerViewModel = hiltViewModel()
  // No-ops once already set to this value (see RagManagerViewModel.setSessionId), so calling this
  // on every recomposition — same imperative-assignment style as the agentTools.* lines above —
  // is cheap.
  ragManagerViewModel.setSessionId(viewModel.currentSessionId)
  val ragUiState by ragManagerViewModel.uiState.collectAsState()
  var showWebSearchSettingsBottomSheet by remember { mutableStateOf(false) }
  // Backs the new "Web search" trigger chip (see AgentModeTriggers) — the same ViewModel instance
  // WebSearchSettingsBottomSheet uses, so the chip's caption stays live with whatever's picked in
  // either the chip's own sheet or the burger-menu "Search provider" tile (HomeScreen.kt), since
  // both are backed by the same @Singleton WebSearchSettingsStore.
  val webSearchSettingsViewModel: WebSearchSettingsViewModel = hiltViewModel()
  val webSearchUiState by webSearchSettingsViewModel.uiState.collectAsState()

  val skillCount = skillUiState.skills.count { it.skill.selected }
  val mcpCount = mcpUiState.mcpServers.count { it.mcpServer.enabled }
  val mcpToolsCount =
    mcpUiState.mcpServers
      .filter { it.mcpServer.enabled }
      .sumOf { it.mcpServer.toolsList.count { tool -> tool.enabled } }

  LaunchedEffect(uiSystemPrompt, mcpToolsCount) {
    curSystemPrompt = getEffectiveBaseSystemPrompt(uiSystemPrompt, mcpToolsCount > 0)
  }

  // Shared with the Home screen's UniversalAgentHomeCard via an in-memory singleton (not
  // SharedPreferences) — never silently stays "on" forever across app restarts, same intent as
  // the rememberSaveable var this replaced, just reachable from more than one screen now. See
  // UniversalAgentEnablementState's doc comment.
  val universalAgentEnablementState = remember { universalAgentEnablementStateFrom(context) }
  val universalAgentRequested by universalAgentEnablementState.requested.collectAsState()
  // The ONLY way to detect AccessibilityService enablement — there's no programmatic request API,
  // the user must manually toggle it on in Settings > Accessibility (see the disclaimer dialog's
  // onConfirm below). Re-checked on ON_RESUME so coming back from Settings updates this live.
  var universalAgentServiceEnabled by remember {
    mutableStateOf(isUniversalAgentAccessibilityServiceEnabled(context))
  }
  var showUniversalAgentDisclaimer by remember { mutableStateOf(false) }
  var currentUniversalAgentPermissionAction by remember {
    mutableStateOf<AskUniversalAgentActionPermissionAction?>(null)
  }
  // Both requested (user opted in) AND actually enabled in Settings must be true for the tool to
  // actually be offered to the model — see resetSessionWithCurrentSkillsAndMcps's tools list.
  val universalAgentEffectivelyEnabled = universalAgentRequested && universalAgentServiceEnabled

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        universalAgentServiceEnabled = isUniversalAgentAccessibilityServiceEnabled(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // Re-merges universalAgentTools into the live session once the service becomes enabled after
  // the user comes back from Settings — without this, a user who enables the service there would
  // otherwise have to manually re-toggle the "+" menu item to actually get the tool. Initialized-
  // guard skips the FIRST value (set from isUniversalAgentAccessibilityServiceEnabled above at
  // composition start), same "skip on first composition" pattern as the coder/rag effects below —
  // only a CHANGE (i.e. actually coming back from Settings) should trigger a reset.
  var universalAgentServiceCheckInitialized by remember { mutableStateOf(false) }
  LaunchedEffect(universalAgentServiceEnabled) {
    if (!universalAgentServiceCheckInitialized) {
      universalAgentServiceCheckInitialized = true
      return@LaunchedEffect
    }
    if (!universalAgentRequested) return@LaunchedEffect
    resetSessionWithCurrentSkillsAndMcps(
      viewModel,
      modelManagerViewModel,
      skillManagerViewModel,
      task,
      curSystemPrompt,
      agentTools,
      initialMessages =
        llmChatUiState.messagesByModel[modelManagerUiState.selectedModel.name] ?: emptyList(),
      clearHistory = false,
      actionsEnabled = actionsEnabled,
      ragEnabled = ragEnabled,
      mobileActionsEnabled = mobileActionsEnabled,
      mobileActionsTools = mobileActionsTools,
      ragDocuments = ragUiState.documents,
      coderTools = coderTools,
      universalAgentEnabled = universalAgentRequested && universalAgentServiceEnabled,
      universalAgentTools = universalAgentTools,
      webSearchEnabled = webSearchEnabled,
    )
  }

  // Keep the "RAG DOCUMENTS AVAILABLE" prompt section in sync when documents are added/removed
  // via RagManagerBottomSheet, not just when the RAG trigger itself is toggled. clearHistory =
  // false — unlike the other resetSessionWithCurrentSkillsAndMcps call sites below, this one is
  // NOT a direct user action on the trigger row, so silently wiping the visible chat because a
  // background import finished would be surprising.
  //
  // ragDocumentsInitialized guards against firing on the FIRST composition too: nothing else in
  // this file calls resetSessionWithCurrentSkillsAndMcps unconditionally on mount (the initial
  // session/model load is LlmChatScreen/LlmChatViewModel's job), so without this guard this
  // effect would add an extra, previously-nonexistent session reset the moment the screen opens.
  var ragDocumentsInitialized by remember { mutableStateOf(false) }
  LaunchedEffect(ragUiState.documents) {
    if (!ragDocumentsInitialized) {
      ragDocumentsInitialized = true
      return@LaunchedEffect
    }
    resetSessionWithCurrentSkillsAndMcps(
      viewModel,
      modelManagerViewModel,
      skillManagerViewModel,
      task,
      curSystemPrompt,
      agentTools,
      initialMessages =
        llmChatUiState.messagesByModel[modelManagerUiState.selectedModel.name] ?: emptyList(),
      clearHistory = false,
      actionsEnabled = actionsEnabled,
      ragEnabled = ragEnabled,
      mobileActionsEnabled = mobileActionsEnabled,
      mobileActionsTools = mobileActionsTools,
      ragDocuments = ragUiState.documents,
      coderTools = coderTools,
      universalAgentEnabled = universalAgentEffectivelyEnabled,
      universalAgentTools = universalAgentTools,
      webSearchEnabled = webSearchEnabled,
    )
  }

  // Merges CoderTools into the live session's tool list the moment a project folder is picked
  // (coderTools is only passed into resetSessionWithCurrentSkillsAndMcps's tools list when
  // non-null AND — via coderTools?.projectRootUri above — a project is actually selected).
  // Same "skip on first composition" guard as the ragDocuments effect above: the initial
  // session/model load already reads coderUiState.projectRootUri directly (see
  // CoderTaskModule.kt's initializeModelFn), so this effect only needs to handle picking a NEW
  // project after the screen is already open.
  var coderProjectInitialized by remember { mutableStateOf(false) }
  LaunchedEffect(coderUiState.projectRootUri) {
    if (!coderProjectInitialized) {
      coderProjectInitialized = true
      return@LaunchedEffect
    }
    resetSessionWithCurrentSkillsAndMcps(
      viewModel,
      modelManagerViewModel,
      skillManagerViewModel,
      task,
      curSystemPrompt,
      agentTools,
      initialMessages =
        llmChatUiState.messagesByModel[modelManagerUiState.selectedModel.name] ?: emptyList(),
      clearHistory = false,
      actionsEnabled = actionsEnabled,
      ragEnabled = ragEnabled,
      mobileActionsEnabled = mobileActionsEnabled,
      mobileActionsTools = mobileActionsTools,
      ragDocuments = ragUiState.documents,
      coderTools = coderTools,
      universalAgentEnabled = universalAgentEffectivelyEnabled,
      universalAgentTools = universalAgentTools,
      webSearchEnabled = webSearchEnabled,
    )
  }

  val selectedModel = modelManagerUiState.selectedModel
  val modelInitStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]

  DisposableEffect(selectedModel.name, task.id) {
    if (selectedModel.setupAgentSkillTopK()) {
      modelManagerViewModel.updateConfigValuesUpdateTrigger()
    }

    onDispose {
      if (selectedModel.cleanupAgentSkillTopK()) {
        modelManagerViewModel.updateConfigValuesUpdateTrigger()
      }
    }
  }

  var initialQueryConsumed by remember { mutableStateOf(false) }

  LaunchedEffect(
    llmChatUiState.isResettingSession,
    modelInitStatus?.status,
    selectedModel.name,
    initialQuery,
  ) {
    // Send the optional initial query to the model if the model is initialized and the initial
    // query is not consumed yet.
    if (
      !initialQuery.isNullOrEmpty() &&
        !initialQueryConsumed &&
        modelInitStatus?.status == ModelInitializationStatusType.INITIALIZED &&
        !llmChatUiState.isResettingSession
    ) {
      initialQueryConsumed = true
      sendMessageTrigger =
        SendMessageTrigger(
          model = selectedModel,
          messages = listOf(ChatMessageText(content = initialQuery, side = ChatSide.USER)),
        )
    }
  }

  var sessionIdConsumed by remember { mutableStateOf(false) }

  LaunchedEffect(
    llmChatUiState.isResettingSession,
    modelInitStatus?.status,
    selectedModel.name,
    sessionId,
  ) {
    // Resume a saved session (Recent Chats) once the model is initialized, same gating as
    // initialQuery above. sessionId and initialQuery are mutually exclusive by construction — see
    // this function's sessionId param doc comment — so there's no ordering conflict between them.
    if (
      !sessionId.isNullOrEmpty() &&
        !sessionIdConsumed &&
        modelInitStatus?.status == ModelInitializationStatusType.INITIALIZED &&
        !llmChatUiState.isResettingSession
    ) {
      val session = viewModel.historySessions.value.firstOrNull { it.sessionId == sessionId }
      sessionIdConsumed = true
      if (session != null) {
        scope.launch {
          viewModel.loadSession(
            session = session,
            model = selectedModel,
            onResetSessionClicked = { _, initialMessages, clearHistory, onDone ->
              resetSessionWithCurrentSkillsAndMcps(
                viewModel,
                modelManagerViewModel,
                skillManagerViewModel,
                task,
                curSystemPrompt,
                agentTools,
                onDone = { onDone() },
                initialMessages = initialMessages,
                clearHistory = clearHistory,
                actionsEnabled = actionsEnabled,
                ragEnabled = ragEnabled,
                mobileActionsEnabled = mobileActionsEnabled,
                mobileActionsTools = mobileActionsTools,
                ragDocuments = ragUiState.documents,
                coderTools = coderTools,
                universalAgentEnabled = universalAgentEffectivelyEnabled,
                universalAgentTools = universalAgentTools,
                webSearchEnabled = webSearchEnabled,
              )
            },
          )
        }
      }
      // else: session vanished (deleted) — sessionIdConsumed is already set, so this doesn't retry.
    }
  }

  LlmChatScreen(
    modelManagerViewModel = modelManagerViewModel,
    // Was hardcoded to BuiltInTaskId.LLM_AGENT_CHAT — silently forced every AgentChatScreen-based
    // task (now including Coder) back to AgentChat's own Task for capability checks, analytics
    // tags, and (via LlmChatScreen's re-derived `task` param) the Task object handed to
    // onResetSessionClickedOverride below, causing wrong-system-prompt-on-reset bugs for any
    // second task reusing this screen. Safe for AgentChatTask itself since its task.id already
    // equals BuiltInTaskId.LLM_AGENT_CHAT.
    taskId = task.id,
    navigateUp = navigateUp,
    mcpCount = mcpCount,
    mcpToolsCount = mcpToolsCount,
    // Resuming a saved session (Recent Chats) already decided which model to use — offering a
    // "pick a different model" chip would contradict that. New chats keep the selector.
    hideModelSelector = !sessionId.isNullOrEmpty(),
    // NOTE: showImagePicker/showAudioPicker were already set further down in this same call
    // (see below, near getActiveSkills) — Ask Image / Audio Scribe were apparently already
    // available in Agent Chat before this session's changes. The standalone Ask Image/Audio
    // Scribe/AI Chat screens (and their LlmAskImageViewModel/LlmAskAudioViewModel, which were
    // functionally identical to LlmChatViewModel) have since been deleted entirely — this screen
    // was always the only place their capabilities actually mattered.
    onFirstToken = { model ->
      scope.launch(Dispatchers.Main) {
        updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)
      }
    },
    onGenerateResponseDone = { model ->
      scope.launch(Dispatchers.Main) {
        // Show any image produced by tools.
        agentTools.resultImageToShow?.let { resultImage ->
          resultImage.base64?.let { base64 ->
            decodeBase64ToBitmap(base64String = base64)?.let { bitmap ->
              viewModel.addMessage(
                model = model,
                message =
                  ChatMessageImage(
                    bitmaps = listOf(bitmap),
                    imageBitMaps = listOf(bitmap.asImageBitmap()),
                    side = ChatSide.AGENT,
                    maxSize = (screenWidthDp.value * 0.8).toInt(),
                    latencyMs = -1.0f,
                    hideSenderLabel = true,
                  ),
              )
            }
          }
          // Clean up.
          agentTools.resultImageToShow = null
        }

        // Show any webview produced by tools.
        agentTools.resultWebviewToShow?.let { webview ->
          val url = webview.url ?: ""
          val iframe = webview.iframe == true
          val aspectRatio = webview.aspectRatio ?: 1.333f
          viewModel.addMessage(
            model = model,
            message =
              ChatMessageWebView(
                url = url,
                iframe = iframe,
                aspectRatio = aspectRatio,
                hideSenderLabel = true,
              ),
          )
          // Clean up.
          agentTools.resultWebviewToShow = null
        }
        updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)
      }
    },
    onResetSessionClickedOverride = { task, _, initialMessages, clearHistory, onDone ->
      resetSessionWithCurrentSkillsAndMcps(
        viewModel,
        modelManagerViewModel,
        skillManagerViewModel,
        task,
        curSystemPrompt,
        agentTools,
        onDone = { onDone() },
        initialMessages = initialMessages,
        clearHistory = clearHistory,
        actionsEnabled = actionsEnabled,
        ragEnabled = ragEnabled,
        mobileActionsEnabled = mobileActionsEnabled,
        mobileActionsTools = mobileActionsTools,
        ragDocuments = ragUiState.documents,
        coderTools = coderTools,
        universalAgentEnabled = universalAgentEffectivelyEnabled,
        universalAgentTools = universalAgentTools,
        webSearchEnabled = webSearchEnabled,
      )
    },
    universalAgentEnabled = universalAgentEffectivelyEnabled,
    onUniversalAgentToggled = { enabled ->
      if (enabled) {
        // Enabling requires the disclaimer + (if needed) a Settings round-trip first — see
        // UniversalAgentDisclaimerDialog's onConfirm below, which sets universalAgentRequested.
        showUniversalAgentDisclaimer = true
      } else {
        universalAgentEnablementState.requested.value = false
        resetSessionWithCurrentSkillsAndMcps(
          viewModel,
          modelManagerViewModel,
          skillManagerViewModel,
          task,
          curSystemPrompt,
          agentTools,
          // Preserve the conversation across this toggle — this is a mode switch, not a "start
          // over" action, and the replay mechanism is already there (see the RAG-documents-changed
          // effect further down for the same pattern).
          initialMessages = llmChatUiState.messagesByModel[selectedModel.name] ?: emptyList(),
          clearHistory = false,
          actionsEnabled = actionsEnabled,
          ragEnabled = ragEnabled,
          mobileActionsEnabled = mobileActionsEnabled,
          mobileActionsTools = mobileActionsTools,
          ragDocuments = ragUiState.documents,
          coderTools = coderTools,
          universalAgentEnabled = false,
          universalAgentTools = universalAgentTools,
          webSearchEnabled = webSearchEnabled,
        )
      }
    },
    // Same reasoning as resetSessionWithCurrentSkillsAndMcps below: showing the attach button for
    // a modality the loaded model has no encoder for just leads the user into the
    // TF_LITE_VISION_ENCODER/audio-encoder NOT_FOUND failure at send time instead of preventing it.
    showImagePicker = selectedModel.llmSupportImage,
    showAudioPicker = selectedModel.llmSupportAudio,
    // Indexes immediately (not tied to the outgoing message) via the same RagManagerViewModel
    // RagManagerBottomSheet uses — its uiState (ragUiState above) is already observed by
    // AgentModeTriggers' RAG caption/progress bar, so indexing progress shows there automatically
    // with no separate progress UI needed here. The onResult callback below is just for the
    // messenger-style "attached a file" bubble in the chat itself.
    onFilePicked = { uri ->
      ragManagerViewModel.importDocumentForChat(uri) { result ->
        result
          .onSuccess { info ->
            viewModel.addMessage(
              model = selectedModel,
              message =
                ChatMessageInfo(content = "📎 Attached \"${info.name}\" (${info.chunkCount} chunks)"),
            )
          }
          .onFailure { e ->
            viewModel.addMessage(
              model = selectedModel,
              message =
                ChatMessageWarning(
                  content = "Couldn't attach document: ${e.message ?: "unknown error"}"
                ),
            )
          }
      }
    },
    getActiveSkills = {
      skillManagerViewModel.getSelectedSkills().map { skill ->
        skillManagerViewModel.getSkillShortId(skill)
      }
    },
    composableBelowMessageList = { model ->
      val actionChannel = agentTools.actionChannel
      val doneIcon = ImageVector.vectorResource(R.drawable.skill)
      // Use rememberUpdatedState to ensure that LaunchedEffect captures the
      // latest active model when the model is switched during an ongoing skill execution.
      val currentModel by androidx.compose.runtime.rememberUpdatedState(model)
      LaunchedEffect(actionChannel) {
        for (action in actionChannel) {
          Log.d(TAG, "Handling action: $action")
          when (action) {
            is SkillProgressAgentAction -> {
              viewModel.updateCollapsableProgressPanelMessage(
                model = currentModel,
                title = action.label,
                inProgress = action.inProgress,
                doneIcon = doneIcon,
                addItemTitle = action.addItemTitle,
                addItemDescription = action.addItemDescription,
                customData = action.customData,
              )
            }
            is CallJsAgentAction -> {
              val skillName =
                if (action.url.contains("/skills/")) {
                  action.url.substringAfter("/skills/").substringBefore("/")
                } else if (action.url.startsWith(LOCAL_URL_BASE + "/")) {
                  action.url.substringAfter(LOCAL_URL_BASE + "/").substringBefore("/")
                } else {
                  action.url
                }
              val skill = skillManagerViewModel.getSkill(name = skillName)
              val skillId = skill?.let { skillManagerViewModel.getSkillShortId(it) } ?: "xxxx"
              try {
                // Set up a safety net timeout so we NEVER hang the chat or tool execution
                launch {
                  delay(60000L) // 60 seconds max
                  if (!action.result.isCompleted) {
                    Log.e(TAG, "JS Execution timed out, completing with error.")
                    Log.d(
                      TAG,
                      "Analytics: skill_execution, capability_name=${task.id}, skill_name=$skillName, success=false, error_type=timeout",
                    )
                    action.result.complete(
                      "{\"error\": \"Skill execution timed out. Please check network connection.\"}"
                    )
                  }
                }

                // Load url.
                suspendCancellableCoroutine<Unit> { continuation ->
                  chatWebViewClient.setPageLoadListener {
                    chatWebViewClient.setPageLoadListener(null)
                    continuation.resume(Unit)
                  }
                  Log.d(TAG, "Loading url: ${action.url}")
                  webViewRef?.loadUrl(action.url)
                }

                // Execute JS.
                Log.d(TAG, "Start to run js")
                chatViewJavascriptInterface.onResultListener = { result ->
                  Log.d(TAG, "Got result:\n$result")
                  action.result.complete(result)
                  val isSuccess = !result.contains("\"error\":")
                  val errorType = if (isSuccess) "" else "js_error"
                  Log.d(
                    TAG,
                    "Analytics: skill_execution, capability_name=${task.id}, skill_name=$skillName, success=$isSuccess, error_type=$errorType",
                  )
                }

                val safeData = JSONObject.quote(action.data)
                val safeSecret = JSONObject.quote(action.secret)
                val script =
                  """
                  (async function() {
                      var startTs = Date.now();
                      while(true) {
                        if (typeof ai_edge_gallery_get_result === 'function') {
                          break;
                        }
                        await new Promise(resolve=>{
                          setTimeout(resolve, 100)
                        });
                        if (Date.now() - startTs > 10000) {
                          break;
                        }
                      }
                      var result = await ai_edge_gallery_get_result($safeData, $safeSecret);
                      AiEdgeGallery.onResultReady(result);
                  })()
                  """
                    .trimIndent()
                webViewRef?.evaluateJavascript(script, null)
              } catch (e: Exception) {
                Log.d(
                  TAG,
                  "Analytics: skill_execution, capability_name=${task.id}, skill_name=$skillName, success=false, error_type=exception",
                )
                action.result.completeExceptionally(e)
              }
            }
            is AskInfoAgentAction -> {
              currentAskInfoAction = action
              askInfoInputValue = "" // Reset input
              showAskInfoDialog = true
            }
            is RequestPermissionAgentAction -> {
              currentPermissionAction = action
              permissionLauncher.launch(action.permission)
            }
            is AskMcpToolCallPermissionAction -> {
              currentMcpPermissionAction = action
            }
            is AskFileWritePermissionAction -> {
              currentFileWritePermissionAction = action
            }
            is AskUniversalAgentActionPermissionAction -> {
              currentUniversalAgentPermissionAction = action
            }
          }
        }
      }

      GalleryWebView(
        // JS-skill execution only. `hidden` is essential: a WebView child with MATCH_PARENT
        // layout params can escape a 1dp AndroidView wrapper on some OEM WebView implementations,
        // compositing an opaque white layer over the chat and hiding all messages.
        modifier = Modifier.size(1.dp),
        hidden = true,
        onWebViewCreated = { webView ->
          webViewRef = webView
          webView.addJavascriptInterface(chatViewJavascriptInterface, "AiEdgeGallery")
        },
        customWebViewClient = chatWebViewClient,
        onConsoleMessage = { consoleMessage ->
          consoleMessage?.let { curConsoleMessage ->
            // Create a LogMessage from the ConsoleMessage and add it to the progress panel.
            val logMessage =
              LogMessage(
                level =
                  when (curConsoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.LOG -> LogMessageLevel.Info
                    ConsoleMessage.MessageLevel.ERROR -> LogMessageLevel.Error
                    ConsoleMessage.MessageLevel.WARNING -> LogMessageLevel.Warning
                    else -> LogMessageLevel.Info
                  },
                source = curConsoleMessage.sourceId(),
                lineNumber = curConsoleMessage.lineNumber(),
                message = curConsoleMessage.message(),
              )
            viewModel.addLogMessageToLastCollapsableProgressPanel(
              model = model,
              logMessage = logMessage,
            )
            Log.d(
              TAG,
              "${curConsoleMessage.message()} " +
                "-- From line ${curConsoleMessage.lineNumber()} of ${curConsoleMessage.sourceId()}",
            )
          }
        },
      )

      // Coder's own searchInProject is a functionally distinct RAG partition from this chip's
      // ragSearch/RagManagerBottomSheet, and Actions/Web Search likewise don't apply to the
      // file-scoped Coder tab — showing this row there would be actively misleading.
      if (task.id === BuiltInTaskId.LLM_AGENT_CHAT) {
        // Reasoning is a thin toggle over the pre-existing native "Enable thinking" per-model
        // switch (ConfigKeys.ENABLE_THINKING) — not a rememberSaveable of its own, so it stays in
        // sync with the Config dialog's own switch. needReinitialization = false on that config
        // means flipping it here doesn't need a session reset (see Config.kt).
        val showReasoningToggle =
          task.allowCapability(ModelCapability.LLM_THINKING, selectedModel)
        val reasoningEnabled =
          remember(selectedModel.name, modelManagerUiState.configValuesUpdateTrigger) {
            selectedModel.getBooleanConfigValue(key = ConfigKeys.ENABLE_THINKING, defaultValue = false)
          }
        AgentModeTriggers(
          actionsEnabled = actionsEnabled,
          ragEnabled = ragEnabled,
          skillCount = skillCount,
          mcpToolsCount = mcpToolsCount,
          mobileActionsEnabled = mobileActionsEnabled,
          ragDocumentCount = ragUiState.documents.size,
          ragChunkCount = ragUiState.documents.sumOf { it.chunkCount },
          ragIndexing = ragUiState.importing,
          ragIndexingDone = ragUiState.importProgress?.done ?: 0,
          ragIndexingTotal = ragUiState.importProgress?.total ?: 0,
          showReasoningToggle = showReasoningToggle,
          reasoningEnabled = reasoningEnabled,
          onReasoningToggled = { enabled ->
            selectedModel.configValues =
              selectedModel.configValues + (ConfigKeys.ENABLE_THINKING.label to enabled)
            modelManagerViewModel.updateConfigValuesUpdateTrigger()
          },
          onActionsToggled = { enabled ->
            actionsEnabled = enabled
            resetSessionWithCurrentSkillsAndMcps(
              viewModel,
              modelManagerViewModel,
              skillManagerViewModel,
              task,
              curSystemPrompt,
              agentTools,
              initialMessages = llmChatUiState.messagesByModel[selectedModel.name] ?: emptyList(),
              clearHistory = false,
              actionsEnabled = enabled,
              ragEnabled = ragEnabled,
              mobileActionsEnabled = mobileActionsEnabled,
              mobileActionsTools = mobileActionsTools,
              ragDocuments = ragUiState.documents,
              coderTools = coderTools,
              universalAgentEnabled = universalAgentEffectivelyEnabled,
              universalAgentTools = universalAgentTools,
              webSearchEnabled = webSearchEnabled,
            )
          },
          onSkillsSettingsClicked = { showSkillsManagerBottomSheet = true },
          onRagToggled = { enabled ->
            ragEnabled = enabled
            resetSessionWithCurrentSkillsAndMcps(
              viewModel,
              modelManagerViewModel,
              skillManagerViewModel,
              task,
              curSystemPrompt,
              agentTools,
              initialMessages = llmChatUiState.messagesByModel[selectedModel.name] ?: emptyList(),
              clearHistory = false,
              actionsEnabled = actionsEnabled,
              ragEnabled = enabled,
              mobileActionsEnabled = mobileActionsEnabled,
              mobileActionsTools = mobileActionsTools,
              ragDocuments = ragUiState.documents,
              coderTools = coderTools,
              universalAgentEnabled = universalAgentEffectivelyEnabled,
              universalAgentTools = universalAgentTools,
              webSearchEnabled = webSearchEnabled,
            )
          },
          onRagSettingsClicked = { showRagManagerBottomSheet = true },
          webSearchEnabled = webSearchEnabled,
          webSearchCaption = webSearchCaption(webSearchEnabled, webSearchUiState),
          onWebSearchToggled = { enabled ->
            webSearchEnabled = enabled
            resetSessionWithCurrentSkillsAndMcps(
              viewModel,
              modelManagerViewModel,
              skillManagerViewModel,
              task,
              curSystemPrompt,
              agentTools,
              initialMessages = llmChatUiState.messagesByModel[selectedModel.name] ?: emptyList(),
              clearHistory = false,
              actionsEnabled = actionsEnabled,
              ragEnabled = ragEnabled,
              mobileActionsEnabled = mobileActionsEnabled,
              mobileActionsTools = mobileActionsTools,
              ragDocuments = ragUiState.documents,
              coderTools = coderTools,
              universalAgentEnabled = universalAgentEffectivelyEnabled,
              universalAgentTools = universalAgentTools,
              webSearchEnabled = enabled,
            )
          },
          onWebSearchSettingsClicked = { showWebSearchSettingsBottomSheet = true },
        )

        // Files attached to the chat (RAG) shown right above the input so the user can see and
        // remove what's currently in context for their next message.
        AttachedFilesRow(
          documents = ragUiState.documents,
          onRemove = { documentId -> ragManagerViewModel.removeDocument(documentId) },
        )
      }

      if (coderTools != null) {
        CoderProjectPickerRow(
          uiState = coderUiState,
          onPick = { uri -> coderViewModel.pickProjectFolder(uri) },
          onReindex = { coderViewModel.reindex() },
        )
      }
    },
    allowEditingSystemPrompt = true,
    curSystemPrompt = curSystemPrompt,
    onSystemPromptChanged = { newPrompt ->
      curSystemPrompt = newPrompt
      viewModel.applySystemPromptChange(
        task = task,
        model = modelManagerViewModel.uiState.value.selectedModel,
        newPrompt = newPrompt,
        systemPromptUpdatedMessage = systemPromptUpdatedMessage,
      )
    },
    emptyStateComposable = { model ->
      val uiState by viewModel.uiState.collectAsState()
      val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
      val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[model.name]
      Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
          !WindowInsets.isImeVisible,
          enter = fadeIn(animationSpec = tween(200)),
          exit = fadeOut(animationSpec = tween(200)),
        ) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
              modifier =
                Modifier.align(Alignment.Center)
                  .padding(horizontal = 48.dp)
                  .padding(bottom = 48.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Text(
                stringResource(R.string.introducing),
                style = MaterialTheme.typography.headlineSmall,
              )
              Text(
                stringResource(R.string.agent_skills),
                style =
                  MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Medium,
                    brush =
                      Brush.linearGradient(colors = listOf(Color(0xFF85B1F8), Color(0xFF3174F1))),
                  ),
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
              )
              Text(
                AnnotatedString.fromHtml(
                  stringResource(
                    R.string.agent_skills_intro,
                    AgentSkillsURLs.REPOSITORY,
                    AgentSkillsURLs.DISCUSSIONS,
                  )
                ),
                style =
                  MaterialTheme.typography.headlineSmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          }
        }

        Row(
          modifier =
            Modifier.align(Alignment.BottomCenter)
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          for (promptChip in TRYOUT_CHIPS) {
            if (
              promptChip.skillName == "learn-something-new" &&
                selectedModel.name != "Gemma-4-E4B-it"
            ) {
              continue
            }
            FilledTonalButton(
              enabled =
                modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZED &&
                  !uiState.isResettingSession,
              onClick = {
                // Skill is selected, trigger sending the message.
                if (skillManagerViewModel.isSkillSelected(promptChip.skillName)) {
                  sendMessageTrigger =
                    SendMessageTrigger(
                      model = model,
                      messages =
                        listOf(ChatMessageText(content = promptChip.prompt, side = ChatSide.USER)),
                    )
                }
                // Skill is not selected, show alert dialog.
                else {
                  disabledSkillName = promptChip.skillName
                  showAlertForDisabledSkill = true
                }
              },
              contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
              Icon(promptChip.icon, contentDescription = null, modifier = Modifier.size(20.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text(promptChip.label)
            }
          }
        }
      }
    },
    sendMessageTrigger = sendMessageTrigger,
  )

  if (showAskInfoDialog && currentAskInfoAction != null) {
    val action = currentAskInfoAction!!
    SecretEditorDialog(
      title = action.dialogTitle,
      fieldLabel = action.fieldLabel,
      value = askInfoInputValue,
      onValueChange = { askInfoInputValue = it },
      onDone = {
        action.result.complete(askInfoInputValue)
        showAskInfoDialog = false
        currentAskInfoAction = null
      },
      onDismiss = {
        action.result.complete("")
        showAskInfoDialog = false
        currentAskInfoAction = null
      },
    )
  }

  if (currentMcpPermissionAction != null) {
    val action = currentMcpPermissionAction!!
    McpToolCallPermissionDialog(
      toolName = action.toolName,
      argument = action.argument,
      onResult = { result ->
        action.result.complete(result)
        if (result == PermissionResult.ALWAYS_ALLOW) {
          val serverState =
            mcpManagerViewModel.uiState.value.mcpServers.find { serverState ->
              serverState.mcpServer.toolsList.any { it.name == action.toolName }
            }
          serverState?.mcpServer?.url?.let { url ->
            mcpManagerViewModel.setMcpToolAlwaysAllow(
              url = url,
              toolName = action.toolName,
              alwaysAllow = true,
            )
          }
        }
        currentMcpPermissionAction = null
      },
    )
  }

  if (currentFileWritePermissionAction != null) {
    val action = currentFileWritePermissionAction!!
    FileWritePermissionDialog(
      path = action.path,
      preview = action.preview,
      onResult = { result ->
        action.result.complete(result)
        currentFileWritePermissionAction = null
      },
    )
  }

  if (currentUniversalAgentPermissionAction != null) {
    val action = currentUniversalAgentPermissionAction!!
    UniversalAgentActionPermissionDialog(
      actionDescription = action.actionDescription,
      onResult = { result ->
        action.result.complete(result)
        currentUniversalAgentPermissionAction = null
      },
    )
  }

  if (showUniversalAgentDisclaimer) {
    UniversalAgentDisclaimerDialog(
      onDismiss = { showUniversalAgentDisclaimer = false },
      onConfirm = {
        showUniversalAgentDisclaimer = false
        universalAgentEnablementState.requested.value = true
        if (!universalAgentServiceEnabled) {
          context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        // The AccessibilityService itself has no foreground-service protection — ask to be
        // exempted from OEM battery-optimization killing too, or aggressive battery managers can
        // silently kill it in the background. No-op if already exempted.
        requestIgnoreBatteryOptimizations(context)
        resetSessionWithCurrentSkillsAndMcps(
          viewModel,
          modelManagerViewModel,
          skillManagerViewModel,
          task,
          curSystemPrompt,
          agentTools,
          initialMessages = llmChatUiState.messagesByModel[selectedModel.name] ?: emptyList(),
          clearHistory = false,
          actionsEnabled = actionsEnabled,
          ragEnabled = ragEnabled,
          mobileActionsEnabled = mobileActionsEnabled,
          mobileActionsTools = mobileActionsTools,
          ragDocuments = ragUiState.documents,
          coderTools = coderTools,
          // requested is now true, so the effective flag simplifies to serviceEnabled's current
          // value — if it's still false (service not enabled yet), the LaunchedEffect(
          // universalAgentServiceEnabled) above picks up the merge once the user comes back from
          // Settings with it enabled.
          universalAgentEnabled = universalAgentServiceEnabled,
          universalAgentTools = universalAgentTools,
          webSearchEnabled = webSearchEnabled,
        )
      },
    )
  }

  if (showSkillsManagerBottomSheet) {
    SkillsManagerBottomSheet(
      agentTools = agentTools,
      skillManagerViewModel = skillManagerViewModel,
      mcpManagerViewModel = mcpManagerViewModel,
      mobileActionsEnabled = mobileActionsEnabled,
      skillCount = skillCount,
      mcpToolsCount = mcpToolsCount,
      onMobileActionsToggled = { enabled ->
        mobileActionsEnabled = enabled
        resetSessionWithCurrentSkillsAndMcps(
          viewModel,
          modelManagerViewModel,
          skillManagerViewModel,
          task,
          curSystemPrompt,
          agentTools,
          initialMessages = llmChatUiState.messagesByModel[selectedModel.name] ?: emptyList(),
          clearHistory = false,
          actionsEnabled = actionsEnabled,
          ragEnabled = ragEnabled,
          mobileActionsEnabled = enabled,
          mobileActionsTools = mobileActionsTools,
          ragDocuments = ragUiState.documents,
          coderTools = coderTools,
          universalAgentEnabled = universalAgentEffectivelyEnabled,
          universalAgentTools = universalAgentTools,
          webSearchEnabled = webSearchEnabled,
        )
      },
      onDismiss = { contentChanged ->
        showSkillsManagerBottomSheet = false
        if (contentChanged) {
          Log.d(TAG, "Selected skills or MCPs/tools changed. Resetting conversation.")
          resetSessionWithCurrentSkillsAndMcps(
            viewModel,
            modelManagerViewModel,
            skillManagerViewModel,
            task,
            curSystemPrompt,
            agentTools,
            initialMessages = llmChatUiState.messagesByModel[selectedModel.name] ?: emptyList(),
            clearHistory = false,
            actionsEnabled = actionsEnabled,
            ragEnabled = ragEnabled,
            mobileActionsEnabled = mobileActionsEnabled,
            mobileActionsTools = mobileActionsTools,
            ragDocuments = ragUiState.documents,
            coderTools = coderTools,
            universalAgentEnabled = universalAgentEffectivelyEnabled,
            universalAgentTools = universalAgentTools,
            webSearchEnabled = webSearchEnabled,
          )
        }
      },
    )
  }

  if (showRagManagerBottomSheet) {
    RagManagerBottomSheet(
      onDismiss = { showRagManagerBottomSheet = false },
      ragEnabled = ragEnabled,
      onRagToggled = { enabled ->
        ragEnabled = enabled
        resetSessionWithCurrentSkillsAndMcps(
          viewModel,
          modelManagerViewModel,
          skillManagerViewModel,
          task,
          curSystemPrompt,
          agentTools,
          initialMessages = llmChatUiState.messagesByModel[selectedModel.name] ?: emptyList(),
          clearHistory = false,
          actionsEnabled = actionsEnabled,
          ragEnabled = enabled,
          mobileActionsEnabled = mobileActionsEnabled,
          mobileActionsTools = mobileActionsTools,
          ragDocuments = ragUiState.documents,
          coderTools = coderTools,
          universalAgentEnabled = universalAgentEffectivelyEnabled,
          universalAgentTools = universalAgentTools,
          webSearchEnabled = webSearchEnabled,
        )
      },
    )
  }

  if (showWebSearchSettingsBottomSheet) {
    WebSearchSettingsBottomSheet(onDismiss = { showWebSearchSettingsBottomSheet = false })
  }

  if (showAlertForDisabledSkill) {
    AlertDialog(
      onDismissRequest = { showAlertForDisabledSkill = false },
      title = { Text(stringResource(R.string.disabled_skill_dialog_title, disabledSkillName)) },
      text = { Text(stringResource(R.string.enable_skill_dialog_content)) },
      confirmButton = {
        Button(onClick = { showAlertForDisabledSkill = false }) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }
}

/** Caption for the "Web search" trigger chip — mirrors AgentModeTriggers.kt's private ragCaption. */
private fun webSearchCaption(enabled: Boolean, uiState: WebSearchSettingsUiState): String {
  if (!enabled) return "Off"
  if (uiState.selectedProvider == WebSearchProvider.NONE) return "Not configured"
  val providerLabel =
    uiState.selectedProvider.name.lowercase().replaceFirstChar { it.uppercase() }
  return "$providerLabel · ${uiState.dailyCallsRemaining} left today"
}

private fun updateProgressPanel(viewModel: LlmChatViewModel, model: Model, agentTools: AgentTools) {
  // Update status.
  val lastProgressPanelMessage =
    viewModel.getLastMessageWithType(
      model = model,
      type = ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
    )
  if (
    lastProgressPanelMessage != null &&
      lastProgressPanelMessage is ChatMessageCollapsableProgressPanel
  ) {
    if (lastProgressPanelMessage.title.startsWith("Loading")) {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(
          label = lastProgressPanelMessage.title.replace("Loading", "Loaded"),
          inProgress = false,
        )
      )
    } else if (lastProgressPanelMessage.title.startsWith("Calling")) {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(
          label = lastProgressPanelMessage.title.replace("Calling", "Called"),
          inProgress = false,
        )
      )
    } else if (lastProgressPanelMessage.title.startsWith("Executing")) {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(
          label = lastProgressPanelMessage.title.replace("Executing", "Executed"),
          inProgress = false,
        )
      )
    } else {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(label = lastProgressPanelMessage.title, inProgress = false)
      )
    }
  }
}

private fun resetSessionWithCurrentSkillsAndMcps(
  viewModel: LlmChatViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  skillManagerViewModel: SkillManagerViewModel,
  task: Task,
  curSystemPrompt: String,
  agentTools: AgentTools,
  onDone: (Model) -> Unit = {},
  initialMessages: List<ChatMessage> = listOf(),
  clearHistory: Boolean = true,
  actionsEnabled: Boolean = true,
  ragEnabled: Boolean = true,
  mobileActionsEnabled: Boolean = false,
  mobileActionsTools: MobileActionsTools? = null,
  ragDocuments: List<RagDocumentInfo> = emptyList(),
  coderTools: CoderTools? = null,
  universalAgentEnabled: Boolean = false,
  universalAgentTools: UniversalAgentTools? = null,
  webSearchEnabled: Boolean = true,
) {
  val model = modelManagerViewModel.uiState.value.selectedModel
  // maxTokens caps the replay to the model's own configured budget -- see toLiteRtMessages's doc
  // comment.
  val litertMessages =
    initialMessages.toLiteRtMessages(
      maxTokens =
        model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    )
  val toolsPrompt = agentTools.mcpManagerViewModel.getToolsPrompt()
  val actualSystemPrompt = getEffectiveBaseSystemPrompt(curSystemPrompt, toolsPrompt.isNotEmpty())
  val engineConfiguration =
    UnifiedAgentConfiguration(
      actionsEnabled = actionsEnabled,
      ragEnabled = ragEnabled,
      webSearchEnabled = webSearchEnabled,
      mobileActionsEnabled = mobileActionsEnabled,
      universalAgentEnabled = universalAgentEnabled,
      // CoderTools are project-scoped and only supplied by the Coder task. General programming
      // guidance still applies in AI Chat without granting file access.
      programmingEnabled = true,
      // Groundwork only: no SuperAgentExtension is installed or enabled yet.
      superAgentEnabled = false,
    )
  val unifiedAgentEngine =
    UnifiedAgentEngine(
      agentTools = agentTools,
      mobileActionsTools = mobileActionsTools,
      coderTools = coderTools,
      universalAgentTools = universalAgentTools,
    )
  viewModel.resetSession(
    task = task,
    model = model,
    systemInstruction =
      unifiedAgentEngine.compileInstruction(
        baseSystemPrompt = actualSystemPrompt,
        skills = skillManagerViewModel.getSelectedSkills(),
        toolsPrompt = toolsPrompt,
        ragDocuments = ragDocuments,
        configuration = engineConfiguration,
      ),
    tools = unifiedAgentEngine.toolProviders(engineConfiguration),
    // See the matching comment in AgentChatTaskModule.kt's initializeModelFn — must track the
    // model's actual capability, not hardcode true, or session reset fails the same way initial
    // load did for any model without a vision/audio encoder.
    supportImage = model.llmSupportImage,
    supportAudio = model.llmSupportAudio,
    onDone = { onDone(model) },
    // See the matching comment in AgentChatTaskModule.kt's initializeModelFn — off for now,
    // observed failing on both a non-Gemma model and its own intended Gemma-4 target.
    enableConversationConstrainedDecoding = false,
    initialMessages = litertMessages,
    clearHistory = clearHistory,
  )
}

class ChatWebViewJavascriptInterface {
  var onResultListener: ((String) -> Unit)? = null

  @JavascriptInterface
  fun onResultReady(result: String) {
    onResultListener?.invoke(result)
  }
}

class ChatWebViewClient(val context: Context) : BaseGalleryWebViewClient(context = context) {
  private var onPageLoaded: (() -> Unit)? = null

  fun setPageLoadListener(listener: (() -> Unit)?) {
    onPageLoaded = listener
  }

  override fun onPageFinished(view: WebView?, url: String?) {
    super.onPageFinished(view, url)
    Log.d(TAG, "page loaded")
    onPageLoaded?.invoke()
  }
}
