# HELIX — protocol core

First module of the from-scratch mobile-mesh inference protocol (see
`../ROADMAP_mobile_ai_mesh.md` and `../AUDIT2_hardened_and_new_protocol.md`).
This package is the **wire + security core** both tracks (LiteRT routing-mesh and
GGUF layer-shard) and every later HELIX phase build on. Pure Python standard library
only, so it loads under Chaquopy.

```bash
python -m helix.selftest             # L2 protocol core → ALL PASSED
python -m helix.transport.selftest   # L1 transport     → ALL PASSED
python -m helix.transport.stream_selftest # USB byte-stream → ALL PASSED
python -m helix.mesh.router_selftest # routing (star/ring) → ALL PASSED
python -m helix.host.control_server  # PowerShell control  → ALL PASSED
python -m helix.control_selftest     # L3 control plane → ALL PASSED
python -m helix.pipeline_selftest     # L4 data plane   → ALL PASSED
python -m helix.orchestrator_selftest # L5 sessions     → ALL PASSED
python -m helix.agent.selftest        # Track A agents  → ALL PASSED
python -m helix.agent.context_selftest # CONTEXT_SYNC    → ALL PASSED
python -m helix.identity_selftest     # ④ per-node id   → ALL PASSED
python -m helix.agent.secure_selftest # ④ signed votes  → ALL PASSED
python -m helix.attest_selftest       # ③ attestation   → ALL PASSED
python -m helix.super.selftest        # superagent mode → ALL PASSED
python -m helix.conformance --check   # wire vectors    → matches vectors.json
```

**Native integration:** the Python package is the reference + conformance oracle. Native
ports (Kotlin/LiteRT for edge, C++/TS/llama.cpp for the ChatterUI fork) implement the
language-neutral contract in [`../HELIX_WIRE_SPEC.md`](../HELIX_WIRE_SPEC.md) and prove
byte-compatibility against `spec/vectors.json` (`python -m helix.conformance`). One wire,
two runners (`ShardRunner`↔llama.cpp RPC, `AgentRunner`↔LiteRT) — a mode toggle in the UI.

## Two tracks on one substrate

HELIX L1–L2 (transport + security) is the shared substrate. On top of it run **two
interchangeable tracks** — this is what makes it a universal protocol for both llama-style
sharding and edge multi-agent:

| Track | What crosses the wire | Layers |
|---|---|---|
| **B — llama sharding** | hidden-state tensors (one model split across devices) | L3 leases (`control.py`) → L4 ring (`pipeline.py`) → L5 sessions (`orchestrator.py`) |
| **A — edge agents (Pointer)** | tasks & results (whole model per device = one agent) | `agent/` — registry + coordinator modes |

Both reuse the same authenticated/encrypted/replay-safe frames, discovery, membership,
liveness and self-healing.

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

**L3 — control plane (membership, placement, leases, liveness)**

