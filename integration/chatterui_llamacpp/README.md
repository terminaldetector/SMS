# ChatterUI (React Native + cui-llama.rn) — integrating HELIX

ChatterUI is a React Native Android app that runs GGUF models on-device via **cui-llama.rn**
(a fork of llama.rn wrapping llama.cpp). Its JS API is `initLlama(...)` →
`context.completion(params, cb)` (streaming) + `tokenize`/`detokenize`. Python can't run
naturally in RN, so on the ChatterUI side HELIX is **native/TS**.

## Integration ladder (start at the top)

| Level | What ChatterUI becomes | Effort | Needs |
|---|---|---|---|
| **1. Client** | UI over a HELIX node | 🟢 low | a TS TCP client to `helix/host/control_server.py` (JSON-lines). No protocol port. The server is built + tested. |
| **2. Agent** | its model = a **Track A** mesh agent | 🟡 med | `makeLlamaAgentRunner` (llama.rn `completion` → `AgentRunner`) + a **small TS HELIX client** (frame codec + transport + agent worker) verified vs `vectors.json`; crypto via `SecurityBridge`. |
| **3. Sharding** | contributes layers to a **Track B** big model | 🔴 high | **native cui-llama.rn changes**: llama.cpp RPC (`GGML_RPC`) or a `ShardRunner` over ggml. |

**Key point:** the *easiest and most valuable* ChatterUI integration is **Level 2 — its whole
GGUF model as a Track A agent** (a mesh of ChatterUI phones = a Pointer mesh of GGUF models),
NOT Track B sharding. Level 3 (sharding across ChatterUI instances) is the ambitious step and is
a native-module fork task, because llama.rn's JS API exposes only whole-model completion, not
layer bands or RPC.

## Files
- `helix.ts` — contracts for all three levels: `HelixControlClient` (L1), `makeLlamaAgentRunner`
  + `AgentRunner`/`AgentCard` (L2), `LlamaRpcCluster`/`ShardRunner` (L3), plus `Transport`,
  `SecurityBridge`, and the conformance gate.
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
- **Level 1 — almost ready:** control server exists and is tested; ChatterUI needs only a TS TCP
  client. Fastest way to demo ChatterUI ↔ HELIX mesh.
- **Level 2 — crux de-risked:** the `AgentRunner` mapping is concrete, and the HELIX **wire is
  now proven reproducible in JS** (`js/` reproduces `vectors.json`, 16/16). Remaining: RN
  transport + agent worker — plumbing over the proven codec, plus wiring crypto through a native
  `SecurityBridge` on-device.
- **Level 3 — design only:** requires native cui-llama.rn RPC/ShardRunner.

## Licensing
ChatterUI is **AGPL-3.0**. Integrating HELIX makes the combined app AGPL — confirm this is
acceptable before shipping.

## Bring-up order
1. Conformance gate: TS/native crypto reproduces `helix/spec/vectors.json`. **Done in JS** (`js/`,
   plain Node `crypto`); on-device, re-run the same vectors through the native `SecurityBridge`.
2. Level 1: TS control client → `infer`/`super` against a running HELIX node.
3. Level 2: `makeLlamaAgentRunner` + TS agent worker → ChatterUI joins as an agent; 2-phone
   Track A test (single/parallel/voting/pipeline).
4. Level 3 (optional): llama.cpp RPC in cui-llama.rn → Track B sharding.
