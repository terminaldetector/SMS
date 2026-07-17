"""Device capability — the enriched signal the scheduler needs (capability negotiation).

Extends the memory-only view (`Capacity`, `AgentCard`) with the hardware/runtime facts a
power-distributing scheduler wants: compute class, accelerators, backends, thermal, battery,
plug state, link RTT. On real devices these come from a host probe (Android/PC); the reference
uses synthetic values. `extensions` carries future HELIX-extension tags (the protocol already
has a version via `v`/`HLX1`/`epoch`).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import List

from helix.agent.card import AgentCard


@dataclass
class Capability:
    node_id: str
    mem_bytes: int = 0
    compute_score: float = 1.0     # relative throughput (tokens/s proxy)
    npu: bool = False
    gpu: bool = False
    backends: List[str] = field(default_factory=list)  # vulkan / metal / opencl / cpu
    thermal: float = 0.0           # 0 cool .. 1 hot
    battery: float = 1.0           # 0 empty .. 1 full
    plugged: bool = False
    net_rtt_ms: float = 0.0
    extensions: List[str] = field(default_factory=list)

    @staticmethod
    def from_card(card: AgentCard, *, compute_score: float = 0.0, gpu: bool = False,
                  backends: List[str] = None, thermal: float = 0.0, plugged: bool = False,
                  net_rtt_ms: float = 0.0) -> "Capability":
        return Capability(
            node_id=card.agent_id,
            mem_bytes=card.mem_bytes,
            compute_score=compute_score or (card.tokens_per_s or 1.0),
            npu=card.npu,
            gpu=gpu,
            backends=list(backends or []),
            thermal=thermal,
            battery=card.battery,
            plugged=plugged,
            net_rtt_ms=net_rtt_ms,
        )
