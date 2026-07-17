"""AgentNode (Track A) — one device's agent: worker + coordinator, on a HELIX endpoint.

Every node runs an :class:`AgentNode` that advertises its :class:`~helix.agent.card.AgentCard`,
answers ``TASK`` frames by running its whole-model :class:`~helix.agent.runner.AgentRunner`
(streaming ``PARTIAL`` then ``RESULT``/``VOTE``), and — when it acts as coordinator — routes
tasks across agents in one of four modes and aggregates:

* **single**   — one capable agent.
* **parallel** — fan out to N agents, aggregate the results.
* **voting**   — fan out, collect candidates+scores, decide the best.
* **pipeline** — sequential stages, each stage's output feeding the next.

If an assigned agent goes silent before answering, the coordinator **re-routes the task
to another capable agent** — healing is simpler here than in the tensor track because an
agent task is idempotent (no KV to rebuild).

The transport, encryption, authentication, replay protection and identity all come from the
shared HELIX substrate (:class:`~helix.endpoint.Endpoint`): ``TASK``/``RESULT``/``VOTE`` are
sealed frames with a trustworthy ``src`` — so, e.g., a result cannot be spoofed as coming
from another agent.
"""

from __future__ import annotations

import asyncio
import uuid
from typing import Any, Callable, Dict, List, Optional, Tuple

from helix.agent.card import AgentCard
from helix.agent.context import ContentStore, ContextEntry, ContextLog, content_ref
from helix.agent.registry import AgentRegistry
from helix.agent.runner import AgentRunner
from helix.endpoint import Endpoint
from helix.identity import Keyring, NodeIdentity
from helix.log import get_logger
from helix.message import Message, MsgType

logger = get_logger("agent")

Aggregate = Callable[[Dict[str, str]], str]
Decide = Callable[[Dict[str, Tuple[str, float]]], str]


def _vote_fields(task: str, voter: str, candidate: str, score: float) -> Dict[str, Any]:
    return {"kind": "vote", "task": task, "voter": voter, "candidate": candidate, "score": score}


def _ctx_fields(entry: ContextEntry) -> Dict[str, Any]:
    # Bind the entry to its content by hash (works for inline or ref'd content).
    h = entry.ref if entry.ref else content_ref(entry.content or "")
    return {"kind": "ctx", "author": entry.author, "lamport": entry.lamport, "role": entry.role, "h": h}


def _default_aggregate(results: Dict[str, str]) -> str:
    return " | ".join(results[a] for a in sorted(results))


def _default_decide(votes: Dict[str, Tuple[str, float]]) -> str:
    # highest score wins; ties broken by agent id for determinism
    best = max(sorted(votes), key=lambda a: votes[a][1])
    return votes[best][0]


