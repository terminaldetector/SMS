// HELIX Level 3 (Track B / Option A) driver for ChatterUI — shard ONE big GGUF across phones via
// llama.cpp's RPC, with HELIX as the control plane. HELIX decides the topology (rpcPlan()); this
// driver hands it to llama.cpp.
//
// STATUS: the control plane is done and proven (HelixClient.rpcPlan ->
// integration/chatterui_llamacpp/js/rpc_smoke.mjs, 7/7). The native side now exists too: a real
// fork of cui-llama.rn lives at forks/cui-llama.rn-rpc (see its RPC_FORK_NOTES.md) — it builds with
// LM_GGML_USE_RPC (verified green in CI: .github/workflows/build_cuillama_rpc_fork.yaml compiles
// the fork's own example app from source and links the RPC backend), exposes `rpc_servers` +
// `tensor_split` on `initLlama()`, and a worker-side `startRpcServer(endpoint, options?)`. This
// module is the TS seam that wires HELIX's plan to that real native surface (types below mirror
// forks/cui-llama.rn-rpc/src/types.ts + src/index.ts exactly — keep them in sync if the fork's API
// changes). Not yet built/run as part of a ChatterUI APK — see FORK_cui-llama-rpc.md for the
// remaining app-level wiring (package.json dependency, expo prebuild, from-source Gradle build).

import { HelixClient } from './helixClient'
import type { RpcClusterPlan } from './helixClient'

// The native surface the forked cui-llama.rn provides (forks/cui-llama.rn-rpc/src/index.ts).
export interface NativeHelixRpc {
    // Worker side: binds an lm_ggml_backend_rpc_start_server() on `endpoint` ("host:port", usually
    // "0.0.0.0:<port>"), serving this device's local backend devices to remote callers. Runs on a
    // detached background thread; the underlying accept() loop never returns, so there is NO stop
    // API — like running the standalone `rpc-server` binary, it serves for the process's lifetime.
    startRpcServer(endpoint: string, options?: { cacheDir?: string; nThreads?: number; devices?: string[] }): Promise<boolean>
}

// The forked initLlama accepts rpc_servers (array, not a joined string) + tensor_split (main/driver
// side). Matches NativeContextParams in forks/cui-llama.rn-rpc/src/types.ts.
export interface RpcInitLlama {
    (params: {
        model: string
        n_gpu_layers?: number
        rpc_servers?: string[] // ["host:port", ...] — the plan's workers, in ring order
        tensor_split?: number[] // llama.cpp --tensor-split ratios, [main-local, worker0, ...]
        [k: string]: unknown
    }): Promise<unknown>
}

export interface ShardWorkerHandle {
    plan: RpcClusterPlan
    role: 'main' | 'worker'
    stop(): Promise<void>
}

// Worker phone: advertise + run an rpc-server so the main node can offload layers to us. (HELIX
// discovery/attestation decides membership; we just serve.) There is no way to stop a started RPC
// server (see NativeHelixRpc above) — `stop()` is a no-op kept for API symmetry with the main role.
export async function startShardWorker(native: NativeHelixRpc, port = 50052): Promise<() => Promise<void>> {
    const ok = await native.startRpcServer(`0.0.0.0:${port}`)
    if (!ok) throw new Error(`startRpcServer failed to bind 0.0.0.0:${port}`)
    return async () => {
        /* no stop API upstream — the server runs for the process's lifetime */
    }
}

// Main/driver phone: get the HELIX plan and load the model distributed across the ring's workers.
export async function startShardMain(
    client: HelixClient,
    initLlama: RpcInitLlama,
    model: { model_id: string; model_path: string; n_layers: number; model_bytes: number }
): Promise<ShardWorkerHandle> {
    const plan = await client.rpcPlan(model)
    if (!plan.ok) throw new Error('rpc_plan failed (is the coordinator a ControlNode with rpc_addrs?)')
    // llama.cpp: --rpc = the worker rpc-servers; --tensor-split spans [main-local, worker0, ...].
    // plan.rpc_arg is "host:port,host:port" (matches --rpc's CLI form) — split into the array the
    // fork's initLlama expects.
    const rpcServers = plan.rpc_arg ? plan.rpc_arg.split(',') : []
    await initLlama({
        model: model.model_path,
        n_gpu_layers: 99,
        rpc_servers: rpcServers,
        tensor_split: plan.tensor_split,
    })
    return {
        plan,
        role: 'main',
        async stop() {
            /* unload handled by ChatterUI's model store */
        },
    }
}
