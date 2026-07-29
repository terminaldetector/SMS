// Turning a HELIX plan into the arguments llama.cpp actually obeys.
//
// This is the file the whole "only one phone ever did any work" story lives in. The plan is right —
// bands, ratios and the ring all check out — and it was being handed to llama.cpp in a form that
// means something else entirely:
//
//   • --tensor-split is indexed by llama.cpp's DEVICE list, and the driving phone's own CPU is not
//     a device. The device list is [rpc_servers…, local GPUs…]; nothing in it stands for "here".
//     So a plan's [main, worker0] ratios landed as [worker0, worker1] — the main node's share was
//     silently given to the first worker and the last worker's share fell off the end. With one
//     worker the whole ring collapsed to one bucket: that worker got every offloaded layer.
//
//   • n_gpu_layers is not "how many layers the model has", it is how many trailing layers are
//     ALLOWED TO LEAVE this phone. Passing the model's full block count means the host keeps
//     nothing and the workers are asked for the entire model — which is how a phone offering
//     1.3 GB was handed a 2.5 GB model, and why a shard that "loaded" was either the workers
//     thrashing or, when the rpc-server could not be reached at all, an ordinary local load
//     wearing a shard's log lines.
//
// llama.cpp splits by layer index: the FIRST i_gpu_start layers stay on the local CPU and the tail
// is divided across the devices in device order. That is exactly the shape of a HELIX plan — the
// main node holds the first band, the workers the bands after it, in ring order — so the mapping is
// direct once written down:
//
//   n_gpu_layers = n_layers + 1 - <layers in the main band>   (the +1 is llama.cpp's output layer,
//                                                              which always travels with the tail)
//   tensor_split = one entry per REMOTE DEVICE, in the order the rpc-servers were registered,
//                  normalised over the workers alone
//   devices      = those same remote devices by name, so nothing else can quietly join the split
//
// Kept apart from helixPlacement.ts on purpose: that file is the cross-language port of the HELIX
// reference planner (helix/rpc_cluster.py) and is proved against it, so it must keep producing the
// protocol's own view of a plan. This is the llama.cpp-facing translation of that view, and it is
// the part that has to be exactly right about someone else's semantics.

import type { RpcClusterPlan } from './helixPlacement'

/** What one endpoint's rpc-server actually exposed, from addRpcServers() before the load. */
export interface RpcEndpointDevices {
    endpoint: string
    /** ggml device names ("RPC0", "RPC1", …) — one per backend that worker is serving. */
    devices: string[]
}

export interface ShardWorkerArgs {
    node: string
    endpoint: string
    /** Layers from the plan's band. */
    layers: number
    /** Share of the offloaded tail this worker holds. */
    ratio: number
    devices: string[]
}

export interface ShardLlamaArgs {
    /** --rpc: the worker rpc-servers, in ring order. */
    rpc_servers: string[]
    /** Device names for the split, remote only, aligned 1:1 with tensor_split. */
    devices: string[]
    /** --tensor-split, one entry per remote device, summing to 1. */
    tensor_split: number[]
    /** -ngl: how many trailing layers (plus the output layer) may leave this phone. */
    n_gpu_layers: number
    /** Layers the driving phone keeps on its own CPU. */
    host_layers: number
    /** Layers held by the workers. */
    remote_layers: number
    workers: ShardWorkerArgs[]
}

/**
 * Translate a HELIX plan into llama.cpp's arguments, or throw saying which assumption broke.
 *
 * `registered` comes from the pre-load registration of the rpc-servers (addRpcServers), so the
 * number of devices each worker exposes is known rather than assumed: a phone serving both its CPU
 * and its GPU shows up as two devices behind one address, and tensor_split is indexed by device,
 * not by phone. Assuming one device per address is what would put a two-worker mesh's ratios one
 * slot out of step — the same class of bug as the one above, one layer down.
 */
export function shardLlamaArgs(
    plan: RpcClusterPlan,
    registered: RpcEndpointDevices[]
): ShardLlamaArgs {
    const main = plan.endpoints.find((e) => e.role === 'main')
    if (!main) throw new Error('the plan has no main node — nothing would drive the shard')
    if (main.band[0] !== 0)
        throw new Error(
            `the main node holds layers ${main.band[0]}–${main.band[1] - 1}, but llama.cpp can only ` +
                'offload the END of a model — the driving phone has to hold the first band'
        )

    const hostLayers = main.band[1] - main.band[0]
    const workers = plan.endpoints.filter((e) => e.role === 'worker')
    if (!workers.length)
        throw new Error('the plan has no workers — this is a local load, not a shard')

    // Ring order is pipeline order, and llama.cpp fills devices in that same ascending-layer order.
    // A worker whose band does not continue where the previous one stopped would be handed layers
    // it was never planned for, with nothing at load time to say so.
    let expected = hostLayers
    for (const w of workers) {
        if (w.band[0] !== expected)
            throw new Error(
                `worker ${w.node} holds layers ${w.band[0]}–${w.band[1] - 1}, but the split reaches it ` +
                    `at layer ${expected} — llama.cpp divides the tail in device order, so the bands ` +
                    'have to be contiguous in ring order'
            )
        expected = w.band[1]
    }
    if (expected !== plan.n_layers)
        throw new Error(
            `the bands stop at layer ${expected} of ${plan.n_layers} — some layers belong to nobody`
        )

    const remoteLayers = plan.n_layers - hostLayers
    if (remoteLayers <= 0)
        throw new Error('every layer was planned onto this phone — there is nothing left to shard')

    const byEndpoint = new Map(registered.map((r) => [r.endpoint, r.devices]))
    const devices: string[] = []
    const tensorSplit: number[] = []
    const detail: ShardWorkerArgs[] = []

    for (const w of workers) {
        const found = byEndpoint.get(w.addr)
        if (!found || found.length === 0)
            throw new Error(
                `${w.node} at ${w.addr} offered no device to hold layers — it is not reachable, or it ` +
                    'stopped sharing its RAM (tap "Share this phone\'s RAM" on it again)'
            )
        const layers = w.band[1] - w.band[0]
        const ratio = layers / remoteLayers
        // One address can serve several backends (a phone's CPU *and* its GPU). They are the same
        // phone's memory, so its band is divided evenly between them rather than being counted twice.
        for (const name of found) {
            devices.push(name)
            tensorSplit.push(ratio / found.length)
        }
        detail.push({ node: w.node, endpoint: w.addr, layers, ratio, devices: found })
    }

    return {
        rpc_servers: workers.map((w) => w.addr),
        devices,
        tensor_split: tensorSplit,
        // The output layer rides along with the last remote band whenever anything is offloaded at
        // all — llama.cpp counts it as one more layer past the blocks, and there is no way to keep
        // it here while still offloading. Hence n_layers + 1 rather than n_layers.
        n_gpu_layers: plan.n_layers + 1 - hostLayers,
        host_layers: hostLayers,
        remote_layers: remoteLayers,
        workers: detail,
    }
}

/**
 * Which of a loaded context's devices are remote, i.e. whether the split was actually taken.
 *
 * initLlama reports the devices the model ended up using. An empty RPC list here is the failure
 * that has cost the most time in this project: the rpc-server could not be reached, llama.cpp fell
 * back to loading everything locally, and every line in the log still said "sharded".
 */
export function rpcDevicesUsed(deviceNames: string[] | undefined): string[] {
    return (deviceNames ?? []).filter((d) => /^RPC\d*/i.test(d))
}
