// Conformance for BitChat's Noise layer (ChatterUI/lib/bitchatNoise.ts).
//
//   node integration/chatterui_llamacpp/js/bitchat_noise_smoke.mjs
//
// A handshake that only ever talks to itself will agree with its own mistakes, so the checks below
// pin the things that actually decide interop with a real BitChat peer: the protocol name (it seeds
// the handshake hash, so one wrong byte means nothing decrypts), the standard Noise HKDF and nonce
// encoding, the XX message sizes, and BitChat's 4-byte big-endian transport nonce prefix.
// Sources and constants: integration/chatterui_llamacpp/BITCHAT_BRIDGE.md.

import { hmac } from '@noble/hashes/hmac'
import { sha256 } from '@noble/hashes/sha2'
import { chacha20poly1305 } from '@noble/ciphers/chacha'
import { x25519 } from '@noble/curves/ed25519'

import {
  CipherState, DH_LEN, HandshakeState, PROTOCOL_NAME, SymmetricState, TAG_LEN, WIRE_NONCE_LEN,
  generateStaticKey, hkdfNoise,
} from '../../../ChatterUI/lib/bitchatNoise.ts'

let pass = 0
const check = (c, w) => { if (!c) throw new Error('FAIL: ' + w); pass++; console.log('  ok  ' + w) }
const eq = (a, b) => a.length === b.length && a.every((x, i) => x === b[i])
const te = new TextEncoder()

// --- the protocol name seeds the handshake hash; getting it wrong fails silently at decrypt time
check(PROTOCOL_NAME === 'Noise_XX_25519_ChaChaPoly_SHA256', 'protocol name matches BitChat')
check(te.encode(PROTOCOL_NAME).length === 32,
  'name is exactly 32 bytes, so it is used verbatim as h rather than hashed')
const sym = new SymmetricState()
check(eq(sym.h, te.encode(PROTOCOL_NAME)), 'h starts as the raw protocol name (no padding needed at 32)')
check(eq(sym.ck, sym.h), 'ck starts equal to h')

// A shorter name must be zero-padded to 32, not hashed — the other branch of the same rule.
const short = new SymmetricState('Noise_XX')
check(short.h.length === 32 && eq(short.h.subarray(0, 8), te.encode('Noise_XX')) &&
  short.h.subarray(8).every((b) => b === 0), 'short names are zero-padded to 32 bytes')

// --- HKDF must match Noise's construction exactly, computed here independently
const ck = sha256(te.encode('ck'))
const ikm = sha256(te.encode('ikm'))
const got = hkdfNoise(ck, ikm, 3)
const tempKey = hmac(sha256, ck, ikm)
const o1 = hmac(sha256, tempKey, Uint8Array.from([1]))
const o2 = hmac(sha256, tempKey, Uint8Array.from([...o1, 2]))
const o3 = hmac(sha256, tempKey, Uint8Array.from([...o2, 3]))
check(eq(got[0], o1) && eq(got[1], o2) && eq(got[2], o3), 'HKDF chains HMAC-SHA256 with a 1-byte counter')

// --- mixHash is SHA256(h || data)
const before = sym.h
sym.mixHash(te.encode('abc'))
check(eq(sym.h, sha256(Uint8Array.from([...before, ...te.encode('abc')]))), 'mixHash = SHA256(h || data)')

// --- AEAD nonce: 4 zero bytes then the counter little-endian (verified against @noble directly)
{
  const key = sha256(te.encode('k'))
  const cs = new CipherState(key)
  const ct0 = cs.encrypt(te.encode('one'))
  const ct1 = cs.encrypt(te.encode('two'))
  const n0 = new Uint8Array(12)
  const n1 = new Uint8Array(12); n1[4] = 1
  check(eq(ct0, chacha20poly1305(key, n0).encrypt(te.encode('one'))), 'first message uses counter 0')
  check(eq(ct1, chacha20poly1305(key, n1).encrypt(te.encode('two'))),
    'counter increments into byte 4 (little-endian), not byte 11')
  check(ct0.length === 3 + TAG_LEN, 'handshake ciphers add only the 16-byte tag — no nonce prefix')
}

