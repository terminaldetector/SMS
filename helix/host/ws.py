"""Minimal WebSocket (RFC 6455) server + a HELIX frame transport over it — pure stdlib.

React Native has a global ``WebSocket`` but no raw TCP without a native module (and the common
native TCP lib has no New-Architecture support). So the phone-agent link uses **WebSocket**: the
phone is the WS client (built-in), a host runs this tiny WS server. One binary WS message carries
exactly one HELIX frame (WebSocket is already message-framed — no length prefix needed).

``WsFrameTransport`` implements :class:`helix.transport.base.Transport`, so an
:class:`~helix.endpoint.Endpoint` / :class:`~helix.agent.node.AgentNode` runs over it unchanged
(point-to-point, like :class:`~helix.transport.stream.StreamTransport`): the client sends its
``node_id`` as the first binary message (handshake), then frames flow both ways.

Only what HELIX needs is implemented: server handshake, binary/ping/pong/close frames, client→server
unmasking. No extensions, no fragmentation of application frames.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import struct
from typing import List, Optional, Tuple

from helix.log import get_logger
from helix.transport.base import FrameHandler, Transport

logger = get_logger("host.ws")

_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
OP_TEXT, OP_BIN, OP_CLOSE, OP_PING, OP_PONG = 0x1, 0x2, 0x8, 0x9, 0xA
_MAX_MSG = 16 * 1024 * 1024  # bound payload before allocation


def accept_key(key: str) -> str:
    return base64.b64encode(hashlib.sha1((key + _GUID).encode("ascii")).digest()).decode("ascii")


async def server_handshake(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> bool:
    """Read the HTTP upgrade request and reply 101. Returns False if it isn't a WS upgrade."""
    line = await reader.readline()
    if not line.startswith(b"GET"):
        return False
    key = None
    while True:
        h = await reader.readline()
        if h in (b"\r\n", b"", b"\n"):
            break
        name, _, value = h.decode("latin1").partition(":")
        if name.strip().lower() == "sec-websocket-key":
            key = value.strip()
    if not key:
        return False
    resp = (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Accept: {}\r\n\r\n".format(accept_key(key))
    )
    writer.write(resp.encode("ascii"))
    await writer.drain()
    return True


def encode_frame(opcode: int, payload: bytes = b"") -> bytes:
    """Server->client frame (never masked)."""
    n = len(payload)
    out = bytearray([0x80 | opcode])
    if n < 126:
        out.append(n)
    elif n < 65536:
        out.append(126)
        out += struct.pack("!H", n)
    else:
        out.append(127)
        out += struct.pack("!Q", n)
    out += payload
    return bytes(out)


async def read_frame(reader: asyncio.StreamReader) -> Optional[Tuple[int, bytes]]:
    """Read one WS frame -> (opcode, payload), or None on EOF/oversize."""
    try:
        b0b1 = await reader.readexactly(2)
    except asyncio.IncompleteReadError:
        return None
    opcode = b0b1[0] & 0x0F
    masked = bool(b0b1[1] & 0x80)
    length = b0b1[1] & 0x7F
    if length == 126:
        (length,) = struct.unpack("!H", await reader.readexactly(2))
    elif length == 127:
        (length,) = struct.unpack("!Q", await reader.readexactly(8))
    if length > _MAX_MSG:
        return None
    mask = await reader.readexactly(4) if masked else b""
    data = await reader.readexactly(length) if length else b""
    if masked:
        data = bytes(c ^ mask[i & 3] for i, c in enumerate(data))
    return opcode, data


class WsFrameTransport(Transport):
    """HELIX Transport over one WebSocket connection (server side). One binary msg = one frame."""

    def __init__(self, node_id: str, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        self.node_id = node_id
        self._reader = reader
        self._writer = writer
        self._remote_id: Optional[str] = None
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
        self._task = asyncio.create_task(self._read_loop())

    async def _read_loop(self) -> None:
        try:
            while True:
                msg = await read_frame(self._reader)
                if msg is None:
                    return
                opcode, payload = msg
                if opcode == OP_BIN:
                    if self._remote_id is None:  # first binary message = the client's node id
                        self._remote_id = payload.decode("utf-8", "replace")
                    else:
                        self._deliver_local(payload)  # identity is authenticated inside the frame
                elif opcode == OP_PING:
                    self._writer.write(encode_frame(OP_PONG, payload))
                    await self._writer.drain()
                elif opcode == OP_CLOSE:
                    return
        except (asyncio.IncompleteReadError, ConnectionResetError):
            pass

    async def _write_binary(self, frame: bytes) -> None:
        try:
            self._writer.write(encode_frame(OP_BIN, frame))
            await self._writer.drain()
        except OSError as exc:  # pragma: no cover
            logger.warning("%s: ws write failed: %s", self.node_id, exc)

    async def send(self, node_id: str, frame: bytes) -> None:
        if node_id == self.node_id:
            self._deliver_local(frame)  # loopback
            return
        await self._write_binary(frame)

    async def broadcast(self, frame: bytes) -> None:
        await self._write_binary(frame)

    async def peers(self) -> List[str]:
        return [self._remote_id] if self._remote_id else []

    async def stop(self) -> None:
        self._started = False
        if self._task is not None:
            self._task.cancel()
            self._task = None
        try:
            self._writer.write(encode_frame(OP_CLOSE))
            self._writer.close()
        except Exception:  # pragma: no cover
            pass
