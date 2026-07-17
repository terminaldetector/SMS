"""RPC cluster planner (Track B / Option A) — HELIX as the control plane for llama.cpp RPC.

ChatterUI shards one big GGUF across phones by letting **llama.cpp's own** ``GGML_RPC`` do the
tensor split, while **HELIX supplies the topology**: which nodes participate, in what ring order,
with what per-node layer band — derived from memory (with the per-node RAM-fit check) and,
optionally, from **attested** memory (③) so a node cannot lie its way into a bigger band.

The output is exactly what a native cui-llama.rn build needs to drive a distributed run:

* ``rpc_arg``      — the ``--rpc host:port,host:port`` list of the worker rpc-servers.
* ``tensor_split`` — ``--tensor-split`` ratios across ``[main-local, worker0, worker1, …]``
  (llama.cpp's split ordering), taken from each node's layer band.
* ``main``         — the driver node (runs ``llama`` locally + offloads to the workers).

This module is pure control-plane logic and is fully testable here; the tensor math and the RPC
transport are llama.cpp's (native, off-device in this env). See
``integration/chatterui_llamacpp/LEVEL3_sharding.md`` for the native side.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Mapping, Optional

from helix.placement import Band, Capacity, plan_placement


class UnaddressableCluster(Exception):
    """A planned ring node has no known rpc address — the ``--rpc`` list cannot be built."""


@dataclass(frozen=True)
class RpcEndpoint:
    node_id: str
    addr: str          # "host:port" of this node's llama.cpp rpc-server
    band: Band         # the layer band this node is intended to hold
    role: str          # "main" (driver) | "worker" (rpc-server)

    def to_dict(self) -> Dict[str, Any]:
        return {"node": self.node_id, "addr": self.addr,
                "band": [self.band.start, self.band.end], "role": self.role}


@dataclass(frozen=True)
class RpcClusterPlan:
    model_id: str
    n_layers: int
    ring: List[str]
    endpoints: List[RpcEndpoint]
    main: str
    rpc_arg: str                       # "h1:p1,h2:p2" — worker rpc-servers for --rpc
    tensor_split: List[float] = field(default_factory=list)  # ratios for --tensor-split

    def to_dict(self) -> Dict[str, Any]:
        return {
            "model_id": self.model_id, "n_layers": self.n_layers, "ring": list(self.ring),
            "endpoints": [e.to_dict() for e in self.endpoints], "main": self.main,
            "rpc_arg": self.rpc_arg, "tensor_split": list(self.tensor_split),
        }


def plan_rpc_cluster(
    members: Mapping[str, Capacity],
    addrs: Mapping[str, str],
    model_id: str,
    n_layers: int,
    model_bytes: int,
    *,
    attested: Optional[Mapping[str, int]] = None,
    order: Optional[List[str]] = None,
) -> RpcClusterPlan:
    """Plan a llama.cpp RPC cluster for one model, or raise.

    ``members``  — announced capacities (mem-weighted placement input).
    ``addrs``    — ``node_id -> "host:port"`` of each node's rpc-server (from ANNOUNCE ``rpc``).
    ``attested`` — if given, ``node_id -> proven bytes`` (from :func:`helix.attest.attested_capacities`);
                   only attested nodes are placed, and their **proven** memory is used, not the claim.

    Reuses :func:`helix.placement.plan_placement` for the ring + bands (so the per-node RAM-fit
    check and memory weighting are identical to Track B tensor sharding).
    """
    if attested is not None:
        caps: Dict[str, Capacity] = {}
        for nid, proven in attested.items():
            if nid not in members:
                continue  # attested but not currently a member -> ignore
            base = members[nid]
            caps[nid] = Capacity(mem_bytes=int(proven), cpu=base.cpu, npu=base.npu, battery=base.battery)
    else:
        caps = dict(members)

    placement = plan_placement(caps, model_id, n_layers, model_bytes, order=order)
    ring = placement.ring

    missing = [nid for nid in ring if not addrs.get(nid)]
    if missing:
        raise UnaddressableCluster(
            "no rpc address for ring node(s): {} (each participant must announce `rpc`)".format(missing))

    endpoints = [
        RpcEndpoint(nid, addrs[nid], placement.bands[nid], "main" if nid == ring[0] else "worker")
        for nid in ring
    ]
    main = ring[0]
    # llama.cpp: the main process drives locally and offloads to the worker rpc-servers; the
    # --rpc list is the workers, and --tensor-split spans [main-local, worker0, worker1, …].
    rpc_arg = ",".join(addrs[nid] for nid in ring[1:])
    tensor_split = [placement.bands[nid].n / n_layers for nid in ring]

    return RpcClusterPlan(
        model_id=model_id, n_layers=n_layers, ring=list(ring), endpoints=endpoints,
        main=main, rpc_arg=rpc_arg, tensor_split=tensor_split,
    )


# --------------------------------------------------------------------------- self-test
def _selftest() -> None:
    GB = 2 ** 30

    # Three phones, memory-weighted 8:4:4 over a 6-layer, 3 GB model -> bands 3/2/1 (largest first).
    members = {
        "hlxA": Capacity(8 * GB, cpu=4.0), "hlxB": Capacity(4 * GB, cpu=2.0),
        "hlxC": Capacity(4 * GB, cpu=2.0),
    }
    addrs = {"hlxA": "10.0.0.1:50052", "hlxB": "10.0.0.2:50052", "hlxC": "10.0.0.3:50052"}

    plan = plan_rpc_cluster(members, addrs, "big-16b", 6, 3 * GB, order=["hlxA", "hlxB", "hlxC"])
    assert plan.ring == ["hlxA", "hlxB", "hlxC"], plan.ring
    assert plan.main == "hlxA"
    bands = {e.node_id: (e.band.start, e.band.end) for e in plan.endpoints}
    assert bands == {"hlxA": (0, 3), "hlxB": (3, 5), "hlxC": (5, 6)}, bands
    assert plan.endpoints[0].role == "main" and plan.endpoints[1].role == "worker"
    # --rpc lists the workers (not the main driver)
    assert plan.rpc_arg == "10.0.0.2:50052,10.0.0.3:50052", plan.rpc_arg
    # --tensor-split spans [main, workers] and covers all layers
    assert plan.tensor_split == [3 / 6, 2 / 6, 1 / 6], plan.tensor_split
    assert abs(sum(plan.tensor_split) - 1.0) < 1e-9
    print("  plan: ring {} main={} split={} rpc={}".format(plan.ring, plan.main,
          [round(x, 3) for x in plan.tensor_split], plan.rpc_arg))

    # Attested path: B proved only 1 GB (lied in ANNOUNCE) -> planning by proof drops it below its
    # claimed band. Here attest C out entirely (no cert) and prove A=8G, B=4G -> ring is A,B only.
    attested = {"hlxA": 8 * GB, "hlxB": 4 * GB}  # C has no valid cert
    plan2 = plan_rpc_cluster(members, addrs, "big-16b", 6, 3 * GB, attested=attested,
                             order=["hlxA", "hlxB"])
    assert plan2.ring == ["hlxA", "hlxB"], plan2.ring
    assert plan2.rpc_arg == "10.0.0.2:50052", plan2.rpc_arg
    print("  attested: only proven nodes placed -> ring {}".format(plan2.ring))

    # Missing address -> UnaddressableCluster.
    try:
        plan_rpc_cluster(members, {"hlxA": "10.0.0.1:50052", "hlxB": "10.0.0.2:50052"},
                         "big-16b", 6, 3 * GB, order=["hlxA", "hlxB", "hlxC"])
        raise AssertionError("expected UnaddressableCluster")
    except UnaddressableCluster as exc:
        assert "hlxC" in str(exc), exc
    print("  guard: ring node without an rpc address is rejected")

    print("ALL PASSED")


if __name__ == "__main__":
    _selftest()
