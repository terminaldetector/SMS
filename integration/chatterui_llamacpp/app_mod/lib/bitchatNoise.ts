// BitChat's Noise layer — the XX handshake and the transport ciphers that carry private messages.
//
// Wire-compatible with BitChat's own implementation (bitchat/Noise/NoiseProtocol.swift):
// `Noise_XX_25519_ChaChaPoly_SHA256`, empty prologue. All primitives come from @noble, which is
// already a ChatterUI dependency and already proven byte-exact against the HELIX vectors — no
// native crypto module.
//
// Two BitChat-specific details that would silently break interop if guessed:
//   1. Transport messages put the nonce on the wire as a 4-byte BIG-endian prefix
//      (`<nonce><ciphertext||tag>`) so out-of-order delivery can be accepted, while the AEAD nonce
//      itself is the standard Noise 12-byte form (4 zero bytes + 8-byte LITTLE-endian counter).
//      Handshake messages carry no prefix.
//   2. Because of that, receivers accept out-of-order nonces within a sliding replay window rather
//      than requiring a strict counter.
//
// Proven by integration/chatterui_llamacpp/js/bitchat_noise_smoke.mjs.

import { chacha20poly1305 } from '@noble/ciphers/chacha'
import { x25519 } from '@noble/curves/ed25519'
import { hmac } from '@noble/hashes/hmac'
import { sha256 } from '@noble/hashes/sha2'

export const PROTOCOL_NAME = 'Noise_XX_25519_ChaChaPoly_SHA256'
export const DH_LEN = 32
export const TAG_LEN = 16
export const WIRE_NONCE_LEN = 4 // big-endian nonce prefix on transport messages
const REPLAY_WINDOW_SIZE = 1024 // bits, matching the reference's sliding window

const te = new TextEncoder()

function concat(...parts: Uint8Array[]): Uint8Array {
    const out = new Uint8Array(parts.reduce((s, p) => s + p.length, 0))
    let at = 0
    for (const p of parts) {
        out.set(p, at)
        at += p.length
    }
    return out
}

// Noise's HKDF: one HMAC to derive a temp key, then chained HMACs with a 1-byte counter.
export function hkdfNoise(chainingKey: Uint8Array, ikm: Uint8Array, numOutputs: number): Uint8Array[] {
    const tempKey = hmac(sha256, chainingKey, ikm)
    const outputs: Uint8Array[] = []
    // Typed against what hmac() returns: TS 5.9 distinguishes Uint8Array<ArrayBuffer> from
    // Uint8Array<ArrayBufferLike>, and `new Uint8Array(0)` alone infers the narrower one.
    let prev: Uint8Array<ArrayBufferLike> = new Uint8Array(0)
    for (let i = 1; i <= numOutputs; i++) {
        prev = hmac(sha256, tempKey, concat(prev, Uint8Array.from([i])))
        outputs.push(prev)
    }
    return outputs
}

// The AEAD nonce: 4 zero bytes then the counter, little-endian (standard Noise).
function aeadNonce(counter: bigint): Uint8Array {
    const n = new Uint8Array(12)
    let c = counter
    for (let i = 4; i < 12; i++) {
        n[i] = Number(c & 0xffn)
        c >>= 8n
    }
    return n
}

export class CipherState {
    private key: Uint8Array | null = null
    private nonce = 0n
    // Replay state, only meaningful when the nonce travels on the wire.
    private highestReceived = 0n
    private window = new Uint8Array(REPLAY_WINDOW_SIZE / 8)

    // BitChat's useExtractedNonce: transport ciphers carry the nonce, handshake ciphers don't.
    // Declared as a plain field rather than a constructor parameter property, which Node's
    // type-stripping (used by the smoke tests to import this file directly) cannot handle.
    private readonly wireNonce: boolean

    constructor(key?: Uint8Array, wireNonce = false) {
        this.wireNonce = wireNonce
        if (key) this.initializeKey(key)
    }

    initializeKey(key: Uint8Array) {
        this.key = key
        this.nonce = 0n
        this.highestReceived = 0n
        this.window = new Uint8Array(REPLAY_WINDOW_SIZE / 8)
    }

    hasKey() {
        return this.key !== null
    }

    encrypt(plaintext: Uint8Array, ad: Uint8Array = new Uint8Array(0)): Uint8Array {
        if (!this.key) throw new Error('cipher has no key')
        // The reference refuses past UInt32.max - 1, because the wire prefix is only 4 bytes.
        if (this.nonce > 0xfffffffen) throw new Error('nonce exhausted')
        const used = this.nonce
        const sealed = chacha20poly1305(this.key, aeadNonce(used), ad).encrypt(plaintext)
        this.nonce += 1n
        if (!this.wireNonce) return sealed
        const prefix = new Uint8Array(WIRE_NONCE_LEN)
        new DataView(prefix.buffer).setUint32(0, Number(used), false) // big-endian
        return concat(prefix, sealed)
    }

