"""Carrying the queue across a restart, and across devices.

Asked for: pause on the desktop, press play on the phone and carry on with the
same song from the same spot in the same shuffled order - and the other way
round - with play taking over the other device's session automatically
whenever it is the newer one. Before this the desktop kept its queue only in
memory, so even closing the window lost it.

The pure cases pin the format and the rules. The player cases run a real
GStreamer pipeline over a generated WAV file, so the resume really is heard
from the saved spot rather than from the start.
"""

from __future__ import annotations

import json
import sys
import time
import wave
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from muzika import session, sync  # noqa: E402


def _track(n: int, duration: int = 180) -> dict:
    return {"kind": "song", "id": f"v{n}", "title": f"Track {n}",
            "subtitle": "Artist", "duration": duration, "thumb": None}


QUEUE = [_track(n) for n in range(5)]
SHUFFLED = [3, 0, 4, 1, 2]


@pytest.fixture()
def isolated(tmp_path, monkeypatch):
    """Own config, own local session folder, own sync folder."""
    monkeypatch.setattr(sync, "CONFIG_DIR", tmp_path / "config")
    monkeypatch.setattr(sync, "CONFIG_FILE", tmp_path / "config" / "config.json")
    monkeypatch.setattr(session, "session_dir", tmp_path / "local")
    session._cache.update(key=None, text=None)
    folder = tmp_path / "Muzika-Sync"
    folder.mkdir()
    sync.set_sync_folder(folder)
    return folder


def _phone_file(updated_at: float, *, playing=False, position_ms=50_000, cursor=1,
                repeat="none", device_id="phone-id") -> str:
    return session.encode_handoff(QUEUE, SHUFFLED, cursor, True, repeat, position_ms,
                                  180_000, playing, device_id, "Pixel 7 Pro", updated_at)


# ------------------------------------------------------------------- format

def test_a_paused_session_comes_across_exactly():
    remote = session.decode_handoff(_phone_file(1000.0), now=1600.0)
    assert remote["device"] == "Pixel 7 Pro"
    assert remote["order"] == SHUFFLED and remote["shuffle"]
    assert remote["cursor"] == 1
    assert remote["queue"][remote["order"][1]]["id"] == "v0"
    assert remote["position_ms"] == 50_000           # paused: not wound forward


def test_a_session_still_playing_is_wound_forward():
    remote = session.decode_handoff(_phone_file(1000.0, playing=True), now=1030.0)
    assert (remote["cursor"], remote["position_ms"]) == (1, 80_000)


def test_winding_forward_crosses_tracks_in_the_shuffled_order():
    # 50 s into a 180 s track, then 7 minutes: 130 s finish it, the next
    # takes 180 s, which leaves 110 s into the one after.
    remote = session.decode_handoff(_phone_file(1000.0, playing=True), now=1000.0 + 420)
    assert (remote["cursor"], remote["position_ms"]) == (3, 110_000)


def test_winding_forward_respects_repeat():
    assert session.advance(QUEUE, SHUFFLED, 4, 190_000, "all") == (0, 10_000)
    assert session.advance(QUEUE, SHUFFLED, 4, 190_000, "one") == (4, 10_000)
    assert session.advance(QUEUE, SHUFFLED, 4, 190_000, "none") == (4, 0)


def test_a_writer_that_died_mid_song_is_not_wound_forward_for_hours():
    now = 1000.0 + session.MAX_EXTRAPOLATE + 1
    remote = session.decode_handoff(_phone_file(1000.0, playing=True), now=now)
    assert (remote["cursor"], remote["position_ms"]) == (1, 50_000)


