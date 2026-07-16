"""Agent registry — capabilities + status + liveness (Track A's L3).

Fed by authenticated ``AGENT_ANNOUNCE`` (capability cards) and ``STATUS`` (free/busy).
Answers "which live agent can do this task, and is it free" for the coordinator, and —
as in the tensor track — reports which agents have gone silent so their in-flight task
can be re-routed.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional

from helix.agent.card import AgentCard


@dataclass
class _Entry:
    card: AgentCard
    status: str  # "free" | "busy"
    last_seen: float


class AgentRegistry:
    def __init__(self, ttl_s: float = 4.0, *, clock: Callable[[], float] = time.monotonic) -> None:
        self._ttl = ttl_s
        self._clock = clock
        self._entries: Dict[str, _Entry] = {}

    def observe(self, agent_id: str, card: Optional[AgentCard] = None, status: Optional[str] = None) -> None:
        now = self._clock()
        e = self._entries.get(agent_id)
        if e is None:
            self._entries[agent_id] = _Entry(card or AgentCard(agent_id), status or "free", now)
        else:
            e.last_seen = now
            if card is not None:
                e.card = card
            if status is not None:
                e.status = status

    def set_status(self, agent_id: str, status: str) -> None:
        e = self._entries.get(agent_id)
        if e is not None:
            e.status = status

    def status(self, agent_id: str) -> Optional[str]:
        e = self._entries.get(agent_id)
        return e.status if e else None

    def live(self) -> List[str]:
        now = self._clock()
        return [a for a, e in self._entries.items() if now - e.last_seen <= self._ttl]

    def is_live(self, agent_id: str) -> bool:
        e = self._entries.get(agent_id)
        return e is not None and self._clock() - e.last_seen <= self._ttl

    def match(self, *, task_type: Optional[str] = None, skill: Optional[str] = None,
              model: Optional[str] = None, free_only: bool = False,
              exclude: Optional[set] = None) -> List[str]:
        """Live agents that satisfy the requirement, free ones first."""
        exclude = exclude or set()
        cand = []
        for a in self.live():
            if a in exclude:
                continue
            e = self._entries[a]
            if e.card.satisfies(task_type=task_type, skill=skill, model=model):
                if free_only and e.status != "free":
                    continue
                cand.append(a)
        cand.sort(key=lambda a: (self._entries[a].status != "free", a))  # free first, stable
        return cand

    def prune(self) -> List[str]:
        now = self._clock()
        lost = [a for a, e in self._entries.items() if now - e.last_seen > self._ttl]
        for a in lost:
            self._entries.pop(a, None)
        return lost
