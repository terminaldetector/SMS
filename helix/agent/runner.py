"""Agent runner contract — the host object that runs one whole LiteRT model.

This is the Track-A analogue of :class:`~helix.shard.ShardRunner`: instead of a band of
layers, it runs a **whole** model as an agent. LiteRT (MediaPipe tasks-genai) implements
this — it already does tokenizer + sampling + streaming internally, so the contract is
just "given a prompt (and shared context), stream the answer".

A real runner runs on a background thread (generation is slow); the reference
:class:`EchoAgentRunner` is synchronous and deterministic so the coordination logic is
testable without a model.
"""

from __future__ import annotations

from typing import Callable, Iterable

try:
    from typing import Protocol, runtime_checkable
except ImportError:  # pragma: no cover
    Protocol = object  # type: ignore

    def runtime_checkable(cls):  # type: ignore
        return cls

from helix.agent.card import AgentCard


@runtime_checkable
class AgentRunner(Protocol):
    def card(self) -> AgentCard: ...
    def infer(self, prompt: str, context: str = "") -> Iterable[str]: ...  # stream chunks
    def score(self, prompt: str, result: str) -> float: ...               # self-assessed (voting)


class EchoAgentRunner:
    """Deterministic reference agent: its answer is ``transform(prompt, context)``.

    Different transforms model different "skills" so pipeline/parallel/voting can be
    verified exactly (e.g. an upper-caser, a reverser, an annotator).
    """

    def __init__(
        self,
        card: AgentCard,
        transform: Callable[[str, str], str] = lambda p, c: p,
        score: Callable[[str, str], float] = lambda p, r: float(len(r)),
    ) -> None:
        self._card = card
        self._transform = transform
        self._score = score

    def card(self) -> AgentCard:
        return self._card

    def infer(self, prompt: str, context: str = "") -> Iterable[str]:
        out = self._transform(prompt, context)
        for word in out.split(" "):
            yield word + " "

    def score(self, prompt: str, result: str) -> float:
        return self._score(prompt, result)