def test_reads_the_file_exactly_as_the_phone_writes_it():
    """Byte-for-byte the shape Handoff.encode produces on Android: every
    track carries source and url, repeat is a word, time is in seconds."""
    android = json.dumps({
        "format": "muzika-nowplaying", "version": 1,
        "device_id": "0b6c…", "device": "Pixel 7 Pro", "updated_at": 1790000000.5,
        "playing": False, "position_ms": 38207, "duration_ms": 231000,
        "cursor": 1, "track_id": "yt2", "shuffle": True, "repeat": "all",
        "queue": [
            {"id": "yt1", "title": "Forever & Always", "artist": "Taylor Swift",
             "duration": 225, "thumb": None, "source": "youtube", "url": None,
             "album": None, "trackNumber": 0},
            {"id": "yt2", "title": "Mine", "artist": "Taylor Swift", "duration": 231,
             "thumb": "https://i.ytimg.com/x.jpg", "source": "youtube", "url": None,
             "album": None, "trackNumber": 0},
            {"id": "bc1", "title": "Song", "artist": "Band", "duration": 200,
             "thumb": None, "source": "bandcamp", "url": "https://b.bandcamp.com/track/s",
             "album": "LP", "trackNumber": 3},
        ],
        "order": [0, 1, 2],
    })
    remote = session.decode_handoff(android, now=1790000100.0)
    mine = remote["queue"][1]
    assert mine == {"kind": "song", "id": "yt2", "title": "Mine", "subtitle": "Taylor Swift",
                    "duration": 231, "thumb": "https://i.ytimg.com/x.jpg"}
    assert remote["queue"][2]["source"] == "bandcamp"
    assert remote["queue"][2]["url"] == "https://b.bandcamp.com/track/s"
    assert remote["queue"][2]["album"] == "LP"
    assert (remote["cursor"], remote["position_ms"], remote["repeat"]) == (1, 38207, "all")


def test_garbage_and_other_formats_are_ignored():
    assert session.decode_handoff("{nope", 0) is None
    assert session.decode_handoff(json.dumps({"format": "muzika-library"}), 0) is None


# --------------------------------------------------------------------- rules

def test_only_a_newer_session_from_another_device_is_taken_over(isolated):
    now = time.time()
    (isolated / session.HANDOFF_FILE).write_text(_phone_file(now - 60))
    assert session.newer_than(now - 120) is not None
    assert session.newer_than(now) is None

    # This machine's own write is never something to take over.
    (isolated / session.HANDOFF_FILE).write_text(_phone_file(now - 30, device_id=session.device_id()))
    assert session.newer_than(0) is None


def test_nothing_is_shared_without_a_sync_folder(isolated):
    (isolated / session.HANDOFF_FILE).write_text(_phone_file(time.time()))
    sync.put("sync_backend", sync.BACKEND_DROPBOX)
    assert session.newer_than(0) is None


def test_the_local_session_round_trips_and_follows_its_track(isolated):
    session.save_queue(QUEUE, SHUFFLED, True)
    session.save_position(QUEUE[0], 1, 42_000, 180_000, "all")
    saved = session.load_local()
    assert saved["order"] == SHUFFLED and saved["cursor"] == 1
    assert (saved["position_ms"], saved["repeat"]) == (42_000, "all")

    # The position names v2, which sits at index 4 of the shuffled order.
    session.save_position(QUEUE[2], 0, 9_000, 180_000, "none")
    assert session.load_local()["cursor"] == 4

    session.save_position({"id": "gone"}, 3, 9_000, 180_000, "none")
    gone = session.load_local()
    assert (gone["cursor"], gone["position_ms"]) == (0, 0)


def test_an_old_local_session_is_dropped(isolated):
    session.save_queue(QUEUE, SHUFFLED, True)
    assert session.load_local(now=time.time() + session.MAX_AGE + 5) is None


# -------------------------------------------------------------------- player

gi = pytest.importorskip("gi")
gi.require_version("Gst", "1.0")
from gi.repository import GLib, Gst  # noqa: E402


@pytest.fixture(scope="module")
def wav(tmp_path_factory) -> str:
    """Thirty seconds of quiet audio, so the pipeline has something real."""
    path = tmp_path_factory.mktemp("audio") / "quiet.wav"
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(8000)
        out.writeframes(b"\x01\x00" * 8000 * 30)
    return path.as_uri()


class _Api:
    def __init__(self, uri: str) -> None:
        self.uri = uri

    def stream(self, _track):
        return self.uri, {}

    def prefetch(self, _track):
        return None

    def forget_stream(self, _video_id):
        return None

    def clear_stream_cache(self):
        return None


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


_players: list = []


@pytest.fixture(autouse=True)
def _dispose_players():
    """A player outlives its test in GLib's timers; a pending handoff write
    from one test must not land in the next test's folder."""
    yield
    while _players:
        player = _players.pop()
        if player._handoff_source:
            GLib.source_remove(player._handoff_source)
            player._handoff_source = 0
        player._queue, player._order, player._cursor = [], [], -1
        player._bin.set_state(Gst.State.NULL)


def _player(wav):
    from muzika.player import Player
    player = Player(_Api(wav), _Store())
    _players.append(player)
    return player


