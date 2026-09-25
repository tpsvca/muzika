"""Local state: favourites, saved library entries, local music, playback and
search history."""

from __future__ import annotations

import sqlite3
import threading
import time
from pathlib import Path

DATA_DIR = Path.home() / ".local" / "share" / "muzika"

SCHEMA = """
CREATE TABLE IF NOT EXISTS favourites (
    video_id TEXT PRIMARY KEY,
    title    TEXT NOT NULL,
    subtitle TEXT,
    thumb    TEXT,
    duration INTEGER DEFAULT 0,
    source   TEXT,
    url      TEXT,
    added_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS library (
    kind     TEXT NOT NULL,
    item_id  TEXT NOT NULL,
    title    TEXT NOT NULL,
    subtitle TEXT,
    thumb    TEXT,
    added_at REAL NOT NULL,
    PRIMARY KEY (kind, item_id)
);
CREATE TABLE IF NOT EXISTS local_tracks (
    path       TEXT PRIMARY KEY,   -- relative to its music folder
    root       TEXT NOT NULL,      -- the music folder it was found in
    title      TEXT NOT NULL,
    artist     TEXT,
    album      TEXT,
    track_no   INTEGER DEFAULT 0,
    duration   INTEGER DEFAULT 0,
    thumb      TEXT,
    indexed_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS local_album ON local_tracks (album);
CREATE INDEX IF NOT EXISTS local_artist ON local_tracks (artist);
CREATE TABLE IF NOT EXISTS history (
    video_id  TEXT NOT NULL,
    title     TEXT NOT NULL,
    subtitle  TEXT,
    thumb     TEXT,
    duration  INTEGER DEFAULT 0,
    played_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS history_played_at ON history (played_at DESC);
CREATE TABLE IF NOT EXISTS searches (
    query      TEXT PRIMARY KEY,
    searched_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS playlists (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL,
    created_at REAL NOT NULL,
    updated_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS playlist_tracks (
    playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
    video_id    TEXT NOT NULL,
    title       TEXT NOT NULL,
    subtitle    TEXT,
    thumb       TEXT,
    duration    INTEGER DEFAULT 0,
    source      TEXT,
    url         TEXT,
    position    INTEGER NOT NULL,
    added_at    REAL NOT NULL,
    PRIMARY KEY (playlist_id, video_id)
);
CREATE INDEX IF NOT EXISTS playlist_tracks_order
    ON playlist_tracks (playlist_id, position);
"""


def _row_to_track(row) -> dict:
    track = {"kind": "song", "id": row["video_id"], "title": row["title"],
             "subtitle": row["subtitle"] or "", "thumb": row["thumb"],
             "duration": row["duration"] or 0}
    keys = row.keys()
    if "source" in keys and row["source"]:
        track["source"] = row["source"]
        track["url"] = row["url"] if "url" in keys else None
    return track


