"""Layer-shard ring driver (L4).

Drives one node's role in a true layer-pipeline over an :class:`~helix.endpoint.Endpoint`.
Rebuilt from exo-core's ``sharded.py`` on HELIX primitives, **coordinator-driven**: the
last shard returns each sampled token to the coordinator, and the coordinator decides
whether to feed the next step (stop on EOS / token budget). Centralising termination in
the coordinator is what makes streaming, stopping, and mid-generation healing (L5) clean —
and it removes the worker's need to know the token budget.

Audited behaviours fixed here:

* **Authenticated + encrypted + replay-protected hops** — every ``ACTIVATION``/``FEED``/
  ``SHARD_TOKEN`` rides an :class:`Endpoint` frame.
* **No double-advance** — a per-task monotonic step guard drops duplicate/old activations
  (steps are strictly sequential in a decode ring).

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
        register: bool = True,
    ) -> None:
        self.node_id = node_id
        self.ep = endpoint
        self.ring = ring
        self.band = band
        self.runner = runner
        self.coordinator = coordinator
        self.codec = codec or RawActivationCodec()
        self.max_new_tokens = max_new_tokens  # only used by the standalone generate() helper

        self.rank = ring.index(node_id)
        self.is_first = self.rank == 0
        self.is_last = self.rank == len(ring) - 1

        self._outbox: List[Tuple[str, str, dict, str]] = []
        self._last_step: Dict[str, int] = {}          # tid -> highest step processed (idempotency)
        self._tokens: Dict[str, Dict[int, int]] = {}  # coordinator helper: tid -> {step: token}
        self._eos_steps: set = set()                  # (tid, step) with EOS
        self._running = False
        if register:
            endpoint.on_message(self.handle)

    def _next(self) -> str:
        return self.ring[(self.rank + 1) % len(self.ring)]

    # -- inbound (worker mechanics) ----------------------------------------
    def handle(self, msg: Message) -> None:
        if msg.type == MsgType.FEED.value:
            self._on_feed(msg)
        elif msg.type == MsgType.ACTIVATION.value:
            self._on_activation(msg)
        elif msg.type == MsgType.SHARD_TOKEN.value:  # coordinator-helper collection
            step = int(msg.body["step"])
            self._tokens.setdefault(msg.tid, {})[step] = int(msg.body["token"])
            if msg.body.get("eos"):
                self._eos_steps.add((msg.tid, step))

    def _fresh(self, tid: str, step: int) -> bool:
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
        self._enqueue(self.coordinator, MsgType.SHARD_TOKEN.value, {
            "step": step, "token": token, "text": self.runner.detok(token),
            "eos": token == self.runner.eos_id(),
        }, tid)

    # -- outbox ------------------------------------------------------------
    def _enqueue(self, target: str, type: str, body: dict, tid: str) -> None:
        self._outbox.append((target, type, body, tid))

    async def flush(self) -> None:
        while self._outbox:
            target, type, body, tid = self._outbox.pop(0)
            await self.ep.send(target, type, body, tid)

    async def run_forever(self, poll_s: float = 0.002) -> None:
        self._running = True
        while self._running:
            await self.flush()
            await asyncio.sleep(poll_s)

    def stop(self) -> None:
        self._running = False

    # -- standalone coordinator helper (L4 tests; L5 uses the Orchestrator) --
    async def generate(self, prompt_token_ids: List[int], *, timeout_s: float = 5.0) -> List[int]:
        """Seed the ring and drive decode from the coordinator; return ids in step order."""
        assert prompt_token_ids, "prompt_token_ids must be non-empty"
        tid = uuid.uuid4().hex
        self._tokens[tid] = {}
        seed = int(prompt_token_ids[-1])
        step = 0
        self._enqueue(self.ring[0], MsgType.FEED.value, {"step": 0, "token": seed}, tid)
        await self.flush()

        loop = asyncio.get_running_loop()
        while step < self.max_new_tokens:
            deadline = loop.time() + timeout_s
            while step not in self._tokens[tid] and loop.time() < deadline:
                await self.flush()
                await asyncio.sleep(0.005)
            if step not in self._tokens[tid]:
                break  # stalled
            tok = self._tokens[tid][step]
            if (tid, step) in self._eos_steps:
                break
            step += 1
            if step < self.max_new_tokens:
                self._enqueue(self.ring[0], MsgType.FEED.value, {"step": step, "token": tok}, tid)
                await self.flush()
        await self.flush()
        return [self._tokens[tid][s] for s in sorted(self._tokens[tid])]
