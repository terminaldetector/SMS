"""Session + orchestrator (L5).

The orchestrator is the node-level conductor that ties the layers together and turns
"a set of phones" into "a generation service":

* every node runs an :class:`Orchestrator` that keeps its L3 :class:`~helix.control.ControlNode`
  alive (announce/heartbeat/prune) and (re)builds its L4 :class:`~helix.pipeline.ShardPipeline`
  whenever it receives a lease;
* the coordinator's orchestrator additionally drives a :class:`Session`: it seeds the ring,
  streams tokens as they arrive, and **decides termination** (token budget / EOS) — closing
  the AUDIT #5 "silent truncation" gap with an explicit :class:`SessionStatus`;
* if a node in the active placement goes silent, the coordinator **re-places over the
  survivors and resumes from the last produced token** (HELIX ② self-healing), incrementing
  ``Session.heals`` — the generation completes despite the loss.

Checkpoint model: the decode state that matters is the produced token sequence. On a heal
the coordinator re-seeds the ring at ``step = len(produced)`` with the last token. A real,
stateful :class:`~helix.shard.ShardRunner` must ``reset`` and re-prefill the sequence-so-far
to rebuild its band's KV on the new placement; the numeric reference is stateless, so the
self-test can assert the healed output equals the fault-free output.
"""

from __future__ import annotations

import asyncio
import uuid
from dataclasses import dataclass
from enum import Enum
from typing import Callable, Dict, List, Optional

from helix.activation import ActivationCodec
from helix.control import ControlNode, Lease
from helix.log import get_logger
from helix.message import Message, MsgType
from helix.pipeline import ShardPipeline
from helix.placement import Band
from helix.shard import ShardRunner

logger = get_logger("orchestrator")

RunnerFactory = Callable[[Band, int, str, str], ShardRunner]  # (band, n_layers, model_id, model_path)


@dataclass
class ModelSpec:
    model_id: str
    n_layers: int
    model_bytes: int
    model_path: str = ""


class SessionStatus(str, Enum):
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"   # finished by EOS or token budget
    TIMEOUT = "TIMEOUT"       # a step stalled past its deadline (even after heal attempts)
    FAILED = "FAILED"         # could not place / no viable ring


class Session:
    """One generation: streaming tokens + an explicit terminal status."""

    def __init__(self, session_id: str, model_id: str, max_new_tokens: int) -> None:
        self.session_id = session_id
        self.model_id = model_id
        self.max_new_tokens = max_new_tokens
        self.status = SessionStatus.RUNNING
        self.heals = 0
        self.tokens: Dict[int, int] = {}
        self.texts: Dict[int, str] = {}
        self._eos: set = set()
        self._q: "asyncio.Queue" = asyncio.Queue()
        self._done = asyncio.Event()

    def _emit(self, step: int, token: int, text: str, eos: bool) -> None:
        if step in self.tokens:
            return  # step-indexed: a re-produced step (post-heal) is idempotent
        self.tokens[step] = token
        self.texts[step] = text
        if eos:
            self._eos.add(step)
        self._q.put_nowait((step, token, text))

    def _finish(self, status: SessionStatus) -> None:
        if self._done.is_set():
            return
        self.status = status
        self._done.set()
        self._q.put_nowait(None)  # stream sentinel

    async def stream(self):
        """Yield ``(step, token, text)`` as tokens are produced, until the session ends."""
        while True:
            item = await self._q.get()
            if item is None:
                return
            yield item

    async def wait(self) -> SessionStatus:
        await self._done.wait()
        return self.status

    def token_list(self) -> List[int]:
        return [self.tokens[s] for s in sorted(self.tokens)]

    def text(self) -> str:
        return "".join(self.texts[s] for s in sorted(self.texts))


