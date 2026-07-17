"""Control plane (L3): membership, discovery, layer leases, liveness.

:class:`ControlNode` wraps an :class:`~helix.endpoint.Endpoint` and plays both roles
(there is no separate master process):

* **member** — periodically broadcasts ``ANNOUNCE`` (capacity) and ``HEARTBEAT``
  (liveness); accepts a ``LEASE`` (its band of layers for a placed model) and replies
  ``LEASE_ACK``; tracks lease expiry.
* **coordinator** — :meth:`place` computes a ring from the live roster (memory-weighted,
  with the per-node RAM check), issues a ``LEASE`` to each node, and collects acks.

Leases replace exo-core's one-shot ``ASSIGN``: they carry a TTL and a monotonically
increasing ``term`` so a node knows a fresh placement supersedes an old one, and so a
node whose heartbeat lapses can have its band reassigned (the self-healing hook —
:meth:`on_node_lost`; full migration/resume is Phase 5 / HELIX ②).

The coordinator identity a member binds is the **authenticated ``msg.src``** of the
LEASE, never a field in the body — so a valid-but-forged "coordinator" name cannot
redirect a node.
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Set

from helix.endpoint import Endpoint
from helix.log import get_logger
from helix.message import Message, MsgType
from helix.placement import Band, Capacity, Placement, plan_placement
from helix.roster import Roster

logger = get_logger("control")


@dataclass
class Lease:
    coordinator: str
    model_id: str
    n_layers: int
    ring: List[str]
    band: Band
    term: int
    expires_at: float

    def valid(self, now: float) -> bool:
        return now < self.expires_at


def _cap_body(cap: Capacity) -> Dict:
    return {"mem": cap.mem_bytes, "cpu": cap.cpu, "npu": cap.npu, "batt": cap.battery}


def _cap_from(body: Dict) -> Capacity:
    return Capacity(
        mem_bytes=int(body.get("mem", 0)),
        cpu=float(body.get("cpu", 0.0)),
        npu=bool(body.get("npu", False)),
        battery=float(body.get("batt", 1.0)),
    )


class ControlNode:
    def __init__(
        self,
        endpoint: Endpoint,
        capacity: Capacity,
        *,
        announce_interval_s: float = 1.0,
        heartbeat_interval_s: float = 0.3,
        ttl_s: float = 3.0,
        lease_ttl_s: float = 8.0,
        clock: Callable[[], float] = time.monotonic,
        rpc_addr: str = "",
    ) -> None:
        self.ep = endpoint
        self.capacity = capacity
        self.clock = clock
        # Option A (llama.cpp RPC): the "host:port" of this node's rpc-server, advertised in
        # ANNOUNCE so the coordinator can build the --rpc list. Empty -> field omitted (wire
        # unchanged for nodes that don't run an rpc-server).
        self.rpc_addr = rpc_addr
        self._rpc_addrs: Dict[str, str] = {}
        if rpc_addr:
            self._rpc_addrs[self.node_id] = rpc_addr
        self._announce_interval = announce_interval_s
        self._heartbeat_interval = heartbeat_interval_s
        self._lease_ttl_s = lease_ttl_s

        self.roster = Roster(ttl_s, clock=clock)
        self.roster.observe(self.node_id, capacity)

        # member state
        self.lease: Optional[Lease] = None
        self._term_seen = 0
        # coordinator state
        self._issued_term = 0
        self._acks: Dict[int, Set[str]] = {}
        self._placement: Optional[Placement] = None
        self.placement_stale = False

        self._outbox: List = []
        self._running = False
        self._on_node_lost: Optional[Callable[[str], None]] = None
        self._on_lease: Optional[Callable[["Lease"], None]] = None
        endpoint.on_message(self._on_message)

    @property
    def node_id(self) -> str:
        return self.ep.node_id

    def on_node_lost(self, cb: Callable[[str], None]) -> None:
        self._on_node_lost = cb

    def on_lease(self, cb: Callable[["Lease"], None]) -> None:
        """Called when a fresh LEASE is accepted (member (re)configures its pipeline)."""
        self._on_lease = cb

    # -- inbound (sync, from transport) ------------------------------------
    def _on_message(self, msg: Message) -> None:
        if msg.type == MsgType.ANNOUNCE.value:
            self.roster.observe(msg.src, _cap_from(msg.body))
            addr = msg.body.get("rpc")
            if addr:
                self._rpc_addrs[msg.src] = str(addr)
        elif msg.type == MsgType.HEARTBEAT.value:
            self.roster.observe(msg.src)
        elif msg.type == MsgType.LEASE.value:
            self._recv_lease(msg)
        elif msg.type == MsgType.LEASE_ACK.value:
            self._acks.setdefault(int(msg.body.get("term", 0)), set()).add(msg.src)

    def _recv_lease(self, msg: Message) -> None:
        b = msg.body
        term = int(b.get("term", 0))
        if term < self._term_seen:
            return  # a newer placement already supersedes this one
        try:
            band = Band(int(b["band"][0]), int(b["band"][1]))
            self.lease = Lease(
                coordinator=msg.src,  # authenticated identity, not a body field
                model_id=str(b["model_id"]),
                n_layers=int(b["n_layers"]),
                ring=[str(n) for n in b["ring"]],
                band=band,
                term=term,
                expires_at=self.clock() + int(b.get("ttl_ms", 8000)) / 1000.0,
            )
        except (KeyError, ValueError, TypeError, IndexError):
            return  # malformed lease → drop
        self._term_seen = term
        if self._on_lease is not None:
            self._on_lease(self.lease)  # (re)build the pipeline BEFORE acking → ack implies ready
        self._enqueue(msg.src, MsgType.LEASE_ACK.value, {"term": term, "band": [band.start, band.end]})
        logger.info("%s leased [%d,%d) term %d from %s", self.node_id, band.start, band.end, term, msg.src)

    # -- outbox ------------------------------------------------------------
    def _enqueue(self, target: Optional[str], type: str, body: Optional[Dict] = None) -> None:
        self._outbox.append((target, type, body))

    async def _flush(self) -> None:
        while self._outbox:
            target, type, body = self._outbox.pop(0)
            if target is None:
                await self.ep.broadcast(type, body)
            else:
                await self.ep.send(target, type, body)

    # -- lifecycle ---------------------------------------------------------
    async def start(self) -> None:
        await self.ep.start()

    async def stop(self) -> None:
        self._running = False
        await self.ep.stop()

    def rpc_addrs(self) -> Dict[str, str]:
        """``node_id -> "host:port"`` rpc-server addresses learned from ANNOUNCE (Option A)."""
        return dict(self._rpc_addrs)

    async def announce(self) -> None:
        body = _cap_body(self.capacity)
        if self.rpc_addr:  # omitted when empty -> ANNOUNCE wire/conformance unchanged
            body["rpc"] = self.rpc_addr
        await self.ep.broadcast(MsgType.ANNOUNCE.value, body)

    async def heartbeat(self) -> None:
        await self.ep.broadcast(MsgType.HEARTBEAT.value, {})

    async def run_forever(self, poll_s: float = 0.02) -> None:
        """Member loop: announce/heartbeat on schedule, flush, prune, fire healing."""
        self._running = True
        last_ann = last_hb = 0.0
        while self._running:
            now = self.clock()
            self.roster.observe(self.node_id, self.capacity)  # keep self alive locally
            if now - last_ann >= self._announce_interval:
                await self.announce()
                last_ann = now
            if now - last_hb >= self._heartbeat_interval:
                await self.heartbeat()
                last_hb = now
            await self._flush()
            for lost in self.roster.prune():
                logger.info("%s: peer %s went silent", self.node_id, lost)
                if self._placement is not None and lost in self._placement.ring:
                    self.placement_stale = True
                if self._on_node_lost is not None:
                    self._on_node_lost(lost)
            await asyncio.sleep(poll_s)

    # -- coordinator role --------------------------------------------------
    async def wait_for_members(self, min_nodes: int, timeout_s: float) -> None:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout_s
        while len(self.roster.live()) < min_nodes and loop.time() < deadline:
            await self._flush()
            await asyncio.sleep(0.02)

    async def place(
        self,
        model_id: str,
        n_layers: int,
        model_bytes: int,
        *,
        min_nodes: int = 1,
        discovery_timeout_s: float = 0.0,
        ack_timeout_s: float = 2.0,
        order: Optional[List[str]] = None,
    ) -> Placement:
        """Compute a placement, issue leases, and collect acks (coordinator)."""
        if discovery_timeout_s > 0:
            await self.wait_for_members(min_nodes, discovery_timeout_s)

        self._issued_term += 1
        term = self._issued_term
        placement = plan_placement(
            self.roster.capacities(), model_id, n_layers, model_bytes, term=term, order=order
        )
        self._placement = placement
        self.placement_stale = False

        for nid in placement.ring:
            band = placement.bands[nid]
            self._enqueue(nid, MsgType.LEASE.value, {
                "term": term,
                "model_id": model_id,
                "n_layers": n_layers,
                "ring": placement.ring,
                "band": [band.start, band.end],
                "ttl_ms": int(self._lease_ttl_s * 1000),
            })
        await self._flush()

        loop = asyncio.get_running_loop()
        deadline = loop.time() + ack_timeout_s
        want = set(placement.ring)
        while not (self._acks.get(term, set()) >= want) and loop.time() < deadline:
            await self._flush()
            await asyncio.sleep(0.01)

        acked = self._acks.get(term, set())
        if not (acked >= want):
            logger.warning("place: %d/%d leases acked (missing %s)", len(acked), len(want), want - acked)
        else:
            logger.info("place: model %s on %d node(s), all leases acked", model_id, len(want))
        return placement

    def leases_acked(self, term: int) -> Set[str]:
        return set(self._acks.get(term, set()))
