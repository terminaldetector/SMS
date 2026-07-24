/*
 * Copyright 2025 Google LLC
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
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.saturnmask.gallery.R
import com.saturnmask.gallery.customtasks.common.CustomTask
import com.saturnmask.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.saturnmask.gallery.customtasks.mobileactions.MOBILE_ACTIONS_CATALOG
import com.saturnmask.gallery.customtasks.universalagent.UniversalAgentTools
import com.saturnmask.gallery.customtasks.universalagent.UniversalAgentToolsImpl
import com.saturnmask.gallery.data.BuiltInTaskId
import com.saturnmask.gallery.data.Category
import com.saturnmask.gallery.data.Model
import com.saturnmask.gallery.data.Task
import com.saturnmask.gallery.domain.rag.RagDocumentInfo
import com.saturnmask.gallery.proto.McpServers
import com.saturnmask.gallery.proto.Skill
import com.saturnmask.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGAgentChatTask"

// The default system prompt for the agent chat task with both skills and MCP tools.
const val DEFAULT_SYSTEM_PROMPT =
  """
  You are an AI assistant that helps users by answering questions and completing tasks using skills and tools. For EVERY new task, request, or question, you MUST execute the following steps in exact order. You MUST NOT skip any steps.

  CRITICAL RULE: You MUST execute all steps silently. Do NOT generate or output any internal thoughts, reasoning, explanations, or intermediate text at ANY step.

  1. EVALUATE AND ROUTE:
     Determine if the request should be handled by a "Skill" (requires loading instructions) or directly by an "MCP Tool".
     - If it is a Skill: Go to Step 2.
     - If it is an MCP Tool: Go to Step 4.
     - If nothing is found, output "No skills or tools found" and stop.

  --- SKILLS ---
  ___SKILLS___

  --- MCP TOOLS ---
  ___TOOLS___

  ==================================================
  FLOW A: SKILL EXECUTION
  ==================================================

  2. Find the most relevant skill from the --- SKILLS --- list (names only). If a name alone
     isn't enough to tell whether it's relevant, call `getSkillDescription` first — do not guess.
     You MUST NOT use `run_intent` or `runMcpTool` under any circumstances at this step.

  3. Use the `load_skill` tool to read its instructions. Follow the skill's instructions exactly to complete the task.
     - You MUST NOT output any intermediate thoughts or status updates. No exceptions!
     - Output ONLY the final result when successful. It should contain a one-sentence summary of the action taken and the final result of the skill.
     - Stop here once Flow A is complete.

  ==================================================
  FLOW B: MCP TOOL DIRECT EXECUTION
  ==================================================

  4. Find the most relevant tool from the --- MCP TOOLS --- list.

  5. Call the `runMcpTool` tool with the following parameters:
     - `toolName`: The name of the tool to run. Use the exact name from the list above. Do not hallucinate the name. Pay attention to casing and plurals.
     - `input`: The input JSON object that matches the tool's expected input schema.

  6. Output ONLY the final result returned by the tool. You MUST NOT output any intermediate thoughts or status updates. No exceptions!
  """

private val DEFAULT_SYSTEM_PROMPT_TRIMMED = DEFAULT_SYSTEM_PROMPT.trimIndent()

// The default system prompt for the agent chat task with only skills.
const val DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY =
  """
  You are an AI assistant that helps users by answering questions and completes tasks using skills. For EVERY new task or request or question, you MUST execute the following steps in exact order. You MUST NOT skip any steps.

  CRITICAL RULE: You MUST execute all steps silently. Do NOT generate or output any internal thoughts, reasoning, explanations, or intermediate text at ANY step.

  1. First, find the most relevant skill from the following list (names only). If a name alone
     isn't enough to tell whether it's relevant, call `getSkillDescription` first — do not guess.

  ___SKILLS___

  After this step you MUST go to next step. You MUST NOT use `run_intent` under any circumstances at this step.

  2. If a relevant skill exists, use the `load_skill` tool to read its instructions. You MUST NOT use `run_intent` under any circumstances at this step.

  3. Follow the skill's instructions exactly to complete the task. You MUST NOT output any intermediate thoughts or status updates. No exceptions! Output ONLY the final result when successful. It should contain one-sentence summary of the action taken, and the final result of the skill.

  4. If no relevant skill is found, output "No relevant skills found" and stop.
  """

private val DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY_TRIMMED =
  DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY.trimIndent()

class AgentChatTask @Inject constructor() : CustomTask {
  private val agentTools: AgentTools = AgentToolsImpl()
  // Never wired into CoderTask — Universal Agent's full-device screen-read/control scope is
  // opt-in per the roadmap's "universal agent" phase, entered via Agent Chat's own "+" menu (see
  // AgentChatScreen.kt/MessageInputText.kt), not something Coder's file-scoped session should
  // also gain implicitly.
  private val universalAgentTools: UniversalAgentTools =
    UniversalAgentToolsImpl(sendAction = { action -> agentTools.sendAgentAction(action) })

  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_AGENT_CHAT,
      // Renamed from "Agent Skills" — this is now the single unified chat entry point on the
      // home screen (Ask Image / Audio Scribe / Mobile Actions / Agent Skills all merged in via
      // the triggers row + image/audio pickers already wired into this screen), not a
      // skills-specific side feature anymore.
      label = "AI Chat",
      category = Category.LLM,
      iconVectorResourceId = R.drawable.agent,
      newFeature = true,
      models = mutableListOf(),
      description =
        "Chat with an on-device LLM — ask about images, transcribe audio, use skills and " +
          "device actions, all in one place",
      shortDescription = "Chat, images, audio, skills & actions",
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
      defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT_TRIMMED,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
    initialMessages: List<Message>,
  ) {
    val initialSystemPrompt = systemInstruction?.toString() ?: task.defaultSystemPrompt
    coroutineScope.launch(Dispatchers.Default) {
      val unused = model.setupAgentSkillTopK()
      val skillsJob = launch { agentTools.skillManagerViewModel.loadSkills() }
      val mcpJob = launch { agentTools.mcpManagerViewModel.loadMcpServers() }
      skillsJob.join()
      mcpJob.join()

      // Determine base system prompt based on whether MCP tools are enabled.
      val toolsPrompt = agentTools.mcpManagerViewModel.getToolsPrompt()
      val baseSystemPrompt =
        getEffectiveBaseSystemPrompt(initialSystemPrompt, toolsPrompt.isNotEmpty())

      val unifiedAgentEngine = UnifiedAgentEngine(agentTools = agentTools)
      val engineConfiguration = UnifiedAgentConfiguration(programmingEnabled = true)
      val finalSystemInstruction =
        unifiedAgentEngine.compileInstruction(
          baseSystemPrompt = baseSystemPrompt,
          skills = agentTools.skillManagerViewModel.getSelectedSkills(),
          toolsPrompt = toolsPrompt,
          ragDocuments = emptyList(),
          configuration = engineConfiguration,
        )

      LlmChatModelHelper.initialize(
        context = context,
        model = model,
        taskId = task.id,
        // Model-agnostic gating, not a hardcoded true: requesting supportImage/supportAudio on a
        // model whose .task file has no vision/audio encoder makes the engine fail outright
        // ("NOT_FOUND: TF_LITE_VISION_ENCODER not found in the model") instead of just skipping
        // the unsupported modality. Text-only models (Qwen2.5, DeepSeek-R1-Distill, Gemma3-1B —
        // none of them carry llmSupportImage/llmSupportAudio) reach Agent Chat via manual import,
        // where they're unconditionally added to the LLM_AGENT_CHAT task regardless of modality.
        supportImage = model.llmSupportImage,
        supportAudio = model.llmSupportAudio,
        onDone = onDone,
        systemInstruction = finalSystemInstruction,
        tools = unifiedAgentEngine.toolProviders(engineConfiguration),
        // Was hardcoded true — observed failing two different ways on real devices: "Failed to
        // create conversation: INTERNAL: Failed to create GemmaModelConstraintProvider" on a
        // non-Gemma model (Qwen2-VL-2B, where that provider doesn't apply at all), and "Failed to
        // invoke the compiled model" (llm_litert_compiled_model_executor.cc:756) on a Gemma-4
        // model — its own intended target. Since it's not reliably working for either the
        // model family it's named for or any other, off until the actual failure mode is
        // understood, not just gated by family the way supportImage/supportAudio are above.
        enableConversationConstrainedDecoding = false,
        initialMessages = initialMessages,
      )
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    val unused = model.cleanupAgentSkillTopK()
    LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    AgentChatScreen(
      task = task,
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      agentTools = agentTools,
      universalAgentTools = universalAgentTools,
      initialQuery = myData.initialQuery,
      sessionId = myData.sessionId,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object AgentChatTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return AgentChatTask()
  }

  @Provides
  @Singleton
  fun provideMcpServersDataStore(@ApplicationContext context: Context): DataStore<McpServers> {
    return DataStoreFactory.create(
      serializer = McpServersSerializer,
      produceFile = { context.dataStoreFile("mcp_servers.pb") },
    )
  }
}

fun injectSkillsAndMcpTools(
  baseSystemPrompt: String,
  skills: List<Skill>,
  toolsPrompt: String,
  actionsEnabled: Boolean = true,
  ragEnabled: Boolean = true,
  mobileActionsEnabled: Boolean = false,
  // NEW: current RAG index contents, so the model knows what's actually searchable right now
  // instead of discovering it only by calling ragSearch and getting nothing back. Empty by
  // default so callers that don't pass it (if any remain) behave exactly as before.
  ragDocuments: List<RagDocumentInfo> = emptyList(),
  universalAgentEnabled: Boolean = false,
  webSearchEnabled: Boolean = true,
): Contents =
  Contents.of(
    compileSkillsAndMcpPrompt(
      baseSystemPrompt = baseSystemPrompt,
      skills = skills,
      toolsPrompt = toolsPrompt,
      actionsEnabled = actionsEnabled,
      ragEnabled = ragEnabled,
      mobileActionsEnabled = mobileActionsEnabled,
      ragDocuments = ragDocuments,
      universalAgentEnabled = universalAgentEnabled,
      webSearchEnabled = webSearchEnabled,
    )
  )

/** Raw prompt compiler used by [UnifiedAgentEngine] before it appends capability instructions. */
fun compileSkillsAndMcpPrompt(
  baseSystemPrompt: String,
  skills: List<Skill>,
  toolsPrompt: String,
  actionsEnabled: Boolean = true,
  ragEnabled: Boolean = true,
  mobileActionsEnabled: Boolean = false,
  ragDocuments: List<RagDocumentInfo> = emptyList(),
  universalAgentEnabled: Boolean = false,
  webSearchEnabled: Boolean = true,
): String {
  // "Actions" (roadmap term) covers both skills (load_skill) and MCP tools (runMcpTool) —
  // both are tool-calling based, so both get suppressed together when the user turns the
  // Actions trigger off.
  //
  // Names only, not descriptions — with many skills selected (e.g. 15+), injecting every
  // skill's full description into every single system prompt was bloating it enough to blow
  // past small models' max_tokens on the very first turn (observed: a 1024-token model choking
  // on a ~1259-token prompt before the user had typed anything). The model can call the new
  // getSkillDescription tool for any name it's unsure about, and loadSkill still fetches full
  // instructions once it actually decides to use one — same JIT pattern loadSkill already used,
  // just extended one level further up.
  // Mobile Actions are appended into this SAME list so the model gets one capability inventory.
  // Their legacy direct-tool adapter and AgentTools are registered together by UnifiedAgentEngine;
  // callers must not assemble either path independently. Mobile Actions retain a separate opt-in
  // gate because device effects are more sensitive than loading an informational skill.
  val selectedSkillNames =
    buildString {
      if (actionsEnabled) {
        skills.filter { it.selected }.forEach { skill -> appendLine("- ${skill.name}") }
      }
      if (mobileActionsEnabled) {
        if (isNotEmpty()) appendLine()
        appendLine("Device actions (always available when Mobile Actions is on):")
        MOBILE_ACTIONS_CATALOG.forEach { (name, description) -> appendLine("- $name: $description") }
      }
    }
      .trimEnd()
  val effectiveToolsPrompt = if (actionsEnabled) toolsPrompt else ""

  val systemPrompt =
    if (selectedSkillNames.isBlank() && effectiveToolsPrompt.isBlank()) {
      ""
    } else {
      baseSystemPrompt
        .replace("___SKILLS___", selectedSkillNames)
        .replace("___TOOLS___", effectiveToolsPrompt)
    }

  val gatingText =
    buildModeGatingText(
      actionsEnabled = actionsEnabled,
      ragEnabled = ragEnabled,
      mobileActionsEnabled = mobileActionsEnabled,
      universalAgentEnabled = universalAgentEnabled,
      webSearchEnabled = webSearchEnabled,
    )
  val ragDocumentsText = buildRagDocumentsPromptText(ragEnabled = ragEnabled, documents = ragDocuments)

  val finalPrompt =
    listOf(systemPrompt, gatingText, ragDocumentsText)
      .filter { it.isNotBlank() }
      .joinToString("\n\n")
  Log.d(TAG, "System prompt:\n$finalPrompt")
  return finalPrompt
}

