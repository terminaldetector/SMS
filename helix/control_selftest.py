"""Runnable self-test for the HELIX L3 control plane.

    python -m helix.control_selftest

Covers placement (incl. the per-node RAM check), roster liveness with an injected
clock, and the full discovery → place → lease → ack flow plus lost-node healing over
the in-memory transport.
"""

from __future__ import annotations

import asyncio

from helix.control import ControlNode
from helix.endpoint import Endpoint
from helix.placement import Capacity, InsufficientCapacity, allocate_layers, plan_placement
from helix.roster import Roster
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec
from helix.transport.memory import InMemoryTransport

SECRET = b"helix-control-selftest-secret"
GB = 2 ** 30


def test_placement() -> None:
    assert allocate_layers(6, [0.5, 0.25, 0.25]) == [3, 2, 1]
    members = {"A": Capacity(8 * GB), "B": Capacity(4 * GB)}
    p = plan_placement(members, "demo", 6, 2 * GB)
    # 8:4 memory over 6 layers -> 4:2
    assert p.band_for("A") == p.bands["A"] and (p.bands["A"].start, p.bands["A"].end) == (0, 4)
    assert (p.bands["B"].start, p.bands["B"].end) == (4, 6)
    # combined memory too small
    try:
        plan_placement({"A": Capacity(1 * GB)}, "big", 4, 8 * GB)
        raise AssertionError("expected InsufficientCapacity (combined)")
    except InsufficientCapacity:
        pass
    # per-node RAM check (AUDIT #8): a tiny node forced to hold a layer it can't fit.
    # 3 layers, model 6 GB -> 2 GB/layer; node C has 8 GB and would get most layers,
    # node D has 0.5 GB and is forced >=1 layer (2 GB) it cannot hold.
    try:
        plan_placement({"C": Capacity(8 * GB), "D": Capacity(GB // 2)}, "m", 3, 6 * GB)
        raise AssertionError("expected InsufficientCapacity (per-node)")
    except InsufficientCapacity as e:
        assert "has 0.50 GB" in str(e) or "assigned" in str(e)
    print("  placement: memory-weighted bands + per-node RAM check")


def test_roster_liveness() -> None:
    t = {"now": 100.0}
    r = Roster(ttl_s=5.0, clock=lambda: t["now"])
    r.observe("A", Capacity(GB)); r.observe("B", Capacity(GB))
    assert set(r.live()) == {"A", "B"}
    t["now"] = 104.0
    r.observe("A")                      # A heartbeats, B goes quiet
    t["now"] = 107.0                    # 3s later: B is 7s stale (>5), A is 3s (<5)
    assert r.live() == ["A"]
    assert r.prune() == ["B"] and "B" not in r
    assert set(r.capacities().keys()) == {"A"}
    print("  roster: liveness + prune on injected clock")


def _node(node_id, bus, mem_gb, **kw):
    ep = Endpoint(InMemoryTransport(node_id, bus), FrameCodec(node_id, sealer_from_cluster_secret(SECRET)))
    return ControlNode(ep, Capacity(mem_gb * GB), **kw)


async def _place_flow() -> None:
    bus = {}
    coord = _node("coord", bus, 8)
    sat1 = _node("sat1", bus, 4)
    sat2 = _node("sat2", bus, 4)
    for n in (coord, sat1, sat2):
        await n.start()
    tasks = [asyncio.ensure_future(n.run_forever()) for n in (coord, sat1, sat2)]
    try:
        # discovery: wait until the coordinator sees all 3, then place a 12-layer model.
        placement = await coord.place("demo-16b", 12, 6 * GB, min_nodes=3, discovery_timeout_s=2.0)
        assert set(placement.ring) == {"coord", "sat1", "sat2"}
        assert sum(b.n for b in placement.bands.values()) == 12
        # every satellite recorded its lease, bound to the authenticated coordinator id.
        for _ in range(100):
            if sat1.lease and sat2.lease and coord.lease:
                break
            await asyncio.sleep(0.01)
        assert sat1.lease is not None and sat1.lease.coordinator == "coord"
        assert coord.lease is not None and coord.lease.coordinator == "coord"  # self-lease via loopback
        # coordinator collected acks from all three (incl. itself)
        assert coord.leases_acked(placement.term) >= {"coord", "sat1", "sat2"}
        b1 = sat1.lease.band
        assert (b1.start, b1.end) == (placement.bands["sat1"].start, placement.bands["sat1"].end)
        print("  flow: discovery -> place(12 layers/3 nodes) -> leases + acks; coordinator authenticated")
    finally:
        for n in (coord, sat1, sat2):
            await n.stop()
        for t in tasks:
            t.cancel()


async def _healing_hook() -> None:
    bus = {}
    coord = _node("coord", bus, 8, heartbeat_interval_s=0.1, ttl_s=0.4)
    sat = _node("sat", bus, 4, heartbeat_interval_s=0.1, ttl_s=0.4)
    lost: list = []
    coord.on_node_lost(lambda nid: lost.append(nid))
    await coord.start(); await sat.start()
    ct = asyncio.ensure_future(coord.run_forever(poll_s=0.02))
    st = asyncio.ensure_future(sat.run_forever(poll_s=0.02))
    try:
        placement = await coord.place("m", 4, 2 * GB, min_nodes=2, discovery_timeout_s=2.0)
        assert "sat" in placement.ring and not coord.placement_stale
        # satellite drops out; its heartbeat stops.
        await sat.stop(); st.cancel()
        for _ in range(60):
            if "sat" in lost:
                break
            await asyncio.sleep(0.02)
        assert "sat" in lost, "coordinator should detect the lost node"
        assert coord.placement_stale, "placement should be marked stale for re-lease (Phase 5)"
        print("  healing: lost placed node detected -> placement flagged stale")
    finally:
        await coord.stop(); ct.cancel()


def main() -> None:
    print("HELIX control-plane (L3) self-test")
    print("[1] placement math"); test_placement()
    print("[2] roster liveness"); test_roster_liveness()
    print("[3] discovery + place + lease + ack"); asyncio.run(_place_flow())
    print("[4] lost-node healing hook"); asyncio.run(_healing_hook())
    print("ALL PASSED")


if __name__ == "__main__":
    main()
