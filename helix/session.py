"""Frame codec + anti-replay — the piece a node actually calls.

:class:`FrameCodec` ties together :mod:`helix.frame`, a :class:`~helix.sealer.Sealer`
and :class:`ReplayGuard`:

* ``seal(type, body, tid)`` → wire bytes: assigns the next per-sender ``seq``,
  serializes the message, generates a fresh nonce, seals the body with the header
  bound in as AAD, and enforces ``MAX_FRAME``.
* ``open(wire)`` → :class:`~helix.message.Message` or ``None``: validates size and
  magic, opens/authenticates the body, parses it, and runs anti-replay on
  ``(src, seq)``. Any failure returns ``None`` (drop), never an exception.

The authenticated ``msg.src`` is what callers use for the sender identity — the
transport's own "from" value is never trusted.
"""

from __future__ import annotations

import os
import threading
from typing import Any, Dict, Optional

from helix import frame as _frame
from helix.message import Message, MsgType
from helix.sealer import Sealer


class ReplayGuard:
    """Per-sender sliding-window anti-replay (IPsec-style, window = 64).

    Accepts strictly-new sequence numbers and a bounded amount of reordering, while
    rejecting duplicates and anything older than the window. Sequence spaces are
    independent per ``src``.
    """

    def __init__(self, window: int = 64) -> None:
        self._window = window
        self._state: Dict[str, list] = {}  # src -> [highest, bitmap]

    def accept(self, src: str, seq: int) -> bool:
        st = self._state.setdefault(src, [0, 0])
        highest, bitmap = st
        if seq > highest:
            shift = seq - highest
            if shift >= self._window:
                bitmap = 1
            else:
                bitmap = ((bitmap << shift) | 1) & ((1 << self._window) - 1)
            st[0], st[1] = seq, bitmap
            return True
        offset = highest - seq
        if offset >= self._window:
            return False  # too old
        mask = 1 << offset
        if bitmap & mask:
            return False  # duplicate
        st[1] = bitmap | mask
        return True


class FrameCodec:
    """Seals outbound messages and opens inbound frames for one node."""

    def __init__(
        self,
        node_id: str,
        sealer: Sealer,
        *,
        epoch: int = 0,
        max_frame: int = _frame.MAX_FRAME,
        replay: bool = True,
    ) -> None:
        self.node_id = node_id
        self._sealer = sealer
        self._epoch = epoch
        self._max_frame = max_frame
        self._replay = ReplayGuard() if replay else None
        self._seq = 0
        self._lock = threading.Lock()

    @property
    def epoch(self) -> int:
        return self._epoch

    def rekey(self, sealer: Sealer, epoch: int) -> None:
        """Adopt a new group key/epoch (membership change). Callers coordinate this."""
        self._sealer = sealer
        self._epoch = epoch

    def seal(self, type: str, body: Optional[Dict[str, Any]] = None, tid: str = "") -> bytes:
        with self._lock:
            self._seq += 1
            seq = self._seq
        msg = Message(type=type, src=self.node_id, body=body or {}, seq=seq, tid=tid)
        nonce = os.urandom(_frame.NONCE_LEN)
        flags = _frame.FLAG_CONFIDENTIAL if self._sealer.confidential else 0
        header = _frame.encode_header(flags, self._epoch, nonce)
        sealed = self._sealer.seal(msg.serialize(), nonce, aad=header)
        wire = header + sealed
        if len(wire) > self._max_frame:
            raise ValueError("frame too large: {} > {}".format(len(wire), self._max_frame))
        return wire

    def open(self, wire: bytes) -> Optional[Message]:
        parsed = _frame.parse_frame(wire, max_frame=self._max_frame)
        if parsed is None:
            return None
        flags, epoch, nonce, sealed = parsed
        if epoch != self._epoch:
            return None  # frame from a different key generation
        header = _frame.encode_header(flags, epoch, nonce)
        plaintext = self._sealer.open(sealed, nonce, aad=header)
        if plaintext is None:
            return None
        msg = Message.parse(plaintext)
        if msg is None:
            return None
        if self._replay is not None and not self._replay.accept(msg.src, msg.seq):
            return None
        return msg


__all__ = ["FrameCodec", "ReplayGuard", "Message", "MsgType"]
