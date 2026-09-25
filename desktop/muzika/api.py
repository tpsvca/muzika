"""YouTube Music data access.

Everything here blocks; call it from a worker thread (see tasks.run_async).
Results are normalised into the small dicts the UI consumes, so no page code
ever touches a raw ytmusicapi payload.
"""

from __future__ import annotations

import logging
import re
import shutil
import threading
from pathlib import Path
from typing import Any

import base64
import re

import requests
import yt_dlp
from ytmusicapi import YTMusic

log = logging.getLogger(__name__)

# Kinds used throughout the UI.
SONG = "song"
VIDEO = "video"
ALBUM = "album"
ARTIST = "artist"
PLAYLIST = "playlist"

_YTDLP_CACHE = Path.home() / ".cache" / "yt-dlp"

# LRCLIB is a free, key-less community lyrics database that also serves synced
# (LRC) lyrics, which YouTube Music often lacks even when it has plain text.
LRCLIB = "https://lrclib.net/api"
LRCLIB_HEADERS = {"User-Agent": "Muzika/1.0 (https://github.com/tpsvca/muzika)"}

# Junk YouTube titles carry that no lyrics database will match on.
_TITLE_NOISE = re.compile(
    r"""\s*[\(\[][^\)\]]*\b(?:official|video|audio|lyric|lyrics|visuali[sz]er|
        mv|hd|hq|4k|remaster(?:ed)?|explicit|clean|single\s+version|
        radio\s+edit|full\s+album|live)\b[^\)\]]*[\)\]]""",
    re.IGNORECASE | re.VERBOSE)
# Credit lines that LRC files stamp at 00:00 - not part of the song.
_CREDIT_LINE = re.compile(
    r"^\s*(?:\u4f5c\u8a5e|\u4f5c\u66f2|\u7f16\u66f2|\u5236\u4f5c|\u5f55\u97f3|\u6df7\u97f3|"
    r"by|lyrics?\s*by|composed?\s*by|arranged?\s*by|produced?\s*by)\s*[:\uff1a]",
    re.IGNORECASE)
_FEAT = re.compile(r"\s*[\(\[]?\b(?:feat|ft|featuring)\.?\s[^\)\]]*[\)\]]?",
                   re.IGNORECASE)


def _parse_lrc(text: str) -> list[tuple[int, str]]:
    """Turn LRC markup into (milliseconds, line) pairs."""
    lines: list[tuple[int, str]] = []
    for raw in (text or "").splitlines():
        stamps = re.findall(r"\[(\d+):(\d+)(?:[.:](\d+))?\]", raw)
        if not stamps:
            continue
        body = re.sub(r"\[[^\]]*\]", "", raw).strip()
        for minutes, seconds, fraction in stamps:
            hundredths = int((fraction or "0").ljust(2, "0")[:2])
            lines.append((int(minutes) * 60000 + int(seconds) * 1000 + hundredths * 10, body))
    lines.sort(key=lambda item: item[0])
    return lines


def _title_variants(title: str, artist: str) -> list[tuple[str, str]]:
    """Progressively looser (title, artist) guesses for lyric lookups."""
    title = (title or "").strip()
    artist = (artist or "").split(",")[0].strip()

    cleaned = _TITLE_NOISE.sub("", title).strip(" -\u2013\u2014")
    # "Artist - Song" is how a lot of uploads are named.
    split_artist, split_title = "", ""
    for dash in (" - ", " \u2013 ", " \u2014 "):
        if dash in cleaned:
            left, right = cleaned.split(dash, 1)
            split_artist, split_title = left.strip(), right.strip()
            break

    # Keyword stripping only knows English; fall back to dropping every
    # bracketed segment, which handles things like "[Oficialus Klipas]".
    bare = re.sub(r"[\(\[][^\)\]]*[\)\]]", "", title).strip(" -\u2013\u2014")
    bare_artist, bare_title = "", ""
    for dash in (" - ", " \u2013 ", " \u2014 "):
        if dash in bare:
            left, right = bare.split(dash, 1)
            bare_artist, bare_title = left.strip(), right.strip()
            break

    candidates: list[tuple[str, str]] = []

    def add(t: str, a: str) -> None:
        t, a = t.strip(" -\u2013\u2014"), a.strip()
        if t and (t, a) not in candidates:
            candidates.append((t, a))

    add(cleaned, artist)
    if split_title:
        add(split_title, artist or split_artist)
        add(split_title, split_artist)
    add(bare, artist)
    if bare_title:
        add(bare_title, artist or bare_artist)
        add(bare_title, bare_artist)
    add(_FEAT.sub("", cleaned), artist)
    if split_title:
        add(_FEAT.sub("", split_title), artist or split_artist)
    if bare_title:
        add(_FEAT.sub("", bare_title), artist or bare_artist)
        add(_FEAT.sub("", bare_title), "")
    add(cleaned, "")
    return candidates