/**
 * Appends explicit, user-controlled mode instructions (the "triggers" from the interface
 * roadmap) on top of the existing skills/MCP routing prompt above. Additive only — when both
 * flags are true this is a no-op relative to the pre-existing prompt, so behavior for anyone
 * not using the new triggers UI is unchanged.
 */
fun buildModeGatingText(
  actionsEnabled: Boolean,
  ragEnabled: Boolean,
  mobileActionsEnabled: Boolean = false,
  universalAgentEnabled: Boolean = false,
  webSearchEnabled: Boolean = true,
): String {
  // Mobile Actions is hard-gated by whether its ToolSet is even added to the session (see
  // resetSessionWithCurrentSkillsAndMcps), not by a prompt instruction, so it doesn't need a
  // branch here — the parameter is accepted for symmetry/future use. Universal Agent IS hard-gated
  // the same way, but unlike Mobile Actions' fixed 7 well-scoped actions, its action space is
  // unbounded across arbitrary apps — brief usage guidance earns its token cost here. Web Search
  // (webSearch/readUrl) lives on the same always-present AgentTools ToolSet as loadSkill/
  // runMcpTool, so — like Actions/RAG — it can only be soft-gated via this prompt text, not by
  // omitting it from a tool list.
  if (actionsEnabled && ragEnabled && webSearchEnabled && !universalAgentEnabled) return ""
  return buildString {
      appendLine("--- MODE SETTINGS (user-controlled triggers) ---")
      if (!actionsEnabled) {
        appendLine(
          "Actions: OFF. Do NOT call load_skill, runMcpTool, runJs, or runIntent under any " +
            "circumstances, even if a relevant skill or tool exists. Answer using your own " +
            "knowledge only."
        )
      }
      if (!ragEnabled) {
        appendLine("RAG: OFF. Do NOT call ragSearch, even if documents are indexed.")
      }
      if (!webSearchEnabled) {
        appendLine(
          "Web Search: OFF. Do NOT call webSearch or readUrl under any circumstances, even if " +
            "the user asks about current events or shares a URL."
        )
      }
      if (universalAgentEnabled) {
        appendLine(
          "Universal Agent: ON. You can see and control the screen across any app. Always call " +
            "listScreenElements before tapElement/typeText/swipeScreen — never guess an element " +
            "id. State what you're about to do in your visible response before calling " +
            "tapElement, typeText, swipeScreen, goBack, goHome, or openApp, since each of those " +
            "prompts the user for confirmation anyway."
        )
      }
    }
    .trimEnd()
}

