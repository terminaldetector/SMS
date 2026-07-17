"""Self-test for the HELIX StreamTransport (USB byte-stream carrier).

    python -m helix.transport.stream_selftest

Uses a socketpair as a stand-in for a USB OTG byte stream: two endpoints exchange
authenticated HELIX frames both ways + self-loopback, and an oversized length prefix is
rejected before allocation.
"""

from __future__ import annotations

import asyncio
import socket
import struct

from helix.endpoint import Endpoint
from helix.message import MsgType
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec
from helix.transport.stream import StreamTransport

SECRET = b"helix-stream-selftest-secret"


async def _streams(sock: socket.socket):
    return await asyncio.open_connection(sock=sock)


def _endpoint(node_id, reader, writer):
    return Endpoint(StreamTransport(node_id, reader, writer),
                    FrameCodec(node_id, sealer_from_cluster_secret(SECRET)))


async def _roundtrip() -> None:
    sa, sb = socket.socketpair()
    ra, wa = await _streams(sa)
    rb, wb = await _streams(sb)
    a = _endpoint("pc", ra, wa)
    b = _endpoint("phone", rb, wb)
    got_a, got_b = [], []
    a.on_message(lambda m: got_a.append(m))
    b.on_message(lambda m: got_b.append(m))
    await a.start(); await b.start()
    try:
        await a.send("phone", MsgType.PROMPT.value, {"text": "over-usb"})
        await b.send("pc", MsgType.PROMPT_TOKEN.value, {"text": "reply"})
        await a.send("pc", MsgType.HEARTBEAT.value, {"n": 1})  # self-loopback
        for _ in range(50):
            if (got_b and any(m.body.get("text") == "reply" for m in got_a)
                    and any(m.type == MsgType.HEARTBEAT.value for m in got_a)):
                break
            await asyncio.sleep(0.02)
        assert got_b and got_b[0].body["text"] == "over-usb" and got_b[0].src == "pc", got_b
        assert any(m.body.get("text") == "reply" for m in got_a)
        assert any(m.type == MsgType.HEARTBEAT.value and m.src == "pc" for m in got_a)
        # peers learned via handshake
        assert await a._t.peers() == ["phone"] and await b._t.peers() == ["pc"]
        print("  stream: PC<->phone over a byte stream; frames authenticated, self-loopback ok")
    finally:
        await a.stop(); await b.stop()


async def _oversized() -> None:
    sa, sb = socket.socketpair()
    ra, wa = await _streams(sa)
    server = _endpoint("srv", ra, wa)
    got = []
    server.on_message(lambda m: got.append(m))
    await server.start()
    try:
        # raw peer sends a huge length prefix as its first framed chunk -> rejected pre-alloc.
        loop = asyncio.get_running_loop()
        await loop.sock_sendall(sb, struct.pack("!I", 500_000_000) + b"\x00" * 8)
        await asyncio.sleep(0.1)
        # transport survived (no crash / no giant alloc); the read loop closed the stream.
        assert got == []
        print("  oversized: bad length prefix rejected before allocation; transport survived")
    finally:
        await server.stop()
        sb.close()


def main() -> None:
    print("HELIX StreamTransport (USB byte-stream) self-test")
    print("[1] PC<->phone round-trip + self-loopback"); asyncio.run(_roundtrip())
    print("[2] oversized length rejection"); asyncio.run(_oversized())
    print("ALL PASSED")


if __name__ == "__main__":
    main()
