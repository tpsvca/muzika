"""Song radio: what plays after a song you looked up and heard out.

Searching for one song and listening to all of it says something a tap on a
playlist does not: this is the sound wanted right now. What used to follow was
the next search result - usually the same song again, live or remastered.

The radio that follows instead is built from two things:

- **the song** - YouTube Music's own radio for it, which is where style, mood
  and feeling come from: it is picked to sit next to this one track;
- **the band** - more by the same artist, threaded through so the radio keeps
  returning to who it started from.

Everything here is pure - no network, no player - so the rules can be tested
and kept in step with the Android copy (`sources/SongRadio.kt`).
"""

from __future__ import annotations

import re
import unicodedata

# How much of a song has to have been heard for it to count as "played to the
# end" rather than skipped through.
HEARD_FRACTION = 0.5
# Every Nth place in the radio goes to the seed's own artist...
ARTIST_EVERY = 4
# ...up to this many times, so it stays a radio and not a discography.
ARTIST_MAX = 8
LIMIT = 50

SOURCE_PREFIX = "songradio:"

_BRACKETS = re.compile(r"[\(\[][^\)\]]*[\)\]]")
_SUFFIX = re.compile(
    r"\s+-\s+(live|remaster(ed)?|edit|radio edit|bonus track|mono|stereo|acoustic|"
    r"single version|album version|demo)\b.*$", re.IGNORECASE)
_SEPARATORS = (", ", " & ", " x ", " feat. ", " feat ", " ft. ", " ft ",
               " with ", " and ", " • ", " / ", " × ")


def _plain(text: str) -> str:
    text = unicodedata.normalize("NFKD", text or "")
    text = "".join(c for c in text if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]+", "", text.lower())


def primary_artist(name: str) -> str:
    """The first credited artist - "Ida Mae, Marcus King" is Ida Mae's song."""
    name = (name or "").strip()
    cut = len(name)
    lowered = name.lower()
    for separator in _SEPARATORS:
        at = lowered.find(separator)
        if at > 0:
            cut = min(cut, at)
    return name[:cut].strip().rstrip(",")


def song_key(track: dict) -> tuple[str, str]:
    """Identifies a song regardless of which recording of it this is.

    "Numb", "Numb - Live" and "Numb (Remastered)" are one song as far as a
    radio is concerned: hearing it three times is not variety.
    """
    title = _SUFFIX.sub("", _BRACKETS.sub(" ", track.get("title") or ""))
    artist = primary_artist(track.get("subtitle") or track.get("artists") or "")
    return _plain(title) or _plain(track.get("title") or ""), _plain(artist)


def heard_enough(heard_ms: int, duration_ms: int) -> bool:
    """Whether the song was listened to, not just reached the end of.

    Dragging the seek bar to the last second ends a track too, and that is not
    a reason to build a radio around it.
    """
    if duration_ms <= 0:
        return heard_ms >= 60_000
    return heard_ms >= duration_ms * HEARD_FRACTION


def blend(seed: dict, similar: list[dict], by_artist: list[dict],
          limit: int = LIMIT) -> list[dict]:
    """Thread the artist's songs through the song's radio.

    The seed itself is left out - it has just been heard - and so is every
    other recording of it. Order is kept: `similar` arrives in the order
    YouTube Music would play it, and the caller shuffles `by_artist`.
    """
    seen_ids = {seed.get("id")}
    seen_keys = {song_key(seed)}

    def fresh(tracks: list[dict]) -> list[dict]:
        kept = []
        for track in tracks:
            if not track.get("id") or track["id"] in seen_ids:
                continue
            key = song_key(track)
            if key in seen_keys:
                continue
            seen_ids.add(track["id"])
            seen_keys.add(key)
            kept.append(track)
        return kept

    # The artist's songs claim their ids first, so the same track turning up
    # in the song's radio as well is not played twice.
    artist_pool = fresh(by_artist)[:ARTIST_MAX]
    similar_pool = fresh(similar)

    result: list[dict] = []
    while len(result) < limit and (similar_pool or artist_pool):
        # Places 1, 5, 9... (counting from 0): the first thing heard is the
        # closest match to the song, the second is the band again.
        wants_artist = len(result) % ARTIST_EVERY == 1
        if artist_pool and (wants_artist or not similar_pool):
            result.append(artist_pool.pop(0))
        elif similar_pool:
            result.append(similar_pool.pop(0))
    return result


def is_search_source(source: str | None) -> bool:
    return bool(source) and (source == "search" or source.startswith("search:"))
