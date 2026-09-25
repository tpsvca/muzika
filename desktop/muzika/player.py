"""Playback: a GStreamer pipeline plus the queue that feeds it."""

from __future__ import annotations

import logging
import random

import gi

gi.require_version("Gst", "1.0")
from gi.repository import GLib, GObject, Gst  # noqa: E402

from . import tasks  # noqa: E402

log = logging.getLogger(__name__)

REPEAT_NONE, REPEAT_ALL, REPEAT_ONE = "none", "all", "one"


class Player(GObject.Object):
    """playbin3 wrapper with a shuffleable queue.

    The queue keeps tracks in the order they were added; `_order` is the
    sequence we actually play. Shuffling permutes `_order`, never the queue,
    so turning shuffle off restores the original order without refetching.
    """

    __gsignals__ = {
        "track-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "state-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "position-changed": (GObject.SignalFlags.RUN_FIRST, None, (GObject.TYPE_INT64, GObject.TYPE_INT64)),
        "queue-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "playback-error": (GObject.SignalFlags.RUN_FIRST, None, (str,)),
    }

    def __init__(self, api, store) -> None:
        super().__init__()
        self._api = api
        self._store = store

        Gst.init(None)
        self._bin = Gst.ElementFactory.make("playbin3", "muzika-player")
        if self._bin is None:
            raise RuntimeError("GStreamer playbin3 is unavailable")
        self._headers: dict = {}
        self._bin.connect("source-setup", self._on_source_setup)

        # Audio only. Left to its own devices playbin3 will set up a video
        # chain and can open its own output window; we never want that.
        # GstPlayFlags: AUDIO|SOFT_VOLUME|DOWNLOAD|BUFFERING
        self._bin.set_property("flags", 0x002 | 0x010 | 0x080 | 0x100)
        fakesink = Gst.ElementFactory.make("fakesink", None)
        if fakesink is not None:
            fakesink.set_property("sync", False)
            self._bin.set_property("video-sink", fakesink)

        bus = self._bin.get_bus()
        bus.add_signal_watch()
        bus.connect("message::eos", lambda *_: self.next(user=False))
        bus.connect("message::error", self._on_bus_error)
        bus.connect("message::buffering", self._on_buffering)

        self._queue: list[dict] = []
        self._order: list[int] = []
        self._cursor: int = -1
        self._shuffle = False
        self._repeat = REPEAT_NONE
        self._loading = False
        self._resolve_token = 0
        # What the user asked for. Querying the pipeline with a zero timeout
        # reports the state it has actually reached, and PLAYING is reached
        # asynchronously - so right after set_state() it still says PAUSED and
        # the button would show the wrong icon.
        self._intended_playing = False
        # Which page filled the queue, so that page's Play button can show
        # Pause while it is the thing playing.
        self._source: str | None = None
        self.autoplay_radio = True

        GLib.timeout_add(500, self._tick)

    # ------------------------------------------------------------- properties

    @property
    def current(self) -> dict | None:
        if 0 <= self._cursor < len(self._order):
            return self._queue[self._order[self._cursor]]
        return None

    @property
    def queue(self) -> list[dict]:
        return [self._queue[i] for i in self._order]

    @property
    def queue_position(self) -> int:
        return self._cursor

    @property
    def loading(self) -> bool:
        return self._loading

    @property
    def playing(self) -> bool:
        return self._intended_playing

    @property
    def shuffle(self) -> bool:
        return self._shuffle

    @shuffle.setter
    def shuffle(self, enabled: bool) -> None:
        if enabled == self._shuffle:
            return
        self._shuffle = enabled
        current_index = self._order[self._cursor] if 0 <= self._cursor < len(self._order) else None
        if enabled:
            rest = [i for i in self._order if i != current_index]
            random.shuffle(rest)
            self._order = ([current_index] if current_index is not None else []) + rest
        else:
            self._order = sorted(self._order)
        if current_index is not None:
            self._cursor = self._order.index(current_index)
        self.emit("queue-changed")

    @property
    def repeat(self) -> str:
        return self._repeat

    @repeat.setter
    def repeat(self, mode: str) -> None:
        self._repeat = mode
        self.emit("state-changed")

    @property
    def volume(self) -> float:
        return float(self._bin.get_property("volume"))

    @volume.setter
    def volume(self, value: float) -> None:
        self._bin.set_property("volume", max(0.0, min(1.0, value)))

    # ------------------------------------------------------------ queue edits

    @property
    def source(self) -> str | None:
        return self._source

    def set_queue(self, tracks: list[dict], start: int | None = None,
                  shuffle: bool | None = None, source: str | None = None) -> None:
        """Replace the queue and start playing.

        `start` is the track the user picked, or None for "no preference".
        `shuffle` of None keeps whatever the shuffle toggle is currently set to,
        so clicking a song does not silently turn the toggle off.
        """
        tracks = [t for t in tracks if t.get("id")]
        if not tracks:
            return
        self._queue = list(tracks)
        self._order = list(range(len(tracks)))
        self._source = source
        if shuffle is None:
            shuffle = self._shuffle
        self._shuffle = shuffle
        if shuffle:
            random.shuffle(self._order)
            if start is not None and 0 <= start < len(tracks):
                # The user picked this track, so it plays first and the rest
                # are shuffled behind it.
                self._order.remove(start)
                self._order.insert(0, start)
            self._cursor = 0
        else:
            self._cursor = start if (start is not None and 0 <= start < len(tracks)) else 0
        self.emit("queue-changed")
        self._load_current()

    def append(self, tracks: list[dict]) -> None:
        tracks = [t for t in tracks if t.get("id")]
        if not tracks:
            return
        base = len(self._queue)
        self._queue.extend(tracks)
        new_indices = list(range(base, base + len(tracks)))
        if self._shuffle:
            random.shuffle(new_indices)
        self._order.extend(new_indices)
        self.emit("queue-changed")
        if self._cursor < 0:
            self._cursor = 0
            self._load_current()

    def play_next(self, track: dict) -> None:
        """Insert directly after the current track."""
        if not track.get("id"):
            return
        self._queue.append(track)
        self._order.insert(self._cursor + 1, len(self._queue) - 1)
        self.emit("queue-changed")

    def remove_at(self, position: int) -> None:
        if not 0 <= position < len(self._order):
            return
        self._order.pop(position)
        if position < self._cursor:
            self._cursor -= 1
        elif position == self._cursor:
            self._cursor -= 1
            self.next(user=True)
        self.emit("queue-changed")

    def clear(self) -> None:
        self._intended_playing = False
        self._bin.set_state(Gst.State.NULL)
        self._queue, self._order, self._cursor = [], [], -1
        self.emit("queue-changed")
        self.emit("track-changed")
        self.emit("state-changed")

    def jump_to(self, position: int) -> None:
        if 0 <= position < len(self._order):
            self._cursor = position
            self._load_current()

    # -------------------------------------------------------------- transport

    def play(self) -> None:
        if self.current is None:
            return
        self._intended_playing = True
        self._bin.set_state(Gst.State.PLAYING)
        self.emit("state-changed")

    def pause(self) -> None:
        self._intended_playing = False
        self._bin.set_state(Gst.State.PAUSED)
        self.emit("state-changed")

    def toggle(self) -> None:
        self.pause() if self.playing else self.play()

    def stop(self) -> None:
        self._intended_playing = False
        self._bin.set_state(Gst.State.NULL)
        self.emit("state-changed")

    def next(self, user: bool = True) -> None:
        if not self._order:
            return
        if self._repeat == REPEAT_ONE and not user:
            self.seek(0)
            self.play()
            return
        if self._cursor + 1 < len(self._order):
            self._cursor += 1
            self._load_current()
            return
        if self._repeat == REPEAT_ALL:
            self._cursor = 0
            self._load_current()
            return
        if self.autoplay_radio and not user and self.current:
            self._extend_with_radio()
            return
        self.stop()

    def previous(self) -> None:
        # Restart the track first, like every other music player.
        position, _ = self.position_duration()
        if position > 5 * Gst.SECOND:
            self.seek(0)
            return
        if self._cursor > 0:
            self._cursor -= 1
            self._load_current()
        else:
            self.seek(0)

    def seek(self, nanoseconds: int) -> None:
        # ACCURATE rather than KEY_UNIT: keyframe snapping can land several
        # seconds from where the user clicked, and for audio the extra cost
        # of an exact seek is negligible.
        self._bin.seek_simple(Gst.Format.TIME,
                              Gst.SeekFlags.FLUSH | Gst.SeekFlags.ACCURATE,
                              max(0, int(nanoseconds)))

    def seek_fraction(self, fraction: float) -> None:
        _, duration = self.position_duration()
        if duration > 0:
            self.seek(int(duration * max(0.0, min(1.0, fraction))))

    def position_duration(self) -> tuple[int, int]:
        ok_pos, position = self._bin.query_position(Gst.Format.TIME)
        ok_dur, duration = self._bin.query_duration(Gst.Format.TIME)
        return (position if ok_pos else 0), (duration if ok_dur else 0)

    # ---------------------------------------------------------------- internals

    def _prefetch_next(self) -> None:
        """Resolve the following track while this one plays, so skipping and
        reaching the end are both instant instead of a yt-dlp round trip."""
        nxt = self._cursor + 1
        if not (0 <= nxt < len(self._order)):
            return
        track = self._queue[self._order[nxt]]
        tasks.run_async(lambda: self._api.prefetch(track), lambda _r: None,
                        lambda _e: None)

    def _load_current(self) -> None:
        track = self.current
        if track is None:
            return
        self._loading = True
        self._resolve_token += 1
        token = self._resolve_token
        self._bin.set_state(Gst.State.NULL)
        self.emit("track-changed")
        self.emit("state-changed")

        video_id = track["id"]

        def resolve():
            # The whole track goes through: non-YouTube sources carry the page
            # URL that yt-dlp needs, which a bare id cannot express.
            return self._api.stream(track)

        def done(result):
            if token != self._resolve_token:
                return  # superseded by a newer request
            url, headers = result
            self._headers = headers
            self._loading = False
            self._intended_playing = True
            self._bin.set_property("uri", url)
            self._bin.set_state(Gst.State.PLAYING)
            self.emit("state-changed")
            tasks.run_async(lambda: self._store.record_play(track))
            self._prefetch_next()

        def failed(exc):
            if token != self._resolve_token:
                return
            self._loading = False
            self._intended_playing = False
            self.emit("state-changed")
            self.emit("playback-error", f"Could not play “{track.get('title', '')}”: {exc}")
            GLib.timeout_add_seconds(1, lambda: (self.next(user=False), False)[1])

        tasks.run_async(resolve, done, failed)

    def _extend_with_radio(self) -> None:
        track = self.current
        if track is None:
            return
        known = {t["id"] for t in self._queue}
        video_id = track["id"]

        def work():
            return self._api.radio(video_id=video_id, limit=25)

        def done(tracks):
            fresh = [t for t in tracks if t["id"] not in known]
            if not fresh:
                self.stop()
                return
            self.append(fresh)
            self.next(user=True)

        tasks.run_async(work, done, lambda _exc: self.stop())

    def _on_source_setup(self, _playbin, source) -> None:
        """YouTube ties stream URLs to the client that requested them."""
        if not self._headers:
            return
        try:
            user_agent = self._headers.get("User-Agent")
            if user_agent and hasattr(source.props, "user_agent"):
                source.set_property("user-agent", user_agent)
            extra = Gst.Structure.new_empty("extra-headers")
            for key, value in self._headers.items():
                if key.lower() != "user-agent":
                    extra.set_value(key, value)
            source.set_property("extra-headers", extra)
        except Exception as exc:  # noqa: BLE001 - non-fatal, playback may still work
            log.debug("could not apply stream headers: %s", exc)

    def _on_bus_error(self, _bus, message) -> None:
        error, debug = message.parse_error()
        log.warning("gstreamer error: %s (%s)", error.message, debug)
        self._intended_playing = False
        self._bin.set_state(Gst.State.NULL)
        self.emit("playback-error", error.message)
        self.emit("state-changed")

    def _on_buffering(self, _bus, message) -> None:
        percent = message.parse_buffering()
        if percent < 100:
            self._bin.set_state(Gst.State.PAUSED)
        elif self.current is not None and self._intended_playing:
            self._bin.set_state(Gst.State.PLAYING)

    def _tick(self) -> bool:
        if self.current is not None:
            position, duration = self.position_duration()
            self.emit("position-changed", position, duration)
        return True
