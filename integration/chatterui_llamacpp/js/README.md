# HELIX JS codec — the ChatterUI wire-compat gate (green)

The riskiest part of the ChatterUI **Level 2 (agent)** integration is proving a TS/JS
implementation can speak HELIX's wire byte-for-byte. This folder proves it — in plain Node
`crypto`, no native module — against the same `helix/spec/vectors.json` the Python reference is
pinned to.

- **`helix_codec.mjs`** — JS reference of the full HELIX wire: HKDF-SHA256 (all-zero salt),
  ChaCha20-Poly1305 AEAD (`sealed = ct||tag`), frame header (`magic|flags|epoch(LE)|nonce`),
  compact message JSON (`{v,t,seq,src[,tid][,b]}`, insertion order), Ed25519 (seed→PKCS8 DER),
  `node_id = "hlx1"+sha256(pub)[:20]`, canonical signed claims, and the routing envelopes.
- **`conformance.mjs`** — loads `helix/spec/vectors.json` and asserts the codec reproduces every
  field, plus an AEAD open round-trip.

## Run

```bash
node integration/chatterui_llamacpp/js/conformance.mjs
# -> ALL PASSED (16 checks) — JS codec is wire-compatible with the Python reference.
```

## What this de-risks

The crypto + framing surface — the part a `SecurityBridge`/`FrameCodec` must get exactly right —
is now demonstrated reproducible in JS. Node's `crypto` provides all three primitives natively;
on-device, `react-native-quick-crypto` (JSI) or a shared Rust/C core exposes the same three, so
the same vectors are the acceptance gate. What remains for Level 2 is **transport** (RN TCP/UDP)
and the **agent worker** (on `TASK` → run the model → stream `PARTIAL` → `RESULT`/`VOTE`) — plumbing
over this proven codec, not new wire design.

> `.mjs` is a reference/gate, not the shipped module. The shipped ChatterUI client is TS over a
> native `SecurityBridge` (see `../helix.ts`); these vectors are its conformance test.
