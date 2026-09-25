"""Thumbnail loading: memory cache, disk cache, then the network."""

from __future__ import annotations

import hashlib
import logging
from collections import OrderedDict
from pathlib import Path

import requests
from gi.repository import Gdk, GLib

from . import tasks

log = logging.getLogger(__name__)

CACHE_DIR = Path.home() / ".cache" / "muzika" / "thumbnails"
_MEMORY_LIMIT = 300


class ImageLoader:
    def __init__(self) -> None:
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        self._memory: OrderedDict[str, Gdk.Texture] = OrderedDict()
        self._session = requests.Session()

    def _cache_path(self, url: str) -> Path:
        return CACHE_DIR / hashlib.sha256(url.encode()).hexdigest()[:32]

    def _remember(self, url: str, texture: Gdk.Texture) -> None:
        self._memory[url] = texture
        self._memory.move_to_end(url)
        while len(self._memory) > _MEMORY_LIMIT:
            self._memory.popitem(last=False)

    def load(self, url: str | None, callback) -> None:
        """Call `callback(texture_or_None)` on the main thread."""
        if not url:
            callback(None)
            return
        cached = self._memory.get(url)
        if cached is not None:
            self._memory.move_to_end(url)
            callback(cached)
            return

        path = self._cache_path(url)

        def work() -> bytes | None:
            if path.exists():
                return path.read_bytes()
            try:
                response = self._session.get(url, timeout=20)
                response.raise_for_status()
            except Exception as exc:  # noqa: BLE001
                log.debug("thumbnail fetch failed: %s", exc)
                return None
            data = response.content
            try:
                path.write_bytes(data)
            except OSError:
                pass
            return data

        def done(data: bytes | None) -> None:
            if not data:
                callback(None)
                return
            try:
                texture = Gdk.Texture.new_from_bytes(GLib.Bytes.new(data))
            except Exception:  # noqa: BLE001 - corrupt or unsupported image
                path.unlink(missing_ok=True)
                callback(None)
                return
            self._remember(url, texture)
            callback(texture)

        tasks.run_async(work, done, lambda _exc: callback(None))

    def clear_disk_cache(self) -> int:
        removed = 0
        for entry in CACHE_DIR.glob("*"):
            try:
                entry.unlink()
                removed += 1
            except OSError:
                pass
        self._memory.clear()
        return removed


loader = ImageLoader()
