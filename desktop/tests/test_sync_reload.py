"""What the window does after the sync file brought something in.

The regression pinned here: a playlist grown from 100 to 273 tracks showed up
on the phone at once and stayed at 100 on the desktop. The import had run and
reported nothing new - correctly, because the rows were already in the database
- and "nothing imported" was being read as "nothing to show", so the views were
never rebuilt and kept their stale counts.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from muzika.sync import reload_actions  # noqa: E402


def result(ok: bool = True, **counts) -> dict:
    return {"ok": ok, **counts}


def test_a_quiet_import_still_rebuilds_the_views():
    """The regression. The rows were already there; only the screen was wrong."""
    refresh, announce = reload_actions(result(tracks_added=0))
    assert refresh, "the views must be rebuilt even when the import added nothing"


def test_a_quiet_import_says_nothing():
    """Rebuilding is free and silent; a toast with no news is not."""
    _refresh, announce = reload_actions(result(tracks_added=0))
    assert not announce


def test_an_import_that_brought_something_is_announced():
    refresh, announce = reload_actions(result(tracks_added=173))
    assert refresh and announce


def test_every_kind_of_change_counts_as_news():
    for key in ("playlists_added", "playlists_updated", "tracks_added",
                "favourites_added", "library_added"):
        _refresh, announce = reload_actions(result(**{key: 1}))
        assert announce, f"{key} should count as something to report"


def test_a_failed_import_changes_nothing():
    """A half-read or foreign file must not be presented as an update."""
    assert reload_actions(result(ok=False)) == (False, False)


def test_a_result_with_no_counts_at_all_is_handled():
    refresh, announce = reload_actions({"ok": True})
    assert refresh and not announce
