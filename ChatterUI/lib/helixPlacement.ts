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

/**
 * Split `totalLayers` by SPEED, capped by what each node can hold.
 *
 * Memory-proportional placement answers the wrong question. Layers run in sequence, one node after
 * another, so a token costs `sum(layers_i / speed_i)` — and the way to make that small is to put
 * layers where they are computed fastest, not where there is room. Weighing by memory alone hands
 * the biggest band to whichever phone has the most free RAM, and if that phone is also the slowest
 * it becomes the bottleneck for every token the mesh ever produces. OVERVIEW.md has admitted this
 * gap since the planner was written.
 *
 * Memory does not stop mattering — it stops being the objective and becomes the constraint. Hence
 * water-filling: hand out layers in proportion to speed, pin any node that hits its memory cap,
 * and share what it could not take among the nodes that still have room. Repeat until nothing more
 * pins, because pinning one node raises everyone else's share and can push the next one over.
 *
 * A node over its cap is now redistributed rather than thrown at, which also removes a real
 * failure: "node X assigned N layers but has M GB" used to abort a plan that was merely badly
 * balanced.
 */
export function allocateLayersCapped(
    totalLayers: number,
    weights: number[],
    caps: number[]
): number[] {
    const n = weights.length
    if (n === 0) throw new Error('no nodes to allocate to')
    if (caps.length !== n) throw new Error('a cap is needed for every node')
    if (totalLayers < n)
        throw new Error(`cannot give >=1 layer to each of ${n} nodes with ${totalLayers} layers`)

    const capacity = caps.reduce((s, c) => s + Math.max(0, Math.floor(c)), 0)
    if (capacity < totalLayers)
        throw new Error(
            `phones can hold ${capacity} of the model's ${totalLayers} layers between them`
        )

    const cap = caps.map((c) => Math.max(0, Math.floor(c)))
    const share = new Array<number>(n).fill(0)
    const pinned = new Array<boolean>(n).fill(false)
    let remaining = totalLayers

    // At most n rounds: each round pins at least one node, or it is the last.
    for (let round = 0; round <= n && remaining > 0; round++) {
        const freeWeight = weights.reduce((s, w, i) => s + (pinned[i] ? 0 : Math.max(0, w)), 0)
        let pinnedThisRound = false

        // Every remaining node has zero speed (nothing was measured, or all measured zero). Falling
        // back to an even split is better than dividing by zero and better than refusing: an
        // unmeasured mesh should still run, just without the speed advantage.
        const freeCount = pinned.filter((p) => !p).length
        for (let i = 0; i < n; i++) {
            if (pinned[i]) continue
            const w = freeWeight > 0 ? Math.max(0, weights[i]) / freeWeight : 1 / freeCount
            const want = share[i] + remaining * w
            if (want >= cap[i]) {
                share[i] = cap[i]
                pinned[i] = true
                pinnedThisRound = true
            } else {
                share[i] = want
            }
        }
        remaining = totalLayers - share.reduce((s, x) => s + x, 0)
        if (!pinnedThisRound) break
    }

    // Integer rounding, largest remainder first, never past a cap.
    const result = share.map((s, i) => Math.min(cap[i], Math.floor(s)))

    let short = totalLayers - result.reduce((s, x) => s + x, 0)
    const byRemainder = share
        .map((_, i) => i)
        .sort((a, b) => share[b] - Math.floor(share[b]) - (share[a] - Math.floor(share[a])))
    while (short > 0) {
        const before = short
        for (const i of byRemainder) {
            if (short === 0) break
            if (result[i] < cap[i]) {
                result[i] += 1
                short -= 1
            }
        }
        // Nothing had room and layers are still unplaced — impossible given the capacity check
        // above, but a silent infinite loop would be a far worse way to find that out.
        if (short === before) throw new Error('could not place every layer within the memory caps')
    }

    // The one-layer-each contract, kept: a node in the ring that runs nothing is a hop that costs
    // a network round trip and contributes no compute.
    for (let i = 0; i < n; i++) {
        if (result[i] > 0) continue
        if (cap[i] < 1) throw new Error(`node ${i} cannot hold even one layer`)
        let j = -1
        for (let k = 0; k < n; k++) if (result[k] > 1 && (j < 0 || result[k] > result[j])) j = k
        if (j < 0) throw new Error('no node has a spare layer to give')
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

    // Speed-weighted placement is OPT-IN, on `cpu` being present and positive. Two reasons, and
    // both matter: this file is proved line-for-line against the Python planner
    // (shard_plan_smoke.mjs), which has no notion of measured speed, so changing the default would
    // turn that proof into a comparison of two different algorithms — and a mesh that has not
    // measured anything should keep behaving exactly as it does today rather than silently
    // switching to a rule based on numbers nobody supplied.
    const perLayer = modelBytes / nLayers
    const speeds = ids.map((i) => members[i].cpu ?? 0)
    const alloc = speeds.some((s) => s > 0)
        ? allocateLayersCapped(
              nLayers,
              speeds,
              // How many layers each phone could hold at all. perLayer treats the model as uniform
              // bytes per layer, which it is not exactly — but it is the same approximation the
              // memory-fit check below has always used, and being wrong the same way in both
              // places is better than two disagreeing estimates.
              ids.map((i) => (perLayer > 0 ? members[i].mem_bytes / perLayer : nLayers))
          )
        : allocateLayers(nLayers, ids.map((i) => members[i].mem_bytes / have))
    const bands: Record<string, Band> = {}
    let cursor = 0
    ids.forEach((nid, k) => {
        bands[nid] = { start: cursor, end: cursor + alloc[k] }
        cursor += alloc[k]
    })

    // A node's own band must fit its own memory, not just the ring total (model treated as uniform
    // bytes/layer) — otherwise the ring "fits" on paper while one phone cannot hold its share.
    // The capped allocator above already respects this, so on the speed-weighted path this is a
    // second opinion that should never fire; on the memory-weighted path it is the only check.
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
