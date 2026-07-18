"""Healing bridge — verify HELIX self-healing (②) with a foreign shard that dies mid-generation.

The server side of the ChatterUI *Level 3 / Option B* healing proof. A full :class:`Orchestrator`
runs on **A** (coordinator + first shard) over a point-to-point HELIX stream; the client **B**
(e.g. ``integration/chatterui_llamacpp/js/control_shard_node.mjs``) joins the control plane
(ANNOUNCE/HEARTBEAT), is leased a layer band, and runs it as the last shard.

Part-way through generation the foreign shard **goes silent** (a crashed phone). A's roster prunes
it, the placement goes stale, and the orchestrator **re-places over the survivors and resumes from
the last produced token** — the session completes with the *same* tokens as a fault-free run, and
``session.heals >= 1``.

    python -m helix.host.heal_bridge_demo [--port 0]   # prints PORT, verifies, exits 0/1
"""

from __future__ import annotations

import argparse
import asyncio
import sys

from helix.control import ControlNode
from helix.endpoint import Endpoint
from helix.orchestrator import ModelSpec, Orchestrator, SessionStatus
from helix.placement import Band, Capacity
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec
from helix.shard import NumericShardRunner
from helix.transport.stream import StreamTransport

SECRET = b"helix-heal-bridge-demo"  # must match the JS side
MODEL = ModelSpec("m6", 6, 3 * (2 ** 30))
SEED_TOKEN = 5
MAX_NEW = 8


def _make_runner(band: Band, n_layers: int, model_id: str = "", model_path: str = "") -> NumericShardRunner:
    r = NumericShardRunner()
    r.load(band, n_layers)
    return r


def _ok(what: str) -> None:
    print("  ok  " + what, flush=True)


async def _verify(orch: Orchestrator) -> bool:
    await orch.control.wait_for_members(2, 4.0)
    live = [n for n in orch.control.roster.live() if n != "A"]
    if not live:
        print("  FAIL: foreign shard never announced", flush=True)
        return False
    _ok("foreign shard joined the control plane: {}".format(live[0]))

    session = await orch.generate([SEED_TOKEN], max_new_tokens=MAX_NEW, min_nodes=2,
                                  discovery_timeout_s=2.0)
    streamed = []
    async for _step, token, _text in session.stream():
        streamed.append(token)
    status = await session.wait()

    expected = [SEED_TOKEN + 6 * k for k in range(1, MAX_NEW + 1)]
    if status != SessionStatus.COMPLETED:
        print("  FAIL: status {}".format(status), flush=True)
        return False
    if session.token_list() != expected:
        print("  FAIL: tokens {} != {}".format(session.token_list(), expected), flush=True)
        return False
    if session.heals < 1:
        print("  FAIL: expected >=1 heal, got {}".format(session.heals), flush=True)
        return False
    _ok("foreign shard contributed, then died mid-generation")
    _ok("re-placed over survivors + resumed from checkpoint ({} heal/s)".format(session.heals))
    _ok("session COMPLETED with fault-free tokens {}".format(session.token_list()))
    return True


async def _main(host: str, port: int) -> int:
    result = {"ok": False}
    stop_evt = asyncio.Event()

    async def on_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        if stop_evt.is_set():
            writer.close()
            return
        transport = StreamTransport("A", reader, writer)
        ep = Endpoint(transport, FrameCodec("A", sealer_from_cluster_secret(SECRET)))
        control = ControlNode(ep, Capacity(8 * (2 ** 30)),
                              announce_interval_s=0.1, heartbeat_interval_s=0.08, ttl_s=0.3)
        orch = Orchestrator(control, _make_runner, MODEL, step_timeout_s=3.0)
        await control.start()
        run = asyncio.ensure_future(orch.run())
        try:
            result["ok"] = await _verify(orch)
        except Exception as exc:  # noqa: BLE001
            print("  FAIL: {}".format(exc), flush=True)
            result["ok"] = False
        finally:
            run.cancel()
            await orch.stop()
            stop_evt.set()

    server = await asyncio.start_server(on_client, host, port)
    print("PORT {}".format(server.sockets[0].getsockname()[1]), flush=True)
    try:
        await asyncio.wait_for(stop_evt.wait(), timeout=30.0)
    except asyncio.TimeoutError:
        print("  FAIL: timed out", flush=True)
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
