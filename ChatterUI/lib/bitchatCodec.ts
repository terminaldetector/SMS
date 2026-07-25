// BitChat wire protocol — binary packet codec.
//
// Byte-for-byte compatible with BitChat's own BinaryProtocol (permissionlesstech/bitchat), so an
// UNMODIFIED BitChat app can talk to this node. Ported from:
//   localPackages/BitFoundation/Sources/BitFoundation/BinaryProtocol.swift
//   localPackages/BitFoundation/Sources/BitFoundation/MessagePadding.swift
//   localPackages/BitFoundation/Sources/BitFoundation/MessageType.swift
// See integration/chatterui_llamacpp/BITCHAT_BRIDGE.md for the constants and where each came from.
//
// All multi-byte values are big-endian. RN-safe: Uint8Array throughout, no Node Buffer.
// Proven against the Swift reference's own layout by js/bitchat_codec_smoke.mjs.

import { inflateSync } from 'fflate'

// --- BLE identifiers (bitchat/Services/BLE/BLEService.swift) ---
export const SERVICE_UUID = 'F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C' // mainnet
export const SERVICE_UUID_TESTNET = 'F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5A'
export const CHARACTERISTIC_UUID = 'A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D'

// --- Packet types (MessageType.swift) ---
export const MsgType = {
    announce: 0x01,
    message: 0x02,
    leave: 0x03,
    courierEnvelope: 0x04,
    noiseHandshake: 0x10,
    noiseEncrypted: 0x11,
    fragment: 0x20,
    requestSync: 0x21,
    fileTransfer: 0x22,
    boardPost: 0x23,
    prekeyBundle: 0x24,
    groupMessage: 0x25,
    ping: 0x26,
    pong: 0x27,
    nostrCarrier: 0x28,
    voiceFrame: 0x29,
} as const

// --- Payload types inside a decrypted noiseEncrypted body (BitchatProtocol.swift) ---
export const NoisePayloadType = {
    privateMessage: 0x01,
    readReceipt: 0x02,
    delivered: 0x03,
    groupInvite: 0x06,
    groupKeyUpdate: 0x07,
    voiceFrame: 0x08,
    verifyChallenge: 0x10,
    verifyResponse: 0x11,
    vouch: 0x12,
} as const

export const V1_HEADER_SIZE = 14
export const V2_HEADER_SIZE = 16
export const SENDER_ID_SIZE = 8
export const RECIPIENT_ID_SIZE = 8
export const SIGNATURE_SIZE = 64

export const Flags = {
    hasRecipient: 0x01,
    hasSignature: 0x02,
    isCompressed: 0x04,
    hasRoute: 0x08,
    isRSR: 0x10,
} as const

// Matches FileTransferLimits.maxFramedFileBytes on the Swift side: the decoder refuses anything
// larger rather than trying to allocate it.
const MAX_FRAMED_BYTES = 1 << 20

export interface BitchatPacket {
    version: number
    type: number
    ttl: number
    // UInt64 on the wire. bigint, not number: encodeForSigning re-encodes the packet, so any
    // precision lost here would silently break signature verification.
    timestamp: bigint
    senderID: Uint8Array
    recipientID?: Uint8Array
    route?: Uint8Array[]
    payload: Uint8Array
    signature?: Uint8Array
    isRSR?: boolean
}

const headerSize = (version: number) =>
    version === 1 ? V1_HEADER_SIZE : version === 2 ? V2_HEADER_SIZE : null
const lengthFieldSize = (version: number) => (version === 2 ? 4 : 2)

// --- PKCS#7-style padding to a block size, to hide the real length (MessagePadding.swift) ---
export const BLOCK_SIZES = [256, 512, 1024, 2048]

export function optimalBlockSize(dataSize: number): number {
    const total = dataSize + 16 // leaves room for an AEAD tag, as the Swift side does
    for (const b of BLOCK_SIZES) if (total <= b) return b
    return dataSize // too big to pad — it gets fragmented anyway
}

