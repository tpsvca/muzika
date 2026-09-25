"""The main window - also the context object every page talks to."""

from __future__ import annotations

import time

import weakref

from gi.repository import Adw, Gdk, Gio, GLib, GObject, Gtk

from . import api as api_mod
from . import sources as source_mod
from . import sync as sync_mod
from . import tasks
from .library import LibraryPage
from .pages import DetailPage, ExplorePage, HomePage, MoodPage, SearchPage
from .player_ui import NowPlayingPage, PlayerBar
from .playlists import LocalPlaylistPage, ask_for_name, choose_playlist
from .widgets import SongRow, Thumb, escape

DESTINATIONS = [
    ("home", "Home", "go-home-symbolic"),
    ("explore", "Explore", "view-grid-symbolic"),
    ("search", "Search", "system-search-symbolic"),
    ("library", "Library", "audio-headphones-symbolic"),
]


class MuzikaWindow(Adw.ApplicationWindow):
    def __init__(self, application, api, store, player):
        super().__init__(application=application)
        self.api = api
        self.store = store
        self.player = player
        self._now_playing: NowPlayingPage | None = None
        self._song_rows: list[weakref.ref] = []
        self._export_source = 0
        self._sync_monitor = None
        self._sync_reload_source = 0
        self._own_write_at = 0.0

        self.set_title("Muzika")
        self.set_default_size(1120, 760)
        self.set_icon_name("lt.a777.Muzika")

        self._toasts = Adw.ToastOverlay()
        shell = Adw.ToolbarView()
        self._split = Adw.NavigationSplitView()
        self._split.set_min_sidebar_width(200)
        self._split.set_max_sidebar_width(260)
        self._split.set_sidebar(self._build_sidebar())

        self._nav = Adw.NavigationView()
        content = Adw.NavigationPage()
        content.set_title("Muzika")
        content.set_child(self._nav)
        self._split.set_content(content)

        shell.set_content(self._split)
        self._player_bar = PlayerBar(self)
        shell.add_bottom_bar(self._player_bar)
        self._toasts.set_child(shell)
        self.set_content(self._toasts)

        # Collapse the sidebar on narrow windows, the way GNOME apps do.
        self._pages: dict[str, object] = {}
        self._home = HomePage(self)
        self._explore = ExplorePage(self)
        self._search = SearchPage(self)
        self._library = LibraryPage(self)
        self._pages = {"home": self._home, "explore": self._explore,
                       "search": self._search, "library": self._library}

        self.player.connect("playback-error", lambda _p, message: self.toast(message))
        self.player.connect("track-changed", lambda *_: self._sync_song_rows())
        self.player.connect("track-changed", lambda *_: self.refresh_sidebar())
        self.player.connect("queue-changed", lambda *_: self.refresh_sidebar())
        self._add_breakpoints()
        self._install_shortcuts()
        self.refresh_sidebar()
        if sync_mod.sync_folder() is not None:
            GLib.idle_add(lambda: (self.sync_library(quiet=True), False)[1])
        self.watch_sync_file()
        # Warm the SoundCloud client id so the first search does not pay for it.
        tasks.run_async(source_mod.warm_up, None, lambda _exc: None)
        self.activate_destination("home")

    # ----------------------------------------------------------------- sidebar

    def _build_sidebar(self) -> Adw.NavigationPage:
        page = Adw.NavigationPage()
        page.set_title("Muzika")

        toolbar = Adw.ToolbarView()
        header = Adw.HeaderBar()
        header.set_show_title(True)

        menu = Gio.Menu()
        menu.append("Sync Library Now", "win.sync-now")
        menu.append("Settings", "win.settings")
        menu.append("Keyboard Shortcuts", "win.shortcuts")
        menu.append("About Muzika", "app.about")
        menu_button = Gtk.MenuButton()
        menu_button.set_icon_name("open-menu-symbolic")
        menu_button.set_menu_model(menu)
        menu_button.set_tooltip_text("Main menu")
        header.pack_end(menu_button)
        toolbar.add_top_bar(header)

        self._sidebar_list = Gtk.ListBox()
        self._sidebar_list.add_css_class("navigation-sidebar")
        self._sidebar_list.set_selection_mode(Gtk.SelectionMode.SINGLE)
        for name, label, icon in DESTINATIONS:
            row = Gtk.ListBoxRow()
            row.destination = name
            box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
            box.set_margin_top(10)
            box.set_margin_bottom(10)
            box.set_margin_start(6)
            box.set_margin_end(6)
            box.append(Gtk.Image.new_from_icon_name(icon))
            box.append(Gtk.Label(label=label, xalign=0.0))
            row.set_child(box)
            self._sidebar_list.append(row)
        self._sidebar_list.connect("row-activated", self._on_sidebar_activated)

        # Below the destinations the sidebar would otherwise be a large empty
        # panel, so it carries live context: what is playing, what is next,
        # and a way into lyrics.
        self._sidebar_extra = Adw.Bin()
        self._sidebar_extra.set_margin_top(6)

        column = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        column.append(self._sidebar_list)
        column.append(self._sidebar_extra)

        scroller = Gtk.ScrolledWindow()
        scroller.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        scroller.set_vexpand(True)
        scroller.set_child(column)
        toolbar.set_content(scroller)
        page.set_child(toolbar)
        return page

    def _sidebar_row(self, track: dict, on_activate, thumb_size: int = 32) -> Adw.ActionRow:
        row = Adw.ActionRow()
        row.set_title(escape(track.get("title")))
        if track.get("subtitle"):
            row.set_subtitle(escape(track["subtitle"]))
        row.set_title_lines(1)
        row.set_subtitle_lines(1)
        thumb = Thumb(thumb_size, track.get("kind", "song"))
        thumb.set_url(track.get("thumb"))
        row.add_prefix(thumb)
        row.set_activatable(True)
        row.connect("activated", lambda _r: on_activate())
        return row

    def refresh_sidebar(self) -> None:
        """Rebuild the context panel under the destination list."""
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        box.set_margin_start(6)
        box.set_margin_end(6)
        box.set_margin_bottom(12)

        current = self.player.current
        if current is not None:
            group = Adw.PreferencesGroup()
            group.set_title("Now playing")
            group.add(self._sidebar_row(current, self.open_now_playing, 40))
            box.append(group)

            quick = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
            quick.set_homogeneous(True)
            for label, icon, tab in (("Lyrics", "view-paged-symbolic", "lyrics"),
                                     ("Queue", "view-list-ordered-symbolic", "queue")):
                button = Gtk.Button()
                button.set_child(Adw.ButtonContent(icon_name=icon, label=label))
                button.add_css_class("flat")
                button.connect("clicked",
                               lambda _b, t=tab: self.open_now_playing(tab=t))
                quick.append(button)
            box.append(quick)

            queue = self.player.queue
            position = self.player.queue_position
            upcoming = list(enumerate(queue))[position + 1:position + 5]
            if upcoming:
                nxt = Adw.PreferencesGroup()
                nxt.set_title("Up next")
                for index, track in upcoming:
                    nxt.add(self._sidebar_row(
                        track, lambda i=index: self.player.jump_to(i)))
                box.append(nxt)
        else:
            playlists = self.store.playlists()[:5]
            if playlists:
                group = Adw.PreferencesGroup()
                group.set_title("Your playlists")
                for entry in playlists:
                    group.add(self._sidebar_row(
                        entry,
                        lambda e=entry: self.open_local_playlist(e["playlist_id"])))
                box.append(group)

            recent = self.store.history(limit=4)
            if recent:
                group = Adw.PreferencesGroup()
                group.set_title("Recently played")
                for track in recent:
                    group.add(self._sidebar_row(
                        track, lambda t=track: self.play_tracks([t], 0, shuffle=False)))
                box.append(group)

        self._sidebar_extra.set_child(box)

    def _on_sidebar_activated(self, _list, row) -> None:
        self.activate_destination(row.destination)

    def activate_destination(self, name: str) -> None:
        page = self._pages.get(name)
        if page is None:
            return
        if name == "home":
            page.ensure_loaded()
        elif name == "explore":
            page.ensure_loaded()
        elif name == "library":
            page.reload()
        self._nav.replace([page])
        for index, (dest, _label, _icon) in enumerate(DESTINATIONS):
            if dest == name:
                self._sidebar_list.select_row(self._sidebar_list.get_row_at_index(index))
                break
        if name == "search":
            GLib.idle_add(page.focus_entry)
        if self._split.get_collapsed():
            self._split.set_show_content(True)

    def _add_breakpoints(self) -> None:
        """Set up after the pages exist, since they own widgets we drive here.

        Adwaita applies only the last breakpoint that matches, so each narrower
        one has to repeat everything the wider ones do.
        """
        icons_only = Adw.InlineViewSwitcherDisplayMode.ICONS
        switcher = self._library.switcher

        # Six tabs with icons and labels want ~680px; add the sidebar, header
        # buttons and window controls and that needs a ~1200px window.
        wide = Adw.Breakpoint.new(Adw.BreakpointCondition.parse("max-width: 1200px"))
        wide.add_setter(switcher, "display-mode", icons_only)
        self.add_breakpoint(wide)

        # The full player bar needs ~710px on its own, well before the sidebar
        # has to collapse, so the thresholds are separate.
        medium = Adw.Breakpoint.new(Adw.BreakpointCondition.parse("max-width: 900px"))
        medium.add_setter(switcher, "display-mode", icons_only)
        medium.add_setter(self._player_bar, "compact", True)
        self.add_breakpoint(medium)

        narrow = Adw.Breakpoint.new(Adw.BreakpointCondition.parse("max-width: 720px"))
        narrow.add_setter(switcher, "display-mode", icons_only)
        narrow.add_setter(self._player_bar, "compact", True)
        narrow.add_setter(self._split, "collapsed", True)
        self.add_breakpoint(narrow)

        self._split.connect("notify::collapsed",
                            lambda *_: self._sync_now_playing_compact())

    def _sync_now_playing_compact(self) -> None:
        if self._now_playing is not None:
            self._now_playing.set_compact(self._split.get_collapsed())

    def add_sidebar_button(self, header: Adw.HeaderBar) -> None:
        """A way back to the sidebar once the split view has collapsed.

        The inner NavigationView's own back button pops inner pages, not the
        split view, so without this a narrow window is a dead end.
        """
        button = Gtk.Button.new_from_icon_name("sidebar-show-symbolic")
        button.add_css_class("flat")
        button.set_tooltip_text("Show sidebar")
        button.connect("clicked", lambda _b: self._split.set_show_content(False))
        self._split.bind_property("collapsed", button, "visible",
                                  GObject.BindingFlags.SYNC_CREATE)
        header.pack_start(button)

    # ------------------------------------------------------- context protocol

    def toast(self, message: str) -> None:
        self._toasts.add_toast(Adw.Toast.new(message))

    def open_item(self, item: dict) -> None:
        if item.get("kind") in (api_mod.SONG, api_mod.VIDEO):
            self.play_tracks([item], 0, shuffle=False)
            return
        # An album or artist found on disk has no catalogue page to fetch.
        if item.get("source") == "local":
            from .library import LocalCollectionPage
            self._nav.push(LocalCollectionPage(self, item))
            if self._split.get_collapsed():
                self._split.set_show_content(True)
            return
        if not item.get("id"):
            self.toast("This item cannot be opened.")
            return
        self._nav.push(DetailPage(self, item))
        if self._split.get_collapsed():
            self._split.set_show_content(True)

    def open_mood(self, mood: dict) -> None:
        self._nav.push(MoodPage(self, mood))

    def open_local_playlist(self, playlist_id: int) -> None:
        self._nav.push(LocalPlaylistPage(self, playlist_id))
        if self._split.get_collapsed():
            self._split.set_show_content(True)

    def go_back(self) -> None:
        self._nav.pop()

    def add_to_playlist(self, tracks: list[dict]) -> None:
        playable = [t for t in tracks if t.get("id")]
        if not playable:
            self.toast("Nothing here can be saved.")
            return
        choose_playlist(self, playable, self)

    def new_playlist(self) -> None:
        def create(name):
            playlist_id = self.store.create_playlist(name)
            self.toast(f"Created “{name}”")
            self.refresh_library()
            self.open_local_playlist(playlist_id)
        ask_for_name(self, "New Playlist", "", "Create", create)

    def open_now_playing(self, tab: str = "song") -> None:
        if self._now_playing is None:
            self._now_playing = NowPlayingPage(self)
            self._sync_now_playing_compact()
        if self._now_playing.get_parent() is None:
            self._nav.push(self._now_playing)
        self._now_playing.show_tab(tab)
        if self._split.get_collapsed():
            self._split.set_show_content(True)

    def play_tracks(self, tracks: list[dict], start: int | None = None,
                    shuffle: bool | None = None, source: str | None = None) -> None:
        playable = [t for t in tracks if t.get("id")]
        if not playable:
            self.toast("Nothing here can be played.")
            return
        self.player.set_queue(playable, start, shuffle, source=source)
        if shuffle:
            self.toast(f"Shuffling {len(playable)} songs")

    def enqueue(self, tracks: list[dict]) -> None:
        playable = [t for t in tracks if t.get("id")]
        if not playable:
            return
        self.player.append(playable)
        self.toast(f"Added {len(playable)} to queue")

    def refresh_library(self) -> None:
        self._library.reload()
        self.refresh_sidebar()
        self._schedule_export()

    def _schedule_export(self) -> None:
        """Push the library to the sync folder shortly after it changes.

        Debounced: adding ten songs to a playlist should write the file once,
        not ten times, and a sync client would otherwise see every intermediate
        state.
        """
        if sync_mod.sync_folder() is None:
            return
        if self._export_source:
            GLib.source_remove(self._export_source)
        self._export_source = GLib.timeout_add_seconds(5, self._run_export)

    def _run_export(self) -> bool:
        self._export_source = 0
        folder = sync_mod.sync_folder()
        if folder is not None:
            self._own_write_at = time.time()
            tasks.run_async(lambda: sync_mod.export_library(self.store, folder),
                            None, lambda exc: self.toast(f"Could not write sync file: {exc}"))
        return False

    # ------------------------------------------------- live updates from sync

    def watch_sync_file(self) -> None:
        """Pick up changes made on another device without a restart.

        Syncthing (or whatever carries the folder) rewrites the library file
        when a phone changes something. Watching it means a playlist edited on
        the phone appears here a second later, instead of waiting for the next
        launch.
        """
        if getattr(self, "_sync_monitor", None) is not None:
            self._sync_monitor.cancel()
            self._sync_monitor = None
        folder = sync_mod.sync_folder()
        if folder is None or sync_mod.backend() != sync_mod.BACKEND_FOLDER:
            return
        target = Gio.File.new_for_path(str(folder / sync_mod.FILENAME))
        self._sync_monitor = target.monitor_file(Gio.FileMonitorFlags.NONE, None)
        self._sync_monitor.connect("changed", self._on_sync_file_changed)

    def _on_sync_file_changed(self, _monitor, _file, _other, event) -> None:
        if event not in (Gio.FileMonitorEvent.CHANGES_DONE_HINT,
                         Gio.FileMonitorEvent.CREATED):
            return
        # Our own export rewrites this file; reacting to it would be a loop.
        if time.time() - getattr(self, "_own_write_at", 0.0) < 8:
            return
        if self._sync_reload_source:
            GLib.source_remove(self._sync_reload_source)
        # A sync client can touch the file several times as it lands.
        self._sync_reload_source = GLib.timeout_add(1500, self._reload_from_sync)

    def _reload_from_sync(self) -> bool:
        self._sync_reload_source = 0

        def work():
            return sync_mod.import_library(self.store)

        def done(result):
            if not result.get("ok"):
                return
            changed = (result.get("playlists_added", 0)
                       + result.get("playlists_updated", 0)
                       + result.get("tracks_added", 0)
                       + result.get("favourites_added", 0)
                       + result.get("library_added", 0))
            if not changed:
                return
            self.refresh_library()
            self.toast("Library updated from another device")

        tasks.run_async(work, done, lambda _exc: None)
        return False

    def make_song_row(self, track: dict, siblings: list[dict], position: int,
                      index: int | None = None, show_thumb: bool = True) -> SongRow:
        row = SongRow(track, index=index, show_thumb=show_thumb)
        row.set_favourite(self.store.is_favourite(track["id"]))
        current = self.player.current
        row.set_now_playing(bool(current and current["id"] == track["id"]))
        self._song_rows.append(weakref.ref(row))
        row.connect("play-requested", lambda _r: self.play_tracks(siblings, position))
        row.connect("favourite-toggled", lambda r: self._on_favourite(r))
        row.connect("menu-requested", lambda r: self._show_song_menu(r))
        return row

    def _sync_song_rows(self) -> None:
        """Mark whichever visible rows are the track now playing."""
        current = self.player.current
        current_id = current["id"] if current else None
        alive = []
        for ref in self._song_rows:
            row = ref()
            if row is None:
                continue  # widget was destroyed with its page
            alive.append(ref)
            row.set_now_playing(row.track["id"] == current_id)
        self._song_rows = alive

    def _on_favourite(self, row: SongRow) -> None:
        desired = row.favourite_button.get_active()
        current = self.store.is_favourite(row.track["id"])
        if desired == current:
            return  # programmatic sync, not a user click
        state = self.store.toggle_favourite(row.track)
        row.set_favourite(state)
        self.toast("Added to favourites" if state else "Removed from favourites")
        self._library.reload()

    def _show_song_menu(self, row: SongRow) -> None:
        track = row.track
        popover = Gtk.Popover()
        popover.set_parent(row)
        popover.set_position(Gtk.PositionType.LEFT)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        box.set_margin_top(6)
        box.set_margin_bottom(6)
        box.set_margin_start(6)
        box.set_margin_end(6)

        def entry(label: str, icon: str, callback) -> None:
            button = Gtk.Button()
            content = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
            content.append(Gtk.Image.new_from_icon_name(icon))
            content.append(Gtk.Label(label=label, xalign=0.0))
            button.set_child(content)
            button.add_css_class("flat")
            button.connect("clicked", lambda _b: (popover.popdown(), callback()))
            box.append(button)

        entry("Play next", "media-skip-forward-symbolic",
              lambda: (self.player.play_next(track), self.toast("Playing next")))
        entry("Add to queue", "list-add-symbolic", lambda: self.enqueue([track]))
        entry("Add to playlist…", "view-list-bullet-symbolic",
              lambda: self.add_to_playlist([track]))
        entry("Start radio", "media-playlist-shuffle-symbolic", lambda: self._start_radio(track))
        entry("Copy link", "edit-copy-symbolic", lambda: self._copy_link(track))

        popover.set_child(box)
        popover.connect("closed", lambda p: p.unparent())
        popover.popup()

    def _start_radio(self, track: dict) -> None:
        self.toast("Starting radio…")

        def done(tracks):
            if not tracks:
                self.toast("No radio available for this song.")
                return
            self.play_tracks(tracks, 0, shuffle=False)

        tasks.run_async(lambda: self.api.radio(video_id=track["id"]), done,
                        lambda exc: self.toast(f"Radio failed: {exc}"))

    def _copy_link(self, track: dict) -> None:
        clipboard = Gdk.Display.get_default().get_clipboard()
        clipboard.set(api_mod.watch_url(track))
        self.toast("Link copied")

    # --------------------------------------------------------------- shortcuts

    def _install_shortcuts(self) -> None:
        actions = [
            ("shortcuts", lambda *_: self._show_shortcuts()),
            ("clear-cache", lambda *_: self._clear_cache()),
            ("sync-now", lambda *_: self.sync_library()),
            ("sync-folder", lambda *_: self.choose_sync_folder()),
            ("settings", lambda *_: self.show_settings()),
        ]
        for name, callback in actions:
            action = Gio.SimpleAction.new(name, None)
            action.connect("activate", callback)
            self.add_action(action)

        controller = Gtk.ShortcutController()
        controller.set_scope(Gtk.ShortcutScope.GLOBAL)

        def add(trigger: str, callback) -> None:
            controller.add_shortcut(Gtk.Shortcut.new(
                Gtk.ShortcutTrigger.parse_string(trigger),
                Gtk.CallbackAction.new(lambda *_a, cb=callback: (cb(), True)[1])))

        add("<Control>comma", self.show_settings)
        add("<Control>f", lambda: self.activate_destination("search"))
        add("<Control>h", lambda: self.activate_destination("home"))
        add("<Control>l", lambda: self.activate_destination("library"))
        add("space", self.player.toggle)
        add("<Control>Right", self.player.next)
        add("<Control>Left", self.player.previous)
        add("<Control>s", lambda: setattr(self.player, "shuffle", not self.player.shuffle))
        self.add_controller(controller)

    # -------------------------------------------------------------- settings

    def show_settings(self) -> None:
        from .settings import SettingsDialog
        SettingsDialog(self).present(self)

    # ------------------------------------------------------------------ sync

    def choose_sync_folder(self) -> None:
        dialog = Gtk.FileDialog()
        dialog.set_title("Choose a folder that Syncthing, Dropbox, Nextcloud "
                         "or OpenCloud already syncs")
        current = sync_mod.sync_folder()
        if current and current.exists():
            dialog.set_initial_folder(Gio.File.new_for_path(str(current)))

        def chosen(source, result):
            try:
                folder = source.select_folder_finish(result)
            except Exception:
                return  # dismissed
            if folder is None:
                return
            sync_mod.set_sync_folder(folder.get_path())
            self.toast(f"Sync folder set to {folder.get_basename()}")
            self.sync_library()

        dialog.select_folder(self, None, chosen)

    def sync_library(self, quiet: bool = False) -> None:
        if sync_mod.backend() == sync_mod.BACKEND_DROPBOX:
            from . import dropbox as dropbox_mod

            def work():
                return dropbox_mod.sync(self.store)
        else:
            folder = sync_mod.sync_folder()
            if folder is None:
                if not quiet:
                    self.toast("Choose a sync folder first")
                    self.show_settings()
                return

            def work():
                return sync_mod.sync(self.store, folder)

        def done(result):
            self.refresh_library()
            if quiet:
                return
            if not result.get("ok"):
                self.toast("Library exported (nothing to import yet)")
                return
            bits = []
            if result["playlists_added"]:
                bits.append(f"{result['playlists_added']} new playlists")
            if result["playlists_updated"]:
                bits.append(f"{result['playlists_updated']} updated")
            if result["favourites_added"]:
                bits.append(f"{result['favourites_added']} favourites")
            if result.get("library_added"):
                bits.append(f"{result['library_added']} saved items")
            self.toast("Synced · " + (", ".join(bits) if bits else "already up to date"))

        tasks.run_async(work, done,
                        lambda exc: None if quiet else self.toast(f"Sync failed: {exc}"))

    def _clear_cache(self) -> None:
        from .images import loader
        removed = loader.clear_disk_cache()
        self.api.clear_stream_cache()
        self.toast(f"Cleared {removed} cached images and the stream cache")

    def _show_shortcuts(self) -> None:
        rows = [
            ("Ctrl+F", "Search"), ("Ctrl+H", "Home"), ("Ctrl+L", "Library"),
            ("Space", "Play / Pause"), ("Ctrl+→", "Next song"),
            ("Ctrl+←", "Previous song"), ("Ctrl+S", "Toggle shuffle"),
        ]
        group = Adw.PreferencesGroup()
        for keys, description in rows:
            row = Adw.ActionRow()
            row.set_title(description)
            label = Gtk.Label(label=keys)
            label.add_css_class("dim-label")
            label.add_css_class("numeric")
            row.add_suffix(label)
            group.add(row)

        page = Adw.PreferencesPage()
        page.add(group)
        dialog = Adw.PreferencesDialog()
        dialog.set_title("Keyboard Shortcuts")
        dialog.add(page)
        dialog.present(self)
