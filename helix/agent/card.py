"""Agent capability card (the local analogue of an A2A Agent Card).

Advertised in ``AGENT_ANNOUNCE`` so the coordinator can match a task to a capable
agent. Same idea as A2A's Agent Card, but carried over HELIX frames on the local mesh
instead of served over HTTP at a well-known URL.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass
class AgentCard:
    agent_id: str
    models: List[str] = field(default_factory=list)      # e.g. ["gemma-3n"]
    skills: List[str] = field(default_factory=list)       # e.g. ["rag", "code", "summarize"]
    task_types: List[str] = field(default_factory=list)   # e.g. ["chat", "analyze", "generate"]
    mem_bytes: int = 0
    tokens_per_s: float = 0.0
    battery: float = 1.0
    extensions: List[str] = field(default_factory=list)  # optional HELIX-extension tags

    def satisfies(self, *, task_type: Optional[str] = None, skill: Optional[str] = None,
                  model: Optional[str] = None) -> bool:
        if task_type is not None and self.task_types and task_type not in self.task_types:
            return False
        if skill is not None and skill not in self.skills:
            return False
        if model is not None and model not in self.models:
            return False
        return True

    def to_body(self) -> Dict[str, Any]:
        body = {
            "agent_id": self.agent_id, "models": self.models, "skills": self.skills,
            "task_types": self.task_types, "mem": self.mem_bytes,
            "tps": self.tokens_per_s, "batt": self.battery,
        }
        if self.extensions:  # omitted when empty -> existing wire/conformance unchanged
            body["ext"] = self.extensions
        return body

    @staticmethod
    def from_body(src: str, body: Dict[str, Any]) -> "AgentCard":
        return AgentCard(
            agent_id=str(body.get("agent_id", src)),
            models=list(body.get("models", [])),
            skills=list(body.get("skills", [])),
            task_types=list(body.get("task_types", [])),
            mem_bytes=int(body.get("mem", 0)),
            tokens_per_s=float(body.get("tps", 0.0)),
            battery=float(body.get("batt", 1.0)),
            extensions=list(body.get("ext", [])),
        )