    decrypt(data: Uint8Array, ad: Uint8Array = new Uint8Array(0)): Uint8Array {
        if (!this.key) throw new Error('cipher has no key')
        let counter: bigint
        let sealed: Uint8Array
        if (this.wireNonce) {
            if (data.length < WIRE_NONCE_LEN) throw new Error('message too short for a nonce')
            counter = BigInt(new DataView(data.buffer, data.byteOffset, data.byteLength).getUint32(0, false))
            sealed = data.subarray(WIRE_NONCE_LEN)
            if (!this.acceptNonce(counter)) throw new Error('replayed or too-old nonce')
        } else {
            counter = this.nonce
            sealed = data
        }
        const plaintext = chacha20poly1305(this.key, aeadNonce(counter), ad).decrypt(sealed)
        if (this.wireNonce) this.markSeen(counter)
        else this.nonce += 1n
        return plaintext
    }

    // Sliding window: newer nonces always pass, older ones only if inside the window and unseen.
    private acceptNonce(n: bigint): boolean {
        const w = BigInt(REPLAY_WINDOW_SIZE)
        if (this.highestReceived >= w && n <= this.highestReceived - w) return false
        if (n > this.highestReceived) return true
        const offset = Number(this.highestReceived - n)
        return (this.window[(offset / 8) | 0] & (1 << offset % 8)) === 0
    }

    private markSeen(n: bigint) {
        if (n > this.highestReceived) {
            const shift = Number(n - this.highestReceived)
            if (shift >= REPLAY_WINDOW_SIZE) this.window.fill(0)
            else {
                // Shift the whole bitmap up by `shift` bits.
                const bytes = shift >> 3
                const bits = shift & 7
                for (let i = this.window.length - 1; i >= 0; i--) {
                    const lo = i - bytes
                    let v = lo >= 0 ? this.window[lo] << bits : 0
                    if (bits && lo - 1 >= 0) v |= this.window[lo - 1] >>> (8 - bits)
                    this.window[i] = v & 0xff
                }
            }
            this.highestReceived = n
            this.window[0] |= 1
        } else {
            const offset = Number(this.highestReceived - n)
            this.window[(offset / 8) | 0] |= 1 << offset % 8
        }
    }
}

export class SymmetricState {
    h: Uint8Array
    ck: Uint8Array
    cipher = new CipherState()

    constructor(protocolName = PROTOCOL_NAME) {
        const name = te.encode(protocolName)
        // Names of 32 bytes or less are used directly, zero-padded — not hashed.
        this.h = name.length <= 32 ? concat(name, new Uint8Array(32 - name.length)) : sha256(name)
        this.ck = this.h
    }

    mixHash(data: Uint8Array) {
        this.h = sha256(concat(this.h, data))
    }

    mixKey(ikm: Uint8Array) {
        const [ck, tempKey] = hkdfNoise(this.ck, ikm, 2)
        this.ck = ck
        this.cipher.initializeKey(tempKey)
    }

    encryptAndHash(plaintext: Uint8Array): Uint8Array {
        if (!this.cipher.hasKey()) {
            this.mixHash(plaintext)
            return plaintext
        }
        const ciphertext = this.cipher.encrypt(plaintext, this.h)
        this.mixHash(ciphertext)
        return ciphertext
    }

    decryptAndHash(ciphertext: Uint8Array): Uint8Array {
        if (!this.cipher.hasKey()) {
            this.mixHash(ciphertext)
            return ciphertext
        }
        const plaintext = this.cipher.decrypt(ciphertext, this.h)
        this.mixHash(ciphertext)
        return plaintext
    }

    // Transport keys. `wireNonce` is true for BitChat transport, so both directions expect the
    // 4-byte nonce prefix.
    split(wireNonce = true): [CipherState, CipherState] {
        const [k1, k2] = hkdfNoise(this.ck, new Uint8Array(0), 2)
        return [new CipherState(k1, wireNonce), new CipherState(k2, wireNonce)]
    }
}

export interface NoiseSession {
    send: CipherState
    recv: CipherState
    handshakeHash: Uint8Array
    remoteStatic: Uint8Array
}

