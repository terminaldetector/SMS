"""Logging shim: stdlib logging on CPython, ``android.util.Log`` under Chaquopy."""

from __future__ import annotations

import logging
from typing import Optional

_TAG = "HELIX"
_configured = False


def _android_log():
    try:
        from java import jclass  # type: ignore

        return jclass("android.util.Log")
    except Exception:
        return None


class _AndroidHandler(logging.Handler):
    def __init__(self) -> None:
        super().__init__()
        self._log = _android_log()

    def emit(self, record: logging.LogRecord) -> None:
        if self._log is None:
            return
        msg = self.format(record)
        try:
            if record.levelno >= logging.ERROR:
                self._log.e(_TAG, msg)
            elif record.levelno >= logging.WARNING:
                self._log.w(_TAG, msg)
            elif record.levelno >= logging.INFO:
                self._log.i(_TAG, msg)
            else:
                self._log.d(_TAG, msg)
        except Exception:
            pass


def _configure() -> None:
    global _configured
    if _configured:
        return
    root = logging.getLogger("helix")
    root.setLevel(logging.INFO)
    handler = _AndroidHandler() if _android_log() is not None else logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(name)s: %(message)s"))
    root.addHandler(handler)
    root.propagate = False
    _configured = True


def get_logger(name: Optional[str] = None) -> logging.Logger:
    _configure()
    return logging.getLogger("helix." + name if name else "helix")
