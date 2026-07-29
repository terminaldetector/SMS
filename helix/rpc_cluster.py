"""RPC cluster planner (Track B / Option A) — HELIX as the control plane for llama.cpp RPC.

ChatterUI shards one big GGUF across phones by letting **llama.cpp's own** ``GGML_RPC`` do the
tensor split, while **HELIX supplies the topology**: which nodes participate, in what ring order,
with what per-node layer band — derived from memory (with the per-node RAM-fit check) and,
optionally, from **attested** memory (③) so a node cannot lie its way into a bigger band.

The plan is HELIX's own view of the split:

* ``rpc_arg``      — the ``--rpc host:port,host:port`` list of the worker rpc-servers.
* ``tensor_split`` — each **ring node's** share of the model, taken from its layer band, main first.
* ``main``         — the driver node (runs ``llama`` locally + offloads to the workers).

``tensor_split`` here spans the ring, which is **not** what llama.cpp's ``--tensor-split`` means —
see :func:`llama_rpc_args`, which translates a plan into llama.cpp's own arguments. That difference
was a real bug on real phones, and the docstring here used to state the wrong version of it.

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
    # The main process drives locally and offloads to the worker rpc-servers, so --rpc lists the
    # workers. These ratios are each ring node's share of the whole model — HELIX's view. Turning
    # them into llama.cpp's --tensor-split is llama_rpc_args()'s job, and is not the identity.
    rpc_arg = ",".join(addrs[nid] for nid in ring[1:])
    tensor_split = [placement.bands[nid].n / n_layers for nid in ring]

    return RpcClusterPlan(
        model_id=model_id, n_layers=n_layers, ring=list(ring), endpoints=endpoints,
        main=main, rpc_arg=rpc_arg, tensor_split=tensor_split,
    )


class UnshardablePlan(Exception):
    """A plan cannot be expressed as llama.cpp arguments (see :func:`llama_rpc_args`)."""


@dataclass(frozen=True)
class LlamaRpcArgs:
    """A plan as **llama.cpp** reads it. Mirrors ChatterUI/lib/helixShardArgs.ts."""

    rpc_servers: List[str]          # --rpc, the workers in ring order
    tensor_split: List[float]       # --tensor-split, one per REMOTE DEVICE, sums to 1
    devices: List[str]              # --device, the remote devices these ratios refer to
    n_gpu_layers: int               # -ngl, how many trailing layers may leave the driver
    host_layers: int                # layers the driver keeps on its own CPU
    remote_layers: int              # layers held by the workers

    def to_dict(self) -> Dict[str, Any]:
        return {
            "rpc_servers": list(self.rpc_servers), "tensor_split": list(self.tensor_split),
            "devices": list(self.devices), "n_gpu_layers": self.n_gpu_layers,
            "host_layers": self.host_layers, "remote_layers": self.remote_layers,
        }


def llama_rpc_args(
    plan: RpcClusterPlan | Mapping[str, Any],
    *,
    devices_per_endpoint: Optional[Mapping[str, List[str]]] = None,
) -> LlamaRpcArgs:
    """Translate a plan into llama.cpp's own arguments, or raise :class:`UnshardablePlan`.

    Two of llama.cpp's parameters do not mean what a plan makes them look like, and both mistakes
    produce a mesh that loads, answers, and runs on one phone:

    * ``--tensor-split`` is indexed by llama.cpp's **device** list, which is
      ``[rpc devices…, local GPUs…]`` — the driver's own CPU is not in it and nothing stands for
      "here". Passing a plan's ring-wide ratios therefore shifts every worker's share by one slot:
      the driver's went to the first worker, the last worker's fell off the end, and with a single
      worker the whole split collapsed onto it.
    * ``-ngl`` is how many **trailing** layers may leave the driver, not how many the model has.
      Passing the block count (or llama.cpp's "all of them" idiom, 99) leaves the driver holding
      nothing and asks the workers for the entire model.

    So: the driver keeps the main band on its CPU, ``-ngl`` is the size of the remote tail (plus one
    for llama.cpp's output layer, which always travels with it), and the ratios span the workers
    alone. ``devices_per_endpoint`` names the devices behind each address — one rpc-server can serve
    several (a phone offering its CPU *and* its GPU), and each takes a ``--tensor-split`` slot.
    """
    d = plan.to_dict() if isinstance(plan, RpcClusterPlan) else dict(plan)
    n_layers = int(d["n_layers"])
    endpoints = list(d["endpoints"])

    mains = [e for e in endpoints if e["role"] == "main"]
    if len(mains) != 1:
        raise UnshardablePlan("a plan needs exactly one main node, found {}".format(len(mains)))
    main = mains[0]
    if int(main["band"][0]) != 0:
        raise UnshardablePlan(
            "the main node holds layers {}-{}, but llama.cpp can only offload the END of a model "
            "— the driver has to hold the first band".format(main["band"][0], main["band"][1] - 1))

    host_layers = int(main["band"][1]) - int(main["band"][0])
    workers = [e for e in endpoints if e["role"] == "worker"]
    if not workers:
        raise UnshardablePlan("a plan with no workers is a local load, not a shard")

    # Ring order is pipeline order, and llama.cpp fills devices in ascending-layer order too, so the
    # worker bands must continue where the main band stopped, contiguously, in that same order.
    expected = host_layers
    for w in workers:
        if int(w["band"][0]) != expected:
            raise UnshardablePlan(
                "worker {} holds layers {}-{} but the split reaches it at layer {}".format(
                    w["node"], w["band"][0], w["band"][1] - 1, expected))
        expected = int(w["band"][1])
    if expected != n_layers:
        raise UnshardablePlan(
            "the bands stop at layer {} of {} — some layers belong to nobody".format(expected, n_layers))

    remote_layers = n_layers - host_layers
    if remote_layers <= 0:
        raise UnshardablePlan("every layer was planned onto the driver — nothing left to shard")

    devices: List[str] = []
    tensor_split: List[float] = []
    for i, w in enumerate(workers):
        names = list((devices_per_endpoint or {}).get(w["addr"], []))
        if not names:
            # No device list given (or an endpoint that answered with none): assume one device per
            # address, which is the single-backend case and what the CLI form implies.
            names = ["RPC{}".format(i)]
        ratio = (int(w["band"][1]) - int(w["band"][0])) / remote_layers
        for name in names:
            devices.append(name)
            tensor_split.append(ratio / len(names))

    return LlamaRpcArgs(
        rpc_servers=[w["addr"] for w in workers],
        tensor_split=tensor_split,
        devices=devices,
        n_gpu_layers=n_layers + 1 - host_layers,
        host_layers=host_layers,
        remote_layers=remote_layers,
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

    # llama.cpp's own arguments: the driver keeps its band locally, the workers split the tail.
    args = llama_rpc_args(plan)
    assert args.host_layers == 3 and args.remote_layers == 3, args
    # -ngl counts the trailing layers allowed to leave, plus llama.cpp's output layer: 6 + 1 - 3.
    assert args.n_gpu_layers == 4, args.n_gpu_layers
    assert args.rpc_servers == ["10.0.0.2:50052", "10.0.0.3:50052"], args.rpc_servers
    # Ratios span the WORKERS (2 layers and 1 layer of a 3-layer tail), not the ring.
    assert args.tensor_split == [2 / 3, 1 / 3], args.tensor_split
    assert abs(sum(args.tensor_split) - 1.0) < 1e-9
    assert args.devices == ["RPC0", "RPC1"], args.devices
    print("  llama args: -ngl {} --tensor-split {} (driver keeps {} layers)".format(
        args.n_gpu_layers, [round(x, 3) for x in args.tensor_split], args.host_layers))

    # A worker serving two backends behind one address takes two --tensor-split slots, not one.
    two = llama_rpc_args(plan, devices_per_endpoint={"10.0.0.2:50052": ["RPC0", "RPC1"],
                                                    "10.0.0.3:50052": ["RPC2"]})
    assert two.devices == ["RPC0", "RPC1", "RPC2"], two.devices
    assert two.tensor_split == [1 / 3, 1 / 3, 1 / 3], two.tensor_split
    assert abs(sum(two.tensor_split) - 1.0) < 1e-9
    print("  llama args: one address serving 2 devices gets 2 ratio slots")

    # A ring where the driver does NOT hold the first band cannot be expressed at all.
    reversed_plan = plan.to_dict()
    eps = [dict(e) for e in reversed_plan["endpoints"]]
    eps[0]["role"] = "worker"   # the node holding layers 0-2 is now a worker
    eps[-1]["role"] = "main"    # and the driver holds the LAST band, which cannot be offloaded around
    reversed_plan["endpoints"] = eps
    try:
        llama_rpc_args(reversed_plan)
        raise AssertionError("expected UnshardablePlan")
    except UnshardablePlan as exc:
        assert "first band" in str(exc) or "reaches it" in str(exc), exc
    print("  guard: a driver that does not hold the first band is refused, not mistranslated")

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
