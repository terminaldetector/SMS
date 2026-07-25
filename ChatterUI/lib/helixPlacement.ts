// HELIX layer placement + llama.cpp RPC cluster planning, in-app.
//
// A TS port of helix/placement.py (allocate_layers / plan_placement) and helix/rpc_cluster.py
// (plan_rpc_cluster), so a HOST PHONE can plan a sharded run by itself — the Python planner needs a
// PC coordinator, which the whole no-PC path exists to avoid. Same algorithm, same outputs: proven
// against the Python reference by integration/chatterui_llamacpp/js/shard_plan_smoke.mjs, which
// imports this very file (no twin to drift out of sync).
//
// What it produces is exactly what llama.cpp's distributed mode needs:
//   rpc_arg      — the worker rpc-servers for --rpc ("host:port,host:port")
//   tensor_split — ratios across [main-local, worker0, worker1, …]
//   main         — the driver node (runs llama locally, offloads the rest)

export interface Capacity {
    mem_bytes: number
    cpu?: number
    npu?: boolean
    battery?: number
}

export interface Band {
    start: number
    end: number // half-open [start, end)
}

export interface RpcEndpoint {
    node: string
    addr: string
    band: [number, number]
    role: 'main' | 'worker'
}

export interface RpcClusterPlan {
    model_id: string
    n_layers: number
    ring: string[]
    endpoints: RpcEndpoint[]
    main: string
    rpc_arg: string
    tensor_split: number[]
}

export interface Placement {
    ring: string[]
    bands: Record<string, Band>
}

const bandSize = (b: Band) => b.end - b.start

// Split `totalLayers` across nodes proportionally to `weights` (largest-remainder), guaranteeing at
// least one layer each. Mirrors helix/placement.py allocate_layers.
export function allocateLayers(totalLayers: number, weights: number[]): number[] {
    const n = weights.length
    if (n === 0) throw new Error('no nodes to allocate to')
    if (totalLayers < n)
        throw new Error(`cannot give >=1 layer to each of ${n} nodes with ${totalLayers} layers`)

    const raw = weights.map((w) => w * totalLayers)
    const result = raw.map((r) => Math.trunc(r))
    // Largest remainder first. Array.prototype.sort is stable, matching Python's sorted().
    const order = raw.map((_, i) => i).sort((a, b) => raw[b] - result[b] - (raw[a] - result[a]))
    const short = totalLayers - result.reduce((s, x) => s + x, 0)
    for (let i = 0; i < short; i++) result[order[i]] += 1

    // Nobody ends up with zero layers: take one from the current largest (first, as Python's max).
    for (let i = 0; i < n; i++) {
        if (result[i] !== 0) continue
        let j = 0
        for (let k = 1; k < n; k++) if (result[k] > result[j]) j = k
        result[j] -= 1
        result[i] = 1
    }
    return result
}

// Ring + per-node layer bands, memory-weighted, with the per-node RAM-fit check.
// Mirrors helix/placement.py plan_placement.
export function planPlacement(
    members: Record<string, Capacity>,
    nLayers: number,
    modelBytes: number,
    order?: string[]
): Placement {
    const ids = order ? [...order] : Object.keys(members).sort()
    if (ids.length === 0) throw new Error('no members')
    if (ids.length > nLayers)
        throw new Error(`more nodes (${ids.length}) than layers (${nLayers})`)

    const have = ids.reduce((s, i) => s + members[i].mem_bytes, 0)
    if (have < modelBytes)
        throw new Error(
            `combined memory too small: need ${(modelBytes / 2 ** 30).toFixed(2)} GB, ` +
                `have ${(have / 2 ** 30).toFixed(2)} GB`
        )

    const alloc = allocateLayers(nLayers, ids.map((i) => members[i].mem_bytes / have))
    const bands: Record<string, Band> = {}
    let cursor = 0
    ids.forEach((nid, k) => {
        bands[nid] = { start: cursor, end: cursor + alloc[k] }
        cursor += alloc[k]
    })

    // A node's own band must fit its own memory, not just the ring total (model treated as uniform
    // bytes/layer) — otherwise the ring "fits" on paper while one phone cannot hold its share.
    const perLayer = modelBytes / nLayers
    for (const nid of ids) {
        const need = perLayer * bandSize(bands[nid])
        if (members[nid].mem_bytes < need)
            throw new Error(
                `node ${nid} assigned ${bandSize(bands[nid])} layers ` +
                    `(~${(need / 2 ** 30).toFixed(2)} GB) but has ` +
                    `${(members[nid].mem_bytes / 2 ** 30).toFixed(2)} GB`
            )
    }
    return { ring: ids, bands }
}

// Plan a llama.cpp RPC cluster for one model. `addrs` is node_id -> "host:port" of each node's
// rpc-server. Mirrors helix/rpc_cluster.py plan_rpc_cluster.
export function planRpcCluster(
    members: Record<string, Capacity>,
    addrs: Record<string, string>,
    modelId: string,
    nLayers: number,
    modelBytes: number,
    order?: string[]
): RpcClusterPlan {
    const placement = planPlacement(members, nLayers, modelBytes, order)
    const ring = placement.ring

    const missing = ring.filter((nid) => !addrs[nid])
    if (missing.length)
        throw new Error(
            `no rpc address for ring node(s): ${missing.join(', ')} ` +
                '(each participant must announce its rpc address)'
        )

    const endpoints: RpcEndpoint[] = ring.map((nid) => ({
        node: nid,
        addr: addrs[nid],
        band: [placement.bands[nid].start, placement.bands[nid].end],
        role: nid === ring[0] ? 'main' : 'worker',
    }))

    return {
        model_id: modelId,
        n_layers: nLayers,
        ring: [...ring],
        endpoints,
        main: ring[0],
        // llama.cpp: the main process drives locally and offloads to the worker rpc-servers, so
        // --rpc lists the workers only, while --tensor-split spans [main, worker0, worker1, …].
        rpc_arg: ring.slice(1).map((nid) => addrs[nid]).join(','),
        tensor_split: ring.map((nid) => bandSize(placement.bands[nid]) / nLayers),
    }
}