def _thumb(item: dict, want: int = 400) -> str | None:
    """Pick the smallest thumbnail at least `want` px wide, else the largest."""
    thumbs = item.get("thumbnails") or []
    if not thumbs:
        return None
    big_enough = [t for t in thumbs if t.get("width", 0) >= want]
    chosen = min(big_enough, key=lambda t: t["width"]) if big_enough else thumbs[-1]
    return chosen.get("url")


def _artists(item: dict) -> str:
    """Human-readable byline.

    `author` comes back as a dict, a list of dicts or a plain string depending
    on the endpoint, so every shape has to be handled - stringifying the dict
    put raw Python into the UI.
    """
    names = [a["name"] for a in (item.get("artists") or [])
             if isinstance(a, dict) and a.get("name")]
    if names:
        return ", ".join(names)
    for key in ("author", "artist", "channelName"):
        value = item.get(key)
        if isinstance(value, dict):
            if value.get("name"):
                return str(value["name"])
        elif isinstance(value, list):
            picked = [v["name"] for v in value if isinstance(v, dict) and v.get("name")]
            if picked:
                return ", ".join(picked)
        elif isinstance(value, str) and value:
            return value
    return ""


def _duration_seconds(item: dict) -> int:
    secs = item.get("duration_seconds")
    if isinstance(secs, int):
        return secs
    text = item.get("duration") or item.get("length") or ""
    parts = [p for p in str(text).split(":") if p.isdigit()]
    total = 0
    for part in parts:
        total = total * 60 + int(part)
    return total


def normalise(item: dict) -> dict | None:
    """Turn any ytmusicapi result into the shape the UI renders."""
    if not isinstance(item, dict):
        return None
    kind = (item.get("resultType") or item.get("type") or "").lower()

    if item.get("videoId") and kind in ("", "song", "video"):
        kind = kind or SONG
        subtitle = _artists(item)
        album = item.get("album")
        album_name = album.get("name") if isinstance(album, dict) else None
        if not subtitle and album_name:
            subtitle = album_name
        return {
            "kind": VIDEO if kind == "video" else SONG,
            "id": item["videoId"],
            "title": item.get("title") or "",
            "subtitle": subtitle,
            "thumb": _thumb(item),
            "duration": _duration_seconds(item),
                "album": album_name,
            "artists": _artists(item),
        }

    if kind == "album" or (item.get("browseId", "").startswith("MPRE")):
        return {
            "kind": ALBUM,
            "id": item.get("browseId"),
            "title": item.get("title") or "",
            "subtitle": _artists(item) or item.get("year") or "",
            "thumb": _thumb(item),
        }

    if kind == "artist" or item.get("channelId"):
        return {
            "kind": ARTIST,
            "id": item.get("browseId") or item.get("channelId"),
            "title": item.get("artist") or item.get("title") or "",
            "subtitle": item.get("subscribers") and f"{item['subscribers']} subscribers" or "",
            "thumb": _thumb(item),
        }

    if kind in ("playlist", "watch_playlist") or item.get("playlistId"):
        pid = item.get("browseId") or item.get("playlistId") or ""
        return {
            "kind": PLAYLIST,
            "id": pid,
            "title": item.get("title") or "",
            "subtitle": item.get("description") or _artists(item) or (
                f"{item['itemCount']} songs" if item.get("itemCount") else ""),
            "thumb": _thumb(item),
        }
    return None


