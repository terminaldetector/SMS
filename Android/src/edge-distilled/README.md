# edge-distilled/

Public assimilation surface for Chatter Triangle. Matches the distillation tree:

```
edge-distilled/
├── engine/          # LiteRTInference, ModelLoader, BackendSelector  (:edge:llm)
├── rag/             # RagEngine, Chunker, Embedder                   (:edge:rag + :edge:embedder)
├── coder/           # CoderTools, ProjectIndexer                     (:edge:tools-coder)
├── web/             # WebSearchEngine                                (:edge:websearch)
├── actions/         # MobileActionsTools                             (:edge:tools-mobile)
├── mcp/             # McpWorkspacePresets                            (:edge:mcp)
├── tools/           # AgentTools + host ports                        (:edge:tools)
└── settings/        # SettingsManager (HF / embedder / web keys)     (:edge:settings)
```

## Gradle

```kotlin
implementation(project(":edge:distilled"))
```

Or depend on leaf modules only.

## Host wiring (Chatter Triangle)

1. Provide Hilt/`EntryPoint` for `RagEngine` + `WebSearchEngine` (modules already included).
2. Implement `AccessTokenStore` and construct `SettingsManager`.
3. Implement `SkillHost` / `McpHost` / `IntentRunner` / `JsSkillRunner` as needed and construct `AgentToolsImpl`.
4. Consume `AgentAction` channel for permission/progress UI in the host.
5. Register `LiteRTInferenceEngine` + host `InferenceEngine(kind=GGUF)` on `EngineRegistry`; call `activate()` to hot-swap without killing the process.
6. For HELIX Track A, wrap the LiteRT engine in `AgentRunnerLiteRt` (`submit`/`poll`/`cardJson`).

See [EDGE_INVENTORY.md](./EDGE_INVENTORY.md) for the used-class inventory and readiness checklist.

## Not included

Gallery Compose UI, demo Tasks, Universal Agent AccessibilityService, HF OAuth UI, model-allowlist catalog screens. GGUF/llama.cpp itself stays in ChatterUI (`cui-llama.rn`).