def _local_session(position_ms: int) -> None:
    session.save_queue([_track(n, duration=30) for n in range(3)], [2, 0, 1], True)
    session.save_position(_track(0, duration=30), 1, position_ms, 30_000, "none")


def test_reopening_shows_the_saved_song_paused_and_resumes_from_its_spot(isolated, wav):
    _local_session(position_ms=12_000)
    player = _player(wav)
    assert player.current["id"] == "v0"
    assert [t["id"] for t in player.queue] == ["v2", "v0", "v1"]
    assert player.shuffle and not player.playing
    assert player.position_duration()[0] == 12 * Gst.SECOND

    heard: list[int] = []
    player.connect("position-changed", lambda _p, pos, _d: heard.append(pos))
    player.play()
    assert _spin(lambda: player._bin.get_state(0)[1] == Gst.State.PLAYING
                 and player.position_duration()[0] > 12 * Gst.SECOND)
    position = player.position_duration()[0]
    assert 12 * Gst.SECOND <= position < 15 * Gst.SECOND
    # Never heard from the top on the way to the saved spot.
    played = [p for p in heard if p > 0]
    assert played and min(played) >= 12 * Gst.SECOND
    player.stop()


def test_play_takes_over_the_phones_newer_session(isolated, wav):
    _local_session(position_ms=3_000)
    player = _player(wav)
    notices: list[str] = []
    player.connect("notice", lambda _p, message: notices.append(message))

    # The phone played since, and paused 20 s into its own track.
    phone_queue = [_track(n, duration=30) for n in range(10, 14)]
    (isolated / session.HANDOFF_FILE).write_text(session.encode_handoff(
        phone_queue, [3, 1, 0, 2], 1, True, "none", 20_000, 30_000, False,
        "phone-id", "Pixel 7 Pro", time.time() + 1))

    player.play()
    assert player.current["id"] == "v11"
    assert [t["id"] for t in player.queue] == ["v13", "v11", "v10", "v12"]
    assert notices == ["Picked up “Track 11” from Pixel 7 Pro"]
    assert _spin(lambda: player.position_duration()[0] > 20 * Gst.SECOND)
    assert player.position_duration()[0] < 23 * Gst.SECOND

    # Taken over once - from here on it is this machine's own session.
    player.pause()
    assert not player.follow_other_device()
    player.stop()


def test_playing_here_tells_the_other_devices(isolated, wav):
    _local_session(position_ms=0)
    player = _player(wav)
    player.play()
    target = isolated / session.HANDOFF_FILE
    assert _spin(lambda: target.exists() and json.loads(target.read_text())["playing"])
    written = json.loads(target.read_text())
    assert written["device_id"] == session.device_id()
    assert written["track_id"] == "v0" and written["playing"] is True
    assert written["order"] == [2, 0, 1] and written["shuffle"] is True

    _spin(lambda: player.position_duration()[0] > 2 * Gst.SECOND)
    player.pause()
    assert _spin(lambda: json.loads(target.read_text())["playing"] is False)
    assert json.loads(target.read_text())["position_ms"] >= 2000

    # Quitting writes the exact spot at once, with no timer left to fire.
    player.save_now()
    assert json.loads(target.read_text())["playing"] is False
    player.stop()


# ------------------------------------------- a paused player stays paused

class _SlowApi(_Api):
    """Resolving takes a moment, as it does with yt-dlp."""

    def stream(self, track):
        time.sleep(0.4)
        return super().stream(track)


class _DeadApi(_Api):
    def stream(self, _track):
        time.sleep(0.3)
        raise RuntimeError("no stream")


def _stale_url_error(player) -> None:
    """What YouTube does to a URL that has gone stale: 403 Forbidden."""
    error = GLib.Error.new_literal(Gst.ResourceError.quark(), "Forbidden",
                                   int(Gst.ResourceError.OPEN_READ))
    player._bin.post_message(Gst.Message.new_error(player._bin, error, "HTTP 403"))


def _playing_at(player, seconds: float) -> None:
    player.play()
    assert _spin(lambda: player._bin.get_state(0)[1] == Gst.State.PLAYING
                 and player.position_duration()[0] > seconds * Gst.SECOND)


