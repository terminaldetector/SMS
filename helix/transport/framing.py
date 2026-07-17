"""Length-prefixed framing over a reliable byte stream.

Shared by the WiFi/TCP transport and the USB/serial :class:`~helix.transport.stream.StreamTransport`:
both move HELIX frames over a stream as ``uint32 big-endian length || frame``, with the length
checked **before** allocation so an attacker-controlled prefix cannot force a huge read.
"""

from __future__ import annotations

import asyncio
import struct
from typing import Optional

LEN_PREFIX = struct.Struct("!I")  # 4-byte big-endian length


def frame_out(frame: bytes) -> bytes:
    """Wrap ``frame`` with its length prefix for writing to a stream."""
    return LEN_PREFIX.pack(len(frame)) + frame


async def read_frame(reader: asyncio.StreamReader, max_frame: int) -> Optional[bytes]:
    """Read one length-prefixed frame. Returns ``None`` on a bad length (caller closes).

    Raises ``asyncio.IncompleteReadError`` / ``ConnectionResetError`` on EOF — the caller's
    read loop treats those as "stream closed".
    """
    header = await reader.readexactly(LEN_PREFIX.size)
    (length,) = LEN_PREFIX.unpack(header)
    if length == 0 or length > max_frame:
        return None  # bad/oversized prefix -> reject before allocating
    return await reader.readexactly(length)
