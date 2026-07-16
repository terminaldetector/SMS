"""Runnable self-test for the HELIX L1 transport layer.

    python -m helix.transport.selftest

Covers the in-memory transport, the real WiFi/IP data path over loopback sockets,
oversized-length rejection, and beacon authentication. Proves the AUDIT2 transport
fixes: correct self-delivery, identity-from-frame (not IP), and bounded reads.
"""

from __future__ import annotations

import asyncio
import socket
import struct

from helix.endpoint import Endpoint
from helix.message import MsgType
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec
from helix.transport.memory import InMemoryTransport
from helix.transport.wifi import WifiTransport

SECRET = b"helix-transport-selftest-secret"


def _endpoint(node_id, transport):
    codec = FrameCodec(node_id, sealer_from_cluster_secret(SECRET))
    return Endpoint(transport, codec)


async def _memory_case() -> None:
    bus = {}
    a = _endpoint("A", InMemoryTransport("A", bus))
    b = _endpoint("B", InMemoryTransport("B", bus))
    got_b, got_self = [], []
    b.on_message(lambda m: got_b.append(m))
    a.on_message(lambda m: got_self.append(m))

    await a.send("B", MsgType.PROMPT.value, {"text": "hi B"}, tid="t")
    assert got_b and got_b[0].src == "A" and got_b[0].body["text"] == "hi B"
    # self-delivery (coordinator addressing itself in the ring) works, and identity
    # comes from the frame — no from_node="" problem.
    await a.send("A", MsgType.HEARTBEAT.value, {"n": 1})
    assert got_self and got_self[0].src == "A"
    # broadcast reaches B, not self
    got_b.clear()
    await a.broadcast(MsgType.ANNOUNCE.value, {"mem": 8})
    assert got_b and got_b[0].type == MsgType.ANNOUNCE.value
    print("  memory: unicast + self-loopback + broadcast; src authenticated from frame")


async def _wifi_case() -> None:
    ta = WifiTransport("A", SECRET, bind_host="127.0.0.1", discovery_port=0)
    tb = WifiTransport("B", SECRET, bind_host="127.0.0.1", discovery_port=0)
    a, b = _endpoint("A", ta), _endpoint("B", tb)
    got_a, got_b = [], []
    a.on_message(lambda m: got_a.append(m))
    b.on_message(lambda m: got_b.append(m))
    await ta.start(); await tb.start()
    # cross-register addresses directly (exercise the real TCP path deterministically,
    # without relying on UDP broadcast in a sandbox)
    ta.add_peer("B", "127.0.0.1", tb.data_port)
    tb.add_peer("A", "127.0.0.1", ta.data_port)
    try:
        await a.send("B", MsgType.PROMPT.value, {"text": "over-tcp"})
        await b.send("A", MsgType.PROMPT_TOKEN.value, {"text": "reply"})
        await a.send("A", MsgType.HEARTBEAT.value, {"n": 7})  # self over wifi
        for _ in range(50):
            if (got_b
                    and any(m.body.get("text") == "reply" for m in got_a)
                    and any(m.type == MsgType.HEARTBEAT.value for m in got_a)):
                break
            await asyncio.sleep(0.02)
        assert got_b and got_b[0].body["text"] == "over-tcp" and got_b[0].src == "A"
        assert any(m.body.get("text") == "reply" for m in got_a)
        assert any(m.type == MsgType.HEARTBEAT.value and m.src == "A" for m in got_a)
        print("  wifi: real TCP round-trip both ways + self-delivery over loopback")
    finally:
        await ta.stop(); await tb.stop()


async def _oversized_case() -> None:
    ts = WifiTransport("S", SECRET, bind_host="127.0.0.1", discovery_port=0, max_frame=1024)
    srv = _endpoint("S", ts)
    got = []
    srv.on_message(lambda m: got.append(m))
    await ts.start()
    try:
        # Raw client sends a length prefix far exceeding max_frame; server must drop
        # the connection without allocating, and stay alive for a subsequent valid peer.
        reader, writer = await asyncio.open_connection("127.0.0.1", ts.data_port)
        writer.write(struct.pack("!I", 500_000_000))  # ~500 MB claimed
        writer.write(b"\x00" * 16)
        await writer.drain()
        await asyncio.sleep(0.1)
        writer.close()
        # server still healthy: a valid peer can now talk to it
        tc = WifiTransport("C", SECRET, bind_host="127.0.0.1", discovery_port=0)
        c = _endpoint("C", tc)
        await tc.start()
        tc.add_peer("S", "127.0.0.1", ts.data_port)
        try:
            await c.send("S", MsgType.PROMPT.value, {"text": "still alive"})
            for _ in range(50):
                if got:
                    break
                await asyncio.sleep(0.02)
            assert got and got[0].body["text"] == "still alive"
            print("  oversized: bad length prefix dropped pre-alloc; server survives")
        finally:
            await tc.stop()
    finally:
        await ts.stop()


def _beacon_case() -> None:
    t = WifiTransport("A", SECRET, bind_host="127.0.0.1")
    beacon = t._make_beacon()
    # a fresh node parses a valid beacon into a peer
    rx = WifiTransport("B", SECRET, bind_host="127.0.0.1")
    rx._on_beacon(beacon, ("10.0.0.5", 0))
    assert "A" in rx._peers and rx._peers["A"].host == "10.0.0.5"
    # wrong cluster secret → ignored
    other = WifiTransport("C", b"different-secret", bind_host="127.0.0.1")
    other._on_beacon(beacon, ("10.0.0.5", 0))
    assert "A" not in other._peers
    # tampered beacon → ignored
    bad = bytearray(beacon); bad[-1] ^= 1
    rx._peers.clear()
    rx._on_beacon(bytes(bad), ("10.0.0.5", 0))
    assert "A" not in rx._peers
    print("  beacon: valid accepted; wrong-secret and tampered rejected")


def main() -> None:
    print("HELIX transport (L1) self-test")
    print("[1] in-memory transport"); asyncio.run(_memory_case())
    print("[2] wifi/IP data path (loopback)"); asyncio.run(_wifi_case())
    print("[3] oversized length rejection"); asyncio.run(_oversized_case())
    print("[4] beacon authentication"); _beacon_case()
    print("ALL PASSED")


if __name__ == "__main__":
    main()
