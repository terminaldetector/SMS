"""Coordinator bridge — verify a foreign (JS/native) HELIX agent over a real socket.

The server side of the ChatterUI *Level 2* integration. A single TCP connection carries a
point-to-point HELIX stream (``StreamTransport``): this process is the **coordinator**, and the
client is one **agent** (e.g. the JS ``AgentNode`` in
``integration/chatterui_llamacpp/js/agent_node.mjs``). Once the agent announces a ``chat`` card,
the coordinator drives it through all four Track-A modes and checks the results, proving the
foreign agent speaks the whole HELIX agent protocol (announce / TASK / PARTIAL / RESULT / VOTE).

    python -m helix.host.agent_bridge_demo [--port 0]   # prints PORT, verifies, exits 0/1

The foreign agent is expected to uppercase the prompt (a deterministic reference "skill").
"""

from __future__ import annotations

import argparse
import asyncio
import sys

from helix.agent.card import AgentCard
from helix.agent.node import AgentNode
from helix.agent.runner import EchoAgentRunner
from helix.endpoint import Endpoint
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec
from helix.transport.stream import StreamTransport

SECRET = b"helix-agent-bridge-demo"  # must match the JS side


def _ok(what: str) -> None:
    print("  ok  " + what, flush=True)


async def _verify(coord: AgentNode) -> bool:
    await coord.wait_for_agents(1, 5.0, skill="chat")
    live = [a for a in coord.registry.live() if a != coord.node_id]
    if not live:
        print("  FAIL: no foreign chat agent announced", flush=True)
        return False
    _ok("foreign agent announced: {}".format(live[0]))

    # single
    res, meta = await coord.run_single("hello world", skill="chat", timeout_s=5.0)
    assert res == "HELLO WORLD", (res, meta)
    _ok('single -> "{}"'.format(res))

    # PARTIAL streaming actually flowed (white-box: partials were opened & routed)
    assert any(c["partials"] for c in coord._coll.values()), "no PARTIAL frames received"
    _ok("PARTIAL frames streamed from the agent")

    # parallel (one capable agent -> aggregate of one)
    res, meta = await coord.run_parallel("hi there", skill="chat", timeout_s=5.0)
    assert res == "HI THERE", (res, meta)
    _ok('parallel -> "{}"'.format(res))

    # voting (agent returns candidate+score; coordinator decides)
    res, meta = await coord.run_voting("vote me", skill="chat", timeout_s=5.0)
    assert res == "VOTE ME", (res, meta)
    _ok('voting -> "{}" ({} vote/s)'.format(res, meta.get("votes")))

    # pipeline (two chat stages; uppercase is idempotent)
    res, meta = await coord.run_pipeline("stage it", [{"skill": "chat"}, {"skill": "chat"}], timeout_s=5.0)
    assert res == "STAGE IT", (res, meta)
    _ok('pipeline -> "{}"'.format(res))
    return True


async def _main(host: str, port: int) -> int:
    result: dict = {"ok": False}
    ready = asyncio.Event()

    async def on_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        if ready.is_set():
            writer.close()
            return
        ready.set()
        transport = StreamTransport("coord", reader, writer)
        ep = Endpoint(transport, FrameCodec("coord", sealer_from_cluster_secret(SECRET)))
        card = AgentCard("coord", skills=["route"], task_types=["route"])
        coord = AgentNode(ep, EchoAgentRunner(card), announce_interval_s=0.1, ttl_s=3.0)
        await coord.start()
        loop = asyncio.ensure_future(coord.run_forever())
        try:
            result["ok"] = await _verify(coord)
        except Exception as exc:  # noqa: BLE001 - report and fail
            print("  FAIL: {}".format(exc), flush=True)
            result["ok"] = False
        finally:
            loop.cancel()
            await coord.stop()
            stop_evt.set()

    stop_evt = asyncio.Event()
    server = await asyncio.start_server(on_client, host, port)
    bound = server.sockets[0].getsockname()[1]
    print("PORT {}".format(bound), flush=True)
    try:
        await asyncio.wait_for(stop_evt.wait(), timeout=30.0)
    except asyncio.TimeoutError:
        print("  FAIL: timed out waiting for agent", flush=True)
    server.close()
    if result["ok"]:
        print("ALL PASSED", flush=True)
        return 0
    return 1


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=0)
    args = ap.parse_args()
    sys.exit(asyncio.run(_main(args.host, args.port)))
