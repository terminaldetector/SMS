// HELIX FrameCodec + stream framing — JS runtime for the ChatterUI *Level 2* agent.
//
// Builds on the proven wire codec (helix_codec.mjs, 16/16 vs vectors.json) and adds the two
// runtime pieces an agent needs on a real socket:
//   - FrameCodec: seal(type,body,tid) with a per-sender seq; open(wire) with epoch check,
//     AEAD auth, message parse, and sliding-window anti-replay (mirrors helix/session.py).
//   - stream framing: uint32-BE length prefix over a byte stream (mirrors
//     helix/transport/framing.py + stream.py), including the node-id handshake.
//
// Verified end-to-end against the real Python coordinator by ./agent_smoke.mjs.

import {
  MAGIC, FLAG_CONFIDENTIAL, encodeHeader, aeadSeal, aeadOpen, serializeMessage, sealerKey,
} from "./helix_codec.mjs";

export const HEADER_LEN = 21;
export const NONCE_LEN = 12;
export const MAX_FRAME = 16 * 1024 * 1024;
export const PROTOCOL_VERSION = 1;

// Message types used by the agent worker (Track A) and the shard ring (Track B). See
// helix/message.py MsgType.
export const Msg = {
  // Track A (agent coordination)
  AGENT_ANNOUNCE: "AGENT_ANNOUNCE", STATUS: "STATUS", TASK: "TASK",
  PARTIAL: "PARTIAL", RESULT: "RESULT", VOTE: "VOTE", DECIDE: "DECIDE",
  // Track B (layer-shard ring)
  FEED: "FEED", ACTIVATION: "ACTIVATION", SHARD_TOKEN: "SHARD_TOKEN",
};

// Parse the sealed plaintext back into a message (mirrors helix/message.py Message.parse).
export function parseMessage(plaintext) {
  let obj;
  try { obj = JSON.parse(Buffer.from(plaintext).toString("utf8")); } catch { return null; }
  if (typeof obj !== "object" || obj === null) return null;
  if (obj.v !== PROTOCOL_VERSION) return null;
  if (typeof obj.t !== "string" || typeof obj.src !== "string" || !obj.src) return null;
  const seq = obj.seq ?? 0;
  if (!Number.isInteger(seq) || seq < 0) return null;
  const b = obj.b ?? {};
  if (typeof b !== "object" || b === null) return null;
  return { v: obj.v, type: obj.t, src: obj.src, seq, tid: String(obj.tid ?? ""), body: b };
}

// Per-sender sliding-window anti-replay (window 64), same rule as helix/session.py ReplayGuard.
class ReplayGuard {
  constructor(window = 64) { this._w = window; this._st = new Map(); }
  accept(src, seq) {
    let st = this._st.get(src);
    if (!st) { st = { hi: 0, bm: 0n }; this._st.set(src, st); }
    const full = (1n << BigInt(this._w)) - 1n;
    if (seq > st.hi) {
      const shift = seq - st.hi;
      st.bm = shift >= this._w ? 1n : ((st.bm << BigInt(shift)) | 1n) & full;
      st.hi = seq;
      return true;
    }
    const off = st.hi - seq;
    if (off >= this._w) return false;
    const mask = 1n << BigInt(off);
    if (st.bm & mask) return false;
    st.bm |= mask;
    return true;
  }
}

export class FrameCodec {
  constructor(nodeId, key, { epoch = 0, replay = true } = {}) {
    this.nodeId = nodeId;
    this._key = key;
    this._epoch = epoch;
    this._seq = 0;
    this._replay = replay ? new ReplayGuard() : null;
  }

  static fromClusterSecret(nodeId, secret, opts) {
    return new FrameCodec(nodeId, sealerKey(secret), opts);
  }

  seal(type, body = {}, tid = "") {
    this._seq += 1;
    const nonce = Buffer.alloc(NONCE_LEN);
    require_crypto_random(nonce);
    const header = encodeHeader(FLAG_CONFIDENTIAL, this._epoch, nonce);
    const pt = serializeMessage({ v: PROTOCOL_VERSION, t: type, seq: this._seq, src: this.nodeId, tid, b: body });
    return Buffer.concat([header, aeadSeal(this._key, nonce, pt, header)]);
  }

  open(wire) {
    if (wire.length < HEADER_LEN || wire.length > MAX_FRAME) return null;
    if (!wire.subarray(0, 4).equals(MAGIC)) return null;
    const flags = wire.readUInt8(4);
    const epoch = wire.readUInt32LE(5);
    if (epoch !== this._epoch) return null;
    const nonce = wire.subarray(9, 21);
    const header = encodeHeader(flags, epoch, nonce);
    const pt = aeadOpen(this._key, nonce, wire.subarray(21), header);
    if (!pt) return null;
    const msg = parseMessage(pt);
    if (!msg) return null;
    if (this._replay && !this._replay.accept(msg.src, msg.seq)) return null;
    return msg;
  }
}

import crypto from "node:crypto";
function require_crypto_random(buf) { crypto.randomFillSync(buf); }

// --- stream framing (helix/transport/framing.py): uint32 BE length || frame ---
export function frameOut(frame) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(frame.length, 0);
  return Buffer.concat([len, frame]);
}

// Buffers a byte stream and yields complete frames; the first frame is the node-id handshake.
export class StreamFramer {
  constructor(maxFrame = MAX_FRAME) { this._buf = Buffer.alloc(0); this._max = maxFrame; }
  push(chunk) {
    this._buf = Buffer.concat([this._buf, chunk]);
    const out = [];
    while (this._buf.length >= 4) {
      const len = this._buf.readUInt32BE(0);
      if (len === 0 || len > this._max) { out.push({ bad: true }); this._buf = Buffer.alloc(0); break; }
      if (this._buf.length < 4 + len) break;
      out.push({ frame: this._buf.subarray(4, 4 + len) });
      this._buf = this._buf.subarray(4 + len);
    }
    return out;
  }
}
