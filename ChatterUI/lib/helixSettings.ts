// Mesh settings, in one place both React and non-React code can read.
//
// These were module constants inside HelixMeshScreen — fine while the mesh was a demo, wrong now
// that a phone has to agree with other phones about them. Ports collide with whatever else a phone
// is running, and the cluster secret was a published demo string, which means any two builds of
// this app on the same Wi-Fi could join each other's mesh. Neither is something a screen should
// own privately.
//
// Everything here is read live from MMKV rather than captured at import, so a change applies to
// the next connection without a restart. Values already negotiated (a mesh you are hosting right
// now) keep the settings they started with — changing a port cannot move a socket that is already
// bound.

import { mmkv } from './storage/MMKV'

export const HelixKeys = {
    /** The coordinator's WebSocket port — agents connect here, and the QR encodes it. */
    port: 'helix-mesh-port',
    /** llama.cpp's rpc-server port on each shard worker. */
    rpcPort: 'helix-rpc-port',
    /** The cluster secret every phone in one mesh must share. */
    secret: 'helix-cluster-secret',
    /** How much of a phone's free memory it will commit to holding layers. */
    memoryProfile: 'helix-memory-profile',
} as const

export const HELIX_DEFAULT_PORT = 8790
export const HELIX_DEFAULT_RPC_PORT = 50052
// The same string the PC demo uses (helix/host/agent_host_ws_demo.py), so a stock build still talks
// to it. It is published, hence the warning in settings: a private mesh should not keep it.
export const HELIX_DEFAULT_SECRET = 'helix-agent-host-ws-demo'

/**
 * How much of what is free a phone offers the mesh.
 *
 * The figure is a fraction of headroom (free memory already minus Android's low-memory kill
 * threshold), not of total RAM. Higher means more layers land on this phone and fewer hops per
 * token; too high means the OS kills the app mid-answer, which costs the whole mesh the inference.
 */
export type MemoryProfile = 'cautious' | 'balanced' | 'greedy'

export const MEMORY_PROFILE_FRACTION: Record<MemoryProfile, number> = {
    cautious: 0.5,
    balanced: 0.66,
    greedy: 0.85,
}

function readPort(key: string, fallback: number): number {
    const raw = mmkv.getString(key)
    const n = raw ? Number(raw) : NaN
    // A port outside the unprivileged range cannot be bound without root, so a typo falls back
    // rather than failing at bind time with something unrecognisable.
    if (!Number.isInteger(n) || n < 1024 || n > 65535) return fallback
    return n
}

export function helixPort(): number {
    return readPort(HelixKeys.port, HELIX_DEFAULT_PORT)
}

export function helixRpcPort(): number {
    return readPort(HelixKeys.rpcPort, HELIX_DEFAULT_RPC_PORT)
}

export function helixSecret(): string {
    const s = mmkv.getString(HelixKeys.secret)
    return s && s.length > 0 ? s : HELIX_DEFAULT_SECRET
}

export function helixMemoryProfile(): MemoryProfile {
    const p = mmkv.getString(HelixKeys.memoryProfile)
    return p === 'cautious' || p === 'greedy' ? p : 'balanced'
}

export function helixMemoryFraction(): number {
    return MEMORY_PROFILE_FRACTION[helixMemoryProfile()]
}
