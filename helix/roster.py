"""Membership + liveness (L3).

The :class:`Roster` is a node's view of the cluster, fed by authenticated ``ANNOUNCE``
(capacity) and ``HEARTBEAT`` (liveness) frames. It answers "who is here and how big are
they" for placement, and — crucially for self-healing — "who has gone quiet" so a lapsed
node's lease can be reassigned.

The clock is injectable so liveness is deterministically testable without real sleeps.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional

from helix.placement import Capacity


@dataclass
class RosterEntry:
    node_id: str
    capacity: Capacity
    last_seen: float


class Roster:
    def __init__(self, ttl_s: float = 6.0, *, clock: Callable[[], float] = time.monotonic) -> None:
        self._ttl = ttl_s
        self._clock = clock
        self._entries: Dict[str, RosterEntry] = {}

    def observe(self, node_id: str, capacity: Optional[Capacity] = None) -> None:
        """Record presence/liveness of ``node_id`` (ANNOUNCE carries capacity, HEARTBEAT
        does not — capacity is kept from the last ANNOUNCE)."""
        now = self._clock()
        entry = self._entries.get(node_id)
        if entry is None:
            self._entries[node_id] = RosterEntry(node_id, capacity or Capacity(0), now)
        else:
            entry.last_seen = now
            if capacity is not None:
                entry.capacity = capacity

    def live(self) -> List[str]:
        now = self._clock()
        return [n for n, e in self._entries.items() if now - e.last_seen <= self._ttl]

    def capacities(self) -> Dict[str, Capacity]:
        """Capacities of currently-live nodes (input to placement)."""
        now = self._clock()
        return {n: e.capacity for n, e in self._entries.items() if now - e.last_seen <= self._ttl}

    def prune(self) -> List[str]:
        """Drop and return nodes whose liveness has lapsed (the 'lost' set)."""
        now = self._clock()
        lost = [n for n, e in self._entries.items() if now - e.last_seen > self._ttl]
        for n in lost:
            self._entries.pop(n, None)
        return lost

    def __contains__(self, node_id: object) -> bool:
        return node_id in self._entries

    def get(self, node_id: str) -> Optional[RosterEntry]:
        return self._entries.get(node_id)
