"""Shard runner contract (L4) — the host object that runs one node's band of layers.

This is the seam a real engine implements: **GGUF/llama.cpp (RPC layer-split)** or
**ONNX Runtime (per-shard sub-model)**. HELIX relays and orchestrates; the runner does
the tensor math for a contiguous band ``[start, end)`` of layers and keeps that band's
KV cache locally.

Unlike exo-core's byte-opaque ``ShardRunner``, the hidden state crosses the HELIX
boundary as a **numeric vector** (``List[float]``). That is deliberate: the activation
codec (quantization / FEC — HELIX ①) can only compress a *known* tensor, not opaque
bytes. A native host may keep the tensor native internally and only surface the vector
at the cross-wire hop (or provide a native codec via the same seam).

Roles by position in the ring:
* first shard: ``embed`` (token id → hidden)
* every shard: ``forward`` (hidden → hidden, running its band)
* last shard:  ``sample`` (hidden → token id) and ``detok`` (id → text)
"""

from __future__ import annotations

from typing import List

try:
    from typing import Protocol, runtime_checkable
except ImportError:  # pragma: no cover
    Protocol = object  # type: ignore

    def runtime_checkable(cls):  # type: ignore
        return cls

from helix.placement import Band


@runtime_checkable
class ShardRunner(Protocol):
    def load(self, band: Band, n_layers: int, model_id: str = "", model_path: str = "") -> None: ...
    def embed(self, token_id: int) -> List[float]: ...       # first shard
    def forward(self, hidden: List[float]) -> List[float]: ...  # every shard
    def sample(self, hidden: List[float]) -> int: ...        # last shard
    def detok(self, token_id: int) -> str: ...               # last shard
    def eos_id(self) -> int: ...


class NumericShardRunner:
    """Dependency-free reference runner (no model), used to prove the ring is correct.

    Each "layer" is ``+1`` to every element of a small hidden vector, so a shard of ``L``
    layers adds ``L`` in :meth:`forward`. Starting from ``embed(t) = [t, 0, 0, 0]`` and
    sampling ``round(hidden[0])``, threading a token through **all** shards yields
    ``t + total_layers`` — which only holds if every device ran its band. That is the
    property the self-test checks.
    """

    def __init__(self, dim: int = 4) -> None:
        self._layers = 0
        self._dim = dim

    def load(self, band: Band, n_layers: int, model_id: str = "", model_path: str = "") -> None:
        self._layers = band.n

    def embed(self, token_id: int) -> List[float]:
        return [float(token_id)] + [0.0] * (self._dim - 1)

    def forward(self, hidden: List[float]) -> List[float]:
        return [x + self._layers for x in hidden]

    def sample(self, hidden: List[float]) -> int:
        return int(round(hidden[0]))

    def detok(self, token_id: int) -> str:
        return str(token_id)

    def eos_id(self) -> int:
        return -1
