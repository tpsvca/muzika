"""Browse pages: Home, Explore, Moods, Search and the detail view."""

from __future__ import annotations

from gi.repository import Adw, GLib, Gtk

from . import api as api_mod
from . import sources as source_mod
from . import tasks
from .widgets import (LoadingView, Shelf, SongRow, Thumb, Tile, error_view,
                      escape, format_duration)

SEARCH_FILTERS = [
    ("All", None),
    ("Songs", "songs"),
    ("Playlists", "playlists"),
    ("Albums", "albums"),
    ("Artists", "artists"),
    ("Videos", "videos"),
]


class BasePage(Adw.NavigationPage):
    """A page that swaps between loading, error and content states."""

    def __init__(self, ctx, title: str):
        super().__init__()
        self.ctx = ctx
        self.set_title(title)
        self._toolbar = Adw.ToolbarView()
        self._header = Adw.HeaderBar()
        ctx.add_sidebar_button(self._header)
        self._toolbar.add_top_bar(self._header)
        self._slot = Adw.Bin()
        self._slot.set_vexpand(True)
        self._toolbar.set_content(self._slot)
        self.set_child(self._toolbar)

    def show_loading(self, message: str = "Loading…") -> None:
        self._slot.set_child(LoadingView(message))

    def show_error(self, message: str, retry=None) -> None:
        self._slot.set_child(error_view(message, retry))

    def show_content(self, widget: Gtk.Widget) -> None:
        self._slot.set_child(widget)

    @staticmethod
    def scrolled(child: Gtk.Widget, clamp: bool = True, maximum: int = 1100) -> Gtk.ScrolledWindow:
        scroller = Gtk.ScrolledWindow()
        scroller.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        scroller.set_vexpand(True)
        if clamp:
            clamped = Adw.Clamp()
            clamped.set_maximum_size(maximum)
            clamped.set_tightening_threshold(700)
            clamped.set_child(child)
            scroller.set_child(clamped)
        else:
            scroller.set_child(child)
        return scroller


class ShelvesPage(BasePage):
    """Shared implementation for Home and Explore."""

    def __init__(self, ctx, title: str):
        super().__init__(ctx, title)
        refresh = Gtk.Button.new_from_icon_name("view-refresh-symbolic")
        refresh.set_tooltip_text("Refresh")
        refresh.add_css_class("flat")
        refresh.connect("clicked", lambda _b: self.reload())
        self._header.pack_end(refresh)
        self._loaded = False

    def fetch(self) -> list[dict]:
        raise NotImplementedError

    def reload(self) -> None:
        self._loaded = True
        self.show_loading()
        tasks.run_async(self.fetch, self._render,
                        lambda exc: self.show_error(str(exc), self.reload))

    def ensure_loaded(self) -> None:
        if not self._loaded:
            self.reload()

    def extra_widgets(self) -> list[Gtk.Widget]:
        return []

    def _render(self, shelves: list[dict]) -> None:
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=24)
        box.set_margin_top(12)
        box.set_margin_bottom(24)
        box.set_margin_start(12)
        box.set_margin_end(12)
        for widget in self.extra_widgets():
            box.append(widget)
        if not shelves:
            self.show_error("YouTube Music returned nothing for this page.", self.reload)
            return
        for shelf in shelves:
            box.append(Shelf(shelf["title"], shelf["items"], self.ctx.open_item))
        self.show_content(self.scrolled(box, clamp=False))


class HomePage(ShelvesPage):
    def __init__(self, ctx):
        super().__init__(ctx, "Home")

    def fetch(self):
        return self.ctx.api.home()


