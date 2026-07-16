"""HELIX wire frame envelope.

Every frame on the mesh is::

    magic(4) | flags(1) | epoch(4) | nonce(12) | sealed_body(...)
    └──────────────── header (21 bytes, authenticated as AAD) ─────┘

* **magic** ``b"HLX1"`` — protocol id + wire version. A frame that does not start
  with it is not ours; drop without touching the rest.
* **flags** — bit 0 = confidential (AEAD) vs authenticated-only (HMAC). Lets a
  receiver know how the body was sealed without trial-decoding.
* **epoch** — group-key epoch (uint32). Bumped on every membership change so old
  frames from a previous key generation are rejected (replay/rekey boundary).
* **nonce** — per-frame nonce for the sealer (unique per key).
* **sealed_body** — output of :class:`~helix.sealer.Sealer` over the plaintext
  message, with **the header bound in as AAD**, so flags/epoch/nonce cannot be
  altered in flight.

``MAX_FRAME`` caps the total wire size and is checked **before** any allocation on
the receive path — closing the AUDIT2 finding that the WiFi transport read an
attacker-controlled 32-bit length with no bound.
"""

from __future__ import annotations

import struct
from typing import Optional, Tuple

MAGIC = b"HLX1"
_HEADER = struct.Struct("<4sB I 12s")  # magic, flags, epoch, nonce
HEADER_LEN = _HEADER.size  # 21
NONCE_LEN = 12

FLAG_CONFIDENTIAL = 0x01

# Generous enough for a quantized activation tensor, small enough to bound memory.
MAX_FRAME = 16 * 1024 * 1024  # 16 MiB


def encode_header(flags: int, epoch: int, nonce: bytes) -> bytes:
    if len(nonce) != NONCE_LEN:
        raise ValueError("nonce must be 12 bytes")
    return _HEADER.pack(MAGIC, flags & 0xFF, epoch & 0xFFFFFFFF, nonce)


def build_frame(flags: int, epoch: int, nonce: bytes, sealed_body: bytes) -> bytes:
    frame = encode_header(flags, epoch, nonce) + sealed_body
    if len(frame) > MAX_FRAME:
        raise ValueError("frame too large: {} > {}".format(len(frame), MAX_FRAME))
    return frame


def parse_frame(wire: bytes, *, max_frame: int = MAX_FRAME) -> Optional[Tuple[int, int, bytes, bytes]]:
    """Split a wire frame into ``(flags, epoch, nonce, sealed_body)``.

    Returns ``None`` (drop silently) if the frame is oversized, too short, or does
    not carry the HELIX magic. The size check happens first, before any slicing.
    """
    if len(wire) > max_frame or len(wire) < HEADER_LEN:
        return None
    magic, flags, epoch, nonce = _HEADER.unpack(wire[:HEADER_LEN])
    if magic != MAGIC:
        return None
    return flags, epoch, nonce, wire[HEADER_LEN:]
