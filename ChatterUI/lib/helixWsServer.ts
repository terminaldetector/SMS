// Minimal WebSocket (RFC 6455) SERVER in JS for React Native — lets a ChatterUI HOST phone accept a
// WebSocket agent phone directly (NO PC). Port of helix/host/ws.py + js/ws_server.mjs. RN-safe
// (Uint8Array, @noble sha1, inline base64 — no Node Buffer). Runs over any duck-typed stream socket;
// the host uses react-native-tcp-socket's server (the ONLY native module, host role only).
//
// Proven cross-language by integration/chatterui_llamacpp/js/p2p_ws_smoke.mjs.

import { sha1 } from '@noble/hashes/sha1'

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
export const OP_BIN = 0x2, OP_CLOSE = 0x8, OP_PING = 0x9, OP_PONG = 0xa
const MAX_MSG = 16 * 1024 * 1024
const _B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
const te = new TextEncoder()
const td = new TextDecoder()

function base64(bytes: Uint8Array): string {
    let out = ''
    for (let i = 0; i < bytes.length; i += 3) {
        const b0 = bytes[i]
        const b1 = i + 1 < bytes.length ? bytes[i + 1] : 0
        const b2 = i + 2 < bytes.length ? bytes[i + 2] : 0
        out += _B64[b0 >> 2] + _B64[((b0 & 3) << 4) | (b1 >> 4)]
        out += i + 1 < bytes.length ? _B64[((b1 & 15) << 2) | (b2 >> 6)] : '='
        out += i + 2 < bytes.length ? _B64[b2 & 63] : '='
    }
    return out
}

export function acceptKey(key: string): string {
    return base64(sha1(te.encode(key + GUID)))
}

export function encodeFrame(opcode: number, payload: Uint8Array = new Uint8Array(0)): Uint8Array {
    const n = payload.length
    let header: Uint8Array
    if (n < 126) header = Uint8Array.from([0x80 | opcode, n])
    else if (n < 65536) header = Uint8Array.from([0x80 | opcode, 126, (n >> 8) & 0xff, n & 0xff])
    else header = Uint8Array.from([0x80 | opcode, 127, 0, 0, 0, 0, (n >>> 24) & 0xff, (n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff])
    const out = new Uint8Array(header.length + n)
    out.set(header, 0)
    out.set(payload, header.length)
    return out
}

// Normalize whatever react-native-tcp-socket hands us ('data' can be a Buffer, Uint8Array, or a
// latin1/binary string depending on version) into a Uint8Array.
function toBytes(d: unknown): Uint8Array {
    if (typeof d === 'string') return Uint8Array.from(d, (c) => c.charCodeAt(0) & 0xff)
    const anyd = d as { buffer?: ArrayBuffer; length?: number }
    if (anyd && anyd.buffer) return new Uint8Array(anyd.buffer, (anyd as any).byteOffset ?? 0, (anyd as any).byteLength ?? anyd.length)
    return new Uint8Array(d as ArrayBuffer)
}

export interface StreamSock {
    on(event: 'data', cb: (data: unknown) => void): void
    on(event: 'close' | 'error', cb: () => void): void
    write(data: Uint8Array): void
    destroy?(): void
}

export class WsServerConnection {
    private buf = new Uint8Array(0)
    private handshaken = false
    private _onBinary: (p: Uint8Array) => void = () => {}
    private _onClose: () => void = () => {}

    constructor(private readonly socket: StreamSock) {
        socket.on('data', (d) => this.onData(toBytes(d)))
        socket.on('close', () => this._onClose())
        socket.on('error', () => this._onClose())
    }

    onBinary(cb: (p: Uint8Array) => void) { this._onBinary = cb }
    onClose(cb: () => void) { this._onClose = cb }

    private append(chunk: Uint8Array) {
        const m = new Uint8Array(this.buf.length + chunk.length)
        m.set(this.buf, 0)
        m.set(chunk, this.buf.length)
        this.buf = m
    }

    private onData(chunk: Uint8Array) {
        this.append(chunk)
        if (!this.handshaken) {
            const s = td.decode(this.buf)
            const end = s.indexOf('\r\n\r\n')
            if (end < 0) return
            const key = (s.match(/sec-websocket-key:\s*(.+)\r\n/i) || [])[1]?.trim()
            if (!key) { this.socket.destroy?.(); return }
            const resp =
                'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n' +
                `Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n`
            this.socket.write(te.encode(resp))
            this.handshaken = true
            this.buf = this.buf.subarray(end + 4)
        }
        this.drainFrames()
    }

    private drainFrames() {
        while (this.buf.length >= 2) {
            const b1 = this.buf[1]
            const opcode = this.buf[0] & 0x0f
            const masked = !!(b1 & 0x80)
            let len = b1 & 0x7f
            let off = 2
            if (len === 126) { if (this.buf.length < 4) return; len = (this.buf[2] << 8) | this.buf[3]; off = 4 }
            else if (len === 127) {
                if (this.buf.length < 10) return
                len = 0
                for (let i = 2; i < 10; i++) len = len * 256 + this.buf[i]
                off = 10
            }
            if (len > MAX_MSG) { this.socket.destroy?.(); return }
            const need = off + (masked ? 4 : 0) + len
            if (this.buf.length < need) return
            let mask: Uint8Array | null = null
            if (masked) { mask = this.buf.subarray(off, off + 4); off += 4 }
            const payload = this.buf.slice(off, off + len)
            if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3]
            this.buf = this.buf.subarray(need)
            if (opcode === OP_BIN) this._onBinary(payload)
            else if (opcode === OP_PING) this.socket.write(encodeFrame(OP_PONG, payload))
            else if (opcode === OP_CLOSE) { this.socket.destroy?.(); return }
        }
    }

    send(payload: Uint8Array) { if (this.handshaken) this.socket.write(encodeFrame(OP_BIN, payload)) }
    close() { try { this.socket.write(encodeFrame(OP_CLOSE)); this.socket.destroy?.() } catch { /* ignore */ } }
}
