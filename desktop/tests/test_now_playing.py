"""The Now Playing tabs: transport, and the lyrics cache.

Widget tests, so they need GTK and skip where it is absent - CI byte-compiles
and lints instead.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

gi = pytest.importorskip("gi")
try:
    gi.require_version("Adw", "1")
    gi.require_version("Gtk", "4.0")
    from gi.repository import Adw, GObject, Gtk
except (ValueError, ImportError) as exc:  # pragma: no cover - depends on the host
    pytest.skip(f"GTK4/libadwaita unavailable: {exc}", allow_module_level=True)

Adw.init()

from muzika.player_ui import MiniBar, NowPlayingPage  # noqa: E402


class FakePlayer(GObject.Object):
    """The player's surface, without GStreamer."""

    __gsignals__ = {
        "track-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "state-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "position-changed": (GObject.SignalFlags.RUN_FIRST, None,
                             (GObject.TYPE_INT64, GObject.TYPE_INT64)),
        "queue-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "playback-error": (GObject.SignalFlags.RUN_FIRST, None, (str,)),
    }

    def __init__(self):
        super().__init__()
        self.current = {"id": "x", "title": "Die, Die My Darling",
                        "subtitle": "Metallica", "duration": 150}
        self.playing = True
        self.loading = False
        self.shuffle = False
        self.repeat = 0
        self.queue: list[dict] = [self.current]
        self.queue_position = 0
        self.calls: list[str] = []

    def toggle(self):
        self.calls.append("toggle")
        self.playing = not self.playing
        self.emit("state-changed")

    def next(self):
        self.calls.append("next")

    def previous(self):
        self.calls.append("previous")


class FakeCtx:
    def __init__(self, player):
        self.player = player
        self.api = None
        self.store = None


def walk(widget):
    yield widget
    child = widget.get_first_child() if hasattr(widget, "get_first_child") else None
    while child is not None:
        yield from walk(child)
        child = child.get_next_sibling()


@pytest.fixture()
def page():
    player = FakePlayer()
    return NowPlayingPage(FakeCtx(player)), player


def test_the_mini_bar_is_hidden_on_the_song_tab(page):
    view, _player = page
    view.show_tab("song")
    assert view._mini_revealer.get_reveal_child() is False, \
        "the Song tab has its own transport; a second one is clutter"


@pytest.mark.parametrize("tab", ["lyrics", "queue"])
def test_the_mini_bar_appears_on_the_tabs_without_transport(page, tab):
    view, _player = page
    view.show_tab(tab)
    assert view._mini_revealer.get_reveal_child() is True, \
        f"no way to pause from the {tab} tab without leaving it"
    assert any(isinstance(w, MiniBar) for w in walk(view))


def test_the_mini_bar_shows_what_is_playing(page):
    view, player = page
    view.show_tab("queue")
    labels = [w.get_label() for w in walk(view._mini) if isinstance(w, Gtk.Label)]
    assert player.current["title"] in labels
    assert player.current["subtitle"] in labels


def test_the_mini_bar_controls_reach_the_player():
    player = FakePlayer()
    bar = MiniBar(player)
    buttons = [w for w in walk(bar) if isinstance(w, Gtk.Button)]
    assert len(buttons) >= 3
    for button in buttons:
        button.emit("clicked")
    assert {"previous", "toggle", "next"} <= set(player.calls)


def test_the_play_button_follows_the_player(page):
    view, player = page
    view.show_tab("lyrics")
    player.playing = True
    view._mini.refresh_state()
    assert view._mini._play.get_icon_name() == "media-playback-pause-symbolic"
    player.playing = False
    view._mini.refresh_state()
    assert view._mini._play.get_icon_name() == "media-playback-start-symbolic"


def test_a_loading_track_says_so_rather_than_offering_pause(page):
    view, player = page
    view.show_tab("lyrics")
    player.loading = True
    view._mini.refresh_state()
    assert view._mini._play.get_icon_name() == "content-loading-symbolic"


# ------------------------------------------------------------- lyrics cache

class CountingApi:
    """A stand-in for Api that counts how often it really goes looking."""

    def __init__(self, answer):
        from muzika.api import Api
        self.lookups = 0
        self._answer = answer
        self._real = Api.__new__(Api)          # no network, no ytmusicapi
        import threading
        from collections import OrderedDict
        self._real._lyrics_cache = OrderedDict()
        self._real._lyrics_lock = threading.Lock()
        self._real._lookup_lyrics = self._lookup
        self.lyrics = lambda *a, **kw: Api.lyrics(self._real, *a, **kw)

    def _lookup(self, video_id, track):
        self.lookups += 1
        return self._answer


TRACK = {"id": "song-1", "title": "Die, Die My Darling",
         "artists": "Metallica", "duration": 150}
FOUND = {"text": "words", "lines": [(0, "words")], "provider": "LRCLIB"}


def test_lyrics_are_looked_up_once_then_served_from_cache():
    api = CountingApi(FOUND)
    first = api.lyrics("song-1", TRACK)
    second = api.lyrics("song-1", TRACK)
    assert api.lookups == 1, "re-entering the tab went back to the network"
    assert first is second


def test_a_song_with_no_lyrics_is_not_looked_up_again_either():
    api = CountingApi(None)
    assert api.lyrics("song-1", TRACK) is None
    assert api.lyrics("song-1", TRACK) is None
    assert api.lookups == 1, "a miss must be cached, or the tab re-asks every provider"


def test_refresh_really_does_go_back_out():
    api = CountingApi(FOUND)
    api.lyrics("song-1", TRACK)
    api.lyrics("song-1", TRACK, refresh=True)
    assert api.lookups == 2


def test_the_cache_outlives_the_song_but_not_forever():
    import time
    api = CountingApi(FOUND)
    api.lyrics("song-1", TRACK)
    (_value, expires_at), = api._real._lyrics_cache.values()
    held_for = expires_at - time.time()
    song = TRACK["duration"]
    assert held_for > song, "cache expired before the song even finished"
    assert held_for >= 3600 - 5


def test_the_cache_does_not_grow_without_bound():
    from muzika.api import _LYRICS_CACHE_MAX
    api = CountingApi(FOUND)
    for index in range(_LYRICS_CACHE_MAX + 20):
        api.lyrics(f"song-{index}", TRACK)
    assert len(api._real._lyrics_cache) == _LYRICS_CACHE_MAX
