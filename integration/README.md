# HELIX native integration scaffolds

Reference stubs for wiring the tested Python protocol (`../helix/`) into the two host apps.
See **[`../INTEGRATION_BRIEF.md`](../INTEGRATION_BRIEF.md)** for the full plan, contracts,
options and effort/risk. These files are scaffolds (not built in this repo) — drop them into
the host project and fill the TODOs.

```
integration/
  edge_litert/            # Edge / LiteRT (Kotlin) — Track A (agents)
    AgentRunnerLiteRt.kt  #   LiteRT .task -> HostAgentRunner poll contract
    MeshService.kt        #   foreground service: Chaquopy + AgentNode + transport
  chatterui_llamacpp/     # ChatterUI (React Native + llama.cpp) — Track B (sharding)
    helix.ts              #   contracts (ShardRunner/Transport/SecurityBridge) + conformance gate
```

Concrete Python seams these plug into (both tested):
- `helix/agent/host.py` — `PollingAgentRunner`: adapts a native poll-based runner to `AgentRunner`.
- `helix/agent/edge_bootstrap.py` — `build_node` / `run` / `coordinate`: what `MeshService` calls.

**Order of work (critical path):**
1. Native crypto/codec passes `helix/spec/vectors.json` (`python -m helix.conformance`).
2. Edge `AgentRunner`→LiteRT, Track A on 2 phones (fastest first success).
3. ChatterUI Track B — start with llama.cpp RPC (Option A), then the full HELIX ring (Option B).
4. Turn on security (③ attestation, ④ signed votes/context — already in the core).
