// HELIX wire codec — pure-JS via @noble (NO native crypto). This is the crux de-risk for the
// ChatterUI *Level 2* agent: it proves every HELIX crypto primitive (ChaCha20-Poly1305, Ed25519,
// HKDF-SHA256) runs in React Native with plain npm packages — no native SecurityBridge / JSI /
// TurboModule needed. Same export surface as helix_codec.mjs (which uses node:crypto); verified
// against helix/spec/vectors.json by ./conformance_noble.mjs.
//
// The app ships the TS twin app_mod/lib/helixCrypto.ts (identical logic + types).

import { chacha20poly1305 } from '@noble/ciphers/chacha'
import { hkdf as nobleHkdf } from '@noble/hashes/hkdf'
import { sha256 } from '@noble/hashes/sha2'
import { ed25519 } from '@noble/curves/ed25519'

const te = new TextEncoder()
const buf = (u8) => Buffer.from(u8.buffer, u8.byteOffset, u8.byteLength)

// --- HKDF-SHA256 (RFC 5869); HELIX uses an all-zero 32-byte salt ---
export function hkdf(secret, info, length = 32) {
  return buf(nobleHkdf(sha256, Uint8Array.from(secret), new Uint8Array(32), Uint8Array.from(info), length))
}

export function sealerKey(clusterSecret, label = 'helix/1 aead key') {
  return hkdf(te.encode(clusterSecret), te.encode(label), 32)
}

// --- ChaCha20-Poly1305 AEAD: sealed = ciphertext || tag(16) ---
export function aeadSeal(key, nonce, plaintext, aad) {
  return buf(chacha20poly1305(Uint8Array.from(key), Uint8Array.from(nonce), Uint8Array.from(aad))
    .encrypt(Uint8Array.from(plaintext)))
}

export function aeadOpen(key, nonce, sealed, aad) {
  try {
    return buf(chacha20poly1305(Uint8Array.from(key), Uint8Array.from(nonce), Uint8Array.from(aad))
      .decrypt(Uint8Array.from(sealed)))
  } catch {
    return null // tag mismatch
  }
}

// --- Frame header: magic(4) | flags(1) | epoch(4 LE) | nonce(12) ---
export const MAGIC = Buffer.from('HLX1')
export const FLAG_CONFIDENTIAL = 0x01

export function encodeHeader(flags, epoch, nonce) {
  const h = Buffer.alloc(21)
  MAGIC.copy(h, 0)
  h.writeUInt8(flags & 0xff, 4)
  h.writeUInt32LE(epoch >>> 0, 5)
  Buffer.from(nonce).copy(h, 9)
  return h
}

export function sealFrame(key, flags, epoch, nonce, plaintext) {
  const header = encodeHeader(flags, epoch, nonce)
  return Buffer.concat([header, aeadSeal(key, nonce, plaintext, header)])
}

// --- Message: compact JSON {v,t,seq,src[,tid][,b]} in that order ---
export function serializeMessage(m) {
  const obj = { v: m.v ?? 1, t: m.t, seq: m.seq ?? 0, src: m.src }
  if (m.tid) obj.tid = m.tid
  if (m.b && Object.keys(m.b).length) obj.b = m.b
  return Buffer.from(JSON.stringify(obj), 'utf8')
}

// --- Ed25519 ---
export function ed25519Public(seed) {
  return buf(ed25519.getPublicKey(Uint8Array.from(seed)))
}

export function ed25519Sign(seed, msg) {
  return buf(ed25519.sign(Uint8Array.from(msg), Uint8Array.from(seed)))
}

export function ed25519Verify(pub, msg, sig) {
  try {
    return ed25519.verify(Uint8Array.from(sig), Uint8Array.from(msg), Uint8Array.from(pub))
  } catch {
    return false
  }
}

export function deriveNodeId(pub) {
  return 'hlx1' + Buffer.from(sha256(Uint8Array.from(pub))).toString('hex').slice(0, 20)
}

export function canonicalStringify(obj) {
  const keys = Object.keys(obj).sort()
  return '{' + keys.map((k) => JSON.stringify(k) + ':' + JSON.stringify(obj[k])).join(',') + '}'
}

export function signClaim(seed, fields) {
  return ed25519Sign(seed, Buffer.from(canonicalStringify(fields), 'utf8'))
}

// --- Routing envelope ---
export function encData(ttl, dst, frame) {
  const d = Buffer.from(dst, 'utf8')
  const head = Buffer.alloc(4)
  head.writeUInt8(0, 0)
  head.writeUInt8(ttl & 0xff, 1)
  head.writeUInt16BE(d.length, 2)
  return Buffer.concat([head, d, Buffer.from(frame)])
}

export function encPresence(ttl, origin) {
  return Buffer.concat([Buffer.from([1, ttl & 0xff]), Buffer.from(origin, 'utf8')])
}
