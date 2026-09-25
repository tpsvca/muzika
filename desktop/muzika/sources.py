"""Audio sources beyond YouTube Music.

Every source resolves to the same item shape the rest of the app already
consumes, plus two extra fields:

    source  which provider it came from
    url     the page URL yt-dlp resolves into a stream

Nothing here needs an account or an API key. yt-dlp does the stream
extraction for all of them, so a source is mostly a search implementation.
"""

from __future__ import annotations

import logging
import re
import threading

import requests
import yt_dlp

log = logging.getLogger(__name__)

YOUTUBE = "youtube"
SOUNDCLOUD = "soundcloud"
BANDCAMP = "bandcamp"

LABELS = {YOUTUBE: "YouTube Music", SOUNDCLOUD: "SoundCloud", BANDCAMP: "Bandcamp"}

_HEADERS = {"User-Agent": "Mozilla/5.0"}
_BROWSER_HEADERS = {
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
}
_SOUNDCLOUD_API = "https://api-v2.soundcloud.com/search/tracks"
_soundcloud_client_id: str | None = None
_soundcloud_lock = threading.Lock()
_BANDCAMP_SEARCH = "https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic"


def _flat_opts() -> dict:
    return {"quiet": True, "no_warnings": True, "skip_download": True,
            "extract_flat": True, "noplaylist": True}


def _soundcloud_id() -> str | None:
    """SoundCloud's public web client id, read the way its own site gets it.

    The id is not secret - every visitor's browser uses it - but it is not
    published either, so it is lifted from the script bundles the homepage
    loads, and then cached for the life of the process.
    """
    global _soundcloud_client_id
    with _soundcloud_lock:
        if _soundcloud_client_id:
            return _soundcloud_client_id
        try:
            html = requests.get("https://soundcloud.com/",
                                headers=_BROWSER_HEADERS, timeout=15).text
            scripts = re.findall(
                r'<script[^>]+src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"', html)
            # The later bundles are the ones that carry it.
            for url in reversed(scripts):
                js = requests.get(url, headers=_BROWSER_HEADERS, timeout=15).text
                found = re.search(r'[,{]client_id:"([A-Za-z0-9]{20,})"', js)
                if found:
                    _soundcloud_client_id = found.group(1)
                    return _soundcloud_client_id
        except Exception as exc:  # noqa: BLE001
            log.debug("soundcloud client id lookup failed: %s", exc)
        return None


def _soundcloud_art(entry: dict) -> str | None:
    art = entry.get("artwork_url") or (entry.get("user") or {}).get("avatar_url")
    if not art:
        return None
    # The default is a 100px thumbnail; SoundCloud will serve larger on request.
    return art.replace("-large.", "-t300x300.")


def search_soundcloud(query: str, limit: int = 15) -> list[dict]:
    """SoundCloud search.

    Goes straight to the API the website itself calls. yt-dlp's `scsearch`
    does the same job but spins up a full extractor to do it, which measured
    1.5-2.9s against roughly 300ms here; it stays as a fallback so a change at
    SoundCloud costs speed rather than the source.
    """
    if not query.strip():
        return []

    client_id = _soundcloud_id()
    if client_id:
        try:
            response = requests.get(
                _SOUNDCLOUD_API,
                params={"q": query, "client_id": client_id, "limit": limit},
                headers=_BROWSER_HEADERS, timeout=15)
            response.raise_for_status()
            items = []
            for entry in (response.json().get("collection") or []):
                url = entry.get("permalink_url")
                if not url or entry.get("kind") != "track":
                    continue
                items.append({
                    "kind": "song",
                    "source": SOUNDCLOUD,
                    "id": f"sc:{entry.get('id') or url}",
                    "url": url,
                    "title": entry.get("title") or "",
                    "subtitle": (entry.get("user") or {}).get("username") or "SoundCloud",
                    "thumb": _soundcloud_art(entry),
                    "duration": int((entry.get("duration") or 0) / 1000),
                })
            if items:
                return items
        except Exception as exc:  # noqa: BLE001
            log.debug("soundcloud api search failed, falling back: %s", exc)

    with yt_dlp.YoutubeDL(_flat_opts()) as ydl:
        data = ydl.extract_info(f"scsearch{limit}:{query}", download=False)

    items = []
    for entry in (data.get("entries") or []):
        url = entry.get("url") or entry.get("webpage_url")
        if not url:
            continue
        items.append({
            "kind": "song",
            "source": SOUNDCLOUD,
            "id": f"sc:{entry.get('id') or url}",
            "url": url,
            "title": entry.get("title") or "",
            "subtitle": entry.get("uploader") or entry.get("channel") or "SoundCloud",
            "thumb": entry.get("thumbnail"),
            "duration": int(entry.get("duration") or 0),
        })
    return items


def search_bandcamp(query: str, limit: int = 15) -> list[dict]:
    """Bandcamp search, via the same autocomplete endpoint the site uses."""
    if not query.strip():
        return []
    try:
        response = requests.post(
            _BANDCAMP_SEARCH,
            json={"search_text": query, "search_filter": "t",
                  "full_page": False, "fan_id": None},
            headers=_HEADERS, timeout=15)
        response.raise_for_status()
        results = ((response.json().get("auto") or {}).get("results")) or []
    except Exception as exc:  # noqa: BLE001
        log.debug("bandcamp search failed: %s", exc)
        return []

    items = []
    for entry in results:
        url = entry.get("item_url_path") or entry.get("item_url_root")
        if not url or entry.get("type") != "t":
            continue
        items.append({
            "kind": "song",
            "source": BANDCAMP,
            "id": f"bc:{url}",
            "url": url,
            "title": entry.get("name") or "",
            "subtitle": entry.get("band_name") or "Bandcamp",
            "thumb": entry.get("img") or entry.get("art_url"),
            "duration": 0,  # the search endpoint does not carry it
        })
        if len(items) >= limit:
            break
    return items


def warm_up() -> None:
    """Fetch SoundCloud's client id ahead of the first search."""
    try:
        _soundcloud_id()
    except Exception:  # noqa: BLE001
        pass


SEARCHERS = {SOUNDCLOUD: search_soundcloud, BANDCAMP: search_bandcamp}


def search(source: str, query: str, limit: int = 15) -> list[dict]:
    searcher = SEARCHERS.get(source)
    if searcher is None:
        return []
    try:
        return searcher(query, limit)
    except Exception as exc:  # noqa: BLE001 - one dead source must not break search
        log.warning("%s search failed: %s", source, exc)
        return []
