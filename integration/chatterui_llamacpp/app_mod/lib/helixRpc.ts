// HELIX Level 3 (Track B / Option A) driver for ChatterUI — shard ONE big GGUF across phones via
// llama.cpp's RPC, with HELIX as the control plane. HELIX decides the topology (rpcPlan()); this
// driver hands it to llama.cpp.
//
// STATUS: the control plane is done and proven (HelixClient.rpcPlan ->
// integration/chatterui_llamacpp/js/rpc_smoke.mjs, 7/7). The tensor split needs a NATIVE fork of
// cui-llama.rn (it currently exposes NO RPC): build llama.cpp with -DGGML_RPC=ON, add an
// `rpc_servers` context param, and expose `startRpcServer(port)`. This module is the TS seam that
// consumes that native surface — see LEVEL3_sharding.md for the exact native changes. Until the
// fork exists, L3 does not run on-device (L1 + L2 do).

import { HelixClient, RpcClusterPlan } from './helixClient'

// The native surface a forked cui-llama.rn must provide (does not exist upstream yet).
export interface NativeHelixRpc {
    // Worker side: start a ggml rpc-server bound to 0.0.0.0:port (from GGML_RPC build).
    startRpcServer(port: number): Promise<void>
    stopRpcServer(): Promise<void>
}

// The forked initLlama must accept rpc_servers + tensor_split (main/driver side).
export interface RpcInitLlama {
    (params: {
        model: string
        n_gpu_layers?: number
        rpc_servers?: string[] // llama.cpp --rpc list
        tensor_split?: number[] // llama.cpp --tensor-split ratios
        [k: string]: unknown
    }): Promise<unknown>
}

export interface ShardWorkerHandle {
    plan: RpcClusterPlan
    role: 'main' | 'worker'
    stop(): Promise<void>
}

// Worker phone: advertise + run a ggml rpc-server so the main node can offload layers to us.
// (HELIX discovery/attestation decides membership; we just serve.)
export async function startShardWorker(native: NativeHelixRpc, port = 50052): Promise<() => Promise<void>> {
    await native.startRpcServer(port)
    return () => native.stopRpcServer()
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
    const workers = plan.rpc_arg ? plan.rpc_arg.split(',') : []
    await initLlama({
        model: model.model_path,
        n_gpu_layers: 99,
        rpc_servers: workers,
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
