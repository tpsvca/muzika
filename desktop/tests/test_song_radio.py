"""Song radio: a song found by searching and heard out is followed by a radio
built from it - its sound, and more by its band - not by the next search result.

The blending rules are pure and are mirrored on Android
(`SongRadioTest.kt`); keep the two in step.
"""

from __future__ import annotations

import sys
import time
import wave
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from muzika import radio, session, sync  # noqa: E402


def _t(track_id: str, title: str, artist: str = "Someone") -> dict:
    return {"kind": "song", "id": track_id, "title": title, "subtitle": artist,
            "duration": 200, "thumb": None}


SEED = _t("seed", "Numb", "Gary Clark Jr.")


# ------------------------------------------------------------------ blending

def test_the_song_itself_and_other_recordings_of_it_are_left_out():
    similar = [SEED, _t("live", "Numb - Live", "Gary Clark Jr."),
               _t("rm", "Numb (Remastered 2020)", "Gary Clark Jr."), _t("a", "Thickfreakness", "The Black Keys")]
    assert [t["id"] for t in radio.blend(SEED, similar, [])] == ["a"]


def test_the_band_comes_back_every_fourth_song_starting_second():
    similar = [_t(f"s{n}", f"Similar {n}", f"Band {n}") for n in range(12)]
    by_artist = [_t(f"a{n}", f"Own {n}", "Gary Clark Jr.") for n in range(3)]
    ids = [t["id"] for t in radio.blend(SEED, similar, by_artist)]
    assert ids[:10] == ["s0", "a0", "s1", "s2", "s3", "a1", "s4", "s5", "s6", "a2"]
    assert len(ids) == 15


def test_the_band_never_takes_over_the_radio():
    similar = [_t(f"s{n}", f"Similar {n}", f"Band {n}") for n in range(60)]
    by_artist = [_t(f"a{n}", f"Own {n}", "Gary Clark Jr.") for n in range(40)]
    result = radio.blend(SEED, similar, by_artist)
    assert len(result) == radio.LIMIT
    assert sum(1 for t in result if t["id"].startswith("a")) == radio.ARTIST_MAX


def test_a_song_in_both_lists_plays_once():
    shared = _t("x", "Bright Lights", "Gary Clark Jr.")
    result = radio.blend(SEED, [shared, _t("s", "Other", "Band")], [shared])
    assert [t["id"] for t in result].count("x") == 1


def test_with_no_radio_of_its_own_the_band_still_plays():
    by_artist = [_t(f"a{n}", f"Own {n}", "Gary Clark Jr.") for n in range(3)]
    assert [t["id"] for t in radio.blend(SEED, [], by_artist)] == ["a0", "a1", "a2"]


def test_the_first_credited_artist_is_the_band():
    assert radio.primary_artist("Ida Mae, Marcus King") == "Ida Mae"
    assert radio.primary_artist("Black Joe Lewis & The Honeybears") == "Black Joe Lewis"
    assert radio.primary_artist("AC/DC") == "AC/DC"


def test_heard_means_listened_to_not_dragged_to_the_end():
    assert radio.heard_enough(100_000, 200_000)
    assert not radio.heard_enough(20_000, 200_000)
    assert radio.heard_enough(61_000, 0) and not radio.heard_enough(5_000, 0)


def test_which_queues_count_as_a_search():
    assert radio.is_search_source("search") and radio.is_search_source("search:soundcloud")
    assert not radio.is_search_source(None) and not radio.is_search_source("playlist:3")
    assert not radio.is_search_source(radio.SOURCE_PREFIX + "seed")


# ---------------------------------------------------------------- the player

gi = pytest.importorskip("gi")
try:
    gi.require_version("Gst", "1.0")
    from gi.repository import GLib, Gst
    Gst.init(None)
    HAVE_GST = Gst.ElementFactory.find("playbin3") is not None
except (ValueError, ImportError):  # pragma: no cover - depends on the host
    HAVE_GST = False

needs_gst = pytest.mark.skipif(not HAVE_GST, reason="GStreamer playbin3 unavailable")

RESULTS = [_t("seed", "Numb", "Gary Clark Jr."), _t("r1", "Numb - Live", "Gary Clark Jr."),
           _t("r2", "Numb", "Somebody Else")]
RADIO = [_t("n1", "Thickfreakness", "The Black Keys"), _t("n2", "Bright Lights", "Gary Clark Jr.")]


