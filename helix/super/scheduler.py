"""Power-distribution scheduler — split devices between Track B (sharded ring) and Track A.

This is the piece the GPT review (rightly) called the crux: decide *which devices form the
big sharded model* and *which become edge agents*, by capability — not just memory. Strong,
cool, plugged devices with enough combined (attested) memory form the **Track B ring**; the
rest become **Track A agents**; overheating / low-battery devices are excluded. ``should_migrate``
turns the existing lease/heal machinery into *soft-signal* migration (thermal/battery), the
"dynamic layer migration" the review asked for.

Reuses :func:`~helix.placement.plan_placement` for the ring's memory-weighted bands.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Mapping

from helix.placement import Band, Capacity, InsufficientCapacity, plan_placement
from helix.super.capability import Capability
from helix.super.config import MeshConfig


@dataclass
class Allocation:
    ring: List[str] = field(default_factory=list)        # Track B (sharded) order
    bands: Dict[str, Band] = field(default_factory=dict)
    agents: List[str] = field(default_factory=list)      # Track A
    excluded: List[str] = field(default_factory=list)    # too hot / low battery
    reason: str = ""


def _eligible(cap: Capability, cfg: MeshConfig) -> bool:
    if cap.thermal > cfg.thermal_max:
        return False
    if cap.battery < cfg.battery_min and not cap.plugged:
        return False
    return True


def _ring_score(cap: Capability) -> float:
    """Prefer strong, cool, plugged, roomy devices for the heavy sharded ring."""
    return (cap.compute_score
            * (1.0 - cap.thermal)
            * (1.5 if cap.plugged else 1.0)
            * (1.3 if (cap.npu or cap.gpu) else 1.0)
            + cap.mem_bytes / (2 ** 30) * 0.05)


def should_migrate(cap: Capability, cfg: MeshConfig) -> bool:
    """Soft-signal trigger: a placed node that overheats / drains should be re-placed away."""
    return cap.thermal > cfg.thermal_max or (cap.battery < cfg.battery_min and not cap.plugged)


def plan_allocation(
    devices: Mapping[str, Capability],
    model_bytes: int,
    n_layers: int,
    cfg: MeshConfig,
) -> Allocation:
    excluded = [nid for nid, c in devices.items() if not _eligible(c, cfg)]
    eligible = {nid: c for nid, c in devices.items() if _eligible(c, cfg)}
    if not eligible:
        return Allocation(excluded=excluded, reason="no eligible devices")

    per_layer = model_bytes / max(1, n_layers)
    # Ring candidates must be able to hold at least one layer (avoids min-1 OOM in placement).
    ranked = sorted(
        (nid for nid, c in eligible.items() if c.mem_bytes >= per_layer),
        key=lambda nid: _ring_score(eligible[nid]), reverse=True,
    )

    ring: List[str] = []
    mem = 0
    for nid in ranked:
        if len(ring) >= min(cfg.ring_max_nodes, n_layers):
            break
        ring.append(nid)
        mem += eligible[nid].mem_bytes
        if mem >= model_bytes:
            break

    bands: Dict[str, Band] = {}
    reason = ""
    if mem >= model_bytes and ring:
        members = {nid: Capacity(eligible[nid].mem_bytes) for nid in ring}
        try:
            placement = plan_placement(members, "super", n_layers, model_bytes, order=ring)
            ring, bands = list(placement.ring), placement.bands
        except InsufficientCapacity as exc:
            ring, bands, reason = [], {}, "ring placement failed: {}".format(exc)
    else:
        ring, reason = [], "insufficient combined memory for Track B ring"

    agents = [nid for nid in eligible if nid not in ring][: cfg.max_agents]
    return Allocation(ring=ring, bands=bands, agents=agents, excluded=excluded, reason=reason)
