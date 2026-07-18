# ChatterUI (React Native + cui-llama.rn) — integrating HELIX

ChatterUI is a React Native Android app that runs GGUF models on-device via **cui-llama.rn**
(a fork of llama.rn wrapping llama.cpp). Its JS API is `initLlama(...)` →
`context.completion(params, cb)` (streaming) + `tokenize`/`detokenize`. Python can't run
naturally in RN, so on the ChatterUI side HELIX is **native/TS**.

## Integration ladder (start at the top)

| Level | What ChatterUI becomes | Effort | Needs |
|---|---|---|---|
| **1. Client** | UI over a HELIX node | 🟢 low | a TS TCP client to `helix/host/control_server.py` (JSON-lines). No protocol port. **Proven end-to-end** — `js/control_smoke.mjs` drives the real mesh, 11/11; RN client in `control_client.ts`. |
| **2. Agent** | its model = a **Track A** mesh agent | 🟡 med | **Proven end-to-end** — a JS HELIX agent (`js/agent_node.mjs`) joins the real Python coordinator and serves single/parallel/voting/pipeline (`js/agent_smoke.mjs`). To ship: wrap llama.rn `completion` as the `AgentRunner` (`makeLlamaAgentRunner`) + RN TCP transport. |
| **3. Sharding** | contributes layers to a **Track B** big model | 🔴 high | **native cui-llama.rn changes**: llama.cpp RPC (`GGML_RPC`) or a `ShardRunner` over ggml. **Both wires proven** — Option A: `js/rpc_smoke.mjs` gets a real `--rpc`/`--tensor-split` plan (7/7); Option B: `js/shard_smoke.mjs` runs a JS shard in a real HELIX ring (`[7,13,19]`). Remaining is native tensor execution. See `LEVEL3_sharding.md`. |

**Key point:** the *easiest and most valuable* ChatterUI integration is **Level 2 — its whole
GGUF model as a Track A agent** (a mesh of ChatterUI phones = a Pointer mesh of GGUF models),
NOT Track B sharding. Level 3 (sharding across ChatterUI instances) is the ambitious step and is
a native-module fork task, because llama.rn's JS API exposes only whole-model completion, not
layer bands or RPC.

## Files
- `helix.ts` — contracts for all three levels: `HelixControlClient` (L1), `makeLlamaAgentRunner`
  + `AgentRunner`/`AgentCard` (L2), `LlamaRpcCluster`/`ShardRunner` (L3), plus `Transport`,
  `SecurityBridge`, and the conformance gate.
- `control_client.ts` — **Level 1**, the RN client (`react-native-tcp-socket`) implementing
  `HelixControlClient` (JSON-lines: ping/status/nodes/context/infer/super). Transport-agnostic.
- `LEVEL3_sharding.md` — **Level 3** native design: Option A (llama.cpp RPC, HELIX control plane)
  recommended first, Option B (full HELIX ring, USB/BLE, resume) as end-state; exact cui-llama.rn
  seams, caveats, bring-up.
- `js/` — **the bring-up gates, all green.** Level 1: `control_client.mjs` + `control_smoke.mjs`
  drive the real Python mesh (11/11). Level 2: `frame_codec.mjs` + `agent_node.mjs` +
  `agent_smoke.mjs` run a JS agent that joins the real coordinator and serves all four modes;
  `helix_codec.mjs` + `conformance.mjs` pin the wire vs `vectors.json` (16/16). Level 3:
  `rpc_smoke.mjs` gets a real llama.cpp RPC plan from HELIX (7/7). See `js/README.md`.
- `js/` — **the wire-compat gate, green.** A JS reference codec (`helix_codec.mjs`) + harness
  (`conformance.mjs`) that reproduces `helix/spec/vectors.json` byte-for-byte in plain Node
  `crypto` — the riskiest part of Level 2 (HELIX in JS/TS), now demonstrated. See `js/README.md`.

## The crux (why L2/L3 need work)
HELIX's protocol core is Python (`helix/`); ChatterUI is TS + native C++. So Levels 2-3 need a
**TS/native HELIX client**, not Chaquopy. The surface is small — frame codec, `FrameCodec`,
agent worker — and must match `helix/spec/vectors.json` byte-for-byte (crypto in a native
JSI/TurboModule: ChaCha20-Poly1305 + Ed25519 + HKDF). Alternatively a **shared Rust core**
(C ABI/JSI) serves both ChatterUI and edge.

## Readiness
- **Level 1 — proven:** control server exists and is tested, and a client is shown driving the
  real mesh end-to-end (`js/control_smoke.mjs`, 11/11). ChatterUI ships `control_client.ts` over
  `react-native-tcp-socket` — the same framing. Fastest way to demo ChatterUI ↔ HELIX mesh.
- **Level 2 — proven end-to-end:** a JS HELIX agent (codec + `FrameCodec` + stream transport +
  worker) joins the **real** Python coordinator and serves single/parallel/voting/pipeline
  (`js/agent_smoke.mjs`); the wire is pinned to `vectors.json` (16/16). Remaining to ship in
  ChatterUI: wrap llama.rn `completion` as the `AgentRunner` and swap `node:net` for
  `react-native-tcp-socket` (crypto via a native `SecurityBridge` on-device). The protocol is done.
- **Level 3 — both wires proven, native tensor exec pending:**
  - *Option A (RPC):* HELIX places a model by (attested) memory and returns the llama.cpp
    `--rpc`/`--tensor-split` topology (`js/rpc_smoke.mjs`, 7/7). Remaining: native `GGML_RPC` build
    + `startRpcServer`/`--rpc` methods.
  - *Option B (full HELIX ring):* a JS shard worker joins a real HELIX layer-shard ring and threads
    activations through its band (`js/shard_smoke.mjs`, tokens `[7,13,19]`); the activation codec is
    pinned in `vectors.json`. **Self-healing proven** — a JS shard dies mid-generation and the ring
    re-places over survivors + resumes from the checkpoint (`js/heal_smoke.mjs`). Remaining: a
    native ggml `ShardRunner` behind the same seam.
  See `LEVEL3_sharding.md`.

## Licensing
ChatterUI is **AGPL-3.0**. Integrating HELIX makes the combined app AGPL — confirm this is
acceptable before shipping.

## Bring-up order
1. Conformance gate: TS/native crypto reproduces `helix/spec/vectors.json`. **Done in JS** (`js/`,
   plain Node `crypto`); on-device, re-run the same vectors through the native `SecurityBridge`.
2. Level 1: TS control client → `infer`/`super` against a running HELIX node.
3. Level 2: `makeLlamaAgentRunner` + TS agent worker → ChatterUI joins as an agent; 2-phone
   Track A test (single/parallel/voting/pipeline).
4. Level 3: control plane is done (`rpcPlan()` → `--rpc`/`--tensor-split`); build cui-llama.rn with
   `GGML_RPC`, add `startRpcServer`/`--rpc` init, then a 2-phone shard test. Full detail in
   `LEVEL3_sharding.md`.
