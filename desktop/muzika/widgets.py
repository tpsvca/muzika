"""Reusable widgets: thumbnails, song rows, tiles and shelves."""

from __future__ import annotations

from gi.repository import Adw, GLib, GObject, Gtk

from . import api as api_mod
from .images import loader

PLACEHOLDER = {
    api_mod.SONG: "audio-x-generic-symbolic",
    api_mod.VIDEO: "video-x-generic-symbolic",
    api_mod.ALBUM: "media-optical-symbolic",
    api_mod.ARTIST: "avatar-default-symbolic",
    api_mod.PLAYLIST: "view-list-symbolic",
}


def escape(text: str | None) -> str:
    return GLib.markup_escape_text(text or "")


def format_duration(seconds: int) -> str:
    if not seconds:
        return ""
    minutes, secs = divmod(int(seconds), 60)
    hours, minutes = divmod(minutes, 60)
    if hours:
        return f"{hours}:{minutes:02d}:{secs:02d}"
    return f"{minutes}:{secs:02d}"


class Thumb(Gtk.Frame):
    """Square rounded artwork with a symbolic placeholder until it loads."""

    def __init__(self, size: int = 48, kind: str = api_mod.SONG, round_full: bool = False):
        super().__init__()
        self.add_css_class("thumb")
        if round_full:
            self.add_css_class("thumb-round")
        self.set_size_request(size, size)
        self.set_halign(Gtk.Align.CENTER)
        self.set_valign(Gtk.Align.CENTER)
        self._size = size
        self._icon = Gtk.Image.new_from_icon_name(PLACEHOLDER.get(kind, "audio-x-generic-symbolic"))
        self._icon.set_pixel_size(max(16, size // 2))
        self._icon.add_css_class("dim-label")
        self._picture = Gtk.Picture()
        self._picture.set_content_fit(Gtk.ContentFit.COVER)
        self._picture.set_size_request(size, size)
        self._stack = Gtk.Stack()
        self._stack.add_named(self._icon, "placeholder")
        self._stack.add_named(self._picture, "image")
        self.set_child(self._stack)
        self.set_overflow(Gtk.Overflow.HIDDEN)

    def do_measure(self, orientation, for_size):
        # Without this the widget inherits Gtk.Picture's natural size, which is
        # the source image's - so a 16:9 thumbnail renders as a wide rectangle.
        return (self._size, self._size, -1, -1)

    def set_url(self, url: str | None) -> None:
        self._stack.set_visible_child_name("placeholder")
        token = object()
        self._token = token

        def done(texture):
            if getattr(self, "_token", None) is not token:
                return  # row was recycled for another item
            if texture is not None:
                self._picture.set_paintable(texture)
                self._stack.set_visible_child_name("image")

        loader.load(url, done)


class SongRow(Adw.ActionRow):
    """One track. Activating plays it; the suffix carries favourite + menu."""

    __gsignals__ = {
        "play-requested": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "menu-requested": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "favourite-toggled": (GObject.SignalFlags.RUN_FIRST, None, ()),
    }

    def __init__(self, track: dict, index: int | None = None, show_thumb: bool = True):
        super().__init__()
        self.track = track
        self.set_activatable(True)
        self.set_title(escape(track.get("title")))
        subtitle = track.get("subtitle") or track.get("artists") or ""
        if subtitle:
            self.set_subtitle(escape(subtitle))
        self.set_title_lines(1)
        self.set_subtitle_lines(1)

        if index is not None:
            number = Gtk.Label(label=str(index))
            number.add_css_class("dim-label")
            number.add_css_class("numeric")
            number.set_width_chars(2)
            number.set_xalign(1.0)
            self.add_prefix(number)
        if show_thumb:
            self._thumb = Thumb(44, track.get("kind", api_mod.SONG))
            self._thumb.set_url(track.get("thumb"))
            self.add_prefix(self._thumb)

        self._playing = Gtk.Image.new_from_icon_name("media-playback-start-symbolic")
        self._playing.add_css_class("accent")
        self._playing.set_visible(False)
        self.add_prefix(self._playing)

        duration = format_duration(track.get("duration", 0))
        self._duration = Gtk.Label(label=duration)
        self._duration.add_css_class("dim-label")
        self._duration.add_css_class("numeric")
        self.add_suffix(self._duration)

        self._favourite = Gtk.ToggleButton()
        self._favourite.set_icon_name("starred-symbolic")
        self._favourite.add_css_class("flat")
        self._favourite.set_valign(Gtk.Align.CENTER)
        self._favourite.set_tooltip_text("Add to favourites")
        self._favourite.connect("toggled", lambda _b: self.emit("favourite-toggled"))
        self.add_suffix(self._favourite)

        menu = Gtk.Button.new_from_icon_name("view-more-symbolic")
        menu.add_css_class("flat")
        menu.set_valign(Gtk.Align.CENTER)
        menu.set_tooltip_text("More options")
        menu.connect("clicked", lambda _b: self.emit("menu-requested"))
        self.add_suffix(menu)

        self.connect("activated", lambda _r: self.emit("play-requested"))

    def set_favourite(self, value: bool) -> None:
        with_block = self._favourite.handler_block_by_func if False else None
        self._favourite.set_active(value)
        self._favourite.set_icon_name("starred-symbolic" if value else "non-starred-symbolic")
        self._favourite.set_tooltip_text("Remove from favourites" if value else "Add to favourites")

    @property
    def favourite_button(self) -> Gtk.ToggleButton:
        return self._favourite

    def set_now_playing(self, value: bool) -> None:
        self._playing.set_visible(value)
        if value:
            self.add_css_class("now-playing")
        else:
            self.remove_css_class("now-playing")


class Tile(Gtk.Button):
    """A playlist / album / artist tile for the shelves."""

    def __init__(self, item: dict, size: int = 150):
        super().__init__()
        self.item = item
        self.add_css_class("flat")
        self.add_css_class("tile")
        self.set_has_frame(False)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        box.set_size_request(size, -1)
        thumb = Thumb(size, item.get("kind", api_mod.PLAYLIST),
                      round_full=item.get("kind") == api_mod.ARTIST)
        thumb.set_url(item.get("thumb"))
        box.append(thumb)

        title = Gtk.Label(label=item.get("title") or "")
        title.set_wrap(True)
        title.set_lines(2)
        title.set_ellipsize(3)  # Pango.EllipsizeMode.END
        title.set_justify(Gtk.Justification.CENTER)
        title.set_max_width_chars(18)
        title.add_css_class("heading")
        title.set_size_request(-1, 40)
        title.set_valign(Gtk.Align.START)
        box.append(title)

        subtitle_text = item.get("subtitle") or ""
        if subtitle_text:
            subtitle = Gtk.Label(label=subtitle_text)
            subtitle.set_ellipsize(3)
            subtitle.set_max_width_chars(20)
            subtitle.add_css_class("caption")
            subtitle.add_css_class("dim-label")
            box.append(subtitle)

        self.set_child(box)
        self.set_tooltip_text(item.get("title") or "")


class Shelf(Gtk.Box):
    """A titled horizontal row of tiles, as on the YouTube Music home page."""

    def __init__(self, title: str, items: list[dict], on_activate, tile_size: int = 150):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.add_css_class("shelf")

        heading = Gtk.Label(label=title, xalign=0.0)
        heading.add_css_class("title-4")
        heading.set_margin_start(4)
        self.append(heading)

        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        for item in items:
            tile = Tile(item, tile_size)
            tile.connect("clicked", lambda _b, it=item: on_activate(it))
            row.append(tile)

        scroller = Gtk.ScrolledWindow()
        scroller.set_policy(Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.NEVER)
        scroller.set_child(row)
        scroller.set_propagate_natural_height(True)
        # Artwork + two title lines + a subtitle. Without this the row is sized
        # from the first tile and taller tiles get their subtitle clipped.
        scroller.set_min_content_height(tile_size + 96)
        self.append(scroller)


class LoadingView(Adw.Bin):
    """Spinner shown while a page fetches its contents."""

    def __init__(self, message: str = "Loading…"):
        super().__init__()
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        box.set_valign(Gtk.Align.CENTER)
        box.set_halign(Gtk.Align.CENTER)
        box.set_vexpand(True)
        spinner = Adw.Spinner()
        spinner.set_size_request(42, 42)
        box.append(spinner)
        label = Gtk.Label(label=message)
        label.add_css_class("dim-label")
        box.append(label)
        self.set_child(box)


def error_view(message: str, on_retry=None) -> Adw.StatusPage:
    text = " ".join(str(message).split())
    if "503" in text or "Service Unavailable" in text:
        text = "YouTube Music is temporarily unavailable. Try again in a moment."
    elif len(text) > 180:
        text = text[:177] + "\u2026"
    page = Adw.StatusPage()
    page.set_icon_name("network-offline-symbolic")
    page.set_title("Something went wrong")
    page.set_description(text)
    if on_retry is not None:
        button = Gtk.Button(label="Try again")
        button.add_css_class("pill")
        button.add_css_class("suggested-action")
        button.set_halign(Gtk.Align.CENTER)
        button.connect("clicked", lambda _b: on_retry())
        page.set_child(button)
    return page
