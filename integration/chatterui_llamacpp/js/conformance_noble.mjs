// Verify the PURE-JS (@noble) HELIX codec reproduces helix/spec/vectors.json byte-for-byte.
//
//   node integration/chatterui_llamacpp/js/conformance_noble.mjs
//
// Passing proves ChatterUI can do all HELIX crypto (ChaCha20-Poly1305 + Ed25519 + HKDF) in React
// Native with plain npm packages (@noble/*) — no native SecurityBridge. This unblocks Level 2
// (the phone's model as a mesh agent): the frame codec + agent worker (already proven by
// agent_smoke.mjs) can run entirely in JS.

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import * as h from './helix_codec_noble.mjs'
import { Int8ActivationCodec } from './activation.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const vectors = JSON.parse(fs.readFileSync(path.join(here, '../../../helix/spec/vectors.json'), 'utf8'))

let pass = 0
function eq(got, want, what) {
  if (got !== want) throw new Error(`${what}:\n  got  ${got}\n  want ${want}`)
  pass++
  console.log('  ok  ' + what)
}

// 1. HKDF
const secret = Buffer.from(vectors.hkdf.secret_utf8, 'utf8')
eq(h.hkdf(secret, Buffer.from('helix/1 aead key'), 32).toString('hex'), vectors.hkdf.aead_key, 'hkdf aead_key')
eq(h.hkdf(secret, Buffer.from('helix/1 hmac key'), 32).toString('hex'), vectors.hkdf.hmac_key, 'hkdf hmac_key')
eq(h.hkdf(secret, Buffer.from('helix/1 beacon key'), 32).toString('hex'), vectors.hkdf.beacon_key, 'hkdf beacon_key')

// 2. AEAD — RFC 8439 anchor
{
  const v = vectors.aead_rfc8439
  const sealed = h.aeadSeal(Buffer.from(v.key, 'hex'), Buffer.from(v.nonce, 'hex'),
    Buffer.from(v.plaintext_utf8, 'utf8'), Buffer.from(v.aad, 'hex'))
  eq(sealed.toString('hex'), v.sealed, 'aead rfc8439')
}

// 3. Message encodings (incl. FEED)
for (const enc of vectors.message_encodings) {
  const f = enc.fields
  eq(h.serializeMessage({ t: f.type, src: f.src, seq: f.seq, tid: f.tid, b: f.body }).toString('hex'),
    enc.bytes, `message ${enc.desc}`)
}
{
  const f = vectors.frame.message_fields
  eq(h.serializeMessage({ v: f.v, t: f.t, seq: f.seq, src: f.src, tid: f.tid, b: f.b }).toString('hex'),
    vectors.frame.plaintext, 'message serialize (frame)')
}

// 4. Full sealed frame
{
  const key = h.hkdf(secret, Buffer.from('helix/1 aead key'), 32)
  const wire = h.sealFrame(key, vectors.frame.flag_confidential, vectors.frame.epoch,
    Buffer.from(vectors.frame.nonce, 'hex'), Buffer.from(vectors.frame.plaintext, 'hex'))
  eq(wire.toString('hex'), vectors.frame.wire, 'sealed frame wire')
}

// 5. Ed25519 + node id + signed claim
{
  const e = vectors.ed25519_rfc8032
  eq(h.ed25519Public(Buffer.from(e.seed, 'hex')).toString('hex'), e.public, 'ed25519 public')
  eq(h.ed25519Sign(Buffer.from(e.seed, 'hex'), Buffer.from(e.message, 'utf8')).toString('hex'),
    e.signature, 'ed25519 signature')
  const nid = vectors.node_id
  eq(h.deriveNodeId(h.ed25519Public(Buffer.from(nid.seed, 'hex'))), nid.node_id, 'node_id derivation')
  const sc = vectors.signed_claim
  eq(h.signClaim(Buffer.from(nid.seed, 'hex'), sc.fields).toString('hex'), sc.signature, 'signed claim')
  // verify round-trip (the agent verifies peers' votes/context)
  const pub = h.ed25519Public(Buffer.from(nid.seed, 'hex'))
  if (!h.ed25519Verify(pub, Buffer.from(h.canonicalStringify(sc.fields)), Buffer.from(sc.signature, 'hex')))
    throw new Error('ed25519 verify round-trip')
  pass++; console.log('  ok  ed25519 verify round-trip')
}

// 6. Routing envelope
{
  const d = vectors.routing_envelope.data
  eq(h.encData(d.ttl, d.dst, Buffer.from(d.frame, 'hex')).toString('hex'), d.envelope, 'routing data envelope')
  const p = vectors.routing_envelope.presence
  eq(h.encPresence(p.ttl, p.origin).toString('hex'), p.envelope, 'routing presence envelope')
}

// 7. int8 activation codec (pure math, shared)
{
  const ac = vectors.activation_codec
  const enc = new Int8ActivationCodec().encode(ac.input)
  eq(Buffer.from(enc.q, 'base64').toString('hex'), Buffer.from(ac.int8.q, 'base64').toString('hex'), 'int8 activation q')
}

// aead open round-trip
{
  const key = h.hkdf(secret, Buffer.from('helix/1 aead key'), 32)
  const wire = Buffer.from(vectors.frame.wire, 'hex')
  const header = wire.subarray(0, 21)
  const opened = h.aeadOpen(key, header.subarray(9, 21), wire.subarray(21), header)
  if (!opened || opened.toString('hex') !== vectors.frame.plaintext) throw new Error('aead open round-trip')
  pass++; console.log('  ok  aead open round-trip')
}

console.log(`\nALL PASSED (${pass} checks) — pure-JS @noble codec is wire-compatible (no native crypto needed).`)
