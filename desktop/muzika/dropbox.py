"""The library file, and optionally the music, in Dropbox.

Dropbox has no anonymous mode: it needs a token belonging to the account. The
user generates one in their own app console and pastes it into Settings, so
nothing here ever sees a password and no app secret is baked into the program.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

import urllib.error
import urllib.request

from . import sync as sync_mod

log = logging.getLogger(__name__)

LIBRARY_PATH = "/Muzika/muzika-library.json"
MUSIC_ROOT = "/Muzika/Music"


class DropboxError(Exception):
    pass


def token() -> str:
    value = (sync_mod.get("dropbox_token") or "").strip()
    if not value:
        raise DropboxError("No Dropbox token — add one in Settings")
    return value


def _request(url: str, *, data: bytes | None, headers: dict) -> bytes:
    request = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", "replace")[:200]
        if error.code == 401:
            raise DropboxError("Dropbox rejected the token") from error
        if error.code == 409:
            raise FileNotFoundError(body) from error
        raise DropboxError(f"Dropbox refused the request ({error.code}): {body}") from error
    except urllib.error.URLError as error:
        raise DropboxError(f"Could not reach Dropbox: {error.reason}") from error


def account_name() -> str:
    """Used by Settings to prove the token actually works."""
    raw = _request(
        "https://api.dropboxapi.com/2/users/get_current_account",
        data=b"null",
        headers={"Authorization": f"Bearer {token()}",
                 "Content-Type": "application/json"},
    )
    data = json.loads(raw or "{}")
    name = (data.get("name") or {}).get("display_name")
    return name or data.get("email") or "Dropbox"


def download(path: str = LIBRARY_PATH) -> bytes | None:
    """None when the file simply is not there yet, which is not an error."""
    try:
        return _request(
            "https://content.dropboxapi.com/2/files/download",
            data=b"",
            headers={"Authorization": f"Bearer {token()}",
                     "Dropbox-API-Arg": json.dumps({"path": path})},
        )
    except FileNotFoundError:
        return None


def upload(content: bytes, path: str = LIBRARY_PATH) -> None:
    argument = json.dumps({"path": path, "mode": "overwrite", "mute": True})
    _request(
        "https://content.dropboxapi.com/2/files/upload",
        data=content,
        headers={"Authorization": f"Bearer {token()}",
                 "Dropbox-API-Arg": argument,
                 "Content-Type": "application/octet-stream"},
    )


# ------------------------------------------------------------------- library

def export_library(store) -> None:
    payload = sync_mod.build_payload(store)
    upload(json.dumps(payload, indent=1, ensure_ascii=False).encode("utf-8"))


def import_library(store) -> dict:
    raw = download()
    if raw is None:
        return {"ok": False, "reason": "no library file in Dropbox yet"}
    try:
        payload = json.loads(raw)
    except ValueError:
        return {"ok": False, "reason": "the Dropbox library file is not readable"}
    if payload.get("format") != sync_mod.FORMAT:
        return {"ok": False, "reason": "not a Muzika library file"}
    return sync_mod.apply_payload(store, payload)


def sync(store) -> dict:
    result = import_library(store)
    export_library(store)
    return result
