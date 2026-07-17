"""Shard-ring bridge — verify a foreign (JS/native) HELIX shard worker over a real socket.

The server side of the ChatterUI *Level 3 / Option B* proof (full HELIX ring). One TCP connection
carries a point-to-point HELIX stream forming a 2-node layer-shard ring ``[A, B]``:

* **A** (this process) is the coordinator **and** the first shard — band ``[0,4)`` of a 6-layer
  model. It seeds the ring and collects the sampled tokens.
* **B** (the client, e.g. ``integration/chatterui_llamacpp/js/shard_node.mjs``) is the last shard —
  band ``[4,6)``. It receives ACTIVATION frames, runs its band, samples, and returns SHARD_TOKEN.

With the reference ``NumericShardRunner`` (each layer +1), threading a token through **both** bands
yields ``seed + 6`` per lap, so tokens ``[7, 13, 19]`` prove the foreign worker really ran its band
and threaded activations over sealed HELIX frames.

    python -m helix.host.shard_bridge_demo [--port 0]   # prints PORT, verifies, exits 0/1
"""

from __future__ import annotations

import argparse
import asyncio
import sys

from helix.endpoint import Endpoint
from helix.pipeline import ShardPipeline
from helix.placement import Band
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec
from helix.shard import NumericShardRunner
from helix.transport.stream import StreamTransport

SECRET = b"helix-shard-bridge-demo"  # must match the JS side
RING = ["A", "B"]
BAND_A = Band(0, 4)   # this process's band (first shard)
N_LAYERS = 6
SEED_TOKEN = 1
MAX_NEW = 3


def _ok(what: str) -> None:
    print("  ok  " + what, flush=True)


async def _verify(ep: Endpoint) -> bool:
    # wait for the foreign shard (B) to complete the transport handshake
    for _ in range(300):
        if "B" in await ep.peers():
            break
        await asyncio.sleep(0.01)
    else:
        print("  FAIL: foreign shard did not connect", flush=True)
        return False
    _ok("foreign shard connected: B")

    runner = NumericShardRunner()
    runner.load(BAND_A, N_LAYERS)
    pa = ShardPipeline("A", ep, RING, BAND_A, runner, coordinator="A", max_new_tokens=MAX_NEW)
    tokens = await pa.generate([SEED_TOKEN], timeout_s=5.0)

    expected = [SEED_TOKEN + N_LAYERS * (i + 1) for i in range(MAX_NEW)]  # [7, 13, 19]
    if tokens != expected:
        print("  FAIL: tokens {} != expected {}".format(tokens, expected), flush=True)
        return False
    _ok("ring A[0,4)+B[4,6) -> {} (both bands ran over sealed frames)".format(tokens))
    _ok("activations threaded across the socket; foreign worker sampled the last band")
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
        await transport.start()
        try:
            result["ok"] = await _verify(ep)
        except Exception as exc:  # noqa: BLE001
            print("  FAIL: {}".format(exc), flush=True)
            result["ok"] = False
        finally:
            await transport.stop()
            stop_evt.set()

    server = await asyncio.start_server(on_client, host, port)
    print("PORT {}".format(server.sockets[0].getsockname()[1]), flush=True)
    try:
        await asyncio.wait_for(stop_evt.wait(), timeout=30.0)
    except asyncio.TimeoutError:
        print("  FAIL: timed out waiting for shard worker", flush=True)
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
