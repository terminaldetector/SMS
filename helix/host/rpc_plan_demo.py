"""RPC-plan demo — the Track B / Option A control plane over a socket.

Stands up a small Track-B mesh: a coordinator :class:`ControlNode` plus a few members, each
announcing its capacity **and** an rpc-server address (``rpc`` in ANNOUNCE). Once the coordinator
has heard everyone, it exposes the control plane on TCP; a client (e.g. the JS
``rpc_smoke.mjs``) issues ``rpc_plan`` and gets back the llama.cpp cluster topology
(``ring`` / ``--rpc`` / ``--tensor-split`` / ``main``) that a native cui-llama.rn driver would run.

    python -m helix.host.rpc_plan_demo [--port 0]   # prints PORT, serves until killed
"""

from __future__ import annotations

import argparse
import asyncio

from helix.control import ControlNode
from helix.endpoint import Endpoint
from helix.host.control_server import ControlServer, agent_dispatch
from helix.placement import Capacity
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec
from helix.transport.memory import InMemoryTransport

SECRET = b"helix-rpc-plan-demo"
GB = 2 ** 30

# node_id -> (memory GB, rpc-server addr). Memory-weighted 8:4:4 over the demo model.
_NODES = {
    "hlxA": (8, "10.0.0.1:50052"),
    "hlxB": (4, "10.0.0.2:50052"),
    "hlxC": (4, "10.0.0.3:50052"),
}


def _node(node_id, bus, mem_gb, addr):
    ep = Endpoint(InMemoryTransport(node_id, bus), FrameCodec(node_id, sealer_from_cluster_secret(SECRET)))
    return ControlNode(ep, Capacity(mem_gb * GB), announce_interval_s=0.1, ttl_s=3.0, rpc_addr=addr)


async def _main(host: str, port: int) -> None:
    bus: dict = {}
    nodes = {nid: _node(nid, bus, mem, addr) for nid, (mem, addr) in _NODES.items()}
    for n in nodes.values():
        await n.start()
    loops = [asyncio.ensure_future(n.run_forever()) for n in nodes.values()]

    coord = nodes["hlxA"]
    await coord.wait_for_members(len(_NODES), 3.0)

    server = ControlServer(agent_dispatch(control=coord), host=host, port=port)
    await server.start()
    print("PORT {}".format(server.port), flush=True)
    try:
        await asyncio.Event().wait()  # serve until killed
    finally:
        await server.stop()
        for n in nodes.values():
            await n.stop()
        for t in loops:
            t.cancel()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=0)
    args = ap.parse_args()
    try:
        asyncio.run(_main(args.host, args.port))
    except KeyboardInterrupt:
        pass
