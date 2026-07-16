"""Pure-Python cryptographic primitives for HELIX (RFC 8439 + RFC 5869).

HELIX runs its orchestration core under Chaquopy, where a third-party crypto
dependency is undesirable. This module therefore implements the exact primitives
the protocol needs — **ChaCha20-Poly1305 AEAD** and **HKDF-SHA256** — using only
the Python standard library (``struct``, ``hmac``, ``hashlib``).

Two important notes:

* **Correctness over cleverness.** The AEAD here is verified against the official
  RFC 8439 §2.8.2 test vector in ``helix.selftest`` — do not "optimise" it without
  re-checking that vector.
* **This is the reference/bring-up implementation.** Pure-Python ChaCha20 is fine
  for the *control plane* (small, infrequent frames) and for tests, but too slow to
  encrypt every *data-plane* activation on a phone. The :class:`~helix.sealer.Sealer`
  abstraction lets the host swap in a native (Kotlin/JNI) AEAD for the hot path while
  keeping this as the portable default — the same "reference in Python, fast impl in
  the host" split already used for inference and transport in exo-core.
"""

from __future__ import annotations

import hashlib
import hmac
import struct
from typing import List, Optional

# --- ChaCha20 (RFC 8439 §2.3) ---------------------------------------------

_CONSTANTS = (0x61707865, 0x3320646E, 0x79622D32, 0x6B206574)  # "expand 32-byte k"
_MASK = 0xFFFFFFFF


def _rotl32(v: int, n: int) -> int:
    return ((v << n) | (v >> (32 - n))) & _MASK


def _quarter_round(s: List[int], a: int, b: int, c: int, d: int) -> None:
    s[a] = (s[a] + s[b]) & _MASK; s[d] ^= s[a]; s[d] = _rotl32(s[d], 16)
    s[c] = (s[c] + s[d]) & _MASK; s[b] ^= s[c]; s[b] = _rotl32(s[b], 12)
    s[a] = (s[a] + s[b]) & _MASK; s[d] ^= s[a]; s[d] = _rotl32(s[d], 8)
    s[c] = (s[c] + s[d]) & _MASK; s[b] ^= s[c]; s[b] = _rotl32(s[b], 7)


def _chacha20_block(key: bytes, counter: int, nonce: bytes) -> bytes:
    """Produce one 64-byte ChaCha20 keystream block (RFC 8439 §2.3.1)."""
    if len(key) != 32:
        raise ValueError("ChaCha20 key must be 32 bytes")
    if len(nonce) != 12:
        raise ValueError("ChaCha20 nonce must be 12 bytes")
    state = [
        *_CONSTANTS,
        *struct.unpack("<8I", key),
        counter & _MASK,
        *struct.unpack("<3I", nonce),
    ]
    working = list(state)
    for _ in range(10):  # 20 rounds = 10 column+diagonal double-rounds
        _quarter_round(working, 0, 4, 8, 12)
        _quarter_round(working, 1, 5, 9, 13)
        _quarter_round(working, 2, 6, 10, 14)
        _quarter_round(working, 3, 7, 11, 15)
        _quarter_round(working, 0, 5, 10, 15)
        _quarter_round(working, 1, 6, 11, 12)
        _quarter_round(working, 2, 7, 8, 13)
        _quarter_round(working, 3, 4, 9, 14)
    out = [(working[i] + state[i]) & _MASK for i in range(16)]
    return struct.pack("<16I", *out)


def chacha20(key: bytes, counter: int, nonce: bytes, data: bytes) -> bytes:
    """Encrypt/decrypt ``data`` with ChaCha20 (symmetric)."""
    out = bytearray(len(data))
    for i in range(0, len(data), 64):
        ks = _chacha20_block(key, counter + i // 64, nonce)
        chunk = data[i : i + 64]
        for j in range(len(chunk)):
            out[i + j] = chunk[j] ^ ks[j]
    return bytes(out)


# --- Poly1305 (RFC 8439 §2.5) ---------------------------------------------

_P1305 = (1 << 130) - 5
_R_CLAMP = 0x0FFFFFFC0FFFFFFC0FFFFFFC0FFFFFFF


def poly1305_mac(msg: bytes, key: bytes) -> bytes:
    """One-time Poly1305 MAC over ``msg`` with a 32-byte one-time ``key``."""
    if len(key) != 32:
        raise ValueError("Poly1305 key must be 32 bytes")
    r = int.from_bytes(key[:16], "little") & _R_CLAMP
    s = int.from_bytes(key[16:32], "little")
    acc = 0
    for i in range(0, len(msg), 16):
        block = msg[i : i + 16]
        n = int.from_bytes(block, "little") + (1 << (8 * len(block)))
        acc = ((acc + n) * r) % _P1305
    acc = (acc + s) & ((1 << 128) - 1)
    return acc.to_bytes(16, "little")


def _pad16(data: bytes) -> bytes:
    rem = len(data) % 16
    return b"\x00" * (16 - rem) if rem else b""


def _poly1305_key_gen(key: bytes, nonce: bytes) -> bytes:
    return _chacha20_block(key, 0, nonce)[:32]


# --- ChaCha20-Poly1305 AEAD (RFC 8439 §2.8) -------------------------------

TAG_LEN = 16
KEY_LEN = 32
NONCE_LEN = 12


def aead_encrypt(key: bytes, nonce: bytes, plaintext: bytes, aad: bytes = b"") -> bytes:
    """AEAD seal → ``ciphertext || tag(16)`` (RFC 8439 §2.8)."""
    otk = _poly1305_key_gen(key, nonce)
    ciphertext = chacha20(key, 1, nonce, plaintext)
    mac_data = (
        aad + _pad16(aad)
        + ciphertext + _pad16(ciphertext)
        + struct.pack("<Q", len(aad))
        + struct.pack("<Q", len(ciphertext))
    )
    tag = poly1305_mac(mac_data, otk)
    return ciphertext + tag


def aead_decrypt(key: bytes, nonce: bytes, sealed: bytes, aad: bytes = b"") -> Optional[bytes]:
    """AEAD open. Returns plaintext, or ``None`` on any tag mismatch / short input.

    A ``None`` return must be treated as "drop silently", never surfaced as an
    error a hostile peer could probe.
    """
    if len(sealed) < TAG_LEN:
        return None
    ciphertext, tag = sealed[:-TAG_LEN], sealed[-TAG_LEN:]
    otk = _poly1305_key_gen(key, nonce)
    mac_data = (
        aad + _pad16(aad)
        + ciphertext + _pad16(ciphertext)
        + struct.pack("<Q", len(aad))
        + struct.pack("<Q", len(ciphertext))
    )
    expected = poly1305_mac(mac_data, otk)
    if not hmac.compare_digest(tag, expected):
        return None
    return chacha20(key, 1, nonce, ciphertext)


# --- HKDF-SHA256 (RFC 5869) -----------------------------------------------

def hkdf(secret: bytes, *, salt: bytes = b"", info: bytes = b"", length: int = 32) -> bytes:
    """Derive ``length`` bytes of key material from ``secret`` (RFC 5869).

    Used to split the cluster secret into purpose-separated subkeys (e.g. an AEAD
    key vs. an HMAC key) so the same secret is never reused across contexts.
    """
    if salt == b"":
        salt = b"\x00" * hashlib.sha256().digest_size
    prk = hmac.new(salt, secret, hashlib.sha256).digest()  # extract
    okm = b""
    t = b""
    counter = 1
    while len(okm) < length:
        t = hmac.new(prk, t + info + bytes([counter]), hashlib.sha256).digest()  # expand
        okm += t
        counter += 1
    return okm[:length]
