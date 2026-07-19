// HELIX frame codec + stream framing for ChatterUI (Level 2). RN-safe (Uint8Array).
//
// Mirrors helix/session.py FrameCodec + helix/transport/framing.py, and the proven JS twin
// integration/chatterui_llamacpp/js/frame_codec.mjs. Builds on helixCrypto.ts (@noble, no native).

import {
    FLAG_CONFIDENTIAL,
    NONCE_LEN,
    aeadOpen,
    aeadSeal,
    concatBytes,
    encodeHeader,
    randomBytes as nobleRandomBytes,
    utf8ToBytes,
} from './helixCrypto'

// Source of a random nonce. Default @noble (works in Node/tests). In React Native, @noble's RNG
// needs a global crypto polyfill; instead the app injects one backed by expo-crypto (already a
// ChatterUI dep, New-Architecture-safe) — see helixAgent.ts. Signature: (n) => Uint8Array.
export type RandomBytes = (n: number) => Uint8Array

export const HEADER_LEN = 21
export const MAX_FRAME = 16 * 1024 * 1024
export const PROTOCOL_VERSION = 1

export const Msg = {
    ANNOUNCE: 'ANNOUNCE', HEARTBEAT: 'HEARTBEAT', LEASE: 'LEASE', LEASE_ACK: 'LEASE_ACK',
    AGENT_ANNOUNCE: 'AGENT_ANNOUNCE', STATUS: 'STATUS', TASK: 'TASK',
    PARTIAL: 'PARTIAL', RESULT: 'RESULT', VOTE: 'VOTE', DECIDE: 'DECIDE',
    CONTEXT_SYNC: 'CONTEXT_SYNC',
} as const

export interface HelixMessage {
    v: number
    type: string
    src: string
    seq: number
    tid: string
    body: Record<string, unknown>
}

const td = new TextDecoder()

// --- Message: compact JSON {v,t,seq,src[,tid][,b]} in that order ---
export function serializeMessage(m: {
    v?: number; t: string; seq?: number; src: string; tid?: string; b?: Record<string, unknown>
}): Uint8Array {
    const obj: Record<string, unknown> = { v: m.v ?? PROTOCOL_VERSION, t: m.t, seq: m.seq ?? 0, src: m.src }
    if (m.tid) obj.tid = m.tid
    if (m.b && Object.keys(m.b).length) obj.b = m.b
    return utf8ToBytes(JSON.stringify(obj))
}

export function parseMessage(pt: Uint8Array): HelixMessage | null {
    let o: any
    try {
        o = JSON.parse(td.decode(pt))
    } catch {
        return null
    }
    if (typeof o !== 'object' || o === null || o.v !== PROTOCOL_VERSION) return null
    if (typeof o.t !== 'string' || typeof o.src !== 'string' || !o.src) return null
    const seq = o.seq ?? 0
    if (!Number.isInteger(seq) || seq < 0) return null
    const b = o.b ?? {}
    if (typeof b !== 'object' || b === null) return null
    return { v: o.v, type: o.t, src: o.src, seq, tid: String(o.tid ?? ''), body: b }
}

// per-sender sliding-window anti-replay (window 64), like helix/session.py
class ReplayGuard {
    private st = new Map<string, { hi: number; bm: bigint }>()
    constructor(private readonly w = 64) {}
    accept(src: string, seq: number): boolean {
        let s = this.st.get(src)
        if (!s) {
            s = { hi: 0, bm: 0n }
            this.st.set(src, s)
        }
        const full = (1n << BigInt(this.w)) - 1n
        if (seq > s.hi) {
            const shift = seq - s.hi
            s.bm = shift >= this.w ? 1n : ((s.bm << BigInt(shift)) | 1n) & full
            s.hi = seq
            return true
        }
        const off = s.hi - seq
        if (off >= this.w) return false
        const mask = 1n << BigInt(off)
        if (s.bm & mask) return false
        s.bm |= mask
        return true
    }
}

export class FrameCodec {
    private seq = 0
    private replay: ReplayGuard | null
    private readonly rand: RandomBytes

    constructor(
        public readonly nodeId: string,
        private readonly key: Uint8Array,
        private readonly epoch = 0,
        replay = true,
        rand: RandomBytes = nobleRandomBytes
    ) {
        this.replay = replay ? new ReplayGuard() : null
        this.rand = rand
    }

    seal(type: string, body: Record<string, unknown> = {}, tid = ''): Uint8Array {
        this.seq += 1
        const nonce = this.rand(NONCE_LEN) // app injects expo-crypto; default @noble (Node/tests)
        const header = encodeHeader(FLAG_CONFIDENTIAL, this.epoch, nonce)
        const pt = serializeMessage({ t: type, seq: this.seq, src: this.nodeId, tid, b: body })
        return concatBytes(header, aeadSeal(this.key, nonce, pt, header))
    }

    open(wire: Uint8Array): HelixMessage | null {
        if (wire.length < HEADER_LEN || wire.length > MAX_FRAME) return null
        if (!(wire[0] === 0x48 && wire[1] === 0x4c && wire[2] === 0x58 && wire[3] === 0x31)) return null // "HLX1"
        const dv = new DataView(wire.buffer, wire.byteOffset, wire.byteLength)
        const flags = wire[4]
        const epoch = dv.getUint32(5, true)
        if (epoch !== this.epoch) return null
        const nonce = wire.subarray(9, 21)
        const header = encodeHeader(flags, epoch, nonce)
        const pt = aeadOpen(this.key, nonce, wire.subarray(21), header)
        if (!pt) return null
        const msg = parseMessage(pt)
        if (!msg) return null
        if (this.replay && !this.replay.accept(msg.src, msg.seq)) return null
        return msg
    }
}

// --- stream framing: uint32 BE length || frame (helix/transport/framing.py) ---
export function frameOut(frame: Uint8Array): Uint8Array {
    const out = new Uint8Array(4 + frame.length)
    new DataView(out.buffer).setUint32(0, frame.length, false) // big-endian
    out.set(frame, 4)
    return out
}

export class StreamFramer {
    private buf = new Uint8Array(0)
    constructor(private readonly max = MAX_FRAME) {}
    push(chunk: Uint8Array): ({ frame: Uint8Array } | { bad: true })[] {
        const merged = new Uint8Array(this.buf.length + chunk.length)
        merged.set(this.buf, 0)
        merged.set(chunk, this.buf.length)
        this.buf = merged
        const out: ({ frame: Uint8Array } | { bad: true })[] = []
        while (this.buf.length >= 4) {
            const len = new DataView(this.buf.buffer, this.buf.byteOffset, this.buf.byteLength).getUint32(0, false)
            if (len === 0 || len > this.max) {
                out.push({ bad: true })
                this.buf = new Uint8Array(0)
                break
            }
            if (this.buf.length < 4 + len) break
            out.push({ frame: this.buf.subarray(4, 4 + len) })
            this.buf = this.buf.subarray(4 + len)
        }
        return out
    }
}
