"""Runnable self-test for the HELIX Track-A agent-coordination (Pointer) layer.

    python -m helix.agent.selftest

Proves the Pointer Protocol rides the same HELIX substrate: discovery via authenticated
AGENT_ANNOUNCE, and the four coordination modes (single / parallel / voting / pipeline)
plus re-route healing when an agent drops mid-task.
"""

from __future__ import annotations

import asyncio

from helix.agent.card import AgentCard
from helix.agent.node import AgentNode
from helix.agent.runner import EchoAgentRunner
from helix.endpoint import Endpoint
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec
from helix.transport.memory import InMemoryTransport

SECRET = b"helix-agent-selftest-secret"


def _agent(node_id, bus, transform, *, skills, task_types, score=None):
    ep = Endpoint(InMemoryTransport(node_id, bus), FrameCodec(node_id, sealer_from_cluster_secret(SECRET)))
    card = AgentCard(node_id, models=["gemma-3n"], skills=skills, task_types=task_types)
    runner = EchoAgentRunner(card, transform=transform, score=score or (lambda p, r: float(len(r))))
    return AgentNode(ep, runner, announce_interval_s=0.1, ttl_s=0.4)


async def _discovery_and_modes() -> None:
    bus = {}
    up = _agent("up", bus, lambda p, c: p.upper(), skills=["case"], task_types=["transform"])
    rev = _agent("rev", bus, lambda p, c: " ".join(reversed(p.split())), skills=["order"], task_types=["transform"])
    bang = _agent("bang", bus, lambda p, c: p + " !", skills=["annotate"], task_types=["transform"])
    coord = _agent("coord", bus, lambda p, c: p, skills=["route"], task_types=["route"])
    agents = [up, rev, bang, coord]
    for a in agents:
        await a.start()
    tasks = [asyncio.ensure_future(a.run_forever()) for a in agents]
    try:
        await coord.wait_for_agents(4, 2.0)
        assert set(coord.registry.live()) >= {"up", "rev", "bang", "coord"}, coord.registry.live()

        # single: route to the case-changer
        res, meta = await coord.run_single("hello world", skill="case")
        assert res == "HELLO WORLD" and meta["status"] == "ok", (res, meta)

        # parallel: fan out to the two transform workers we name, aggregate deterministically
        agg, meta = await coord.run_parallel(
            "hello world", agent_ids=["up", "bang"],
            aggregate=lambda r: " || ".join(r[a] for a in sorted(r)))
        # aggregate joins by sorted agent id: "bang" before "up"
        assert agg == "hello world ! || HELLO WORLD" and meta["n"] == 2, (agg, meta)

        # voting: longest answer wins (score = len); "bang" adds " !" so it wins over "up"
        dec, meta = await coord.run_voting("hello world", agent_ids=["up", "bang"])
        assert dec == "hello world !" and meta["votes"] == 2, (dec, meta)

        # pipeline: upper-case, then annotate -> "HELLO WORLD !"
        out, meta = await coord.run_pipeline(
            "hello world", [{"skill": "case"}, {"skill": "annotate"}])
        assert out == "HELLO WORLD !" and meta["status"] == "ok", (out, meta)
        print("  modes: single/parallel/voting/pipeline all correct over sealed frames")
    finally:
        for a in agents:
            await a.stop()
        for t in tasks:
            t.cancel()


async def _healing() -> None:
    bus = {}
    # two interchangeable case-changers + a coordinator
    a1 = _agent("case1", bus, lambda p, c: p.upper(), skills=["case"], task_types=["transform"])
    a2 = _agent("case2", bus, lambda p, c: p.upper(), skills=["case"], task_types=["transform"])
    coord = _agent("coord", bus, lambda p, c: p, skills=["route"], task_types=["route"])
    agents = {"case1": a1, "case2": a2, "coord": coord}
    for a in agents.values():
        await a.start()
    tasks = {nid: asyncio.ensure_future(a.run_forever()) for nid, a in agents.items()}
    try:
        await coord.wait_for_agents(3, 2.0, skill="case")  # waits until both case agents are seen
        # Kill the primary WITHOUT waiting: it still looks live in the registry, so
        # run_single dispatches to it, the task goes nowhere, and after its liveness
        # lapses the coordinator must re-route the in-flight task to the survivor.
        first = coord._pick(skill="case")
        await agents[first].stop(); tasks[first].cancel(); del agents[first]
        res, meta = await coord.run_single("recover please", skill="case", prefer=first, timeout_s=3.0)
        assert res == "RECOVER PLEASE", (res, meta)
        assert meta["heals"] >= 1, meta  # a real re-route happened
        print("  healing: primary agent gone mid-task -> re-routed to survivor, completed {}".format(meta))
    finally:
        for a in agents.values():
            await a.stop()
        for t in tasks.values():
            t.cancel()


def main() -> None:
    print("HELIX Track-A agent-coordination (Pointer) self-test")
    print("[1] discovery + single/parallel/voting/pipeline"); asyncio.run(_discovery_and_modes())
    print("[2] re-route healing"); asyncio.run(_healing())
    print("ALL PASSED")


if __name__ == "__main__":
    main()
