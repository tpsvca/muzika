"""Music files on disk: finding them, reading their tags, keeping them indexed.

GStreamer is already a hard dependency for playback, and its discoverer reads
tags for every format it can play, so there is no second tag library here and
nothing new to install.

A file's identity is its path inside the shared layout:

    <library folder name>/<whatever structure it already had>

which is exactly how it is laid out under `<sync folder>/Music` on every
device. Both ends therefore compute the *same* id for the same file, so a
playlist built here still resolves on the phone. Getting this wrong is subtle
and total: the ids silently diverge and every synced playlist entry dangles.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path

import gi

gi.require_version("Gst", "1.0")
gi.require_version("GstPbutils", "1.0")
from gi.repository import Gst, GstPbutils  # noqa: E402

log = logging.getLogger(__name__)

SOURCE = "local"

AUDIO_SUFFIXES = {
    ".mp3", ".flac", ".m4a", ".aac", ".ogg", ".oga", ".opus",
    ".wav", ".wma", ".aiff", ".aif", ".alac", ".mka", ".ape",
}

COVER_DIR = Path.home() / ".cache" / "muzika" / "covers"

_discoverer: GstPbutils.Discoverer | None = None


def _get_discoverer() -> GstPbutils.Discoverer:
    global _discoverer
    if _discoverer is None:
        if not Gst.is_initialized():
            Gst.init(None)
        # Five seconds is generous for a local file and stops one damaged
        # file from stalling a whole scan.
        _discoverer = GstPbutils.Discoverer.new(5 * Gst.SECOND)
    return _discoverer


def track_id(relative: str) -> str:
    """Stable across devices, because it is derived from the relative path."""
    return f"local:{relative}"


def is_audio(path: Path) -> bool:
    return path.suffix.lower() in AUDIO_SUFFIXES


def iter_audio_files(root: Path):
    """Every audio file under root, skipping hidden and sync-service dirs."""
    skip = {".stversions", ".stfolder", ".dropbox.cache", "@eaDir"}
    for folder, dirs, names in os.walk(root, followlinks=False):
        dirs[:] = sorted(d for d in dirs if not d.startswith(".") and d not in skip)
        for name in sorted(names):
            if name.startswith("."):
                continue
            path = Path(folder) / name
            if is_audio(path):
                yield path


# ------------------------------------------------------------------- tags

def _tag_string(tags, key: str) -> str | None:
    if tags is None:
        return None
    ok, value = tags.get_string(key)
    if ok and value and value.strip():
        return value.strip()
    return None


def _tag_uint(tags, key: str) -> int | None:
    if tags is None:
        return None
    ok, value = tags.get_uint(key)
    return value if ok else None


def _cover_from_tags(tags, album_key: str) -> str | None:
    """Write embedded art out once per album and hand back the path."""
    if tags is None:
        return None
    ok, sample = tags.get_sample(Gst.TAG_IMAGE)
    if not ok:
        ok, sample = tags.get_sample(Gst.TAG_PREVIEW_IMAGE)
    if not ok or sample is None:
        return None

    safe = "".join(c if c.isalnum() or c in "-_" else "_" for c in album_key)[:80]
    destination = COVER_DIR / f"{safe or 'album'}.jpg"
    if destination.exists():
        return str(destination)

    buffer = sample.get_buffer()
    if buffer is None:
        return None
    got, info = buffer.map(Gst.MapFlags.READ)
    if not got:
        return None
    try:
        COVER_DIR.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(bytes(info.data))
    except OSError:
        return None
    finally:
        buffer.unmap(info)
    return str(destination)


def _folder_cover(path: Path) -> str | None:
    """Many libraries keep the art beside the songs rather than inside them."""
    for name in ("cover", "folder", "front", "album", "albumart"):
        for suffix in (".jpg", ".jpeg", ".png", ".webp"):
            candidate = path.parent / f"{name}{suffix}"
            if candidate.exists():
                return str(candidate)
    return None


def read_track(path: Path, root: Path) -> dict | None:
    """One file as a track dict, or None when it is not really audio."""
    try:
        inside = path.relative_to(root)
    except ValueError:
        return None
    # Prefixed with the folder's own name, which is how it is laid out once
    # shared, so this id matches the one the phone computes.
    relative = str(Path(root.name) / inside)

    title = artist = album = None
    duration = 0
    number = None
    thumb = None

    try:
        info = _get_discoverer().discover_uri(Gst.filename_to_uri(str(path)))
        if not info.get_audio_streams():
            return None
        duration = int((info.get_duration() or 0) / Gst.SECOND)
        tags = info.get_tags()
        title = _tag_string(tags, Gst.TAG_TITLE)
        artist = (_tag_string(tags, Gst.TAG_ARTIST)
                  or _tag_string(tags, Gst.TAG_ALBUM_ARTIST)
                  or _tag_string(tags, Gst.TAG_COMPOSER))
        album = _tag_string(tags, Gst.TAG_ALBUM)
        number = _tag_uint(tags, Gst.TAG_TRACK_NUMBER)
        thumb = _cover_from_tags(tags, f"{artist or ''}-{album or path.parent.name}")
    except Exception as exc:
        # A file we cannot read is skipped, never fatal to the scan.
        log.debug("could not read %s: %s", path, exc)
        if not is_audio(path):
            return None

    # Fall back to the path when the file carries no tags: the near-universal
    # layout is <artist>/<album>/<nn> <title>. Counting from the end rather
    # than the start keeps this right however deep the library sits.
    parts = Path(relative).parts
    if not title:
        stem = path.stem
        cleaned = stem.split(" ", 1)[1] if stem.split(" ", 1)[0].strip("-.").isdigit() \
            and " " in stem else stem
        title = cleaned.replace("_", " ").strip() or path.name
    if not artist:
        artist = parts[-3].replace("_", " ") if len(parts) >= 3 else ""
    if not album:
        album = parts[-2].replace("_", " ") if len(parts) >= 2 else ""
    if thumb is None:
        thumb = _folder_cover(path)

    return {
        "kind": "song",
        "id": track_id(relative),
        "title": title,
        "subtitle": artist,
        "artists": artist,
        "album": album,
        "duration": duration,
        "thumb": thumb,
        "source": SOURCE,
        "url": relative,
        "track_number": number or 0,
    }


def scan(root: str | os.PathLike, progress=None) -> list[dict]:
    """Index one music folder. `progress(done, path)` is called as it goes."""
    base = Path(root).expanduser()
    if not base.is_dir():
        return []
    found: list[dict] = []
    for index, path in enumerate(iter_audio_files(base), start=1):
        track = read_track(path, base)
        if track is not None:
            found.append(track)
        if progress is not None:
            progress(index, path)
    log.info("indexed %d files under %s", len(found), base)
    return found


def resolve(track: dict, roots: list[str], shared: str | os.PathLike | None = None) -> str | None:
    """Absolute path for a local track.

    The id carries the library folder's name as its first component, so a
    music folder matches by having that name, while the shared copy under
    `<sync folder>/Music` matches the whole relative path as-is.
    """
    relative = track.get("url") or ""
    if not relative:
        return None
    if os.path.isabs(relative) and os.path.exists(relative):
        return relative

    head, _, tail = relative.partition("/")
    for root in roots:
        base = Path(root).expanduser()
        # <music folder>/<path inside it>, once its own name is stripped back off
        if tail and base.name == head:
            candidate = base / tail
            if candidate.exists():
                return str(candidate)
        candidate = base / relative
        if candidate.exists():
            return str(candidate)
    if shared:
        candidate = Path(shared).expanduser() / relative
        if candidate.exists():
            return str(candidate)
    return None


# ---------------------------------------------------------------- sharing

def share_plan(roots: list[str], destination: Path) -> tuple[list[tuple[Path, Path]], int]:
    """What would have to be copied for the phone to have this music.

    Returns the (source, target) pairs that are missing or stale, and the
    total byte count, so the caller can tell the user before it starts.
    """
    pending: list[tuple[Path, Path]] = []
    total = 0
    for root in roots:
        base = Path(root).expanduser()
        if not base.is_dir():
            continue
        # Each folder keeps its own name, which is also the first component of
        # every track id, so both devices agree on what a file is called.
        prefix = destination / base.name
        for path in iter_audio_files(base):
            target = prefix / path.relative_to(base)
            try:
                if target.exists() and target.stat().st_size == path.stat().st_size:
                    continue
                total += path.stat().st_size
            except OSError:
                continue
            pending.append((path, target))
    return pending, total


def share(roots: list[str], destination: Path, progress=None) -> tuple[int, int]:
    """Copy music into the synced folder. Returns (copied, failed).

    The files are copied, not moved: the original library stays exactly where
    it is, and the sync service carries the copy.
    """
    import shutil

    pending, _ = share_plan(roots, destination)
    copied = failed = 0
    for index, (source, target) in enumerate(pending, start=1):
        try:
            target.parent.mkdir(parents=True, exist_ok=True)
            temporary = target.with_name(f".{target.name}.part")
            shutil.copy2(source, temporary)
            temporary.replace(target)
            copied += 1
        except OSError as exc:
            log.warning("could not share %s: %s", source, exc)
            failed += 1
        if progress is not None:
            progress(index, len(pending), source)
    return copied, failed


def human_size(count: int) -> str:
    size = float(count)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if size < 1024 or unit == "TB":
            return f"{size:.0f} {unit}" if unit in ("B", "KB") else f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} TB"
