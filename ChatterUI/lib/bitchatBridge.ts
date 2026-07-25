// BitChat ↔ HELIX bridge: a private message from a BitChat user becomes a prompt for the mesh's
// model, and the answer goes back over the same Noise session.
//
// Sits above the proven layers (bitchatCodec / bitchatNoise / bitchatFragment) and below the BLE
// transport, which is injected rather than imported — so two bridges can be wired to each other
// in-memory and tested without a radio. That is what js/bitchat_bridge_smoke.mjs does.
//
// Message payload layout, from BitchatMessage.swift (all big-endian):
//   flags(1) | timestamp(8, ms) | idLen(1) | id | senderLen(1) | sender | contentLen(2) | content
//   then, per flag: originalSender, recipientNickname, senderPeerID, mentions

import { sha256 } from '@noble/hashes/sha2'

import type { BitchatPacket } from './bitchatCodec.ts'
import { MsgType, NoisePayloadType, decodePacket, encodePacket } from './bitchatCodec.ts'
import { FragmentAssembler, fragmentPacket } from './bitchatFragment.ts'
import type { NoiseSession } from './bitchatNoise.ts'
import { HandshakeState, defaultRandomBytes } from './bitchatNoise.ts'

export const PEER_ID_LEN = 8
export const DEFAULT_TTL = 7

export const MessageFlags = {
    isRelay: 0x01,
    isPrivate: 0x02,
    hasOriginalSender: 0x04,
    hasRecipientNickname: 0x08,
    hasSenderPeerID: 0x10,
    hasMentions: 0x20,
    isBridged: 0x40,
} as const

const te = new TextEncoder()
const td = new TextDecoder()

/** BitChat identifies a peer by the first 8 bytes of SHA-256 over its Noise static public key. */
export function peerIdFromPublicKey(publicKey: Uint8Array): Uint8Array {
    return sha256(publicKey).slice(0, PEER_ID_LEN)
}

export const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')

export interface BitchatMessage {
    id: string
    sender: string
    content: string
    timestamp: number // ms since epoch
    isPrivate?: boolean
    senderPeerID?: string
}

export function encodeMessage(msg: BitchatMessage): Uint8Array {
    const id = te.encode(msg.id).subarray(0, 255)
    const sender = te.encode(msg.sender).subarray(0, 255)
    const content = te.encode(msg.content).subarray(0, 0xffff)
    const senderPeerID = msg.senderPeerID ? te.encode(msg.senderPeerID).subarray(0, 255) : null

    let flags = 0
    if (msg.isPrivate) flags |= MessageFlags.isPrivate
    if (senderPeerID) flags |= MessageFlags.hasSenderPeerID

    const size = 1 + 8 + 1 + id.length + 1 + sender.length + 2 + content.length +
        (senderPeerID ? 1 + senderPeerID.length : 0)
    const out = new Uint8Array(size)
    const view = new DataView(out.buffer)
    let at = 0
    out[at++] = flags
    view.setBigUint64(at, BigInt(Math.floor(msg.timestamp)), false)
    at += 8
    out[at++] = id.length
    out.set(id, at); at += id.length
    out[at++] = sender.length
    out.set(sender, at); at += sender.length
    view.setUint16(at, content.length, false); at += 2
    out.set(content, at); at += content.length
    if (senderPeerID) {
        out[at++] = senderPeerID.length
        out.set(senderPeerID, at); at += senderPeerID.length
    }
    return out
}

export function decodeMessage(data: Uint8Array): BitchatMessage | null {
    try {
        const view = new DataView(data.buffer, data.byteOffset, data.byteLength)
        let at = 0
        const need = (n: number) => {
            if (at + n > data.length) throw new Error('truncated')
        }
        need(9)
        const flags = data[at++]
        const timestamp = Number(view.getBigUint64(at, false)); at += 8
        need(1)
        const idLen = data[at++]; need(idLen)
        const id = td.decode(data.subarray(at, at + idLen)); at += idLen
        need(1)
        const senderLen = data[at++]; need(senderLen)
        const sender = td.decode(data.subarray(at, at + senderLen)); at += senderLen
        need(2)
        const contentLen = view.getUint16(at, false); at += 2
        need(contentLen)
        const content = td.decode(data.subarray(at, at + contentLen)); at += contentLen

        // Optional fields appear in a fixed order; only the ones whose flag is set are present.
        let senderPeerID: string | undefined
        if (flags & MessageFlags.hasOriginalSender) {
            need(1); const n = data[at++]; need(n); at += n
        }
        if (flags & MessageFlags.hasRecipientNickname) {
            need(1); const n = data[at++]; need(n); at += n
        }
        if (flags & MessageFlags.hasSenderPeerID) {
            need(1); const n = data[at++]; need(n)
            senderPeerID = td.decode(data.subarray(at, at + n)); at += n
        }
        return { id, sender, content, timestamp, isPrivate: !!(flags & MessageFlags.isPrivate), senderPeerID }
    } catch {
        return null
    }
}

