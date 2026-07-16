"""HELIX transports (L1): move opaque frame bytes between nodes."""

from __future__ import annotations

from helix.transport.base import FrameHandler, Transport
from helix.transport.memory import InMemoryTransport

__all__ = ["Transport", "FrameHandler", "InMemoryTransport"]

# WifiTransport pulls in asyncio sockets; import lazily so ``helix.transport`` stays
# importable in minimal environments.
try:
    from helix.transport.wifi import WifiTransport  # noqa: F401

    __all__.append("WifiTransport")
except Exception:  # pragma: no cover
    pass
