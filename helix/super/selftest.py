"""Self-test for the SuperAgent experimental mode.

    python -m helix.super.selftest

S1 (this file, extended in S2): config validation, the power-distribution scheduler, and the
soft-migration trigger.
"""

from __future__ import annotations

import asyncio

from helix.super.capability import Capability
from helix.super.config import MeshConfig
from helix.super.scheduler import plan_allocation, should_migrate
from helix.super.superagent import ENSEMBLE, HIERARCHICAL, AgentNodeTrackA, SuperAgent

GB = 2 ** 30


class _FakeA:
    def __init__(self, transforms):
        self._t = transforms

    async def contributions(self, prompt):
        return {k: f(prompt) for k, f in self._t.items()}


class _FakeB:
    def __init__(self, fn):
        self._fn = fn

    async def generate(self, prompt):
        return self._fn(prompt)


def test_config() -> None:
    MeshConfig().validate()
    for bad in (MeshConfig(ttl=0), MeshConfig(thermal_max=1.5),
                MeshConfig(quant_level="fp32"), MeshConfig(default_strategy="nope")):
        try:
            bad.validate()
            raise AssertionError("expected ValueError for {}".format(bad))
        except ValueError:
            pass
    print("  config: valid defaults; bad values rejected")


def test_scheduler() -> None:
    cfg = MeshConfig()
    devices = {
        "gpu-plugged": Capability("gpu-plugged", mem_bytes=8 * GB, compute_score=4, gpu=True,
                                  thermal=0.1, plugged=True),
        "strong-cool": Capability("strong-cool", mem_bytes=6 * GB, compute_score=3, thermal=0.2),
        "hot":         Capability("hot", mem_bytes=8 * GB, compute_score=4, thermal=0.95),
        "low-batt":    Capability("low-batt", mem_bytes=6 * GB, compute_score=3, battery=0.1),
        "weak":        Capability("weak", mem_bytes=1 * GB, compute_score=1, thermal=0.1),
    }
    # 12 GB model needs more than one device -> a real sharded ring (the "3 monomesh" case).
    alloc = plan_allocation(devices, model_bytes=12 * GB, n_layers=6, cfg=cfg)
    # strong + cool + plugged form the Track B ring (cover 12 GB); hot / low-batt excluded;
    # the weak device becomes a Track A agent.
    assert set(alloc.ring) == {"gpu-plugged", "strong-cool"}, alloc.ring
    assert sum(b.n for b in alloc.bands.values()) == 6, alloc.bands
    assert set(alloc.excluded) == {"hot", "low-batt"}, alloc.excluded
    assert "weak" in alloc.agents, alloc.agents
    print("  scheduler: ring={}  agents={}  excluded={}".format(
        alloc.ring, alloc.agents, alloc.excluded))


def test_migration() -> None:
    cfg = MeshConfig()
    assert should_migrate(Capability("x", thermal=0.95), cfg)                 # overheating
    assert should_migrate(Capability("y", battery=0.05, plugged=False), cfg)  # draining
    assert not should_migrate(Capability("z", thermal=0.3, battery=0.9), cfg)
    assert not should_migrate(Capability("w", battery=0.05, plugged=True), cfg)  # low but plugged
    print("  migration: soft triggers fire on overheat / drain, not on healthy / plugged")


def test_hierarchical() -> None:
    a = _FakeA({"up": lambda p: p.upper(), "rev": lambda p: " ".join(reversed(p.split()))})
    b = _FakeB(lambda p: "BIG(" + p + ")")   # big model echoes the (augmented) prompt
    sup = SuperAgent(b, a, MeshConfig())
    res = asyncio.run(sup.run("hello world", HIERARCHICAL))
    # agents' notes were injected into the big model's prompt (provenance-labelled)
    assert res.strategy == HIERARCHICAL and res.status == "ok"
    assert "HELLO WORLD" in res.text and "world hello" in res.text, res.text
    assert res.text.startswith("BIG(hello world") and "# assistant notes" in res.text
    assert set(res.contributors["track_a"]) == {"up", "rev"}
    print("  hierarchical: 2 agents' notes fed the big model -> {}...".format(res.text[:38]))


def test_ensemble() -> None:
    a = _FakeA({"a1": lambda p: p.upper(), "a2": lambda p: p + "!"})
    b = _FakeB(lambda p: "BIG:" + p)
    sup = SuperAgent(b, a, MeshConfig())
    res = asyncio.run(sup.run("hello", ENSEMBLE))
    # both ran concurrently; default fuse = big model primary + agent cross-check
    assert res.strategy == ENSEMBLE and res.status == "ok"
    assert "BIG:hello" in res.text and "HELLO" in res.text and "hello!" in res.text, res.text
    assert res.contributors["track_b"] == "BIG:hello"
    print("  ensemble: big model + agent consensus fused -> {!r}".format(res.text))


async def _track_a_adapter() -> None:
    from helix.agent.card import AgentCard
    from helix.agent.node import AgentNode
    from helix.agent.runner import EchoAgentRunner
    from helix.endpoint import Endpoint
    from helix.sealer import sealer_from_cluster_secret
    from helix.session import FrameCodec
    from helix.transport.memory import InMemoryTransport

    SECRET = b"super-adapter-selftest"
    bus = {}

    def agent(nid, transform, skills):
        ep = Endpoint(InMemoryTransport(nid, bus), FrameCodec(nid, sealer_from_cluster_secret(SECRET)))
        return AgentNode(ep, EchoAgentRunner(AgentCard(nid, skills=skills, task_types=["chat"]),
                                             transform=transform), announce_interval_s=0.1, ttl_s=2.0)

    coord = agent("coord", lambda p, c: p, ["route"])
    up = agent("up", lambda p, c: p.upper(), ["chat"])
    bang = agent("bang", lambda p, c: p + " !", ["chat"])
    nodes = [coord, up, bang]
    for n in nodes:
        await n.start()
    loops = [asyncio.ensure_future(n.run_forever()) for n in nodes]
    try:
        await coord.wait_for_agents(3, 2.0)
        adapter = AgentNodeTrackA(coord, agent_ids=["up", "bang"])
        contribs = await adapter.contributions("hello")
        assert contribs == {"up": "HELLO", "bang": "hello !"}, contribs
        print("  adapter: AgentNodeTrackA.contributions -> {}".format(contribs))
    finally:
        for n in nodes:
            await n.stop()
        for t in loops:
            t.cancel()


def main() -> None:
    print("HELIX SuperAgent self-test")
    print("[1] config"); test_config()
    print("[2] scheduler (power distribution)"); test_scheduler()
    print("[3] soft-migration trigger"); test_migration()
    print("[4] superagent hierarchical"); test_hierarchical()
    print("[5] superagent ensemble"); test_ensemble()
    print("[6] Track A adapter (real coordinator)"); asyncio.run(_track_a_adapter())
    print("ALL PASSED")


if __name__ == "__main__":
    main()
