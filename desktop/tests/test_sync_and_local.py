"""Tests for the parts that have actually broken.

Every case here corresponds to a real bug: a local track id that differed
between devices, a merge that could have emptied a playlist, a migration that
had to run against an existing database, and an artist name split into a
fragment.
"""

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from muzika import db, local, sync  # noqa: E402


@pytest.fixture()
def store(tmp_path, monkeypatch):
    monkeypatch.setattr(db, "DATA_DIR", tmp_path)
    return db.Store(tmp_path / "muzika.db")


def _track(track_id, title="T", artist="A"):
    return {"kind": "song", "id": track_id, "title": title,
            "subtitle": artist, "duration": 1, "thumb": None}


# ------------------------------------------------------------------ merging

def test_import_never_empties_a_playlist(store, tmp_path, monkeypatch):
    """The rule that matters most: a sync must not destroy local tracks."""
    pid = store.create_playlist("Keepers")
    store.add_many_to_playlist(pid, [_track("a"), _track("b")])

    payload = {"format": sync.FORMAT, "version": 1, "updated_at": 0,
               "device": "other", "playlists": [
                   {"name": "Keepers", "updated_at": 0, "tracks": []}],
               "favourites": [], "library": []}
    sync.apply_payload(store, payload)

    tracks = store.playlist(pid)["tracks"]
    assert [t["id"] for t in tracks] == ["a", "b"]


def test_favourites_are_unioned_not_replaced(store):
    store.add_favourite(_track("mine"))
    payload = {"format": sync.FORMAT, "version": 1, "updated_at": 0,
               "device": "other", "playlists": [],
               "favourites": [{"id": "theirs", "title": "T", "artist": "A",
                               "duration": 0, "thumb": None}],
               "library": []}
    sync.apply_payload(store, payload)
    assert {f["id"] for f in store.favourites()} == {"mine", "theirs"}


def test_saved_items_are_unioned_on_kind_and_id(store):
    store.toggle_library({"kind": "album", "id": "MPRE1", "title": "One"})
    payload = {"format": sync.FORMAT, "version": 1, "updated_at": 0,
               "device": "other", "playlists": [], "favourites": [],
               "library": [{"kind": "album", "id": "MPRE2", "title": "Two",
                            "subtitle": "", "thumb": None}]}
    sync.apply_payload(store, payload)
    assert {i["id"] for i in store.library("album")} == {"MPRE1", "MPRE2"}


def test_import_is_idempotent(store):
    payload = {"format": sync.FORMAT, "version": 1, "updated_at": 0,
               "device": "other",
               "playlists": [{"name": "P", "updated_at": 0,
                              "tracks": [{"id": "x", "title": "X",
                                          "artist": "A", "duration": 0,
                                          "thumb": None}]}],
               "favourites": [], "library": []}
    sync.apply_payload(store, payload)
    sync.apply_payload(store, payload)
    assert len([p for p in store.playlists() if p["title"] == "P"]) == 1


# ------------------------------------------------ the round trip in one file

def test_export_then_import_preserves_everything(store, tmp_path):
    pid = store.create_playlist("Round trip")
    store.add_many_to_playlist(pid, [_track("a"), _track("b")])
    store.add_favourite(_track("fav"))
    store.toggle_library({"kind": "artist", "id": "UC1", "title": "Band"})

    path = sync.export_library(store, tmp_path)
    payload = json.loads(Path(path).read_text())

    fresh = db.Store(tmp_path / "fresh.db")
    sync.apply_payload(fresh, payload)

    pid2 = [p["playlist_id"] for p in fresh.playlists()
            if p["title"] == "Round trip"][0]
    assert [t["id"] for t in fresh.playlist(pid2)["tracks"]] == ["a", "b"]
    assert {f["id"] for f in fresh.favourites()} == {"fav"}
    assert {i["id"] for i in fresh.library("artist")} == {"UC1"}


# -------------------------------------------------------------- local music

def test_local_track_id_is_prefixed_with_the_library_folder(tmp_path):
    """The bug that silently broke every synced local track.

    The id has to be <library folder>/<path inside it>, because that is what
    the phone computes from its own copy under <sync folder>/Music.
    """
    root = tmp_path / "mymusic"
    (root / "Band" / "Album").mkdir(parents=True)
    song = root / "Band" / "Album" / "01 Song.mp3"
    song.write_bytes(b"not really audio")

    track = local.read_track(song, root)
    assert track["id"] == "local:mymusic/Band/Album/01 Song.mp3"
    assert track["url"] == "mymusic/Band/Album/01 Song.mp3"


def test_untagged_file_falls_back_to_artist_and_album_from_the_path(tmp_path):
    root = tmp_path / "lib"
    (root / "The Band" / "The Album").mkdir(parents=True)
    song = root / "The Band" / "The Album" / "03 The Song.mp3"
    song.write_bytes(b"not really audio")

    track = local.read_track(song, root)
    assert track["subtitle"] == "The Band"
    assert track["album"] == "The Album"
    assert track["title"] == "The Song"


def test_resolve_finds_a_track_from_its_prefixed_id(tmp_path):
    root = tmp_path / "mymusic"
    (root / "A").mkdir(parents=True)
    song = root / "A" / "s.mp3"
    song.write_bytes(b"x")
    track = local.read_track(song, root)
    assert local.resolve(track, [str(root)]) == str(song)
