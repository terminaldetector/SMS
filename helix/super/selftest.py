"""Self-test for the SuperAgent experimental mode.

    python -m helix.super.selftest

S1 (this file, extended in S2): config validation, the power-distribution scheduler, and the
soft-migration trigger.
"""

from __future__ import annotations

from helix.super.capability import Capability
from helix.super.config import MeshConfig
from helix.super.scheduler import plan_allocation, should_migrate

GB = 2 ** 30


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


def main() -> None:
    print("HELIX SuperAgent self-test")
    print("[1] config"); test_config()
    print("[2] scheduler (power distribution)"); test_scheduler()
    print("[3] soft-migration trigger"); test_migration()
    print("ALL PASSED")


if __name__ == "__main__":
    main()
