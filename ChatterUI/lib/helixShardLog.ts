// What actually happened when a model was split, written out in full.
//
// Sharding fails quietly. Every wrong outcome so far has looked the same from the outside — the
// model loads, the chat answers, and the only symptom is that it is slow, or that a phone dies
// mid-answer. The causes were not subtle once seen (`GPU Layers: 0`, so nothing ever left the
// phone; memory divided by TOTAL rather than free, so a device was handed a share it could not
// hold), but nothing on screen or in the log distinguished them from a mesh working as intended.
//
// So this prints the numbers the plan was made from, the numbers it produced, and the arithmetic
// between them — and then checks that arithmetic against itself. A plan whose bands do not cover
// every layer, or whose ratios do not sum to one, is broken in a way that will not announce itself
// later; it is worth catching in the line above the load rather than in an hour of guessing.
//
// Pure string formatting on purpose: no React Native imports, so CI can run it against real plans.

import type { RpcClusterPlan } from './helixPlacement'

export interface ShardLogNode {
    id: string
    /** Free memory this node offered, in bytes — what the split was actually weighted by. */
    mem: number
    rpc: string
}

export interface ShardLogModel {
    name: string
    /** GGUF size on disk, in bytes. 0 when the record predates size detection. */
    bytes: number
    layers: number
}

const gb = (bytes: number) => (bytes / 1024 ** 3).toFixed(2) + ' GB'

const pct = (part: number, whole: number) => (whole > 0 ? ((part / whole) * 100).toFixed(1) : '0.0') + '%'

/**
 * Sanity checks over a finished plan.
 *
 * These are not defensive noise: each one corresponds to a way the split has actually gone wrong
 * or could. They are returned rather than thrown — a plan that is merely suspicious should still
 * be attempted, and the log is what makes the connection when the attempt then behaves oddly.
 */
export function checkShardPlan(plan: RpcClusterPlan, nodes: ShardLogNode[]): string[] {
    const problems: string[] = []

    const covered = plan.endpoints.reduce((n, e) => n + (e.band[1] - e.band[0]), 0)
    if (covered !== plan.n_layers)
        problems.push(
            `bands cover ${covered} layers but the model has ${plan.n_layers} — some layers are unplaced or double-placed`
        )

    // Sorted copy: the ring order is the pipeline order, and a gap between one band's end and the
    // next one's start is a layer nobody runs.
    const sorted = [...plan.endpoints].sort((a, b) => a.band[0] - b.band[0])
    for (let i = 1; i < sorted.length; i++) {
        if (sorted[i].band[0] !== sorted[i - 1].band[1])
            problems.push(
                `gap or overlap between ${sorted[i - 1].node} (ends ${sorted[i - 1].band[1]}) and ${sorted[i].node} (starts ${sorted[i].band[0]})`
            )
    }

    const empty = plan.endpoints.filter((e) => e.band[1] <= e.band[0])
    if (empty.length) problems.push(`${empty.map((e) => e.node).join(', ')} got no layers at all`)

    const splitSum = plan.tensor_split.reduce((a, b) => a + b, 0)
    if (Math.abs(splitSum - 1) > 0.01)
        problems.push(`tensor_split sums to ${splitSum.toFixed(4)}, not 1 — llama.cpp will not divide as planned`)

    if (plan.tensor_split.length !== plan.ring.length)
        problems.push(
            `tensor_split has ${plan.tensor_split.length} entries for ${plan.ring.length} nodes — the ratios do not line up with the ring`
        )

    // The main node is local; only the workers appear in --rpc, so the count is one short by design.
    const rpcCount = plan.rpc_arg ? plan.rpc_arg.split(',').filter(Boolean).length : 0
    if (rpcCount !== plan.ring.length - 1)
        problems.push(
            `--rpc lists ${rpcCount} servers for ${plan.ring.length - 1} workers — a phone in the ring is not being asked for its layers`
        )

    const addrs = nodes.map((n) => n.rpc).filter(Boolean)
    if (new Set(addrs).size !== addrs.length)
        problems.push('two nodes announced the same rpc address — one will get every layer meant for both')

    const noMem = nodes.filter((n) => !(n.mem > 0))
    if (noMem.length)
        problems.push(`${noMem.map((n) => n.id).join(', ')} reported no free memory, so the split could not weigh them`)

    return problems
}

