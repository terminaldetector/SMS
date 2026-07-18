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

> **Confirmed:** `cui-llama.rn` (Vali-98) exposes **no RPC** — `ContextParams` / `NativeContextParams`
> / `initLlama` have no `rpc_servers` and the build has no `GGML_RPC`. So Level 3 needs a **native
> fork** of the module. Levels 1–2 need none of this. The TS seam that consumes the fork is
> `app_mod/lib/helixRpc.ts` (`NativeHelixRpc` + `RpcInitLlama`); the control plane it calls
> (`HelixClient.rpcPlan`) is done and proven (`js/rpc_smoke.mjs`, 7/7).

1. **Build with RPC:** compile the bundled llama.cpp with `-DGGML_RPC=ON` for the Android ABIs
   (the Gradle/CMake for the module's `android/` — cui-llama.rn already builds ggml from source
   there, so this is a build flag + linking the rpc backend).
2. **Worker method (`startRpcServer(port)`):** wrap ggml's `rpc-server` (or `ggml_backend_rpc_start_server`)
   as a TurboModule method binding `0.0.0.0:port`. Each phone calls it and announces its `host:port`
   (becomes the `rpc` field in HELIX `ANNOUNCE` — already supported, `helix/control.py`).
3. **Main/driver — `rpc_servers` + `tensor_split` params:** add `rpc_servers?: string[]` and
   `tensor_split?: number[]` to `ContextParams` **and** `NativeContextParams` (`src/`), thread them
   through the bridge (`android/src/main/java/com/rnllama/*` + `cpp/rnllama.cpp`) into llama.cpp's
   `common_params.rpc_servers` / `tensor_split`. The driver phone then loads the model distributed
   (`helixRpc.ts` `startShardMain` passes `plan.rpc_arg.split(',')` + `plan.tensor_split`).
4. **Wire-up (TS, ready):** `app_mod/lib/helixRpc.ts` — workers call `startShardWorker(native)`,
   the driver calls `startShardMain(client, initLlama, model)` which fetches `rpcPlan()` and inits
   the model with the RPC topology. See also `LlamaRpcCluster` in `helix.ts`.

### Sharding you can run today (llama.cpp binaries — no app fork)

You don't need the cui-llama.rn fork to *try* sharding: HELIX plans the topology and stock
llama.cpp binaries (built with `-DGGML_RPC=ON`) do the tensor split. `helix/host/rpc_launch.py`
turns a live plan into the exact commands:

```bash
# a HELIX coordinator (ControlNode with rpc addresses) is running on TCP <port>:
python -m helix.host.rpc_launch --host <coord-ip> --port <port> \
    --model-id big-16b --n-layers 80 --model-bytes 16000000000 --model-path /sdcard/big.gguf
# ->  worker B:  rpc-server -H 0.0.0.0 -p 50052        (run on each worker device)
#     worker C:  rpc-server -H 0.0.0.0 -p 50052
#     main A:    llama-cli -m /sdcard/big.gguf -ngl 99 --rpc B:50052,C:50052 --tensor-split 0.5,0.33,0.17
```

Build llama.cpp per device (Termux on Android, or a Linux SBC) with `-DGGML_RPC=ON`; run the printed
commands. HELIX supplies discovery + memory-weighted placement + attestation; llama.cpp does the
tensors. This is the fastest route to a real "16B across N phones" demo, ahead of the in-app fork.
(`python -m helix.host.rpc_launch --selftest` verifies the command builder.)

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

### Data-plane bandwidth (measured)

`python -m helix.host.bandwidth` seals a **real** `ACTIVATION` frame per codec (activations
round-tripped through float32, like a real engine), per token per inter-shard hop:

| model / d_model | raw JSON | int8 b64 | int8/raw | int8 binary\* |
|---|---|---|---|---|
| ~1–3B / 2048 | 39.7 KB | 2.9 KB | 7.3% | 2.0 KB |
| ~7–8B / 4096 | 79.3 KB | 5.6 KB | 7.1% | 4.1 KB |
| ~13B / 5120 | 99.1 KB | 7.0 KB | 7.1% | 5.1 KB |
| ~65–70B / 8192 | 158.5 KB | 11.1 KB | 7.0% | 8.2 KB |

\* theoretical binary body (no base64) — the follow-up path; int8 b64 carries a ~33% base64 tax
over it. **int8 is ~14× smaller than raw JSON** and is the practical default. Projected over a
4-node ring (3 hops) at 20 tok/s decode, a 13B model needs **~3.3 Mb/s with int8** vs ~47.6 Mb/s
raw — int8 sits comfortably inside real Wi-Fi (~100–300 Mb/s) and USB-OTG, while raw JSON strains
Wi-Fi at large `d_model`. Prefill multiplies the per-token cost by `seq_len` (one bursty pass).
Next lever if needed: a **binary activation body** (drops the base64 tax) behind the same
`ActivationCodec` seam — no ring-driver change.

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
