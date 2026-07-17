"""SCAFFOLD — PC-side USB-OTG bring-up (PC is a full HELIX node running Python directly).

The PC opens one byte stream per connected smartphone and gives each to a
:class:`~helix.transport.stream.StreamTransport`; a :class:`~helix.mesh.router.MeshRouter`
turns them into a **star hub** (relays leaf↔leaf and USB↔WiFi) or a ring. A
:class:`~helix.host.control_server.ControlServer` exposes the stack to PowerShell.

Channels (any reliable byte stream works — StreamTransport is channel-agnostic):
- **usb-serial / CDC-ACM**: pyserial-asyncio → ``open_serial_connection(url="COM5"|"/dev/ttyACM0")``.
- **AOA bulk**: pyusb over the accessory endpoints (wrap in a reader/writer shim).
- **adb forward (dev)**: ``adb forward tcp:9000 tcp:9000`` then a plain TCP stream.

Import-guarded so optional deps don't break anything; fill the TODOs for your channel.
"""

from __future__ import annotations

import asyncio
from typing import List, Tuple

from helix.endpoint import Endpoint
from helix.mesh.router import MeshRouter
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec
from helix.transport.stream import StreamTransport

Stream = Tuple[asyncio.StreamReader, asyncio.StreamWriter]


async def stream_from_serial(path: str, baud: int = 115200) -> Stream:
    import serial_asyncio  # pip install pyserial-asyncio
    return await serial_asyncio.open_serial_connection(url=path, baudrate=baud)


async def stream_from_tcp(host: str, port: int) -> Stream:
    # dev path: `adb forward tcp:<port> tcp:<port>` then connect here
    return await asyncio.open_connection(host, port)


async def build_pc_hub(node_id: str, cluster_secret: bytes, links: List[Stream]) -> Endpoint:
    """Star hub: one StreamTransport per smartphone, all under one MeshRouter."""
    router = MeshRouter(node_id)
    for reader, writer in links:
        router.add_downstream(StreamTransport(node_id, reader, writer))
    endpoint = Endpoint(router, FrameCodec(node_id, sealer_from_cluster_secret(cluster_secret)))
    await router.start()
    await router.announce_presence()   # let leaves learn routes
    return endpoint


async def main() -> None:
    # Example wiring (fill in real channels for your setup):
    secret = b"CHANGE-ME-shared-out-of-band"
    links = [
        # await stream_from_serial("/dev/ttyACM0"),
        # await stream_from_serial("/dev/ttyACM1"),
        # await stream_from_tcp("127.0.0.1", 9000),   # adb-forward dev
    ]
    endpoint = await build_pc_hub("pc-hub", secret, links)  # noqa: F841
    # Attach a ControlNode / AgentNode on `endpoint`, then a ControlServer for PowerShell:
    #   from helix.host.control_server import ControlServer, agent_dispatch
    #   node = AgentNode(endpoint, runner, identity=NodeIdentity())
    #   await ControlServer(agent_dispatch(node), port=8765).start()
    #   asyncio.ensure_future(node.run_forever())
    print("scaffold: fill in USB channels + node/runner, then run the ControlServer")


if __name__ == "__main__":
    asyncio.run(main())
