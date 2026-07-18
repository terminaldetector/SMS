"""Agent-host demo — the coordinator side of a ChatterUI *Level 2* first experiment.

A phone running the HELIX agent (its GGUF model joining the mesh) connects here over TCP and
announces itself as a Track-A agent. This process is the **coordinator**: it also exposes the L1
HTTP control plane, so you POST a prompt and it routes the task to the phone agent, whose model
produces the answer — the mesh, driven from anywhere:

    phone (agent)  --TCP-->  coordinator  <--HTTP--  you (curl / another device)

    python -m helix.host.agent_host_demo [--tcp-port 8790] [--http-port 8799] [--host 0.0.0.0]

Prints ``TCP_PORT n`` immediately (connect the phone agent there), then ``HTTP_PORT m`` once an
agent has joined (POST /cmd there). Same ``SECRET`` must be used on both sides.
"""

from __future__ import annotations

import argparse
import asyncio
import threading
from http.server import ThreadingHTTPServer

from helix.agent.card import AgentCard
from helix.agent.node import AgentNode
from helix.agent.runner import EchoAgentRunner
from helix.endpoint import Endpoint
from helix.host import http_control as httpc
from helix.host.control_server import agent_dispatch
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec
from helix.transport.stream import StreamTransport

SECRET = b"helix-agent-host-demo"  # must match the phone/JS side


async def _serve_tcp(host: str, tcp_port: int, ready: threading.Event, hold: dict) -> None:
    joined = asyncio.Event()

    async def on_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        if joined.is_set():
            writer.close()
            return
        transport = StreamTransport("coord", reader, writer)
        ep = Endpoint(transport, FrameCodec("coord", sealer_from_cluster_secret(SECRET)))
        # The coordinator is itself an AgentNode with a routing-only card (it delegates real work).
        card = AgentCard("coord", skills=["route"], task_types=["route"])
        coord = AgentNode(ep, EchoAgentRunner(card), announce_interval_s=0.2, ttl_s=4.0)
        await coord.start()
        hold["loop_task"] = asyncio.ensure_future(coord.run_forever())
        await coord.wait_for_agents(1, 15.0, skill="chat")
        joined.set()
        httpc._DISPATCH = agent_dispatch(coord)
        httpc._LOOP = asyncio.get_running_loop()
        hold["coord"] = coord
        ready.set()

    server = await asyncio.start_server(on_client, host, tcp_port)
    hold["tcp_port"] = server.sockets[0].getsockname()[1]
    print("TCP_PORT {}".format(hold["tcp_port"]), flush=True)
    async with server:
        await server.serve_forever()


def serve(host: str, tcp_port: int, http_port: int) -> None:
    loop = asyncio.new_event_loop()
    ready = threading.Event()
    hold: dict = {}

    def _run_loop() -> None:
        asyncio.set_event_loop(loop)
        loop.create_task(_serve_tcp(host, tcp_port, ready, hold))
        loop.run_forever()

    threading.Thread(target=_run_loop, daemon=True).start()
    if not ready.wait(60):
        raise RuntimeError("no agent joined in time")

    httpd = ThreadingHTTPServer((host, http_port), httpc._Handler)
    print("HTTP_PORT {}".format(httpd.server_address[1]), flush=True)
    print("agent joined; POST prompts to http://{}:{}/cmd".format(host, httpd.server_address[1]), flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
        loop.call_soon_threadsafe(loop.stop)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--tcp-port", type=int, default=8790)
    ap.add_argument("--http-port", type=int, default=8799)
    args = ap.parse_args()
    serve(args.host, args.tcp_port, args.http_port)
