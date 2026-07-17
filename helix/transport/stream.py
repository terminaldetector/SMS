"""Point-to-point transport over one reliable byte stream (USB OTG: AOA / usb-serial).

A single bidirectional stream connects exactly two endpoints (PC ↔ smartphone). Frames are
length-prefixed (shared with the WiFi transport via :mod:`helix.transport.framing`). Star and
ring USB topologies are built by giving several of these to a
:class:`~helix.mesh.router.MeshRouter`, which forwards between them.

Construction takes an asyncio ``StreamReader``/``StreamWriter`` pair — whatever the host wraps
the USB channel into (pyserial/pyusb on a PC, ``UsbAccessory`` on Android, or a socket in tests).
On :meth:`start` the two ends exchange their node ids (a transport-level handshake, only for
addressing — authentication is per-frame above).
"""

from __future__ import annotations

import asyncio
from typing import List, Optional

from helix.frame import MAX_FRAME
from helix.log import get_logger
from helix.transport.base import FrameHandler, Transport
from helix.transport.framing import frame_out, read_frame

logger = get_logger("transport.stream")

_ID_MAX = 1024  # handshake node-id frame cap


class StreamTransport(Transport):
    def __init__(
        self,
        node_id: str,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        *,
        max_frame: int = MAX_FRAME,
        remote_id: Optional[str] = None,
    ) -> None:
        self.node_id = node_id
        self._reader = reader
        self._writer = writer
        self._max_frame = max_frame
        self._remote_id = remote_id
        self._handlers: List[FrameHandler] = []
        self._task: Optional[asyncio.Task] = None
        self._started = False

    def on_frame(self, handler: FrameHandler) -> None:
        self._handlers.append(handler)

    def _deliver_local(self, frame: bytes) -> None:
        for h in self._handlers:
            h(frame)

    async def start(self) -> None:
        if self._started:
            return
        self._started = True
        self._writer.write(frame_out(self.node_id.encode("utf-8")))  # handshake: our id first
        await self._writer.drain()
        self._task = asyncio.create_task(self._read_loop())

    async def _read_loop(self) -> None:
        try:
            hs = await read_frame(self._reader, _ID_MAX)  # first frame = remote node id
            if hs is not None:
                self._remote_id = hs.decode("utf-8", "replace")
            while True:
                frame = await read_frame(self._reader, self._max_frame)
                if frame is None:
                    logger.warning("%s: bad frame length, closing stream", self.node_id)
                    return
                self._deliver_local(frame)  # identity authenticated inside the frame
        except (asyncio.IncompleteReadError, ConnectionResetError):
            pass

    async def stop(self) -> None:
        self._started = False
        if self._task is not None:
            self._task.cancel()
            self._task = None
        try:
            self._writer.close()
        except Exception:  # pragma: no cover
            pass

    async def peers(self) -> List[str]:
        return [self._remote_id] if self._remote_id else []

    async def _write(self, frame: bytes) -> None:
        if len(frame) > self._max_frame:
            logger.warning("%s: frame over max_frame, dropping", self.node_id)
            return
        try:
            self._writer.write(frame_out(frame))
            await self._writer.drain()
        except OSError as exc:
            logger.warning("%s: stream write failed: %s", self.node_id, exc)

    async def send(self, node_id: str, frame: bytes) -> None:
        if node_id == self.node_id:
            self._deliver_local(frame)  # loopback; identity is in the frame
            return
        await self._write(frame)  # single remote peer

    async def broadcast(self, frame: bytes) -> None:
        await self._write(frame)
