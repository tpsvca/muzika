"""Library sync through a plain file in a folder you already sync.

Syncthing, Dropbox, Nextcloud and OpenCloud all do one thing in common: they
sync a folder. So instead of four API integrations with four sets of
credentials, the library is written as a single JSON file into a folder of
your choosing and whichever service you already run carries it.

The same format is read and written by the Android build, so the two ends
actually meet.
"""

from __future__ import annotations

import json
import logging
import os
import socket
import tempfile
import time
from pathlib import Path

log = logging.getLogger(__name__)

FILENAME = "muzika-library.json"
FORMAT = "muzika-library"
VERSION = 1

CONFIG_DIR = Path.home() / ".config" / "muzika"
CONFIG_FILE = CONFIG_DIR / "config.json"


# --------------------------------------------------------------------- config

def load_config() -> dict:
    try:
        return json.loads(CONFIG_FILE.read_text())
    except (OSError, ValueError):
        return {}


def save_config(config: dict) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_FILE.write_text(json.dumps(config, indent=2))


BACKEND_FOLDER = "folder"
BACKEND_DROPBOX = "dropbox"

#: Music copied here travels to the phone with everything else in the folder.
MUSIC_SUBDIR = "Music"


def get(key: str, default=None):
    return load_config().get(key, default)


def put(key: str, value) -> None:
    config = load_config()
    if value is None:
        config.pop(key, None)
    else:
        config[key] = value
    save_config(config)


def backend() -> str:
    return load_config().get("sync_backend", BACKEND_FOLDER)


def music_folders() -> list[str]:
    folders = load_config().get("music_folders", [])
    return [f for f in folders if isinstance(f, str)]


def add_music_folder(path: str | os.PathLike) -> bool:
    folders = music_folders()
    target = str(Path(path).expanduser())
    if target in folders:
        return False
    folders.append(target)
    put("music_folders", folders)
    return True


def remove_music_folder(path: str) -> None:
    put("music_folders", [f for f in music_folders() if f != path])


def shared_music_dir() -> Path | None:
    """Where music has to sit for the phone to get it."""
    folder = sync_folder()
    return folder / MUSIC_SUBDIR if folder else None


def sync_folder() -> Path | None:
    folder = load_config().get("sync_folder")
    return Path(folder) if folder else None


def set_sync_folder(folder: str | os.PathLike | None) -> None:
    config = load_config()
    if folder is None:
        config.pop("sync_folder", None)
    else:
        config["sync_folder"] = str(folder)
    save_config(config)


# ------------------------------------------------------------------ transfer

def _track_payload(track: dict) -> dict:
    payload = {
        "id": track["id"],
        "title": track.get("title") or "",
        "artist": track.get("subtitle") or "",
        "duration": int(track.get("duration") or 0),
        "thumb": track.get("thumb"),
    }
    # Non-YouTube tracks need their source and page URL to be playable
    # anywhere else. Additive, so older readers simply ignore them.
    if track.get("source") and track["source"] != "youtube":
        payload["source"] = track["source"]
        payload["url"] = track.get("url")
    return payload


def _track_from_payload(entry: dict) -> dict:
    track = {
        "kind": "song",
        "id": entry["id"],
        "title": entry.get("title") or "",
        "subtitle": entry.get("artist") or "",
        "duration": int(entry.get("duration") or 0),
        "thumb": entry.get("thumb"),
    }
    if entry.get("source"):
        track["source"] = entry["source"]
        track["url"] = entry.get("url")
    return track


def build_payload(store) -> dict:
    playlists = []
    for entry in store.playlists():
        data = store.playlist(entry["playlist_id"])
        if data is None:
            continue
        playlists.append({
            "name": data["title"],
            "updated_at": entry.get("updated_at") or time.time(),
            "tracks": [_track_payload(t) for t in data["tracks"]],
        })
    return {
        "format": FORMAT,
        "version": VERSION,
        "updated_at": time.time(),
        "device": socket.gethostname(),
        "playlists": playlists,
        "favourites": [_track_payload(t) for t in store.favourites()],
        # Saved albums, artists and playlists. Additive: a reader that does not
        # know the key simply carries on with playlists and favourites.
        "library": [
            {
                "kind": item["kind"],
                "id": item["id"],
                "title": item["title"],
                "subtitle": item.get("subtitle") or "",
                "thumb": item.get("thumb"),
            }
            for item in store.library()
        ],
    }


