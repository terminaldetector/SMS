"""Activation codec (L4 / HELIX ①) — how a hidden-state tensor crosses the wire.

Only activations cross device boundaries in a layer-shard pipeline, and during prefill
they dominate bandwidth (``d_model × seq_len`` per hop). This module abstracts *how* an
activation is put on the wire so the encoding can improve without touching the ring
driver:

* :class:`RawActivationCodec` — exact ``float`` list. Reference / correctness tests.
* :class:`Int8ActivationCodec` — per-tensor symmetric int8 quantization (≈4× smaller
  than fp32 before framing). This is the practical, pure-Python half of HELIX ①.

The remaining half — rateless FEC (fountain/RaptorQ) so a lost datagram doesn't stall
the ring — plugs in behind the same interface and is better done in the host (native);
the interface is here so that swap needs no ring-driver change.

Payloads are JSON-embeddable (dict). A binary body path would remove the base64 overhead
of the quantized form and is a follow-up; even with it, int8 is a large net win.
"""

from __future__ import annotations

import base64
from abc import ABC, abstractmethod
from typing import Any, Dict, List


class ActivationCodec(ABC):
    @abstractmethod
    def encode(self, vec: List[float]) -> Dict[str, Any]:
        """Return a JSON-embeddable representation of ``vec``."""

    @abstractmethod
    def decode(self, payload: Dict[str, Any]) -> List[float]:
        """Inverse of :meth:`encode`."""


class RawActivationCodec(ActivationCodec):
    """Exact fp32 list — lossless, used to verify ring correctness."""

    def encode(self, vec: List[float]) -> Dict[str, Any]:
        return {"raw": [float(x) for x in vec]}

    def decode(self, payload: Dict[str, Any]) -> List[float]:
        return [float(x) for x in payload["raw"]]


class Int8ActivationCodec(ActivationCodec):
    """Per-tensor symmetric int8 quantization: ``q = round(x / scale)``, ``scale = max|x|/127``.

    Lossy (error ≤ scale/2 per element) but ~4× smaller than fp32 and far smaller than an
    fp32 JSON list. Stored as unsigned bytes (offset +128) so each element is one byte.
    """

    def encode(self, vec: List[float]) -> Dict[str, Any]:
        m = max((abs(float(x)) for x in vec), default=0.0)
        scale = m / 127.0 if m > 0 else 1.0
        q = bytes(((max(-127, min(127, int(round(x / scale)))) + 128) & 0xFF) for x in vec)
        return {"q": base64.b64encode(q).decode("ascii"), "s": scale, "n": len(vec)}

    def decode(self, payload: Dict[str, Any]) -> List[float]:
        raw = base64.b64decode(payload["q"])
        scale = float(payload["s"])
        return [(b - 128) * scale for b in raw]
