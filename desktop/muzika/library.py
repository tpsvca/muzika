"""The Library page: favourites, saved items and playback history."""

from __future__ import annotations

from gi.repository import Adw, Gtk

from . import api as api_mod
from .pages import BasePage
from .widgets import Thumb, Tile, escape

TABS = [
    ("mine", "My Playlists", "view-list-bullet-symbolic"),
    ("favourites", "Favourites", "starred-symbolic"),
    ("playlists", "Playlists", "view-list-symbolic"),
    ("music", "Music", "folder-music-symbolic"),
    ("albums", "Albums", "media-optical-symbolic"),
    ("artists", "Artists", "avatar-default-symbolic"),
    ("history", "History", "document-open-recent-symbolic"),
]


class LibraryPage(BasePage):
    def __init__(self, ctx):
        super().__init__(ctx, "Library")
        self._stack = Adw.ViewStack()
        # Inline switcher because it can drop to icons only; the regular
        # ViewSwitcher just ellipsises its labels into "Favo…", "Playl…".
        self.switcher = Adw.InlineViewSwitcher()
        self.switcher.set_stack(self._stack)
        self.switcher.set_display_mode(Adw.InlineViewSwitcherDisplayMode.BOTH)
        self._header.set_title_widget(self.switcher)

        for name, title, icon in TABS:
            bin_ = Adw.Bin()
            bin_.set_vexpand(True)
            page = self._stack.add_titled(bin_, name, title)
            page.set_icon_name(icon)
            setattr(self, f"_slot_{name}", bin_)

        self.show_content(self._stack)
        self.reload()

    def reload(self) -> None:
        self._fill_local_playlists()
        self._fill_songs("favourites", self.ctx.store.favourites(),
                         "No favourites yet", "Star a song to keep it here.",
                         "starred-symbolic")
        self._fill_songs("history", self.ctx.store.history(),
                         "Nothing played yet", "Songs you play will show up here.",
                         "document-open-recent-symbolic", clearable=True)
        self._fill_songs("music", self.ctx.store.local_tracks(),
                         "No music on this computer yet",
                         "Add a folder of audio files in Settings.",
                         "folder-music-symbolic")
        # Saved catalogue items and what was found on disk, in one place: both
        # are things you own, and splitting them across tabs helps nobody.
        self._fill_tiles("playlists", self.ctx.store.library(api_mod.PLAYLIST))
        self._fill_tiles("albums",
                         self.ctx.store.library(api_mod.ALBUM)
                         + self.ctx.store.local_albums())
        self._fill_tiles("artists",
                         self.ctx.store.library(api_mod.ARTIST)
                         + self.ctx.store.local_artists())

    # ------------------------------------------------------------------ views

    def _tab_slot(self, name: str) -> Adw.Bin:
        return getattr(self, f"_slot_{name}")

    @staticmethod
    def _empty(title: str, description: str, icon: str) -> Adw.StatusPage:
        page = Adw.StatusPage()
        page.set_icon_name(icon)
        page.set_title(title)
        page.set_description(description)
        return page

    def _fill_songs(self, name: str, tracks: list[dict], empty_title: str,
                    empty_description: str, icon: str, clearable: bool = False) -> None:
        slot = self._tab_slot(name)
        if not tracks:
            slot.set_child(self._empty(empty_title, empty_description, icon))
            return

        group = Adw.PreferencesGroup()
        group.set_title(f"{len(tracks)} song{'s' if len(tracks) != 1 else ''}")

        buttons = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        play = Gtk.Button.new_from_icon_name("media-playback-start-symbolic")
        play.add_css_class("flat")
        play.set_tooltip_text("Play all")
        play.connect("clicked", lambda _b: self.ctx.play_tracks(tracks, 0, shuffle=False))
        buttons.append(play)
        shuffle = Gtk.Button.new_from_icon_name("media-playlist-shuffle-symbolic")
        shuffle.add_css_class("flat")
        shuffle.set_tooltip_text("Shuffle all")
        shuffle.connect("clicked", lambda _b: self.ctx.play_tracks(tracks, shuffle=True))
        buttons.append(shuffle)
        if clearable:
            clear = Gtk.Button.new_from_icon_name("user-trash-symbolic")
            clear.add_css_class("flat")
            clear.set_tooltip_text("Clear history")
            clear.connect("clicked", lambda _b: (self.ctx.store.clear_history(), self.reload()))
            buttons.append(clear)
        group.set_header_suffix(buttons)

        for index, track in enumerate(tracks):
            group.add(self.ctx.make_song_row(track, tracks, index))

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.set_margin_top(12)
        box.set_margin_bottom(24)
        box.set_margin_start(12)
        box.set_margin_end(12)
        box.append(group)
        slot.set_child(self.scrolled(box))

    def _fill_local_playlists(self) -> None:
        slot = self._tab_slot("mine")
        entries = self.ctx.store.playlists()

        if not entries:
            page = Adw.StatusPage()
            page.set_icon_name("view-list-bullet-symbolic")
            page.set_title("No playlists yet")
            page.set_description("Make one, then add songs with “Add to playlist”.")
            button = Gtk.Button(label="New Playlist")
            button.add_css_class("pill")
            button.add_css_class("suggested-action")
            button.set_halign(Gtk.Align.CENTER)
            button.connect("clicked", lambda _b: self.ctx.new_playlist())
            page.set_child(button)
            slot.set_child(page)
            return

        group = Adw.PreferencesGroup()
        group.set_title(f"{len(entries)} playlist{'s' if len(entries) != 1 else ''}")
        new_button = Gtk.Button.new_from_icon_name("document-new-symbolic")
        new_button.add_css_class("flat")
        new_button.set_tooltip_text("New playlist")
        new_button.connect("clicked", lambda _b: self.ctx.new_playlist())
        group.set_header_suffix(new_button)

        for entry in entries:
            row = Adw.ActionRow()
            row.set_title(escape(entry["title"]))
            row.set_subtitle(entry["subtitle"])
            thumb = Thumb(44, "playlist")
            thumb.set_url(entry.get("thumb"))
            row.add_prefix(thumb)
            row.add_suffix(Gtk.Image.new_from_icon_name("go-next-symbolic"))
            row.set_activatable(True)
            row.connect("activated",
                        lambda _r, e=entry: self.ctx.open_local_playlist(e["playlist_id"]))
            group.add(row)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.set_margin_top(12)
        box.set_margin_bottom(24)
        box.set_margin_start(12)
        box.set_margin_end(12)
        box.append(group)
        slot.set_child(self.scrolled(box))

    def _fill_tiles(self, name: str, items: list[dict]) -> None:
        slot = self._tab_slot(name)
        if not items:
            icon = {"playlists": "view-list-symbolic", "albums": "media-optical-symbolic",
                    "artists": "avatar-default-symbolic"}[name]
            slot.set_child(self._empty(f"No saved {name}",
                                       "Use the bookmark button on any page to save it here.",
                                       icon))
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
        slot.set_child(self.scrolled(flow))


