"""Control server (PC) — drive the HELIX stack over newline-delimited JSON.

The PC runs the Python stack directly and exposes it on a local TCP socket (or, in
production, a Windows named pipe). The PowerShell module ``Helix.psm1`` connects and sends one
JSON request per line, getting one JSON response per line:

    -> {"cmd":"infer","prompt":"...","mode":"single","skill":"chat"}
    <- {"ok":true,"result":"..."}

Commands: ``ping`` / ``status`` / ``nodes`` / ``context`` / ``infer``. The transport binds to
``127.0.0.1`` only (localhost) — it is the local control plane, not a mesh port.

    python -m helix.host.control_server    # runs a self-test (server + client over InMemory)
"""

from __future__ import annotations

import asyncio
import json
from typing import Any, Awaitable, Callable, Dict, Optional

from helix.agent.edge_bootstrap import coordinate
from helix.agent.node import AgentNode
from helix.log import get_logger

logger = get_logger("host.control")

Dispatch = Callable[[Dict[str, Any]], Awaitable[Dict[str, Any]]]

_INFER_KEYS = ("skill", "task_type", "agent_ids", "n", "prefer", "context", "timeout_s")


def agent_dispatch(node: AgentNode, *, superagent: Any = None) -> Dispatch:
    """Build a command dispatcher bound to a coordinator :class:`AgentNode`.

    If ``superagent`` is provided, the ``super`` command runs one prompt across Track A + Track B
    (the experimental unified mode).
    """

    async def dispatch(req: Dict[str, Any]) -> Dict[str, Any]:
        cmd = req.get("cmd")
        if cmd == "ping":
            return {"ok": True, "pong": True}
        if cmd == "super":
            if superagent is None:
                return {"ok": False, "error": "superagent not configured"}
            res = await superagent.run(str(req.get("prompt", "")), req.get("strategy"))
            return {"ok": True, "result": res.text, "strategy": res.strategy, "status": res.status}
        if cmd == "status":
            return {"ok": True, "node_id": node.node_id, "agents": len(node.registry.live())}
        if cmd == "nodes":
            return {"ok": True, "agents": node.registry.live()}
        if cmd == "context":
            await node.context_append(str(req.get("role", "user")), str(req.get("content", "")))
            return {"ok": True}
        if cmd == "infer":
            kw = {k: req[k] for k in _INFER_KEYS if k in req}
            result = await coordinate(node, str(req.get("prompt", "")),
                                      str(req.get("mode", "single")),
                                      stages=req.get("stages"), **kw)
            return {"ok": True, "result": result}
        return {"ok": False, "error": "unknown cmd: {}".format(cmd)}

    return dispatch


class ControlServer:
    def __init__(self, dispatch: Dispatch, *, host: str = "127.0.0.1", port: int = 0) -> None:
        self._dispatch = dispatch
        self._host = host
        self._port = port
        self._server: Optional[asyncio.AbstractServer] = None

    @property
    def port(self) -> int:
        return self._port

    async def start(self) -> None:
        self._server = await asyncio.start_server(self._on_client, self._host, self._port)
        self._port = self._server.sockets[0].getsockname()[1]
        logger.info("control server on %s:%d", self._host, self._port)

    async def stop(self) -> None:
        if self._server is not None:
            self._server.close()
            self._server = None

    async def _on_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            while True:
                line = await reader.readline()
                if not line:
                    break
                try:
                    req = json.loads(line)
                    resp = await self._dispatch(req)
                except ValueError:
                    resp = {"ok": False, "error": "invalid json"}
                except Exception as exc:  # a command failed — report, don't drop the connection
                    resp = {"ok": False, "error": str(exc)}
                writer.write((json.dumps(resp) + "\n").encode("utf-8"))
                await writer.drain()
        except (asyncio.IncompleteReadError, ConnectionResetError):
            pass
        finally:
            writer.close()


def _selftest() -> None:
    from helix.agent.card import AgentCard
    from helix.agent.node import AgentNode as _AN
    from helix.agent.runner import EchoAgentRunner
    from helix.endpoint import Endpoint
    from helix.sealer import sealer_from_cluster_secret
    from helix.session import FrameCodec
    from helix.transport.memory import InMemoryTransport

    SECRET = b"helix-control-server-selftest"

    def agent(node_id, bus, transform, skills):
        ep = Endpoint(InMemoryTransport(node_id, bus), FrameCodec(node_id, sealer_from_cluster_secret(SECRET)))
        card = AgentCard(node_id, skills=skills, task_types=["chat"])
        return _AN(ep, EchoAgentRunner(card, transform=transform), announce_interval_s=0.1, ttl_s=2.0)

    async def main():
        bus = {}
        coord = agent("coord", bus, lambda p, c: p, ["route"])
        up = agent("up", bus, lambda p, c: p.upper(), ["chat"])
        for n in (coord, up):
            await n.start()
        loops = [asyncio.ensure_future(n.run_forever()) for n in (coord, up)]
        # unified mode: Track A = the real coordinator, Track B = a stand-in big model
        from helix.super.config import MeshConfig
        from helix.super.superagent import AgentNodeTrackA, CallableTrackB, SuperAgent

        async def big_model(prompt):
            return "BIG(" + prompt + ")"

        superagent = SuperAgent(CallableTrackB(big_model), AgentNodeTrackA(coord, skill="chat"),
                                MeshConfig())
        server = ControlServer(agent_dispatch(coord, superagent=superagent))
        await server.start()
        r, w = await asyncio.open_connection("127.0.0.1", server.port)

        async def call(obj):
            w.write((json.dumps(obj) + "\n").encode()); await w.drain()
            return json.loads(await r.readline())

        try:
            assert (await call({"cmd": "ping"}))["pong"] is True
            await coord.wait_for_agents(2, 2.0)
            nodes = await call({"cmd": "nodes"})
            assert "up" in nodes["agents"], nodes
            res = await call({"cmd": "infer", "prompt": "hello", "mode": "single", "skill": "chat"})
            assert res["ok"] and res["result"] == "HELLO", res
            sup = await call({"cmd": "super", "prompt": "hello", "strategy": "ensemble"})
            assert sup["ok"] and "BIG(hello)" in sup["result"] and "HELLO" in sup["result"], sup
            bad = await call({"cmd": "nope"})
            assert bad["ok"] is False
            print("  control: ping/nodes/infer/super over JSON-lines; bad cmd handled")
            print("ALL PASSED")
        finally:
            w.close()
            await server.stop()
            for n in (coord, up):
                await n.stop()
            for t in loops:
                t.cancel()

    asyncio.run(main())


if __name__ == "__main__":
    _selftest()
