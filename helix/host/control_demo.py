"""Run a real HELIX control server with demo agents — for cross-language client bring-up.

Starts a coordinator + a couple of echo agents over InMemory transport, exposes the control
plane on a TCP port, prints ``PORT <n>`` (so a launcher can read it), then serves until killed.
This is the server side of the ChatterUI *Level 1* integration: a TS/JS client connects here and
drives the mesh with JSON-lines (see ``integration/chatterui_llamacpp/js/control_smoke.mjs``).

    python -m helix.host.control_demo [--port 0] [--host 127.0.0.1]
"""

from __future__ import annotations

import argparse
import asyncio

from helix.agent.card import AgentCard
from helix.agent.node import AgentNode
from helix.agent.runner import EchoAgentRunner
from helix.endpoint import Endpoint
from helix.host.control_server import ControlServer, agent_dispatch
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec
from helix.super.config import MeshConfig
from helix.super.superagent import AgentNodeTrackA, CallableTrackB, SuperAgent
from helix.transport.memory import InMemoryTransport

SECRET = b"helix-control-demo"


def _agent(node_id, bus, transform, skills):
    ep = Endpoint(InMemoryTransport(node_id, bus), FrameCodec(node_id, sealer_from_cluster_secret(SECRET)))
    card = AgentCard(node_id, skills=skills, task_types=["chat"])
    return AgentNode(ep, EchoAgentRunner(card, transform=transform), announce_interval_s=0.1, ttl_s=2.0)


async def _main(host: str, port: int) -> None:
    bus: dict = {}
    coord = _agent("coord", bus, lambda p, c: p, ["route"])
    up = _agent("up", bus, lambda p, c: p.upper(), ["chat"])
    lo = _agent("lo", bus, lambda p, c: p.lower(), ["chat"])
    for n in (coord, up, lo):
        await n.start()
    loops = [asyncio.ensure_future(n.run_forever()) for n in (coord, up, lo)]

    async def big_model(prompt: str) -> str:
        return "BIG(" + prompt + ")"

    superagent = SuperAgent(CallableTrackB(big_model), AgentNodeTrackA(coord, skill="chat"), MeshConfig())
    server = ControlServer(agent_dispatch(coord, superagent=superagent), host=host, port=port)
    await server.start()
    await coord.wait_for_agents(3, 3.0)
    print("PORT {}".format(server.port), flush=True)
    try:
        await asyncio.Event().wait()  # serve until killed
    finally:
        await server.stop()
        for n in (coord, up, lo):
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
