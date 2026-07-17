"""Network / mesh tuning parameters for a SuperAgent run.

One place to tune the knobs the operator asked for ("настраивание параметров сети"): TTL,
timeouts, transport preference, quantization, the default fusion strategy, and the scheduler's
thermal/battery thresholds. Passed into the scheduler, orchestrator and agent coordinator.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import List

STRATEGIES = ("hierarchical", "ensemble")


@dataclass
class MeshConfig:
    ttl: int = 8                       # routing TTL
    discovery_timeout_s: float = 2.0
    step_timeout_s: float = 3.0        # per decode step (Track B)
    engine_timeout_s: float = 10.0     # per engine (Track A / Track B) in the superagent
    transport_pref: List[str] = field(default_factory=lambda: ["wifi", "usb", "bitchat"])
    quant_level: str = "int8"          # "raw" | "int8" (activation codec, Track B)
    default_strategy: str = "hierarchical"
    thermal_max: float = 0.8           # exclude a device hotter than this (0..1)
    battery_min: float = 0.2           # exclude below this unless plugged (0..1)
    max_agents: int = 8
    ring_max_nodes: int = 8

    def validate(self) -> "MeshConfig":
        if self.ttl < 1:
            raise ValueError("ttl must be >= 1")
        if not (0.0 < self.thermal_max <= 1.0):
            raise ValueError("thermal_max must be in (0, 1]")
        if not (0.0 <= self.battery_min < 1.0):
            raise ValueError("battery_min must be in [0, 1)")
        if self.quant_level not in ("raw", "int8"):
            raise ValueError("quant_level must be 'raw' or 'int8'")
        if self.default_strategy not in STRATEGIES:
            raise ValueError("default_strategy must be one of {}".format(STRATEGIES))
        if self.max_agents < 1 or self.ring_max_nodes < 1:
            raise ValueError("max_agents/ring_max_nodes must be >= 1")
        return self
