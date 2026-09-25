"""Player chrome: the persistent bottom bar and the Now Playing page."""

from __future__ import annotations

import gi

gi.require_version("Gst", "1.0")
from gi.repository import Adw, GLib, GObject, Gst, Gtk  # noqa: E402

from . import tasks  # noqa: E402
from .player import REPEAT_ALL, REPEAT_NONE, REPEAT_ONE  # noqa: E402
from .widgets import Thumb, escape, format_duration  # noqa: E402

REPEAT_CYCLE = {REPEAT_NONE: REPEAT_ALL, REPEAT_ALL: REPEAT_ONE, REPEAT_ONE: REPEAT_NONE}
REPEAT_ICON = {
    REPEAT_NONE: "media-playlist-repeat-symbolic",
    REPEAT_ALL: "media-playlist-repeat-symbolic",
    REPEAT_ONE: "media-playlist-repeat-song-symbolic",
}
REPEAT_TOOLTIP = {
    REPEAT_NONE: "Repeat: off",
    REPEAT_ALL: "Repeat: all",
    REPEAT_ONE: "Repeat: this song",
}


class SeekBar(Gtk.Box):
    """Position slider that does not fight the user while they drag it."""

    def __init__(self, player, compact: bool = True):
        super().__init__(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self._player = player
        self._seeking = False
        self._pending: float | None = None
        self._commit_source = 0
        self._release_source = 0

        self._elapsed = Gtk.Label(label="0:00")
        self._elapsed.add_css_class("numeric")
        self._elapsed.add_css_class("caption")
        self._elapsed.add_css_class("dim-label")
        self._elapsed.set_width_chars(5)

        self._total = Gtk.Label(label="0:00")
        self._total.add_css_class("numeric")
        self._total.add_css_class("caption")
        self._total.add_css_class("dim-label")
        self._total.set_width_chars(5)

        self._scale = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0.0, 1.0, 0.001)
        self._scale.set_draw_value(False)
        self._scale.set_hexpand(True)

        # "change-value" covers dragging, clicking the trough, arrow keys and
        # scrolling. A Gtk.GestureClick added here would never see "released",
        # because Gtk.Scale's own drag gesture claims the event sequence.
        self._scale.connect("change-value", self._on_change_value)

        self.append(self._elapsed)
        self.append(self._scale)
        self.append(self._total)
        if compact:
            self.add_css_class("seek-compact")

    def _on_change_value(self, _scale, _scroll, value) -> bool:
        """Follow the handle immediately, seek once the user settles."""
        value = max(0.0, min(1.0, float(value)))
        self._seeking = True
        self._pending = value
        _, duration = self._player.position_duration()
        if duration:
            self._elapsed.set_label(format_duration(int(duration * value / Gst.SECOND)))
        # Debounced: dragging emits this continuously and flushing a seek per
        # pixel would thrash the pipeline.
        if self._commit_source:
            GLib.source_remove(self._commit_source)
        self._commit_source = GLib.timeout_add(120, self._commit_seek)
        return False

    def _commit_seek(self) -> bool:
        self._commit_source = 0
        if self._pending is not None:
            self._player.seek_fraction(self._pending)
            self._pending = None
        # Give the pipeline a moment to report the new position before the
        # bar starts following playback again, or it visibly snaps back.
        if self._release_source:
            GLib.source_remove(self._release_source)
        self._release_source = GLib.timeout_add(350, self._end_seek)
        return False

    def _end_seek(self) -> bool:
        self._release_source = 0
        self._seeking = False
        return False

    def update(self, position: int, duration: int) -> None:
        if duration > 0:
            self._total.set_label(format_duration(duration // Gst.SECOND))
            if not self._seeking:
                self._scale.set_value(position / duration)
                self._elapsed.set_label(format_duration(position // Gst.SECOND))
            self._scale.set_sensitive(True)
        else:
            self._scale.set_value(0.0)
            self._scale.set_sensitive(False)
            self._elapsed.set_label("0:00")
            self._total.set_label("0:00")


class PlayerBar(Gtk.Box):
    """Always-visible transport at the bottom of the window."""

    def __init__(self, ctx):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.ctx = ctx
        self._player = ctx.player
        self.add_css_class("player-bar")

        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        row.set_margin_top(8)
        row.set_margin_bottom(8)
        row.set_margin_start(12)
        row.set_margin_end(12)

        # --- current track, click to open Now Playing
        self._thumb = Thumb(48)
        info = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        info.set_valign(Gtk.Align.CENTER)
        self._title = Gtk.Label(label="Nothing playing", xalign=0.0)
        self._title.add_css_class("heading")
        self._title.set_ellipsize(3)
        self._title.set_max_width_chars(24)
        self._artist = Gtk.Label(label="", xalign=0.0)
        self._artist.add_css_class("caption")
        self._artist.add_css_class("dim-label")
        self._artist.set_ellipsize(3)
        self._artist.set_max_width_chars(24)
        info.append(self._title)
        info.append(self._artist)

        identity = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        identity.append(self._thumb)
        identity.append(info)
        opener = Gtk.Button()
        opener.set_child(identity)
        opener.add_css_class("flat")
        opener.set_tooltip_text("Open now playing")
        opener.connect("clicked", lambda _b: self.ctx.open_now_playing())
        opener.set_size_request(150, -1)
        opener.set_hexpand(True)
        self._opener = opener
        row.append(opener)

        # --- transport
        controls = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=4)
        controls.set_halign(Gtk.Align.CENTER)
        controls.set_valign(Gtk.Align.CENTER)

        self._shuffle = Gtk.ToggleButton.new()
        self._shuffle.set_icon_name("media-playlist-shuffle-symbolic")
        self._shuffle.add_css_class("flat")
        self._shuffle.set_tooltip_text("Shuffle")
        self._shuffle.connect("toggled",
                              lambda b: setattr(self._player, "shuffle", b.get_active()))
        controls.append(self._shuffle)

        previous = Gtk.Button.new_from_icon_name("media-skip-backward-symbolic")
        previous.add_css_class("flat")
        previous.set_tooltip_text("Previous")
        previous.connect("clicked", lambda _b: self._player.previous())
        controls.append(previous)

        self._play = Gtk.Button.new_from_icon_name("media-playback-start-symbolic")
        self._play.add_css_class("circular")
        self._play.add_css_class("suggested-action")
        self._play.set_tooltip_text("Play")
        self._play.connect("clicked", lambda _b: self._player.toggle())
        controls.append(self._play)

        next_button = Gtk.Button.new_from_icon_name("media-skip-forward-symbolic")
        next_button.add_css_class("flat")
        next_button.set_tooltip_text("Next")
        next_button.connect("clicked", lambda _b: self._player.next())
        controls.append(next_button)

        self._repeat = Gtk.Button.new_from_icon_name(REPEAT_ICON[REPEAT_NONE])
        self._repeat.add_css_class("flat")
        self._repeat.set_tooltip_text(REPEAT_TOOLTIP[REPEAT_NONE])
        self._repeat.connect("clicked", self._cycle_repeat)
        controls.append(self._repeat)
        self._controls = controls
        row.append(controls)

        # --- seek bar
        self._seek = SeekBar(self._player)
        self._seek.set_hexpand(True)
        self._seek.set_valign(Gtk.Align.CENTER)
        row.append(self._seek)

        # --- right-hand actions
        actions = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=4)
        actions.set_valign(Gtk.Align.CENTER)
        self._favourite = Gtk.ToggleButton()
        self._favourite.set_icon_name("non-starred-symbolic")
        self._favourite.add_css_class("flat")
        self._favourite.set_tooltip_text("Add to favourites")
        self._favourite.connect("clicked", self._toggle_favourite)
        actions.append(self._favourite)

        queue = Gtk.Button.new_from_icon_name("view-list-ordered-symbolic")
        queue.add_css_class("flat")
        queue.set_tooltip_text("Queue")
        queue.connect("clicked", lambda _b: self.ctx.open_now_playing(tab="queue"))
        actions.append(queue)

        self._volume = Gtk.VolumeButton()
        self._volume.set_value(1.0)
        self._volume.add_css_class("flat")
        self._volume.connect("value-changed",
                             lambda _b, value: setattr(self._player, "volume", value))
        actions.append(self._volume)
        self._actions = actions
        row.append(actions)

        self._progress = Gtk.ProgressBar()
        self._progress.add_css_class("mini-progress")
        self._progress.set_visible(False)

        self.append(Gtk.Separator())
        self.append(self._progress)
        self.append(row)
        self._compact = False

        self._player.connect("track-changed", lambda *_: self.refresh_track())
        self._player.connect("state-changed", lambda *_: self.refresh_state())
        self._player.connect("queue-changed", lambda *_: self.refresh_state())
        self._player.connect("position-changed", self._on_position)
        self.refresh_track()
        self.refresh_state()

    def _on_position(self, _player, position: int, duration: int) -> None:
        self._seek.update(position, duration)
        if self._compact:
            self._progress.set_fraction(position / duration if duration > 0 else 0.0)

    @GObject.Property(type=bool, default=False)
    def compact(self) -> bool:
        return self._compact

    @compact.setter  # type: ignore[no-redef]
    def compact(self, value: bool) -> None:
        self.set_compact(value)

    def set_compact(self, compact: bool) -> None:
        """Narrow windows get a mini player: art, title, and play/pause.

        Everything dropped here is still reachable by tapping the mini player,
        which opens Now Playing with its own controls, lyrics and queue.
        A property so Adw.Breakpoint can drive it and restore it on its own.
        """
        if compact == self._compact:
            return
        self._compact = compact
        self._seek.set_visible(not compact)
        self._actions.set_visible(not compact)
        self._shuffle.set_visible(not compact)
        self._repeat.set_visible(not compact)
        self._progress.set_visible(compact)
        self._opener.set_size_request(-1 if compact else 150, -1)

    def _cycle_repeat(self, _button) -> None:
        mode = REPEAT_CYCLE[self._player.repeat]
        self._player.repeat = mode
        self._repeat.set_icon_name(REPEAT_ICON[mode])
        self._repeat.set_tooltip_text(REPEAT_TOOLTIP[mode])
        if mode == REPEAT_NONE:
            self._repeat.remove_css_class("accent")
        else:
            self._repeat.add_css_class("accent")

    def _toggle_favourite(self, _button) -> None:
        track = self._player.current
        if not track:
            return
        state = self.ctx.store.toggle_favourite(track)
        self._sync_favourite(state)
        self.ctx.toast("Added to favourites" if state else "Removed from favourites")
        self.ctx.refresh_library()

    def _sync_favourite(self, state: bool) -> None:
        self._favourite.set_icon_name("starred-symbolic" if state else "non-starred-symbolic")
        self._favourite.set_tooltip_text(
            "Remove from favourites" if state else "Add to favourites")

    def refresh_track(self) -> None:
        track = self._player.current
        if track is None:
            self._title.set_label("Nothing playing")
            self._artist.set_label("")
            self._thumb.set_url(None)
            self._favourite.set_sensitive(False)
            return
        self._favourite.set_sensitive(True)
        self._title.set_label(track.get("title") or "")
        self._artist.set_label(track.get("subtitle") or "")
        self._thumb.set_url(track.get("thumb"))
        self._sync_favourite(self.ctx.store.is_favourite(track["id"]))

    def refresh_state(self) -> None:
        playing = self._player.playing
        if self._player.loading:
            self._play.set_icon_name("content-loading-symbolic")
            self._play.set_tooltip_text("Loading…")
        else:
            self._play.set_icon_name(
                "media-playback-pause-symbolic" if playing else "media-playback-start-symbolic")
            self._play.set_tooltip_text("Pause" if playing else "Play")
        if self._shuffle.get_active() != self._player.shuffle:
            self._shuffle.set_active(self._player.shuffle)


class NowPlayingPage(Adw.NavigationPage):
    """Big artwork, transport, lyrics and the queue."""

    def __init__(self, ctx):
        super().__init__()
        self.ctx = ctx
        self._player = ctx.player
        self.set_title("Now Playing")
        self._lyrics_for: str | None = None
        self._lyric_lines: list[tuple[int, str]] | None = None
        self._lyric_labels: list[Gtk.Label] = []
        self._lyric_index = -1
        self._lyric_box: Gtk.Widget | None = None
        self._lyric_scroller: Gtk.ScrolledWindow | None = None

        toolbar = Adw.ToolbarView()
        header = Adw.HeaderBar()
        self._stack = Adw.ViewStack()
        switcher = Adw.ViewSwitcher()
        switcher.set_stack(self._stack)
        switcher.set_policy(Adw.ViewSwitcherPolicy.WIDE)
        header.set_title_widget(switcher)
        self._switcher = switcher
        toolbar.add_top_bar(header)

        # A wide switcher does not fit a phone-width window; the same stack
        # gets a bottom bar instead.
        self._switcher_bar = Adw.ViewSwitcherBar()
        self._switcher_bar.set_stack(self._stack)
        toolbar.add_bottom_bar(self._switcher_bar)

        page = self._stack.add_titled(self._build_song(), "song", "Song")
        page.set_icon_name("audio-x-generic-symbolic")
        self._lyrics_slot = Adw.Bin()
        self._lyrics_slot.set_vexpand(True)
        page = self._stack.add_titled(self._lyrics_slot, "lyrics", "Lyrics")
        page.set_icon_name("view-paged-symbolic")
        self._queue_slot = Adw.Bin()
        self._queue_slot.set_vexpand(True)
        page = self._stack.add_titled(self._queue_slot, "queue", "Queue")
        page.set_icon_name("view-list-ordered-symbolic")

        self._stack.connect("notify::visible-child-name", lambda *_: self._on_tab_changed())
        toolbar.set_content(self._stack)
        self.set_child(toolbar)

        self._player.connect("track-changed", lambda *_: self.refresh())
        self._player.connect("queue-changed", lambda *_: self._build_queue())
        self._player.connect("position-changed", self._on_position)
        self.refresh()

    def _on_position(self, _player, position: int, duration: int) -> None:
        self._seek.update(position, duration)
        self._follow_lyrics(position)

    def show_tab(self, name: str) -> None:
        self._stack.set_visible_child_name(name)

    def set_compact(self, compact: bool) -> None:
        self._switcher.set_visible(not compact)
        self._switcher_bar.set_reveal(compact)

    # ------------------------------------------------------------- song panel

    def _build_song(self) -> Gtk.Widget:
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=18)
        box.set_valign(Gtk.Align.CENTER)
        box.set_halign(Gtk.Align.CENTER)
        box.set_margin_top(24)
        box.set_margin_bottom(24)

        self._art = Thumb(280)
        self._art.add_css_class("hero-art")
        box.append(self._art)

        self._big_title = Gtk.Label(label="Nothing playing")
        self._big_title.add_css_class("title-1")
        self._big_title.set_wrap(True)
        self._big_title.set_justify(Gtk.Justification.CENTER)
        self._big_title.set_max_width_chars(34)
        box.append(self._big_title)

        self._big_artist = Gtk.Label(label="")
        self._big_artist.add_css_class("title-4")
        self._big_artist.add_css_class("dim-label")
        self._big_artist.set_wrap(True)
        self._big_artist.set_justify(Gtk.Justification.CENTER)
        self._big_artist.set_max_width_chars(40)
        box.append(self._big_artist)

        self._seek = SeekBar(self._player, compact=False)
        self._seek.set_size_request(420, -1)
        box.append(self._seek)

        clamp = Adw.Clamp()
        clamp.set_maximum_size(560)
        clamp.set_child(box)
        scroller = Gtk.ScrolledWindow()
        scroller.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        scroller.set_child(clamp)
        return scroller

    def refresh(self) -> None:
        track = self._player.current
        if track is None:
            self._big_title.set_label("Nothing playing")
            self._big_artist.set_label("")
            self._art.set_url(None)
        else:
            self._big_title.set_label(track.get("title") or "")
            self._big_artist.set_label(track.get("subtitle") or "")
            self._art.set_url(track.get("thumb"))
        self._build_queue()
        if self._stack.get_visible_child_name() == "lyrics":
            self._load_lyrics()

    def _on_tab_changed(self) -> None:
        if self._stack.get_visible_child_name() == "lyrics":
            self._load_lyrics()

    # ----------------------------------------------------------------- lyrics

    def _load_lyrics(self) -> None:
        track = self._player.current
        if track is None:
            status = Adw.StatusPage()
            status.set_icon_name("view-paged-symbolic")
            status.set_title("No song playing")
            self._lyrics_slot.set_child(status)
            return
        if self._lyrics_for == track["id"]:
            return
        self._lyrics_for = track["id"]

        from .widgets import LoadingView
        self._lyric_lines = None
        self._lyric_labels = []
        self._lyric_index = -1
        self._lyrics_slot.set_child(LoadingView("Fetching lyrics…"))
        video_id = track["id"]

        def done(result):
            if self._lyrics_for != video_id:
                return
            if not result or not result.get("text"):
                status = Adw.StatusPage()
                status.set_icon_name("view-paged-symbolic")
                status.set_title("No lyrics")
                status.set_description(
                    "Neither YouTube Music nor LRCLIB has lyrics for this song.")
                self._lyrics_slot.set_child(status)
                return
            self._lyrics_slot.set_child(self._render_lyrics(result))

        def failed(_exc):
            if self._lyrics_for != video_id:
                return
            status = Adw.StatusPage()
            status.set_icon_name("view-paged-symbolic")
            status.set_title("Lyrics unavailable")
            self._lyrics_slot.set_child(status)

        tasks.run_async(lambda: self.ctx.api.lyrics(video_id, track), done, failed)

    def _render_lyrics(self, data: dict) -> Gtk.Widget:
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        box.set_margin_top(24)
        box.set_margin_bottom(24)
        box.set_margin_start(12)
        box.set_margin_end(12)

        lines = data.get("lines")
        self._lyric_lines = lines
        self._lyric_labels = []
        self._lyric_index = -1

        texts = [text for _ms, text in lines] if lines else data["text"].splitlines()
        for text in texts:
            label = Gtk.Label(label=text or " ")
            label.set_wrap(True)
            label.set_justify(Gtk.Justification.CENTER)
            label.add_css_class("lyric-line")
            if lines:
                # Synced: dim everything until the song reaches it.
                label.add_css_class("lyric-pending")
            box.append(label)
            self._lyric_labels.append(label)

        provider = data.get("provider") or data.get("source")
        if provider:
            note = "Synced lyrics from %s" if lines else "Lyrics from %s"
            source = Gtk.Label(label=note % provider)
            source.add_css_class("caption")
            source.add_css_class("dim-label")
            source.set_margin_top(18)
            box.append(source)

        clamp = Adw.Clamp()
        clamp.set_maximum_size(620)
        clamp.set_child(box)
        scroller = Gtk.ScrolledWindow()
        scroller.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        scroller.set_vexpand(True)
        scroller.set_child(clamp)
        self._lyric_box = box
        self._lyric_scroller = scroller
        return scroller

    def _follow_lyrics(self, position: int) -> None:
        """Highlight and scroll to the line the song is currently on."""
        if not self._lyric_lines or not self._lyric_labels:
            return
        if self._stack.get_visible_child_name() != "lyrics":
            return
        milliseconds = position // 1_000_000
        index = -1
        for i, (start, _text) in enumerate(self._lyric_lines):
            if start <= milliseconds:
                index = i
            else:
                break
        if index == self._lyric_index or index < 0:
            return
        if 0 <= self._lyric_index < len(self._lyric_labels):
            previous = self._lyric_labels[self._lyric_index]
            previous.remove_css_class("lyric-active")
            previous.add_css_class("lyric-pending")
        self._lyric_index = index
        label = self._lyric_labels[index]
        label.remove_css_class("lyric-pending")
        label.add_css_class("lyric-active")
        self._scroll_to(label)

    def _scroll_to(self, label: Gtk.Label) -> None:
        scroller, box = self._lyric_scroller, self._lyric_box
        if scroller is None or box is None:
            return
        # compute_bounds is the GTK4 way; translate_coordinates' return shape
        # differs between PyGObject versions.
        ok, bounds = label.compute_bounds(box)
        if not ok:
            return
        y = bounds.origin.y
        adjustment = scroller.get_vadjustment()
        target = y - (adjustment.get_page_size() / 2) + (label.get_height() / 2)
        upper = adjustment.get_upper() - adjustment.get_page_size()
        adjustment.set_value(max(0.0, min(target, max(0.0, upper))))

    # ------------------------------------------------------------------ queue

    def _build_queue(self) -> None:
        queue = self._player.queue
        if not queue:
            status = Adw.StatusPage()
            status.set_icon_name("view-list-ordered-symbolic")
            status.set_title("Queue is empty")
            status.set_description("Play a song, album or playlist to fill it.")
            self._queue_slot.set_child(status)
            return

        group = Adw.PreferencesGroup()
        group.set_title(f"{len(queue)} in queue")
        clear = Gtk.Button.new_from_icon_name("user-trash-symbolic")
        clear.add_css_class("flat")
        clear.set_tooltip_text("Clear queue")
        clear.connect("clicked", lambda _b: self._player.clear())
        group.set_header_suffix(clear)

        current = self._player.queue_position
        for position, track in enumerate(queue):
            row = Adw.ActionRow()
            row.set_title(escape(track.get("title")))
            row.set_subtitle(escape(track.get("subtitle")))
            row.set_title_lines(1)
            row.set_subtitle_lines(1)
            row.set_activatable(True)
            row.connect("activated", lambda _r, p=position: self._player.jump_to(p))
            if position == current:
                icon = Gtk.Image.new_from_icon_name("media-playback-start-symbolic")
                icon.add_css_class("accent")
                row.add_prefix(icon)
                row.add_css_class("now-playing")
            else:
                number = Gtk.Label(label=str(position + 1))
                number.add_css_class("dim-label")
                number.add_css_class("numeric")
                number.set_width_chars(2)
                row.add_prefix(number)
            remove = Gtk.Button.new_from_icon_name("list-remove-symbolic")
            remove.add_css_class("flat")
            remove.set_valign(Gtk.Align.CENTER)
            remove.set_tooltip_text("Remove from queue")
            remove.connect("clicked", lambda _b, p=position: self._player.remove_at(p))
            row.add_suffix(remove)
            group.add(row)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.set_margin_top(12)
        box.set_margin_bottom(24)
        box.set_margin_start(12)
        box.set_margin_end(12)
        box.append(group)
        clamp = Adw.Clamp()
        clamp.set_maximum_size(900)
        clamp.set_child(box)
        scroller = Gtk.ScrolledWindow()
        scroller.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        scroller.set_vexpand(True)
        scroller.set_child(clamp)
        self._queue_slot.set_child(scroller)