// The XX handshake:
//   -> e
//   <- e, ee, s, es
//   -> s, se
// Three messages; afterwards each side knows the other's static key and holds transport ciphers.
export class HandshakeState {
    private sym: SymmetricState
    private e: { secret: Uint8Array; pub: Uint8Array } | null = null
    private re: Uint8Array | null = null
    private rs: Uint8Array | null = null
    private step = 0

    // Plain fields, not parameter properties — see the note on CipherState.
    private readonly initiator: boolean
    private readonly s: { secret: Uint8Array; pub: Uint8Array }

    constructor(
        initiator: boolean,
        s: { secret: Uint8Array; pub: Uint8Array },
        prologue: Uint8Array = new Uint8Array(0)
    ) {
        this.initiator = initiator
        this.s = s
        this.sym = new SymmetricState()
        this.sym.mixHash(prologue)
        // XX starts with no pre-messages, so nothing else is mixed in here.
    }

    private ephemeral() {
        if (!this.e) {
            const secret = x25519.utils.randomPrivateKey()
            this.e = { secret, pub: x25519.getPublicKey(secret) }
        }
        return this.e
    }

    // Test hook: fixing the ephemeral makes handshakes reproducible.
    setEphemeral(secret: Uint8Array) {
        this.e = { secret, pub: x25519.getPublicKey(secret) }
    }

    get handshakeHash() {
        return this.sym.h
    }
    get remoteStatic() {
        return this.rs
    }

    writeMessage(payload: Uint8Array = new Uint8Array(0)): Uint8Array {
        const parts: Uint8Array[] = []
        if (this.step === 0 && this.initiator) {
            // -> e
            const e = this.ephemeral()
            parts.push(e.pub)
            this.sym.mixHash(e.pub)
        } else if (this.step === 1 && !this.initiator) {
            // <- e, ee, s, es
            const e = this.ephemeral()
            parts.push(e.pub)
            this.sym.mixHash(e.pub)
            this.sym.mixKey(x25519.getSharedSecret(e.secret, this.re!)) // ee
            parts.push(this.sym.encryptAndHash(this.s.pub)) // s
            this.sym.mixKey(x25519.getSharedSecret(this.s.secret, this.re!)) // es
        } else if (this.step === 2 && this.initiator) {
            // -> s, se
            parts.push(this.sym.encryptAndHash(this.s.pub)) // s
            this.sym.mixKey(x25519.getSharedSecret(this.s.secret, this.re!)) // se
        } else {
            throw new Error(`no message to write at step ${this.step}`)
        }
        parts.push(this.sym.encryptAndHash(payload))
        this.step += 1
        return concat(...parts)
    }

    readMessage(message: Uint8Array): Uint8Array {
        let off = 0
        const take = (n: number) => {
            if (off + n > message.length) throw new Error('handshake message truncated')
            const out = message.subarray(off, off + n)
            off += n
            return out
        }

        if (this.step === 0 && !this.initiator) {
            // -> e
            this.re = take(DH_LEN)
            this.sym.mixHash(this.re)
        } else if (this.step === 1 && this.initiator) {
            // <- e, ee, s, es
            this.re = take(DH_LEN)
            this.sym.mixHash(this.re)
            this.sym.mixKey(x25519.getSharedSecret(this.e!.secret, this.re)) // ee
            this.rs = this.sym.decryptAndHash(take(DH_LEN + TAG_LEN)) // s (encrypted: key is set)
            this.sym.mixKey(x25519.getSharedSecret(this.e!.secret, this.rs)) // es
        } else if (this.step === 2 && !this.initiator) {
            // -> s, se
            this.rs = this.sym.decryptAndHash(take(DH_LEN + TAG_LEN)) // s
            this.sym.mixKey(x25519.getSharedSecret(this.e!.secret, this.rs)) // se
        } else {
            throw new Error(`no message to read at step ${this.step}`)
        }
        const payload = this.sym.decryptAndHash(message.subarray(off))
        this.step += 1
        return payload
    }

    isComplete() {
        return this.step >= 3
    }

    // After the third message. The initiator's first transport cipher is the responder's second,
    // so each side swaps them.
    finish(): NoiseSession {
        if (!this.isComplete()) throw new Error('handshake is not finished')
        if (!this.rs) throw new Error('no remote static key')
        const [c1, c2] = this.sym.split()
        return {
            send: this.initiator ? c1 : c2,
            recv: this.initiator ? c2 : c1,
            handshakeHash: this.sym.h,
            remoteStatic: this.rs,
        }
    }
}

export function generateStaticKey(): { secret: Uint8Array; pub: Uint8Array } {
    const secret = x25519.utils.randomPrivateKey()
    return { secret, pub: x25519.getPublicKey(secret) }
}