/**
 * The full report, for the log, in the order a person reads it: what was split, across what, into
 * what, and whether that adds up.
 */
export function formatShardPlan(
    plan: RpcClusterPlan,
    model: ShardLogModel,
    nodes: ShardLogNode[]
): string {
    const totalMem = nodes.reduce((n, x) => n + x.mem, 0)
    const byId = new Map(nodes.map((n) => [n.id, n]))
    const perLayer = model.layers > 0 ? model.bytes / model.layers : 0

    const lines: string[] = []
    lines.push('------ SHARD PLAN -----')
    lines.push(`Model: ${model.name}`)
    lines.push(
        `Size: ${model.bytes > 0 ? gb(model.bytes) : 'unknown'} · ${model.layers} layers` +
            (perLayer > 0 ? ` · ~${gb(perLayer)} per layer` : '')
    )
    lines.push(`Ring: ${plan.ring.join(' → ')} (main: ${plan.main})`)
    lines.push(`Free memory offered: ${gb(totalMem)} across ${nodes.length} phones`)
    if (model.bytes > 0)
        lines.push(
            totalMem >= model.bytes
                ? `Headroom: the mesh offers ${pct(totalMem, model.bytes)} of the model's size`
                : `TIGHT: the mesh offers ${pct(totalMem, model.bytes)} of the model's size — expect a load failure or a kill`
        )
    lines.push('')

    // Ring order, not plan order: this is the sequence a token actually travels.
    for (const id of plan.ring) {
        const e = plan.endpoints.find((x) => x.node === id)
        const n = byId.get(id)
        if (!e) {
            lines.push(`${id}: in the ring but absent from the plan`)
            continue
        }
        const layers = e.band[1] - e.band[0]
        const idx = plan.ring.indexOf(id)
        const ratio = plan.tensor_split[idx]
        const needs = perLayer * layers
        lines.push(
            `${e.role === 'main' ? '▸' : ' '} ${id} (${e.role})` +
                `\n    layers ${e.band[0]}–${e.band[1] - 1} (${layers}, ${pct(layers, plan.n_layers)})` +
                `\n    ratio ${ratio === undefined ? '—' : ratio.toFixed(4)}` +
                `\n    offered ${n ? gb(n.mem) : 'unknown'}` +
                (perLayer > 0 && n
                    ? `, needs ~${gb(needs)}${needs > n.mem ? '  ← MORE THAN IT OFFERED' : ''}`
                    : '') +
                `\n    at ${e.addr || (e.role === 'main' ? 'local' : 'no address')}`
        )
    }

    lines.push('')
    lines.push(`--rpc: ${plan.rpc_arg || '(none — nothing will leave this phone)'}`)
    lines.push(`--tensor-split: ${plan.tensor_split.map((x) => x.toFixed(4)).join(', ')}`)

    const problems = checkShardPlan(plan, nodes)
    if (problems.length) {
        lines.push('')
        lines.push('PROBLEMS:')
        for (const p of problems) lines.push(`  ! ${p}`)
    } else {
        lines.push('Checks: bands cover every layer, ratios sum to 1, every worker is addressed.')
    }
    lines.push('------ END SHARD PLAN -----')
    return lines.join('\n')
}

export interface ShardDeviceMemory {
    total: number
    available: number
    threshold: number
    low: boolean
    /** What this phone will actually offer, after the profile fraction. */
    usable: number
    profile: string
    fraction: number
}

/**
 * This phone's own memory arithmetic, spelled out.
 *
 * Placement is only ever as good as this number, and it is derived through several steps that are
 * each invisible on their own — so the log shows the raw reading, the OS's kill threshold, the
 * profile fraction and the result, rather than the result alone.
 */