class ExplorePage(ShelvesPage):
    def __init__(self, ctx):
        super().__init__(ctx, "Explore")
        self._moods: list[dict] = []

    def fetch(self):
        charts = self.ctx.api.charts()
        try:
            self._moods = self.ctx.api.mood_categories()
        except Exception:
            self._moods = []
        return charts

    def extra_widgets(self):
        if not self._moods:
            return []
        heading = Gtk.Label(label="Moods & genres", xalign=0.0)
        heading.add_css_class("title-4")
        heading.set_margin_start(4)

        flow = Gtk.FlowBox()
        flow.set_selection_mode(Gtk.SelectionMode.NONE)
        flow.set_max_children_per_line(6)
        flow.set_column_spacing(8)
        flow.set_row_spacing(8)
        flow.set_homogeneous(False)
        for mood in self._moods:
            button = Gtk.Button(label=mood["title"])
            button.add_css_class("pill")
            button.connect("clicked", lambda _b, m=mood: self.ctx.open_mood(m))
            flow.append(button)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        box.append(heading)
        box.append(flow)
        return [box]


class MoodPage(BasePage):
    def __init__(self, ctx, mood: dict):
        super().__init__(ctx, mood.get("title") or "Mood")
        self._mood = mood
        self.reload()

    def reload(self) -> None:
        self.show_loading()
        tasks.run_async(lambda: self.ctx.api.mood_playlists(self._mood["params"]),
                        self._render,
                        lambda exc: self.show_error(str(exc), self.reload))

    def _render(self, items: list[dict]) -> None:
        if not items:
            self.show_error("No playlists in this category.", self.reload)
            return
        flow = Gtk.FlowBox()
        flow.set_selection_mode(Gtk.SelectionMode.NONE)
        flow.set_max_children_per_line(6)
        flow.set_column_spacing(12)
        flow.set_row_spacing(12)
        flow.set_margin_top(12)
        flow.set_margin_bottom(24)
        flow.set_margin_start(12)
        flow.set_margin_end(12)
        for item in items:
            tile = Tile(item)
            tile.connect("clicked", lambda _b, it=item: self.ctx.open_item(it))
            flow.append(tile)
        self.show_content(self.scrolled(flow))


