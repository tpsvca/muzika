"""Playlists you make yourself, stored locally.

These are not YouTube playlists - the app is not signed in to an account - so
they live in the local SQLite store and can hold any track you come across.
"""

from __future__ import annotations

from gi.repository import Adw, Gtk

from .pages import BasePage
from .widgets import Thumb, error_view, escape, format_duration


def ask_for_name(parent, heading: str, initial: str, accept: str, on_accept) -> None:
    """Small name prompt used for creating and renaming playlists."""
    dialog = Adw.AlertDialog()
    dialog.set_heading(heading)
    entry = Gtk.Entry()
    entry.set_text(initial)
    entry.set_activates_default(True)
    entry.set_margin_top(6)
    entry.set_margin_start(6)
    entry.set_margin_end(6)
    dialog.set_extra_child(entry)
    dialog.add_response("cancel", "Cancel")
    dialog.add_response("accept", accept)
    dialog.set_response_appearance("accept", Adw.ResponseAppearance.SUGGESTED)
    dialog.set_default_response("accept")
    dialog.set_close_response("cancel")

    def responded(_dialog, response):
        if response == "accept":
            name = entry.get_text().strip()
            if name:
                on_accept(name)

    dialog.connect("response", responded)
    dialog.present(parent)
    entry.grab_focus()


def choose_playlist(ctx, tracks: list[dict], parent) -> None:
    """Pick a playlist to add `tracks` to, or make a new one."""
    store = ctx.store

    def add_to(playlist_id: int, name: str) -> None:
        added = store.add_many_to_playlist(playlist_id, tracks)
        skipped = len(tracks) - added
        if added and skipped:
            ctx.toast(f"Added {added} to “{name}” · {skipped} already there")
        elif added:
            ctx.toast(f"Added {added} to “{name}”" if added > 1
                      else f"Added to “{name}”")
        else:
            ctx.toast(f"Already in “{name}”")
        ctx.refresh_library()

    dialog = Adw.Dialog()
    dialog.set_title("Add to Playlist")
    dialog.set_content_width(420)
    dialog.set_content_height(460)

    toolbar = Adw.ToolbarView()
    toolbar.add_top_bar(Adw.HeaderBar())

    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
    box.set_margin_top(12)
    box.set_margin_bottom(12)
    box.set_margin_start(12)
    box.set_margin_end(12)

    new_group = Adw.PreferencesGroup()
    new_row = Adw.ActionRow()
    new_row.set_title("New playlist…")
    new_row.add_prefix(Gtk.Image.new_from_icon_name("document-new-symbolic"))
    new_row.set_activatable(True)

    def make_new(_row):
        dialog.close()
        def create(name):
            playlist_id = store.create_playlist(name)
            add_to(playlist_id, name)
        ask_for_name(parent, "New Playlist", "", "Create", create)

    new_row.connect("activated", make_new)
    new_group.add(new_row)
    box.append(new_group)

    existing = store.playlists()
    if existing:
        group = Adw.PreferencesGroup()
        group.set_title("Your playlists")
        for entry in existing:
            row = Adw.ActionRow()
            row.set_title(escape(entry["title"]))
            row.set_subtitle(entry["subtitle"])
            thumb = Thumb(36, "playlist")
            thumb.set_url(entry.get("thumb"))
            row.add_prefix(thumb)
            row.set_activatable(True)
            row.connect("activated",
                        lambda _r, e=entry: (dialog.close(),
                                             add_to(e["playlist_id"], e["title"])))
            group.add(row)
        box.append(group)

    scroller = Gtk.ScrolledWindow()
    scroller.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
    scroller.set_vexpand(True)
    scroller.set_child(box)
    toolbar.set_content(scroller)
    dialog.set_child(toolbar)
    dialog.present(parent)


