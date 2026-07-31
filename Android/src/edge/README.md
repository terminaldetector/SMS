# GEDGE Distillation (`:edge:*`)

Clean, modular extracts from Google AI Edge Gallery / Saturn Mask for host apps
(Gallery shell today, Chatter Triangle / ChatterUI native bridge next).

Edge is **split into library modules**, not rewritten. Packages stay
`com.saturnmask.gallery.*` so the existing app wires with minimal import churn;
Gradle coordinates are the integration surface.

## Module graph

```
:edge:core
   ↑
   ├── :edge:llm              (LiteRT-LM + AICore helpers)
   ├── :edge:embedder
   │      ↑
   │      └── :edge:rag
   │             ↑
   │             └── :edge:tools-coder
   ├── :edge:websearch
   ├── :edge:mcp
   └── :edge:tools-mobile

:app  →  all of the above + Compose UI / AgentTools host bindings
```

## What lives where

| Module | Contents | Extracted from |
|--------|----------|----------------|
| `:edge:core` | `Model`, `Accelerator`, `Config`/`ConfigKeys`, LLM defaults, `ModelCapability` + override store, `AgentAction*` bus | `data/*`, `common/AgentActions` |
| `:edge:llm` | `LlmModelHelper`, `LlmChatModelHelper` (CPU/GPU/NPU backends, multimodal Contents), `AICoreModelHelper`, `Model.runtimeHelper` | `runtime/*`, `ui/llmchat/LlmChatModelHelper` |
| `:edge:embedder` | `TextEmbedder`, EmbeddingGemma TFLite runner, hashing fallback, embedder settings | `domain/rag/*Embedder*` |
| `:edge:rag` | `RagEngine` (static/dynamic), chunking/index/search, document extractors, Hilt modules | `domain/rag/*` |
| `:edge:websearch` | Brave/Serper engines, page fetcher, settings store, Hilt module | `domain/websearch/*` |
| `:edge:mcp` | Google Workspace MCP presets (HTTP) | `domain/mcp/*` |
| `:edge:tools-mobile` | `MobileActionsTools` + action catalog | `customtasks/mobileactions/{Tools,Actions}` |
| `:edge:tools-coder` | SAF file IO, patch apply, project indexer (DYNAMIC RAG) | `customtasks/coder/*` (logic only) |

## Intentionally left in `:app`

| Area | Why |
|------|-----|
| Compose UI (home, chat chrome, bottom sheets, triggers) | Host-owned presentation |
| `AgentTools` / `IntentHandler` / Skill+MCP ViewModels | Still bound to Gallery protos + ViewModels — Phase 2 extract behind interfaces |
| Universal Agent (AccessibilityService) | Manifest/service lifecycle is host-specific |
| Model allowlist, downloads, HF OAuth, Tasks/CustomTask registration | Gallery shell / catalog |
| Demo/marketing surfaces | Already cleaned in Saturn Mask; not distilled |

## Host integration (Chatter Triangle)

1. Depend on the modules you need, e.g. `implementation(project(":edge:llm"))` + `:edge:rag` + `:edge:websearch`.
2. Provide Hilt (or rebind EntryPoints) for `RagEngine` / `WebSearchEngine` / embedder file paths.
3. Implement the `AgentAction` permission/progress side in your UI (same types as Gallery).
4. Do **not** pull Gallery Compose screens — only logic modules.

## Backend note

This fork does not expose a separate public `CompiledModel` API. Inference is
LiteRT-LM `Engine` + `Conversation` with `Backend.CPU|GPU|NPU` selected via
`ConfigKeys.ACCELERATOR` / `VISION_ACCELERATOR`.
