// HELIX crypto for ChatterUI (Level 2) — PURE JS via @noble, no native module.
//
// Proven wire-compatible with helix/spec/vectors.json by the .mjs twin
// (integration/chatterui_llamacpp/js/helix_codec_noble.mjs + conformance_noble.mjs, 19/19).
// React-Native-safe: Uint8Array throughout (no Node Buffer), bytes/hex/utf8 via @noble utils.
//
// Add to ChatterUI: npm i @noble/ciphers @noble/curves @noble/hashes

import { chacha20poly1305 } from '@noble/ciphers/chacha'
import { ed25519 } from '@noble/curves/ed25519'
import { hkdf as nobleHkdf } from '@noble/hashes/hkdf'
import { sha256 } from '@noble/hashes/sha2'
import { bytesToHex, concatBytes, randomBytes, utf8ToBytes } from '@noble/hashes/utils'

export const NONCE_LEN = 12
export const FLAG_CONFIDENTIAL = 0x01
const MAGIC = utf8ToBytes('HLX1')

// --- HKDF-SHA256 (RFC 5869); HELIX uses an all-zero 32-byte salt ---
export function hkdf(secret: Uint8Array, info: Uint8Array, length = 32): Uint8Array {
    return nobleHkdf(sha256, secret, new Uint8Array(32), info, length)
}

export function sealerKey(clusterSecret: string, label = 'helix/1 aead key'): Uint8Array {
    return hkdf(utf8ToBytes(clusterSecret), utf8ToBytes(label), 32)
}

// --- ChaCha20-Poly1305 AEAD: sealed = ciphertext || tag(16) ---
export function aeadSeal(key: Uint8Array, nonce: Uint8Array, pt: Uint8Array, aad: Uint8Array): Uint8Array {
    return chacha20poly1305(key, nonce, aad).encrypt(pt)
}

export function aeadOpen(key: Uint8Array, nonce: Uint8Array, sealed: Uint8Array, aad: Uint8Array): Uint8Array | null {
    try {
        return chacha20poly1305(key, nonce, aad).decrypt(sealed)
    } catch {
        return null
    }
}

// --- Frame header: magic(4) | flags(1) | epoch(4 LE) | nonce(12) ---
export function encodeHeader(flags: number, epoch: number, nonce: Uint8Array): Uint8Array {
    const h = new Uint8Array(21)
    h.set(MAGIC, 0)
    h[4] = flags & 0xff
    new DataView(h.buffer).setUint32(5, epoch >>> 0, true) // little-endian
    h.set(nonce, 9)
    return h
}

// --- Ed25519 ---
export function ed25519Public(seed: Uint8Array): Uint8Array {
    return ed25519.getPublicKey(seed)
}
export function ed25519Sign(seed: Uint8Array, msg: Uint8Array): Uint8Array {
    return ed25519.sign(msg, seed)
}
export function ed25519Verify(pub: Uint8Array, msg: Uint8Array, sig: Uint8Array): boolean {
    try {
        return ed25519.verify(sig, msg, pub)
    } catch {
        return false
    }
}
export function deriveNodeId(pub: Uint8Array): string {
    return 'hlx1' + bytesToHex(sha256(pub)).slice(0, 20)
}

// canonical JSON for signed claims: sorted keys, compact
export function canonicalStringify(obj: Record<string, unknown>): string {
    const keys = Object.keys(obj).sort()
    return '{' + keys.map((k) => JSON.stringify(k) + ':' + JSON.stringify(obj[k])).join(',') + '}'
}
export function signClaim(seed: Uint8Array, fields: Record<string, unknown>): Uint8Array {
    return ed25519Sign(seed, utf8ToBytes(canonicalStringify(fields)))
}

export { bytesToHex, concatBytes, randomBytes, utf8ToBytes }
