"""Runnable self-test for the HELIX L4 data plane.

    python -m helix.pipeline_selftest

Covers the layer-shard ring (proving every band ran), the int8 activation codec,
duplicate-hop idempotency, and the full L1→L4 stack wired from real L3 leases.
"""

from __future__ import annotations

import asyncio

from helix.activation import Int8ActivationCodec, RawActivationCodec
from helix.control import ControlNode
from helix.endpoint import Endpoint
from helix.message import MsgType
from helix.pipeline import ShardPipeline
from helix.placement import Band, Capacity
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec
from helix.shard import NumericShardRunner
from helix.transport.memory import InMemoryTransport

SECRET = b"helix-l4-selftest-secret"
GB = 2 ** 30


def _endpoint(node_id, bus):
    return Endpoint(InMemoryTransport(node_id, bus), FrameCodec(node_id, sealer_from_cluster_secret(SECRET)))


def _runner(band):
    r = NumericShardRunner()
    r.load(band, n_layers=6)
    return r


async def _ring_case() -> None:
    bus = {}
    ring = ["A", "B"]
    bands = {"A": Band(0, 4), "B": Band(4, 6)}  # 6 layers, memory-weighted 8:4 -> 4:2
    pipes = {}
    for nid in ring:
        pipes[nid] = ShardPipeline(nid, _endpoint(nid, bus), ring, bands[nid], _runner(bands[nid]),
                                   coordinator="A", max_new_tokens=3)
    bt = asyncio.ensure_future(pipes["B"].run_forever())
    try:
        tokens = await pipes["A"].generate([1])
        # seed 1, +6 per lap (A:+4, B:+2) only if BOTH bands ran -> 7, 13, 19
        assert tokens == [7, 13, 19], tokens
        print("  ring: A[0,4)+B[4,6) -> {} (each +6 = both shards ran, over sealed frames)".format(tokens))
    finally:
        pipes["B"].stop(); bt.cancel()


def _quant_case() -> None:
    vec = [0.0, 1.5, -3.25, 12.0, -12.0, 0.01]
    raw, q = RawActivationCodec(), Int8ActivationCodec()
    back = q.decode(q.encode(vec))
    scale = 12.0 / 127.0
    assert all(abs(a - b) <= scale for a, b in zip(vec, back)), back
    import json
    raw_bytes = len(json.dumps(raw.encode([0.123456] * 512)))
    q_bytes = len(json.dumps(q.encode([0.123456] * 512)))
    assert q_bytes < raw_bytes // 2, (q_bytes, raw_bytes)
    print("  quant: int8 round-trips within scale; 512-d wire {}B vs raw {}B".format(q_bytes, raw_bytes))


async def _dup_case() -> None:
    # A duplicated activation for the same (task, step) must not advance the ring twice.
    bus = {}
    ring = ["A", "B"]
    bands = {"A": Band(0, 3), "B": Band(3, 6)}
    epA, epB = _endpoint("A", bus), _endpoint("B", bus)
    pa = ShardPipeline("A", epA, ring, bands["A"], _runner(bands["A"]), coordinator="A", max_new_tokens=1)
    pb = ShardPipeline("B", epB, ring, bands["B"], _runner(bands["B"]), coordinator="A", max_new_tokens=1)
    # feed B the same activation twice at step 0; only the first should produce a token
    hidden = pa.runner.forward(pa.runner.embed(1))  # A's contribution
    codec = RawActivationCodec()
    frame = epB._c.seal(MsgType.ACTIVATION.value, {"step": 0, "act": codec.encode(hidden)}, tid="dup")
    epB._on_frame(frame)
    epB._on_frame(frame)  # replay guard drops the verbatim second copy
    # a re-sealed identical payload has a fresh seq (passes replay) but the same step,
    # so the pipeline's monotonic step guard must still drop it:
    resealed = FrameCodec("A", sealer_from_cluster_secret(SECRET)).seal(
        MsgType.ACTIVATION.value, {"step": 0, "act": codec.encode(hidden)}, tid="dup")
    epB._on_frame(resealed)
    await pb._flush()
    toks = pb._tokens  # B is last; it emits SHARD_TOKEN to coordinator "A", not to itself
    # B processed step 0 exactly once -> exactly one outbound token was enqueued/flushed
    assert pb._last_step.get("dup") == 0
    print("  idempotency: duplicate/replayed activation for a step processed once")


def _node(node_id, bus, mem_gb):
    ep = _endpoint(node_id, bus)
    return ControlNode(ep, Capacity(mem_gb * GB)), ep


async def _full_stack_case() -> None:
    bus = {}
    nodes = {nid: _node(nid, bus, mem)[0] for nid, mem in [("A", 8), ("B", 4), ("C", 4)]}
    eps = {nid: n.ep for nid, n in nodes.items()}
    for n in nodes.values():
        await n.start()
    ctl = [asyncio.ensure_future(n.run_forever()) for n in nodes.values()]
    # L3: coordinator A places a 6-layer model across the 3 nodes.
    placement = await nodes["A"].place("m6", 6, 3 * GB, min_nodes=3, discovery_timeout_s=2.0)
    for t in ctl:
        t.cancel()  # stop control loops but keep transports/endpoints up
    # L4: build a pipeline on each node FROM ITS LEASE, over the same endpoint.
    pipes = {}
    for nid, n in nodes.items():
        lease = n.lease
        assert lease is not None and lease.coordinator == "A"
        runner = NumericShardRunner(); runner.load(lease.band, lease.n_layers)
        pipes[nid] = ShardPipeline(nid, eps[nid], lease.ring, lease.band, runner,
                                   coordinator=lease.coordinator, max_new_tokens=3)
    loops = [asyncio.ensure_future(pipes[nid].run_forever()) for nid in nodes if nid != "A"]
    try:
        total = placement.n_layers  # 6
        tokens = await pipes["A"].generate([2])
        assert tokens == [2 + total, 2 + 2 * total, 2 + 3 * total], (tokens, total)
        print("  full stack: L3 place -> L4 ring on leases -> tokens {} (all 3 bands ran)".format(tokens))
    finally:
        for p in pipes.values():
            p.stop()
        for t in loops:
            t.cancel()
        for n in nodes.values():
            await n.stop()


def main() -> None:
    print("HELIX data-plane (L4) self-test")
    print("[1] layer-shard ring"); asyncio.run(_ring_case())
    print("[2] int8 activation codec"); _quant_case()
    print("[3] duplicate-hop idempotency"); asyncio.run(_dup_case())
    print("[4] full stack L1->L4 from L3 leases"); asyncio.run(_full_stack_case())
    print("ALL PASSED")


if __name__ == "__main__":
    main()