/** Moves raw bytes to a peer. The BLE module implements this; tests substitute an in-memory pair. */
export interface BitchatTransport {
    send(peerId: string, data: Uint8Array): Promise<boolean> | boolean
}

/** Answers a prompt — in the app this is the HELIX mesh or the phone's own model. */
export type Responder = (prompt: string, from: string) => Promise<string>

interface PeerState {
    handshake?: HandshakeState
    session?: NoiseSession
    /** Their Noise static key, known once the handshake completes. */
    remoteStatic?: Uint8Array
}

export interface BridgeOptions {
    nickname?: string
    /** Bytes a BLE link can carry in one go; larger packets get fragmented. */
    chunkSize?: number
    randomBytes?: (n: number) => Uint8Array
    onLog?: (line: string) => void
}

export class BitchatBridge {
    private peers = new Map<string, PeerState>()
    private assembler = new FragmentAssembler()
    private readonly nickname: string
    private readonly chunkSize: number
    private readonly rand: (n: number) => Uint8Array
    private readonly onLog?: (line: string) => void
    readonly peerID: Uint8Array

    // Plain fields rather than constructor parameter properties: the smoke tests import this file
    // directly under Node's type-stripping, which cannot parse those.
    private readonly staticKey: { secret: Uint8Array; pub: Uint8Array }
    private readonly transport: BitchatTransport
    private readonly respond: Responder

    constructor(
        staticKey: { secret: Uint8Array; pub: Uint8Array },
        transport: BitchatTransport,
        respond: Responder,
        opts: BridgeOptions = {}
    ) {
        this.staticKey = staticKey
        this.transport = transport
        this.respond = respond
        this.peerID = peerIdFromPublicKey(staticKey.pub)
        this.nickname = opts.nickname ?? 'helix'
        this.chunkSize = opts.chunkSize ?? 469
        // Same injected source the handshakes use — React Native has no global crypto.getRandomValues.
        this.rand = opts.randomBytes ?? defaultRandomBytes
        this.onLog = opts.onLog
    }

    /** Feed every packet the transport delivers. `link` is the BLE address it arrived on. */
    async onData(link: string, raw: Uint8Array): Promise<void> {
        const packet = decodePacket(raw)
        if (!packet) return

        // Fragments rebuild into the original packet; anything else is handled directly.
        if (packet.type === MsgType.fragment) {
            const whole = this.assembler.add(packet)
            if (whole) await this.handle(link, whole)
            return
        }
        await this.handle(link, packet)
    }

    private async handle(link: string, packet: BitchatPacket): Promise<void> {
        // Ignore anything addressed to somebody else; broadcasts have no recipient.
        if (packet.recipientID && hex(packet.recipientID) !== hex(this.peerID)) return

        if (packet.type === MsgType.noiseHandshake) await this.onHandshake(link, packet)
        else if (packet.type === MsgType.noiseEncrypted) await this.onEncrypted(link, packet)
    }

    private state(peer: string): PeerState {
        let s = this.peers.get(peer)
        if (!s) {
            s = {}
            this.peers.set(peer, s)
        }
        return s
    }

