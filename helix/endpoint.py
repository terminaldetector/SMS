"""``Endpoint`` — the L1↔L2 seam: a transport + a frame codec.

Callers work in terms of typed messages; the endpoint seals them into frames on the
way out and opens/authenticates them on the way in. Inbound handlers receive a fully
authenticated :class:`~helix.message.Message` (with a trustworthy ``src``) or nothing —
malformed, tampered, replayed, or wrong-key frames are dropped inside :meth:`FrameCodec.open`.
"""

from __future__ import annotations

from typing import Any, Callable, Dict, List, Optional

from helix.message import Message
from helix.session import FrameCodec
from helix.transport.base import Transport

MessageHandler = Callable[[Message], None]


class Endpoint:
    def __init__(self, transport: Transport, codec: FrameCodec) -> None:
        self._t = transport
        self._c = codec
        self._handlers: List[MessageHandler] = []
        transport.on_frame(self._on_frame)

    @property
    def node_id(self) -> str:
        return self._c.node_id

    def on_message(self, handler: MessageHandler) -> None:
        self._handlers.append(handler)

    def off_message(self, handler: MessageHandler) -> None:
        """Deregister a handler (used when a pipeline is replaced on re-placement)."""
        try:
            self._handlers.remove(handler)
        except ValueError:
            pass

    def _on_frame(self, frame: bytes) -> None:
        msg = self._c.open(frame)
        if msg is None:
            return
        for h in self._handlers:
            h(msg)

    async def send(self, node_id: str, type: str, body: Optional[Dict[str, Any]] = None, tid: str = "") -> None:
        await self._t.send(node_id, self._c.seal(type, body, tid))

    async def broadcast(self, type: str, body: Optional[Dict[str, Any]] = None, tid: str = "") -> None:
        await self._t.broadcast(self._c.seal(type, body, tid))

    async def peers(self) -> List[str]:
        return await self._t.peers()

    async def start(self) -> None:
        await self._t.start()

    async def stop(self) -> None:
        await self._t.stop()
