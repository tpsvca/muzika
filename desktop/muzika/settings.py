"""The Settings dialog: where the library syncs to, and what music is on disk."""

from __future__ import annotations

import logging
from pathlib import Path

from gi.repository import Adw, Gio, GLib, Gtk

from . import dropbox as dropbox_mod
from . import local as local_mod
from . import sync as sync_mod
from . import tasks

log = logging.getLogger(__name__)

BACKENDS = [
    (sync_mod.BACKEND_FOLDER, "Synced folder",
     "A folder Syncthing, the Dropbox app, Nextcloud or OpenCloud already syncs"),
    (sync_mod.BACKEND_DROPBOX, "Dropbox",
     "Straight to Dropbox over its API, with a token you generate"),
]


class SettingsDialog(Adw.PreferencesDialog):
    """Built fresh each time it is opened, so it always shows current state."""

    def __init__(self, window):
        super().__init__()
        self.window = window
        self.store = window.store
        self.set_title("Settings")
        self.set_search_enabled(False)

        self._music_group: Adw.PreferencesGroup | None = None
        self._share_row: Adw.ActionRow | None = None
        self._scan_row: Adw.ActionRow | None = None

        self.add(self._sync_page())
        self.add(self._music_page())

    # -------------------------------------------------------------- helpers

    def _toast(self, message: str) -> None:
        self.add_toast(Adw.Toast.new(message))

    @staticmethod
    def _button(label: str, callback, css: str | None = None) -> Gtk.Button:
        button = Gtk.Button(label=label)
        button.set_valign(Gtk.Align.CENTER)
        if css:
            button.add_css_class(css)
        button.connect("clicked", lambda *_: callback())
        return button

    # ----------------------------------------------------------- sync page

    def _sync_page(self) -> Adw.PreferencesPage:
        page = Adw.PreferencesPage()
        page.set_title("Sync")
        page.set_icon_name("folder-remote-symbolic")

        group = Adw.PreferencesGroup()
        group.set_title("Where your library travels")
        group.set_description(
            "Playlists, favourites and saved albums are written as one small "
            "JSON file that the phone reads and writes too.")

        self._backend_row = Adw.ComboRow()
        self._backend_row.set_title("Method")
        model = Gtk.StringList()
        for _, label, _description in BACKENDS:
            model.append(label)
        self._backend_row.set_model(model)
        current = sync_mod.backend()
        self._backend_row.set_selected(
            next((i for i, (key, _l, _d) in enumerate(BACKENDS) if key == current), 0))
        self._backend_row.set_subtitle(BACKENDS[self._backend_row.get_selected()][2])
        self._backend_row.connect("notify::selected", self._backend_changed)
        group.add(self._backend_row)

        folder = sync_mod.sync_folder()
        self._folder_row = Adw.ActionRow()
        self._folder_row.set_title("Sync folder")
        self._folder_row.set_subtitle(str(folder) if folder else "Not set")
        self._folder_row.add_suffix(self._button("Choose…", self._choose_folder))
        self._folder_row.set_activatable(True)
        self._folder_row.connect("activated", lambda *_: self._choose_folder())
        group.add(self._folder_row)

        self._token_row = Adw.PasswordEntryRow()
        self._token_row.set_title("Dropbox access token")
        self._token_row.set_text(sync_mod.get("dropbox_token") or "")
        self._token_row.connect("apply", self._save_token)
        self._token_row.set_show_apply_button(True)
        group.add(self._token_row)

        self._dropbox_row = Adw.ActionRow()
        self._dropbox_row.set_title("Test the Dropbox connection")
        self._dropbox_row.set_subtitle(
            "Dropbox has no anonymous mode, so it needs a token you generate")
        self._dropbox_row.add_suffix(self._button("Test", self._test_dropbox))
        group.add(self._dropbox_row)

        help_row = Adw.ActionRow()
        help_row.set_title("How to get a token")
        help_row.set_subtitle(
            "App Console → Create app → Scoped access → App folder → "
            "Permissions: files.content.read + files.content.write → "
            "Generate access token")
        help_row.add_suffix(self._button(
            "Open", lambda: Gtk.UriLauncher.new(
                "https://www.dropbox.com/developers/apps").launch(self.window, None, None)))
        group.add(help_row)
        page.add(group)

        behaviour = Adw.PreferencesGroup()
        behaviour.set_title("Behaviour")

        self._auto_row = Adw.SwitchRow()
        self._auto_row.set_title("Sync when Muzika starts")
        self._auto_row.set_subtitle("Pull in changes made on the other devices")
        self._auto_row.set_active(bool(sync_mod.get("auto_sync", True)))
        self._auto_row.connect(
            "notify::active",
            lambda row, _p: sync_mod.put("auto_sync", row.get_active()))
        behaviour.add(self._auto_row)

        now_row = Adw.ActionRow()
        now_row.set_title("Sync now")
        now_row.add_suffix(self._button("Sync", self._sync_now, "suggested-action"))
        behaviour.add(now_row)
        page.add(behaviour)

        self._update_backend_rows()
        return page

    def _backend_changed(self, row, _param) -> None:
        key = BACKENDS[row.get_selected()][0]
        sync_mod.put("sync_backend", key)
        self.window.watch_sync_file()
        row.set_subtitle(BACKENDS[row.get_selected()][2])
        self._update_backend_rows()

    def _update_backend_rows(self) -> None:
        dropbox = sync_mod.backend() == sync_mod.BACKEND_DROPBOX
        self._folder_row.set_visible(not dropbox)
        self._token_row.set_visible(dropbox)
        self._dropbox_row.set_visible(dropbox)
        # The music still travels through a folder even on the Dropbox backend,
        # so say so rather than hiding the setting entirely.
        if self._share_row is not None:
            self._refresh_share_row()

    def _save_token(self, row) -> None:
        sync_mod.put("dropbox_token", row.get_text().strip() or None)
        self._toast("Token saved" if row.get_text().strip() else "Token cleared")

    def _test_dropbox(self) -> None:
        tasks.run_async(
            dropbox_mod.account_name,
            lambda name: self._toast(f"Connected as {name}"),
            lambda exc: self._toast(str(exc)))

    def _choose_folder(self) -> None:
        dialog = Gtk.FileDialog()
        dialog.set_title("Choose a folder your sync service already watches")
        current = sync_mod.sync_folder()
        if current and current.exists():
            dialog.set_initial_folder(Gio.File.new_for_path(str(current)))

        def chosen(source, result):
            try:
                folder = source.select_folder_finish(result)
            except GLib.Error:
                return
            if folder is None:
                return
            sync_mod.set_sync_folder(folder.get_path())
            self._folder_row.set_subtitle(folder.get_path())
            self.window.watch_sync_file()
            self._refresh_share_row()
            self._toast(f"Sync folder set to {folder.get_basename()}")

        dialog.select_folder(self.window, None, chosen)

    def _sync_now(self) -> None:
        self.window.sync_library()
        self._toast("Syncing…")

    # ---------------------------------------------------------- music page

    def _music_page(self) -> Adw.PreferencesPage:
        page = Adw.PreferencesPage()
        page.set_title("Music")
        page.set_icon_name("folder-music-symbolic")

        self._music_group = Adw.PreferencesGroup()
        self._music_group.set_title("Music on this computer")
        self._music_group.set_description(
            "Point Muzika at folders of audio files and it reads their tags. "
            "Works for anything already synced onto this machine, including a "
            "Dropbox or Nextcloud folder.")
        add_button = self._button("Add folder…", self._add_folder, "suggested-action")
        self._music_group.set_header_suffix(add_button)
        page.add(self._music_group)
        self._fill_music_group()

        share_group = Adw.PreferencesGroup()
        share_group.set_title("Getting it onto the phone")
        share_group.set_description(
            "Muzika copies the audio into the sync folder; whichever service "
            "you run carries the files. Your originals are left where they are.")

        self._share_row = Adw.ActionRow()
        self._share_row.set_title("Copy music into the sync folder")
        self._share_row.add_suffix(self._button("Copy", self._share_music))
        share_group.add(self._share_row)
        self._refresh_share_row()
        page.add(share_group)

        storage = Adw.PreferencesGroup()
        storage.set_title("Storage")
        cache_row = Adw.ActionRow()
        cache_row.set_title("Clear cached artwork and streams")
        cache_row.add_suffix(self._button("Clear", self._clear_cache))
        storage.add(cache_row)

        history_row = Adw.ActionRow()
        history_row.set_title("Clear listening history")
        history_row.set_subtitle("Playlists and favourites are kept")
        history_row.add_suffix(self._button("Clear", self._clear_history, "destructive-action"))
        storage.add(history_row)
        page.add(storage)
        return page

    def _fill_music_group(self) -> None:
        assert self._music_group is not None
        for row in list(getattr(self, "_music_rows", [])):
            self._music_group.remove(row)
        self._music_rows: list[Gtk.Widget] = []

        folders = sync_mod.music_folders()
        if not folders:
            empty = Adw.ActionRow()
            empty.set_title("No music folders yet")
            empty.set_subtitle("Add one and Muzika will index it")
            self._music_group.add(empty)
            self._music_rows.append(empty)
        else:
            for folder in folders:
                row = Adw.ActionRow()
                row.set_title(Path(folder).name or folder)
                count = len(self.store.local_tracks())
                row.set_subtitle(folder)
                remove = Gtk.Button.new_from_icon_name("user-trash-symbolic")
                remove.set_valign(Gtk.Align.CENTER)
                remove.add_css_class("flat")
                remove.set_tooltip_text("Remove this folder")
                remove.connect("clicked", lambda *_a, f=folder: self._remove_folder(f))
                row.add_suffix(remove)
                self._music_group.add(row)
                self._music_rows.append(row)

        scan = Adw.ActionRow()
        total = self.store.local_count()
        scan.set_title("Rescan")
        scan.set_subtitle(
            f"{total} song{'s' if total != 1 else ''} indexed"
            if total else "Nothing indexed yet")
        scan.add_suffix(self._button("Rescan", self._rescan))
        self._music_group.add(scan)
        self._music_rows.append(scan)
        self._scan_row = scan

    def _add_folder(self) -> None:
        dialog = Gtk.FileDialog()
        dialog.set_title("Choose a folder of music")
        music = Path.home() / "Music"
        if music.is_dir():
            dialog.set_initial_folder(Gio.File.new_for_path(str(music)))

        def chosen(source, result):
            try:
                folder = source.select_folder_finish(result)
            except GLib.Error:
                return
            if folder is None:
                return
            path = folder.get_path()
            if not sync_mod.add_music_folder(path):
                self._toast("That folder is already in the list")
                return
            self._fill_music_group()
            self._scan_one(path)

        dialog.select_folder(self.window, None, chosen)

    def _remove_folder(self, folder: str) -> None:
        sync_mod.remove_music_folder(folder)
        self.store.forget_local_root(folder)
        self._fill_music_group()
        self._refresh_share_row()
        self.window.refresh_library()
        self._toast("Folder removed — the files themselves are untouched")

    def _scan_one(self, folder: str) -> None:
        self._toast(f"Scanning {Path(folder).name}…")

        def work():
            return folder, local_mod.scan(folder)

        def done(result):
            root, tracks = result
            count = self.store.replace_local_tracks(root, tracks)
            self._fill_music_group()
            self._refresh_share_row()
            self.window.refresh_library()
            self._toast(f"Indexed {count} song{'s' if count != 1 else ''}")

        tasks.run_async(work, done, lambda exc: self._toast(f"Scan failed: {exc}"))

    def _rescan(self) -> None:
        folders = sync_mod.music_folders()
        if not folders:
            self._toast("Add a music folder first")
            return
        self._toast("Rescanning…")

        def work():
            return [(f, local_mod.scan(f)) for f in folders]

        def done(results):
            total = sum(self.store.replace_local_tracks(root, tracks)
                        for root, tracks in results)
            self._fill_music_group()
            self._refresh_share_row()
            self.window.refresh_library()
            self._toast(f"Indexed {total} song{'s' if total != 1 else ''}")

        tasks.run_async(work, done, lambda exc: self._toast(f"Scan failed: {exc}"))

    # -------------------------------------------------------------- sharing

    def _refresh_share_row(self) -> None:
        if self._share_row is None:
            return
        destination = sync_mod.shared_music_dir()
        folders = sync_mod.music_folders()
        if destination is None:
            self._share_row.set_subtitle("Choose a sync folder first")
            self._share_row.set_sensitive(False)
            return
        if not folders:
            self._share_row.set_subtitle("Add a music folder first")
            self._share_row.set_sensitive(False)
            return
        self._share_row.set_sensitive(True)

        def work():
            return local_mod.share_plan(folders, destination)

        def done(result):
            pending, total = result
            if not pending:
                self._share_row.set_subtitle(f"Up to date in {destination}")
            else:
                self._share_row.set_subtitle(
                    f"{len(pending)} file{'s' if len(pending) != 1 else ''} "
                    f"({local_mod.human_size(total)}) to copy into {destination}")

        tasks.run_async(work, done, lambda exc: log.warning("share plan failed: %s", exc))

    def _share_music(self) -> None:
        destination = sync_mod.shared_music_dir()
        folders = sync_mod.music_folders()
        if destination is None or not folders:
            return
        self._share_row.set_sensitive(False)
        self._toast("Copying music into the sync folder…")

        def work():
            destination.mkdir(parents=True, exist_ok=True)
            return local_mod.share(folders, destination)

        def done(result):
            copied, failed = result
            self._share_row.set_sensitive(True)
            self._refresh_share_row()
            if failed:
                self._toast(f"Copied {copied}, {failed} failed")
            elif copied:
                self._toast(f"Copied {copied} file{'s' if copied != 1 else ''} — "
                            "your sync service will carry them")
            else:
                self._toast("Already up to date")

        def failed(exc):
            self._share_row.set_sensitive(True)
            self._toast(f"Copy failed: {exc}")

        tasks.run_async(work, done, failed)

    # -------------------------------------------------------------- storage

    def _clear_cache(self) -> None:
        from .images import loader
        removed = loader.clear_disk_cache()
        self._toast(f"Cleared {removed} cached images and the stream cache")

    def _clear_history(self) -> None:
        self.store.clear_history()
        self.window.refresh_library()
        self._toast("History cleared")