class Store:
    """SQLite wrapper. Safe to call from worker threads."""

    def __init__(self, path: Path | None = None) -> None:
        self.path = path or (DATA_DIR / "muzika.db")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._db = sqlite3.connect(self.path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        with self._lock:
            self._db.executescript(SCHEMA)
            self._migrate()
            self._db.commit()

    def _migrate(self) -> None:
        """Add columns introduced after a database was first created."""
        for table, column in (("favourites", "source"), ("favourites", "url"),
                              ("playlist_tracks", "source"), ("playlist_tracks", "url")):
            existing = {row[1] for row in self._db.execute(f"PRAGMA table_info({table})")}
            if column not in existing:
                self._db.execute(f"ALTER TABLE {table} ADD COLUMN {column} TEXT")

    # ------------------------------------------------------------ favourites

    def is_favourite(self, video_id: str) -> bool:
        with self._lock:
            row = self._db.execute(
                "SELECT 1 FROM favourites WHERE video_id = ?", (video_id,)).fetchone()
        return row is not None

    def add_favourite(self, track: dict) -> None:
        with self._lock:
            self._db.execute(
                "INSERT OR REPLACE INTO favourites "
                "(video_id, title, subtitle, thumb, duration, source, url, added_at) "
                "VALUES (?,?,?,?,?,?,?,?)",
                (track["id"], track.get("title", ""), track.get("subtitle", ""),
                 track.get("thumb"), track.get("duration", 0),
                 track.get("source"), track.get("url"), time.time()))
            self._db.commit()

    def remove_favourite(self, video_id: str) -> None:
        with self._lock:
            self._db.execute("DELETE FROM favourites WHERE video_id = ?", (video_id,))
            self._db.commit()

    def toggle_favourite(self, track: dict) -> bool:
        """Returns the new state."""
        if self.is_favourite(track["id"]):
            self.remove_favourite(track["id"])
            return False
        self.add_favourite(track)
        return True

    def favourites(self) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM favourites ORDER BY added_at DESC").fetchall()
        return [_row_to_track(r) for r in rows]

    # --------------------------------------------------------------- library

    def in_library(self, kind: str, item_id: str) -> bool:
        with self._lock:
            row = self._db.execute(
                "SELECT 1 FROM library WHERE kind = ? AND item_id = ?", (kind, item_id)).fetchone()
        return row is not None

    def toggle_library(self, item: dict) -> bool:
        kind, item_id = item["kind"], item["id"]
        if self.in_library(kind, item_id):
            with self._lock:
                self._db.execute("DELETE FROM library WHERE kind = ? AND item_id = ?",
                                 (kind, item_id))
                self._db.commit()
            return False
        with self._lock:
            self._db.execute(
                "INSERT OR REPLACE INTO library (kind, item_id, title, subtitle, thumb, added_at) "
                "VALUES (?,?,?,?,?,?)",
                (kind, item_id, item.get("title", ""), item.get("subtitle", ""),
                 item.get("thumb"), time.time()))
            self._db.commit()
        return True

    def library(self, kind: str | None = None) -> list[dict]:
        sql = "SELECT * FROM library"
        args: tuple = ()
        if kind:
            sql += " WHERE kind = ?"
            args = (kind,)
        sql += " ORDER BY added_at DESC"
        with self._lock:
            rows = self._db.execute(sql, args).fetchall()
        return [{"kind": r["kind"], "id": r["item_id"], "title": r["title"],
                 "subtitle": r["subtitle"] or "", "thumb": r["thumb"]} for r in rows]

    # --------------------------------------------------------------- history

    def record_play(self, track: dict) -> None:
        with self._lock:
            self._db.execute(
                "INSERT INTO history (video_id, title, subtitle, thumb, duration, played_at) "
                "VALUES (?,?,?,?,?,?)",
                (track["id"], track.get("title", ""), track.get("subtitle", ""),
                 track.get("thumb"), track.get("duration", 0), time.time()))
            self._db.execute(
                "DELETE FROM history WHERE rowid NOT IN "
                "(SELECT rowid FROM history ORDER BY played_at DESC LIMIT 500)")
            self._db.commit()

    def history(self, limit: int = 100) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT video_id, title, subtitle, thumb, duration, MAX(played_at) AS played_at "
                "FROM history GROUP BY video_id ORDER BY played_at DESC LIMIT ?",
                (limit,)).fetchall()
        return [_row_to_track(r) for r in rows]

    def clear_history(self) -> None:
        with self._lock:
            self._db.execute("DELETE FROM history")
            self._db.commit()

    # -------------------------------------------------------- search history

    def record_search(self, query: str) -> None:
        query = query.strip()
        if not query:
            return
        with self._lock:
            self._db.execute(
                "INSERT OR REPLACE INTO searches (query, searched_at) VALUES (?,?)",
                (query, time.time()))
            self._db.commit()

    def recent_searches(self, limit: int = 12) -> list[str]:
        with self._lock:
            rows = self._db.execute(
                "SELECT query FROM searches ORDER BY searched_at DESC LIMIT ?", (limit,)).fetchall()
        return [r["query"] for r in rows]

    def clear_searches(self) -> None:
        with self._lock:
            self._db.execute("DELETE FROM searches")
            self._db.commit()

    # ------------------------------------------------------- local playlists

    def create_playlist(self, name: str) -> int:
        name = name.strip() or "Untitled playlist"
        now = time.time()
        with self._lock:
            cursor = self._db.execute(
                "INSERT INTO playlists (name, created_at, updated_at) VALUES (?,?,?)",
                (name, now, now))
            self._db.commit()
            return int(cursor.lastrowid)

    def rename_playlist(self, playlist_id: int, name: str) -> None:
        name = name.strip()
        if not name:
            return
        with self._lock:
            self._db.execute("UPDATE playlists SET name = ?, updated_at = ? WHERE id = ?",
                             (name, time.time(), playlist_id))
            self._db.commit()

    def delete_playlist(self, playlist_id: int) -> None:
        with self._lock:
            self._db.execute("DELETE FROM playlist_tracks WHERE playlist_id = ?", (playlist_id,))
            self._db.execute("DELETE FROM playlists WHERE id = ?", (playlist_id,))
            self._db.commit()

    def playlists(self) -> list[dict]:
        """Every local playlist, with its size and a cover from its first track."""
        with self._lock:
            rows = self._db.execute("""
                SELECT p.id, p.name, p.updated_at,
                       (SELECT COUNT(*) FROM playlist_tracks t WHERE t.playlist_id = p.id)
                           AS track_count,
                       (SELECT t.thumb FROM playlist_tracks t WHERE t.playlist_id = p.id
                        ORDER BY t.position LIMIT 1) AS thumb
                FROM playlists p ORDER BY p.updated_at DESC
            """).fetchall()
        return [{"kind": "local_playlist", "id": str(r["id"]), "playlist_id": r["id"],
                 "title": r["name"], "thumb": r["thumb"], "count": r["track_count"],
                 "updated_at": r["updated_at"],
                 "subtitle": f"{r['track_count']} song{'s' if r['track_count'] != 1 else ''}"}
                for r in rows]

    def playlist(self, playlist_id: int) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM playlists WHERE id = ?",
                                   (playlist_id,)).fetchone()
        if row is None:
            return None
        tracks = self.playlist_tracks(playlist_id)
        return {"kind": "local_playlist", "id": str(row["id"]), "playlist_id": row["id"],
                "title": row["name"], "tracks": tracks, "count": len(tracks),
                "thumb": tracks[0]["thumb"] if tracks else None}

    def playlist_tracks(self, playlist_id: int) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM playlist_tracks WHERE playlist_id = ? ORDER BY position",
                (playlist_id,)).fetchall()
        return [_row_to_track(r) for r in rows]

    def _next_position(self, playlist_id: int) -> int:
        row = self._db.execute(
            "SELECT COALESCE(MAX(position), -1) + 1 AS next FROM playlist_tracks "
            "WHERE playlist_id = ?", (playlist_id,)).fetchone()
        return int(row["next"])

    def add_to_playlist(self, playlist_id: int, track: dict) -> bool:
        """Returns False when the track is already in the playlist."""
        if not track.get("id"):
            return False
        with self._lock:
            existing = self._db.execute(
                "SELECT 1 FROM playlist_tracks WHERE playlist_id = ? AND video_id = ?",
                (playlist_id, track["id"])).fetchone()
            if existing:
                return False
            self._db.execute(
                "INSERT INTO playlist_tracks "
                "(playlist_id, video_id, title, subtitle, thumb, duration, source, url, "
                "position, added_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                (playlist_id, track["id"], track.get("title", ""), track.get("subtitle", ""),
                 track.get("thumb"), track.get("duration", 0),
                 track.get("source"), track.get("url"),
                 self._next_position(playlist_id), time.time()))
            self._db.execute("UPDATE playlists SET updated_at = ? WHERE id = ?",
                             (time.time(), playlist_id))
            self._db.commit()
        return True

    def add_many_to_playlist(self, playlist_id: int, tracks: list[dict]) -> int:
        return sum(1 for track in tracks if self.add_to_playlist(playlist_id, track))

    def remove_from_playlist(self, playlist_id: int, video_id: str) -> None:
        with self._lock:
            self._db.execute(
                "DELETE FROM playlist_tracks WHERE playlist_id = ? AND video_id = ?",
                (playlist_id, video_id))
            self._db.execute("UPDATE playlists SET updated_at = ? WHERE id = ?",
                             (time.time(), playlist_id))
            self._db.commit()
        self._renumber(playlist_id)

    def move_track(self, playlist_id: int, video_id: str, delta: int) -> None:
        """Shift a track up (-1) or down (+1) in the playlist."""
        tracks = self.playlist_tracks(playlist_id)
        ids = [t["id"] for t in tracks]
        if video_id not in ids:
            return
        index = ids.index(video_id)
        target = index + delta
        if not 0 <= target < len(ids):
            return
        ids[index], ids[target] = ids[target], ids[index]
        with self._lock:
            for position, vid in enumerate(ids):
                self._db.execute(
                    "UPDATE playlist_tracks SET position = ? "
                    "WHERE playlist_id = ? AND video_id = ?", (position, playlist_id, vid))
            self._db.execute("UPDATE playlists SET updated_at = ? WHERE id = ?",
                             (time.time(), playlist_id))
            self._db.commit()

    def _renumber(self, playlist_id: int) -> None:
        with self._lock:
            rows = self._db.execute(
                "SELECT video_id FROM playlist_tracks WHERE playlist_id = ? ORDER BY position",
                (playlist_id,)).fetchall()
            for position, row in enumerate(rows):
                self._db.execute(
                    "UPDATE playlist_tracks SET position = ? "
                    "WHERE playlist_id = ? AND video_id = ?",
                    (position, playlist_id, row["video_id"]))
            self._db.commit()

    # ----------------------------------------------------------- local music

    def replace_local_tracks(self, root: str, tracks: list[dict]) -> int:
        """Swap in a fresh index for one music folder, in a single transaction.

        Replacing wholesale is what makes a rescan pick up deletions and
        retags; there is no partial state a crash can leave behind.
        """
        now = time.time()
        with self._lock:
            self._db.execute("DELETE FROM local_tracks WHERE root = ?", (root,))
            self._db.executemany(
                "INSERT OR REPLACE INTO local_tracks "
                "(path, root, title, artist, album, track_no, duration, thumb, indexed_at) "
                "VALUES (?,?,?,?,?,?,?,?,?)",
                [(t["url"], root, t["title"], t.get("subtitle") or "",
                  t.get("album") or "", int(t.get("track_number") or 0),
                  int(t.get("duration") or 0), t.get("thumb"), now)
                 for t in tracks])
            self._db.commit()
        return len(tracks)

    def forget_local_root(self, root: str) -> None:
        with self._lock:
            self._db.execute("DELETE FROM local_tracks WHERE root = ?", (root,))
            self._db.commit()

    @staticmethod
    def _local_row(row) -> dict:
        return {
            "kind": "song",
            "id": f"local:{row['path']}",
            "title": row["title"],
            "subtitle": row["artist"] or "",
            "album": row["album"] or "",
            "duration": row["duration"] or 0,
            "thumb": row["thumb"],
            "source": "local",
            "url": row["path"],
            "track_number": row["track_no"] or 0,
        }

    def local_tracks(self, album: str | None = None,
                     artist: str | None = None) -> list[dict]:
        sql = "SELECT * FROM local_tracks"
        args: tuple = ()
        if album is not None:
            sql += " WHERE album = ?"
            args = (album,)
        elif artist is not None:
            sql += " WHERE artist = ?"
            args = (artist,)
        sql += " ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE, track_no, title"
        with self._lock:
            rows = self._db.execute(sql, args).fetchall()
        return [self._local_row(r) for r in rows]

    def local_count(self) -> int:
        with self._lock:
            return self._db.execute("SELECT COUNT(*) FROM local_tracks").fetchone()[0]

    def local_albums(self) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT album, MIN(artist) AS artist, COUNT(*) AS n, "
                "       MAX(thumb) AS thumb FROM local_tracks "
                "WHERE album <> '' GROUP BY album ORDER BY album COLLATE NOCASE"
            ).fetchall()
        return [{"kind": "album", "id": f"local-album:{r['album']}", "title": r["album"],
                 "subtitle": r["artist"] or "", "thumb": r["thumb"], "count": r["n"],
                 "source": "local"} for r in rows]

    def local_artists(self) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT artist, COUNT(*) AS n, MAX(thumb) AS thumb FROM local_tracks "
                "WHERE artist <> '' GROUP BY artist ORDER BY artist COLLATE NOCASE"
            ).fetchall()
        return [{"kind": "artist", "id": f"local-artist:{r['artist']}", "title": r["artist"],
                 "subtitle": f"{r['n']} song{'s' if r['n'] != 1 else ''}",
                 "thumb": r["thumb"], "count": r["n"], "source": "local"} for r in rows]

    def local_track(self, relative: str) -> dict | None:
        with self._lock:
            row = self._db.execute(
                "SELECT * FROM local_tracks WHERE path = ?", (relative,)).fetchone()
        return self._local_row(row) if row else None
