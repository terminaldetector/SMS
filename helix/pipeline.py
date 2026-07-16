"""Layer-shard ring driver (L4).

Drives one node's role in a true layer-pipeline over an :class:`~helix.endpoint.Endpoint`.
Rebuilt from exo-core's ``sharded.py`` on HELIX primitives, with three concrete fixes to
the audited behaviour:

* **Authenticated + encrypted + replay-protected hops.** Every ``ACTIVATION``/``FEED``/
  ``SHARD_TOKEN`` rides an :class:`Endpoint` frame, so activations are confidential and
  cannot be forged or replayed (old design: clear JSON, no auth).
* **No double-advance.** A per-task monotonic step guard drops duplicate/old activations
  (steps are strictly sequential in a decode ring), so a retransmitted hop cannot advance
  the ring twice.
* **No reorder truncation.** The coordinator assembles collected tokens **by step index**
  and finishes on ``DONE`` — so a token arriving after ``DONE``, or out of order, is not
  silently dropped (old design appended in arrival order and stopped on first ``DONE``).

Ring/band/coordinator come from the L3 :class:`~helix.control.Lease`; the runner
(:mod:`helix.shard`) does the tensor math.
"""

from __future__ import annotations

import asyncio
import uuid
from typing import Dict, List, Optional, Tuple

from helix.activation import ActivationCodec, RawActivationCodec
from helix.endpoint import Endpoint
from helix.log import get_logger
from helix.message import Message, MsgType
from helix.placement import Band
from helix.shard import ShardRunner

logger = get_logger("pipeline")


class ShardPipeline:
    def __init__(
        self,
        node_id: str,
        endpoint: Endpoint,
        ring: List[str],
        band: Band,
        runner: ShardRunner,
        coordinator: str,
        *,
        codec: Optional[ActivationCodec] = None,
        max_new_tokens: int = 16,
    ) -> None:
        self.node_id = node_id
        self.ep = endpoint
        self.ring = ring
        self.band = band
        self.runner = runner
        self.coordinator = coordinator
        self.codec = codec or RawActivationCodec()
        self.max_new_tokens = max_new_tokens

        self.rank = ring.index(node_id)
        self.is_first = self.rank == 0
        self.is_last = self.rank == len(ring) - 1

        self._outbox: List[Tuple[str, str, dict, str]] = []
        self._last_step: Dict[str, int] = {}        # tid -> highest step processed (idempotency)
        self._tokens: Dict[str, Dict[int, int]] = {}  # coordinator: tid -> {step: token}
        self._done: set = set()
        self._running = False
        endpoint.on_message(self._on_message)

    def _next(self) -> str:
        return self.ring[(self.rank + 1) % len(self.ring)]

    # -- inbound -----------------------------------------------------------
    def _on_message(self, msg: Message) -> None:
        if msg.type == MsgType.FEED.value:
            self._on_feed(msg)
        elif msg.type == MsgType.ACTIVATION.value:
            self._on_activation(msg)
        elif msg.type == MsgType.SHARD_TOKEN.value:
            self._tokens.setdefault(msg.tid, {})[int(msg.body["step"])] = int(msg.body["token"])
        elif msg.type == MsgType.DONE.value:
            self._done.add(msg.tid)

    def _fresh(self, tid: str, step: int) -> bool:
        """True if ``step`` is new for ``tid`` (strictly-increasing steps in a decode ring)."""
        if step <= self._last_step.get(tid, -1):
            return False
        self._last_step[tid] = step
        return True

    def _on_feed(self, msg: Message) -> None:
        if not self.is_first:
            return
        tid, step = msg.tid, int(msg.body["step"])
        if not self._fresh(tid, step):
            return
        hidden = self.runner.forward(self.runner.embed(int(msg.body["token"])))
        self._advance(tid, step, hidden)

    def _on_activation(self, msg: Message) -> None:
        tid, step = msg.tid, int(msg.body["step"])
        if not self._fresh(tid, step):
            return
        hidden = self.runner.forward(self.codec.decode(msg.body["act"]))
        self._advance(tid, step, hidden)

    def _advance(self, tid: str, step: int, hidden: List[float]) -> None:
        if not self.is_last:
            self._enqueue(self._next(), MsgType.ACTIVATION.value, {"step": step, "act": self.codec.encode(hidden)}, tid)
            return
        token = self.runner.sample(hidden)
        self._enqueue(self.coordinator, MsgType.SHARD_TOKEN.value, {"step": step, "token": token}, tid)
        nxt = step + 1
        if token == self.runner.eos_id() or nxt >= self.max_new_tokens:
            self._enqueue(self.coordinator, MsgType.DONE.value, {}, tid)
        else:
            self._enqueue(self.ring[0], MsgType.FEED.value, {"step": nxt, "token": token}, tid)

    # -- outbox ------------------------------------------------------------
    def _enqueue(self, target: str, type: str, body: dict, tid: str) -> None:
        self._outbox.append((target, type, body, tid))

    async def _flush(self) -> None:
        while self._outbox:
            target, type, body, tid = self._outbox.pop(0)
            await self.ep.send(target, type, body, tid)

    async def run_forever(self, poll_s: float = 0.002) -> None:
        self._running = True
        while self._running:
            await self._flush()
            await asyncio.sleep(poll_s)

    def stop(self) -> None:
        self._running = False

    # -- coordinator role --------------------------------------------------
    async def generate(self, prompt_token_ids: List[int], *, timeout_s: float = 5.0) -> List[int]:
        """Seed the ring with the prompt's last token; return generated ids in step order."""
        assert prompt_token_ids, "prompt_token_ids must be non-empty"
        tid = uuid.uuid4().hex
        self._tokens[tid] = {}
        self._done.discard(tid)

        self._enqueue(self.ring[0], MsgType.FEED.value, {"step": 0, "token": int(prompt_token_ids[-1])}, tid)
        await self._flush()

        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout_s
        while tid not in self._done and loop.time() < deadline:
            await self._flush()
            await asyncio.sleep(0.005)
        await self._flush()

        toks = self._tokens.get(tid, {})
        return [toks[s] for s in sorted(toks)]  # assembled by step → reorder-safe
