"""WiFi / IP transport: UDP-beacon discovery + length-prefixed TCP data path.

Rewrite of the hardened ``exo_core.networking.wifi_mesh`` that fixes the AUDIT2
findings at the transport layer:

* **No identity-by-IP.** The inbound handler is called with the frame bytes only.
  Sender identity is the authenticated ``src`` inside the frame — the transport never
  maps a source IP to a node id, so it cannot be fooled by NAT/shared-IP or a spoofed
  beacon into mis-attributing a frame.
* **Bounded reads.** The TCP length prefix is checked against ``max_frame`` **before**
  any allocation; an oversized prefix drops the connection instead of reading gigabytes.
* **Correct self-delivery.** ``send(self.node_id, …)`` loops back locally.
* **Authenticated beacons.** Discovery beacons are HMAC-tagged with a subkey derived
  from the cluster secret and carry a timestamp, so an unrelated device on the same
  Wi-Fi cannot appear as a peer and stale beacons are ignored. (Per-node *identity*
  binding of beacons — so one cluster member can't advertise another's id — is a
  Phase-5 concern that needs per-node keys; here beacons authenticate cluster
  membership, matching the current shared-secret trust model.)

Works over any IP link: Wi-Fi Aware, Wi-Fi Direct, same-LAN Wi-Fi, or a USB-tether
(``usb0``) — the transport does not care how the IP link came to exist.
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import socket
import time
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

from helix.crypto import hkdf
from helix.frame import MAX_FRAME
from helix.log import get_logger
from helix.transport.base import FrameHandler, Transport
from helix.transport.framing import frame_out, read_frame

logger = get_logger("transport.wifi")

_BEACON_TAG_LEN = 32
_BEACON_INFO = b"helix/1 beacon key"
_BEACON_MAX_AGE_S = 8.0  # reject beacons whose timestamp is older/newer than this


@dataclass
class _PeerInfo:
    host: str
    port: int
    last_seen: float


class WifiTransport(Transport):
    def __init__(
        self,
        node_id: str,
        cluster_secret: bytes,
        *,
        bind_host: str = "0.0.0.0",
        discovery_port: int = 47632,
        data_port: int = 0,
        beacon_interval_s: float = 3.0,
        stale_peer_s: float = 12.0,
        max_frame: int = MAX_FRAME,
    ) -> None:
        self.node_id = node_id
        self._beacon_key = hkdf(cluster_secret, info=_BEACON_INFO, length=32)
        self._bind_host = bind_host
        self._discovery_port = discovery_port
        self._beacon_interval_s = beacon_interval_s
        self._stale_peer_s = stale_peer_s
        self._max_frame = max_frame

        self._handlers: List[FrameHandler] = []
        self._peers: Dict[str, _PeerInfo] = {}
        self._connections: Dict[str, asyncio.StreamWriter] = {}
        self._server: Optional[asyncio.AbstractServer] = None
        self._udp: Optional[asyncio.DatagramTransport] = None
        self._port = data_port
        self._tasks: List[asyncio.Task] = []
        self._started = False

    # -- lifecycle ---------------------------------------------------------
    async def start(self) -> None:
        if self._started:
            return
        self._server = await asyncio.start_server(
            self._on_tcp_client, host=self._bind_host, port=self._port
        )
        self._port = self._server.sockets[0].getsockname()[1]

        loop = asyncio.get_running_loop()
        try:
            udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            if hasattr(socket, "SO_REUSEPORT"):
                udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            udp.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            udp.bind((self._bind_host, self._discovery_port))
            udp.setblocking(False)
            self._udp, _ = await loop.create_datagram_endpoint(
                lambda: _BeaconProtocol(self._on_beacon), sock=udp
            )
            self._tasks.append(asyncio.create_task(self._beacon_loop()))
        except OSError as exc:  # discovery optional (e.g. peers added manually)
            logger.warning("%s: UDP discovery unavailable: %s", self.node_id, exc)
        self._tasks.append(asyncio.create_task(self._prune_loop()))
        self._started = True
        logger.info("%s: wifi transport on TCP :%d", self.node_id, self._port)

    async def stop(self) -> None:
        for t in self._tasks:
            t.cancel()
        self._tasks.clear()
        if self._server is not None:
            self._server.close()
        if self._udp is not None:
            self._udp.close()
        for w in self._connections.values():
            w.close()
        self._connections.clear()
        self._started = False

    # -- addressing --------------------------------------------------------
    @property
    def data_port(self) -> int:
        return self._port

    def add_peer(self, node_id: str, host: str, port: int) -> None:
        """Register a peer address directly (external directory / tests), bypassing
        UDP discovery."""
        self._peers[node_id] = _PeerInfo(host, port, time.monotonic())

    async def peers(self) -> List[str]:
        return list(self._peers.keys())

    # -- IO ----------------------------------------------------------------
    def on_frame(self, handler: FrameHandler) -> None:
        self._handlers.append(handler)

    def _deliver_local(self, frame: bytes) -> None:
        for h in self._handlers:
            h(frame)

    async def broadcast(self, frame: bytes) -> None:
        for nid in list(self._peers.keys()):
            await self.send(nid, frame)

    async def send(self, node_id: str, frame: bytes) -> None:
        if node_id == self.node_id:
            self._deliver_local(frame)  # correct loopback; identity is in the frame
            return
        if len(frame) > self._max_frame:
            logger.warning("send(%r): frame over max_frame, dropping", node_id)
            return
        peer = self._peers.get(node_id)
        if peer is None:
            logger.warning("send(%r): unknown peer, dropping", node_id)
            return
        try:
            writer = await self._connection_for(node_id, peer)
            writer.write(frame_out(frame))
            await writer.drain()
        except OSError as exc:
            logger.warning("send(%r) failed: %s", node_id, exc)
            self._connections.pop(node_id, None)

    async def _connection_for(self, node_id: str, peer: _PeerInfo) -> asyncio.StreamWriter:
        w = self._connections.get(node_id)
        if w is not None and not w.is_closing():
            return w
        _r, w = await asyncio.open_connection(peer.host, peer.port)
        self._connections[node_id] = w
        return w

    async def _on_tcp_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            while True:
                frame = await read_frame(reader, self._max_frame)
                if frame is None:
                    logger.warning("%s: bad frame length, closing", self.node_id)
                    return  # drop connection BEFORE allocating
                self._deliver_local(frame)  # identity authenticated inside the frame
        except (asyncio.IncompleteReadError, ConnectionResetError):
            pass
        finally:
            writer.close()

    # -- discovery ---------------------------------------------------------
    def _make_beacon(self) -> bytes:
        payload = json.dumps(
            {"nid": self.node_id, "port": self._port, "ts": time.time()}
        ).encode()
        tag = hmac.new(self._beacon_key, payload, hashlib.sha256).digest()
        return tag + payload

    async def _beacon_loop(self) -> None:
        while True:
            try:
                self._udp.sendto(self._make_beacon(), ("255.255.255.255", self._discovery_port))
            except OSError as exc:
                logger.debug("beacon send failed: %s", exc)
            await asyncio.sleep(self._beacon_interval_s)

    def _on_beacon(self, data: bytes, addr: Tuple[str, int]) -> None:
        if len(data) < _BEACON_TAG_LEN:
            return
        tag, payload = data[:_BEACON_TAG_LEN], data[_BEACON_TAG_LEN:]
        expected = hmac.new(self._beacon_key, payload, hashlib.sha256).digest()
        if not hmac.compare_digest(tag, expected):
            return  # not our cluster
        try:
            obj = json.loads(payload.decode())
            nid, port, ts = str(obj["nid"]), int(obj["port"]), float(obj["ts"])
        except (ValueError, KeyError, UnicodeDecodeError):
            return
        if nid == self.node_id:
            return
        if abs(time.time() - ts) > _BEACON_MAX_AGE_S:
            return  # stale / replayed beacon
        self._peers[nid] = _PeerInfo(host=addr[0], port=port, last_seen=time.monotonic())

    async def _prune_loop(self) -> None:
        while True:
            await asyncio.sleep(self._stale_peer_s / 2)
            now = time.monotonic()
            for nid in [n for n, p in self._peers.items() if now - p.last_seen > self._stale_peer_s]:
                self._peers.pop(nid, None)
                w = self._connections.pop(nid, None)
                if w is not None:
                    w.close()


class _BeaconProtocol(asyncio.DatagramProtocol):
    def __init__(self, on_beacon) -> None:
        self._on_beacon = on_beacon

    def datagram_received(self, data: bytes, addr: Tuple[str, int]) -> None:
        self._on_beacon(data, addr)
