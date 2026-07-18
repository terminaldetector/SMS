"""HTTP control adapter — drive the HELIX stack from a browser / React Native ``fetch``.

The JSON-lines TCP control server (:mod:`helix.host.control_server`) is perfect for PowerShell and
native clients, but a React Native app (ChatterUI) has no raw-TCP without a native module. This
adapter exposes the **same** command dispatch over plain HTTP so the app can talk to a HELIX node
with the built-in ``fetch`` — no extra native dependency (the ChatterUI *Level 1 / mesh mod*):

    POST /cmd   {"cmd":"infer","prompt":"...","mode":"single"}  ->  {"ok":true,"result":"..."}
    GET  /health                                                ->  {"ok":true,...}

It reuses :func:`helix.host.control_server.agent_dispatch`, so commands and semantics are identical
to the TCP server (``ping``/``status``/``nodes``/``context``/``infer``/``super``/``rpc_plan``).
Pure stdlib (``http.server``), so it stays Chaquopy-friendly. Binds ``127.0.0.1`` by default; pass
``--host 0.0.0.0`` to reach it from a phone on the same LAN (control plane only — not a mesh port).

    python -m helix.host.http_control [--host 0.0.0.0] [--port 8799]
"""

from __future__ import annotations

import argparse
import asyncio
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, Optional

from helix.agent.card import AgentCard
from helix.agent.node import AgentNode
from helix.agent.runner import EchoAgentRunner
from helix.endpoint import Endpoint
from helix.host.control_server import Dispatch, agent_dispatch
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec
from helix.super.config import MeshConfig
from helix.super.superagent import AgentNodeTrackA, CallableTrackB, SuperAgent
from helix.transport.memory import InMemoryTransport

SECRET = b"helix-http-control-demo"


def _agent(node_id, bus, transform, skills):
    ep = Endpoint(InMemoryTransport(node_id, bus), FrameCodec(node_id, sealer_from_cluster_secret(SECRET)))
    card = AgentCard(node_id, skills=skills, task_types=["chat"])
    return AgentNode(ep, EchoAgentRunner(card, transform=transform), announce_interval_s=0.1, ttl_s=2.0)


async def _build_demo_dispatch(hold: Dict[str, Any]) -> Dispatch:
    """Stand up a small demo mesh on the running loop and return its dispatcher."""
    bus: dict = {}
    coord = _agent("coord", bus, lambda p, c: p, ["route"])
    up = _agent("up", bus, lambda p, c: p.upper(), ["chat"])
    lo = _agent("lo", bus, lambda p, c: p.lower(), ["chat"])
    for n in (coord, up, lo):
        await n.start()
    hold["loops"] = [asyncio.ensure_future(n.run_forever()) for n in (coord, up, lo)]
    hold["nodes"] = [coord, up, lo]

    async def big_model(prompt: str) -> str:
        return "BIG(" + prompt + ")"

    superagent = SuperAgent(CallableTrackB(big_model), AgentNodeTrackA(coord, skill="chat"), MeshConfig())
    await coord.wait_for_agents(3, 3.0)
    return agent_dispatch(coord, superagent=superagent)


_DISPATCH: Optional[Dispatch] = None
_LOOP: Optional[asyncio.AbstractEventLoop] = None


class _Handler(BaseHTTPRequestHandler):
    def _send(self, code: int, obj: dict) -> None:
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")  # RN/browser cross-origin
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self) -> None:  # CORS preflight
        self._send(200, {"ok": True})

    def do_GET(self) -> None:
        if self.path.rstrip("/") == "/health":
            self._send(200, {"ok": True, "helix": "http-control"})
        else:
            self._send(404, {"ok": False, "error": "not found"})

    def do_POST(self) -> None:
        if self.path.rstrip("/") != "/cmd":
            self._send(404, {"ok": False, "error": "not found"})
            return
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            req = json.loads(raw or b"{}")
            if not isinstance(req, dict):
                raise ValueError("not an object")
        except ValueError:
            self._send(400, {"ok": False, "error": "invalid json"})
            return
        fut = asyncio.run_coroutine_threadsafe(_DISPATCH(req), _LOOP)  # type: ignore[misc]
        try:
            resp = fut.result(timeout=30)
        except Exception as exc:  # a command failed — report, keep serving
            resp = {"ok": False, "error": str(exc)}
        self._send(200, resp)

    def log_message(self, *args) -> None:  # quiet
        pass


def serve(host: str, port: int) -> None:
    loop = asyncio.new_event_loop()
    hold: Dict[str, Any] = {}
    ready = threading.Event()

    async def _setup() -> None:
        hold["dispatch"] = await _build_demo_dispatch(hold)
        ready.set()

    def _run_loop() -> None:
        asyncio.set_event_loop(loop)
        loop.create_task(_setup())
        loop.run_forever()

    threading.Thread(target=_run_loop, daemon=True).start()
    if not ready.wait(10):
        raise RuntimeError("demo mesh did not come up")

    global _DISPATCH, _LOOP
    _DISPATCH = hold["dispatch"]
    _LOOP = loop
    httpd = ThreadingHTTPServer((host, port), _Handler)
    print("PORT {}".format(httpd.server_address[1]), flush=True)
    print("HELIX HTTP control on http://{}:{}  (POST /cmd, GET /health)".format(host, httpd.server_address[1]),
          flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
        loop.call_soon_threadsafe(loop.stop)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1", help="0.0.0.0 to reach from a phone on the LAN")
    ap.add_argument("--port", type=int, default=8799)
    args = ap.parse_args()
    serve(args.host, args.port)