class SearchPage(BasePage):
    def __init__(self, ctx):
        super().__init__(ctx, "Search")
        self._latest = tasks.Latest()
        self._filter: str | None = None

        self._entry = Gtk.SearchEntry()
        self._entry.set_placeholder_text("Songs, albums, artists, playlists…")
        self._entry.set_hexpand(True)
        self._entry.connect("activate", lambda _e: self.run_search())
        self._header.set_title_widget(self._entry)

        self._filter_bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        self._filter_bar.set_halign(Gtk.Align.CENTER)
        self._filter_bar.set_margin_top(8)
        self._filter_bar.set_margin_bottom(8)
        first: Gtk.ToggleButton | None = None
        for label, value in SEARCH_FILTERS:
            button = Gtk.ToggleButton(label=label)
            button.add_css_class("pill")
            if first is None:
                first = button
                button.set_active(True)
            else:
                button.set_group(first)
            button.connect("toggled", self._on_filter_toggled, value)
            self._filter_bar.append(button)
        self._toolbar.add_top_bar(self._filter_bar)
        self._filter_bar.set_visible(False)

        self.show_recent()

    def focus_entry(self) -> None:
        self._entry.grab_focus()

    def search_for(self, query: str) -> None:
        self._entry.set_text(query)
        self.run_search()

    def _on_filter_toggled(self, button: Gtk.ToggleButton, value: str | None) -> None:
        if not button.get_active():
            return
        self._filter = value
        if self._entry.get_text().strip():
            self.run_search()

    # ------------------------------------------------------------------ views

    def show_recent(self) -> None:
        self._filter_bar.set_visible(False)
        recent = self.ctx.store.recent_searches()
        if not recent:
            page = Adw.StatusPage()
            page.set_icon_name("system-search-symbolic")
            page.set_title("Search YouTube Music")
            page.set_description("Find songs, albums, artists and playlists.")
            self.show_content(page)
            return

        group = Adw.PreferencesGroup()
        group.set_title("Recent searches")
        clear = Gtk.Button.new_from_icon_name("user-trash-symbolic")
        clear.add_css_class("flat")
        clear.set_tooltip_text("Clear search history")
        clear.connect("clicked", lambda _b: (self.ctx.store.clear_searches(), self.show_recent()))
        group.set_header_suffix(clear)
        for query in recent:
            row = Adw.ActionRow()
            row.set_title(escape(query))
            row.add_prefix(Gtk.Image.new_from_icon_name("document-open-recent-symbolic"))
            row.set_activatable(True)
            row.connect("activated", lambda _r, q=query: self.search_for(q))
            group.add(row)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.set_margin_top(12)
        box.set_margin_bottom(24)
        box.set_margin_start(12)
        box.set_margin_end(12)
        box.append(group)
        self.show_content(self.scrolled(box))

    def run_search(self) -> None:
        query = self._entry.get_text().strip()
        if not query:
            self.show_recent()
            return
        self._filter_bar.set_visible(True)
        self.ctx.store.record_search(query)
        generation = self._latest.begin()
        self.show_loading(f"Searching for “{query}”…")
        filter_ = self._filter

        def work():
            # Other sources are searched alongside YouTube; each one is
            # independently guarded, so a dead provider costs results but
            # never the whole search.
            return {
                "youtube": self.ctx.api.search(query, filter_, limit=40),
                "sources": self.ctx.api.search_sources(
                    query, [source_mod.SOUNDCLOUD, source_mod.BANDCAMP], limit=12),
            }

        tasks.run_async(
            work,
            self._latest.guard(generation, self._render),
            self._latest.guard(generation, lambda exc: self.show_error(str(exc), self.run_search)))

    def _render(self, payload: dict) -> None:
        results = payload.get("youtube") or []
        extra = payload.get("sources") or {}
        if not results and not extra:
            page = Adw.StatusPage()
            page.set_icon_name("system-search-symbolic")
            page.set_title("No results")
            page.set_description("Try a different search or filter.")
            self.show_content(page)
            return

        songs = [r for r in results if r["kind"] in (api_mod.SONG, api_mod.VIDEO)]
        others = [r for r in results if r["kind"] not in (api_mod.SONG, api_mod.VIDEO)]

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=18)
        box.set_margin_top(12)
        box.set_margin_bottom(24)
        box.set_margin_start(12)
        box.set_margin_end(12)

        if songs:
            group = Adw.PreferencesGroup()
            group.set_title("Songs")
            play_all = Gtk.Button.new_from_icon_name("media-playback-start-symbolic")
            play_all.add_css_class("flat")
            play_all.set_tooltip_text("Play all results")
            play_all.connect("clicked", lambda _b: self.ctx.play_tracks(songs, 0, shuffle=False))
            group.set_header_suffix(play_all)
            for index, track in enumerate(songs):
                group.add(self.ctx.make_song_row(track, songs, index))
            box.append(group)

        by_kind: dict[str, list[dict]] = {}
        for item in others:
            by_kind.setdefault(item["kind"], []).append(item)
        labels = {api_mod.PLAYLIST: "Playlists", api_mod.ALBUM: "Albums", api_mod.ARTIST: "Artists"}
        for kind, label in labels.items():
            items = by_kind.get(kind)
            if not items:
                continue
            heading = Gtk.Label(label=label, xalign=0.0)
            heading.add_css_class("title-4")
            flow = Gtk.FlowBox()
            flow.set_selection_mode(Gtk.SelectionMode.NONE)
            flow.set_max_children_per_line(6)
            flow.set_column_spacing(12)
            flow.set_row_spacing(12)
            for item in items:
                tile = Tile(item)
                tile.connect("clicked", lambda _b, it=item: self.ctx.open_item(it))
                flow.append(tile)
            section = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
            section.append(heading)
            section.append(flow)
            box.append(section)

        for name, tracks in extra.items():
            group = Adw.PreferencesGroup()
            group.set_title(source_mod.LABELS.get(name, name.title()))
            group.set_description(f"{len(tracks)} result{'s' if len(tracks) != 1 else ''}")
            play_all = Gtk.Button.new_from_icon_name("media-playback-start-symbolic")
            play_all.add_css_class("flat")
            play_all.set_tooltip_text("Play all")
            play_all.connect("clicked", lambda _b, t=tracks: self.ctx.play_tracks(t, 0, shuffle=False))
            group.set_header_suffix(play_all)
            for index, track in enumerate(tracks):
                group.add(self.ctx.make_song_row(track, tracks, index))
            box.append(group)

        self.show_content(self.scrolled(box))


