"""What is playing, kept on disk: across restarts, and across devices.

Two jobs, one format.

**Restarts.** The queue lived only in memory, so closing the window lost it.
It is now kept under ~/.local/share/muzika in two files, because they change
at very different rates: the queue (hundreds of tracks, rewritten only when it
changes) and the position (a few bytes, rewritten every few seconds).

**Handoff.** `muzika-nowplaying.json`, written into the sync folder beside
the library file, carries the same state to the phone and back. Pause here,
press play on the phone, and it carries on with the same song from the same
spot in the same shuffled order. Every device writes it when its own playback
changes; pressing play takes it over when it is newer than anything this
device did itself. The format is in docs/sync-format.md, and the Android
reader is player/Handoff.kt - the two must stay in step.
"""

from __future__ import annotations

import json
import logging
import os
import socket
import time
import uuid
from pathlib import Path

from . import sync as sync_mod
from . import syncthing
from .db import DATA_DIR

log = logging.getLogger(__name__)

QUEUE_FILE = "session-queue.json"
POSITION_FILE = "session-position.json"
HANDOFF_FILE = "muzika-nowplaying.json"
HANDOFF_FORMAT = "muzika-nowplaying"
VERSION = 1

REPEAT_NAMES = ("none", "all", "one")

#: How long a paused session stays resumable. Asked for: at least a week.
MAX_AGE = 30 * 24 * 3600

#: How long a session reported as still playing is assumed to have kept
#: playing. A writer killed mid-song never says it stopped; without a bound
#: its position would be wound forward through the whole queue days later.
MAX_EXTRAPOLATE = 2 * 3600

#: Where the local files go; tests point this somewhere else.
session_dir: Path = DATA_DIR


class Snapshot(dict):
    """queue, order, cursor, shuffle, repeat, position_ms, duration_ms, saved_at
    - plus device and device_id when it came from another machine."""


# ------------------------------------------------------------------- devices

def device_id() -> str:
    """Tells this install's own handoff write apart from another device's."""
    value = sync_mod.get("device_id")
    if not value:
        value = str(uuid.uuid4())
        sync_mod.put("device_id", value)
    return value


def device_name() -> str:
    """Only ever shown ("Picked up … from fedora")."""
    return sync_mod.get("device_name") or socket.gethostname() or "desktop"


# ------------------------------------------------------------------- writing

def _write_atomically(target: Path, text: str) -> None:
    """Renamed into place, so neither a crash nor a sync client ever sees half
    a file. The temporary name is one Syncthing ignores by default."""
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        temp = target.with_name(f".syncthing.{target.name}.tmp")
        temp.write_text(text, encoding="utf-8")
        os.replace(temp, target)
    except OSError as exc:
        log.warning("could not save %s: %s", target.name, exc)


def _track_payload(track: dict) -> dict:
    payload = sync_mod._track_payload(track)
    # Every source, YouTube included, so the reader never has to guess.
    payload.setdefault("source", track.get("source") or "youtube")
    payload.setdefault("url", track.get("url"))
    if track.get("album"):
        payload["album"] = track["album"]
    return payload


def _track_from_payload(entry: dict) -> dict:
    track = sync_mod._track_from_payload(entry)
    if track.get("source") == "youtube" and not track.get("url"):
        track.pop("source", None)
        track.pop("url", None)
    if entry.get("album"):
        track["album"] = entry["album"]
    return track


def save_queue(queue: list[dict], order: list[int], shuffle: bool) -> None:
    _write_atomically(session_dir / QUEUE_FILE, json.dumps({
        "version": VERSION,
        "saved_at": time.time(),
        "shuffle": shuffle,
        "queue": [_track_payload(t) for t in queue],
        "order": order,
    }))


