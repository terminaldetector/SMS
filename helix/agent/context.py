"""Shared conversation context for agent coordination (Pointer / Track A).

Multiple agents need a common view of the dialogue (user turns, agent outputs, retrieved
docs) without shipping the whole context on every step — that is the multi-agent analogue
of prefill bandwidth. Three ideas make it work on a churning mesh:

* **Op-based CRDT, Lamport-ordered.** The context is an append-only *set* of entries keyed
  by ``(author, lamport)``. Each `CONTEXT_SYNC` carries only the new entry (a delta). Every
  node merges deltas into the same deterministic total order (sort by ``lamport`` then
  ``author``), so nodes converge with no central sequencer — a good fit for a mesh with no
  reliable total-order broadcast. Duplicate/replayed deltas are idempotent (same key).

* **Provenance.** An entry's ``author`` must equal the **authenticated frame ``src``** that
  delivered it (enforced in :class:`~helix.agent.node.AgentNode`). This blocks the context
  prompt-injection threat from the Pointer threat model: an agent cannot inject a turn "as"
  the user or another agent, and a renderer can label each entry by trusted author/role so
  peer output is never silently promoted to a system instruction.

* **Content addressing.** Large or repeated content (a system prompt, a document) is stored
  by SHA-256 and referenced by hash; the bytes travel once (as a `CONTEXT_BLOB`), and entries
  carry the 64-char ref. Analogous to the int8 activation codec: pay for the payload once.
"""

from __future__ import annotations

import hashlib
import time
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Tuple


def content_ref(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


@dataclass
class ContextEntry:
    author: str
    lamport: int
    role: str                        # "user" | "agent" | "doc" | "system" | ...
    content: Optional[str] = None    # inline content, OR
    ref: Optional[str] = None        # content-addressed reference (hash)
    ts: float = 0.0

    @property
    def key(self) -> Tuple[str, int]:
        return (self.author, self.lamport)

    def to_body(self) -> Dict:
        b: Dict = {"author": self.author, "lamport": self.lamport, "role": self.role, "ts": self.ts}
        if self.content is not None:
            b["content"] = self.content
        if self.ref is not None:
            b["ref"] = self.ref
        return b

    @staticmethod
    def from_body(body: Dict) -> Optional["ContextEntry"]:
        try:
            author = str(body["author"])
            lamport = int(body["lamport"])
            role = str(body["role"])
        except (KeyError, ValueError, TypeError):
            return None
        content = body.get("content")
        ref = body.get("ref")
        if content is None and ref is None:
            return None
        return ContextEntry(author, lamport, role,
                            None if content is None else str(content),
                            None if ref is None else str(ref),
                            float(body.get("ts", 0.0)))


class ContentStore:
    """SHA-256 content-addressed store; rejects blobs whose bytes don't match their ref."""

    def __init__(self) -> None:
        self._d: Dict[str, str] = {}

    def put(self, content: str) -> str:
        ref = content_ref(content)
        self._d[ref] = content
        return ref

    def put_with_ref(self, ref: str, content: str) -> bool:
        if content_ref(content) != ref:
            return False  # integrity: content doesn't hash to the claimed ref
        self._d[ref] = content
        return True

    def get(self, ref: str) -> Optional[str]:
        return self._d.get(ref)

    def has(self, ref: str) -> bool:
        return ref in self._d


class ContextLog:
    """Append-only, Lamport-ordered CRDT of :class:`ContextEntry` for one node."""

    def __init__(self, author: str) -> None:
        self.author = author
        self.clock = 0
        self._entries: Dict[Tuple[str, int], ContextEntry] = {}

    def append(self, role: str, *, content: Optional[str] = None, ref: Optional[str] = None) -> ContextEntry:
        self.clock += 1
        e = ContextEntry(self.author, self.clock, role, content, ref, ts=time.time())
        self._entries[e.key] = e
        return e

    def merge(self, e: ContextEntry) -> bool:
        """Merge a remote entry. Returns True if it was new (idempotent otherwise)."""
        if e.key in self._entries:
            return False
        self._entries[e.key] = e
        self.clock = max(self.clock, e.lamport)  # Lamport receive
        return True

    def ordered(self) -> List[ContextEntry]:
        return [self._entries[k] for k in sorted(self._entries, key=lambda k: (k[1], k[0]))]

    def frontier(self) -> Dict[str, int]:
        """Per-author highest lamport seen — the digest used for catch-up (anti-entropy)."""
        f: Dict[str, int] = {}
        for (author, lamport) in self._entries:
            f[author] = max(f.get(author, 0), lamport)
        return f

    def render(self, resolve: Callable[[ContextEntry], str]) -> str:
        """Render the shared context as provenance-labelled lines for a prompt."""
        return "\n".join("[{}:{}] {}".format(e.role, e.author, resolve(e)) for e in self.ordered())