class Api:
    """Thin, thread-safe facade over ytmusicapi and yt-dlp."""

    def __init__(self) -> None:
        self._ytm: YTMusic | None = None
        self._lock = threading.Lock()

    @property
    def ytm(self) -> YTMusic:
        with self._lock:
            if self._ytm is None:
                self._ytm = YTMusic()
            return self._ytm

    # ---------------------------------------------------------------- browse

    def home(self, limit: int = 8) -> list[dict]:
        shelves = []
        for shelf in self.ytm.get_home(limit=limit):
            items = [n for n in (normalise(i) for i in shelf.get("contents", [])) if n]
            if items:
                shelves.append({"title": shelf.get("title") or "", "items": items})
        return shelves

    def charts(self, country: str = "ZZ") -> list[dict]:
        data = self.ytm.get_charts(country)
        shelves = []
        for key, label in (("songs", "Top songs"), ("videos", "Top videos"), ("artists", "Top artists")):
            section = data.get(key)
            entries = section.get("items") if isinstance(section, dict) else section
            items = [n for n in (normalise(i) for i in (entries or [])) if n]
            if items:
                shelves.append({"title": label, "items": items})
        return shelves

    def mood_categories(self) -> list[dict]:
        out = []
        for section, entries in (self.ytm.get_mood_categories() or {}).items():
            for entry in entries:
                out.append({"title": entry.get("title", ""), "params": entry.get("params", ""),
                            "section": section})
        return out

    def mood_playlists(self, params: str) -> list[dict]:
        """Items in a mood / genre category.

        Not ytmusicapi's get_mood_playlists(): these pages mix playlists with
        music videos, and its parser looks for a browseId on the title run,
        which videos do not have - one video raises and the whole page is lost.
        Parsing the response here keeps the videos as playable entries.
        """
        response = self.ytm._send_request(
            "browse", {"browseId": "FEmusic_moods_and_genres_category", "params": params})
        items: list[dict] = []
        seen: set[str] = set()
        for section in self._browse_sections(response):
            for renderer in self._shelf_items(section):
                parsed = self._parse_two_row_item(renderer)
                if parsed and parsed["id"] not in seen:
                    seen.add(parsed["id"])
                    items.append(parsed)
        return items

    @staticmethod
    def _browse_sections(response: dict) -> list[dict]:
        try:
            tabs = response["contents"]["singleColumnBrowseResultsRenderer"]["tabs"]
            return tabs[0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"]
        except (KeyError, IndexError, TypeError):
            return []

    @staticmethod
    def _shelf_items(section: dict) -> list[dict]:
        out = []
        for key in ("gridRenderer", "musicCarouselShelfRenderer",
                    "musicImmersiveCarouselShelfRenderer"):
            shelf = section.get(key)
            if not isinstance(shelf, dict):
                continue
            for entry in (shelf.get("items") or shelf.get("contents") or []):
                renderer = entry.get("musicTwoRowItemRenderer")
                if isinstance(renderer, dict):
                    out.append(renderer)
        return out

    @staticmethod
    def _parse_two_row_item(renderer: dict) -> dict | None:
        runs = (renderer.get("title") or {}).get("runs") or []
        title = runs[0].get("text") if runs else None
        if not title:
            return None
        subtitle = " ".join(
            r.get("text", "") for r in ((renderer.get("subtitle") or {}).get("runs") or []))

        thumbs = []
        try:
            thumbs = (renderer["thumbnailRenderer"]["musicThumbnailRenderer"]
                      ["thumbnail"]["thumbnails"])
        except (KeyError, TypeError):
            pass
        thumb = _thumb({"thumbnails": thumbs})

        endpoint = renderer.get("navigationEndpoint") or {}
        browse = endpoint.get("browseEndpoint") or {}
        browse_id = browse.get("browseId")
        if browse_id:
            page_type = ""
            try:
                page_type = (browse["browseEndpointContextSupportedConfigs"]
                             ["browseEndpointContextMusicConfig"]["pageType"])
            except (KeyError, TypeError):
                pass
            if "ARTIST" in page_type or browse_id.startswith("UC"):
                kind = ARTIST
            elif "ALBUM" in page_type or browse_id.startswith("MPRE"):
                kind = ALBUM
            else:
                kind = PLAYLIST
            return {"kind": kind, "id": browse_id, "title": title,
                    "subtitle": subtitle.strip(" \u2022"), "thumb": thumb}

        video_id = (endpoint.get("watchEndpoint") or {}).get("videoId")
        if video_id:
            music_type = ""
            try:
                music_type = (endpoint["watchEndpoint"]["watchEndpointMusicSupportedConfigs"]
                              ["watchEndpointMusicConfig"]["musicVideoType"])
            except (KeyError, TypeError):
                pass
            kind = VIDEO if "OMV" in music_type or "UGC" in music_type else SONG
            return {"kind": kind, "id": video_id, "title": title,
                    "subtitle": subtitle.strip(" \u2022"), "thumb": thumb, "duration": 0}
        return None

    def search(self, query: str, filter_: str | None = None, limit: int = 30) -> list[dict]:
        results = self.ytm.search(query, filter=filter_, limit=limit)
        items = [n for n in (normalise(r) for r in results) if n]
        if filter_ is not None:
            return items
        # The unfiltered endpoint returns songs with no artists and no album -
        # that data only comes back when the songs filter is applied, so take
        # the song rows from there and keep everything else from the mixed page.
        try:
            songs = self.ytm.search(query, filter="songs", limit=limit)
        except Exception:
            return items
        enriched = [n for n in (normalise(r) for r in songs) if n]
        if not enriched:
            return items
        others = [i for i in items if i["kind"] not in (SONG, VIDEO)]
        return enriched + others

    def search_sources(self, query: str, sources: list[str], limit: int = 15) -> dict[str, list[dict]]:
        """Search the non-YouTube sources. One dead source must not sink search."""
        from . import sources as source_mod
        found = {}
        for name in sources:
            results = source_mod.search(name, query, limit)
            if results:
                found[name] = results
        return found

    def search_suggestions(self, query: str) -> list[str]:
        try:
            return [s for s in self.ytm.get_search_suggestions(query) if isinstance(s, str)]
        except Exception:
            return []

    # ---------------------------------------------------------------- detail

    def playlist(self, playlist_id: str, limit: int | None = 200) -> dict:
        pid = playlist_id[2:] if playlist_id.startswith("VL") else playlist_id
        data = self.ytm.get_playlist(pid, limit=limit)
        tracks = [n for n in (normalise(t) for t in data.get("tracks", [])) if n]
        return {
            "kind": PLAYLIST,
            "id": playlist_id,
            "title": data.get("title") or "",
            "subtitle": _artists(data),
            "description": data.get("description") or "",
            "thumb": _thumb(data, 600),
            "count": data.get("trackCount") or len(tracks),
            "tracks": tracks,
        }

    def album(self, browse_id: str) -> dict:
        data = self.ytm.get_album(browse_id)
        tracks = []
        for track in data.get("tracks", []):
            norm = normalise(track)
            if norm:
                # Album tracks carry no thumbnail of their own.
                norm["thumb"] = norm["thumb"] or _thumb(data, 600)
                norm["album"] = data.get("title")
                tracks.append(norm)
        return {
            "kind": ALBUM,
            "id": browse_id,
            "title": data.get("title") or "",
            "subtitle": _artists(data),
            "description": data.get("description") or "",
            "thumb": _thumb(data, 600),
            "count": len(tracks),
            "tracks": tracks,
            "year": data.get("year"),
        }

    def artist(self, channel_id: str) -> dict:
        data = self.ytm.get_artist(channel_id)
        songs = (data.get("songs") or {}).get("results") or []
        tracks = [n for n in (normalise(s) for s in songs) if n]
        shelves = []
        for key, label in (("albums", "Albums"), ("singles", "Singles"), ("videos", "Videos")):
            entries = (data.get(key) or {}).get("results") or []
            items = [n for n in (normalise(e) for e in entries) if n]
            if items:
                shelves.append({"title": label, "items": items})
        return {
            "kind": ARTIST,
            "id": channel_id,
            "title": data.get("name") or "",
            "subtitle": data.get("subscribers") and f"{data['subscribers']} subscribers" or "",
            "description": data.get("description") or "",
            "thumb": _thumb(data, 600),
            "tracks": tracks,
            "shelves": shelves,
            "shuffle_id": (data.get("shuffleId") or None),
            "radio_id": (data.get("radioId") or None),
        }

    def radio(self, video_id: str | None = None, playlist_id: str | None = None,
              shuffle: bool = False, limit: int = 50) -> list[dict]:
        """Queue continuation - what YouTube Music would play next."""
        data = self.ytm.get_watch_playlist(videoId=video_id, playlistId=playlist_id,
                                           limit=limit, shuffle=shuffle)
        return [n for n in (normalise(t) for t in data.get("tracks", [])) if n]

    def lyrics(self, video_id: str, track: dict | None = None) -> dict | None:
        """Lyrics for a track, from whichever source has them.

        Synced lyrics from *any* provider beat plain text from a closer one -
        a wall of untimed text cannot follow the song, so it is the last resort
        rather than the first answer.

        Returns {"text", "source", "lines": [(ms, text)] | None, "provider"}.
        """
        plain: list[dict] = []

        youtube = self._youtube_lyrics(video_id)
        if youtube and youtube.get("lines"):
            return youtube
        if youtube and youtube.get("text"):
            plain.append(youtube)

        if track:
            for fetch in (self._lrclib_lyrics, self._netease_lyrics, self._kugou_lyrics):
                try:
                    found = fetch(track)
                except Exception as exc:  # noqa: BLE001 - one bad provider must not stop the rest
                    log.debug("%s failed: %s", fetch.__name__, exc)
                    continue
                if found and found.get("lines"):
                    return found
                if found and found.get("text"):
                    plain.append(found)

        return plain[0] if plain else None

    # ---------------------------------------------------- more lyric sources

    @staticmethod
    def _lyric_result(lrc: str, provider: str) -> dict | None:
        lines = _parse_lrc(lrc)
        if not lines:
            return None
        # LRC files often carry credit lines at 00:00; they are not lyrics.
        cleaned = [(ms, text) for ms, text in lines
                   if text and not _CREDIT_LINE.match(text)]
        if not cleaned:
            return None
        return {"text": "\n".join(t for _, t in cleaned), "source": provider,
                "lines": cleaned, "provider": provider}

    def _netease_lyrics(self, track: dict) -> dict | None:
        headers = {"User-Agent": "Mozilla/5.0", "Referer": "https://music.163.com/"}
        duration = int(track.get("duration") or 0)
        for title, artist in _title_variants(
                track.get("title") or "", track.get("artists") or track.get("subtitle") or ""):
            query = f"{title} {artist}".strip()
            response = requests.get("https://music.163.com/api/search/get",
                                    params={"s": query, "type": 1, "limit": 5},
                                    headers=headers, timeout=12)
            songs = ((response.json().get("result") or {}).get("songs")) or []
            for song in songs:
                if duration and abs(int(song.get("duration", 0)) / 1000 - duration) > 20:
                    continue
                lyric = requests.get("https://music.163.com/api/song/lyric",
                                     params={"id": song["id"], "lv": 1, "kv": 1, "tv": -1},
                                     headers=headers, timeout=12)
                result = self._lyric_result(
                    (lyric.json().get("lrc") or {}).get("lyric") or "", "NetEase")
                if result:
                    return result
        return None

    def _kugou_lyrics(self, track: dict) -> dict | None:
        headers = {"User-Agent": "Mozilla/5.0"}
        duration = int(track.get("duration") or 0)
        for title, artist in _title_variants(
                track.get("title") or "", track.get("artists") or track.get("subtitle") or ""):
            query = f"{title} {artist}".strip()
            search = requests.get("http://mobilecdn.kugou.com/api/v3/search/song",
                                  params={"format": "json", "keyword": query,
                                          "page": 1, "pagesize": 5},
                                  headers=headers, timeout=12)
            songs = ((search.json().get("data") or {}).get("info")) or []
            for song in songs:
                if duration and abs(int(song.get("duration", 0)) - duration) > 20:
                    continue
                candidates = requests.get(
                    "http://krcs.kugou.com/search",
                    params={"ver": 1, "man": "yes", "client": "mobi", "hash": song["hash"]},
                    headers=headers, timeout=12).json().get("candidates") or []
                for candidate in candidates[:3]:
                    payload = requests.get(
                        "http://lyrics.kugou.com/download",
                        params={"ver": 1, "client": "pc", "id": candidate["id"],
                                "accesskey": candidate["accesskey"],
                                "fmt": "lrc", "charset": "utf8"},
                        headers=headers, timeout=12).json().get("content", "")
                    if not payload:
                        continue
                    lrc = base64.b64decode(payload).decode("utf-8", "ignore")
                    result = self._lyric_result(lrc, "KuGou")
                    if result:
                        return result
        return None

    def _youtube_lyrics(self, video_id: str) -> dict | None:
        try:
            watch = self.ytm.get_watch_playlist(videoId=video_id, limit=1)
            browse_id = watch.get("lyrics")
            if not browse_id:
                return None
            try:
                data = self.ytm.get_lyrics(browse_id, timestamps=True)
            except Exception:
                data = self.ytm.get_lyrics(browse_id)
        except Exception as exc:  # noqa: BLE001
            log.debug("youtube lyrics failed: %s", exc)
            return None
        if not data:
            return None
        raw = data.get("lyrics")
        if isinstance(raw, list):  # timed lyrics
            lines = [(int(getattr(line, "start_time", 0) or 0), str(getattr(line, "text", "")))
                     for line in raw]
            return {"text": "\n".join(t for _, t in lines),
                    "source": data.get("source") or "YouTube Music",
                    "lines": lines, "provider": "YouTube Music"}
        return {"text": raw or "", "source": data.get("source") or "YouTube Music",
                "lines": None, "provider": "YouTube Music"}

    def _lrclib_lyrics(self, track: dict) -> dict | None:
        title = track.get("title") or ""
        artist = track.get("artists") or track.get("subtitle") or ""
        album = track.get("album") or ""
        duration = int(track.get("duration") or 0)

        for candidate_title, candidate_artist in _title_variants(title, artist):
            if not candidate_artist:
                hit = self._lrclib_search(candidate_title, "", duration)
            else:
                hit = (self._lrclib_get(candidate_title, candidate_artist, album, duration)
                       or self._lrclib_search(candidate_title, candidate_artist, duration))
            if hit:
                return hit
        return None

    def _lrclib_request(self, path: str, params: dict):
        try:
            response = requests.get(f"{LRCLIB}/{path}", params=params,
                                    headers=LRCLIB_HEADERS, timeout=15)
        except Exception as exc:  # noqa: BLE001
            log.debug("lrclib %s failed: %s", path, exc)
            return None
        if response.status_code != 200:
            return None
        try:
            return response.json()
        except ValueError:
            return None

    @staticmethod
    def _lrclib_format(entry: dict) -> dict | None:
        if not isinstance(entry, dict) or entry.get("instrumental"):
            return None
        synced = entry.get("syncedLyrics") or ""
        plain = entry.get("plainLyrics") or ""
        lines = _parse_lrc(synced) if synced else None
        text = "\n".join(t for _, t in lines) if lines else plain
        if not text.strip():
            return None
        return {"text": text, "source": "LRCLIB", "lines": lines or None,
                "provider": "LRCLIB"}

    def _lrclib_get(self, title: str, artist: str, album: str, duration: int) -> dict | None:
        params = {"track_name": title, "artist_name": artist}
        if album:
            params["album_name"] = album
        if duration:
            # LRCLIB only matches within a couple of seconds, so a bad duration
            # is worse than none - retry without it.
            entry = self._lrclib_request("get", {**params, "duration": duration})
            formatted = self._lrclib_format(entry) if entry else None
            if formatted:
                return formatted
        entry = self._lrclib_request("get", params)
        return self._lrclib_format(entry) if entry else None

    def _lrclib_search(self, title: str, artist: str, duration: int) -> dict | None:
        params = {"track_name": title}
        if artist:
            params["artist_name"] = artist
        results = self._lrclib_request("search", params)
        if not isinstance(results, list) or not results:
            return None
        if duration:
            # Fuzzy search will happily match a 4-minute song to an hour-long
            # mix, so require the runtime to be in the same ballpark.
            results = [e for e in results
                       if abs(int(e.get("duration") or 0) - duration) <= 20]
            if not results:
                return None
            results = sorted(
                results, key=lambda e: abs(int(e.get("duration") or 0) - duration))
        # Prefer an entry that actually carries synced lyrics.
        for entry in sorted(results, key=lambda e: not e.get("syncedLyrics"))[:6]:
            formatted = self._lrclib_format(entry)
            if formatted:
                return formatted
        return None

    # ---------------------------------------------------------------- stream

    def stream(self, track: dict | str, retry_on_403: bool = True) -> tuple[str, dict]:
        """Resolve a playable audio URL plus the headers it must be fetched with.

        Takes a track dict (any source) or a bare YouTube video id. Non-YouTube
        sources carry the page URL in `url`; yt-dlp extracts all of them.

        A stale yt-dlp player cache makes YouTube hand back URLs that 403 on
        first byte, so on failure we wipe the cache once and retry - that is a
        real failure mode, not a hypothetical one.
        """
        if isinstance(track, str):
            target = f"https://music.youtube.com/watch?v={track}"
        else:
            # A file on disk needs no extractor at all.
            if track.get("source") == "local":
                from . import local as local_mod
                from . import sync as sync_mod
                path = local_mod.resolve(track, sync_mod.music_folders(),
                                         sync_mod.shared_music_dir())
                if path is None:
                    raise RuntimeError(f"Missing file: {track.get('url')}")
                import gi
                gi.require_version("Gst", "1.0")
                from gi.repository import Gst
                return Gst.filename_to_uri(path), {}
            target = track.get("url") or f"https://music.youtube.com/watch?v={track['id']}"
        opts = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "noplaylist": True,
            # Opus first: decoding it needs no gstreamer-libav.
            "format": "bestaudio[acodec=opus]/bestaudio/best",
        }
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(target, download=False)
        except Exception as exc:
            if retry_on_403 and _YTDLP_CACHE.exists():
                log.warning("stream extraction failed (%s); clearing yt-dlp cache", exc)
                shutil.rmtree(_YTDLP_CACHE, ignore_errors=True)
                return self.stream(track, retry_on_403=False)
            raise
        return info["url"], (info.get("http_headers") or {})

    @staticmethod
    def clear_stream_cache() -> None:
        shutil.rmtree(_YTDLP_CACHE, ignore_errors=True)


def watch_url(item: dict) -> str:
    if item.get("kind") in (SONG, VIDEO):
        return f"https://music.youtube.com/watch?v={item['id']}"
    if item.get("kind") == PLAYLIST:
        pid = item["id"]
        return f"https://music.youtube.com/playlist?list={pid[2:] if pid.startswith('VL') else pid}"
    return f"https://music.youtube.com/browse/{item.get('id', '')}"