class Orchestrator:
    def __init__(
        self,
        control: ControlNode,
        make_runner: RunnerFactory,
        model: ModelSpec,
        *,
        codec: Optional[ActivationCodec] = None,
        step_timeout_s: float = 2.0,
    ) -> None:
        self.control = control
        self.ep = control.ep
        self.make_runner = make_runner
        self.model = model
        self.codec = codec
        self.step_timeout_s = step_timeout_s

        self._pipeline: Optional[ShardPipeline] = None
        self._session: Optional[Session] = None
        self._running = False

        control.on_lease(self._on_lease)
        self.ep.on_message(self._collect)  # coordinator-side collection (survives pipeline swaps)

    @property
    def node_id(self) -> str:
        return self.control.node_id

    # -- pipeline (re)build on lease ---------------------------------------
    def _on_lease(self, lease: Lease) -> None:
        if self._pipeline is not None:
            self.ep.off_message(self._pipeline.handle)
        runner = self.make_runner(lease.band, lease.n_layers, self.model.model_id, self.model.model_path)
        pipe = ShardPipeline(
            self.node_id, self.ep, lease.ring, lease.band, runner,
            coordinator=lease.coordinator, codec=self.codec, register=False,
        )
        self.ep.on_message(pipe.handle)
        self._pipeline = pipe
        logger.info("%s: pipeline for band [%d,%d) ring=%s", self.node_id, lease.band.start, lease.band.end, lease.ring)

    # -- coordinator-side token collection ---------------------------------
    def _collect(self, msg: Message) -> None:
        if msg.type != MsgType.SHARD_TOKEN.value:
            return
        s = self._session
        if s is None or msg.tid != s.session_id:
            return
        s._emit(int(msg.body["step"]), int(msg.body["token"]), str(msg.body.get("text", "")),
                bool(msg.body.get("eos", False)))

    # -- lifecycle ---------------------------------------------------------
    async def run(self) -> None:
        """Background loops every node runs: control (announce/heartbeat/prune) + pump."""
        self._running = True
        control_task = asyncio.ensure_future(self.control.run_forever())
        try:
            while self._running:
                if self._pipeline is not None:
                    await self._pipeline.flush()
                await asyncio.sleep(0.002)
        finally:
            control_task.cancel()

    async def stop(self) -> None:
        self._running = False
        await self.control.stop()

    async def _seed(self, tid: str, step: int, token: int) -> None:
        ring0 = self._pipeline.ring[0]
        await self.ep.send(ring0, MsgType.FEED.value, {"step": step, "token": token}, tid)

    # -- coordinator: run a generation -------------------------------------
    async def generate(
        self,
        prompt_token_ids: List[int],
        *,
        max_new_tokens: int = 16,
        min_nodes: int = 1,
        discovery_timeout_s: float = 2.0,
    ) -> Session:
        """Place the model and start driving a :class:`Session` (returns immediately)."""
        session = Session(uuid.uuid4().hex, self.model.model_id, max_new_tokens)
        self._session = session
        try:
            if discovery_timeout_s > 0:
                await self.control.wait_for_members(min_nodes, discovery_timeout_s)
            await self.control.place(
                self.model.model_id, self.model.n_layers, self.model.model_bytes,
                min_nodes=min_nodes, discovery_timeout_s=0.0,
            )
        except Exception as exc:  # placement failed (InsufficientCapacity, etc.)
            logger.warning("generate: placement failed: %s", exc)
            session._finish(SessionStatus.FAILED)
            return session
        asyncio.ensure_future(self._drive(session, int(prompt_token_ids[-1])))
        return session

    async def _drive(self, session: Session, seed: int) -> None:
        tid = session.session_id
        step = 0
        last = seed
        await self._seed(tid, step, seed)
        loop = asyncio.get_running_loop()
        deadline = loop.time() + self.step_timeout_s
        while step < session.max_new_tokens:
            while step not in session.tokens and loop.time() < deadline:
                if self.control.placement_stale:
                    healed = await self._heal(session, tid, step, last)
                    if not healed:
                        session._finish(SessionStatus.FAILED)
                        return
                    deadline = loop.time() + self.step_timeout_s
                await asyncio.sleep(0.005)
            if step not in session.tokens:
                session._finish(SessionStatus.TIMEOUT)
                return
            last = session.tokens[step]
            if step in session._eos:
                session._finish(SessionStatus.COMPLETED)
                return
            step += 1
            deadline = loop.time() + self.step_timeout_s
            if step < session.max_new_tokens:
                await self._seed(tid, step, last)
        session._finish(SessionStatus.COMPLETED)

    async def _heal(self, session: Session, tid: str, step: int, last: int) -> bool:
        """Re-place over the survivors and resume decode from the checkpoint token."""
        try:
            await self.control.place(
                self.model.model_id, self.model.n_layers, self.model.model_bytes,
                min_nodes=1, discovery_timeout_s=0.0,
            )
        except Exception as exc:
            logger.warning("heal: re-placement failed: %s", exc)
            return False
        session.heals += 1
        self.control.placement_stale = False
        logger.info("%s: healed (#%d), resuming at step %d", self.node_id, session.heals, step)
        await self._seed(tid, step, last)  # re-run the pending step on the new ring
        return True
