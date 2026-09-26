"""Turning a YouTube title into something a lyrics database will match.

These are the cases measured against a real library on 2026-09-26, where the
words existed on LRCLIB and the lookup could not find them. No network: the
guessing is pure string work, and that is what is being pinned.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from muzika.api import _title_variants  # noqa: E402


def guesses(title: str, artist: str = "") -> list[tuple[str, str]]:
    return _title_variants(title, artist)


def test_song_dash_artist_is_read_the_right_way_round():
    """The regression: only "Artist - Song" used to be tried.

    Stored this way, the lookup searched for a song called "Beth Hart" by an
    artist called "I'd Rather Go Blind" - and found nothing, while twenty
    timed versions sat on LRCLIB under the obvious reading.
    """
    out = guesses("I'd Rather Go Blind - Beth Hart")
    assert ("I'd Rather Go Blind", "Beth Hart") in out


def test_artist_dash_song_still_works():
    """The orientation that already worked must not regress."""
    out = guesses("Metallica - Nothing Else Matters")
    assert ("Nothing Else Matters", "Metallica") in out


def test_both_orientations_are_offered_when_it_is_ambiguous():
    """A bare dash is genuinely ambiguous, so try it both ways."""
    out = guesses("Alpha - Beta")
    assert ("Beta", "Alpha") in out
    assert ("Alpha", "Beta") in out


def test_bracketed_noise_is_dropped():
    out = guesses("Melissa (Whisper Sessions) (feat. Derek Trucks)",
                  "The Allman Brothers Band")
    assert ("Melissa", "The Allman Brothers Band") in out


def test_a_known_artist_is_preferred_over_one_guessed_from_the_title():
    """When the track carries an artist, that guess should come first."""
    out = guesses("Metallica - One", "Metallica")
    assert out[0][1] == "Metallica"


def test_the_plain_title_is_always_tried():
    out = guesses("Endure the Plight", "Lamb of God")
    assert ("Endure the Plight", "Lamb of God") in out


def test_no_duplicates():
    out = guesses("Metallica - One", "Metallica")
    assert len(out) == len(set(out))


def test_nothing_blank_is_offered():
    for title, _artist in guesses(" - ", ""):
        assert title.strip()
