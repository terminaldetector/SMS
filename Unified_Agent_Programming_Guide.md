# Unified Agent programming guide

`UnifiedAgentEngine` is the only composition boundary for AI Chat capabilities. Do not register a
new `ToolSet` directly in `AgentChatScreen`.

## Add a capability

1. Implement the smallest possible `ToolSet` adapter. Keep Android side effects in a reusable
   executor rather than in the UI.
2. Add the adapter and its explicit enablement flag to `UnifiedAgentEngine`.
3. Register tools in `UnifiedAgentEngine.toolProviders()`.
4. Add concise model guidance in `compileInstruction()`. A prompt flag is not a security boundary:
   sensitive tools must also be omitted from the tool list while disabled.
5. Route progress, permissions and confirmations through `AgentTools.actionChannel`.
6. Add the capability to the unified inventory shown to the model and user.
7. Verify cold initialization and live session reset use the same engine configuration.

## Programming behavior

`PROGRAMMING_INSTRUCTION` applies to AI Chat and Coder sessions. It requires minimal, compatible
changes, fenced code, verification steps, and forbids claiming writes/builds/tests without a
successful tool call. Project file access is available only when `CoderTools` is supplied.

## Mobile Actions and Agent Skills

Both are capabilities of `UnifiedAgentEngine`:

- `AgentTools` exposes skills, MCP, Android intents, RAG and web search.
- `MobileActionsTools` remains a direct-tool compatibility adapter for existing fine-tuned models.
- The engine owns prompt compilation, hard gating and LiteRT registration for both.

Do not instantiate a second Mobile Actions conversation engine. New device actions should have one
shared executor and may expose skill and direct-tool adapters only when model compatibility requires
both.

## Super Agent extension

Implement `SuperAgentExtension` and provide:

- a scoped system instruction;
- a list of `ToolProvider`s;
- explicit user-controlled enablement.

Super Agent tools must be permission-scoped, observable, cancellable and hard-gated. Disabled tools
must not be registered. Never treat prompt instructions as authorization. The default configuration
keeps `superAgentEnabled = false` and grants no autonomous capabilities.
