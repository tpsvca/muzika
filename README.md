# Muzika

A music player for **Linux desktop** and **Android** that needs no account,
and keeps your library in sync between them.

It plays from YouTube Music, SoundCloud, Bandcamp and your own audio files.
There is no sign-in, no API key, and nothing about what you listen to is sent
anywhere — the suggestions on the home page are worked out on your own device
from your own play history.

| Desktop (GTK4 / libadwaita) | Android (Compose / Material 3) |
|---|---|
| ![desktop](docs/desktop/home.png) | ![android](docs/android/home.png) |

## What it does

- **Four sources.** YouTube Music, SoundCloud, Bandcamp, and local audio files
  (mp3, flac, m4a, ogg, opus, wav…) with their tags and cover art.
- **Search** across songs, albums, artists and playlists.
- **Explore** YouTube Music's moods and genres — 36 categories of curated
  playlists.
- **Made for you.** Your most-played artists seed radios; the home page is
  built from them. No account, no profile on anyone's server.
- **Your library.** Playlists you build, liked songs, saved albums and artists,
  listening history.
- **Lyrics**, time-synced and following the song, from LRCLIB, NetEase and
  KuGou when YouTube Music has none.
- **Sync between devices** through one small JSON file in a folder you already
  sync — Syncthing, Dropbox, Nextcloud, OpenCloud. Your music files can travel
  the same way.
- **System integration**: MPRIS on the desktop; media notification, lock-screen
  controls, a home-screen widget and launcher shortcuts on Android.

<p align="center">
  <img src="docs/android/explore.png" width="30%" />
  <img src="docs/android/search.png" width="30%" />
  <img src="docs/android/media-controls.png" width="30%" />
</p>

## Why this exists

The desktop player began because AudioTube is a Kirigami (KDE) app: on GNOME it
works but never looks at home, and its structural widgets are KDE idioms that
cannot be restyled into Adwaita ones. The Android app began because every
existing client had stopped being able to play anything without a Google
account.

## Install

### Android

Download the APK from the [latest release](../../releases/latest) and open it.
Android 8.0 (API 26) or newer.

On first launch it asks for all-files access. That is used for one thing: the
sync folder holding `muzika-library.json` and, if you want it, your music.

### Linux desktop

GTK4, libadwaita and GStreamer come from your distribution; everything else is
a normal Python install.

```bash
# Fedora
sudo dnf install python3-gobject gtk4 libadwaita \
    gstreamer1-plugins-good gstreamer1-plugins-bad-free

# Debian / Ubuntu
sudo apt install python3-gi python3-gi-cairo gir1.2-gtk-4.0 gir1.2-adw-1 \
    gstreamer1.0-plugins-good gstreamer1.0-plugins-bad

# Arch
sudo pacman -S python-gobject gtk4 libadwaita gst-plugins-good gst-plugins-bad
```

Then:

```bash
git clone https://github.com/tpsvca/muzika.git
cd muzika/desktop
./install.sh          # installs into ~/.local, adds the menu entry and icon
```

Or run it straight from the checkout without installing anything:

```bash
cd muzika/desktop && ./bin/muzika
```

`yt-dlp` and `ytmusicapi` are what keep extraction working as the services
change. Keep them current:

```bash
pip install --user --upgrade yt-dlp ytmusicapi
```

## Syncing your library

Both apps read and write **one file**, `muzika-library.json`, in a folder of
your choosing. Point them at a folder your sync service already carries and the
two ends meet — there is no Muzika server and no account.

Set the folder in **Settings → Sync** on both devices (the Android default is
`/storage/emulated/0/Muzika`). Dropbox can also be used directly over its API
with a token you generate yourself.

To carry the audio too, add a music folder in **Settings → Music** on the
desktop and press *Copy music into the sync folder*. The format is documented
in [`docs/sync-format.md`](docs/sync-format.md).

## Building

### Android

```bash
cd android
./gradlew assembleDebug        # app/build/outputs/apk/debug/
```

Needs JDK 17 and an Android SDK; point `local.properties` at it with
`sdk.dir=/path/to/Android/Sdk`, or set `ANDROID_HOME`.

`app/src/test` contains live tests that hit YouTube Music for real and are the
fastest way to find out whether a parser has gone stale:

```bash
./gradlew testDebugUnitTest
```

### Desktop

```bash
cd desktop && pip install --user -e .
```

## How it works, briefly

YouTube Music's `browse`, `search` and `next` endpoints answer **anonymously**;
only its `player` endpoint demands a login. So the catalogue — search, albums,
artists, moods, radios — comes from InnerTube directly, while the audio stream
is resolved by [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
on Android and [yt-dlp](https://github.com/yt-dlp/yt-dlp) on the desktop. That
split is the whole reason this works without an account.

## Contributing

Issues and pull requests are welcome. Keep changes focused, and say in the PR
what you actually verified rather than what you expect to work.

## Legal

Muzika is an independent project. It is not affiliated with, endorsed by or
connected to Google, YouTube, SoundCloud or Bandcamp in any way.

It extracts publicly reachable streams the same way NewPipe and yt-dlp do.
Doing so may conflict with those services' Terms of Service in your
jurisdiction, and you are responsible for how you use it. Nothing here
circumvents paid subscriptions, DRM or access controls, and no content is
hosted or redistributed.

## Licence

[GPL-3.0-or-later](LICENSE).

The Android app links [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor),
which is GPL-3.0, so the GPL is not a preference here — it is a requirement.
