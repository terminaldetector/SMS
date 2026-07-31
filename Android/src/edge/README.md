# GEDGE Distillation (`:edge:*` + `:edge:distilled`)

Edge is **split into parts and assimilated**, not rewritten. Use
[`../edge-distilled/`](../edge-distilled/) as the Chatter Triangle entry point.

## Prompt tree ↔ Gradle modules

```
edge-distilled/
├── engine/     → :edge:llm      (LiteRTInference, ModelLoader, BackendSelector)
├── rag/        → :edge:rag + :edge:embedder  (RagEngine, Chunker/TextChunker, Embedder)
├── coder/      → :edge:tools-coder
├── web/        → :edge:websearch
├── actions/    → :edge:tools-mobile
├── mcp/        → :edge:mcp
├── tools/      → :edge:tools    (AgentTools + SkillHost/McpHost ports)
└── settings/   → :edge:settings (SettingsManager, AccessTokenStore)
```

Shared types (`Model`, `AgentAction*`, configs, capability overrides) live in `:edge:core`.

## Backend note

No separate public `CompiledModel` API in this fork. Backend switching is
`BackendSelector` → LiteRT-LM `EngineConfig.backend` / `visionBackend` / `audioBackend`
(CPU / GPU / NPU).

## Left in `:app`

Compose UI, Gallery `AgentTools` ViewModel adapters (can migrate to distilled
`AgentToolsImpl` + host ports), Universal Agent service, downloads/HF OAuth UI,
allowlist catalog, demo Tasks.
