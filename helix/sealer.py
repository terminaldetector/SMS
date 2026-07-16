"""Frame sealing: authentication (+ optional confidentiality).

A :class:`Sealer` turns a plaintext frame body into a wire body and back. Two
implementations ship:

* :class:`AeadSealer` — ChaCha20-Poly1305. **Confidential + authenticated.** This
  is the default and closes the AUDIT2 finding that the WiFi data path sent prompts
  and tokens in clear text (HMAC alone authenticates but does not encrypt).
* :class:`HmacSealer` — HMAC-SHA256. **Authenticated but NOT confidential** (the
  body stays in clear). Kept for the control plane where privacy is not required and
  for interop/debugging; never use it for prompts, tokens or activations.

Both derive their key from the cluster secret via HKDF with a distinct ``info``
label, so the same secret is never reused across the two schemes.

The abstraction exists so the **host can swap in a native AEAD** (Kotlin/JNI) for the
hot data-plane path while this pure-Python implementation stays the portable default
for the control plane and tests — mirroring exo-core's inference/transport bridges.
"""

from __future__ import annotations

import hashlib
import hmac
from abc import ABC, abstractmethod
from typing import Optional

from helix.crypto import KEY_LEN, NONCE_LEN, aead_decrypt, aead_encrypt, hkdf

_HMAC_TAG_LEN = 32


class Sealer(ABC):
    """Seals/opens a frame body. ``nonce_len`` bytes of nonce are supplied per frame."""

    nonce_len: int = NONCE_LEN
    confidential: bool = False

    @abstractmethod
    def seal(self, plaintext: bytes, nonce: bytes, aad: bytes) -> bytes:
        """Return the wire body for ``plaintext`` (nonce unique per key)."""

    @abstractmethod
    def open(self, sealed: bytes, nonce: bytes, aad: bytes) -> Optional[bytes]:
        """Return the plaintext, or ``None`` on any authentication failure."""


class AeadSealer(Sealer):
    """ChaCha20-Poly1305 — confidential and authenticated (recommended default)."""

    confidential = True

    def __init__(self, key: bytes) -> None:
        if len(key) != KEY_LEN:
            raise ValueError("AeadSealer key must be 32 bytes")
        self._key = key

    def seal(self, plaintext: bytes, nonce: bytes, aad: bytes) -> bytes:
        return aead_encrypt(self._key, nonce, plaintext, aad)

    def open(self, sealed: bytes, nonce: bytes, aad: bytes) -> Optional[bytes]:
        return aead_decrypt(self._key, nonce, sealed, aad)


class HmacSealer(Sealer):
    """HMAC-SHA256 — authenticated but the body stays in clear (control plane only)."""

    confidential = False

    def __init__(self, key: bytes) -> None:
        self._key = key

    def seal(self, plaintext: bytes, nonce: bytes, aad: bytes) -> bytes:
        tag = hmac.new(self._key, nonce + aad + plaintext, hashlib.sha256).digest()
        return plaintext + tag  # body is visible; tag authenticates it

    def open(self, sealed: bytes, nonce: bytes, aad: bytes) -> Optional[bytes]:
        if len(sealed) < _HMAC_TAG_LEN:
            return None
        plaintext, tag = sealed[:-_HMAC_TAG_LEN], sealed[-_HMAC_TAG_LEN:]
        expected = hmac.new(self._key, nonce + aad + plaintext, hashlib.sha256).digest()
        if not hmac.compare_digest(tag, expected):
            return None
        return plaintext


_INFO_AEAD = b"helix/1 aead key"
_INFO_HMAC = b"helix/1 hmac key"


def sealer_from_cluster_secret(secret: bytes, *, mode: str = "aead") -> Sealer:
    """Build a :class:`Sealer` from the out-of-band cluster secret.

    ``mode="aead"`` (default) → confidential + authenticated. ``mode="hmac"`` →
    authenticated only. Each mode derives a distinct key via HKDF.
    """
    if mode == "aead":
        return AeadSealer(hkdf(secret, info=_INFO_AEAD, length=KEY_LEN))
    if mode == "hmac":
        return HmacSealer(hkdf(secret, info=_INFO_HMAC, length=KEY_LEN))
    raise ValueError("unknown sealer mode: {!r}".format(mode))
