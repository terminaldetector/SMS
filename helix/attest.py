"""Capability attestation (HELIX ③) — prove capacity instead of self-declaring it.

The audit's memory-lie stands as long as a node just *announces* its RAM: claim a huge
number, win placement, then OOM/stall the ring. Per-node identity (④) stops spoofing
another node, not lying about your own capacity. Attestation replaces the claim with a
**proof**:

1. The node announces a claimed capacity.
2. A verifier (coordinator / admission authority) issues a **challenge** for that capacity.
3. The node returns a **proof-of-capability** it can only produce (in time) if it really
   has the resource.
4. The verifier checks it and issues a **capability certificate** signed with its ④ key.
5. **Placement and admission use the certified capacity, not the raw claim.** A node with
   no valid cert gets no placement — closing the memory-lie at the protocol level, and
   raising the cost of Sybil identities from ~free to one real resource-proof each.

**Reference proof = memory-hard challenge (timing-based).** A challenge-seeded buffer is
built as a hash chain of ``claimed/32`` words; a pseudo-random walk over it produces a
digest. Answering quickly needs the whole buffer resident; a device without the RAM must
recompute chain values on the fly (O(N) per step) and misses the deadline. The digest also
**binds the proof to the claimed size** — a proof computed for a smaller buffer verifies to
a different value and is rejected (checked functionally in the self-test; the memory-hardness
itself is the timing argument).

The :class:`Prover`/:class:`Verifier` split is deliberate so a production build can swap in a
**succinct proof-of-space** (Merkle / depth-robust graphs) or **hardware/TEE attestation**
without changing the message flow or the certificate.
"""

from __future__ import annotations

import hashlib
import os
import time
from dataclasses import dataclass
from typing import Any, Dict, List, Mapping, Optional

from helix.identity import Keyring, NodeIdentity

WORD = 32  # bytes per buffer word (SHA-256 digest)


def _sha(*parts: bytes) -> bytes:
    h = hashlib.sha256()
    for p in parts:
        h.update(p)
    return h.digest()


def memory_walk(challenge: bytes, n_words: int, steps: int) -> bytes:
    """Digest of a pseudo-random walk over a challenge-seeded hash-chain buffer.

    Building the buffer touches ``n_words * 32`` bytes; the walk's random access forces it
    to be resident to answer within a deadline. The result depends on ``n_words``, so it
    binds the proof to the claimed capacity.
    """
    if n_words < 1:
        n_words = 1
    buf: List[bytes] = [b""] * n_words
    buf[0] = _sha(challenge)
    for i in range(1, n_words):
        buf[i] = _sha(buf[i - 1], i.to_bytes(4, "big"))
    pos = int.from_bytes(_sha(challenge, b"walk")[:8], "big") % n_words
    acc = _sha(challenge, b"acc")
    for _ in range(steps):
        acc = _sha(acc, buf[pos])
        pos = int.from_bytes(buf[pos][:8], "big") % n_words
    return acc


@dataclass
class Challenge:
    chal: str          # hex nonce
    claimed_bytes: int
    steps: int = 64

    @property
    def n_words(self) -> int:
        return max(1, self.claimed_bytes // WORD)

    def to_body(self) -> Dict[str, Any]:
        return {"chal": self.chal, "claimed": self.claimed_bytes, "steps": self.steps}

    @staticmethod
    def from_body(b: Dict[str, Any]) -> "Challenge":
        return Challenge(str(b["chal"]), int(b["claimed"]), int(b.get("steps", 64)))


@dataclass
class Proof:
    acc: str           # hex digest
    elapsed_s: float

    def to_body(self) -> Dict[str, Any]:
        return {"acc": self.acc, "elapsed": self.elapsed_s}

    @staticmethod
    def from_body(b: Dict[str, Any]) -> "Proof":
        return Proof(str(b["acc"]), float(b.get("elapsed", 0.0)))


@dataclass
class CapabilityCert:
    """A signed statement that ``subject`` demonstrated ``capacity_bytes`` (④-signed)."""

    subject: str
    capacity_bytes: int
    expires: float
    issuer: str = ""
    sig: str = ""

    def _fields(self) -> Dict[str, Any]:
        return {"kind": "cap", "subject": self.subject, "capacity": self.capacity_bytes,
                "expires": self.expires, "issuer": self.issuer}

    def sign(self, issuer: NodeIdentity) -> "CapabilityCert":
        self.issuer = issuer.node_id
        self.sig = issuer.sign_claim(self._fields())
        return self

    def verify(self, keyring: Keyring, *, now: Optional[float] = None) -> bool:
        now = time.time() if now is None else now
        return now < self.expires and bool(self.sig) and \
            keyring.verify_claim(self.issuer, self._fields(), self.sig)

    def to_body(self) -> Dict[str, Any]:
        d = self._fields(); d["sig"] = self.sig; return d

    @staticmethod
    def from_body(b: Dict[str, Any]) -> "CapabilityCert":
        c = CapabilityCert(str(b["subject"]), int(b["capacity"]), float(b["expires"]), str(b.get("issuer", "")))
        c.sig = str(b.get("sig", ""))
        return c


class Prover:
    """Node side: answer a capability challenge (reference = memory-hard walk)."""

    def prove(self, challenge: Challenge) -> Proof:
        t0 = time.monotonic()
        acc = memory_walk(bytes.fromhex(challenge.chal), challenge.n_words, challenge.steps)
        return Proof(acc.hex(), time.monotonic() - t0)


class Verifier:
    """Verifier / admission authority: challenge, check, and issue signed certs."""

    def __init__(self, issuer: NodeIdentity, *, cert_ttl_s: float = 60.0,
                 seconds_per_gib: float = 8.0) -> None:
        self.issuer = issuer
        self.cert_ttl_s = cert_ttl_s
        self.seconds_per_gib = seconds_per_gib  # deadline scales with claimed size

    def challenge(self, claimed_bytes: int, steps: int = 64) -> Challenge:
        return Challenge(os.urandom(16).hex(), claimed_bytes, steps)

    def deadline_s(self, claimed_bytes: int) -> float:
        return max(1.0, self.seconds_per_gib * claimed_bytes / (1024 ** 3))

    def certify(self, subject: str, challenge: Challenge, proof: Proof, *,
                now: Optional[float] = None, check_time: bool = False) -> Optional[CapabilityCert]:
        expect = memory_walk(bytes.fromhex(challenge.chal), challenge.n_words, challenge.steps)
        if proof.acc != expect.hex():
            return None  # wrong buffer size or wrong challenge -> not the claimed capacity
        if check_time and proof.elapsed_s > self.deadline_s(challenge.claimed_bytes):
            return None  # too slow -> resource not actually resident
        now = time.time() if now is None else now
        return CapabilityCert(subject, challenge.claimed_bytes, now + self.cert_ttl_s).sign(self.issuer)


def attested_capacities(certs: Mapping[str, CapabilityCert], keyring: Keyring,
                        *, now: Optional[float] = None) -> Dict[str, int]:
    """Capacity (bytes) of nodes holding a currently-valid, correctly-signed cert.

    Feed the result into placement instead of self-declared ANNOUNCE memory: only attested
    nodes get capacity, so a memory-liar (no valid cert) is excluded.
    """
    out: Dict[str, int] = {}
    for subject, cert in certs.items():
        if cert.subject == subject and cert.verify(keyring, now=now):
            out[subject] = cert.capacity_bytes
    return out