export function pad(data: Uint8Array, targetSize: number): Uint8Array {
    if (data.length >= targetSize) return data
    const need = targetSize - data.length
    if (need <= 0 || need > 255) return data // pad length must fit one byte
    const out = new Uint8Array(data.length + need)
    out.set(data, 0)
    out.fill(need, data.length)
    return out
}

export function unpad(data: Uint8Array): Uint8Array {
    if (!data.length) return data
    const n = data[data.length - 1]
    if (n <= 0 || n > data.length) return data
    for (let i = data.length - n; i < data.length; i++) if (data[i] !== n) return data
    return data.subarray(0, data.length - n)
}

// Fit an id into exactly `size` bytes: truncate if long, zero-pad if short (as Swift does).
function fixedWidth(id: Uint8Array, size: number): Uint8Array {
    if (id.length === size) return id
    const out = new Uint8Array(size)
    out.set(id.subarray(0, size), 0)
    return out
}

export function encodePacket(packet: BitchatPacket, { padding = true } = {}): Uint8Array | null {
    const version = packet.version
    const hSize = headerSize(version)
    if (hSize === null) return null
    const lenBytes = lengthFieldSize(version)

    // We never compress on send — it is the sender's choice, and an uncompressed packet is always
    // valid to a peer. (Decoding still handles compressed packets from others.)
    const payload = packet.payload
    if (version === 1 && payload.length > 0xffff) return null
    if (version === 2 && payload.length > 0xffffffff) return null

    // Route is a v2+ feature; on v1 it is dropped rather than mis-encoded.
    const route = version >= 2 ? (packet.route ?? []).map((h) => fixedWidth(h, SENDER_ID_SIZE)) : []
    if (route.length > 255) return null
    const hasRoute = route.length > 0

    const parts: number[] = []
    parts.push(version, packet.type, packet.ttl)
    for (let shift = 56n; shift >= 0n; shift -= 8n)
        parts.push(Number((packet.timestamp >> shift) & 0xffn))

    let flags = 0
    if (packet.recipientID) flags |= Flags.hasRecipient
    if (packet.signature) flags |= Flags.hasSignature
    if (hasRoute) flags |= Flags.hasRoute
    if (packet.isRSR) flags |= Flags.isRSR
    parts.push(flags)

    // payloadLength counts the payload only — route bytes are excluded.
    if (version === 2)
        for (let s = 24; s >= 0; s -= 8) parts.push((payload.length >>> s) & 0xff)
    else parts.push((payload.length >> 8) & 0xff, payload.length & 0xff)

    const head = new Uint8Array(parts)
    const chunks: Uint8Array[] = [head, fixedWidth(packet.senderID, SENDER_ID_SIZE)]
    if (packet.recipientID) chunks.push(fixedWidth(packet.recipientID, RECIPIENT_ID_SIZE))
    if (hasRoute) {
        chunks.push(new Uint8Array([route.length]))
        for (const hop of route) chunks.push(hop)
    }
    chunks.push(payload)
    if (packet.signature) chunks.push(packet.signature.subarray(0, SIGNATURE_SIZE))

    const total = chunks.reduce((s, c) => s + c.length, 0)
    const out = new Uint8Array(total)
    let at = 0
    for (const c of chunks) {
        out.set(c, at)
        at += c.length
    }
    return padding ? pad(out, optimalBlockSize(out.length)) : out
}

// The exact bytes BitChat signs: no signature, TTL forced to 0 (it changes as relays decrement it)
// and the RSR flag cleared. Padding is on, matching the Swift default.
export function encodeForSigning(packet: BitchatPacket): Uint8Array | null {
    return encodePacket({ ...packet, signature: undefined, ttl: 0, isRSR: false })
}

export function decodePacket(data: Uint8Array): BitchatPacket | null {
    // Try as-is first (packets aren't always padded), then retry with padding stripped — the same
    // two-step the Swift decoder uses.
    const direct = decodeCore(data)
    if (direct) return direct
    const stripped = unpad(data)
    if (stripped.length === data.length) return null
    return decodeCore(stripped)
}