class AgentNode:
    def __init__(
        self,
        endpoint: Endpoint,
        runner: AgentRunner,
        *,
        announce_interval_s: float = 0.5,
        ttl_s: float = 3.0,
        clock=None,
        identity: Optional[NodeIdentity] = None,
        keyring: Optional[Keyring] = None,
    ) -> None:
        self.ep = endpoint
        self.runner = runner
        self.card = runner.card()
        self.registry = AgentRegistry(ttl_s, **({"clock": clock} if clock else {}))
        self.registry.observe(self.node_id, self.card, "free")

        # HELIX ④: with an identity, votes and context entries are Ed25519-signed and
        # verified against the keyring — attribution stops being spoofable by an insider
        # who merely knows the group secret. Requires the endpoint id to be the
        # self-certifying identity.node_id. Without an identity, behaviour is unchanged.
        self.identity = identity
        self.keyring = keyring if keyring is not None else Keyring()
        if identity is not None:
            self.keyring.admit(self.node_id, identity.public)

        self._announce_interval = announce_interval_s
        self._busy = False
        self._outbox: List[Tuple[str, str, dict, str]] = []
        self._coll: Dict[str, Dict[str, Any]] = {}  # tid -> {results, votes, partials}
        self.context = ContextLog(self.node_id)     # shared conversation (CRDT)
        self._blobs = ContentStore()                # content-addressed store
        self._running = False
        self._on_agent_lost: Optional[Callable[[str], None]] = None
        endpoint.on_message(self._on_message)

    @property
    def node_id(self) -> str:
        return self.ep.node_id

    def on_agent_lost(self, cb: Callable[[str], None]) -> None:
        self._on_agent_lost = cb

    def _require_sig(self) -> bool:
        """Strict attribution mode: on when this node has an identity (cluster policy)."""
        return self.identity is not None

    # -- shared context (CONTEXT_SYNC) -------------------------------------
    def _resolve(self, e: ContextEntry) -> str:
        if e.content is not None:
            return e.content
        c = self._blobs.get(e.ref) if e.ref else None
        return c if c is not None else "<missing:{}>".format((e.ref or "")[:8])

    def context_text(self) -> str:
        return self.context.render(self._resolve)

    async def share_blob(self, content: str) -> str:
        """Broadcast a content-addressed blob once; return its ref for entries to cite."""
        ref = self._blobs.put(content)
        await self.ep.broadcast(MsgType.CONTEXT_BLOB.value, {"ref": ref, "content": content})
        return ref

    async def context_append(self, role: str, content: str, *, as_ref: bool = False) -> ContextEntry:
        """Append a turn to the shared context and broadcast the delta.

        ``as_ref=True`` shares the content as a blob once and cites it by hash — use for
        large/repeated content (a document, a long system prompt) so it travels once.
        """
        if as_ref:
            ref = await self.share_blob(content)
            entry = self.context.append(role, ref=ref)
        else:
            entry = self.context.append(role, content=content)
        body = entry.to_body()
        if self.identity is not None:
            body["sig"] = self.identity.sign_claim(_ctx_fields(entry))
        await self.ep.broadcast(MsgType.CONTEXT_SYNC.value, body)
        return entry

    # -- inbound -----------------------------------------------------------
    def _on_message(self, msg: Message) -> None:
        t = msg.type
        if t == MsgType.AGENT_ANNOUNCE.value:
            self.registry.observe(msg.src, AgentCard.from_body(msg.src, msg.body), None)
            pk = msg.body.get("pk")
            if pk:  # bootstrap the keyring (TOFU); admit() enforces id<->key binding
                try:
                    self.keyring.admit(msg.src, bytes.fromhex(str(pk)))
                except ValueError:
                    pass
        elif t == MsgType.STATUS.value:
            self.registry.observe(msg.src, None, "busy" if msg.body.get("busy") else "free")
        elif t == MsgType.TASK.value:
            self._handle_task(msg)
        elif t == MsgType.PARTIAL.value:
            c = self._coll.get(msg.tid)
            if c is not None:
                c["partials"].setdefault(msg.src, []).append(str(msg.body.get("chunk", "")))
        elif t == MsgType.RESULT.value:
            c = self._coll.get(msg.tid)
            if c is not None:
                c["results"][msg.src] = str(msg.body.get("text", ""))
        elif t == MsgType.VOTE.value:
            c = self._coll.get(msg.tid)
            if c is None:
                return
            candidate = str(msg.body.get("candidate", ""))
            score = float(msg.body.get("score", 0.0))
            if self._require_sig():
                sig = msg.body.get("sig")
                # One vote per authenticated node: the vote must carry the voter's own
                # signature. An insider forging a vote as another node has no such key.
                if not sig or not self.keyring.verify_claim(
                        msg.src, _vote_fields(msg.tid, msg.src, candidate, score), str(sig)):
                    logger.warning("dropped unsigned/forged vote attributed to %s", msg.src)
                    return
            c["votes"][msg.src] = (candidate, score)
        elif t == MsgType.CONTEXT_SYNC.value:
            entry = ContextEntry.from_body(msg.body)
            # Provenance: an entry's author must be the authenticated sender. This blocks
            # injecting context "as" the user or another agent (Pointer threat model §6).
            if entry is None or entry.author != msg.src:
                return
            if self._require_sig():
                sig = msg.body.get("sig")
                # Cryptographic provenance: only the author's key can sign the entry, so an
                # insider cannot forge a turn as another node even under a valid group seal.
                if not sig or not self.keyring.verify_claim(entry.author, _ctx_fields(entry), str(sig)):
                    logger.warning("dropped unsigned/forged context entry from %s", entry.author)
                    return
            if self.context.merge(entry) and entry.ref and not self._blobs.has(entry.ref):
                self._enqueue(msg.src, MsgType.CONTEXT_PULL.value, {"ref": entry.ref}, "")
        elif t == MsgType.CONTEXT_BLOB.value:
            self._blobs.put_with_ref(str(msg.body.get("ref", "")), str(msg.body.get("content", "")))
        elif t == MsgType.CONTEXT_PULL.value:
            ref = str(msg.body.get("ref", ""))
            content = self._blobs.get(ref)
            if content is not None:
                self._enqueue(msg.src, MsgType.CONTEXT_BLOB.value, {"ref": ref, "content": content}, "")

    def _handle_task(self, msg: Message) -> None:
        b = msg.body
        tid, coord = msg.tid, msg.src
        mode = str(b.get("mode", "single"))
        prompt = str(b.get("prompt", ""))
        # An explicit task context wins; otherwise the agent runs with the shared
        # conversation it has synced (provenance-labelled).
        context = str(b.get("context", "")) or self.context_text()
        self._busy = True
        parts: List[str] = []
        for chunk in self.runner.infer(prompt, context):
            parts.append(chunk)
            self._enqueue(coord, MsgType.PARTIAL.value, {"chunk": chunk}, tid)
        result = "".join(parts).strip()
        if mode == "voting":
            score = self.runner.score(prompt, result)
            body = {"candidate": result, "score": score}
            if self.identity is not None:
                body["sig"] = self.identity.sign_claim(_vote_fields(tid, self.node_id, result, score))
            self._enqueue(coord, MsgType.VOTE.value, body, tid)
        else:
            self._enqueue(coord, MsgType.RESULT.value, {"text": result}, tid)
        self._busy = False

    # -- outbox + loops ----------------------------------------------------
    def _enqueue(self, target: str, type: str, body: dict, tid: str) -> None:
        self._outbox.append((target, type, body, tid))

    async def _flush(self) -> None:
        while self._outbox:
            target, type, body, tid = self._outbox.pop(0)
            await self.ep.send(target, type, body, tid)

    async def announce(self) -> None:
        body = self.card.to_body()
        if self.identity is not None:
            body["pk"] = self.identity.public.hex()  # lets peers admit our key (TOFU)
        await self.ep.broadcast(MsgType.AGENT_ANNOUNCE.value, body)
        await self.ep.broadcast(MsgType.STATUS.value, {"busy": self._busy})

    async def start(self) -> None:
        await self.ep.start()

    async def stop(self) -> None:
        self._running = False
        await self.ep.stop()

    async def run_forever(self, poll_s: float = 0.02) -> None:
        self._running = True
        last = 0.0
        loop = asyncio.get_running_loop()
        while self._running:
            now = loop.time()
            self.registry.observe(self.node_id, self.card, "busy" if self._busy else "free")
            if now - last >= self._announce_interval:
                await self.announce()
                last = now
            await self._flush()
            for lost in self.registry.prune():
                if self._on_agent_lost is not None:
                    self._on_agent_lost(lost)
            await asyncio.sleep(poll_s)

    # -- coordinator role --------------------------------------------------
    async def wait_for_agents(self, min_agents: int, timeout_s: float, **match) -> None:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout_s
        while len(self.registry.match(**match)) < min_agents and loop.time() < deadline:
            await self._flush()
            await asyncio.sleep(0.02)

    def _pick(self, exclude=None, **match) -> Optional[str]:
        cands = self.registry.match(exclude=exclude or set(), **match)
        return cands[0] if cands else None

    async def run_single(
        self, prompt: str, *, task_type=None, skill=None, context: str = "",
        timeout_s: float = 3.0, prefer: Optional[str] = None,
    ) -> Tuple[Optional[str], Dict[str, Any]]:
        tid = uuid.uuid4().hex
        self._coll[tid] = {"results": {}, "votes": {}, "partials": {}}
        agent = prefer or self._pick(task_type=task_type, skill=skill)
        if agent is None:
            return None, {"status": "no_agent", "heals": 0}
        tried = {agent}
        heals = 0
        await self.ep.send(agent, MsgType.TASK.value,
                           {"mode": "single", "prompt": prompt, "context": context}, tid)
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout_s
        while not self._coll[tid]["results"] and loop.time() < deadline:
            if not self.registry.is_live(agent) and not self._coll[tid]["results"]:
                alt = self._pick(task_type=task_type, skill=skill, exclude=tried)
                if alt is not None:
                    agent, heals = alt, heals + 1
                    tried.add(alt)
                    await self.ep.send(alt, MsgType.TASK.value,
                                       {"mode": "single", "prompt": prompt, "context": context}, tid)
            await self._flush()
            await asyncio.sleep(0.005)
        res = next(iter(self._coll[tid]["results"].values()), None)
        return res, {"status": "ok" if res is not None else "timeout", "heals": heals}

    async def run_parallel(
        self, prompt: str, *, agent_ids: Optional[List[str]] = None, n: Optional[int] = None,
        task_type=None, skill=None, aggregate: Optional[Aggregate] = None, timeout_s: float = 3.0,
    ) -> Tuple[Optional[str], Dict[str, Any]]:
        agents = agent_ids or self.registry.match(task_type=task_type, skill=skill)
        if n is not None:
            agents = agents[:n]
        if not agents:
            return None, {"status": "no_agent", "heals": 0}
        tid = uuid.uuid4().hex
        self._coll[tid] = {"results": {}, "votes": {}, "partials": {}}
        assigned, target, heals = set(agents), len(agents), 0
        for aid in agents:
            await self.ep.send(aid, MsgType.TASK.value, {"mode": "parallel", "prompt": prompt}, tid)
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout_s
        while len(self._coll[tid]["results"]) < target and loop.time() < deadline:
            for aid in list(assigned):
                if aid not in self._coll[tid]["results"] and not self.registry.is_live(aid):
                    alt = self._pick(task_type=task_type, skill=skill,
                                     exclude=assigned | set(self._coll[tid]["results"]))
                    if alt is not None:
                        assigned.add(alt)
                        heals += 1
                        await self.ep.send(alt, MsgType.TASK.value, {"mode": "parallel", "prompt": prompt}, tid)
            await self._flush()
            await asyncio.sleep(0.005)
        results = self._coll[tid]["results"]
        if not results:
            return None, {"status": "timeout", "heals": heals, "n": 0}
        agg = (aggregate or _default_aggregate)(results)
        return agg, {"status": "ok" if len(results) >= target else "partial",
                     "heals": heals, "n": len(results)}

    async def run_voting(
        self, prompt: str, *, agent_ids: Optional[List[str]] = None, n: Optional[int] = None,
        task_type=None, skill=None, decide: Optional[Decide] = None, timeout_s: float = 3.0,
    ) -> Tuple[Optional[str], Dict[str, Any]]:
        agents = agent_ids or self.registry.match(task_type=task_type, skill=skill)
        if n is not None:
            agents = agents[:n]
        if not agents:
            return None, {"status": "no_agent"}
        tid = uuid.uuid4().hex
        self._coll[tid] = {"results": {}, "votes": {}, "partials": {}}
        target = len(agents)
        for aid in agents:
            await self.ep.send(aid, MsgType.TASK.value, {"mode": "voting", "prompt": prompt}, tid)
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout_s
        while len(self._coll[tid]["votes"]) < target and loop.time() < deadline:
            await self._flush()
            await asyncio.sleep(0.005)
        votes = self._coll[tid]["votes"]  # {agent: (candidate, score)} — one vote per authenticated node
        if not votes:
            return None, {"status": "timeout", "votes": 0}
        decision = (decide or _default_decide)(votes)
        await self.ep.broadcast(MsgType.DECIDE.value, {"decision": decision}, tid)
        return decision, {"status": "ok", "votes": len(votes)}

    async def run_pipeline(
        self, prompt: str, stages: List[Dict[str, Any]], *, timeout_s: float = 3.0,
    ) -> Tuple[Optional[str], Dict[str, Any]]:
        cur, heals = prompt, 0
        for i, st in enumerate(stages):
            res, meta = await self.run_single(
                cur, task_type=st.get("task_type"), skill=st.get("skill"), timeout_s=timeout_s)
            if res is None:
                return None, {"status": "failed_stage", "stage": i, "heals": heals}
            heals += int(meta.get("heals", 0))
            cur = res
        return cur, {"status": "ok", "heals": heals}