    private async onHandshake(link: string, packet: BitchatPacket): Promise<void> {
        const peer = hex(packet.senderID)
        const st = this.state(peer)

        // No handshake in flight means they opened one, so we answer as the responder.
        if (!st.handshake)
            st.handshake = new HandshakeState(false, this.staticKey, undefined, this.rand)

        const hs = st.handshake
        let reply: Uint8Array | null = null
        try {
            hs.readMessage(packet.payload)
            if (!hs.isComplete()) reply = hs.writeMessage()
        } catch (e) {
            // A failed handshake must not leave half-state behind, or every later attempt from that
            // peer would be read against a stale transcript and fail too.
            this.peers.delete(peer)
            this.onLog?.(`handshake with ${peer} failed: ${e instanceof Error ? e.message : String(e)}`)
            return
        }

        // Settle our state BEFORE sending. Sending awaits, and a peer that answers immediately gets
        // its reply handled inside that await — which would otherwise run against, and clear, the
        // very state this frame is about to touch.
        if (hs.isComplete()) {
            st.session = hs.finish()
            st.remoteStatic = st.session.remoteStatic
            st.handshake = undefined
            this.onLog?.(`noise session established with ${peer}`)
        }

        if (reply) await this.sendPacket(link, MsgType.noiseHandshake, reply, packet.senderID)
    }

    private async onEncrypted(link: string, packet: BitchatPacket): Promise<void> {
        const peer = hex(packet.senderID)
        const st = this.peers.get(peer)
        if (!st?.session) {
            this.onLog?.(`encrypted packet from ${peer} before a session existed`)
            return
        }

        let plaintext: Uint8Array
        try {
            plaintext = st.session.recv.decrypt(packet.payload)
        } catch {
            this.onLog?.(`could not decrypt from ${peer}`)
            return
        }
        if (!plaintext.length) return

        // First byte says what the payload is; everything private rides inside noiseEncrypted so
        // an observer cannot tell message types apart.
        const kind = plaintext[0]
        if (kind !== NoisePayloadType.privateMessage) return

        const msg = decodeMessage(plaintext.subarray(1))
        if (!msg?.content.trim()) return
        this.onLog?.(`prompt from ${msg.sender || peer}: ${msg.content}`)

        let answer: string
        try {
            answer = await this.respond(msg.content, msg.sender || peer)
        } catch (e) {
            answer = `error: ${e instanceof Error ? e.message : String(e)}`
        }
        await this.sendPrivate(link, packet.senderID, answer)
    }

    /** Open a Noise session with a peer we discovered (we are the initiator). */
    async startHandshake(link: string, peerID: Uint8Array): Promise<void> {
        const peer = hex(peerID)
        const st = this.state(peer)
        if (st.session || st.handshake) return // already up or in flight
        st.handshake = new HandshakeState(true, this.staticKey, undefined, this.rand)
        await this.sendPacket(link, MsgType.noiseHandshake, st.handshake.writeMessage(), peerID)
    }

    async sendPrivate(link: string, peerID: Uint8Array, text: string): Promise<boolean> {
        const st = this.peers.get(hex(peerID))
        if (!st?.session) return false
        const body = encodeMessage({
            id: hex(this.rand(8)),
            sender: this.nickname,
            content: text,
            timestamp: Date.now(),
            isPrivate: true,
            senderPeerID: hex(this.peerID),
        })
        const payload = new Uint8Array(1 + body.length)
        payload[0] = NoisePayloadType.privateMessage
        payload.set(body, 1)
        return this.sendPacket(link, MsgType.noiseEncrypted, st.session.send.encrypt(payload), peerID)
    }

    private async sendPacket(
        link: string,
        type: number,
        payload: Uint8Array,
        recipientID?: Uint8Array
    ): Promise<boolean> {
        const packet: BitchatPacket = {
            version: 1,
            type,
            ttl: DEFAULT_TTL,
            timestamp: BigInt(Date.now()),
            senderID: this.peerID,
            recipientID,
            payload,
        }
        const whole = encodePacket(packet)
        if (!whole) return false

        // Fits in one write, so send it as-is; only oversized packets pay the fragment overhead.
        if (whole.length <= this.chunkSize) return !!(await this.transport.send(link, whole))

        const frags = fragmentPacket(packet, this.rand(8), this.chunkSize)
        if (!frags) return false
        let ok = true
        for (const f of frags) {
            const bytes = encodePacket(f)
            if (!bytes || !(await this.transport.send(link, bytes))) ok = false
        }
        return ok
    }

    hasSession(peerID: Uint8Array): boolean {
        return !!this.peers.get(hex(peerID))?.session
    }

    forget(peerID: Uint8Array) {
        this.peers.delete(hex(peerID))
    }
}