export function formatShardMemory(m: ShardDeviceMemory, rpcAddr?: string): string {
    const headroom = Math.max(0, m.available - m.threshold)
    return [
        '------ SHARD DEVICE -----',
        rpcAddr ? `Serving layers at: ${rpcAddr}` : 'Not serving layers (no rpc-server started)',
        `RAM total: ${gb(m.total)}`,
        `Available now: ${gb(m.available)}${m.low ? '  (Android reports LOW MEMORY)' : ''}`,
        `OS kill threshold: ${gb(m.threshold)}`,
        `Headroom: ${gb(headroom)}`,
        `Profile: ${m.profile} (${Math.round(m.fraction * 100)}% of headroom)`,
        `Offering to the mesh: ${gb(m.usable)}`,
        '------ END SHARD DEVICE -----',
    ].join('\n')
}

export interface BackendDevice {
    backend: string
    type: string
    deviceName: string
    maxMemorySize?: number
}

/**
 * What the context reports after a sharded load — the check that the split was actually taken.
 *
 * "Workers: 1" was a true statement that read as a complaint, and it answered the wrong question.
 * The number that matters is not how many workers were asked for but whether llama.cpp actually
 * registered them: an rpc-server that never connected leaves the model loaded whole on this phone,
 * running perfectly, at a speed nothing distinguishes from a working shard on a small model.
 *
 * `devices` is llama.cpp's own list. An entry whose backend is RPC is proof the far phone is in
 * the graph; no such entry means the layers never left, whatever the plan said.
 */
export function formatShardLoaded(
    plan: RpcClusterPlan,
    gpuLayers: number,
    loadMs: number,
    sharded: boolean,
    devices: BackendDevice[] = []
): string {
    const rpcDevices = devices.filter(
        (d) => /rpc/i.test(d.backend) || /rpc/i.test(d.type) || /rpc/i.test(d.deviceName)
    )
    const wanted = plan.rpc_arg ? plan.rpc_arg.split(',').filter(Boolean) : []

    const lines = [
        '------ SHARD LOADED -----',
        `Took ${(loadMs / 1000).toFixed(1)}s`,
        `Phones in the ring: ${plan.ring.length} (this one + ${plan.ring.length - 1} holding layers)`,
        `Context marked sharded: ${sharded}`,
        `n_gpu_layers requested: ${gpuLayers} of ${plan.n_layers}`,
    ]

    if (devices.length === 0) {
        lines.push('Backend devices: could not be read — offload is UNVERIFIED')
    } else {
        lines.push(`Backend devices: ${devices.map((d) => `${d.backend}/${d.deviceName}`).join(', ')}`)
        if (rpcDevices.length === 0)
            lines.push(
                `  ! NO RPC DEVICE REGISTERED — the ${wanted.length} worker(s) were asked for and ` +
                    'llama.cpp did not connect to any. Every layer is running on THIS phone; the ' +
                    'split was planned and then not taken. Check the worker tapped "Share this ' +
                    `phone's RAM" and is reachable at ${wanted.join(', ') || '(no address)'}.`
            )
        else if (rpcDevices.length < wanted.length)
            lines.push(
                `  ! only ${rpcDevices.length} of ${wanted.length} workers connected — the rest of ` +
                    'their layers fell back to this phone'
            )
        else lines.push(`  ✓ ${rpcDevices.length} RPC device(s) in the graph — layers really left this phone`)
    }

    // The failure that cost the most time: layers eligible to leave the phone were capped at 0, so
    // the split was planned, logged, and then quietly ignored by llama.cpp.
    if (gpuLayers <= 0)
        lines.push('  ! n_gpu_layers is 0 — NOTHING left this phone, the model is loaded whole here')
    if (plan.ring.length <= 1)
        lines.push('  ! the ring is one node — this is a normal local load, not a shard')
    lines.push('------ END SHARD LOADED -----')
    return lines.join('\n')
}
