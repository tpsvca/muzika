"""Run blocking work off the UI thread and deliver results back on it."""

from __future__ import annotations

import logging
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Callable

from gi.repository import GLib

log = logging.getLogger(__name__)

_pool = ThreadPoolExecutor(max_workers=6, thread_name_prefix="muzika")


def run_async(work: Callable[[], Any],
              on_done: Callable[[Any], None] | None = None,
              on_error: Callable[[Exception], None] | None = None) -> None:
    """Call `work` in a worker thread; `on_done`/`on_error` run on the main loop."""

    def runner() -> None:
        try:
            result = work()
        except Exception as exc:  # noqa: BLE001 - surfaced to the caller
            log.warning("background task failed: %s", exc, exc_info=True)
            if on_error is not None:
                GLib.idle_add(on_error, exc)
            return
        if on_done is not None:
            GLib.idle_add(on_done, result)

    _pool.submit(runner)


class Latest:
    """Drops results from superseded requests.

    Typing in the search box fires a request per keystroke; without this the
    slowest response would win and overwrite the newest one.
    """

    def __init__(self) -> None:
        self._generation = 0

    def begin(self) -> int:
        self._generation += 1
        return self._generation

    def is_current(self, generation: int) -> bool:
        return generation == self._generation

    def guard(self, generation: int, callback: Callable[[Any], None]) -> Callable[[Any], None]:
        def wrapped(result: Any) -> None:
            if self.is_current(generation):
                callback(result)
        return wrapped


def shutdown() -> None:
    _pool.shutdown(wait=False, cancel_futures=True)
