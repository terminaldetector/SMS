# HELIX — protocol core

First module of the from-scratch mobile-mesh inference protocol (see
`../ROADMAP_mobile_ai_mesh.md` and `../AUDIT2_hardened_and_new_protocol.md`).
This package is the **wire + security core** both tracks (LiteRT routing-mesh and
GGUF layer-shard) and every later HELIX phase build on. Pure Python standard library
only, so it loads under Chaquopy.

```bash
python -m helix.selftest             # L2 protocol core → ALL PASSED
python -m helix.transport.selftest   # L1 transport     → ALL PASSED
```

## What it is

**L2 — protocol core (wire + security)**

| Module | Responsibility |
|---|---|
| `crypto.py` | ChaCha20-Poly1305 AEAD + HKDF-SHA256 (RFC 8439 / 5869), pure stdlib. Verified against the official RFC 8439 §2.8.2 vector. |
| `sealer.py` | `Sealer` abstraction: `AeadSealer` (confidential+authenticated) / `HmacSealer` (auth-only). Keys derived from the cluster secret via HKDF. |
| `frame.py` | Versioned wire envelope `magic·flags·epoch·nonce·sealed_body`; `MAX_FRAME` bound checked before allocation. |
| `message.py` | Versioned messages with authenticated `src`, `seq`, disambiguated types (`PROMPT_TOKEN` vs `SHARD_TOKEN`). |
| `session.py` | `FrameCodec` (seal/open) + `ReplayGuard` (per-sender sliding-window anti-replay). |

**L1 — transport (move opaque frame bytes)**

| Module | Responsibility |
|---|---|
| `transport/base.py` | `Transport` ABC. Inbound handler gets **only the frame** — identity is authenticated inside it, never resolved from the transport. |
| `transport/memory.py` | `InMemoryTransport` — process-local, correct self-delivery. |
| `transport/wifi.py` | `WifiTransport` — UDP-beacon discovery + length-prefixed TCP over any IP link (Wi-Fi Aware/Direct/LAN/USB-tether). Bounded reads, authenticated beacons, correct self-delivery. |
| `endpoint.py` | `Endpoint` — the L1↔L2 seam: seals typed messages out, opens authenticated messages in. |

## How it answers the audit

| Finding (AUDIT2) | Resolved by |
|---|---|
| 2.1 self-delivery `from_node=""`, 2.4 identity-by-IP, #11 | **`msg.src` is authenticated inside the frame** — the sender identity no longer comes from the transport at all |
| 2.2 plaintext data path (lost confidentiality) | `AeadSealer` (ChaCha20-Poly1305) — bodies are encrypted, not just HMAC'd |
| 2.3 unbounded TCP length | `MAX_FRAME` enforced **before** allocation on `open`, and on `seal` |
| #3 (partial) ordering/dedup/replay | per-sender `seq` + `ReplayGuard` sliding window; verbatim frame replay is dropped |
| #7 no version / `TOKEN` overload | `v` protocol version + split `PROMPT_TOKEN`/`SHARD_TOKEN` |
| 2.5 rekey on churn (foundation) | `epoch` in the authenticated header; `FrameCodec.rekey()` for group-key changes |

## Design notes

- **Reference vs host crypto.** The pure-Python AEAD is correct (RFC-vector-checked)
  and fine for the control plane and tests, but too slow to encrypt every data-plane
  activation on a phone. `Sealer` is an interface so the host can plug a native AEAD
  for the hot path — the same "reference-in-Python, fast-in-host" split exo-core uses
  for inference and transport.
- **Not yet included (next phases):** per-node asymmetric identity + MLS-style group
  ratchet (this layer currently uses a shared cluster secret, so it authenticates
  *membership*, not *which member*); capability attestation; the coded activation
  data plane. Those are Phase 5 / HELIX ②③④ in the roadmap and layer on top of this
  core without changing the frame contract.
