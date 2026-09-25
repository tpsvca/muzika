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

import requests
import yt_dlp

log = logging.getLogger(__name__)

YOUTUBE = "youtube"
SOUNDCLOUD = "soundcloud"
BANDCAMP = "bandcamp"

LABELS = {YOUTUBE: "YouTube Music", SOUNDCLOUD: "SoundCloud", BANDCAMP: "Bandcamp"}

_HEADERS = {"User-Agent": "Mozilla/5.0"}
_BANDCAMP_SEARCH = "https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic"


def _flat_opts() -> dict:
    return {"quiet": True, "no_warnings": True, "skip_download": True,
            "extract_flat": True, "noplaylist": True}


def search_soundcloud(query: str, limit: int = 15) -> list[dict]:
    """SoundCloud search, via yt-dlp's own search handler."""
    if not query.strip():
        return []
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
