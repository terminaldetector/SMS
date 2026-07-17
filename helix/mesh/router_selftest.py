"""Self-test for the HELIX MeshRouter (relay / star / ring).

    python -m helix.mesh.router_selftest

Star: a leaf reaches another leaf through the hub (relayed, not seen by unrelated nodes);
broadcast reaches all. Ring: a broadcast reaches every node exactly once (TTL + dedup kill the
loop). All over authenticated HELIX frames — the router only sees the cleartext routing label.
"""

from __future__ import annotations

import asyncio

from helix.endpoint import Endpoint
from helix.mesh.router import MeshRouter
from helix.message import MsgType
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec
from helix.transport.memory import InMemoryTransport

SECRET = b"helix-router-selftest-secret"


def _router_node(node_id):
    r = MeshRouter(node_id)
    ep = Endpoint(r, FrameCodec(node_id, sealer_from_cluster_secret(SECRET)))
    seen = []
    ep.on_message(lambda m: seen.append(m))
    return r, ep, seen


def _link(r1: MeshRouter, r2: MeshRouter) -> None:
    bus = {}  # a private 2-party bus = one point-to-point link
    r1.add_downstream(InMemoryTransport(r1.node_id, bus))
    r2.add_downstream(InMemoryTransport(r2.node_id, bus))


async def _settle(routers, seconds=0.3):
    for r in routers:
        await r.announce_presence()
    await asyncio.sleep(seconds)  # let presence + scheduled forwards flush


async def _star() -> None:
    (H, epH, gotH), (A, epA, gotA), (B, epB, gotB) = (
        _router_node("H"), _router_node("A"), _router_node("B"))
    _link(H, A); _link(H, B)
    for r in (H, A, B):
        await r.start()
    try:
        await _settle([H, A, B])
        # A -> B, relayed through the hub H
        await epA.send("B", MsgType.PROMPT.value, {"text": "leaf-to-leaf"})
        for _ in range(50):
            if gotB:
                break
            await asyncio.sleep(0.02)
        assert gotB and gotB[0].body["text"] == "leaf-to-leaf" and gotB[0].src == "A", gotB
        assert not any(m.body.get("text") == "leaf-to-leaf" for m in gotH), "hub delivered a relayed frame up!"
        # broadcast from A reaches hub + other leaf, not itself
        gotB.clear()
        await epA.broadcast(MsgType.ANNOUNCE.value, {"mem": 1})
        for _ in range(50):
            if gotB and gotH:
                break
            await asyncio.sleep(0.02)
        assert gotB and gotH and not gotA
        print("  star: A->B relayed via hub (hub didn't consume it); broadcast reached all")
    finally:
        for r in (H, A, B):
            await r.stop()


async def _ring() -> None:
    (A, epA, gotA), (B, epB, gotB), (C, epC, gotC) = (
        _router_node("A"), _router_node("B"), _router_node("C"))
    _link(A, B); _link(B, C); _link(C, A)
    for r in (A, B, C):
        await r.start()
    try:
        await _settle([A, B, C])
        await epA.broadcast(MsgType.ANNOUNCE.value, {"mem": 2})
        await asyncio.sleep(0.3)
        # each other node delivers the broadcast exactly once (TTL + dedup kill the loop)
        assert len(gotB) == 1 and len(gotC) == 1, (len(gotB), len(gotC))
        assert gotA == [], "originator got its own broadcast"
        print("  ring: broadcast reached each node exactly once (loop suppressed)")
    finally:
        for r in (A, B, C):
            await r.stop()


def main() -> None:
    print("HELIX MeshRouter self-test")
    print("[1] star relay + broadcast"); asyncio.run(_star())
    print("[2] ring broadcast dedup"); asyncio.run(_ring())
    print("ALL PASSED")


if __name__ == "__main__":
    main()
