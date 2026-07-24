# Native fork of cui-llama.rn — enable llama.cpp RPC (HELIX Level 3, in-app sharding)

> **DONE.** This recipe has been executed: the fork lives at `forks/cui-llama.rn-rpc` (repo root),
> `ChatterUI/package.json`'s `cui-llama.rn` points at `file:../forks/cui-llama.rn-rpc`, and its RPC
> backend compiles + links **verified green in CI**
> (`.github/workflows/build_cuillama_rpc_fork.yaml`, builds the fork's own `example` app from source
> and uploads the linked `.so`s + APK). Full status: `forks/cui-llama.rn-rpc/RPC_FORK_NOTES.md`.
> `.github/workflows/chatterui-apk.yml` now builds ChatterUI itself with
> `rnllamaBuildFromSource=true` — that specific run (a real ChatterUI APK with RPC compiled in)
> hasn't happened yet, so on-device verification with the actual app is still the next step.
> The recipe below is kept as a reference for re-deriving the fork on a future `cui-llama.rn` version
> bump — it does not need to be repeated for the current version.

Goal: make the cui-llama.rn native module able to (a) **join** a distributed run as a worker
(`startRpcServer(endpoint, options?)`) and (b) **drive** one as the main node (`rpc_servers` +
`tensor_split` in `initLlama`). HELIX supplies the topology (`HelixClient.rpcPlan()`, proven); this
fork lets the app consume it. The TS seam: `ChatterUI/lib/helixRpc.ts`
(`startShardWorker` / `startShardMain` against the `NativeHelixRpc` / `RpcInitLlama` surface below,
which mirrors the fork's real API — see `RPC_FORK_NOTES.md` for the exact signatures).

> This was originally written as an engineering recipe grounded in the **actual** package source
> (`cui-llama.rn@1.11.14`, llama.cpp **b9309**) for applying and building in your own fork, since the
> cloud dev environment this repo was built in has no Android NDK. It has since been executed (see
> above) — steps below are the historical record of what was done.

## Confirmed baseline (why each step is needed)
- RPC is **stripped**: no `cpp/ggml-rpc.{cpp,h}`; no `rpc_servers` in `cpp/common/common.{h,cpp}`.
- Registration exists but is **gated**: `cpp/ggml-backend-reg.cpp` registers the RPC backend only
  under `#ifdef LM_GGML_USE_RPC` (note the `LM_` prefix used for ggml macros here).
- JS→C++ params flow through **`cpp/jsi/JSIParams.cpp`** (`params.hasProperty/getProperty`), not
  `@ReactMethod` (new-arch JSI; module bootstraps via `RNLlamaModule.install`).
- The module ships **prebuilt `.so`** (`bin/arm64-v8a`). Because we change C++, the fork must
  **build from source**: set `RNLLAMA_BUILD_FROM_SOURCE=ON` (`android/src/main/CMakeLists.txt:25`).

## Steps

### A. Vendor the RPC backend source (matching version)
Copy `ggml-rpc.cpp` and `ggml-rpc.h` from llama.cpp **b9309** (same build as `cpp/common/build-info.cpp`
→ `LLAMA_BUILD_NUMBER = 9309`) into `cpp/`. Keep the `lm_`/`LM_` symbol prefixing this fork uses
(the fork renames ggml symbols `ggml_*`→`lm_ggml_*`); run the fork's rename script or prefix by hand
so `lm_ggml_backend_rpc_reg()` / `lm_ggml_backend_rpc_start_server()` resolve.

### B. Restore `rpc_servers` in `common_params`
- `cpp/common/common.h`: add to `struct common_params` — `std::string rpc_servers = "";`
- `cpp/common/common.cpp` (`common_init_from_params`): restore the upstream b9309 block that, when
  `params.rpc_servers` is non-empty, registers each `host:port` as an RPC device (via the rpc
  backend reg) so llama.cpp offloads layers to them. (Copy the `if (!params.rpc_servers.empty())`
  section from upstream common.cpp b9309.)

### C. Build flags + source (CMake)
- `android/src/main/rnllama/CMakeLists.txt`: add `${RNLLAMA_LIB_DIR}/ggml-rpc.cpp` to the rnllama
  source list (next to `ggml-backend-reg.cpp`, ~line 41) and add the compile define
  `-DLM_GGML_USE_RPC` to the backend `target_compile_options` (mirror how `-DLM_GGML_USE_OPENCL` /
  `-DLM_GGML_USE_HEXAGON` are added at `android/src/main/CMakeLists.txt:126/130`).
- Ensure sockets link (RPC uses TCP): usually none extra on Android/bionic; add if the linker asks.

### D. Read the new params in the JSI bridge
- `cpp/jsi/JSIParams.cpp`: where other context params are read, add
  ```cpp
  if (params.hasProperty(runtime, "rpc_servers")) {
      auto arr = params.getProperty(runtime, "rpc_servers").asObject(runtime).asArray(runtime);
      std::string joined;
      for (size_t i = 0; i < arr.size(runtime); i++) {
          if (i) joined += ",";
          joined += arr.getValueAtIndex(runtime, i).asString(runtime).utf8(runtime);
      }
      cparams.rpc_servers = joined;               // -> common_params.rpc_servers
  }
  // tensor_split: llama.cpp already supports it; read a number[] into cparams.tensor_split[...]
  ```

### E. TS types (so the app can pass the params type-safely)
- `src/NativeRNLlama.ts` (`NativeContextParams`) and `src/types.ts` (`ContextParams`): add
  `rpc_servers?: string[]` and `tensor_split?: number[]`.
- `src/index.ts`: pass them through to the native `initContext` call (they ride the same params map).

### F. Worker method `startRpcServer(port)`
The app's `helixRpc.ts` calls `NativeHelixRpc.startRpcServer(port)`. Expose it via the module's JSI
surface (`cpp/jsi/RNLlamaJSI.cpp`, where `install` registers host functions): a host function that
spawns a thread running `lm_ggml_backend_rpc_start_server("0.0.0.0:" + port, ...)` (from the vendored
ggml-rpc), returning a Promise. Add matching TS in `src/index.ts` (`export function startRpcServer(port)`).
(iOS: same host function; the pod already builds ggml — add the same `LM_GGML_USE_RPC`.)

## Build
- In the ChatterUI fork: bump the dep to your forked `cui-llama.rn`, set
  `RNLLAMA_BUILD_FROM_SOURCE=ON` (build from source now that C++ changed), then the existing
  `ci/build-apk.yml` builds it — **but note it now compiles llama.cpp** (much longer; the NDK step is
  already there). Consider raising the workflow `timeout-minutes`.

## Wire to HELIX (already done, TS side)
- Worker phones: `startShardWorker(NativeHelixRpc, 50052)` → announce `host:50052` (HELIX `ANNOUNCE.rpc`).
- Driver phone: `startShardMain(helixClient, initLlama, model)` → fetches `rpcPlan()` → `initLlama`
  with `rpc_servers = plan.rpc_arg.split(',')`, `tensor_split = plan.tensor_split`.
- Or skip the app entirely for a first test: `helix/host/rpc_launch.py` prints the equivalent
  `rpc-server` / `llama-cli --rpc` commands for stock llama.cpp binaries.

## Test checklist (on-device)
1. Two phones, same cluster secret; both run a forked build. Worker calls `startRpcServer(50052)`.
2. HELIX coordinator places a 16B-class GGUF (attested memory) → `rpcPlan()`.
3. Driver `initLlama({ rpc_servers, tensor_split })` → `completion` runs distributed; confirm both
   devices hold their band (RAM / RPC logs). Compare tokens/s vs single-device (should enable a model
   that doesn't fit one phone).

## Caveats
- RPC hops are llama.cpp's **TCP, not HELIX frames** — run over a trusted LAN / WireGuard; admit only
  attested (③) + identified (④) nodes.
- API drift: vendor ggml-rpc from **exactly** b9309. A version mismatch is the most likely build break.
- No mid-run resume (RPC restarts on node loss) — HELIX re-plans (`helix/orchestrator.py` covers the
  fine-grained resume only for the full-HELIX-ring path, Option B).
