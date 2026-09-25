# Upstream report: AudioTube fails with `KeyError: 'endpoint'` on Fedora

Written while building Muzika, kept here so it can be filed upstream. **Not yet
submitted** — it needs an account on the relevant tracker.

## Where this belongs

Primarily **Fedora's Flatpak runtime**, not AudioTube: the fault is a
dependency version shipped by `org.fedoraproject.Platform.YtDlp`. A secondary,
smaller suggestion for AudioTube is at the end.

## Symptom

`org.kde.audiotube` (Fedora Flatpak) raises `KeyError: 'endpoint'` on playback.
Every track fails; the UI gives no usable explanation.

## Cause

The runtime extension `org.fedoraproject.Platform.YtDlp` ships **ytmusicapi
1.10.2**, while AudioTube 26.08 expects **1.12.2**. In the older version,
`get_tab_browse_id()` reads `tabRenderer["endpoint"]` — a key YouTube Music no
longer sends. Newer ytmusicapi handles its absence.

AudioTube already knows the version it wants: it logs *"tested and supported
version is X"* at startup.

## Reproduce

1. Fedora, `flatpak install org.kde.audiotube` from Fedora's remote.
2. Play any track.
3. `KeyError: 'endpoint'`.

`flatpak update` does **not** fix it — the remote extension carries the same
build date and a 0-byte delta.

## Workaround

Overlay a current ytmusicapi ahead of the bundled one:

```bash
flatpak override --user org.kde.audiotube \
  --filesystem=$HOME/.local/share/audiotube-ytmusicapi:ro \
  --env=PYTHONPATH=$HOME/.local/share/audiotube-ytmusicapi:/app/extensions/yt-dlp/python3
```

PYTHONPATH order matters — the app's own path must stay second. Undo with
`flatpak override --user --reset org.kde.audiotube`.

Pin the exact version AudioTube names in its log rather than the newest; it
warns on anything else.

## Suggested fixes

**Fedora** — rebuild `org.fedoraproject.Platform.YtDlp` against a current
ytmusicapi, and bump the build so `flatpak update` actually delivers it.

**AudioTube** (optional, defensive) — it already compares the installed
ytmusicapi against the version it was tested with, but only logs the result. On
an extraction failure, surfacing that mismatch in the UI would turn an opaque
`KeyError` into "the ytmusicapi shipped by your runtime is older than this
build expects". That is the difference between a bug report and an hour lost.
