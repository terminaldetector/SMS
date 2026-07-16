"""Per-node cryptographic identity (HELIX ④) — Ed25519 signatures, pure stdlib.

The group secret (see :mod:`helix.sealer`) proves a frame came from *a cluster member* —
it cannot say *which* member, because everyone shares it. That is enough for
confidentiality but not for attribution, and attribution is exactly what voting
(one-node-one-vote / anti-Sybil) and context provenance (who authored a turn) need.

This module adds a per-node **Ed25519** identity:

* Each node holds a signing keypair. Attribution-critical claims (votes, context entries,
  capability announces) are **signed** with the private key and verified against the
  sender's public key — so a member who knows the group secret still cannot forge a vote
  or a context turn "as" another node.
* **Self-certifying node ids.** ``node_id = derive_node_id(public_key)`` binds the id to
  the key: you cannot claim another node's id without its private key, and a peer verifies
  the binding locally with no certificate authority (like libp2p peer ids / onion addresses).

Ed25519 is implemented per RFC 8032 in pure Python (SHA-512 from ``hashlib``), so it loads
under Chaquopy. It is verified against the RFC 8032 test-1 public-key vector in
``helix.identity_selftest``. Pure-Python EC is fine for infrequent control-plane claims; a
native signer swaps in on the host for volume, via the same reference/host split used
elsewhere.
"""

from __future__ import annotations

import hashlib
import json
import os
from typing import Any, Dict, Optional

# --- Ed25519 (RFC 8032) ----------------------------------------------------

_p = 2 ** 255 - 19
_L = 2 ** 252 + 27742317777372353535851937790883648493
_d = (-121665 * pow(121666, _p - 2, _p)) % _p
_I = pow(2, (_p - 1) // 4, _p)


def _sha512(m: bytes) -> bytes:
    return hashlib.sha512(m).digest()


def _inv(x: int) -> int:
    return pow(x, _p - 2, _p)


def _recover_x(y: int, sign: int) -> Optional[int]:
    if y >= _p:
        return None
    x2 = (y * y - 1) * _inv(_d * y * y + 1) % _p
    if x2 == 0:
        return None if sign else 0
    x = pow(x2, (_p + 3) // 8, _p)
    if (x * x - x2) % _p != 0:
        x = x * _I % _p
    if (x * x - x2) % _p != 0:
        return None
    if (x & 1) != sign:
        x = _p - x
    return x


_g_y = 4 * _inv(5) % _p
_g_x = _recover_x(_g_y, 0)
_B = (_g_x, _g_y, 1, _g_x * _g_y % _p)
_NEUTRAL = (0, 1, 1, 0)


def _add(P, Q):
    A = (P[1] - P[0]) * (Q[1] - Q[0]) % _p
    B = (P[1] + P[0]) * (Q[1] + Q[0]) % _p
    C = 2 * P[3] * Q[3] * _d % _p
    D = 2 * P[2] * Q[2] % _p
    E, F, G, H = B - A, D - C, D + C, B + A
    return (E * F % _p, G * H % _p, F * G % _p, E * H % _p)


def _mul(s: int, P):
    Q = _NEUTRAL
    while s > 0:
        if s & 1:
            Q = _add(Q, P)
        P = _add(P, P)
        s >>= 1
    return Q


def _equal(P, Q) -> bool:
    if (P[0] * Q[2] - Q[0] * P[2]) % _p != 0:
        return False
    if (P[1] * Q[2] - Q[1] * P[2]) % _p != 0:
        return False
    return True


def _compress(P) -> bytes:
    zinv = _inv(P[2])
    x = P[0] * zinv % _p
    y = P[1] * zinv % _p
    return int(y | ((x & 1) << 255)).to_bytes(32, "little")


def _decompress(s: bytes):
    if len(s) != 32:
        return None
    y = int.from_bytes(s, "little")
    sign = (y >> 255) & 1
    y &= (1 << 255) - 1
    x = _recover_x(y, sign)
    if x is None:
        return None
    return (x, y, 1, x * y % _p)


def _secret_expand(secret: bytes):
    h = _sha512(secret)
    a = int.from_bytes(h[:32], "little")
    a &= (1 << 254) - 8
    a |= (1 << 254)
    return a, h[32:]


def ed25519_public(secret: bytes) -> bytes:
    a, _ = _secret_expand(secret)
    return _compress(_mul(a, _B))


def ed25519_sign(secret: bytes, msg: bytes) -> bytes:
    a, prefix = _secret_expand(secret)
    A = _compress(_mul(a, _B))
    r = int.from_bytes(_sha512(prefix + msg), "little") % _L
    Rs = _compress(_mul(r, _B))
    k = int.from_bytes(_sha512(Rs + A + msg), "little") % _L
    s = (r + k * a) % _L
    return Rs + int(s).to_bytes(32, "little")


def ed25519_verify(public: bytes, msg: bytes, sig: bytes) -> bool:
    if len(sig) != 64 or len(public) != 32:
        return False
    A = _decompress(public)
    if A is None:
        return False
    Rs = sig[:32]
    R = _decompress(Rs)
    if R is None:
        return False
    s = int.from_bytes(sig[32:], "little")
    if s >= _L:
        return False
    k = int.from_bytes(_sha512(Rs + public + msg), "little") % _L
    return _equal(_mul(s, _B), _add(R, _mul(k, A)))


# --- Node identity + keyring ----------------------------------------------

_ID_PREFIX = "hlx1"


def derive_node_id(public: bytes) -> str:
    """Self-certifying id: a function of the public key (no CA needed to verify)."""
    return _ID_PREFIX + hashlib.sha256(public).hexdigest()[:20]


def id_binds_key(node_id: str, public: bytes) -> bool:
    return derive_node_id(public) == node_id


def _canonical(fields: Dict[str, Any]) -> bytes:
    return json.dumps(fields, sort_keys=True, separators=(",", ":")).encode("utf-8")


class NodeIdentity:
    """A node's Ed25519 keypair and its self-certifying id."""

    def __init__(self, seed: Optional[bytes] = None) -> None:
        self.seed = seed or os.urandom(32)
        self.public = ed25519_public(self.seed)
        self.node_id = derive_node_id(self.public)

    def sign(self, msg: bytes) -> bytes:
        return ed25519_sign(self.seed, msg)

    def sign_claim(self, fields: Dict[str, Any]) -> str:
        """Sign a structured claim (e.g. a vote or context entry). Returns hex signature."""
        return self.sign(_canonical(fields)).hex()


class Keyring:
    """Maps node ids to public keys (trust-on-first-use), enforcing self-certification."""

    def __init__(self) -> None:
        self._keys: Dict[str, bytes] = {}

    def admit(self, node_id: str, public: bytes) -> bool:
        """Learn a peer's key. Rejects an id that doesn't bind to the key, or a key swap."""
        if not id_binds_key(node_id, public):
            return False
        existing = self._keys.get(node_id)
        if existing is not None and existing != public:
            return False  # TOFU: a node's key cannot change under it
        self._keys[node_id] = public
        return True

    def has(self, node_id: str) -> bool:
        return node_id in self._keys

    def verify_claim(self, node_id: str, fields: Dict[str, Any], sig_hex: str) -> bool:
        pub = self._keys.get(node_id)
        if pub is None:
            return False
        try:
            sig = bytes.fromhex(sig_hex)
        except ValueError:
            return False
        return ed25519_verify(pub, _canonical(fields), sig)
