"""HELIX protocol messages (the sealed plaintext inside a frame).

Every message carries, in its **authenticated** payload:

* ``v``   — protocol version (forward/backward-compat handle that the old exo-core
            protocol lacked entirely).
* ``t``   — message type (see :class:`MsgType`).
* ``seq`` — per-sender monotonic sequence number (ordering / dedup / replay).
* ``src`` — the sender's node id. **This is the authoritative sender identity** and
            replaces the transport-provided ``from_node``. Because ``src`` is inside
            the sealed, authenticated body, a receiver never has to trust who the
            transport *says* sent a frame — which structurally removes the whole
            "identity by IP / ``from_node==''`` self-delivery" class of bugs from
            AUDIT2 (2.1, 2.4) and the coordinator-spoofing risk.
* ``tid`` — optional task/session id.
* ``b``   — type-specific body.

The old single ``TOKEN`` type is split into :attr:`MsgType.PROMPT_TOKEN` (text tokens
on the routing/echo path) and :attr:`MsgType.SHARD_TOKEN` (sampled ids on the
layer-shard ring), so the two sub-protocols can never be confused on one mesh.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, Optional

PROTOCOL_VERSION = 1


class MsgType(str, Enum):
    # -- control plane --------------------------------------------------
    ANNOUNCE = "ANNOUNCE"       # node -> all: presence + (attested) capacity
    LEASE = "LEASE"             # coordinator -> node: layer-band lease
    LEASE_ACK = "LEASE_ACK"     # node -> coordinator: lease accepted
    HEARTBEAT = "HEARTBEAT"     # liveness (piggy-backable on data frames)
    # -- inference: routing / echo path (Track A) -----------------------
    PROMPT = "PROMPT"           # coordinator/stage -> stage: prompt/activation text
    PROMPT_TOKEN = "PROMPT_TOKEN"   # last stage -> coordinator: output text chunk
    DONE = "DONE"               # last stage -> coordinator: generation finished
    # -- inference: true layer-shard ring (Track B / llama sharding) ----
    ACTIVATION = "ACTIVATION"   # stage i -> i+1: hidden-state blob
    FEED = "FEED"               # last -> first: sampled token id (closes the ring)
    SHARD_TOKEN = "SHARD_TOKEN"  # last stage -> coordinator: sampled token id
    # -- agent coordination (Pointer / Track A / edge) ------------------
    AGENT_ANNOUNCE = "AGENT_ANNOUNCE"  # agent -> all: capability card
    STATUS = "STATUS"           # agent -> coordinator: free/busy + load
    TASK = "TASK"               # coordinator -> agent: a task/subtask
    PARTIAL = "PARTIAL"         # agent -> coordinator: streamed partial output
    RESULT = "RESULT"           # agent -> coordinator: final output for a task
    VOTE = "VOTE"               # agent -> coordinator: candidate + score (voting mode)
    DECIDE = "DECIDE"           # coordinator -> all: final decision/aggregate
    CONTEXT_SYNC = "CONTEXT_SYNC"  # any -> group: append-only context delta (one entry)
    CONTEXT_BLOB = "CONTEXT_BLOB"  # any -> group/peer: content-addressed blob (ref + content)
    CONTEXT_PULL = "CONTEXT_PULL"  # peer -> owner: request a blob by ref


_TYPES = {t.value for t in MsgType}


@dataclass
class Message:
    """A HELIX protocol message (the plaintext sealed inside a frame)."""

    type: str
    src: str
    body: Dict[str, Any] = field(default_factory=dict)
    seq: int = 0
    tid: str = ""
    v: int = PROTOCOL_VERSION

    def serialize(self) -> bytes:
        obj = {"v": self.v, "t": self.type, "seq": self.seq, "src": self.src}
        if self.tid:
            obj["tid"] = self.tid
        if self.body:
            obj["b"] = self.body
        return json.dumps(obj, separators=(",", ":")).encode("utf-8")

    @staticmethod
    def parse(data: bytes) -> Optional["Message"]:
        """Parse plaintext into a :class:`Message`, or ``None`` if malformed.

        Only structurally valid, known-version, known-type messages are accepted;
        anything else is dropped (never raised), so a peer cannot use malformed
        payloads to probe behaviour.
        """
        try:
            obj = json.loads(bytes(data).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return None
        if not isinstance(obj, dict):
            return None
        if obj.get("v") != PROTOCOL_VERSION:
            return None
        t = obj.get("t")
        src = obj.get("src")
        if t not in _TYPES or not isinstance(src, str) or not src:
            return None
        seq = obj.get("seq", 0)
        if not isinstance(seq, int) or seq < 0:
            return None
        body = obj.get("b", {})
        if not isinstance(body, dict):
            return None
        return Message(type=t, src=src, body=body, seq=seq, tid=str(obj.get("tid", "")))