def save_position(track: dict | None, cursor: int, position_ms: int,
                  duration_ms: int, repeat: str) -> None:
    _write_atomically(session_dir / POSITION_FILE, json.dumps({
        "track_id": track.get("id") if track else None,
        "cursor": cursor,
        "position_ms": max(0, int(position_ms)),
        "duration_ms": max(0, int(duration_ms)),
        "repeat": repeat,
        "saved_at": time.time(),
    }))


def encode_handoff(queue: list[dict], order: list[int], cursor: int, shuffle: bool,
                   repeat: str, position_ms: int, duration_ms: int, playing: bool,
                   device_id_: str, device: str, now: float) -> str:
    current = queue[order[cursor]] if 0 <= cursor < len(order) and order[cursor] < len(queue) else None
    return json.dumps({
        "format": HANDOFF_FORMAT,
        "version": VERSION,
        "device_id": device_id_,
        "device": device,
        "updated_at": now,
        "playing": playing,
        "position_ms": max(0, int(position_ms)),
        "duration_ms": max(0, int(duration_ms)),
        "cursor": cursor,
        "track_id": current.get("id") if current else None,
        "shuffle": shuffle,
        "repeat": repeat if repeat in REPEAT_NAMES else "none",
        "queue": [_track_payload(t) for t in queue],
        "order": order,
    })


def handoff_path() -> Path | None:
    """Only the folder backend has a folder to put it in."""
    folder = sync_mod.sync_folder()
    if folder is None or sync_mod.backend() != sync_mod.BACKEND_FOLDER:
        return None
    return folder / HANDOFF_FILE


def publish(queue, order, cursor, shuffle, repeat, position_ms, duration_ms, playing) -> None:
    target = handoff_path()
    if target is None or not target.parent.is_dir():
        return
    _write_atomically(target, encode_handoff(
        queue, order, cursor, shuffle, repeat, position_ms, duration_ms, playing,
        device_id(), device_name(), time.time()))
    # Left alone, Syncthing waits out its watcher delay before it even looks.
    # A pause is the moment you are about to pick the phone up, so tell it now.
    syncthing.rescan_async(target.parent)


def clear_local() -> None:
    for name in (QUEUE_FILE, POSITION_FILE):
        try:
            (session_dir / name).unlink()
        except OSError:
            pass


# ------------------------------------------------------------------- reading

def _clean_order(raw, queue) -> list[int]:
    seen, order = set(), []
    for value in raw if isinstance(raw, list) else []:
        if isinstance(value, int) and 0 <= value < len(queue) and value not in seen:
            seen.add(value)
            order.append(value)
    return order


def _find_cursor(queue, order, stored, track_id) -> int | None:
    """Trust the stored cursor only where its track actually sits; otherwise
    look the track up by id. None when the track is not in the queue at all."""
    if not isinstance(stored, int):
        stored = -1
    if track_id is None:
        return stored if 0 <= stored < len(order) else None
    if 0 <= stored < len(order) and queue[order[stored]].get("id") == track_id:
        return stored
    for index, entry in enumerate(order):
        if queue[entry].get("id") == track_id:
            return index
    return None


def decode_local(queue_text: str, position_text: str | None, now: float) -> Snapshot | None:
    try:
        saved = json.loads(queue_text)
        queue = [_track_from_payload(e) for e in saved["queue"] if e.get("id")]
    except (ValueError, KeyError, TypeError, AttributeError) as exc:
        log.warning("saved session unreadable: %s", exc)
        return None
    order = _clean_order(saved.get("order"), queue)
    try:
        position = json.loads(position_text) if position_text else {}
        if not isinstance(position, dict):
            position = {}
    except ValueError:
        position = {}
    saved_at = max(float(saved.get("saved_at") or 0), float(position.get("saved_at") or 0))
    if not queue or not order or now - saved_at > MAX_AGE:
        return None
    cursor = _find_cursor(queue, order, position.get("cursor"), position.get("track_id"))
    return Snapshot(
        queue=queue, order=order, cursor=cursor if cursor is not None else 0,
        shuffle=bool(saved.get("shuffle")),
        repeat=position.get("repeat") if position.get("repeat") in REPEAT_NAMES else "none",
        # A position belongs to one track; if that track was lost, so is it.
        position_ms=int(position.get("position_ms") or 0) if cursor is not None else 0,
        duration_ms=int(position.get("duration_ms") or 0) if cursor is not None else 0,
        saved_at=saved_at,
    )