// --- transport ciphers put a 4-byte BIG-endian nonce on the wire
{
  const key = sha256(te.encode('t'))
  const send = new CipherState(key, true)
  const recv = new CipherState(key, true)
  const w0 = send.encrypt(te.encode('hello'))
  check(w0.length === WIRE_NONCE_LEN + 5 + TAG_LEN, 'transport message = 4-byte nonce + ciphertext + tag')
  check(eq(w0.subarray(0, 4), Uint8Array.from([0, 0, 0, 0])), 'first transport nonce is 0')
  const w1 = send.encrypt(te.encode('hello'))
  check(eq(w1.subarray(0, 4), Uint8Array.from([0, 0, 0, 1])), 'nonce prefix is big-endian')
  check(new TextDecoder().decode(recv.decrypt(w0)) === 'hello', 'receiver decrypts using the wire nonce')

  // Out-of-order delivery is the reason the nonce is on the wire at all.
  const send2 = new CipherState(key, true)
  const recv2 = new CipherState(key, true)
  const a = send2.encrypt(te.encode('a')), b = send2.encrypt(te.encode('b')), c = send2.encrypt(te.encode('c'))
  recv2.decrypt(c)
  check(new TextDecoder().decode(recv2.decrypt(a)) === 'a', 'an earlier message still decrypts after a later one')
  recv2.decrypt(b)
  let replayed = false
  try { recv2.decrypt(a) } catch { replayed = true }
  check(replayed, 'replaying a message that was already seen is rejected')
}

// --- the XX handshake itself
{
  const alice = generateStaticKey(), bob = generateStaticKey()
  const hs1 = new HandshakeState(true, alice)
  const hs2 = new HandshakeState(false, bob)

  const m1 = hs1.writeMessage()
  check(m1.length === DH_LEN, 'message 1 is just the ephemeral key (payload unencrypted, empty)')
  hs2.readMessage(m1)

  const m2 = hs2.writeMessage()
  check(m2.length === DH_LEN + (DH_LEN + TAG_LEN) + TAG_LEN,
    'message 2 = e + encrypted s + encrypted payload')
  hs1.readMessage(m2)
  check(eq(hs1.remoteStatic, bob.pub), 'initiator learns the responder static key from message 2')

  const m3 = hs1.writeMessage()
  check(m3.length === (DH_LEN + TAG_LEN) + TAG_LEN, 'message 3 = encrypted s + encrypted payload')
  hs2.readMessage(m3)
  check(eq(hs2.remoteStatic, alice.pub), 'responder learns the initiator static key from message 3')

  check(hs1.isComplete() && hs2.isComplete(), 'both sides consider XX finished after three messages')
  check(eq(hs1.handshakeHash, hs2.handshakeHash), 'both sides derive the same handshake hash')

  const a = hs1.finish(), b = hs2.finish()
  const dec = new TextDecoder()
  check(dec.decode(b.recv.decrypt(a.send.encrypt(te.encode('to bob')))) === 'to bob',
    'initiator -> responder transport works')
  check(dec.decode(a.recv.decrypt(b.send.encrypt(te.encode('to alice')))) === 'to alice',
    'responder -> initiator transport works')
  check(eq(a.remoteStatic, bob.pub) && eq(b.remoteStatic, alice.pub),
    'each side ends up holding the other real static key')
}

// --- handshake payloads (BitChat carries data in them) and tamper detection
{
  const alice = generateStaticKey(), bob = generateStaticKey()
  const hs1 = new HandshakeState(true, alice)
  const hs2 = new HandshakeState(false, bob)
  const dec = new TextDecoder()
  check(dec.decode(hs2.readMessage(hs1.writeMessage(te.encode('hi')))) === 'hi',
    'message 1 carries a cleartext payload')
  check(dec.decode(hs1.readMessage(hs2.writeMessage(te.encode('yo')))) === 'yo',
    'message 2 carries an encrypted payload')

  const tampered = hs1.writeMessage(te.encode('x')).slice()
  tampered[tampered.length - 1] ^= 0xff
  let rejected = false
  try { hs2.readMessage(tampered) } catch { rejected = true }
  check(rejected, 'a modified handshake message is rejected, not silently accepted')
}

// --- a fixed ephemeral makes the whole exchange reproducible, which is what lets a future test
// compare against captured BitChat bytes
{
  const s = { secret: new Uint8Array(32).fill(7), pub: x25519.getPublicKey(new Uint8Array(32).fill(7)) }
  const run = () => {
    const hs = new HandshakeState(true, s)
    hs.setEphemeral(new Uint8Array(32).fill(9))
    return hs.writeMessage(te.encode('fixed'))
  }
  check(eq(run(), run()), 'a fixed ephemeral key yields identical message 1 bytes')
  check(eq(run().subarray(0, DH_LEN), x25519.getPublicKey(new Uint8Array(32).fill(9))),
    'message 1 begins with the raw ephemeral public key')
}

console.log(`\nALL PASSED (${pass} checks) — Noise XX + transport ciphers match BitChat's scheme.`)
