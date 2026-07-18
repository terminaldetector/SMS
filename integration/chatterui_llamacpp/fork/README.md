# cui-llama.rn RPC fork — apply kit

Applies the HELIX Level-3 RPC changes to **cui-llama.rn 1.11.14** (llama.cpp **b9309**). Enables
`rpc_servers` + `tensor_split` in `initLlama` (driver) and a `startRpcServer(port)` worker method,
so ChatterUI can shard one big GGUF across phones (`app_mod/lib/helixRpc.ts` consumes it).

> Grounded in the real package source, but **not built/tested here** (no NDK). Verify on-device.
> Full rationale: `../FORK_cui-llama-rpc.md`.

## What's here
- `patches/` — real unified diffs for the **verifiable param-plumbing** (5 files): the
  `rpc_servers` field in `common_params`, the JSI param reads, the TS types, and the CMake
  build flag + `ggml-rpc.cpp` source entry.
- `scripts/vendor-ggml-rpc.sh` — fetch matching `ggml-rpc.{cpp,h}` (b9309) + apply the fork's
  `lm_` symbol rename.

## Apply order (in your cui-llama.rn fork checkout)

```bash
FORK=/path/to/cui-llama.rn
# 1) param plumbing (real patches)
for p in patches/*.patch; do git -C "$FORK" apply "/path/to/SMS/integration/chatterui_llamacpp/fork/$p"; done
# 2) vendor the RPC backend source (matches b9309, renames symbols)
bash scripts/vendor-ggml-rpc.sh "$FORK"
```

## Two manual steps (upstream-dependent — verify symbol names against b9309)

These touch code that varies by llama.cpp version, so they are given as **concrete snippets to
place by hand**, not blind patches.

**M1 — wire `rpc_servers` → offload devices.** cui-llama.rn stripped the RPC device setup from
`common_init_from_params`, so setting `params.rpc_servers` alone does nothing. Add this where
context devices are assembled (in `cpp/rn-llama.cpp` `loadModel`, or right after the JSI block in
`cpp/jsi/JSIParams.cpp`), mirroring upstream b9309:

```cpp
if (!cparams.rpc_servers.empty()) {
    lm_ggml_backend_reg_t rpc_reg = lm_ggml_backend_reg_by_name("RPC");
    if (rpc_reg) {
        typedef lm_ggml_backend_dev_t (*add_dev_t)(const char *);
        auto add_dev = (add_dev_t) lm_ggml_backend_reg_get_proc_address(rpc_reg, "lm_ggml_backend_rpc_add_device");
        if (add_dev) {
            std::stringstream ss(cparams.rpc_servers);
            std::string ep;
            while (std::getline(ss, ep, ',')) {
                lm_ggml_backend_dev_t dev = add_dev(ep.c_str());
                if (dev) cparams.devices.push_back(dev);
            }
        }
    }
}
```
(Confirm the proc-address name `lm_ggml_backend_rpc_add_device` against the vendored `ggml-rpc.cpp`.)

**M2 — `startRpcServer(port)` worker method.** Register a JSI host function in
`cpp/jsi/RNLlamaJSI.cpp` (where `install` registers functions) that runs, on a background thread,
the vendored server entry (upstream `ggml_backend_rpc_start_server`, here `lm_`-prefixed), bound to
`0.0.0.0:<port>`; return a Promise. Add the TS declaration in `src/index.ts`:
```ts
export function startRpcServer(port: number): Promise<void>
```
This is what `helixRpc.ts` `NativeHelixRpc.startRpcServer` calls on worker phones.

## Build
- In the ChatterUI fork, point the `cui-llama.rn` dep at your fork and set
  `RNLLAMA_BUILD_FROM_SOURCE=ON` (C++ changed → can't use the prebuilt `.so`). The existing
  `ci/build-apk.yml` builds it, but now **compiles llama.cpp** — raise `timeout-minutes`.
- First device test: `../FORK_cui-llama-rpc.md` → "Test checklist".

## Prefer to skip the fork for a first test?
`helix/host/rpc_launch.py` turns the same HELIX plan into stock-llama.cpp `rpc-server` / `--rpc`
commands — real sharding on 2 devices without touching cui-llama.rn.
