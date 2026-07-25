// BitChat fragmentation — splitting packets that exceed the BLE link MTU and reassembling them.
//
// Ported from bitchat/Services/BLE/BLEOutboundFragmentPlanner.swift and BLEFragmentHandler.swift.
// The important structural point: a fragment does NOT carry a piece of the original *payload*, it
// carries a slice of the fully encoded original packet — padding and all. Reassembly therefore just
// concatenates the slices and hands the result back to the packet decoder.
//
// Fragment packet: type = MsgType.fragment (0x20), payload =
//   fragmentID(8) | index(2, big-endian) | total(2, big-endian) | originalType(1) | chunk
//
// Proven by integration/chatterui_llamacpp/js/bitchat_fragment_smoke.mjs.

// Explicit .ts extension so the smoke tests can load this file directly under Node's
// type-stripping, which (unlike Metro and tsc) will not guess the extension. The type import is
// kept separate for the same reason: strip-only mode cannot tell a type export from a value one.
import type { BitchatPacket } from './bitchatCodec.ts'
import { MsgType, decodePacket, encodePacket } from './bitchatCodec.ts'

export const FRAGMENT_ID_LEN = 8
export const FRAGMENT_HEADER_LEN = 13 // id(8) + index(2) + total(2) + originalType(1)
export const MIN_CHUNK_SIZE = 64

// Reassembly limits, matching the reference: enough for real traffic, bounded against a peer that
// opens assemblies and never finishes them.
export const MAX_CONCURRENT_ASSEMBLIES = 128
export const ASSEMBLY_TIMEOUT_MS = 30_000
export const MAX_ASSEMBLED_BYTES = 1 << 20

export interface FragmentHeader {
    fragmentID: Uint8Array
    index: number
    total: number
    originalType: number
    data: Uint8Array
}

export function randomFragmentID(rand: (n: number) => Uint8Array): Uint8Array {
    return rand(FRAGMENT_ID_LEN)
}

export function parseFragment(payload: Uint8Array): FragmentHeader | null {
    if (payload.length < FRAGMENT_HEADER_LEN) return null
    const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength)
    const total = view.getUint16(10, false)
    const index = view.getUint16(8, false)
    if (total === 0 || index >= total) return null
    return {
        fragmentID: payload.slice(0, FRAGMENT_ID_LEN),
        index,
        total,
        originalType: payload[12],
        data: payload.slice(FRAGMENT_HEADER_LEN),
    }
}

// Split one packet into fragment packets. `chunkSize` comes from the negotiated BLE MTU; the
// reference clamps it to a floor so a tiny MTU can't explode the fragment count.
export function fragmentPacket(
    packet: BitchatPacket,
    fragmentID: Uint8Array,
    chunkSize: number,
    { padding = true } = {}
): BitchatPacket[] | null {
    if (fragmentID.length !== FRAGMENT_ID_LEN) return null
    const full = encodePacket(packet, { padding })
    if (!full) return null
    const size = Math.max(chunkSize, MIN_CHUNK_SIZE)

    const chunks: Uint8Array[] = []
    for (let at = 0; at < full.length; at += size) chunks.push(full.subarray(at, Math.min(at + size, full.length)))
    if (!chunks.length || chunks.length > 0xffff) return null

    return chunks.map((chunk, index) => {
        const payload = new Uint8Array(FRAGMENT_HEADER_LEN + chunk.length)
        payload.set(fragmentID, 0)
        const view = new DataView(payload.buffer)
        view.setUint16(8, index, false)
        view.setUint16(10, chunks.length, false)
        payload[12] = packet.type
        payload.set(chunk, FRAGMENT_HEADER_LEN)
        // The fragment travels as its own packet, keeping the original's addressing and TTL so
        // relays route it the same way.
        return {
            version: packet.version,
            type: MsgType.fragment,
            ttl: packet.ttl,
            timestamp: packet.timestamp,
            senderID: packet.senderID,
            recipientID: packet.recipientID,
            payload,
            isRSR: packet.isRSR,
        }
    })
}

interface Assembly {
    total: number
    originalType: number
    chunks: Map<number, Uint8Array>
    bytes: number
    startedAt: number
}

const idKey = (id: Uint8Array) => Array.from(id, (b) => b.toString(16).padStart(2, '0')).join('')

// Collects fragments until a set is complete, then decodes the reassembled packet.
export class FragmentAssembler {
    private assemblies = new Map<string, Assembly>()

    // Feed a received fragment packet. Returns the original packet once the last piece arrives,
    // null while still incomplete or if the fragment is unusable.
    add(fragment: BitchatPacket, now = Date.now()): BitchatPacket | null {
        if (fragment.type !== MsgType.fragment) return null
        const header = parseFragment(fragment.payload)
        if (!header) return null

        this.evictStale(now)
        const key = idKey(header.fragmentID)
        let asm = this.assemblies.get(key)
        if (!asm) {
            // Drop the oldest rather than growing without bound when a peer floods new ids.
            if (this.assemblies.size >= MAX_CONCURRENT_ASSEMBLIES) {
                const oldest = [...this.assemblies.entries()].sort((a, b) => a[1].startedAt - b[1].startedAt)[0]
                if (oldest) this.assemblies.delete(oldest[0])
            }
            asm = { total: header.total, originalType: header.originalType, chunks: new Map(), bytes: 0, startedAt: now }
            this.assemblies.set(key, asm)
        }
        // A fragment claiming a different total for the same id is inconsistent — ignore it rather
        // than letting it corrupt an in-flight assembly.
        if (header.total !== asm.total) return null
        if (asm.chunks.has(header.index)) return null // duplicate, e.g. a relayed copy

        if (asm.bytes + header.data.length > MAX_ASSEMBLED_BYTES) {
            this.assemblies.delete(key)
            return null
        }
        asm.chunks.set(header.index, header.data)
        asm.bytes += header.data.length
        if (asm.chunks.size !== asm.total) return null

        this.assemblies.delete(key)
        const out = new Uint8Array(asm.bytes)
        let at = 0
        for (let i = 0; i < asm.total; i++) {
            const part = asm.chunks.get(i)!
            out.set(part, at)
            at += part.length
        }
        return decodePacket(out)
    }

    private evictStale(now: number) {
        for (const [key, asm] of this.assemblies)
            if (now - asm.startedAt > ASSEMBLY_TIMEOUT_MS) this.assemblies.delete(key)
    }

    get pending() {
        return this.assemblies.size
    }
}
