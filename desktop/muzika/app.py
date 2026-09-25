"""Application entry point."""

from __future__ import annotations

import logging
import sys
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("Gst", "1.0")
from gi.repository import Adw, Gdk, Gio, Gtk  # noqa: E402

from . import tasks  # noqa: E402
from .api import Api  # noqa: E402
from .db import Store  # noqa: E402
from .mpris import MprisServer  # noqa: E402
from .player import Player  # noqa: E402
from .window import MuzikaWindow  # noqa: E402

APP_ID = "lt.a777.Muzika"
VERSION = "1.0.0"

log = logging.getLogger(__name__)


class MuzikaApp(Adw.Application):
    def __init__(self) -> None:
        super().__init__(application_id=APP_ID,
                         flags=Gio.ApplicationFlags.DEFAULT_FLAGS)
        self.api = Api()
        self.store = Store()
        self.player = Player(self.api, self.store)
        self._window: MuzikaWindow | None = None
        self._mpris: MprisServer | None = None

    def do_startup(self) -> None:
        Adw.Application.do_startup(self)
        self._load_css()

        about = Gio.SimpleAction.new("about", None)
        about.connect("activate", lambda *_: self._show_about())
        self.add_action(about)

        quit_action = Gio.SimpleAction.new("quit", None)
        quit_action.connect("activate", lambda *_: self.quit())
        self.add_action(quit_action)
        self.set_accels_for_action("app.quit", ["<Control>q"])

        try:
            self._mpris = MprisServer(self)
        except Exception as exc:  # noqa: BLE001 - MPRIS is a nicety, not a requirement
            log.warning("MPRIS unavailable: %s", exc)

    def do_activate(self) -> None:
        if self._window is None:
            self._window = MuzikaWindow(self, self.api, self.store, self.player)
        self._window.present()

    def do_shutdown(self) -> None:
        self.player.stop()
        if self._mpris is not None:
            self._mpris.shutdown()
        tasks.shutdown()
        Adw.Application.do_shutdown(self)

    def raise_window(self) -> None:
        if self._window is not None:
            self._window.present()

    def _load_css(self) -> None:
        # Shipped inside the package so it is found whether Muzika is run
        # from a checkout or installed into site-packages.
        css_path = Path(__file__).resolve().parent / "data" / "style.css"
        if not css_path.exists():
            log.warning("stylesheet missing at %s", css_path)
            return
        provider = Gtk.CssProvider()
        provider.load_from_path(str(css_path))
        display = Gdk.Display.get_default()
        if display is not None:
            Gtk.StyleContext.add_provider_for_display(
                display, provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)

    def _show_about(self) -> None:
        about = Adw.AboutDialog()
        about.set_application_name("Muzika")
        about.set_application_icon(APP_ID)
        about.set_version(VERSION)
        about.set_developer_name("Built with Claude Code")
        about.set_comments(
            "A GNOME music player for YouTube Music.\n\n"
            "Metadata via ytmusicapi, streams via yt-dlp, playback via GStreamer.")
        about.set_license_type(Gtk.License.GPL_3_0)
        about.set_website("https://github.com/tpsvca/muzika")
        about.present(self._window)


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        datefmt="%H:%M:%S")
    return MuzikaApp().run(sys.argv)


if __name__ == "__main__":
    sys.exit(main())
