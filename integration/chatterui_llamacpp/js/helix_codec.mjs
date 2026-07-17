// HELIX wire codec — JavaScript reference (Node's crypto), proving the ChatterUI/TS port is
// wire-compatible with the Python reference. Uses only standard primitives (ChaCha20-Poly1305,
// Ed25519, HKDF-SHA256), so any platform crypto lib (react-native-quick-crypto / JSI) can match.
//
// Byte layouts mirror helix/frame.py, helix/message.py, helix/sealer.py, helix/identity.py,
// helix/mesh/router.py. Verified against helix/spec/vectors.json by ./conformance.mjs.

import crypto from "node:crypto";

const te = new TextEncoder();

// --- HKDF-SHA256 (RFC 5869); HELIX uses an all-zero 32-byte salt (see helix/crypto.py) ---
export function hkdf(secret, info, length = 32) {
  const salt = Buffer.alloc(32, 0);
  return Buffer.from(crypto.hkdfSync("sha256", Buffer.from(secret), salt, Buffer.from(info), length));
}

export function sealerKey(clusterSecret, label = "helix/1 aead key") {
  return hkdf(te.encode(clusterSecret), te.encode(label), 32);
}

// --- ChaCha20-Poly1305 AEAD: sealed = ciphertext || tag(16) ---
export function aeadSeal(key, nonce, plaintext, aad) {
  const c = crypto.createCipheriv("chacha20-poly1305", key, nonce, { authTagLength: 16 });
  c.setAAD(Buffer.from(aad));
  const ct = Buffer.concat([c.update(Buffer.from(plaintext)), c.final()]);
  return Buffer.concat([ct, c.getAuthTag()]);
}

export function aeadOpen(key, nonce, sealed, aad) {
  const ct = sealed.subarray(0, sealed.length - 16);
  const tag = sealed.subarray(sealed.length - 16);
  const d = crypto.createDecipheriv("chacha20-poly1305", key, nonce, { authTagLength: 16 });
  d.setAAD(Buffer.from(aad));
  d.setAuthTag(tag);
  try {
    return Buffer.concat([d.update(ct), d.final()]);
  } catch {
    return null; // tag mismatch
  }
}

// --- Frame header: magic(4) | flags(1) | epoch(4 LE) | nonce(12)  (helix/frame.py) ---
export const MAGIC = Buffer.from("HLX1");
export const FLAG_CONFIDENTIAL = 0x01;

export function encodeHeader(flags, epoch, nonce) {
  const h = Buffer.alloc(21);
  MAGIC.copy(h, 0);
  h.writeUInt8(flags & 0xff, 4);
  h.writeUInt32LE(epoch >>> 0, 5);
  Buffer.from(nonce).copy(h, 9);
  return h;
}

export function sealFrame(key, flags, epoch, nonce, plaintext) {
  const header = encodeHeader(flags, epoch, nonce);
  return Buffer.concat([header, aeadSeal(key, nonce, plaintext, header)]);
}

// --- Message: compact JSON {v,t,seq,src[,tid][,b]} in that order (helix/message.py) ---
export function serializeMessage(m) {
  const obj = { v: m.v ?? 1, t: m.t, seq: m.seq ?? 0, src: m.src };
  if (m.tid) obj.tid = m.tid;
  if (m.b && Object.keys(m.b).length) obj.b = m.b;
  return Buffer.from(JSON.stringify(obj), "utf8"); // JSON.stringify = no spaces, insertion order
}

// --- Ed25519 (helix/identity.py) via PKCS8/SPKI DER wrappers around raw keys ---
const PKCS8_PREFIX = Buffer.from("302e020100300506032b657004220420", "hex");

function privFromSeed(seed) {
  return crypto.createPrivateKey({ key: Buffer.concat([PKCS8_PREFIX, Buffer.from(seed)]),
                                   format: "der", type: "pkcs8" });
}

export function ed25519Public(seed) {
  const pub = crypto.createPublicKey(privFromSeed(seed)).export({ format: "jwk" });
  return Buffer.from(pub.x, "base64url");
}

export function ed25519Sign(seed, msg) {
  return crypto.sign(null, Buffer.from(msg), privFromSeed(seed));
}

export function deriveNodeId(pub) {
  return "hlx1" + crypto.createHash("sha256").update(pub).digest("hex").slice(0, 20);
}

// canonical JSON for signed claims: sorted keys, compact (helix/identity.py _canonical)
export function canonicalStringify(obj) {
  const keys = Object.keys(obj).sort();
  return "{" + keys.map((k) => JSON.stringify(k) + ":" + JSON.stringify(obj[k])).join(",") + "}";
}

export function signClaim(seed, fields) {
  return ed25519Sign(seed, Buffer.from(canonicalStringify(fields), "utf8"));
}

// --- Routing envelope (helix/mesh/router.py) ---
export function encData(ttl, dst, frame) {
  const d = Buffer.from(dst, "utf8");
  const head = Buffer.alloc(4);
  head.writeUInt8(0, 0);
  head.writeUInt8(ttl & 0xff, 1);
  head.writeUInt16BE(d.length, 2);
  return Buffer.concat([head, d, Buffer.from(frame)]);
}

export function encPresence(ttl, origin) {
  return Buffer.concat([Buffer.from([1, ttl & 0xff]), Buffer.from(origin, "utf8")]);
}
