"""Home's own sections: recently played, and your playlists.

These build real widgets, so they need the GTK stack and are skipped where it
is absent - CI byte-compiles instead. The point is to catch the two things a
reader cannot see from the source: that the sections are actually in the tree,
and that they survive YouTube Music failing.
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
    from gi.repository import Adw, Gtk
except (ValueError, ImportError) as exc:  # pragma: no cover - depends on the host
    pytest.skip(f"GTK4/libadwaita unavailable: {exc}", allow_module_level=True)

Adw.init()

from muzika.db import Store  # noqa: E402
from muzika.pages import HomePage  # noqa: E402


class FakeCtx:
    """Only the parts of the window HomePage actually touches."""

    def __init__(self, store):
        self.store = store
        self.api = None
        self.played: list[tuple[list[dict], int]] = []
        self.opened_playlists: list[int] = []
        self.opened_tabs: list[str] = []
        self.new_playlists = 0

    def add_sidebar_button(self, _header):
        pass

    def open_item(self, item):
        pass

    def play_tracks(self, tracks, index, shuffle=False):
        self.played.append((tracks, index))

    def open_local_playlist(self, playlist_id):
        self.opened_playlists.append(playlist_id)

    def open_library_tab(self, name):
        self.opened_tabs.append(name)

    def new_playlist(self):
        self.new_playlists += 1


def walk(widget):
    yield widget
    child = widget.get_first_child() if hasattr(widget, "get_first_child") else None
    while child is not None:
        yield from walk(child)
        child = child.get_next_sibling()


def headings(page):
    return [w.get_label() for w in walk(page)
            if isinstance(w, Gtk.Label) and w.has_css_class("title-4")]


def tile_titles(page):
    from muzika.widgets import Tile
    return [t.item.get("title") for t in walk(page) if isinstance(t, Tile)]


@pytest.fixture()
def home(tmp_path):
    store = Store(tmp_path / "muzika.db")
    ctx = FakeCtx(store)
    return HomePage(ctx), store, ctx


def test_both_sections_render_before_the_network_answers(home):
    page, store, _ctx = home
    store.record_play({"id": "a", "title": "Enter Sandman", "subtitle": "Metallica"})
    playlist_id = store.create_playlist("Road trip")

    # show_loading is what reload() calls before the fetch returns.
    page.show_loading()

    assert "Recently played" in headings(page)
    assert "My playlists" in headings(page)
    assert "Enter Sandman" in tile_titles(page)
    assert "Road trip" in tile_titles(page)
    assert playlist_id


def test_sections_survive_youtube_music_failing(home):
    page, store, _ctx = home
    store.record_play({"id": "a", "title": "One", "subtitle": "Metallica"})
    store.create_playlist("Road trip")

    page._on_failed(RuntimeError("503 Service Unavailable"))

    assert "Recently played" in headings(page)
    assert "My playlists" in headings(page)
    assert "One" in tile_titles(page)


def test_a_page_with_nothing_of_its_own_still_shows_the_error(home):
    page, _store, _ctx = home  # empty library: no recent plays, no playlists

    page._on_failed(RuntimeError("503 Service Unavailable"))

    # Headings are still drawn (the shelves explain themselves when empty),
    # but there are no tiles and the failure is reported rather than hidden.
    assert tile_titles(page) == []
    labels = [w.get_label() for w in walk(page) if isinstance(w, Gtk.Label) and w.get_label()]
    assert any("unavailable" in (text or "").lower() for text in labels)


def test_clicking_a_recent_tile_plays_the_shelf_from_there(home):
    page, store, ctx = home
    for index, title in enumerate(["First", "Second", "Third"]):
        store.record_play({"id": f"id{index}", "title": title, "subtitle": "x"})

    page.show_loading()
    recent = page._recent
    page._play_recent(recent[1])

    tracks, start = ctx.played[-1]
    assert [t["title"] for t in tracks] == [t["title"] for t in recent]
    assert start == 1, "should start at the clicked track, not the top"


def test_opening_a_playlist_tile_uses_its_row_id(home):
    page, store, ctx = home
    playlist_id = store.create_playlist("Road trip")

    page.show_loading()
    page._open_playlist({"playlist_id": playlist_id})

    assert ctx.opened_playlists == [playlist_id]


def test_a_track_change_marks_home_stale_without_redrawing(home):
    page, store, _ctx = home
    store.record_play({"id": "a", "title": "One", "subtitle": "Metallica"})
    page._loaded = True
    page._render([])
    before = tile_titles(page)

    store.record_play({"id": "b", "title": "Two", "subtitle": "Metallica"})
    page.refresh_mine(immediate=False)

    assert page._mine_stale is True
    assert tile_titles(page) == before, "an unmapped page must not redraw yet"

    page.ensure_loaded()
    assert page._mine_stale is False
    assert "Two" in tile_titles(page)
