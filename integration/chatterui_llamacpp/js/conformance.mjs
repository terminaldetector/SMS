// Verify the JS HELIX codec reproduces helix/spec/vectors.json byte-for-byte.
//
//   node integration/chatterui_llamacpp/js/conformance.mjs
//
// Passing proves a TS/JS implementation is wire-compatible with the Python reference — the
// crux of the ChatterUI (Level 2 agent) integration.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import * as h from "./helix_codec.mjs";
import { Int8ActivationCodec } from "./activation.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const vectors = JSON.parse(fs.readFileSync(path.join(here, "../../../helix/spec/vectors.json"), "utf8"));

let pass = 0;
function eq(gotHex, wantHex, what) {
  if (gotHex !== wantHex) throw new Error(`${what}:\n  got  ${gotHex}\n  want ${wantHex}`);
  pass++;
  console.log(`  ok  ${what}`);
}

// 1. HKDF subkeys
const secret = Buffer.from(vectors.hkdf.secret_utf8, "utf8");
eq(h.hkdf(secret, Buffer.from("helix/1 aead key"), 32).toString("hex"), vectors.hkdf.aead_key, "hkdf aead_key");
eq(h.hkdf(secret, Buffer.from("helix/1 hmac key"), 32).toString("hex"), vectors.hkdf.hmac_key, "hkdf hmac_key");
eq(h.hkdf(secret, Buffer.from("helix/1 beacon key"), 32).toString("hex"), vectors.hkdf.beacon_key, "hkdf beacon_key");

// 2. AEAD — RFC 8439 anchor
{
  const v = vectors.aead_rfc8439;
  const sealed = h.aeadSeal(Buffer.from(v.key, "hex"), Buffer.from(v.nonce, "hex"),
                            Buffer.from(v.plaintext_utf8, "utf8"), Buffer.from(v.aad, "hex"));
  eq(sealed.toString("hex"), v.sealed, "aead rfc8439");
}

// 3. Message encoding
{
  const f = vectors.frame.message_fields;
  eq(h.serializeMessage({ v: f.v, t: f.t, seq: f.seq, src: f.src, tid: f.tid, b: f.b }).toString("hex"),
     vectors.frame.plaintext, "message serialize");
  for (const enc of vectors.message_encodings) {
    const fld = enc.fields;
    eq(h.serializeMessage({ t: fld.type, src: fld.src, seq: fld.seq, tid: fld.tid, b: fld.body }).toString("hex"),
       enc.bytes, `message ${enc.desc}`);
  }
}

// 4. Full sealed frame (fixed nonce)
{
  const key = h.hkdf(secret, Buffer.from("helix/1 aead key"), 32);
  const wire = h.sealFrame(key, vectors.frame.flag_confidential, vectors.frame.epoch,
                           Buffer.from(vectors.frame.nonce, "hex"), Buffer.from(vectors.frame.plaintext, "hex"));
  eq(wire.toString("hex"), vectors.frame.wire, "sealed frame wire");
}

// 5. Ed25519 — RFC 8032 anchor + node id + signed claim
{
  const e = vectors.ed25519_rfc8032;
  eq(h.ed25519Public(Buffer.from(e.seed, "hex")).toString("hex"), e.public, "ed25519 public");
  eq(h.ed25519Sign(Buffer.from(e.seed, "hex"), Buffer.from(e.message, "utf8")).toString("hex"),
     e.signature, "ed25519 signature");

  const nid = vectors.node_id;
  eq(Buffer.from(h.deriveNodeId(h.ed25519Public(Buffer.from(nid.seed, "hex"))), "utf8").toString("hex"),
     Buffer.from(nid.node_id, "utf8").toString("hex"), "node_id derivation");

  const sc = vectors.signed_claim;
  eq(h.signClaim(Buffer.from(nid.seed, "hex"), sc.fields).toString("hex"), sc.signature, "signed claim");
}

// 6. Routing envelope
{
  const d = vectors.routing_envelope.data;
  eq(h.encData(d.ttl, d.dst, Buffer.from(d.frame, "hex")).toString("hex"), d.envelope, "routing data envelope");
  const p = vectors.routing_envelope.presence;
  eq(h.encPresence(p.ttl, p.origin).toString("hex"), p.envelope, "routing presence envelope");
}

// 7. Activation codec (Track B / HELIX ①) — pinned numerically (not by JSON float formatting)
{
  const ac = vectors.activation_codec;
  const enc = new Int8ActivationCodec().encode(ac.input);
  eq(Buffer.from(enc.q, "base64").toString("hex"), Buffer.from(ac.int8.q, "base64").toString("hex"),
     "int8 activation q bytes");
  if (enc.n !== ac.int8.n) throw new Error(`int8 n: ${enc.n} != ${ac.int8.n}`);
  if (Math.abs(enc.s - ac.int8.s) > 1e-12) throw new Error(`int8 scale: ${enc.s} != ${ac.int8.s}`);
  pass += 1; console.log("  ok  int8 activation scale+len");
  const back = new Int8ActivationCodec().decode(ac.int8);
  if (!ac.input.every((x, i) => Math.abs(x - back[i]) <= ac.int8.s)) throw new Error("int8 round-trip");
  pass += 1; console.log("  ok  int8 activation round-trip within scale");
}

// round-trip sanity: open the sealed frame back
{
  const key = h.hkdf(secret, Buffer.from("helix/1 aead key"), 32);
  const wire = Buffer.from(vectors.frame.wire, "hex");
  const header = wire.subarray(0, 21);
  const opened = h.aeadOpen(key, header.subarray(9, 21), wire.subarray(21), header);
  if (!opened || opened.toString("hex") !== vectors.frame.plaintext) throw new Error("aead open round-trip");
  console.log("  ok  aead open round-trip");
  pass++;
}

console.log(`\nALL PASSED (${pass} checks) — JS codec is wire-compatible with the Python reference.`);
