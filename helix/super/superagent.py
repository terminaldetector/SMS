"""SuperAgent — one prompt across Track A (agents) + Track B (sharded model), fused.

Two strategies (both selectable, per the operator's ask):

* **hierarchical** — the small Track A agents contribute first; their outputs augment the prompt
  (provenance-labelled), and the big Track B model generates the final answer. "One powerful
  agent that has three small ones."
* **ensemble** — Track A and Track B run concurrently on the same prompt; a fusion function
  merges / cross-checks / votes between the big model and the agent consensus.

The two engines are injected as thin adapters (Protocols), so the SuperAgent logic is
unit-testable with reference engines and independent of the real GGUF/LiteRT runtimes. An
:class:`AgentNodeTrackA` adapter wraps the existing agent coordinator; the Track B engine is any
``async generate(prompt) -> str`` the app supplies (wrapping its sharded runtime).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Dict, Optional

try:
    from typing import Protocol, runtime_checkable
except ImportError:  # pragma: no cover
    Protocol = object  # type: ignore

    def runtime_checkable(cls):  # type: ignore
        return cls

from helix.super.config import MeshConfig
from helix.super.sync import gather_both, with_timeout

HIERARCHICAL = "hierarchical"
ENSEMBLE = "ensemble"


@runtime_checkable
class TrackBEngine(Protocol):
    async def generate(self, prompt: str) -> str: ...          # the big sharded model


@runtime_checkable
class TrackAEngine(Protocol):
    async def contributions(self, prompt: str) -> Dict[str, str]: ...  # {agent_id: result}


@dataclass
class SuperResult:
    text: str
    strategy: str
    contributors: Dict[str, Any] = field(default_factory=dict)  # {"track_b":..., "track_a":{...}}
    status: str = "ok"


Fuse = Callable[[str, Optional[str], Dict[str, str]], str]


def default_fuse(prompt: str, b_out: Optional[str], a_contribs: Dict[str, str]) -> str:
    """Big-model answer is primary; the agent consensus is attached as a cross-check."""
    a_join = " | ".join(a_contribs[k] for k in sorted(a_contribs)) if a_contribs else ""
    if b_out is None:
        return a_join
    if not a_join:
        return b_out
    return "{}  [cross-check: {}]".format(b_out, a_join)


class SuperAgent:
    def __init__(self, track_b: TrackBEngine, track_a: TrackAEngine, cfg: MeshConfig,
                 *, fuse: Optional[Fuse] = None) -> None:
        self._b = track_b
        self._a = track_a
        self.cfg = cfg
        self._fuse = fuse or default_fuse

    async def run(self, prompt: str, strategy: Optional[str] = None) -> SuperResult:
        strategy = strategy or self.cfg.default_strategy
        if strategy == HIERARCHICAL:
            return await self._hierarchical(prompt)
        if strategy == ENSEMBLE:
            return await self._ensemble(prompt)
        raise ValueError("unknown strategy: {}".format(strategy))

    async def _hierarchical(self, prompt: str) -> SuperResult:
        contribs = await with_timeout(self._a.contributions(prompt), self.cfg.engine_timeout_s, {})
        augmented = self._augment(prompt, contribs)
        text = await with_timeout(self._b.generate(augmented), self.cfg.engine_timeout_s, None)
        status = "ok" if text is not None else "partial"
        return SuperResult(text if text is not None else self._join(contribs), HIERARCHICAL,
                           {"track_a": contribs, "track_b": text}, status)

    async def _ensemble(self, prompt: str) -> SuperResult:
        a_contribs, b_out = await gather_both(
            self._a.contributions(prompt), self._b.generate(prompt),
            self.cfg.engine_timeout_s, a_default={}, b_default=None)
        fused = self._fuse(prompt, b_out, a_contribs)
        status = "ok" if (b_out is not None or a_contribs) else "failed"
        return SuperResult(fused, ENSEMBLE, {"track_a": a_contribs, "track_b": b_out}, status)

    def _augment(self, prompt: str, contribs: Dict[str, str]) -> str:
        if not contribs:
            return prompt
        notes = "\n".join("[assist:{}] {}".format(a, contribs[a]) for a in sorted(contribs))
        return prompt + "\n\n# assistant notes\n" + notes

    @staticmethod
    def _join(contribs: Dict[str, str]) -> str:
        return " | ".join(contribs[k] for k in sorted(contribs))


class AgentNodeTrackA:
    """Track A adapter over the existing agent coordinator (returns per-agent contributions)."""

    def __init__(self, node: Any, **coordinate_kw: Any) -> None:
        self._node = node
        self._kw = coordinate_kw

    async def contributions(self, prompt: str) -> Dict[str, str]:
        captured: Dict[str, str] = {}

        def aggregate(results: Dict[str, str]) -> str:
            captured.update(results)
            return ""

        await self._node.run_parallel(prompt, aggregate=aggregate, **self._kw)
        return dict(captured)


class CallableTrackB:
    """Track B adapter over any ``async (prompt) -> str`` (wrap the sharded runtime here)."""

    def __init__(self, generate: Callable[[str], Awaitable[str]]) -> None:
        self._generate = generate

    async def generate(self, prompt: str) -> str:
        return await self._generate(prompt)