function decodeCore(raw: Uint8Array): BitchatPacket | null {
    if (raw.length < V1_HEADER_SIZE + SENDER_ID_SIZE) return null
    let off = 0
    const left = (n: number) => off + n <= raw.length

    const version = raw[off++]
    if (version !== 1 && version !== 2) return null
    const hSize = headerSize(version)!
    const lenBytes = lengthFieldSize(version)
    if (raw.length < hSize + SENDER_ID_SIZE) return null

    const type = raw[off++]
    const ttl = raw[off++]

    let timestamp = 0n
    for (let i = 0; i < 8; i++) timestamp = (timestamp << 8n) | BigInt(raw[off++])

    const flags = raw[off++]
    const hasRecipient = (flags & Flags.hasRecipient) !== 0
    const hasSignature = (flags & Flags.hasSignature) !== 0
    const isCompressed = (flags & Flags.isCompressed) !== 0
    const hasRoute = version >= 2 && (flags & Flags.hasRoute) !== 0
    const isRSR = (flags & Flags.isRSR) !== 0

    let payloadLength: number
    if (version === 2) {
        if (!left(4)) return null
        payloadLength = ((raw[off] << 24) | (raw[off + 1] << 16) | (raw[off + 2] << 8) | raw[off + 3]) >>> 0
        off += 4
    } else {
        if (!left(2)) return null
        payloadLength = (raw[off] << 8) | raw[off + 1]
        off += 2
    }
    if (payloadLength > MAX_FRAMED_BYTES) return null

    if (!left(SENDER_ID_SIZE)) return null
    const senderID = raw.slice(off, off + SENDER_ID_SIZE)
    off += SENDER_ID_SIZE

    let recipientID: Uint8Array | undefined
    if (hasRecipient) {
        if (!left(RECIPIENT_ID_SIZE)) return null
        recipientID = raw.slice(off, off + RECIPIENT_ID_SIZE)
        off += RECIPIENT_ID_SIZE
    }

    // Route bytes sit between the ids and the payload, and are NOT counted in payloadLength.
    let route: Uint8Array[] | undefined
    if (hasRoute) {
        if (!left(1)) return null
        const hops = raw[off++]
        if (hops > 0) {
            const list: Uint8Array[] = []
            for (let i = 0; i < hops; i++) {
                if (!left(SENDER_ID_SIZE)) return null
                list.push(raw.slice(off, off + SENDER_ID_SIZE))
                off += SENDER_ID_SIZE
            }
            route = list
        }
    }

    let payload: Uint8Array
    if (isCompressed) {
        if (payloadLength < lenBytes) return null
        let originalSize: number
        if (version === 2) {
            if (!left(4)) return null
            originalSize = ((raw[off] << 24) | (raw[off + 1] << 16) | (raw[off + 2] << 8) | raw[off + 3]) >>> 0
            off += 4
        } else {
            if (!left(2)) return null
            originalSize = (raw[off] << 8) | raw[off + 1]
            off += 2
        }
        if (originalSize > MAX_FRAMED_BYTES) return null
        const compressedSize = payloadLength - lenBytes
        if (compressedSize <= 0 || !left(compressedSize)) return null
        const compressed = raw.slice(off, off + compressedSize)
        off += compressedSize
        // Refuse absurd expansion ratios rather than inflating a decompression bomb.
        if (originalSize / compressedSize > 50000) return null
        try {
            // Apple's COMPRESSION_ZLIB is raw DEFLATE (no zlib header), which is fflate's inflate.
            const out = inflateSync(compressed, { out: new Uint8Array(originalSize) })
            if (out.length !== originalSize) return null
            payload = out
        } catch {
            return null
        }
    } else {
        if (!left(payloadLength)) return null
        payload = raw.slice(off, off + payloadLength)
        off += payloadLength
    }

    let signature: Uint8Array | undefined
    if (hasSignature) {
        if (!left(SIGNATURE_SIZE)) return null
        signature = raw.slice(off, off + SIGNATURE_SIZE)
        off += SIGNATURE_SIZE
    }

    return { version, type, ttl, timestamp, senderID, recipientID, route, payload, signature, isRSR }
}
