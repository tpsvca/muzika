# Contributing

Thanks for looking. A few things that will save you time.

## Where things are

| Path | What lives there |
|---|---|
| `desktop/` | GTK4 / libadwaita player, Python |
| `android/` | Kotlin + Jetpack Compose app |
| `docs/sync-format.md` | the file both apps share — read this before touching sync |
| `docs/audio-sources-feasibility-2026-09-26.md` | which services can be added as sources, and which cannot — read before proposing one |

## Before you open a PR

- **Say what you verified, not what you expect to work.** "Played three tracks
  from a mood page on a Pixel 7 Pro" is useful. "Should work" is not.
- Keep the change focused. One problem per PR.
- Match the surrounding style. Comments explain *why*, not *what*.

## The part most likely to break

Extraction. YouTube Music changes its response shapes without warning, and when
it does, screens go empty rather than throwing. The Android app has live tests
that hit the real API for exactly this reason:

```bash
cd android && ./gradlew testDebugUnitTest
```

They are the fastest way to find out whether a parser has gone stale. If you
change anything in `sources/Innertube.kt`, run them.

The desktop has its own tests, which need no network:

```bash
cd desktop && pip install -e '.[dev]' && pytest
```

They cover the sync merge rules and local-library identifiers — the two places
where a mistake quietly corrupts a library on someone else's device.

## Sync changes

`muzika-library.json` is read and written by both apps and by older versions of
each. Two rules:

- **Additive only.** A reader that does not know your new key must still work.
- **Never make deletions propagate.** Favourites and saved items are unioned on
  purpose: silent data loss across devices is the worse failure.

## Local music

A local track's id is its path *inside the shared layout*
(`<library folder>/<path>`), identical on every device. If you change how that
is computed, change it in `desktop/muzika/local.py` **and**
`android/.../data/LocalMusic.kt` together, or every synced playlist entry
pointing at a local file will dangle.

## Releases

Tag it and CI does the rest:

```bash
git tag v1.2.3 && git push origin v1.2.3
```

`versionCode` is derived from the tag (1.2.3 → 10203), so it always increases.
Signing comes from repo secrets; a fork with none set gets an unsigned APK
rather than a failed build.