/**
 * Lists what's actually in the local RAG index right now, so the model can decide WHETHER to
 * call `ragSearch` at all instead of either (a) never calling it because it doesn't know
 * anything is indexed, or (b) calling it speculatively on every turn "just in case". Rebuilt
 * every time the session resets — including on trigger toggles (existing behavior) AND now on
 * document add/remove (see AgentChatScreen.kt's ragUiState.documents LaunchedEffect) — so this
 * never goes stale mid-session the way a one-time system prompt would.
 */
private fun buildRagDocumentsPromptText(ragEnabled: Boolean, documents: List<RagDocumentInfo>): String {
  if (!ragEnabled || documents.isEmpty()) return ""
  return buildString {
    appendLine("--- RAG DOCUMENTS AVAILABLE ---")
    appendLine(
      "The following documents are indexed locally and searchable via ragSearch. Prefer " +
        "calling ragSearch over guessing when a question could relate to one of these:"
    )
    documents.forEach { doc ->
      appendLine("- \"${doc.name}\" (${doc.chunkCount} chunks): ${doc.previewText}")
    }
  }
    .trimEnd()
}

// Check whether the system prompt is the default one.
fun isDefaultSystemPrompt(prompt: String): Boolean {
  return prompt == DEFAULT_SYSTEM_PROMPT_TRIMMED ||
    prompt == DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY_TRIMMED
}

// Returns the effective default system prompt depending on whether MCP tools are enabled.
fun getEffectiveBaseSystemPrompt(currentPrompt: String, hasMcpTools: Boolean): String {
  return if (isDefaultSystemPrompt(currentPrompt)) {
    if (hasMcpTools) DEFAULT_SYSTEM_PROMPT_TRIMMED else DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY_TRIMMED
  } else {
    currentPrompt
  }
}
