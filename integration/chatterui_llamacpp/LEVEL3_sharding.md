# ChatterUI Level 3 — Track B sharding (one big GGUF across phones)

Level 3 is the ambitious one: split **one** large model across several ChatterUI phones by layer,
so a model too big for any single device runs on the mesh. Levels 1–2 needed no native changes;
Level 3 does, because cui-llama.rn's JS API exposes only whole-model `completion`, not layer bands
or an RPC backend. This doc specifies the native work and records what HELIX already provides.

There are two strategies. **We recommend Option A first** (fastest real sharding), with Option B
as the end-state.

---

## Option A — llama.cpp RPC (recommended first)

llama.cpp already ships a distributed backend, `GGML_RPC`: each contributing device runs an
`rpc-server`, and one **main** process runs the model with `--rpc host:port,…`, offloading layer
work to those servers. The tensor split and the activation transport are **entirely llama.cpp's**.

**HELIX's job is the control plane** — the part llama.cpp does *not* do:

| HELIX provides | Module |
|---|---|
| discovery (who is here, are they alive) | `helix/control.py` (ANNOUNCE/HEARTBEAT/prune) |
| placement — memory-weighted layer bands, **per-node RAM-fit check** | `helix/placement.py` `plan_placement` |
| planning **by proven memory**, not the claim (③) | `helix/attest.py` `attested_capacities` |
| identity — which nodes may join (④) | `helix/identity.py` |
| the RPC topology handed to llama.cpp | `helix/rpc_cluster.py` `plan_rpc_cluster` |

HELIX emits exactly what the driver needs (`helix/rpc_cluster.py` → `rpc_plan` control command):

```
{ ring:[hlxA,hlxB,hlxC], main:"hlxA",
  rpc_arg:"10.0.0.2:50052,10.0.0.3:50052",     // --rpc  (the workers)
  tensor_split:[0.5,0.333,0.167],               // --tensor-split (main-local, worker0, worker1)
  endpoints:[{node,addr,band:[s,e),role}] }
```

This control plane is **built and tested here** (no device needed):
`node integration/chatterui_llamacpp/js/rpc_smoke.mjs` → a JS client drives the real Python
coordinator and gets a valid plan (`PYTHONPATH=. python3 -m helix.rpc_cluster` covers the planner
directly). The one protocol addition was an optional `rpc` (`host:port`) field in `ANNOUNCE` so the
coordinator can build the `--rpc` list; it is omitted when empty, so the wire/conformance vectors
are unchanged.

### Native work in cui-llama.rn (the remaining, off-device part)

1. **Build with RPC:** compile the bundled llama.cpp with `-DGGML_RPC=ON` for the Android ABIs.
2. **Worker method:** expose `startRpcServer(port)` (binds `ggml`'s `rpc-server` on
   `0.0.0.0:port`). Every participating phone calls it and announces its `host:port` (that becomes
   the `rpc` field HELIX advertises).
3. **Main/driver method:** let `initLlama` / the context init accept an `--rpc` endpoint list and
   `--tensor-split` ratios (thread them into the `common_params` llama.cpp already parses). The
   ChatterUI driver phone reads `plan.rpc_arg` / `plan.tensor_split` from `rpcPlan()` and runs its
   normal `completion` — now distributed.
4. **Wire-up:** ChatterUI JS calls `HelixControlClient.rpcPlan({model_id,n_layers,model_bytes})`,
   starts `rpc-server` on workers, runs `completion` on `main`. See `LlamaRpcCluster` in `helix.ts`.

### Honest caveats (Option A)

- **Data plane is llama.cpp's TCP RPC, not HELIX frames.** The tensor/activation hops are **not**
  HELIX-sealed — no ChaCha20-Poly1305, no replay guard, and no USB/BLE transport on those hops.
  Run RPC over a trusted LAN or a WireGuard tunnel. (Option B closes this.)
- **Healing is coarse.** RPC has no mid-run resume. On node loss HELIX detects it (heartbeat prune
  → `placement_stale`), **re-plans over the survivors, and restarts the run** — vs Track B's
  token-checkpoint resume (`helix/orchestrator.py`).
- **Bandwidth.** Cross-device activation traffic during prefill is real; keep the ring small and
  co-located (same Wi-Fi/USB segment). `tensor_split` from memory weight is a starting point, not a
  latency-optimal order (Phase-6 RTT ordering applies here too).

---

## Option B — full HELIX ring (end-state)

A native, ggml-backed `ShardRunner` (mirroring `helix/shard.py`: `embed` / `forward` a layer band /
`sample` / `detok`) so activations ride **HELIX frames** end-to-end. This is what makes HELIX worth
it beyond LAN:

- **Any transport** — the ring runs over Wi-Fi, **USB-OTG (star/ring)**, or BLE via the same
  `MeshRouter`, because activations are HELIX frames, not TCP sockets.
- **Confidential + authenticated hops** — the int8 activation codec (`helix/activation.py`) under
  AEAD; replay-protected `ACTIVATION`/`FEED`/`SHARD_TOKEN`.
- **Fine-grained healing/resume** — re-place over survivors and resume decode from the last token
  (`helix/orchestrator.py`). **Proven cross-language:** `js/heal_smoke.mjs` runs a JS shard that
  joins the control plane, is leased a band, contributes tokens, then **goes silent mid-generation**;
  the Python orchestrator prunes it, re-places over the survivors, and resumes from the checkpoint —
  the session completes with the *same* tokens as fault-free (`heals >= 1`). This is the resume
  Option A cannot do (RPC restarts the whole run).

The whole ring protocol is proven in Python (`helix/pipeline_selftest.py`: a real L3 placement →
L4 ring → tokens that only match if every band ran) **and now cross-language in JS**: a JS shard
worker joins a real Python ring over TCP and threads activations through its band —
`node integration/chatterui_llamacpp/js/shard_smoke.mjs` → tokens `[7,13,19]` (both bands ran over
sealed HELIX frames). So the **data-plane wire** (`FEED`/`ACTIVATION`/`SHARD_TOKEN` + the int8/raw
activation codec, pinned by `vectors.json` `activation_codec`) is reproducible in the native/TS
layer. The remaining gap is the **native ggml `ShardRunner`** (real band execution + hidden-state
I/O) inside cui-llama.rn — a substantial C++ task, deferred until Option A is shipping. It plugs in
behind the exact seam the JS `NumericShardRunner` fills (`embed` / `forward` a band / `sample` /
`detok`); the ring, framing, codec, healing and transport around it are done.

---

## Bring-up order

1. **Control plane (done here):** `rpc_cluster` planner + `rpc_plan` command, verified by
   `rpc_smoke.mjs`. HELIX places by (attested) memory and emits `--rpc`/`--tensor-split`.
2. **Native RPC (Option A):** build cui-llama.rn with `GGML_RPC`; add `startRpcServer` +
   `--rpc`/`--tensor-split` init; wire `rpcPlan()` → workers + main.
3. **2-phone shard test:** one 13B-class GGUF that does not fit one phone, split across two, driven
   from ChatterUI. Then add attestation (③) and identity (④) gating for admission.
4. **Option B (later):** native ggml `ShardRunner` → activations over HELIX frames → USB/BLE +
   resume. Reuse `helix/shard.py`, `helix/pipeline.py`, `helix/orchestrator.py` unchanged.

## Licensing

ChatterUI is **AGPL-3.0**; integrating HELIX (and any llama.cpp RPC build) keeps the combined app
AGPL. Confirm before shipping.
