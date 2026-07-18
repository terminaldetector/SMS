# HELIX JS — the ChatterUI bring-up gates (green)

Six proofs, all runnable in plain Node, all green:

- **Level 1 (client) — proven end-to-end.** `control_client.mjs` + `control_smoke.mjs` drive the
  **real** Python HELIX mesh over TCP (spawns `helix/host/control_demo.py`): ping / status /
  nodes / context / infer(single,parallel,voting) / super, plus error-keeps-connection.
  → `ALL PASSED (11 checks)`.
- **Level 2 (agent) — proven end-to-end.** `frame_codec.mjs` + `agent_node.mjs` + `agent_smoke.mjs`
  run a **JS HELIX agent that joins the real Python coordinator over TCP** (spawns
  `helix/host/agent_bridge_demo.py`) and answers `single`/`parallel`/`voting`/`pipeline` tasks
  (announce → TASK → PARTIAL stream → RESULT/VOTE). → `ALL PASSED — served 5 tasks`.
- **Level 2 wire gate.** `helix_codec.mjs` + `conformance.mjs` reproduce the HELIX wire
  byte-for-byte vs `vectors.json` (the crypto/framing anchor the agent builds on).
- **Level 3 — Option A (RPC control plane) proven end-to-end.** `rpc_smoke.mjs` asks the real
  Python coordinator (`helix/host/rpc_plan_demo.py`) to place a big model and returns the llama.cpp
  RPC topology (`ring` / `--rpc` / `--tensor-split` / `main`). → `ALL PASSED (7 checks)`. Tensor
  math + RPC transport are llama.cpp's (native); HELIX supplies discovery/placement/topology.
- **Level 3 — Option B (full HELIX ring) proven end-to-end.** `shard_smoke.mjs` runs a JS shard
  worker that joins a REAL Python layer-shard ring (`helix/host/shard_bridge_demo.py`) and threads
  activations through its band (`FEED`/`ACTIVATION`/`SHARD_TOKEN` + activation codec, sealed
  frames) → tokens `[7,13,19]` (both bands ran). The tensor math is the numeric reference; a real
  node swaps in a native ggml `ShardRunner` behind the same seam.
- **Level 3 — self-healing (②) proven end-to-end.** `heal_smoke.mjs` runs a JS control-shard node
  that joins the control plane (ANNOUNCE/HEARTBEAT), is leased a band, runs it, then **goes silent
  mid-generation**. The Python `Orchestrator` (`helix/host/heal_bridge_demo.py`) prunes it,
  re-places over the survivors, and **resumes from the checkpoint token** → session COMPLETED with
  fault-free tokens, `heals >= 1`.

## Files
- **Level 1:** `control_client.mjs` (JSON-lines client, incl. `rpcPlan`) · `control_smoke.mjs`
  (drives the mesh).
- **Level 2:** `helix_codec.mjs` (wire codec) · `frame_codec.mjs` (`FrameCodec` seal/open + seq +
  replay, stream framing) · `agent_node.mjs` (announce + TASK worker) · `agent_smoke.mjs`
  (end-to-end vs the real coordinator) · `conformance.mjs` (vectors gate).
- **Level 3:** `rpc_smoke.mjs` (Option A: llama.cpp RPC plan from HELIX) · `activation.mjs`
  (raw + int8 codec) · `shard_node.mjs` (`ShardNode` ring worker + `NumericShardRunner`) ·
  `shard_smoke.mjs` (Option B: JS shard joins the real ring) · `control_shard_node.mjs`
  (`ControlShardNode`: announce/heartbeat + lease-driven band + can go silent) · `heal_smoke.mjs`
  (self-healing: JS shard dies → ring re-places + resumes). Native side in `../LEVEL3_sharding.md`.

---

## Level 2 wire-compat gate (crypto + framing)

The riskiest part of the ChatterUI **Level 2 (agent)** integration is proving a TS/JS
implementation can speak HELIX's wire byte-for-byte. This folder proves it — in plain Node
`crypto`, no native module — against the same `helix/spec/vectors.json` the Python reference is
pinned to.

- **`helix_codec.mjs`** — JS reference of the full HELIX wire: HKDF-SHA256 (all-zero salt),
  ChaCha20-Poly1305 AEAD (`sealed = ct||tag`), frame header (`magic|flags|epoch(LE)|nonce`),
  compact message JSON (`{v,t,seq,src[,tid][,b]}`, insertion order), Ed25519 (seed→PKCS8 DER),
  `node_id = "hlx1"+sha256(pub)[:20]`, canonical signed claims, and the routing envelopes.
- **`conformance.mjs`** — loads `helix/spec/vectors.json` and asserts the codec reproduces every
  field, plus an AEAD open round-trip.

## Run

```bash
# Level 1 — client drives the real mesh (spawns the Python control server itself):
node integration/chatterui_llamacpp/js/control_smoke.mjs
# -> ALL PASSED (11 checks) — JS control client drives the real HELIX mesh (Level 1).

# Level 2 — JS agent joins the real coordinator and serves tasks (spawns the Python coordinator):
node integration/chatterui_llamacpp/js/agent_smoke.mjs
# -> ALL PASSED — JS agent joined the real HELIX mesh and served 5 tasks (Level 2).

# Level 2 wire gate — codec reproduces the vectors:
node integration/chatterui_llamacpp/js/conformance.mjs
# -> ALL PASSED (16 checks) — JS codec is wire-compatible with the Python reference.

# Level 3 Option A — JS gets a real llama.cpp RPC plan from HELIX:
node integration/chatterui_llamacpp/js/rpc_smoke.mjs
# -> ALL PASSED (7 checks) — JS drives the real HELIX Level 3 control plane (Option A).

# Level 3 Option B — JS shard worker joins a real HELIX ring and threads activations:
node integration/chatterui_llamacpp/js/shard_smoke.mjs
# -> ALL PASSED — JS shard joined the real HELIX ring and threaded 3 activations (Option B).

# Level 3 self-healing — JS shard dies mid-generation; the ring re-places + resumes:
node integration/chatterui_llamacpp/js/heal_smoke.mjs
# -> ALL PASSED — JS shard died; the real HELIX ring healed + resumed (Option B).
```

## What this de-risks

The crypto + framing surface — the part a `SecurityBridge`/`FrameCodec` must get exactly right —
is reproducible in JS, and the **full agent protocol on top of it is now proven end-to-end**
(`agent_smoke.mjs`): a JS agent announces, receives sealed `TASK` frames, streams `PARTIAL`, and
returns `RESULT`/`VOTE` to the real Python coordinator across all four modes. Node's `crypto`
provides all three primitives natively; on-device, `react-native-quick-crypto` (JSI) or a shared
Rust/C core exposes the same three, so the same vectors are the acceptance gate. What remains for
shipping in ChatterUI is wrapping cui-llama.rn's `completion` as the `AgentRunner` (mapping in
`../helix.ts`) and swapping `node:net` for `react-native-tcp-socket` — the protocol itself is done.

> `.mjs` is a reference/gate, not the shipped module. The shipped ChatterUI client is TS over a
> native `SecurityBridge` (see `../helix.ts`); these vectors are its conformance test.