def load_local(now: float | None = None) -> Snapshot | None:
    try:
        queue_text = (session_dir / QUEUE_FILE).read_text(encoding="utf-8")
    except OSError:
        return None
    try:
        position_text = (session_dir / POSITION_FILE).read_text(encoding="utf-8")
    except OSError:
        position_text = None
    return decode_local(queue_text, position_text, time.time() if now is None else now)


def advance(queue, order, cursor, position_ms, repeat) -> tuple[int, int]:
    """Where a queue that kept playing has got to, walking forward through the
    order by each track's length. Stops on a track of unknown length."""
    if repeat == "one":
        length = int(queue[order[cursor]].get("duration") or 0) * 1000
        return cursor, (position_ms % length if length > 0 else position_ms)
    at, position = cursor, position_ms
    while True:
        length = int(queue[order[at]].get("duration") or 0) * 1000
        if length <= 0 or position < length:
            return at, position
        position -= length
        if at + 1 < len(order):
            at += 1
        elif repeat == "all":
            at = 0
        else:
            return at, 0        # ran off the end: it stopped there


def decode_handoff(text: str, now: float) -> Snapshot | None:
    try:
        saved = json.loads(text)
        if saved.get("format") != HANDOFF_FORMAT:
            return None
        queue = [_track_from_payload(e) for e in saved["queue"] if e.get("id")]
    except (ValueError, KeyError, TypeError, AttributeError) as exc:
        log.warning("%s unreadable: %s", HANDOFF_FILE, exc)
        return None
    order = _clean_order(saved.get("order"), queue)
    updated_at = float(saved.get("updated_at") or 0)
    if not queue or not order or now - updated_at > MAX_AGE:
        return None
    cursor = _find_cursor(queue, order, saved.get("cursor"), saved.get("track_id"))
    repeat = saved.get("repeat") if saved.get("repeat") in REPEAT_NAMES else "none"
    position = int(saved.get("position_ms") or 0) if cursor is not None else 0
    at = cursor if cursor is not None else 0
    # Still playing over there: it has moved on since it wrote.
    elapsed = now - updated_at
    if cursor is not None and saved.get("playing") and 0 < elapsed <= MAX_EXTRAPOLATE:
        at, position = advance(queue, order, at, position + int(elapsed * 1000), repeat)
    return Snapshot(
        queue=queue, order=order, cursor=at, shuffle=bool(saved.get("shuffle")),
        repeat=repeat, position_ms=max(0, position),
        duration_ms=int(saved.get("duration_ms") or 0) if at == cursor else 0,
        saved_at=updated_at,
        device=saved.get("device") or "another device",
        device_id=saved.get("device_id") or "",
    )


_cache: dict = {"key": None, "text": None}


def newer_than(local_changed_at: float, now: float | None = None) -> Snapshot | None:
    """Another device's session, when it is newer than this device's own last
    change. Asked on every play press and window focus, so the file is only
    re-read when its timestamp or size moved."""
    path = handoff_path()
    if path is None:
        return None
    try:
        stat = path.stat()
        key = (str(path), stat.st_mtime_ns, stat.st_size)
        if key != _cache["key"]:
            _cache["text"] = path.read_text(encoding="utf-8")
            _cache["key"] = key
    except OSError:
        return None
    remote = decode_handoff(_cache["text"], time.time() if now is None else now)
    if remote is None or remote["device_id"] == device_id():
        return None
    return remote if remote["saved_at"] > local_changed_at else None
