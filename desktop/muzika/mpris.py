"""MPRIS2 so GNOME's media keys, lock screen and top-bar controls drive us."""

from __future__ import annotations

import logging

import gi

gi.require_version("Gst", "1.0")
from gi.repository import Gio, GLib, Gst  # noqa: E402

from .player import REPEAT_ALL, REPEAT_NONE, REPEAT_ONE  # noqa: E402

log = logging.getLogger(__name__)

BUS_NAME = "org.mpris.MediaPlayer2.muzika"
OBJECT_PATH = "/org/mpris/MediaPlayer2"
ROOT_IFACE = "org.mpris.MediaPlayer2"
PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"

INTROSPECTION = """
<node>
  <interface name="org.mpris.MediaPlayer2">
    <method name="Raise"/>
    <method name="Quit"/>
    <property name="CanQuit" type="b" access="read"/>
    <property name="CanRaise" type="b" access="read"/>
    <property name="HasTrackList" type="b" access="read"/>
    <property name="Identity" type="s" access="read"/>
    <property name="DesktopEntry" type="s" access="read"/>
    <property name="SupportedUriSchemes" type="as" access="read"/>
    <property name="SupportedMimeTypes" type="as" access="read"/>
  </interface>
  <interface name="org.mpris.MediaPlayer2.Player">
    <method name="Next"/>
    <method name="Previous"/>
    <method name="Pause"/>
    <method name="PlayPause"/>
    <method name="Stop"/>
    <method name="Play"/>
    <method name="Seek"><arg direction="in" name="Offset" type="x"/></method>
    <method name="SetPosition">
      <arg direction="in" name="TrackId" type="o"/>
      <arg direction="in" name="Position" type="x"/>
    </method>
    <method name="OpenUri"><arg direction="in" name="Uri" type="s"/></method>
    <signal name="Seeked"><arg name="Position" type="x"/></signal>
    <property name="PlaybackStatus" type="s" access="read"/>
    <property name="LoopStatus" type="s" access="readwrite"/>
    <property name="Rate" type="d" access="readwrite"/>
    <property name="Shuffle" type="b" access="readwrite"/>
    <property name="Metadata" type="a{sv}" access="read"/>
    <property name="Volume" type="d" access="readwrite"/>
    <property name="Position" type="x" access="read"/>
    <property name="MinimumRate" type="d" access="read"/>
    <property name="MaximumRate" type="d" access="read"/>
    <property name="CanGoNext" type="b" access="read"/>
    <property name="CanGoPrevious" type="b" access="read"/>
    <property name="CanPlay" type="b" access="read"/>
    <property name="CanPause" type="b" access="read"/>
    <property name="CanSeek" type="b" access="read"/>
    <property name="CanControl" type="b" access="read"/>
  </interface>
</node>
"""

LOOP_TO_REPEAT = {"None": REPEAT_NONE, "Playlist": REPEAT_ALL, "Track": REPEAT_ONE}
REPEAT_TO_LOOP = {v: k for k, v in LOOP_TO_REPEAT.items()}


