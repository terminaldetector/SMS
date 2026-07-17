"""Mesh routing — forward frames between several transports (star / ring / two-tier bridge).

HELIX unicast is sender-addressed at the transport, and the frame's ``dst`` is sealed — so a
node that must **relay** for others (a USB star hub, or a server bridging BitChat clients to a
WiFi/USB backbone) can't tell where to forward. :class:`MeshRouter` adds a thin **routing
envelope** around the sealed frame:

* **data:** ``kind=0 | ttl | dst_len(2) | dst | frame`` — ``dst`` is a cleartext node-id label
  (or ``*`` = broadcast); the frame stays encrypted (only the routing label is exposed, like an
  IP header over ciphertext).
* **presence:** ``kind=1 | ttl | origin`` — floods a node id so routers learn which downstream
  reaches it (a reactive directory).

`MeshRouter` implements :class:`~helix.transport.base.Transport` **upward** (an ``Endpoint`` wraps
it unchanged) while owning several **downstream** transports. It forwards by directory when known,
else floods; a TTL + a per-frame dedup set bound loops. This covers a star (hub relays leaf↔leaf),
a ring (flood to neighbours), and a bridge (downstreams of different kinds, e.g. BitChat + WiFi).
"""

from __future__ import annotations

import asyncio
import hashlib
import struct
from collections import OrderedDict
from typing import Dict, List, Optional, Tuple

from helix.log import get_logger
from helix.transport.base import FrameHandler, Transport

logger = get_logger("mesh.router")

_KIND_DATA = 0
_KIND_PRESENCE = 1
_H = struct.Struct("!H")
_BROADCAST = "*"


def _enc_data(ttl: int, dst: str, frame: bytes) -> bytes:
    d = dst.encode("utf-8")
    return bytes([_KIND_DATA, ttl & 0xFF]) + _H.pack(len(d)) + d + frame


def _enc_presence(ttl: int, origin: str) -> bytes:
    return bytes([_KIND_PRESENCE, ttl & 0xFF]) + origin.encode("utf-8")


def _decode(env: bytes) -> Optional[Tuple[int, int, str, Optional[bytes]]]:
    if len(env) < 2:
        return None
    kind, ttl = env[0], env[1]
    if kind == _KIND_PRESENCE:
        return kind, ttl, env[2:].decode("utf-8", "replace"), None
    if kind == _KIND_DATA:
        if len(env) < 4:
            return None
        (dlen,) = _H.unpack(env[2:4])
        if len(env) < 4 + dlen:
            return None
        dst = env[4:4 + dlen].decode("utf-8", "replace")
        return kind, ttl, dst, env[4 + dlen:]
    return None


def _digest(data: bytes) -> bytes:
    return hashlib.sha256(data).digest()[:16]


class MeshRouter(Transport):
    def __init__(self, node_id: str, *, default_ttl: int = 8, dedup_size: int = 2048) -> None:
        self.node_id = node_id
        self._default_ttl = default_ttl
        self._dedup_size = dedup_size
        self._downstreams: List[Transport] = []
        self._handlers: List[FrameHandler] = []   # upward (Endpoint)
        self._directory: Dict[str, Transport] = {}
        self._seen: "OrderedDict[bytes, None]" = OrderedDict()

    # -- wiring ------------------------------------------------------------
    def add_downstream(self, transport: Transport) -> None:
        self._downstreams.append(transport)
        transport.on_frame(lambda env, t=transport: self._on_downstream(t, env))

    def on_frame(self, handler: FrameHandler) -> None:
        self._handlers.append(handler)

    def _deliver_up(self, frame: bytes) -> None:
        for h in self._handlers:
            h(frame)

    async def start(self) -> None:
        for t in self._downstreams:
            await t.start()

    async def stop(self) -> None:
        for t in self._downstreams:
            await t.stop()

    async def peers(self) -> List[str]:
        return list(self._directory.keys())

    def _fresh(self, key: bytes) -> bool:
        if key in self._seen:
            return False
        self._seen[key] = None
        if len(self._seen) > self._dedup_size:
            self._seen.popitem(last=False)
        return True

    # -- presence (builds the routing directory) ---------------------------
    async def announce_presence(self) -> None:
        env = _enc_presence(self._default_ttl, self.node_id)
        for t in self._downstreams:
            await t.broadcast(env)

    # -- Transport (upward, used by Endpoint) ------------------------------
    async def send(self, node_id: str, frame: bytes) -> None:
        if node_id == self.node_id:
            self._deliver_up(frame)  # loopback; identity is inside the frame
            return
        self._fresh(_digest(frame))  # mark our own origination so it won't loop back to us
        env = _enc_data(self._default_ttl, node_id, frame)
        target = self._directory.get(node_id)
        if target is not None:
            await target.broadcast(env)
        else:
            for t in self._downstreams:
                await t.broadcast(env)

    async def broadcast(self, frame: bytes) -> None:
        self._fresh(_digest(frame))
        env = _enc_data(self._default_ttl, _BROADCAST, frame)
        for t in self._downstreams:
            await t.broadcast(env)

    # -- downstream ingress (relay) ----------------------------------------
    def _on_downstream(self, src: Transport, env: bytes) -> None:
        decoded = _decode(env)
        if decoded is None:
            return
        kind, ttl, a, frame = decoded
        if kind == _KIND_PRESENCE:
            origin = a
            if origin == self.node_id:
                return
            self._directory[origin] = src  # origin reachable via this downstream
            if ttl > 1 and self._fresh(_digest(b"P" + env)):
                self._flood(_enc_presence(ttl - 1, origin), exclude=src)
            return
        # data
        dst, frame = a, frame or b""
        if not self._fresh(_digest(frame)):
            return  # loop / duplicate
        if dst == self.node_id:
            self._deliver_up(frame)
            return
        if dst == _BROADCAST:
            self._deliver_up(frame)
            if ttl > 1:
                self._flood(_enc_data(ttl - 1, _BROADCAST, frame), exclude=src)
            return
        # unicast for someone else -> forward toward dst
        if ttl > 1:
            out = _enc_data(ttl - 1, dst, frame)
            target = self._directory.get(dst)
            if target is not None and target is not src:
                self._schedule(target.broadcast(out))
            else:
                self._flood(out, exclude=src)

    def _flood(self, env: bytes, *, exclude: Optional[Transport] = None) -> None:
        for t in self._downstreams:
            if t is not exclude:
                self._schedule(t.broadcast(env))

    @staticmethod
    def _schedule(coro) -> None:
        asyncio.ensure_future(coro)  # forward promptly from the sync ingress callback
