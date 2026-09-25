# Muzika library sync format

Syncthing, Dropbox, Nextcloud and OpenCloud all do the same thing: sync a
folder. So Muzika does not integrate with any of them. It writes one file into
a folder you already sync, and whichever service you run carries it.

No API keys, no OAuth, no account linking, and it works with any service that
can sync a directory — including ones not listed here.

## The file

`muzika-library.json`, written into the folder you choose.

```json
{
  "format": "muzika-library",
  "version": 1,
  "updated_at": 1790000000.0,
  "device": "fedora",
  "playlists": [
    {
      "name": "Road Trip",
      "updated_at": 1790000000.0,
      "tracks": [
        {"id": "khnokW3Mw24", "title": "Instant Crush",
         "artist": "Daft Punk", "duration": 337, "thumb": "https://…"}
      ]
    }
  ],
  "favourites": [
    {"id": "…", "title": "…", "artist": "…", "duration": 0, "thumb": null}
  ]
}
```

`id` is the YouTube video id, which is what makes the entry portable: any
client that can play a YouTube video id can play the track.

## Saved albums, artists and playlists

`library` carries what you have saved from the catalogue, as opposed to
playlists you built yourself:

```json
{
  "library": [
    {"kind": "album",    "id": "MPREb_…", "title": "Painkiller", "subtitle": "Judas Priest", "thumb": "…"},
    {"kind": "artist",   "id": "UC…",     "title": "Thundertale", "subtitle": "4.37K subscribers", "thumb": "…"},
    {"kind": "playlist", "id": "VLPL…",   "title": "Katedra", "subtitle": "Milda Drums", "thumb": "…"}
  ]
}
```

`kind` is one of `album`, `artist` or `playlist`, and `id` is the YouTube Music
browse id, so either client can open the real page from it. The key is additive:
a reader that predates it carries on with `playlists` and `favourites`.

## Music files

The library file describes music; it never contains audio. Files travel by
sitting in the synced folder:

```
<sync folder>/Music/<library folder name>/<whatever structure it had>
```

A local track's `id` is `local:` followed by that path *relative to `Music/`*,
and its `url` is the same relative path. Both clients compute it identically,
which is what lets a playlist mixing YouTube and local songs resolve on either
device. `source` is `"local"`.

Muzika copies into that folder and never moves the originals. Whichever
service the user runs — Syncthing, the Dropbox app, Nextcloud, OpenCloud —
carries the files; nothing here re-implements file transfer.

## Merge rules

Deliberately simple, because a sync conflict resolved cleverly and wrongly is
worse than one resolved predictably.

- **Playlists are matched by name**, case-insensitively.
- A playlist that exists on only one side is **created** on the other.
- When both sides have it and the track lists differ, the side with the newer
  `updated_at` **replaces** the other. The lists are not merged into something
  neither device chose.
- **Favourites are unioned.** A favourite is never removed by a sync.
- **Saved items are unioned**, on `(kind, id)`. Unsaving an album on one device
  does not unsave it on the other.
- Importing twice changes nothing — the operation is idempotent.

Deletions do not propagate. Removing a track on one device and syncing will
not remove it elsewhere unless that playlist is also the newer one. This is a
deliberate trade: silent data loss across devices is the worse failure.

## Writing

The file is written to a temporary name in the same directory and then
`os.replace()`d into place, so a sync client never picks up a half-written
file.
