"""Sync module — coordinate the tasker (Track A) and the mesh-slice (Track B).

"Синхромодуль таскера и меш-слайса": barriers, per-engine timeouts and partial-result merge so
one slow/failing engine can't hang or crash a SuperAgent run. Hierarchical runs the engines in
order (A → B); ensemble runs them concurrently.
"""

from __future__ import annotations

import asyncio
from typing import Any, Awaitable, Optional, Tuple

from helix.log import get_logger

logger = get_logger("super.sync")


async def with_timeout(coro: Awaitable[Any], timeout_s: float, default: Any = None) -> Any:
    """Await ``coro`` with a bound; on timeout or error return ``default`` (don't propagate)."""
    try:
        return await asyncio.wait_for(coro, timeout_s)
    except asyncio.TimeoutError:
        logger.warning("engine timed out after %.1fs", timeout_s)
        return default
    except Exception as exc:  # one engine failing must not sink the whole run
        logger.warning("engine failed: %s", exc)
        return default


async def gather_both(
    a_coro: Awaitable[Any], b_coro: Awaitable[Any], timeout_s: float,
    *, a_default: Any = None, b_default: Any = None,
) -> Tuple[Any, Any]:
    """Run both engines concurrently, each bounded; return ``(a_result, b_result)``."""
    return await asyncio.gather(
        with_timeout(a_coro, timeout_s, a_default),
        with_timeout(b_coro, timeout_s, b_default),
    )
