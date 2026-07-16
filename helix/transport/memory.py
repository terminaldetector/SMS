"""Process-local transport for tests and single-process multi-node simulation."""

from __future__ import annotations

import asyncio
from typing import Dict, List

from helix.transport.base import FrameHandler, Transport


class InMemoryTransport(Transport):
    """All instances sharing a ``bus`` dict see each other.

    Self-delivery is correct: ``send(self.node_id, frame)`` loops the frame back to
    this node's handlers (the codec then reads the authenticated ``src`` from inside),
    so a coordinator that addresses itself in the ring works without the transport
    inventing a ``from_node``.
    """

    def __init__(self, node_id: str, bus: Dict[str, "InMemoryTransport"]) -> None:
        self.node_id = node_id
        self._bus = bus
        self._handlers: List[FrameHandler] = []
        bus[node_id] = self

    def on_frame(self, handler: FrameHandler) -> None:
        self._handlers.append(handler)

    def _deliver(self, frame: bytes) -> None:
        for handler in self._handlers:
            handler(frame)

    async def start(self) -> None:  # nothing to open
        return

    async def stop(self) -> None:
        self._bus.pop(self.node_id, None)

    async def send(self, node_id: str, frame: bytes) -> None:
        peer = self._bus.get(node_id)  # includes self → correct loopback
        if peer is not None:
            peer._deliver(frame)
        await asyncio.sleep(0)

    async def broadcast(self, frame: bytes) -> None:
        for nid, peer in list(self._bus.items()):
            if nid != self.node_id:
                peer._deliver(frame)
        await asyncio.sleep(0)

    async def peers(self) -> List[str]:
        return [n for n in self._bus if n != self.node_id]