def export_library(store, folder: str | os.PathLike | None = None) -> Path:
    """Write the library file atomically, so a half-written file never syncs."""
    target_dir = Path(folder) if folder else sync_folder()
    if target_dir is None:
        raise ValueError("No sync folder is configured")
    target_dir.mkdir(parents=True, exist_ok=True)
    payload = build_payload(store)
    destination = target_dir / FILENAME

    handle, temporary = tempfile.mkstemp(dir=target_dir, prefix=".muzika-", suffix=".tmp")
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, indent=1, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
    except BaseException:
        Path(temporary).unlink(missing_ok=True)
        raise
    log.info("exported %d playlists to %s", len(payload["playlists"]), destination)
    return destination


def read_payload(folder: str | os.PathLike | None = None) -> dict | None:
    source_dir = Path(folder) if folder else sync_folder()
    if source_dir is None:
        return None
    path = source_dir / FILENAME
    if not path.exists():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        log.warning("could not read %s: %s", path, exc)
        return None
    if payload.get("format") != FORMAT:
        log.warning("%s is not a Muzika library file", path)
        return None
    return payload


def import_library(store, folder: str | os.PathLike | None = None) -> dict:
    """Merge the library file in `folder` into the local store."""
    payload = read_payload(folder)
    if payload is None:
        return {"ok": False, "reason": "no library file found"}
    return apply_payload(store, payload)


def apply_payload(store, payload: dict) -> dict:
    """Merge an already-loaded payload, wherever it came from.

    Playlists are matched by name. The newer side wins for a given playlist -
    a playlist edited elsewhere replaces the local track list, rather than the
    two being merged into something neither device chose. Favourites and saved
    items are unioned, so neither is ever silently lost.
    """
    existing = {entry["title"].casefold(): entry for entry in store.playlists()}
    added_playlists = updated_playlists = added_tracks = 0

    for remote in payload.get("playlists", []):
        name = (remote.get("name") or "").strip()
        if not name:
            continue
        tracks = [_track_from_payload(t) for t in remote.get("tracks", []) if t.get("id")]
        local = existing.get(name.casefold())

        if local is None:
            playlist_id = store.create_playlist(name)
            added_tracks += store.add_many_to_playlist(playlist_id, tracks)
            added_playlists += 1
            continue

        local_full = store.playlist(local["playlist_id"])
        local_ids = [t["id"] for t in (local_full["tracks"] if local_full else [])]
        remote_ids = [t["id"] for t in tracks]
        if local_ids == remote_ids:
            continue
        if float(remote.get("updated_at") or 0) > float(local.get("updated_at") or 0):
            for track_id in local_ids:
                store.remove_from_playlist(local["playlist_id"], track_id)
            added_tracks += store.add_many_to_playlist(local["playlist_id"], tracks)
            updated_playlists += 1
        else:
            added_tracks += store.add_many_to_playlist(local["playlist_id"], tracks)
            updated_playlists += 1

    favourites_added = 0
    for entry in payload.get("favourites", []):
        if not entry.get("id") or store.is_favourite(entry["id"]):
            continue
        store.add_favourite(_track_from_payload(entry))
        favourites_added += 1

    # Saved items are unioned too - removing one on another device should not
    # silently delete it here.
    library_added = 0
    for entry in payload.get("library", []):
        kind, item_id = entry.get("kind"), entry.get("id")
        if not kind or not item_id or store.in_library(kind, item_id):
            continue
        store.toggle_library({
            "kind": kind,
            "id": item_id,
            "title": entry.get("title") or "",
            "subtitle": entry.get("subtitle") or "",
            "thumb": entry.get("thumb"),
        })
        library_added += 1

    return {
        "ok": True,
        "device": payload.get("device", "unknown"),
        "playlists_added": added_playlists,
        "playlists_updated": updated_playlists,
        "tracks_added": added_tracks,
        "favourites_added": favourites_added,
        "library_added": library_added,
    }


def sync(store, folder: str | os.PathLike | None = None) -> dict:
    """Import whatever is there, then write our merged state back."""
    result = import_library(store, folder)
    export_library(store, folder)
    return result
