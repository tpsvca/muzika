"""Ask the local Syncthing to look at the folder now, rather than in its own time.

Syncthing notices a changed file through its filesystem watcher, which waits a
few seconds before acting (`fsWatcherDelayS`, 5s by default). That delay is
sensible for a folder full of documents and needlessly slow for one small file
that has just been deliberately written. Its API takes a "scan this folder
now" request, and using it takes a library change from roughly eight seconds
to reach the other device down to about three.

Everything here is best-effort and silent. The folder backend is meant to work
with anything that syncs a directory - Nextcloud, Dropbox, a network share -
so Syncthing not being installed, not running, or not sharing this folder are
all perfectly normal and must cost nothing but a skipped call.

There is no equivalent on Android: the Syncthing app keeps its configuration
in private storage, so another app cannot read the API key it would need.
"""

from __future__ import annotations

import logging
import os
import threading
import xml.etree.ElementTree as ET
from pathlib import Path

import requests

log = logging.getLogger(__name__)

TIMEOUT = 4
_cache: dict | None = None
_lock = threading.Lock()


def _config_path() -> Path | None:
    """Where this user's Syncthing keeps config.xml, new location first."""
    candidates = []
    state = os.environ.get("XDG_STATE_HOME")
    if state:
        candidates.append(Path(state) / "syncthing" / "config.xml")
    config = os.environ.get("XDG_CONFIG_HOME")
    if config:
        candidates.append(Path(config) / "syncthing" / "config.xml")
    candidates += [
        Path.home() / ".local" / "state" / "syncthing" / "config.xml",
        Path.home() / ".config" / "syncthing" / "config.xml",
    ]
    return next((p for p in candidates if p.is_file()), None)


def _read_config() -> dict | None:
    """The API address, key and the folders it knows, or None if absent."""
    path = _config_path()
    if path is None:
        return None
    try:
        root = ET.parse(path).getroot()
    except Exception as exc:  # noqa: BLE001 - a broken config is not our problem
        log.debug("could not read syncthing config: %s", exc)
        return None

    gui = root.find("gui")
    if gui is None:
        return None
    address = (gui.findtext("address") or "127.0.0.1:8384").strip()
    key = (gui.findtext("apikey") or "").strip()
    if not key:
        return None
    scheme = "https" if (gui.get("tls") or "").lower() == "true" else "http"

    folders = {}
    for folder in root.findall("folder"):
        folder_path, folder_id = folder.get("path"), folder.get("id")
        if folder_path and folder_id:
            folders[os.path.realpath(os.path.expanduser(folder_path))] = folder_id
    return {"base": f"{scheme}://{address}", "key": key, "folders": folders}


def _config() -> dict | None:
    global _cache
    with _lock:
        if _cache is None:
            _cache = _read_config() or {}
        return _cache or None


def forget() -> None:
    """Drop the cached config, for when the sync folder setting changes."""
    global _cache
    with _lock:
        _cache = None


def folder_id(path: str | os.PathLike) -> str | None:
    """Which Syncthing folder covers this path, if any."""
    config = _config()
    if not config:
        return None
    target = Path(os.path.realpath(os.path.expanduser(str(path))))
    for known, ident in config["folders"].items():
        known_path = Path(known)
        if target == known_path or known_path in target.parents:
            return ident
    return None


def rescan(path: str | os.PathLike) -> bool:
    """Ask Syncthing to scan the folder holding `path`. Never raises."""
    config = _config()
    ident = folder_id(path)
    if not config or not ident:
        return False
    try:
        response = requests.post(
            f"{config['base']}/rest/db/scan",
            params={"folder": ident},
            headers={"X-API-Key": config["key"]},
            timeout=TIMEOUT,
        )
        if response.status_code == 200:
            log.debug("asked syncthing to scan %s", ident)
            return True
        log.debug("syncthing scan returned %s", response.status_code)
    except Exception as exc:  # noqa: BLE001 - absent or asleep is normal
        log.debug("syncthing not reachable: %s", exc)
    return False


def rescan_async(path: str | os.PathLike) -> None:
    """Same, off the UI thread, for calling straight after a write."""
    threading.Thread(target=rescan, args=(path,), daemon=True).start()