class DetailPage(BasePage):
    """A playlist, album or artist: artwork, Play/Shuffle, then the tracks."""

    def __init__(self, ctx, item: dict):
        super().__init__(ctx, item.get("title") or "Loading…")
        self._item = item
        self._detail: dict | None = None
        self._play_content: Adw.ButtonContent | None = None
        self._handlers: list[int] = []
        self.show_loading()
        kind, item_id = item["kind"], item["id"]

        def fetch():
            if kind == api_mod.ALBUM:
                return self.ctx.api.album(item_id)
            if kind == api_mod.ARTIST:
                return self.ctx.api.artist(item_id)
            return self.ctx.api.playlist(item_id)

        self._fetch = fetch
        tasks.run_async(fetch, self._render,
                        lambda exc: self.show_error(str(exc), self._retry))

    def _retry(self) -> None:
        self.show_loading()
        tasks.run_async(self._fetch, self._render,
                        lambda exc: self.show_error(str(exc), self._retry))

    def _render(self, detail: dict) -> None:
        self._detail = detail
        self.set_title(detail.get("title") or "")
        tracks = detail.get("tracks") or []

        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=18)
        outer.set_margin_top(12)
        outer.set_margin_bottom(24)
        outer.set_margin_start(12)
        outer.set_margin_end(12)

        outer.append(self._build_header(detail, tracks))

        if tracks:
            group = Adw.PreferencesGroup()
            subtitle = f"{len(tracks)} song{'s' if len(tracks) != 1 else ''}"
            group.set_title("Tracks")
            group.set_description(subtitle)
            numbered = detail["kind"] == api_mod.ALBUM
            for index, track in enumerate(tracks):
                row = self.ctx.make_song_row(track, tracks, index,
                                             index=index + 1 if numbered else None,
                                             show_thumb=not numbered)
                group.add(row)
            outer.append(group)
        elif detail["kind"] != api_mod.ARTIST:
            outer.append(error_view("This item has no playable tracks."))

        for shelf in detail.get("shelves", []):
            outer.append(Shelf(shelf["title"], shelf["items"], self.ctx.open_item))

        self.show_content(self.scrolled(outer))

        player = self.ctx.player
        self._handlers = [
            player.connect("state-changed", lambda *_: self._sync_play_button()),
            player.connect("track-changed", lambda *_: self._sync_play_button()),
        ]
        self.connect("destroy", self._drop_handlers)
        self._sync_play_button()

    def _drop_handlers(self, *_args) -> None:
        for handler in self._handlers:
            self.ctx.player.disconnect(handler)
        self._handlers = []

    def _source_id(self) -> str:
        if not self._detail:
            return ""
        return f"{self._detail['kind']}:{self._detail['id']}"

    def _is_playing_this(self) -> bool:
        return bool(self._detail) and self.ctx.player.source == self._source_id()

    def _on_play_clicked(self, tracks: list[dict]) -> None:
        if self._is_playing_this():
            # Already playing this page - act as a pause/resume button.
            self.ctx.player.toggle()
            return
        self.ctx.play_tracks(tracks, 0, shuffle=False, source=self._source_id())

    def _sync_play_button(self) -> None:
        if self._play_content is None:
            return
        if self._is_playing_this() and self.ctx.player.playing:
            self._play_content.set_icon_name("media-playback-pause-symbolic")
            self._play_content.set_label("Pause")
        else:
            self._play_content.set_icon_name("media-playback-start-symbolic")
            self._play_content.set_label("Play")

    def _build_header(self, detail: dict, tracks: list[dict]) -> Gtk.Widget:
        header = Adw.WrapBox()
        header.set_child_spacing(20)
        header.set_line_spacing(16)
        header.set_margin_bottom(4)

        art = Thumb(180, detail["kind"], round_full=detail["kind"] == api_mod.ARTIST)
        art.set_url(detail.get("thumb"))
        art.add_css_class("hero-art")
        header.append(art)

        text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        text.set_valign(Gtk.Align.CENTER)
        text.set_hexpand(True)
        text.set_size_request(240, -1)

        kind_label = Gtk.Label(label=detail["kind"].upper(), xalign=0.0)
        kind_label.add_css_class("caption-heading")
        kind_label.add_css_class("dim-label")
        text.append(kind_label)

        title = Gtk.Label(label=detail.get("title") or "", xalign=0.0)
        title.add_css_class("title-1")
        title.set_wrap(True)
        title.set_lines(2)
        title.set_ellipsize(3)
        text.append(title)

        if detail.get("subtitle"):
            subtitle = Gtk.Label(label=detail["subtitle"], xalign=0.0)
            subtitle.add_css_class("dim-label")
            subtitle.set_wrap(True)
            subtitle.set_lines(2)
            subtitle.set_ellipsize(3)
            text.append(subtitle)

        if tracks:
            total = sum(t.get("duration", 0) for t in tracks)
            meta = Gtk.Label(
                label=f"{len(tracks)} songs · {format_duration(total)}" if total
                else f"{len(tracks)} songs", xalign=0.0)
            meta.add_css_class("caption")
            meta.add_css_class("dim-label")
            text.append(meta)

        buttons = Adw.WrapBox()
        buttons.set_child_spacing(8)
        buttons.set_line_spacing(8)
        buttons.set_margin_top(8)
        # Without this the row reports its full single-line width as natural,
        # which pushes the whole header to stack vertically far too early.
        buttons.set_natural_line_length(320)

        self._play_content = Adw.ButtonContent(
            icon_name="media-playback-start-symbolic", label="Play")
        play = Gtk.Button()
        play.set_child(self._play_content)
        play.add_css_class("suggested-action")
        play.add_css_class("pill")
        play.set_sensitive(bool(tracks))
        play.connect("clicked", lambda _b: self._on_play_clicked(tracks))
        buttons.append(play)

        shuffle = Gtk.Button()
        shuffle.set_child(Adw.ButtonContent(icon_name="media-playlist-shuffle-symbolic",
                                            label="Shuffle"))
        shuffle.add_css_class("pill")
        shuffle.set_sensitive(bool(tracks))
        shuffle.connect("clicked",
                        lambda _b: self.ctx.play_tracks(tracks, shuffle=True,
                                                        source=self._source_id()))
        buttons.append(shuffle)

        queue = Gtk.Button.new_from_icon_name("list-add-symbolic")
        queue.add_css_class("pill")
        queue.set_tooltip_text("Add to queue")
        queue.set_sensitive(bool(tracks))
        queue.connect("clicked", lambda _b: self.ctx.enqueue(tracks))
        buttons.append(queue)

        save = Gtk.Button.new_from_icon_name("view-list-bullet-symbolic")
        save.add_css_class("pill")
        save.set_tooltip_text("Save all to one of your playlists")
        save.set_sensitive(bool(tracks))
        save.connect("clicked", lambda _b: self.ctx.add_to_playlist(tracks))
        buttons.append(save)

        saved = self.ctx.store.in_library(detail["kind"], detail["id"])
        library = Gtk.ToggleButton()
        library.set_icon_name("user-bookmarks-symbolic" if saved else "bookmark-new-symbolic")
        library.set_active(saved)
        library.add_css_class("pill")
        library.set_tooltip_text("Save to library")
        library.connect("toggled", self._on_library_toggled, detail)
        buttons.append(library)

        text.append(buttons)
        header.append(text)
        return header

    def _on_library_toggled(self, button: Gtk.ToggleButton, detail: dict) -> None:
        entry = {"kind": detail["kind"], "id": detail["id"], "title": detail.get("title", ""),
                 "subtitle": detail.get("subtitle", ""), "thumb": detail.get("thumb")}
        now_saved = self.ctx.store.in_library(entry["kind"], entry["id"])
        if button.get_active() == now_saved:
            return
        state = self.ctx.store.toggle_library(entry)
        button.set_icon_name("user-bookmarks-symbolic" if state else "bookmark-new-symbolic")
        self.ctx.toast("Saved to library" if state else "Removed from library")
        self.ctx.refresh_library()
