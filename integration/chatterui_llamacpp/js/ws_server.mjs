// Minimal WebSocket (RFC 6455) server in JS — the SERVER half a ChatterUI host phone runs so another
// ChatterUI phone (built-in WebSocket agent) connects directly, NO PC. Port of helix/host/ws.py.
//
// Works over any duck-typed stream socket: Node `net.Socket` (this proof) or react-native-tcp-socket
// (the app). Uses @noble sha1 + inline base64 for the accept-key (RN has no Buffer), so this proof
// exercises the exact handshake the app uses. One binary WS message = one HELIX frame.

import { sha1 } from '@noble/hashes/sha1'

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
export const OP_TEXT = 0x1, OP_BIN = 0x2, OP_CLOSE = 0x8, OP_PING = 0x9, OP_PONG = 0xa
const MAX_MSG = 16 * 1024 * 1024
const _B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

const te = new TextEncoder()

export function base64(bytes) {
  let out = ''
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i], b1 = i + 1 < bytes.length ? bytes[i + 1] : 0, b2 = i + 2 < bytes.length ? bytes[i + 2] : 0
    out += _B64[b0 >> 2] + _B64[((b0 & 3) << 4) | (b1 >> 4)]
    out += i + 1 < bytes.length ? _B64[((b1 & 15) << 2) | (b2 >> 6)] : '='
    out += i + 2 < bytes.length ? _B64[b2 & 63] : '='
  }
  return out
}

export function acceptKey(key) {
  return base64(sha1(te.encode(key + GUID)))
}

export function encodeFrame(opcode, payload = new Uint8Array(0)) {
  const n = payload.length
  let header
  if (n < 126) header = Uint8Array.from([0x80 | opcode, n])
  else if (n < 65536) header = Uint8Array.from([0x80 | opcode, 126, (n >> 8) & 0xff, n & 0xff])
  else header = Uint8Array.from([0x80 | opcode, 127, 0, 0, 0, 0, (n >>> 24) & 0xff, (n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff])
  const out = new Uint8Array(header.length + n)
  out.set(header, 0); out.set(payload, header.length)
  return out
}

// A WebSocket server connection over a duck-typed socket: on('data', Uint8Array|Buffer), write(bytes).
export class WsServerConnection {
  constructor(socket) {
    this.socket = socket
    this.buf = new Uint8Array(0)
    this.handshaken = false
    this._onBinary = () => {}
    this._onClose = () => {}
    socket.on('data', (d) => this._onData(d instanceof Uint8Array ? d : new Uint8Array(d)))
    socket.on('close', () => this._onClose())
    socket.on('error', () => this._onClose())
  }

  onBinary(cb) { this._onBinary = cb }
  onClose(cb) { this._onClose = cb }

  _append(chunk) {
    const m = new Uint8Array(this.buf.length + chunk.length)
    m.set(this.buf, 0); m.set(chunk, this.buf.length); this.buf = m
  }

  _onData(chunk) {
    this._append(chunk)
    if (!this.handshaken) {
      const s = new TextDecoder().decode(this.buf)
      const end = s.indexOf('\r\n\r\n')
      if (end < 0) return
      const key = (s.match(/sec-websocket-key:\s*(.+)\r\n/i) || [])[1]?.trim()
      if (!key) { this.socket.destroy?.(); return }
      const resp = 'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
        + `Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n`
      this.socket.write(te.encode(resp))
      this.handshaken = true
      this.buf = this.buf.subarray(end + 4) // leftover = start of WS frames
    }
    this._drainFrames()
  }

  _drainFrames() {
    while (this.buf.length >= 2) {
      const b0 = this.buf[0], b1 = this.buf[1]
      const opcode = b0 & 0x0f, masked = !!(b1 & 0x80)
      let len = b1 & 0x7f, off = 2
      if (len === 126) { if (this.buf.length < 4) return; len = (this.buf[2] << 8) | this.buf[3]; off = 4 }
      else if (len === 127) {
        if (this.buf.length < 10) return
        len = 0; for (let i = 2; i < 10; i++) len = len * 256 + this.buf[i]; off = 10
      }
      if (len > MAX_MSG) { this.socket.destroy?.(); return }
      const need = off + (masked ? 4 : 0) + len
      if (this.buf.length < need) return
      let mask = null
      if (masked) { mask = this.buf.subarray(off, off + 4); off += 4 }
      const payload = this.buf.slice(off, off + len)
      if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3]
      this.buf = this.buf.subarray(need)
      if (opcode === OP_BIN) this._onBinary(payload)
      else if (opcode === OP_PING) this.socket.write(encodeFrame(OP_PONG, payload))
      else if (opcode === OP_CLOSE) { this.socket.destroy?.(); return }
    }
  }

  send(payload) { if (this.handshaken) this.socket.write(encodeFrame(OP_BIN, payload)) }
  close() { try { this.socket.write(encodeFrame(OP_CLOSE)); this.socket.destroy?.() } catch {} }
}
