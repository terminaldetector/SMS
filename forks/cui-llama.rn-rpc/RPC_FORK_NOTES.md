# cui-llama.rn RPC fork — status

Base: `cui-llama.rn` v1.11.14, pinned upstream llama.cpp commit `6d57c26`
(`cpp/common/build-info.cpp`, `LLAMA_BUILD_NUMBER = 9309`).

Goal: re-enable `ggml`'s RPC backend (`GGML_USE_RPC`, upstream's `--rpc host:port` /
`ggml_backend_rpc_add_server`) inside this fork, which vendors llama.cpp with every `ggml_`/`GGML_`
symbol renamed to `lm_ggml_`/`LM_GGML_` to avoid clashing with other copies of ggml in the same app
(see `scripts/bootstrap.sh`'s rename step). The RPC backend itself was never vendored, so
`ggml-backend-reg.cpp`'s `#ifdef LM_GGML_USE_RPC` block always compiled out.

## What's done (steps A-E)

- **A — vendor + rename**: `cpp/ggml-rpc.cpp`, `cpp/ggml-rpc.h`, `cpp/transport.cpp`,
  `cpp/transport.h` pulled from upstream llama.cpp at commit `6d57c26` (matching this fork's pinned
  build), then renamed with the same `sed 's/ggml_/lm_ggml_/g'` / `s/GGML_/LM_GGML_/g` pass
  `scripts/bootstrap.sh` uses for every other vendored file. Verified zero residual unprefixed
  `ggml_`/`GGML_` occurrences in all four files.
- **B — device plumbing**: not needed. `cpp/llama.cpp`'s default device-selection code already
  special-cases any registered backend whose `reg_name == "RPC"` and inserts it at the front of the
  device list (`model->devices`) — that logic was already present upstream-side in this fork, just
  dormant because no RPC devices were ever registered.
- **C — CMake wiring**: `android/src/main/rnllama/CMakeLists.txt` — added `ggml-rpc.cpp` and
  `transport.cpp` to `RNLLAMA_SOURCE_FILES`, and `-DLM_GGML_USE_RPC` to the per-arch
  `target_compile_options` (next to the existing `-DLM_GGML_USE_CPU`). No changes needed to
  `android/src/main/CMakeLists.txt` — nothing outside `ggml-backend-reg.cpp` reads that define.
- **D — JS → native param**: `cpp/jsi/RNLlamaJSI.cpp`'s `llamaInitContext` now reads a new
  `rpc_servers: string[]` field (list of `"host:port"` endpoints) off the init params object. For
  each new endpoint it calls `lm_ggml_backend_rpc_add_server(endpoint)` and then
  **`lm_ggml_backend_register(reg)`** — this second call is easy to miss (the upstream CLI does it
  in `common/arg.cpp`'s `add_rpc_devices()`) but is required: `add_server()` only builds the reg
  object, it does not add it to the global backend registry on its own. Endpoints are deduped in a
  static `std::set` so repeated context inits with the same servers don't re-register them.
  This all happens before `configureBackendDevices()` runs, so the existing "RPC"-reg-name
  filtering in `getFilteredDefaultDevices()` / device enumeration picks the new devices up with no
  further changes.
- **E — TS types**: `src/types.ts` — added `rpc_servers?: Array<string>` to `NativeContextParams`,
  next to the existing `devices`/`no_gpu_devices` fields.

## What's left (step F, deferred)

- A JSI method to **start** an RPC server on-device (`ggml_backend_rpc_start_server`) so a phone
  can act as a worker for another device's inference — this is new host-function surface, not a
  small mechanical patch, and deserves its own pass.
- Wiring this fork into ChatterUI / the HELIX mesh integration described in the `archi` bundle
  (`ROADMAP_mobile_ai_mesh.md` Level 3 / Option A) — that's an app-level `package.json` dependency
  swap plus a full Expo/RN build, out of scope for validating the native module in isolation.
- iOS: the JSI change is platform-agnostic, but `LM_GGML_USE_RPC` is only wired into the Android
  CMake files above; `ios/` was not touched.

## How this gets verified

This session has no NDK/Android SDK, so none of the above has been compiled locally. Instead,
`.github/workflows/build_cuillama_rpc_fork.yaml` builds the library's own `example` RN app (linked
back to this fork via `example/react-native.config.js`) with
`RNLLAMA_BUILD_FROM_SOURCE`/`rnllamaBuildFromSource=true`, targeting `arm64-v8a` only to keep CI
time down. A green run there is the real "steps A-C checkpoint" the original plan called for; a
link failure almost always means a missed `ggml_` → `lm_ggml_` rename, and a missing-symbol error
at the `LM_GGML_USE_RPC` define site means the define isn't reaching that compilation unit.

Once that's green, sending `rpc_servers: ["<ip>:<port>"]` in `initLlama()`'s params (with an
upstream `rpc-server` binary running at that address) should make the remote device show up as an
`"RPC"` device and receive offloaded layers automatically.
