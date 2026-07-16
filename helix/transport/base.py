"""Transport abstraction for HELIX.

A :class:`Transport` moves **opaque frame bytes** between nodes. Crucially, unlike
exo-core's ``IMeshNetwork``, the inbound handler receives **only the frame** — not a
``from_node``. The sender's identity lives inside the authenticated frame (``msg.src``,
see :mod:`helix.message`), so the transport never has to guess who sent something from
an IP address. That single change removes the identity-by-IP fragility and the
``from_node==""`` self-delivery bug found in the AUDIT2 review.

Implementations:

* :class:`~helix.transport.memory.InMemoryTransport` — process-local, for tests.
* :class:`~helix.transport.wifi.WifiTransport` — UDP-beacon discovery + length-prefixed
  TCP data path over any IP link (Wi-Fi Aware / Direct / LAN / USB-tether).

The interface is transport-agnostic on purpose: BLE (via bitchat) or a native radio
can implement the same contract.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Callable, List

# Receives one raw HELIX frame. Sender identity is authenticated *inside* the frame,
# not supplied here.
FrameHandler = Callable[[bytes], None]


class Transport(ABC):
    """Moves opaque frame bytes between nodes addressed by ``node_id``."""

    node_id: str

    @abstractmethod
    async def start(self) -> None:
        """Open sockets / begin discovery. Idempotent."""

    @abstractmethod
    async def stop(self) -> None:
        """Release resources."""

    @abstractmethod
    async def send(self, node_id: str, frame: bytes) -> None:
        """Unicast ``frame`` to ``node_id``. Sending to *self* loops back locally."""

    @abstractmethod
    async def broadcast(self, frame: bytes) -> None:
        """Send ``frame`` to every reachable peer (excluding self)."""

    @abstractmethod
    async def peers(self) -> List[str]:
        """Currently reachable node ids (excluding self)."""

    @abstractmethod
    def on_frame(self, handler: FrameHandler) -> None:
        """Register a callback invoked with each inbound frame's bytes."""