class LocalCollectionPage(BasePage):
    """Every local song on one album, or by one artist."""

    def __init__(self, ctx, item: dict):
        title = item.get("title") or "Music"
        super().__init__(ctx, title)
        self._item = item
        self.reload()

    def reload(self) -> None:
        item = self._item
        name = item.get("title") or ""
        if item.get("kind") == api_mod.ALBUM:
            tracks = self.ctx.store.local_tracks(album=name)
        else:
            tracks = self.ctx.store.local_tracks(artist=name)

        if not tracks:
            page = Adw.StatusPage()
            page.set_icon_name("folder-music-symbolic")
            page.set_title("Nothing here any more")
            page.set_description(
                "These files are no longer in your music folders. "
                "Rescan in Settings to update the index.")
            self.show_content(page)
            return

        group = Adw.PreferencesGroup()
        group.set_title(name)
        subtitle = item.get("subtitle") or ""
        group.set_description(
            f"{len(tracks)} song{'s' if len(tracks) != 1 else ''}"
            + (f" \u00b7 {subtitle}" if subtitle and not subtitle.endswith("songs") else ""))

        buttons = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        play = Gtk.Button.new_from_icon_name("media-playback-start-symbolic")
        play.add_css_class("flat")
        play.set_tooltip_text("Play all")
        play.connect("clicked", lambda _b: self.ctx.play_tracks(tracks, 0, shuffle=False))
        buttons.append(play)
        shuffle = Gtk.Button.new_from_icon_name("media-playlist-shuffle-symbolic")
        shuffle.add_css_class("flat")
        shuffle.set_tooltip_text("Shuffle all")
        shuffle.connect("clicked", lambda _b: self.ctx.play_tracks(tracks, shuffle=True))
        buttons.append(shuffle)
        group.set_header_suffix(buttons)

        for index, track in enumerate(tracks):
            group.add(self.ctx.make_song_row(track, tracks, index))

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.set_margin_top(12)
        box.set_margin_bottom(24)
        box.set_margin_start(12)
        box.set_margin_end(12)
        box.append(group)
        self.show_content(self.scrolled(box))