class MprisServer:
    def __init__(self, app) -> None:
        self._app = app
        self._player = app.player
        self._connection: Gio.DBusConnection | None = None
        self._registration_ids: list[int] = []
        self._owner_id = Gio.bus_own_name(
            Gio.BusType.SESSION, BUS_NAME, Gio.BusNameOwnerFlags.NONE,
            self._on_bus_acquired, None, None)

        self._player.connect("track-changed", lambda *_: self._changed(
            {"Metadata": self._metadata(), "CanSeek": GLib.Variant("b", True)}))
        self._player.connect("state-changed", lambda *_: self._changed({
            "PlaybackStatus": GLib.Variant("s", self._status()),
            "Shuffle": GLib.Variant("b", self._player.shuffle),
            "LoopStatus": GLib.Variant("s", REPEAT_TO_LOOP.get(self._player.repeat, "None")),
        }))

    # ------------------------------------------------------------------ setup

    def _on_bus_acquired(self, connection: Gio.DBusConnection, _name: str) -> None:
        self._connection = connection
        node = Gio.DBusNodeInfo.new_for_xml(INTROSPECTION)
        for interface in node.interfaces:
            try:
                reg_id = connection.register_object(
                    OBJECT_PATH, interface, self._on_method, self._on_get, self._on_set)
                self._registration_ids.append(reg_id)
            except Exception as exc:  # noqa: BLE001
                log.warning("MPRIS: could not register %s: %s", interface.name, exc)

    def shutdown(self) -> None:
        if self._connection is not None:
            for reg_id in self._registration_ids:
                self._connection.unregister_object(reg_id)
        if self._owner_id:
            Gio.bus_unown_name(self._owner_id)

    # -------------------------------------------------------------- callbacks

    def _on_method(self, _conn, _sender, _path, interface, method, params, invocation) -> None:
        player = self._player
        try:
            if interface == ROOT_IFACE:
                if method == "Raise":
                    self._app.raise_window()
                elif method == "Quit":
                    self._app.quit()
            elif interface == PLAYER_IFACE:
                if method == "Next":
                    player.next()
                elif method == "Previous":
                    player.previous()
                elif method == "Pause":
                    player.pause()
                elif method == "Play":
                    player.play()
                elif method == "PlayPause":
                    player.toggle()
                elif method == "Stop":
                    player.stop()
                elif method == "Seek":
                    offset = params.unpack()[0]
                    position, _ = player.position_duration()
                    player.seek(position + offset * 1000)
                elif method == "SetPosition":
                    _track_id, position = params.unpack()
                    player.seek(position * 1000)
            invocation.return_value(None)
        except Exception as exc:  # noqa: BLE001
            log.warning("MPRIS method %s failed: %s", method, exc)
            invocation.return_value(None)

    def _on_get(self, _conn, _sender, _path, interface, prop):
        player = self._player
        if interface == ROOT_IFACE:
            return {
                "CanQuit": GLib.Variant("b", True),
                "CanRaise": GLib.Variant("b", True),
                "HasTrackList": GLib.Variant("b", False),
                "Identity": GLib.Variant("s", "Muzika"),
                "DesktopEntry": GLib.Variant("s", "lt.a777.Muzika"),
                "SupportedUriSchemes": GLib.Variant("as", []),
                "SupportedMimeTypes": GLib.Variant("as", []),
            }.get(prop)
        if interface == PLAYER_IFACE:
            position, _ = player.position_duration()
            has_track = player.current is not None
            return {
                "PlaybackStatus": GLib.Variant("s", self._status()),
                "LoopStatus": GLib.Variant("s", REPEAT_TO_LOOP.get(player.repeat, "None")),
                "Rate": GLib.Variant("d", 1.0),
                "Shuffle": GLib.Variant("b", player.shuffle),
                "Metadata": self._metadata(),
                "Volume": GLib.Variant("d", player.volume),
                "Position": GLib.Variant("x", position // 1000),
                "MinimumRate": GLib.Variant("d", 1.0),
                "MaximumRate": GLib.Variant("d", 1.0),
                "CanGoNext": GLib.Variant("b", has_track),
                "CanGoPrevious": GLib.Variant("b", has_track),
                "CanPlay": GLib.Variant("b", has_track),
                "CanPause": GLib.Variant("b", has_track),
                "CanSeek": GLib.Variant("b", has_track),
                "CanControl": GLib.Variant("b", True),
            }.get(prop)
        return None

    def _on_set(self, _conn, _sender, _path, interface, prop, value) -> bool:
        if interface != PLAYER_IFACE:
            return False
        if prop == "Volume":
            self._player.volume = value.get_double()
        elif prop == "Shuffle":
            self._player.shuffle = value.get_boolean()
        elif prop == "LoopStatus":
            self._player.repeat = LOOP_TO_REPEAT.get(value.get_string(), REPEAT_NONE)
        else:
            return False
        return True

    # ----------------------------------------------------------------- helpers

    def _status(self) -> str:
        if self._player.current is None:
            return "Stopped"
        return "Playing" if self._player.playing else "Paused"

    def _metadata(self) -> GLib.Variant:
        track = self._player.current
        if track is None:
            return GLib.Variant("a{sv}", {})
        _, duration = self._player.position_duration()
        if duration <= 0:
            duration = track.get("duration", 0) * Gst.SECOND
        safe_id = "".join(c if c.isalnum() else "_" for c in str(track.get("id", "unknown")))
        data = {
            "mpris:trackid": GLib.Variant("o", f"/lt/a777/Muzika/track/{safe_id}"),
            "mpris:length": GLib.Variant("x", int(duration // 1000)),
            "xesam:title": GLib.Variant("s", track.get("title") or ""),
            "xesam:artist": GLib.Variant("as", [track.get("subtitle") or ""]),
        }
        if track.get("album"):
            data["xesam:album"] = GLib.Variant("s", track["album"])
        if track.get("thumb"):
            data["mpris:artUrl"] = GLib.Variant("s", track["thumb"])
        return GLib.Variant("a{sv}", data)

    def _changed(self, properties: dict) -> None:
        if self._connection is None:
            return
        try:
            self._connection.emit_signal(
                None, OBJECT_PATH, "org.freedesktop.DBus.Properties", "PropertiesChanged",
                GLib.Variant("(sa{sv}as)", (PLAYER_IFACE, properties, [])))
        except Exception as exc:  # noqa: BLE001
            log.debug("MPRIS PropertiesChanged failed: %s", exc)
