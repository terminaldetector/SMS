"""Host runner adapter (native integration seam for Track A).

A native model runtime (Kotlin LiteRT via Chaquopy, or any JNI/FFI object) cannot hand
Python a streaming iterator from a native callback, so it exposes a **poll-based** contract
instead — exactly the pattern exo-core used for inference. :class:`PollingAgentRunner`
adapts that contract to :class:`~helix.agent.runner.AgentRunner`, so the rest of Track A is
unchanged.

Host contract (implement this in Kotlin/native)::

    interface HostAgentRunner {
        fun cardJson(): String                          // AgentCard body as JSON
        fun submit(taskId: String, prompt: String, context: String)
        fun poll(): String    // JSON: [{"task":"..","chunk":"..","done":false}, ...]
        fun score(prompt: String, result: String): Double
    }

    python -m helix.agent.host    # runs a fake-host self-test
"""

from __future__ import annotations

import json
import time
import uuid
from typing import Any, Iterable

from helix.agent.card import AgentCard
from helix.agent.runner import AgentRunner  # noqa: F401  (documents the target protocol)


class PollingAgentRunner:
    """Adapts a poll-based host runtime to the streaming :class:`AgentRunner` contract."""

    def __init__(self, host: Any, *, poll_interval_s: float = 0.0) -> None:
        self._host = host
        self._poll_interval = poll_interval_s
        self._card = AgentCard.from_body("", json.loads(host.cardJson()))

    def card(self) -> AgentCard:
        return self._card

    def infer(self, prompt: str, context: str = "") -> Iterable[str]:
        task_id = uuid.uuid4().hex
        self._host.submit(task_id, prompt, context)
        while True:
            for item in json.loads(self._host.poll() or "[]"):
                if item.get("task") != task_id:
                    continue
                chunk = item.get("chunk")
                if chunk:
                    yield str(chunk)
                if item.get("done"):
                    return
            if self._poll_interval:
                time.sleep(self._poll_interval)  # a real slow runtime: poll on a worker thread


def _selftest() -> None:
    class FakeHost:
        """Emits the prompt word-by-word (stands in for a Kotlin LiteRT runner)."""

        def __init__(self) -> None:
            self._pending: dict = {}

        def cardJson(self) -> str:  # noqa: N802 (host naming)
            return json.dumps({"agent_id": "host-1", "skills": ["chat"], "task_types": ["chat"]})

        def submit(self, task_id, prompt, context):
            self._pending[task_id] = (prompt + " " + context).split()

        def poll(self) -> str:
            out = []
            for tid, words in list(self._pending.items()):
                if words:
                    out.append({"task": tid, "chunk": words.pop(0) + " ", "done": False})
                else:
                    out.append({"task": tid, "done": True})
                    del self._pending[tid]
            return json.dumps(out)

        def score(self, prompt, result):
            return float(len(result))

    runner = PollingAgentRunner(FakeHost())
    assert runner.card().agent_id == "host-1" and "chat" in runner.card().skills
    out = "".join(runner.infer("hello world", "")).strip()
    assert out == "hello world", repr(out)
    print("host adapter: poll-based host -> AgentRunner.infer streams {!r}".format(out))
    print("ALL PASSED")


if __name__ == "__main__":
    _selftest()