class _Api:
    def __init__(self, uri: str) -> None:
        self.uri = uri
        self.asked: list[str] = []

    def stream(self, _track, retry_on_403: bool = True):
        return self.uri, {}

    def prefetch(self, _track):
        return None

    def forget_stream(self, _key):
        return None

    def song_radio(self, track, limit: int = 50):
        self.asked.append(track["id"])
        return list(RADIO)

    def radio(self, **_kwargs):
        return []


class _Store:
    def record_play(self, _track):
        return None


def _spin(until, seconds: float = 8.0) -> bool:
    context = GLib.MainContext.default()
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        context.iteration(False)
        if until():
            return True
        time.sleep(0.01)
    return False


@pytest.fixture()
def player(tmp_path, monkeypatch):
    monkeypatch.setattr(sync, "CONFIG_DIR", tmp_path / "config")
    monkeypatch.setattr(sync, "CONFIG_FILE", tmp_path / "config" / "config.json")
    monkeypatch.setattr(session, "session_dir", tmp_path / "local")
    session._cache.update(key=None, text=None)
    clip = tmp_path / "clip.wav"
    with wave.open(str(clip), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(8000)
        out.writeframes(b"\0\0" * 8000 * 30)
    from muzika.player import Player
    made = Player(_Api(clip.as_uri()), _Store())
    notices: list[str] = []
    made.connect("notice", lambda _p, text: notices.append(text))
    made.notices = notices
    yield made
    if made._handoff_source:
        GLib.source_remove(made._handoff_source)
        made._handoff_source = 0
    made._queue, made._order, made._cursor = [], [], -1
    made._bin.set_state(Gst.State.NULL)


def _playing(player) -> bool:
    return _spin(lambda: player._loaded and player._bin.get_state(0)[1] == Gst.State.PLAYING)


def _hear_it_out(player) -> None:
    """Stand in for the minutes of listening the half-second ticks would count."""
    player._heard_ms = 150_000
    player._prepare_song_radio(200 * Gst.SECOND)


@needs_gst
def test_a_searched_song_heard_to_the_end_is_followed_by_its_radio(player):
    player.set_queue(RESULTS, 0, shuffle=False, source="search")
    assert _playing(player)
    _hear_it_out(player)
    assert _spin(lambda: player._song_radio is not None)
    player.next(user=False)                      # what the end of the stream does
    assert [t["id"] for t in player.queue] == ["seed", "n1", "n2"]
    assert player.current["id"] == "n1"
    assert player.source == radio.SOURCE_PREFIX + "seed"
    assert player.notices == ["Radio from “Numb”"]
    assert player._api.asked == ["seed"]


@needs_gst
def test_skipping_it_goes_to_the_next_search_result_as_before(player):
    player.set_queue(RESULTS, 0, shuffle=False, source="search")
    assert _playing(player)
    _hear_it_out(player)
    assert _spin(lambda: player._song_radio is not None)
    player.next(user=True)
    assert player.current["id"] == "r1" and len(player.queue) == 3
    assert player.source == "search"


@needs_gst
def test_dragging_to_the_end_without_listening_builds_no_radio(player):
    player.set_queue(RESULTS, 0, shuffle=False, source="search")
    assert _playing(player)
    player._heard_ms = 4_000
    player._prepare_song_radio(200 * Gst.SECOND)
    _spin(lambda: False, seconds=0.3)
    player.next(user=False)
    assert player.current["id"] == "r1" and player._api.asked == []


@needs_gst
def test_a_playlist_is_never_replaced_by_a_radio(player):
    player.set_queue(RESULTS, 0, shuffle=False, source="playlist:1")
    assert _playing(player)
    _hear_it_out(player)
    _spin(lambda: False, seconds=0.3)
    player.next(user=False)
    assert player.current["id"] == "r1" and player._api.asked == []


@needs_gst
def test_the_radio_does_not_grow_a_radio_of_its_own(player):
    player.set_queue(RESULTS, 0, shuffle=False, source="search")
    assert _playing(player)
    _hear_it_out(player)
    assert _spin(lambda: player._song_radio is not None)
    player.next(user=False)
    assert _playing(player)
    _hear_it_out(player)
    _spin(lambda: False, seconds=0.3)
    player.next(user=False)
    assert player.current["id"] == "n2" and player._api.asked == ["seed"]