class LocalPlaylistPage(BasePage):
    """One of your own playlists."""

    def __init__(self, ctx, playlist_id: int):
        super().__init__(ctx, "Playlist")
        self._playlist_id = playlist_id
        self._play_content: Adw.ButtonContent | None = None
        self._handlers: list[int] = []

        menu_button = Gtk.MenuButton()
        menu_button.set_icon_name("view-more-symbolic")
        menu_button.set_tooltip_text("Playlist options")
        popover = Gtk.Popover()
        menu_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        menu_box.set_margin_top(6)
        menu_box.set_margin_bottom(6)
        menu_box.set_margin_start(6)
        menu_box.set_margin_end(6)
        for label, icon, callback in (
                ("Rename…", "document-edit-symbolic", self._rename),
                ("Delete playlist", "user-trash-symbolic", self._delete)):
            button = Gtk.Button()
            content = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
            content.append(Gtk.Image.new_from_icon_name(icon))
            content.append(Gtk.Label(label=label, xalign=0.0))
            button.set_child(content)
            button.add_css_class("flat")
            button.connect("clicked", lambda _b, cb=callback: (popover.popdown(), cb()))
            menu_box.append(button)
        popover.set_child(menu_box)
        menu_button.set_popover(popover)
        self._header.pack_end(menu_button)

        player = self.ctx.player
        self._handlers = [
            player.connect("state-changed", lambda *_: self._sync_play_button()),
            player.connect("track-changed", lambda *_: self._sync_play_button()),
        ]
        self.connect("destroy", self._drop_handlers)
        self.reload()

    def _drop_handlers(self, *_args) -> None:
        for handler in self._handlers:
            self.ctx.player.disconnect(handler)
        self._handlers = []

    # ------------------------------------------------------------------ state

    def _source_id(self) -> str:
        return f"local:{self._playlist_id}"

    def _is_playing_this(self) -> bool:
        return self.ctx.player.source == self._source_id()

    def _sync_play_button(self) -> None:
        if self._play_content is None:
            return
        if self._is_playing_this() and self.ctx.player.playing:
            self._play_content.set_icon_name("media-playback-pause-symbolic")
            self._play_content.set_label("Pause")
        else:
            self._play_content.set_icon_name("media-playback-start-symbolic")
            self._play_content.set_label("Play")

    # ------------------------------------------------------------------ views

    def reload(self) -> None:
        data = self.ctx.store.playlist(self._playlist_id)
        if data is None:
            self.show_error("This playlist no longer exists.")
            return
        self.set_title(data["title"])
        tracks = data["tracks"]

        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=18)
        outer.set_margin_top(12)
        outer.set_margin_bottom(24)
        outer.set_margin_start(12)
        outer.set_margin_end(12)
        outer.append(self._build_header(data, tracks))

        if tracks:
            group = Adw.PreferencesGroup()
            group.set_title("Tracks")
            group.set_description(
                f"{len(tracks)} song{'s' if len(tracks) != 1 else ''}")
            for index, track in enumerate(tracks):
                group.add(self._track_row(track, tracks, index))
            outer.append(group)
        else:
            empty = Adw.StatusPage()
            empty.set_icon_name("view-list-bullet-symbolic")
            empty.set_title("This playlist is empty")
            empty.set_description(
                "Use “Add to playlist” from the ⋮ menu on any song.")
            outer.append(empty)

        self.show_content(self.scrolled(outer))
        self._sync_play_button()

    def _build_header(self, data: dict, tracks: list[dict]) -> Gtk.Widget:
        header = Adw.WrapBox()
        header.set_child_spacing(20)
        header.set_line_spacing(16)

        art = Thumb(180, "playlist")
        art.set_url(data.get("thumb"))
        art.add_css_class("hero-art")
        header.append(art)

        text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        text.set_valign(Gtk.Align.CENTER)
        text.set_hexpand(True)
        text.set_size_request(240, -1)

        kind = Gtk.Label(label="YOUR PLAYLIST", xalign=0.0)
        kind.add_css_class("caption-heading")
        kind.add_css_class("dim-label")
        text.append(kind)

        title = Gtk.Label(label=data["title"], xalign=0.0)
        title.add_css_class("title-1")
        title.set_wrap(True)
        title.set_lines(2)
        title.set_ellipsize(3)
        text.append(title)

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
        shuffle.set_child(Adw.ButtonContent(
            icon_name="media-playlist-shuffle-symbolic", label="Shuffle"))
        shuffle.add_css_class("pill")
        shuffle.set_sensitive(bool(tracks))
        shuffle.connect("clicked", lambda _b: self.ctx.play_tracks(
            tracks, shuffle=True, source=self._source_id()))
        buttons.append(shuffle)

        queue = Gtk.Button.new_from_icon_name("list-add-symbolic")
        queue.add_css_class("pill")
        queue.set_tooltip_text("Add to queue")
        queue.set_sensitive(bool(tracks))
        queue.connect("clicked", lambda _b: self.ctx.enqueue(tracks))
        buttons.append(queue)

        text.append(buttons)
        header.append(text)
        return header

    def _on_play_clicked(self, tracks: list[dict]) -> None:
        if self._is_playing_this():
            self.ctx.player.toggle()
            return
        self.ctx.play_tracks(tracks, 0, shuffle=False, source=self._source_id())

    def _track_row(self, track: dict, tracks: list[dict], index: int):
        row = self.ctx.make_song_row(track, tracks, index)
        # Reordering and removal only make sense inside a playlist you own.
        up = Gtk.Button.new_from_icon_name("go-up-symbolic")
        up.add_css_class("flat")
        up.set_valign(Gtk.Align.CENTER)
        up.set_tooltip_text("Move up")
        up.set_sensitive(index > 0)
        up.connect("clicked", lambda _b: self._move(track["id"], -1))

        down = Gtk.Button.new_from_icon_name("go-down-symbolic")
        down.add_css_class("flat")
        down.set_valign(Gtk.Align.CENTER)
        down.set_tooltip_text("Move down")
        down.set_sensitive(index < len(tracks) - 1)
        down.connect("clicked", lambda _b: self._move(track["id"], 1))

        remove = Gtk.Button.new_from_icon_name("list-remove-symbolic")
        remove.add_css_class("flat")
        remove.set_valign(Gtk.Align.CENTER)
        remove.set_tooltip_text("Remove from playlist")
        remove.connect("clicked", lambda _b: self._remove(track))

        row.add_suffix(up)
        row.add_suffix(down)
        row.add_suffix(remove)
        return row

    # ---------------------------------------------------------------- actions

    def _move(self, video_id: str, delta: int) -> None:
        self.ctx.store.move_track(self._playlist_id, video_id, delta)
        self.reload()

    def _remove(self, track: dict) -> None:
        self.ctx.store.remove_from_playlist(self._playlist_id, track["id"])
        self.ctx.toast(f"Removed “{track['title']}”")
        self.reload()
        self.ctx.refresh_library()

    def _rename(self) -> None:
        data = self.ctx.store.playlist(self._playlist_id)
        if data is None:
            return

        def rename(name):
            self.ctx.store.rename_playlist(self._playlist_id, name)
            self.reload()
            self.ctx.refresh_library()

        ask_for_name(self, "Rename Playlist", data["title"], "Rename", rename)

    def _delete(self) -> None:
        data = self.ctx.store.playlist(self._playlist_id)
        if data is None:
            return
        dialog = Adw.AlertDialog()
        dialog.set_heading("Delete playlist?")
        dialog.set_body(f"“{data['title']}” and its {data['count']} tracks "
                        "will be removed. This cannot be undone.")
        dialog.add_response("cancel", "Cancel")
        dialog.add_response("delete", "Delete")
        dialog.set_response_appearance("delete", Adw.ResponseAppearance.DESTRUCTIVE)
        dialog.set_close_response("cancel")

        def responded(_dialog, response):
            if response != "delete":
                return
            self.ctx.store.delete_playlist(self._playlist_id)
            self.ctx.toast("Playlist deleted")
            self.ctx.refresh_library()
            self.ctx.go_back()

        dialog.connect("response", responded)
        dialog.present(self)
