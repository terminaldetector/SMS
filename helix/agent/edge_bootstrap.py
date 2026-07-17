"""Edge bootstrap glue — build and drive an AgentNode from a native host runner.

Called from the Kotlin `MeshService` (via Chaquopy). Keeps all the wiring in one place so
the native side only passes: the host runner (poll contract), the cluster secret, and
whether to use a per-node identity (④).

    python -m helix.agent.edge_bootstrap    # self-test over the in-memory transport

Threading note: ``run(node)`` owns the asyncio loop. To invoke a coordination call from a
different (UI) thread, schedule ``coordinate(...)`` onto that loop with
``asyncio.run_coroutine_threadsafe`` — do **not** call ``asyncio.run`` again.
"""

from __future__ import annotations

from typing import Any, Callable, List, Optional

from helix.agent.host import PollingAgentRunner
from helix.agent.node import AgentNode
from helix.endpoint import Endpoint
from helix.identity import NodeIdentity
from helix.sealer import sealer_from_cluster_secret
from helix.session import FrameCodec


def _default_transport(node_id: str, secret: bytes):
    from helix.transport.wifi import WifiTransport
    return WifiTransport(node_id, secret)


def build_node(
    host_runner: Any,
    cluster_secret: bytes,
    *,
    identity: bool = True,
    transport_factory: Optional[Callable[[str, bytes], Any]] = None,
) -> AgentNode:
    """Wrap a native poll-based runner into a fully-wired :class:`AgentNode`."""
    runner = PollingAgentRunner(host_runner)
    idn = NodeIdentity() if identity else None
    node_id = idn.node_id if idn is not None else runner.card().agent_id
    transport = (transport_factory or _default_transport)(node_id, cluster_secret)
    endpoint = Endpoint(transport, FrameCodec(node_id, sealer_from_cluster_secret(cluster_secret)))
    return AgentNode(endpoint, runner, identity=idn)


async def run(node: AgentNode) -> None:
    """Start the transport and drive the node's background loops (blocks)."""
    await node.start()
    await node.run_forever()


async def coordinate(node: AgentNode, prompt: str, mode: str, *,
                     stages: Optional[List[dict]] = None, **kw) -> Optional[str]:
    """Run one coordination request; returns the aggregated result text."""
    if mode == "single":
        res, _ = await node.run_single(prompt, **kw)
    elif mode == "parallel":
        res, _ = await node.run_parallel(prompt, **kw)
    elif mode == "voting":
        res, _ = await node.run_voting(prompt, **kw)
    elif mode == "pipeline":
        res, _ = await node.run_pipeline(prompt, stages or [], **kw)
    else:
        raise ValueError("unknown mode: {}".format(mode))
    return res


def _selftest() -> None:
    import asyncio
    import json

    class FakeHost:
        def __init__(self, agent_id, transform):
            self.agent_id, self.transform, self._p = agent_id, transform, {}

        def cardJson(self):  # noqa: N802
            return json.dumps({"agent_id": self.agent_id, "skills": ["chat"], "task_types": ["chat"]})

        def submit(self, tid, prompt, context):
            self._p[tid] = self.transform(prompt).split()

        def poll(self):
            out = []
            for tid, w in list(self._p.items()):
                if w:
                    out.append({"task": tid, "chunk": w.pop(0) + " ", "done": False})
                else:
                    out.append({"task": tid, "done": True}); del self._p[tid]
            return json.dumps(out)

        def score(self, prompt, result):
            return float(len(result))

    async def main():
        bus = {}
        from helix.transport.memory import InMemoryTransport
        tf = lambda nid, sec: InMemoryTransport(nid, bus)
        coord = build_node(FakeHost("coord", lambda p: p), b"secret", identity=False, transport_factory=tf)
        up = build_node(FakeHost("up", lambda p: p.upper()), b"secret", identity=False, transport_factory=tf)
        for n in (coord, up):
            await n.start()
        loops = [asyncio.ensure_future(n.run_forever()) for n in (coord, up)]
        try:
            await coord.wait_for_agents(2, 2.0)
            out = await coordinate(coord, "hello world", "single", skill="chat", prefer="up")
            assert out == "HELLO WORLD", repr(out)
            print("edge bootstrap: build_node + coordinate('single') -> {!r}".format(out))
            print("ALL PASSED")
        finally:
            for n in (coord, up):
                await n.stop()
            for t in loops:
                t.cancel()

    asyncio.run(main())


if __name__ == "__main__":
    _selftest()
