"""Placement (L3): decide which node runs which band of layers.

Pure, dependency-free logic (ported/cleaned from exo-core's memory-weighted split)
with one deliberate improvement over the original: a **per-node RAM-fit check**. The
old exo-core placement only checked that the *combined* ring memory covered the model,
so the min-one-layer-per-node rule could hand a layer to a device that could not hold
it (AUDIT finding #8). :func:`plan_placement` rejects that here.

Ring order is currently deterministic (sorted node ids). Phase 6 replaces this with a
latency/energy-aware ordering (mini-TSP over measured RTT); the interface already takes
an optional explicit ``order`` so that swap needs no change here.

Note on trust: capacities come from ``ANNOUNCE`` and are still *self-declared*. A node
that lies about its memory is not caught by the per-node check (it claimed enough).
Closing that needs capability attestation — Phase 5 / HELIX ③.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional


@dataclass(frozen=True)
class Capacity:
    """What a node advertises. Memory is primary; the rest are Phase-6 scheduler inputs."""

    mem_bytes: int
    cpu: float = 0.0
    npu: bool = False
    battery: float = 1.0  # 0..1, 1.0 = plugged/full


@dataclass(frozen=True)
class Band:
    """A half-open band of layers ``[start, end)`` on one node."""

    start: int
    end: int

    @property
    def n(self) -> int:
        return self.end - self.start


@dataclass(frozen=True)
class Placement:
    model_id: str
    n_layers: int
    ring: List[str]
    bands: Dict[str, Band] = field(default_factory=dict)
    term: int = 0

    def band_for(self, node_id: str) -> Band:
        return self.bands[node_id]


class InsufficientCapacity(Exception):
    """Raised when no viable placement exists, with structure for the UI/caller."""

    def __init__(self, found: List[str], need_bytes: int, have_bytes: int, detail: str = "") -> None:
        self.found = found
        self.need_bytes = need_bytes
        self.have_bytes = have_bytes
        self.detail = detail
        super().__init__(
            "no viable placement: {} node(s) {}, need {:.2f} GB, have {:.2f} GB{}".format(
                len(found), found, need_bytes / 2**30, have_bytes / 2**30,
                " — " + detail if detail else "",
            )
        )


def allocate_layers(total_layers: int, weights: List[float]) -> List[int]:
    """Split ``total_layers`` across nodes proportionally to ``weights``.

    Largest-remainder method, guaranteeing at least one layer per node.
    """
    n = len(weights)
    if n == 0:
        raise ValueError("no nodes to allocate to")
    if total_layers < n:
        raise ValueError("cannot give >=1 layer to each of {} nodes with {} layers".format(n, total_layers))
    raw = [w * total_layers for w in weights]
    result = [int(r) for r in raw]
    order = sorted(range(n), key=lambda i: raw[i] - result[i], reverse=True)
    for i in range(total_layers - sum(result)):
        result[order[i]] += 1
    for i in range(n):
        if result[i] == 0:
            j = max(range(n), key=lambda k: result[k])
            result[j] -= 1
            result[i] = 1
    return result


def plan_placement(
    members: Dict[str, Capacity],
    model_id: str,
    n_layers: int,
    model_bytes: int,
    *,
    term: int = 0,
    order: Optional[List[str]] = None,
) -> Placement:
    """Compute a ring placement, or raise :class:`InsufficientCapacity`."""
    ids = list(order) if order is not None else sorted(members)
    if not ids:
        raise InsufficientCapacity([], model_bytes, 0, "no members")
    if len(ids) > n_layers:
        raise InsufficientCapacity(ids, model_bytes, sum(members[i].mem_bytes for i in ids),
                                   "more nodes ({}) than layers ({})".format(len(ids), n_layers))

    have = sum(members[i].mem_bytes for i in ids)
    if have < model_bytes:
        raise InsufficientCapacity(ids, model_bytes, have, "combined memory too small")

    weights = [members[i].mem_bytes / have for i in ids]
    alloc = allocate_layers(n_layers, weights)

    bands: Dict[str, Band] = {}
    cursor = 0
    for nid, count in zip(ids, alloc):
        bands[nid] = Band(cursor, cursor + count)
        cursor += count

    # Per-node RAM fit (AUDIT #8): a node's band must fit its own memory, not just the
    # ring total. Approximate the model as uniform bytes/layer.
    per_layer = model_bytes / n_layers
    for nid in ids:
        need = per_layer * bands[nid].n
        if members[nid].mem_bytes < need:
            raise InsufficientCapacity(
                ids, model_bytes, have,
                "node {} assigned {} layers (~{:.2f} GB) but has {:.2f} GB".format(
                    nid, bands[nid].n, need / 2**30, members[nid].mem_bytes / 2**30),
            )

    return Placement(model_id=model_id, n_layers=n_layers, ring=ids, bands=bands, term=term)