| Module | Responsibility |
|---|---|
| `placement.py` | Memory-weighted ring placement with a **per-node RAM-fit check** (closes AUDIT #8, which exo-core only checked on the ring total). |
| `roster.py` | Membership + liveness from `ANNOUNCE`/`HEARTBEAT`; injectable clock; `prune()` returns the lost set. |
| `control.py` | `ControlNode` — member (announce/heartbeat, accept `LEASE`, reply `LEASE_ACK`) and coordinator (`place()` → issue leases, collect acks). Layer **leases** (TTL + `term`) replace one-shot `ASSIGN`; `on_node_lost` is the self-healing hook. Coordinator identity is the authenticated `msg.src`. |

**L4 — data plane (the inference ring)**

| Module | Responsibility |
|---|---|
| `shard.py` | `ShardRunner` contract (what GGUF/llama.cpp-RPC or ONNX implements) + `NumericShardRunner` reference. Hidden state crosses as a numeric vector so it can be compressed. |
| `activation.py` | `ActivationCodec`: `RawActivationCodec` (exact) / `Int8ActivationCodec` (per-tensor int8, ~7× smaller wire; the pure-Python half of HELIX ①). FEC plugs in behind the same interface. |
| `pipeline.py` | `ShardPipeline` — drives one node's role in the layer-shard ring over an `Endpoint`. **Coordinator-driven**: the last shard returns each token to the coordinator, which decides termination. Monotonic per-task step guard (no double-advance). Ring/band/coordinator come from the L3 lease. |

**L5 — sessions & orchestration (self-healing generation)**

| Module | Responsibility |
|---|---|
| `orchestrator.py` | `Orchestrator` — node-level conductor: keeps the L3 control node alive, (re)builds its L4 pipeline on every lease, and (as coordinator) drives a `Session` — seeds the ring, streams tokens, decides termination. On a lost node it **re-places over survivors and resumes from the last produced token** (HELIX ② healing). `Session` streams `(step, token, text)` and ends with an explicit `SessionStatus` (COMPLETED / TIMEOUT / FAILED), closing the AUDIT #5 silent-truncation gap. |

**Track A — edge agent coordination (Pointer)**

| Module | Responsibility |
|---|---|
| `agent/card.py` | `AgentCard` — capability advertisement (local analogue of an A2A Agent Card), carried in `AGENT_ANNOUNCE`. |
| `agent/runner.py` | `AgentRunner` contract (a whole LiteRT model = one agent) + `EchoAgentRunner` reference. |
| `agent/registry.py` | `AgentRegistry` — capabilities + free/busy status + liveness; matches a task to a capable agent. |
| `agent/node.py` | `AgentNode` — worker (runs its model on `TASK`, streams `PARTIAL`→`RESULT`/`VOTE`) + coordinator with four modes (**single / parallel / voting / pipeline**) and **re-route healing** (a lost agent's task is idempotently re-assigned). Votes are one-per-authenticated-node (Sybil note in `../POINTER_protocol.md`). |
| `agent/context.py` | `ContextLog` — shared conversation as an **op-based CRDT** (Lamport-ordered append-only set) synced by `CONTEXT_SYNC` **deltas**. Entry `author` must equal the authenticated frame `src` (**provenance** — blocks context prompt-injection). Large/repeated content is **content-addressed** (`CONTEXT_BLOB` travels once, cited by SHA-256 ref; missing refs pulled via `CONTEXT_PULL`). |

**④ Per-node identity (cryptographic attribution)**

| Module | Responsibility |
|---|---|
| `identity.py` | **Ed25519** (RFC 8032, pure stdlib, verified against the RFC test-1 vector) + `NodeIdentity` with **self-certifying ids** (`node_id = f(public_key)`) + `Keyring` (TOFU, binding-enforced). The group secret proves *membership*; a per-node signature proves *which member* — upgrading one-node-one-vote and context provenance from "a member" to "this specific node", so an insider who knows the group secret still cannot forge a vote or a turn as another node. Full Sybil resistance additionally needs cluster admission / attestation (③). |
| `agent/node.py` (④ mode) | With an optional `identity`, `AgentNode` signs each `VOTE` and `CONTEXT_SYNC` entry and verifies incoming ones against a `Keyring` bootstrapped from `AGENT_ANNOUNCE` public keys — cryptographic attribution end-to-end. Opt-in: without an identity, behaviour is unchanged. |

**③ Capability attestation (prove capacity, don't declare it)**

| Module | Responsibility |
|---|---|
| `attest.py` | Challenge → proof-of-capability → **④-signed `CapabilityCert`**. Reference proof is a memory-hard walk that binds to the claimed size (timing-based hardness; `Prover`/`Verifier` are pluggable for succinct proof-of-space / TEE later). `attested_capacities()` feeds **placement** the *proven* capacity, so a memory-liar with no valid cert is excluded — closing the audit's memory-lie and raising Sybil cost to one real proof per identity. |

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
