# Muzika — desktop

GTK4 / libadwaita player for Linux. Python, GStreamer, `ytmusicapi` + `yt-dlp`.

See the [main README](../README.md) for what it does, screenshots and install
instructions, and [`../docs/sync-format.md`](../docs/sync-format.md) for the
file it shares with the Android app.

```bash
./install.sh     # into ~/.local, with menu entry and icon
./bin/muzika     # or just run it from here
```

## Layout

| Path | What lives there |
|---|---|
| `muzika/api.py` | ytmusicapi + yt-dlp, normalisation, lyrics chain |
| `muzika/sources.py` | SoundCloud and Bandcamp search |
| `muzika/local.py` | scanning local files, reading tags, sharing to the phone |
| `muzika/player.py` | GStreamer playbin3, the queue |
| `muzika/db.py` | SQLite: playlists, favourites, saved items, local index |
| `muzika/sync.py` | the library file, and the folder backend |
| `muzika/dropbox.py` | the Dropbox backend |
| `muzika/settings.py` | the Settings dialog |
| `muzika/*.py` (rest) | the windows, pages and widgets |
