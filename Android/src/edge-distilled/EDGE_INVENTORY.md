# Edge inventory & Chatter Triangle assimilation checklist

Phase-1 deliverable: what is **actually used**, what is distilled, what remains host-owned.

## Readiness criteria

| Criterion | Status | Notes |
|---|---|---|
| Edge split into modules; each builds alone | ✅ | `:edge:core|llm|embedder|rag|websearch|mcp|tools*|settings` + `:edge:distilled` |
| Edge deps compatible with Chatter Triangle | 🟡 | See [Dependency conflicts](#dependency-conflicts). Compose leak in `:edge:tools-mobile` Actions icons. |
| RAG / Coder / Web / Mobile Actions inside Chatter Triangle | ⬜ | Distilled APIs ready; **not yet wired into ChatterUI** (source lives on `cursor/fix-sharding-*`) |
| LiteRT ↔ GGUF switch without app restart | 🟡 | `InferenceEngine` + `EngineRegistry` added; GGUF impl = ChatterUI `cui-llama.rn` bridge (host) |
| Edge settings moved into Chatter settings | 🟡 | `SettingsManager` + `AccessTokenStore` ports; Gallery adapter exists; Chatter MMKV adapter TODO |
| Edge UI triggers adapted to Chatter design | ⬜ | Logic-only distillation by design — Chatter owns UI |
| Builds & runs on a real device | ⬜ | `compileDebugKotlin` green here; device APK = ChatterUI host branch |

## Module map (prompt tree)

```
edge-distilled/
├── engine/   LiteRTInference, ModelLoader, BackendSelector,
│             InferenceEngine, EngineRegistry, AgentRunnerLiteRt
├── rag/      RagEngine, Chunker (TextChunker), Embedder (TextEmbedder)
├── coder/    CoderTools, ProjectIndexer
├── web/      WebSearchEngine
├── actions/  MobileActionsTools
├── mcp/      McpWorkspacePresets
├── tools/    AgentTools (+ SkillHost / McpHost / IntentRunner / JsSkillRunner)
└── settings/ SettingsManager, AccessTokenStore
```

## Classes / APIs that are load-bearing (used)

### Engine (`:edge:llm`)
| Type | Role |
|---|---|
| `LlmModelHelper` / `LlmChatModelHelper` | LiteRT-LM Engine+Conversation init/infer/cleanup |
| `BackendSelector` | CPU/GPU/NPU(/TPU→NPU) mapping |
| `ModelLoader` | path / readiness |
| `LiteRTInference` | host façade over helpers |
| `InferenceEngine` / `LiteRTInferenceEngine` / `EngineRegistry` | **unified LiteRT↔GGUF API + hot-swap** |
| `AgentRunnerLiteRt` | HELIX Track A `submit`/`poll`/`score`/`cardJson` |
| `AICoreModelHelper` | optional ML Kit GenAI path (package-allowlisted) |
| `Model.runtimeHelper` | routes LITERT_LM vs AICORE |

### RAG + embedders
| Type | Role |
|---|---|
| `RagEngine` / `RagEngineImpl` | static JSON index + dynamic session map, cosine search |
| `TextChunker` | 512/50 overlapping chunks |
| `TextEmbedder`, `TfLiteTextEmbedder`, `FallbackTextEmbedder`, `HashingTextEmbedder` | EmbeddingGemma / custom / hash |
| `EmbedderSettingsStore`, `EMBEDDING_GEMMA_MODEL` | selection + download descriptor |
| extractors | PDF / DOCX / EPUB / plain |

### Features
| Type | Role |
|---|---|
| `WebSearchEngine` (+ Brave/Serper), `WebPageFetcher`, settings store | search + readUrl |
| `CoderTools` / `CoderFileResolver` / `CoderProjectIndexer` | SAF IO, patch, DYNAMIC RAG project search |
| `MobileActionsTools` + `Actions` | device ToolSet catalog |
| `McpWorkspacePresets` | Google Workspace HTTP MCP presets |
| distilled `AgentToolsImpl` | ToolSet composition via host ports |

### Core / settings
| Type | Role |
|---|---|
| `Model`, `Config`/`ConfigKeys`, `Accelerator`, `ModelCapability*` | model descriptor + capability gating |
| `AgentAction*` | permission/progress bus for host UI |
| `SettingsManager` | HF token, embedder, web-search keys |

## Intentionally NOT distilled (leave / delete from host APK)

- Gallery Compose screens, home marketing, TOS promos
- `Tasks.kt` demo tiles / legacy `LLM_CHAT`
- Universal Agent AccessibilityService (host lifecycle)
- Gallery ViewModel-bound `AgentTools` (use distilled ports instead)
- HF AppAuth UI, Firebase, benchmark UI, model-manager chrome
- MediaPipe `tasks-genai` scaffold in `integration/edge_litert` (superseded by LiteRT-LM)

## Dependency conflicts

| Artifact | Module | Chatter Triangle note |
|---|---|---|
| `litertlm-android` | `:edge:llm`, tools | Keep — LiteRT path. **Do not** also add `com.google.ai.edge.litert:litert` (duplicate `libLiteRt.so`) |
| Play Services TFLite + DJL tokenizers | `:edge:embedder` | Keep isolated in embedder; conflicts with full LiteRT AAR |
| Compose (icons) | `:edge:tools-mobile` | **Conflict for RN host** — strip icons from `Actions` before RN packaging, or keep module Android-only |
| Hilt | several | Optional for Chatter; EntryPoints work; prefer constructor injection in host |
| pdfbox-android | `:edge:rag` | OK if RAG shipped; omit module if unused |
| mcp-kotlin-sdk / Ktor | Gallery MCP client (app) | Distilled presets are dep-free; live MCP client stays host |

## Chatter Triangle wiring order (priority)

1. **Depend** on `:edge:distilled` (or leaf modules) from the Android Gradle host that ChatterUI already uses for native code — **or** copy `edge-distilled/` sources into the Triangle repo.
2. **Register engines:**
   ```kotlin
   val registry = EngineRegistry()
   registry.register(LiteRTInferenceEngine())
   registry.register(hostGgufEngine) // implements InferenceEngine, kind=GGUF
   registry.activate(EngineKind.LITERT) // or GGUF — no process kill
   ```
3. **HELIX Track A:** wrap active LiteRT engine in `AgentRunnerLiteRt` → existing mesh `Transport` (BitChat / WS).
4. **Features one-by-one:** RAG → Web → Coder → Mobile Actions → MCP presets; adapt toggles to Chatter settings drawer (MMKV), not Gallery sheets.
5. **Do not** port Gallery Compose triggers — reimplement chips against Chatter design using the same boolean mode flags.

## Unified engine switch (no restart)

```
User toggles LiteRT | GGUF
        │
        ▼
EngineRegistry.activate(kind)   // unloads previous
        │
        ▼
registry.loadActive(ctx, request)
        │
        ├─ LITERT → LiteRTInferenceEngine (gallery Model / .litertlm)
        └─ GGUF   → host bridge → Llama.useLlamaModelStore.load(...)
```

Sessions / BitChat / DLAP / HELIX stay owned by Chatter Triangle; Edge only supplies runners + tools + RAG.

## Gap vs “works inside Chatter Triangle”

This SMS branch contains **Gallery + edge-distilled**. ChatterUI/HELIX live on
`origin/cursor/fix-sharding-single-worker-17ff`. Next assimilation PR should open on that
branch (or a merge branch) and consume `:edge:distilled` / these packages.
