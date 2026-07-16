"""Runnable self-test for the HELIX L5 session / orchestrator layer.

    python -m helix.orchestrator_selftest

Covers streaming generation with an explicit terminal status, the single-node case,
a failed placement, and mid-generation self-healing (a node drops and the session
completes anyway, with the same output as the fault-free run).
"""

from __future__ import annotations

import asyncio

from helix.control import ControlNode
from helix.endpoint import Endpoint
from helix.orchestrator import ModelSpec, Orchestrator, SessionStatus
from helix.placement import Band, Capacity
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec
from helix.shard import NumericShardRunner
from helix.transport.memory import InMemoryTransport

SECRET = b"helix-l5-selftest-secret"
GB = 2 ** 30


def _make_runner(band: Band, n_layers: int, model_id: str = "", model_path: str = "") -> NumericShardRunner:
    r = NumericShardRunner()
    r.load(band, n_layers)
    return r


def _orch(node_id, bus, mem_gb, model, **kw):
    ep = Endpoint(InMemoryTransport(node_id, bus), FrameCodec(node_id, sealer_from_cluster_secret(SECRET)))
    control = ControlNode(ep, Capacity(mem_gb * GB), **kw)
    return Orchestrator(control, _make_runner, model)


async def _stream_case() -> None:
    bus = {}
    model = ModelSpec("m6", 6, 3 * GB)
    orchs = {nid: _orch(nid, bus, mem, model) for nid, mem in [("A", 8), ("B", 4), ("C", 4)]}
    for o in orchs.values():
        await o.control.start()
    runs = [asyncio.ensure_future(o.run()) for o in orchs.values()]
    try:
        session = await orchs["A"].generate([5], max_new_tokens=4, min_nodes=3, discovery_timeout_s=2.0)
        streamed = []
        async for step, token, text in session.stream():
            streamed.append(token)
        status = await session.wait()
        # 6 layers total, +6/step from seed 5 -> 11, 17, 23, 29
        assert status == SessionStatus.COMPLETED, status
        assert session.token_list() == [11, 17, 23, 29], session.token_list()
        assert streamed == [11, 17, 23, 29], streamed
        assert session.heals == 0
        print("  stream: 3 nodes -> streamed {} (COMPLETED, explicit status)".format(streamed))
    finally:
        for o in orchs.values():
            await o.stop()
        for r in runs:
            r.cancel()


async def _single_node_case() -> None:
    bus = {}
    model = ModelSpec("solo", 4, GB)
    o = _orch("solo", bus, 8, model)
    await o.control.start()
    run = asyncio.ensure_future(o.run())
    try:
        session = await o.generate([2], max_new_tokens=3, min_nodes=1, discovery_timeout_s=0.5)
        status = await session.wait()
        assert status == SessionStatus.COMPLETED, status
        # one node holds all 4 layers -> +4/step from 2 -> 6, 10, 14
        assert session.token_list() == [6, 10, 14], session.token_list()
        print("  single node: world_size=1 -> {} (COMPLETED)".format(session.token_list()))
    finally:
        await o.stop(); run.cancel()


async def _failed_case() -> None:
    bus = {}
    model = ModelSpec("huge", 4, 100 * GB)  # far more than the node has
    o = _orch("small", bus, 1, model)
    await o.control.start()
    run = asyncio.ensure_future(o.run())
    try:
        session = await o.generate([1], max_new_tokens=3, min_nodes=1, discovery_timeout_s=0.5)
        status = await session.wait()
        assert status == SessionStatus.FAILED, status
        print("  failed placement: surfaced as SessionStatus.FAILED (not a silent hang)")
    finally:
        await o.stop(); run.cancel()


async def _healing_case() -> None:
    bus = {}
    model = ModelSpec("m6", 6, 3 * GB)
    kw = dict(heartbeat_interval_s=0.08, ttl_s=0.3)
    orchs = {nid: _orch(nid, bus, mem, model, **kw) for nid, mem in [("A", 8), ("B", 4), ("C", 4)]}
    for o in orchs.values():
        o.step_timeout_s = 3.0
        await o.control.start()
    runs = {nid: asyncio.ensure_future(o.run()) for nid, o in orchs.items()}
    try:
        session = await orchs["A"].generate([5], max_new_tokens=20, min_nodes=3, discovery_timeout_s=2.0)
        killed = False
        streamed = []
        async for step, token, text in session.stream():
            streamed.append(token)
            # after a few tokens, drop a satellite mid-generation
            if not killed and len(streamed) >= 3:
                victim = "C" if "C" in orchs else "B"
                await orchs[victim].stop()
                runs[victim].cancel()
                del orchs[victim]
                killed = True
        status = await session.wait()
        assert status == SessionStatus.COMPLETED, status
        # +6/step from seed 5 for 20 steps, regardless of how bands were re-split on heal
        assert session.token_list() == [5 + 6 * k for k in range(1, 21)], session.token_list()
        assert session.heals >= 1, session.heals
        print("  healing: node dropped mid-gen, {} heal(s), completed with correct {} tokens".format(
            session.heals, len(session.token_list())))
    finally:
        for o in orchs.values():
            await o.stop()
        for r in runs.values():
            r.cancel()


def main() -> None:
    print("HELIX orchestrator (L5) self-test")
    print("[1] streaming generation + explicit status"); asyncio.run(_stream_case())
    print("[2] single-node generation"); asyncio.run(_single_node_case())
    print("[3] failed placement -> FAILED"); asyncio.run(_failed_case())
    print("[4] mid-generation self-healing"); asyncio.run(_healing_case())
    print("ALL PASSED")


if __name__ == "__main__":
    main()