def test_a_stream_that_dies_while_paused_stays_silent_and_keeps_its_spot(isolated, wav):
    """The bug: left paused, the player was found later on the next song,
    playing - every recovery path ended in play."""
    _local_session(position_ms=0)
    player = _player(wav)
    _playing_at(player, 2)
    player.pause()
    _spin(lambda: False, seconds=0.3)
    paused_at = player.position_duration()[0]

    _stale_url_error(player)
    _spin(lambda: False, seconds=2.0)          # time for any retry or skip to happen

    assert not player.playing
    assert player._bin.get_state(0)[1] != Gst.State.PLAYING
    assert player.current["id"] == "v0"        # not moved on
    assert abs(player.position_duration()[0] - paused_at) < Gst.SECOND / 2

    # Play fetches it afresh and carries on from that spot.
    player.play()
    assert _spin(lambda: player._bin.get_state(0)[1] == Gst.State.PLAYING
                 and player.position_duration()[0] > paused_at)
    assert player.position_duration()[0] < paused_at + 3 * Gst.SECOND


def test_a_stale_stream_while_playing_is_fetched_again_from_where_it_died(isolated, wav):
    _local_session(position_ms=0)
    player = _player(wav)
    _playing_at(player, 4)
    _stale_url_error(player)
    assert _spin(lambda: player._bin.get_state(0)[1] == Gst.State.PLAYING
                 and player.position_duration()[0] > 4 * Gst.SECOND)
    assert player.current["id"] == "v0" and player.playing


def test_pausing_while_a_song_is_still_being_fetched_keeps_it_from_starting(isolated, wav):
    _local_session(position_ms=7_000)
    from muzika.player import Player
    player = Player(_SlowApi(wav), _Store())
    _players.append(player)
    player.play()
    assert player.playing                       # the button shows the press at once
    player.toggle()                             # pressed again before sound arrived
    _spin(lambda: False, seconds=1.5)
    assert not player.playing
    assert player._bin.get_state(0)[1] != Gst.State.PLAYING
    assert player.current["id"] == "v0"
    assert player.position_duration()[0] == 7 * Gst.SECOND


def test_a_song_that_cannot_be_found_is_only_skipped_if_you_are_still_listening(isolated, wav):
    _local_session(position_ms=0)
    from muzika.player import Player
    player = Player(_DeadApi(wav), _Store())
    _players.append(player)
    player.play()
    player.pause()
    _spin(lambda: False, seconds=2.0)
    assert player.current["id"] == "v0"         # paused: stays put

    player.play()                               # listening: the failure moves on
    assert _spin(lambda: player.current["id"] == "v1", seconds=4.0)


def test_writing_the_handoff_file_nudges_syncthing_at_once(isolated, monkeypatch):
    """Left alone Syncthing waits out its watcher delay before it even looks;
    a pause is the moment you reach for the phone."""
    nudged: list = []
    monkeypatch.setattr(session.syncthing, "rescan_async", lambda path: nudged.append(Path(path)))
    session.publish(QUEUE, SHUFFLED, 1, True, "none", 1000, 180_000, False)
    assert (isolated / session.HANDOFF_FILE).exists()
    assert nudged == [isolated]


def test_the_window_picks_up_a_handoff_the_moment_syncthing_delivers_it(isolated, tmp_path):
    """Reported: the phone's pause reached this machine, but the window kept
    saying "Nothing playing" - taking over waited for play or a click. The
    file is now watched. Syncthing delivers by writing a temporary file and
    renaming it into place, which is what is done here."""
    window_mod = pytest.importorskip("muzika.window")
    from types import SimpleNamespace
    from gi.repository import Gio

    followed: list[bool] = []
    fake = SimpleNamespace(_handoff_source=0,
                           player=SimpleNamespace(follow_other_device=lambda announce=False:
                                                  followed.append(announce)))
    fake._follow_handoff = lambda: window_mod.MuzikaWindow._follow_handoff(fake)

    target = isolated / session.HANDOFF_FILE
    monitor = Gio.File.new_for_path(str(target)).monitor_file(Gio.FileMonitorFlags.NONE, None)
    monitor.connect("changed", lambda m, f, o, e: window_mod.MuzikaWindow._on_handoff_changed(fake, m, f, o, e))
    _spin(lambda: False, seconds=0.2)

    temp = isolated / ".syncthing.muzika-nowplaying.json.tmp"
    temp.write_text(_phone_file(time.time()))
    temp.replace(target)
    assert _spin(lambda: followed == [True], seconds=5.0)
    monitor.cancel()
