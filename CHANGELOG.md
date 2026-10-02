## 2026-10-02 — Importing the full Spotify playlist into MyTop

### Added
- **What**: the remaining tracks from the 467-track Spotify export, taking MyTop from 113 to 467 tracks
- **Why**: the earlier import could only read the 100 tracks Spotify's embed exposes; the CSV export carries the whole playlist
- **How**: each row is searched on YouTube Music and accepted only when the artist matches, so a same-titled song by somebody else is never substituted. The CSV's `Duration` column settles which take to take — within 25 seconds of Spotify's length — which separates a studio cut from a live or extended one.

Duplicates were skipped three ways: against what MyTop already held, against repeats inside the CSV itself, and again on the resolved match, since two different CSV rows can point at the same YouTube track. 354 added, 0 duplicate ids and 0 duplicate title+artist pairs afterwards, every track with a cover.

Two rows needed the spelling fixed before they resolved: the CSV says "Mandowar" for Manowar, and credits "Clint Eastwood" to Electronic Swing Orchestra rather than Gorillaz. Two did not resolve at all and were left out rather than filled with a near-miss: "Booyaka 619" (WWE/P.O.D.) and Rockpile's "Play That Fast Thing (One More Time)".

The library file was written and Syncthing asked to scan it, so the phone picks the playlist up without waiting.

## 2026-10-02 — Giving covers back to tracks already played without one

### Fixed
- **What**: Recently played still showed blank covers after the artwork fix — the fix applied to newly played tracks, but everything already stored without a cover stayed blank
- **Why**: I claimed those would repair themselves. They only would if you happened to play the same track again, which for a radio track is unlikely. That was wrong, and the gaps stayed.
- **How**: the app now repairs them on launch, quietly and in the background. First from playlists and favourites, where the cover is already known and nothing need be fetched; then by looking up whatever is left. It touches only rows missing a cover, a handful at a time, so it costs nothing on a healthy library.

On this library: 10 tracks were blank, 1 was filled from a playlist with no network at all, 8 more were resolved by lookup. The last one does not resolve — the id no longer returns anything — and is left alone rather than given something invented.

### Checked — Android does not share the fault
Its radio parser reads the thumbnail from its own JSON path and always did. Probed live against four paths: search songs 20/20 with artwork, artist page 5/5, radio 50/50, artist catalogue 151/151. No change needed there.

### Noted
`window.py` had no logger, so the first line of logging added to it would have thrown `NameError` at runtime. Lint caught it; one has been declared, matching the other modules.

## 2026-10-02 — Radio tracks arrived with no artwork

### Fixed
- **What**: everything played through a radio or an artist shuffle showed the placeholder note instead of its cover — in Now playing, Up next, and then in Recently played once it reached the history
- **Why**: a watch playlist, which is what a radio and an artist shuffle return, spells the field `thumbnail`. Search, album and artist results spell it `thumbnails`. The extractor read only the plural, so every radio track came back with nothing. Introduced with the artist radio and shuffle work, and missed because the artist page's own tracks come from a different endpoint and looked fine.
- **How**: both spellings are accepted, and a lone thumbnail object is tolerated as well as a list of sizes.

### Measured
| | before | after |
|---|---|---|
| radio tracks with artwork | 0/49 | **49/49** |
| artist shuffle with artwork | 0/49 | **49/49** |
| artist page (unchanged) | 5/5 | 5/5 |
| search results (unchanged) | 36/36 | 36/36 |

### Already stored
Of 253 history rows, 10 were saved without artwork; no playlist row was affected. Those repair themselves — each play writes a fresh row and the view takes the newest for a track, so playing one again picks up its cover.

## 2026-10-02 — The artist radio button said "repeat"

### Fixed
- **What**: the new radio button on the artist page was unreadable — an icon-only button in a row of icon-only buttons, drawn with the **repeat** symbol, which already means repeat on the player bar
- **Why**: Adwaita ships no radio-receiver icon. `radio-symbolic` sounds right but lives in the `ui/` set and is the form-control radio button, a plain circle, so reaching for it would have been worse.
- **How**: it is now a labelled **Radio** button, like Play and Shuffle beside it, so the meaning does not rest on an icon at all. The wireless arcs stand in for broadcast, and the tooltip says "Endless mix based on <artist>" rather than restating the label.

## 2026-10-02 — Desktop: shuffling an artist uses their whole catalogue

### Fixed
- **What**: shuffling an artist on the desktop played only the five songs listed on their page — "Shuffling 5 songs" for an artist with hundreds
- **Why**: the button shuffled whatever the page happened to show, which is a "top songs" shelf of about five. The same fault was fixed on Android last week; the desktop was left behind because that is where it was reported.
- **How**: YouTube Music publishes a shuffle playlist covering an artist's whole catalogue on the same page, and `ytmusicapi` already hands it over as `shuffleId` — it was being parsed and then ignored. Following it gives **234 tracks for P!nk instead of 5**. The page's own tracks remain the fallback for artists without one.

This is a better approach than the one taken on Android, which walks the album shelves and fetches up to twelve of them. Worth porting back.

### Added
An artist radio button on the artist page, from the `radioId` the page already carried — 98 tracks when tried.

### Measured
| | before | after |
|---|---|---|
| P!nk, shuffle | 5 tracks | **234** |
| P!nk, radio | not offered | 98 |

## 2026-10-01 — Widget playlists: artwork, and a tap that answers back

### Changed — covers instead of wrapped text
Each playlist is now its cover with a short label underneath, rather than a name that wrapped and clipped.

**Four, not five.** The widget has roughly 320dp of usable width. Five chips leave about 56dp each, which forces the cover down to ~40dp and the label under 9sp — smaller than is comfortable to read at arm's length on a home screen. Four gives about 72dp each: a 44dp cover with an 11sp label. Artwork alone was tempting and would have fitted five, but a playlist's cover here is just its first track's artwork, so two playlists can look alike; the label is what keeps them apart. The row needs more height for this, so it now appears above 150dp rather than 120dp.

### Fixed — tapping a playlist looked like nothing happened
Resolving a stream takes a second or two. In that gap the widget still showed the previous track with no sign the tap had landed, which reads as a dead button and invites a second press — and a second press would have started something else.

The tapped playlist's name now appears immediately, with **Starting…** beneath it, before anything touches the network. The real track replaces it the moment sound actually arrives. The name is read from the database rather than passed through the intent, so the acknowledgement is correct even for a widget drawn before the playlist was renamed.

Covers are cached once fetched, so a redraw does not refetch them, and the widget redraws itself when they arrive rather than waiting for the next update.

### Not verified on device
No widget is placed on the test phone's home screen and ADB cannot place one, so the layout has been built and its logic tested but not seen. Worth a look on the phone that has one.

## 2026-10-01 — Widget: a second row of your busiest playlists

### Added
- **What**: stretch the widget taller and a second row appears with up to four of your playlists. Tapping one shuffles it straight from the home screen.
- **Why "busiest"**: the order is counted from the listening history — every play of a track belonging to a playlist counts towards it — so a playlist you actually listen to rises above one you made and forgot. Playlists with no plays yet still appear, newest first, so a fresh library is not an empty row. Empty playlists are left out.
- Shuffled rather than played in order: the row is for putting something on, not for resuming a particular track.
- The widget now arrives two cells tall by default, and resizing re-renders. Without that last part it kept whatever it last drew, so stretching it appeared to do nothing — which is exactly what it did before this change.

### How the row is built
`RemoteViews` cannot inflate a list, so the four slots exist in the layout and are hidden individually when there is nothing to put in them. The row itself only shows when the widget is at least 120dp tall, decided **per widget** rather than once for all of them, since each can be a different size. The chip background has a light and a dark variant, like the rest of the widget, because `RemoteViews` inflate in the launcher's context and cannot read this app's theme.

### Tests
5 on the selection: that listening beats editing, that a fresh library still fills the row, that empty playlists are skipped, that it never returns more than the slots available, and that an empty library gives an empty row rather than an error. Android 35.

### Not verified on device
The Pixel has no widget placed on its home screen and placing one over ADB is not possible, so the row's appearance has not been seen — only its logic tested. The widget in use is on the other phone.

## 2026-10-01 — Car and headset skip buttons, artist radio, shuffling a whole artist

### Fixed — skip buttons from a car, headset or Bluetooth remote did nothing
- **Why**: those arrive as media-button key events, and Media3 decides whether to act on one by consulting the commands it has cached for the player. Muzika's queue is not ExoPlayer's — ExoPlayer holds one track at a time, so `QueuePlayer` advertises the skip commands itself, but nothing ever tells Media3 they appeared. The event was dropped before the player saw it.
- **Proof**: sending `KEYCODE_MEDIA_NEXT` to the phone — exactly what a car sends — left the track unchanged, and instrumenting `QueuePlayer` showed Media3 never called it at all, not even `hasNextMediaItem`.
- **How**: the session now handles the button itself, which sidesteps that bookkeeping. Verified on the phone: next advances the track, and previous steps back when pressed within five seconds or restarts the current one after that, which is the usual behaviour.

### Added — artist radio
A radio button on the artist page, seeded from their best-known song, which is what "more like this" means in practice.

### Changed — shuffling an artist plays the whole catalogue
The artist page lists a "top songs" shelf of five or ten tracks, and shuffle drew only from those, so it replayed the same handful. It now gathers the album and single shelves too and shuffles the lot. Albums are fetched in parallel and capped at twelve, since an artist with fifty records would otherwise mean fifty round trips before the first note; anything that fails is skipped rather than losing the gather.

### Noted
A test-loop fault is worth recording: `assembleDebug` without `-PmuzikaVersion` builds as version 1.0.0, so installing it over a newer release is refused as a downgrade. Those failures were being discarded, and several rounds of "testing" ran against stale code. Build test APKs with an explicit version above the installed one.

## 2026-09-27 — The shortcut icons vanished in dark mode on some phones

### Fixed
- **What**: the **Liked songs** and **Recently played** tiles showed a blank white square in dark mode on a Nothing phone, while looking correct in light mode and correct on a Pixel
- **Why**: the icon was drawn with a hardcoded `Color.White` on a background of `colorScheme.primary`. That only works on a palette where `primary` is dark. Nothing OS supplies a **monochrome dynamic palette**, so in dark mode `primary` *is* white — white icon, white background, nothing to see. In light mode the same palette makes `primary` black, which is why only dark mode was affected, and the app's own green palette pairs a light `primary` with a dark `onPrimary`, which is why a Pixel showed it correctly.
- **How**: the icon now uses `colorScheme.onPrimary`, which is by definition the colour Material intends to sit on `primary`, so it is correct in every scheme — the app's own, and any wallpaper-derived one. A caller-supplied tint is an arbitrary colour rather than a theme role, so that path picks whichever of black or white actually contrasts with it.

`tintFor`, used by the mood tiles, always returns lightness 0.55 whatever the theme, so white text on those is stable and was left alone.

### Verified
Builds clean and the suite passes. The visual check on the test device was cut short — the phone locked itself mid-test — and the device that actually shows the fault is not reachable over ADB, so the fix rests on the palette reasoning above rather than a screenshot.

## 2026-09-27 — Android: coming back from a result no longer wipes the search

### Fixed
- **What**: searching, opening a result and pressing back left an empty search box with no results and the keyboard up, as though the search had never happened
- **Why**: the root renders a tab only while nothing is pushed on top of it. Opening a result pushes a screen, so the Search tab leaves composition entirely and every `remember` inside it — the query, the filter, the results — is discarded. Coming back rebuilt it from scratch.
- **How**: that state now lives outside composition, so back returns to exactly the search you left. `searching` is deliberately left behind: the coroutine running a search is tied to composition and cancelled on the way out, so a spinner restored on return would never stop.
- The auto-focus was already conditional on there being no submitted query, so restoring the query also stops the keyboard reappearing over the results.

### Fixed — while in there
The offline state was declared and read but **never assigned**, so it could not appear at all: a search that failed because the network was down showed the ordinary "Nothing found" toast. It is now set from whether the catalogue call actually failed, and the toast is suppressed in that case, since an empty result and a dead connection are not the same thing.

### Verified
On the device, not just in a test: searched, opened an artist, pressed back — query kept, results kept.

## 2026-09-26 — Push a library change out immediately instead of waiting

### Added
- **What**: after every library write, and on **Sync now**, the desktop asks the local Syncthing to scan the folder at once
- **Why**: Syncthing notices a changed file through its filesystem watcher, which waits out `fsWatcherDelayS` — five seconds by default. Sensible for a folder of documents, needless for one small file we have just deliberately written. Measured between two machines: **8 s without the nudge, 3 s with it**.
- Best-effort and silent throughout. The folder backend has to keep working with Nextcloud, Dropbox or a plain network share, so Syncthing being absent, stopped, or not sharing this folder are all normal and cost nothing but a skipped call. The config is re-read whenever the sync folder changes, since the folder may have moved between shares.

### Not on Android
The Syncthing app keeps its configuration in private storage, so another app cannot read the API key it would need. Android writes the file immediately, as it already did, and its changes travel on Syncthing's own schedule.

### Already worked — measured, not assumed
The receiving app needs no prodding: it watches the library file and reloads by itself. Against the running desktop app, each of these landed in **about two seconds**, with no restart:

| change written to the file | picked up |
|---|---|
| a playlist added, by atomic rename as Syncthing lands a file | ~2 s |
| a track removed from a playlist | ~2 s |
| the whole library restored | ~2 s |

So the round trip is roughly Syncthing's delay plus two seconds, and the nudge is aimed at the larger half.

### Tests
10 tests on the cases that matter — an unshared path, no Syncthing at all, a config with the GUI off, a corrupt config, and an API that refuses the connection. None may raise, and none may reach the network when there is no folder to scan. Desktop 63.

## 2026-09-26 — Android: remove a song from your playlist without hunting for it

### Added
- **What**: **Remove from "<playlist>"** in the ⋮ menu of any song inside one of your own playlists
- **Why**: taking a song out was only possible after switching the screen into its reorder mode. The button that gets you there is labelled **Reorder**, which is not where anyone looks to delete something — so for practical purposes the feature was missing. It is now where the rest of the per-song actions already are.

### How it decides
The menu previously had no idea which list you were looking at, so it could not offer removal at all. It now carries an optional `PlaylistContext`, and only the rows inside one of your own playlists pass one. Everywhere else — YouTube Music albums and playlists, search results, Home shelves, the library — passes nothing and the item does not appear. A YouTube Music playlist is not ours to edit, and a song on its own has no playlist to be removed from.

Removal writes through `Store.removeFromPlaylist` and pushes the sync file, exactly as the reorder mode already did, so the other devices see it. The playlist screen reloads on its own because the change bumps the library version it watches.

The reorder mode keeps its own remove button; this adds a second, more obvious route rather than replacing it.

### Desktop
Unchanged — it already has a per-row remove button on its playlist page.

## 2026-09-26 — Desktop: no second transport bar inside Now Playing

### Removed
- **What**: the compact player bar added to the desktop's Lyrics and Queue tabs
- **Why**: it was a mistake. The desktop window already carries a player bar along its bottom edge at all times, so this stacked the same controls twice — two seek sliders and two play buttons, one directly above the other. The reasoning that produced it came from Android, where the Now Playing sheet covers the whole screen and genuinely has no other controls. That case is real and **the Android bar stays**; the desktop never had the problem it was solving.

### Tests
The three tests that asserted the bar appears are replaced by one asserting the opposite — that no tab of Now Playing grows a transport bar of its own — plus a check that removing it did not take the Song tab's own seek bar with it. Desktop 53.

## 2026-09-26 — "Forbidden" no longer ends the song

### Fixed
- **What**: playback occasionally stopped with a **Forbidden** toast and would not start again
- **Why**: YouTube hands back stream URLs that extract fine and then refuse the first byte — a stale player cache, an expired token, a different address. `stream()` already retried, but only when *extraction* raised; a 403 arrives later, from GStreamer, when the URL is actually fetched. The bus error handler simply gave up. Worse, the dead URL stayed in the stream cache, so pressing play again replayed the same dead address until it expired — up to half an hour of a song refusing to play.
- **How**: a Forbidden on the bus now drops that URL from the cache, clears yt-dlp's player cache and resolves once more before giving up. One retry per track, so a genuinely dead track cannot loop, and the retry is earned back after a few seconds of real playback so a song going stale twice in one session can still recover the second time.

### Tests
2 desktop tests on the cache invalidation — that only the rejected URL is dropped, and that forgetting an unknown key is harmless. Desktop 57.

## 2026-09-26 — Lyrics: no longer answers with somebody else's song

### Fixed
- **What**: "Open Invitation" by Jade Marie Patek — an English song — was shown timed Japanese words that did not follow the music
- **Why**: nothing verified the artist. LRCLIB carries twenty songs called *Open Invitation*; the artist-qualified search returned none, so the lookup fell back to a title-only search and accepted a stranger's song. The runtime check did not catch it either, because that entry is within five seconds of this track's length. YouTube Music had the correct words all along, but untimed — and "timed from anywhere beats untimed from nearer" chose the wrong song over the right one.
- **How**: a result is now checked against the artists this track could plausibly have, on both apps and for LRCLIB and NetEase alike. With nothing that matches, the provider returns nothing rather than a stranger — so the chain falls through to the correct untimed words instead.

### The part that needed a second attempt
Verifying against the *stored* artist alone made things worse, dropping three correct matches. The stored artist is frequently the uploading channel rather than the act:

| stored artist | title | actually by |
|---|---|---|
| RocKwiz | I'd Rather Go Blind - Beth Hart | Beth Hart |
| Rock s Músicas | Cream - Sunshine Of Your Love (HD) | Cream |
| Emma0815007 | T. Rex - Get It On (1971) HD… | T. Rex |

The performer is usually sitting in the title, so both sides of the dash now count as candidates too. That restores all three while still refusing the wrong *Open Invitation*, whose title has no dash and so offers only the stored artist.

### Measured
Same 18 tracks from the listening history:

| | before | after |
|---|---|---|
| timed | 15/18 | 14/18 |
| untimed | 1/18 | 1/18 |
| nothing | 2/18 | 3/18 |
| **wrong song** | **1** | **0** |

One fewer timed result, and the one lost was the wrong song. The reported track now returns YouTube Music's correct English words, untimed, with the pane saying so.

### Tests
5 desktop tests on artist matching and 1 Android live test that fails if this track is ever answered in CJK script again. Desktop 55, Android 30.

## 2026-09-26 — A big library no longer means a slow sync

### Fixed
- **What**: taking in a synced library got slow in proportion to its size — 2.4 s for 50,000 tracks on the desktop, and over a second for 2,000 on Android
- **Why**: every row was its own transaction. SQLite syncs to disk on each commit, so importing N tracks meant N fsyncs, and the cost was almost entirely waiting for the disk rather than doing any work. The whole import is now one transaction on both sides: `Store.bulk()` on the desktop, `Store.transaction {}` on Android. `add_many_to_playlist` is batched too.

### Measured
Desktop, same synthetic libraries before and after:

| tracks | file | export | import before | import after |
|---|---|---|---|---|
| 500 | 0.10 MB | 4 ms | 24 ms | **4 ms** |
| 5,000 | 1.02 MB | 35 ms | 237 ms | **38 ms** |
| 20,000 | 4.09 MB | 134 ms | 947 ms | **154 ms** |
| 50,000 | 10.24 MB | 340 ms | 2,388 ms | **396 ms** |

Android, on a desktop CPU under Robolectric, so a phone will be slower:

| tracks | file | import before | import after |
|---|---|---|---|
| 100 | 17.8 KB | 387 ms | **171 ms** |
| 500 | 88.4 KB | 787 ms | **370 ms** |
| 2,000 | 353 KB | 1,324 ms | **813 ms** |

### On the format itself
A track costs **181 bytes** on Android and **289** on the desktop, which carries a couple more fields. That is flat — measured across three library sizes the spread is 1.01× — so the file is linear in the number of tracks and holds no per-library overhead. 10,000 tracks is roughly 2 MB and parses in about 10 ms; parsing was never the bottleneck.

### Tests
`SyncScaleTest` guards the thing that would actually hurt: it fails if the cost per track stops being flat, which is what a format that starts repeating itself would look like. Android 29 tests, desktop 50.

## 2026-09-26 — Lyrics: 72% of tracks found timed words, now 83%

### Fixed
- **What**: songs whose timed lyrics were sitting on LRCLIB came back with nothing
- **Why**: two separate matching faults, both measured against a real listening history rather than guessed at.
  - **Only one dash orientation was tried.** A title is split on " - " and the left side taken as the artist. A track stored as `I'd Rather Go Blind - Beth Hart` was therefore searched for as a song called *"Beth Hart"* by an artist called *"I'd Rather Go Blind"*, which finds nothing — while twenty timed versions sat there under the obvious reading. Both orientations are now offered.
  - **The duration filter discarded live and session recordings.** Entries more than 20 seconds from the track's runtime were dropped outright, so an acoustic or session cut never matched the studio timings even though the words are identical. Having already searched on title and artist, a search that finds nothing of the right length now falls back to the rest instead of giving up.

### Measured
Same 18 tracks from the listening history, before and after:

| | before | after |
|---|---|---|
| timed | 13/18 (72%) | **15/18 (83%)** |
| untimed | 1/18 | **0/18** |
| nothing | 4/18 | 3/18 |

The three still missing have no artist recorded at all, or are genuinely absent from every provider.

### Checked and rejected
Five more lyrics services were probed live; none adds timed words, so none was added. `lyrics.ovh` works but serves plain text only; ChartLyrics returns 404 to everything; Textyl no longer resolves; QQ Music's search works but its lyric endpoint is gated and returns empty; Vagalume now needs an API key. Worth knowing before anyone proposes them again: the gap was never the number of providers.

### Tests
8 desktop tests on the title guessing, which needs no network, and an Android live test for the reversed orientation. Totals: desktop 50, Android 28.

## 2026-09-26 — One command to install, verified on five distributions

### Added
- **`desktop/bootstrap.sh`** — takes a bare machine to a working Muzika in one command:

  ```bash
  curl -fsSL https://raw.githubusercontent.com/tpsvca/muzika/main/desktop/bootstrap.sh | bash
  ```

  It works out the distribution, **prints the package command and waits for you to agree** before running anything, then clones and hands over to `install.sh`. It reads the confirmation from `/dev/tty` rather than stdin, because under `curl | bash` stdin is the script itself. `MUZIKA_SRC` chooses where to clone, `MUZIKA_YES=1` skips the prompt. With no terminal and no `MUZIKA_YES` it refuses rather than assuming consent.
- **openSUSE** joins Fedora, Debian/Ubuntu and Arch as a documented target.

### Fixed
- Package lists are now **verified rather than asserted**. Each was installed in a clean container and the app's own preflight run against it:

  | | result |
  |---|---|
  | Arch | packages install, preflight passes |
  | Fedora 42 | packages install, preflight passes |
  | Debian 13 (trixie) | packages install, preflight passes |
  | Ubuntu 25.10 | packages install, preflight passes |
  | openSUSE Tumbleweed | packages install, preflight passes |

  The one-liner itself was then run end to end on Debian 13 and Fedora 42: clone, install, `muzika` on the path, preflight clean.

### Changed
- Arch's command is `pacman -Syu`, not `-S`. A partial upgrade is the wrong thing to recommend on a rolling release, and `-S` alone hits the same stale-index 404 that Debian does.

### Tests
`tests/test_install_docs.py` — 16 tests holding the three copies of the package list together. `bootstrap.sh` must carry its own copy because it runs before the repo exists, `preflight.py` has the authoritative one and the README shows them to people; a fix applied to one and forgotten in the others is exactly how somebody ends up following instructions that cannot work. The tests also pin the specific lessons from this week: Debian refreshes its index first, Arch uses `-Syu`, `python3-venv` is present, and every list carries GStreamer's GObject bindings and not just its plugins. Desktop suite: 42 tests.

## 2026-09-26 — README: what to do when apt reports a 404

### Added
- A troubleshooting note for `404 Not Found` on a `.deb` while apt reports everything up to date. Debian's point releases supersede files and delete the old ones, so an index naming a superseded version asks for a file that exists on no mirror — confirmed against the pool, which carries `python3.13-venv_3.13.5-2+deb13u4`, `+deb13u5` and `3.13.15-1`, but not the plain `3.13.5-2` that apt was requesting. `apt update` does not help, because apt believes its index is current; the index has to be discarded and refetched.

## 2026-09-26 — Refresh the package index before installing

### Fixed
- **What**: the Debian install command could fail with a bare `404 Not Found` on a `.deb`
- **Why**: a package index older than the mirror's current state makes apt ask for a file that has already been removed from the pool — it wanted `python3.13-venv 3.13.5-2` while the mirror had moved on, and fetched `python3-venv 3.13.5-1` alongside it. Nothing about the package list was wrong; the index simply needed refreshing. `sudo apt update &&` is now part of the command, with a note saying why it is there.
- Arch's line is `pacman -Syu` rather than `-S` for the same reason, and because a partial upgrade is the wrong thing to recommend on a rolling release. Fedora and openSUSE refresh their metadata on their own.

## 2026-09-26 — install.sh failed silently on a fresh Debian

### Fixed
- **What**: on a fresh Debian 13 the installer printed `Creating the virtualenv…` and then stopped, with no error and no installed app
- **Why**: `python3-venv` is a separate package on Debian and Ubuntu that nothing else pulls in. Without it `python3 -m venv` cannot create an environment — and Debian's patched `venv/__init__.py` prints its *"you need to install the python3-venv package"* advice with a plain `print()`, i.e. to **stdout**, before exiting 1. The installer sent that step's stdout to `/dev/null`, so `set -e` killed the script and the one message that would have explained it was thrown away.
- **How**: no step discards its output any more. Each runs through a helper that captures stdout and stderr together and prints them if the step fails, so a failure now names itself. Verified by forcing one: the installer reports `That step failed. What it printed: Error: Unable to create directory …` and exits 1, where it used to exit silently.
- `--upgrade-deps` was dropped from the venv call. It made venv reach out to the network to upgrade pip, which is one more thing to fail on a fresh machine for no benefit here.

### Added
- `python3-venv` to the Debian/Ubuntu package list, and a note explaining what Debian splits out and why each piece is needed.
- The preflight now checks that `venv` and `ensurepip` are actually usable before the installer tries, so this is caught up front with the package named rather than discovered halfway through.

## 2026-09-26 — The Linux install instructions were wrong

### Fixed
- **What**: following the README on a fresh Debian installed cleanly and then the app would not start
- **Why**: the app calls `require_version` for **Gst** and **GstPbutils**, whose typelibs live in `gir1.2-gstreamer-1.0` and `gir1.2-gst-plugins-base-1.0`. Neither was in the package list, and nothing else pulls them in — verified: `gstreamer1.0-plugins-good`, `gstreamer1.0-plugins-bad` and `python3-gi` have no dependency on either. The plugin packages carry the plugins, not the GObject bindings. Added to the Debian/Ubuntu, Fedora and Arch lists, plus a new openSUSE list.
- **What**: `install.sh` reported success on systems the app cannot run on
- **Why**: it only checked that `import gi` and `require_version` worked for Gtk and Adw. It never checked Gst, and never checked the libadwaita *version*. The UI uses `Adw.WrapBox` and `Adw.InlineViewSwitcher`, both libadwaita 1.7, so on an older release the install succeeded and the app died later inside building a window.
- **What**: a missing typelib produced a `ValueError` traceback naming no package
- **Why**: `app.py` calls `require_version` at import time, so the failure happened before any check could run. The `muzika` command now points at a small launcher that checks the system first and only then imports Gtk.

### Added
- `muzika/preflight.py` — checks every typelib the code imports and every libadwaita widget the UI builds, then names the packages for the detected distribution. By capability, not by version number, so it stays correct as the UI adopts newer widgets. Run it directly when something is wrong:
  `~/.local/share/muzika/venv/bin/python -m muzika.preflight`
- **Minimum stated in the README**: libadwaita 1.7, i.e. Fedora 42+, Debian 13 (trixie)+, Ubuntu 25.04+, or a rolling release. Debian 12 and Ubuntu 22.04/24.04 LTS ship 1.5 or older and cannot run it.
- **An Updating section**: `cd muzika && git pull && cd desktop && ./install.sh`, which reuses the same virtualenv and leaves playlists, settings and history alone.
- **An "if it will not start" section** pointing at the preflight.

### Changed
- Every install command is now a single line, so it survives copy-paste. The backslash continuations are gone from the README entirely.

## 2026-09-26 — Which services can be added as sources (research, no code)

### Added
- **What**: `docs/audio-sources-feasibility-2026-09-26.md` — a measured study of TikTok, the paid streaming services and the open alternatives, with the probe commands so it can be re-run when the answers go stale
- **Why**: "can we add X?" keeps coming up and the answer is not guessable — it turns on whether a service offers search, whether yt-dlp or NewPipeExtractor can resolve it, and whether Android can reach it at all

### Findings
- **Amazon Music, Spotify, Apple Music, Deezer, Tidal, Qobuz, Pandora, Napster are impossible.** No extractor exists in yt-dlp or NewPipeExtractor for any of them; all serve Widevine-DRM'd audio to a paid account. This is a wall, not a gap — nothing to build against.
- **TikTok cannot be a source.** Extraction works (8 yt-dlp extractors, live pull returned AAC with `track`/`artist`), but no TikTok search exists anywhere, and Android has no way to resolve it. The content is also wrong for a library: sampled durations were 42–120 s, clips rather than songs, often labelled `original sound`. The shape that would work is a desktop "Import from a link" saving into the local library — not built.
- **Three are viable on both platforms**, because each hands over a plain stream URL and so needs no extractor at all: **Audius** (`audio/mpeg`), **Internet Archive** (`audio/flac`) and **Radio Browser** (`audio/aacp`). All probed live, no account, no API key.
- Rejected with reasons: Mixcloud (desktop-only), Jamendo (needs an embedded `client_id`, which would break unmodified third-party installs), Bilibili/Niconico (regional, no NewPipe), PeerTube (viable but little music).

### Noted
Android's reach is bounded by NewPipeExtractor, which ships exactly four services — `bandcamp`, `peertube`, `soundcloud`, `youtube`. Any future source must either be one of those or expose a direct URL.

## 2026-09-25 — Lyrics cached, timed lyrics found more often, transport on every tab

### Added — lyrics cache (both apps)
- **What**: lyrics are now held in memory per track. Leaving the Lyrics tab and coming back, or replaying the song, no longer goes back to the network.
- **Why**: nothing was cached. Every visit asked up to four providers again, for words that never change.
- Held for **max(1 hour, three times the song length)** on a hit, so skipping back or repeating is free; a **miss** is cached too, for max(10 minutes, twice the song length) — short enough that lyrics published later are not written off for the session, long enough to stop the tab hammering four providers for a song that has none.
- Bounded at 128 tracks, least-recently-used dropped first.
- **Measured (desktop)**: first look 961 ms, second **0.0 ms**, miss 6,250 ms then **0.01 ms**. A deliberate "Try again" drops the entry and really does go back out (252 ms).

### Fixed — the words could not follow the song
- **What**: an untimed wall of lyrics was sometimes returned while timed ones existed
- **Why**: the LRCLIB provider returned the first hit it could format, timed or not, from the first title variant that answered. It also took the first six search results and *then* filtered by duration, so six wrong-length matches meant no lyrics at all. Untimed hits are now kept aside until every variant has been tried, results are filtered by duration first and timed entries are preferred within a single response.
- **Evidence**: emulating the Android lookup exactly against live LRCLIB over 16 real tracks, 2 changed from untimed to timed ("Redneck", "Down the Drain" — both without an artist). A track restored from the synced library or read off a badly tagged file arrives with no artist, while the same song from a search arrives with one — which is how two devices could disagree about the same song.
- **What**: the KuGou lyrics provider could never answer on Android
- **Why**: its search host serves no HTTPS at all, and Android has refused cleartext by default since Android 9 — the app declared no exception, so three requests per lookup were made and silently refused. A scoped network-security config now permits cleartext for that one host, and the other two KuGou endpoints were switched to HTTPS.
- Untimed lyrics now say so — "No timings for this one, so it cannot follow the song" — with a **Look again** button, instead of sitting there as a static wall with no explanation. "No lyrics found" gained a retry too.

### Added — transport on the Lyrics and Queue tabs
- **What**: a compact player bar at the bottom of both tabs, on desktop and Android: what is playing, where it is, and previous / play-pause / next
- **Why**: the Song tab carried all the controls, so reading the lyrics meant switching back a tab just to pause.

### Tests
6 Android (live provider tests, including the cache and the artist-less lookup) and 12 desktop. Verified on device: the mini bar appears on both tabs, the lyrics view scrolls with the song, and re-entering the tab shows cached lyrics immediately. Totals: Android 27, desktop 26.

## 2026-09-25 — v1.5.0: the background-playback fix actually reaches phones

### Fixed
- **What**: v1.4.0 was the newest release, so a phone installing from GitHub still had the bug where playback dies in the background and only comes back after restarting the app
- **Why**: the fix was committed to `main` but never tagged. Nothing was published, so there was nothing to update to. v1.4.0 predates it.

### Added
- **Background playback** in Settings. It reports whether Android will let Muzika keep playing once you leave the app, and opens the system exemption dialog when it will not.
- **Why**: a media foreground service is enough on stock Android — verified on a Pixel, 11 minutes backgrounded across two track changes. Several OEM builds, Nothing OS among them, run their own killer on top and stop the app regardless. There is no API to opt out; the only supported route is the user granting the exemption, so the app says so plainly instead of playing worse in silence.
- The **version number** now appears in Settings → About. Until now there was no way to tell from inside the app which build was installed.

### Tests
3 Robolectric tests for the exemption reading and the settings intent. Android total 21, all passing. Verified on device: the row shows the warning state on a phone that is not exempt, and tapping it opens `com.android.settings…RequestIgnoreBatteryOptimizations`.

## 2026-09-25 — Recently played and My playlists on the desktop Home page

### Added
- **What**: Home now opens with two sections of your own — **Recently played** (last 12 songs, newest first) and **My playlists** (local playlists, most recently changed first) — above YouTube Music's recommendations
- **Why**: your own listening was reachable only through Library tabs, or a sidebar list that is replaced by the queue as soon as anything plays
- Clicking a recent tile plays **the whole shelf from that track**, not just the one song, so it behaves like a queue rather than a one-shot
- **See all** on Recently played opens the Library's History tab; **+** on My playlists creates one
- Both sections render **before the network answers**. They come from SQLite, so Home is useful the instant it opens instead of showing a full-page spinner; only the recommendations below sit under a spinner.

### Fixed
- **What**: when YouTube Music failed or returned nothing, the whole Home and Explore page was replaced by an error screen
- **Why**: `extra_widgets()` was built and then discarded on the empty path. Explore lost its mood pills the same way. Your own playlists and history do not come from YouTube Music and must not disappear with it — the page now renders what it has and reports the failure inline, with Try again. A page with nothing of its own still falls back to the error screen, and now shows the real reason rather than a generic line.

### Changed
- A track change marks Home stale rather than redrawing it, so the page does not jump back to the top under someone who is reading it; it refreshes the next time Home is opened. A change you made yourself — creating a playlist, toggling a favourite — shows immediately. Neither path re-fetches the recommendations.
- `Shelf` takes an optional header action and an empty-state hint, so an empty section explains itself instead of leaving a gap under a heading.

### Tests
6 new desktop tests (14 total, all passing) covering both sections rendering, surviving a YouTube Music failure, the error path for an empty library, play-from-clicked-track, playlist opening, and stale-marking. They build real widgets, so they skip where GTK is absent — CI byte-compiles and lints instead.

## 2026-09-25 — Background playback: the real cause, and the fix

### Fixed
- **What**: music played for a few minutes in the background, then stopped and never came back
- **Why**: at the end of every track ExoPlayer reached `STATE_ENDED` while the next stream was being resolved. Media3 drops the service out of the foreground the instant that happens, and Android then **refuses** to let a backgrounded app put it back:

  ```
  E/ActivityManager: Background started FGS: Disallowed [uidState: SVC; BFGS denied: true]
  android.app.ForegroundServiceStartNotAllowedException: startForegroundService() not allowed
      at androidx.media3.session.MediaNotificationManager.startForeground(MediaNotificationManager.java:363)
  ```

  From the first track change onwards playback ran with no foreground service protecting it — oom adj 700, the service record gone entirely — until the system reclaimed the process.
- **How**: the next track is now handed to ExoPlayer's own playlist while the current one is still playing, using only a stream that is already cached. The player crosses over internally, going READY → BUFFERING → READY, and never sits in `STATE_ENDED` at all. Repeat-one is handed to ExoPlayer's `REPEAT_MODE_ONE` for the same reason: looping by hand went through `STATE_ENDED` on every pass.
- `ensureService()` no longer asks for a foreground start when the service is already running. It could never have succeeded from the background, and each denied attempt spends the app's allowance.

An earlier fix in this series — starting the service whenever playback begins, rather than only in `MainActivity.onCreate` — was necessary but not sufficient. It is kept; this is the other half.

### Measured — background playback

11 minutes backgrounded, across two track changes:

| | Before | After |
|---|---|---|
| Foreground service after first track change | dropped, then **gone** | **held for the full run** |
| Process oom adj | 700 (reclaimable) | **200 (perceptible)** |
| `startForegroundService` denials | one per track change | **0** |
| Buffering events across 2 track changes | — | **2** |

### Added
- **Offline state in search** — a failed lookup showed an empty screen indistinguishable from "no results". It now says so, and offers Retry.
- **Desktop keyboard shortcuts** — repeat, favourite, add-to-playlist, sync now, volume, and back. `space`, `plus` and `minus` are ignored while a text box has focus, so they cannot steal a keystroke mid-search.
- **Tests** — 8 desktop tests covering the sync merge rules and local-library identifiers, and 9 Android tests covering library grouping and store/sync behaviour. Android total is now 18 including the live API probes.

### Changed
- **Prefetch widened** from one track ahead to two: skipping twice in a row no longer waits on an extractor for the second skip.
- **Library lists memoised** — album, artist and playlist groupings were rebuilt on every recomposition.

### Fixed — measurement
`SpeedProbe` was still looking for `VISITOR_DATA` when the page spells it `visitorData` — the exact bug the app itself had fixed some time ago. The probe had been reporting a false negative ever since.

## 2026-09-25 — SoundCloud on the desktop: 2.9s to 0.3s

### Fixed
- **What**: SoundCloud search on the desktop took 1.5-2.9 s, against 72 ms for the same search on Android
- **Why**: the desktop went through yt-dlp's `scsearch`, which spins up a full extractor to do what is one HTTP request. It now calls the same API SoundCloud's own website calls, reading the public web client id out of the script bundles the homepage loads — the way the site itself obtains it — and caching it for the life of the process. The id is warmed in the background at startup, so even the first search does not pay for it.
- yt-dlp remains the fallback: if SoundCloud changes and the API path fails, the source degrades to slow rather than disappearing.
- Results now carry real durations and 300 px artwork, which `scsearch`'s flat extraction did not provide.

### Measured

| | Before | After |
|---|---|---|
| SoundCloud search | 1,522–2,872 ms | **307 ms** (1,048 ms if the id is cold) |
| SoundCloud + Bandcamp together | 1,522–2,872 ms | **729 ms** |

Playback was re-checked: a SoundCloud result still resolves to a playable stream (809 ms), and that path is cached and prefetched like every other source.

## 2026-09-25 — Desktop speed, song loading, and live updates

### Fixed — speed
- **What**: a desktop search made **three sequential** POSTs (466 + 370 + 1033 ms) and only then searched SoundCloud and Bandcamp, also one after the other
- **Why**: everything was serial inside one worker thread, so the latencies simply added up. The two YouTube Music pages now run together, the other sources run together, and the whole lot runs alongside rather than after. `limit` dropped from 40 to 20, which also stops ytmusicapi fetching continuation pages nobody scrolls to.
- **What**: **every play resolved the stream from scratch** — 1.2-1.5 s on the desktop, 1.1-2.6 s on Android, *including replaying the song you just heard*
- **Why**: nothing was cached. Both apps now keep resolved URLs until shortly before they expire (YouTube states the expiry in the URL itself; anything else gets 30 minutes), and both **prefetch the next track while the current one plays**, so skipping and reaching the end are instant instead of a round trip.

### Added — live updates
The desktop watched nothing: a playlist changed on your phone only appeared after a restart. It now monitors the library file and reloads when another device writes to it, debounced, and ignores its own writes so it cannot loop. Settings re-arms the watcher when the folder or backend changes.

### Measured

| | Before | After |
|---|---|---|
| Desktop YouTube search | 1,892 ms | **~1,250 ms** |
| Desktop stream, replay | 1,471 ms | **0 ms** |
| Android stream, replay | 1,517 ms | **0 ms** |
| External change picked up | restart required | **~4 s, no restart** |

Local database queries were never the problem — playlists, favourites, history and the local index all return in under a millisecond.

### Still slow, and not fixed here
- **SoundCloud and Bandcamp on the desktop: 1.5-2.9 s**, against 72 ms for the same SoundCloud search on Android. The desktop goes through yt-dlp's `scsearch`; Android uses NewPipeExtractor. That gap is now the slowest part of a desktop search.
- **First play of a track not seen before** still costs 1.2-2.6 s in both apps. That is the extractor doing real work, and only the cache and prefetch hide it.

## 2026-09-25 — Search was 25 seconds. It is now under two.

### Fixed
- **What**: every YouTube Music API call re-downloaded the ~480 KB YouTube Music homepage before doing any work
- **Why**: the visitor id was looked up as `VISITOR_DATA`, but the page spells it **`visitorData`**. The regex never matched, so nothing was ever cached, and each call paid ~1.7 s and half a megabyte before it started. A visitor id is optional — every endpoint answers without one, which is why this went unnoticed while everything still *worked*. It is now resolved at most once, in the background at startup, and a failure is remembered rather than retried.
- **What**: a search ran six requests one after another
- **Why**: the latencies simply added up. Songs, SoundCloud, Bandcamp, artists, albums and playlists are independent, and now run concurrently.
- **What**: every thumbnail was requested at 544×544, including for list rows 56 dp tall
- **Why**: roughly four times the pixels that can be displayed — about 1 MB of images per search instead of a tenth of that, plus the decode cost. Rows and cards now ask for 256 px; detail pages still get 544.

### Measured, before and after, against the live API

| | Before | After |
|---|---|---|
| One InnerTube call | 5,746 ms | **380 ms** |
| Artists / albums / playlists | 5.7 / 5.8 / 6.7 s | 0.34 / 0.47 / 0.38 s |
| Full search, as the screen ran it | **25,320 ms** | **1,774 ms** |
| Same work fully parallel | 6,278 ms | **529 ms** |

Bandwidth was never the constraint: SoundCloud (72 ms) and Bandcamp (605 ms) were always fast. This was wasted round trips.

### Added
- `app/src/test/.../SpeedProbe.kt` — times each network path against the real API, so a regression like this shows up as a number instead of a feeling.

### Verified
- All 7 parser tests still pass; artwork, durations and metadata unchanged.

## 2026-09-25 — Progress bar on the home-screen widget

### Added
- **What**: the now-playing widget shows a progress bar for the current track
- **How it keeps moving**: a widget is not a live view — it only redraws when something pushes an update. The player therefore refreshes it on a timer while playing, in ~3 second steps (under half a percent of a typical track) rather than the 500 ms of the internal tick, because each update is an IPC to the launcher.
- **What**: widget artwork is cached and re-applied on every update
- **Why**: an update rebuilds the whole RemoteViews, so without caching the cover would revert to the placeholder on every refresh — and refetching it three times a minute would be wasteful.
- **What**: `onTaskRemoved` now defers to Media3 when playback is still running, instead of swallowing the call and leaving its foreground bookkeeping inconsistent.

### Verified on the device
- Widget rendered on the home screen with cover art, title, artist, controls and the bar
- The bar **moves**: measured 352 → 552 accent-coloured pixels across 25 seconds of playback

### Open, and not fixed by this change
Playback still stops when the app is backgrounded on the Pixel. Two different proximate causes appeared in the logs, so this needs more work rather than a guess:
- `ActivityManager: Stopping service due to app idle … PlaybackService` — the service was reaped, which should not happen to a foreground service
- a later run instead **paused** at a fixed position while the buffer kept filling, coincident with `getNewOutputDevices … AUDIO_DEVICE_OUT_SPEAKER`, i.e. an audio route change tripping the becoming-noisy handling

## 2026-09-25 — Music stopped mid-song and never recovered

### Fixed
- **What**: playback died partway through a track and stayed dead, while the button still showed a pause icon as though nothing was wrong
- **Why**: **the player never took a wake lock.** `WAKE_LOCK` was declared in the manifest from the start and nothing ever used it. Without `setWakeMode(C.WAKE_MODE_NETWORK)` the CPU and the Wi-Fi radio doze while the screen is off, the stream stalls, and ExoPlayer sits there believing it is still playing — so the position freezes and the icon keeps lying.
- **What**: no audio focus handling — a call or another app could talk over Muzika, and it would not duck, pause or resume
- **What**: unplugging headphones kept playing out loud
- **What**: a dropped connection skipped the song entirely
- **Why**: `onPlayerError` went straight to the next track. A transient network blip should not cost you what you were listening to; it now retries the same track once with a freshly resolved URL and only moves on if that fails too.

### Added
- **What**: a stall watchdog. A stream can die without ExoPlayer ever reporting an error — the socket goes half-open, the buffer drains, and the player reports "playing" forever. The position is now watched directly, and a track that has not advanced for 12 seconds while it should be playing is re-resolved and resumed **from where it stopped**.
- **What**: buffering is surfaced in the UI, so a stalled stream can no longer look like healthy playback.

### Verified on the device
- `dumpsys power` shows `PARTIAL_WAKE_LOCK 'ExoPlayer:WakeLockManager'` held by Muzika's uid during playback, and `dumpsys wifi` shows a Wi-Fi lock held. Neither existed before — the same greps returned nothing.

## 2026-09-25 — Save the song that is playing, without hunting for it

### Added
- **What**: **Add to playlist** for the currently playing track, on both apps
- **Why**: both had a favourites toggle for what was playing, but adding it to a playlist meant leaving the player, finding the same song again in a list, and opening its row menu. The action belongs where the song is.
- **Desktop**: a new button in the player bar beside the favourites star, opening the existing playlist chooser. It greys out when nothing is playing, like the star does.
- **Android**: an *Add to playlist* button next to *Add to favourites* on the now-playing screen, opening the existing playlist picker.

### Verified
- Android: the picker opens **over** the now-playing sheet without the nested-sheet glitch that was the risk here, and dismisses cleanly
- Adding a track already in the playlist is a no-op, as intended
- Adding a new one — *The Unforgiven* — took MyTop from 4 songs to 5
- It reached the desktop: Syncthing carried the file and the import reported `playlists_updated: 1`, with MyTop showing the same 5 songs
- Desktop reinstalled and runs with the new button, MPRIS responding, no errors

## 2026-09-25 — Published to GitHub, v1.0.0 released

### Added
- **What**: the repo is live at `github.com/tpsvca/muzika`, **private** for now
- **What**: `v1.0.0` tagged, and CI published a **signed** `muzika-1.0.0.apk` (15 MB) to the release

### Fixed (the CI took two attempts)
- **What**: the release workflow failed instantly on every push, and the run was attributed to the workflow file rather than a job
- **Why**: a 0-second failure is workflow *validation*, not a build error. Two contexts were used where Actions does not allow them — `secrets` in a step-level `if`, then `runner` in job-level `env`. `secrets` is resolved once into a job-level env string, and `MUZIKA_KEYSTORE` is now exported from the restore step through `GITHUB_ENV`, so it is set only when a keystore was really restored and a fork with no secrets still falls through to the unsigned build.
- **What**: the release job now runs `apksigner verify` on its own output and warns if the APK came out unsigned, rather than leaving it to be discovered after publishing.

### Verified
- All three workflows registered `active`; `release.yml` correctly does **not** fire on a branch push
- The `v1.0.0` run is green at every step
- The published asset downloads and verifies: `CN=Muzika`, SHA-256 `a2ca9077…`, identical to the local keystore
- `aapt2` on the downloaded APK: `lt.a777.muzika`, versionName `1.0.0`, minSdk 26

### Still to do
- The repo is private; flipping it to public is a one-click change in Settings
- The Pixel still runs the **debug-signed** build. Installing the release APK over it needs an uninstall first, because Android refuses an APK signed by a different key — the library would come back from the sync folder, but it is a deliberate step, not an automatic one.

## 2026-09-25 — Packaged both apps for GitHub

### Changed
- **What**: restructured into a monorepo — `desktop/` (Python/GTK4) and `android/` (Kotlin/Compose), with `docs/`, `CHANGELOG.md` and one `README.md` shared
- **Why**: the two apps share the sync format, so one issue tracker and one release page keeps them honest with each other.
- **What**: the desktop app is relocatable — `style.css` moved inside the package, `bin/muzika` derives its own path, and the `.desktop` entry uses `Exec=muzika` instead of a hard-coded home directory
- **Why**: it could only ever have run from `/home/<user>/projects/muzika`.

### Added
- **What**: `LICENSE` — GPL-3.0-or-later
- **Why**: the Android app links NewPipeExtractor, which is GPL-3.0. Not a preference, a requirement.
- **What**: `desktop/pyproject.toml` and `desktop/install.sh` — `pip install` gives a `muzika` command; the script adds the menu entry and icon under `~/.local`
- **What**: release signing driven entirely by environment variables, with an unsigned fallback so a fork builds out of the box
- **What**: three GitHub Actions workflows — debug APK on every push, byte-compile + lint for the desktop, and a signed release APK attached to the Release on any `v*` tag
- **What**: `README.md` covering both apps, install instructions per distribution, screenshots, a plain statement of what the project is not affiliated with, and the licence reasoning
- **What**: `.gitignore` excluding build output, `local.properties` and any keystore

### Verified
- `pip install` of `desktop/` into a clean venv produces a working `muzika` command; the packaged CSS resolves from site-packages and the app launches
- Android **debug** build succeeds from the new layout
- Android **release** build with no secrets set produces `app-release-unsigned.apk` — the fork path works
- Android **release** build with the signing variables set produces `app-release.apk`, and `apksigner verify` reports *Verifies*, v2 scheme, `CN=Muzika`, SHA-256 matching the keystore
- 107 files staged; audited for tokens, keys and personal paths — nothing sensitive tracked
- The installed launcher was repaired after the move and the app runs from the installed package

### Note
The signing keystore lives in `~/.muzika-signing/` (mode 600, outside the repo) with the GitHub secret values beside it. Losing it means never being able to ship an upgrade to anyone who installed a release, because Android refuses an APK signed by a different key.

## 2026-09-25 — Media controls in the shade, home-screen widget, launcher shortcuts

### Fixed
- **What**: nothing appeared in the notification shade or lock screen while Muzika was playing
- **Why**: `PlaybackService` built a `MediaSession` but never registered it. Media3 only adds a session automatically when a **`MediaController` connects**, and nothing here uses one — the UI drives the ExoPlayer directly — so the service never went foreground and the system had no media controls to show. The platform said as much in its log: `setFgsIfNoSessionIsLinkedToNotification`. One `addSession(it)` fixes it.
- **What**: the media chip had no **next** button
- **Why**: the queue lives in `MuzikaPlayer`, not in ExoPlayer — each track is resolved to a stream URL only as it starts, so ExoPlayer holds exactly one item and reports "no next track". A `QueuePlayer` (`ForwardingPlayer`) now advertises the skip commands and routes them back to the real queue.
- **What**: a launcher shortcut opened the app but did nothing, unless it was the one shortcut that needed no async work
- **Why**: the request was held in Compose state that the effect was *keyed on*, and consuming it cleared that key — cancelling the coroutine at its first suspension point. The effect now keys on a counter that only ever increments.
- **What**: a shortcut fired at an already-running app landed on the wrong screen
- **Why**: with the default launch mode a second `MainActivity` was created, and the old one — still composed in the back stack — consumed the request first. `launchMode="singleTask"`, which is what a music app wants anyway.

### Added
- **What**: a **now-playing home-screen widget** (4×1, resizable) with artwork, title, artist and previous / play-pause / next; tapping it opens the app
- **Why**: requested. It updates from the player itself, and artwork is fetched asynchronously and pushed as a second update, because a widget update must return promptly.
- **What**: **launcher shortcuts** — long-press the icon for *Liked songs*, *Shuffle everything* and *Search*
- **What**: richer media metadata (album, album artist, display title) so the system chip has something to show

### Verified on the Pixel
- Service reaches `isForeground=true` with `types=0x00000002` (mediaPlayback) and posts a `MediaStyle` notification, `category=transport`
- The shade shows the full media chip: artwork, "This phone", title, artist, seek bar, previous, pause and **next** — `actions=3`, and the platform session bitmask gained `SKIP_TO_NEXT`
- The widget is registered, appears in the picker under "Muzika · 4 × 1", and its RemoteViews inflate and render (note icon, title, subtitle, three controls)
- All three shortcuts registered; **Search** and **Liked songs** open their screens and **Shuffle everything** starts playback (`state:started`), cold *and* warm
- Zero Muzika crashes throughout

### Note
Placing the widget is a manual drag and this phone's first home page is full, so it is not placed yet — long-press the home screen → Widgets → Muzika. A crash seen while trying to automate that drag was Omega Launcher's own (`com.saggitt.omega`, `width & height must be > 0`), not Muzika's; the widget root was changed from `match_parent` to `wrap_content` height regardless, since a zero-height measure is exactly what provokes it.

## 2026-09-25 — Desktop Settings, Dropbox backend, and local music on both devices

### Added
- **What**: a real **Settings** dialog on the desktop (`muzika/settings.py`, menu → Settings or `Ctrl+,`), replacing the two ad-hoc sync menu entries
- **Why**: sync had no visible configuration at all, and there was nowhere to put the new options.
- **What**: **Dropbox as a sync backend** (`muzika/dropbox.py`), selectable alongside the synced folder
- **Why**: requested, and it brings the desktop to parity with Android. Dropbox has no anonymous mode, so it takes an access token the user generates in their own app console — no app secret is baked in and no password is ever seen. Settings has a "Test the connection" button that names the account back.
- **What**: **local music libraries** (`muzika/local.py`) — point Muzika at folders of audio files and it indexes them
- **Why**: requested. Tags are read with GStreamer's discoverer, which is already a dependency and handles mp3, flac, m4a, ogg, opus and wav alike; embedded cover art is extracted once per album, art beside the files is picked up, and an untagged file falls back to its path. New `local_tracks` table, a **Music** tab in the Library, and local albums and artists folded into the existing Albums and Artists tabs.
- **What**: **"Copy music into the sync folder"** in Settings, with a live count and size of what is still to copy
- **Why**: requested — this is how a local music stock reaches the phone. Muzika copies into `<sync folder>/Music`; whichever service is running carries the files. The originals are never moved.
- **What**: Android indexes that same folder (`data/LocalMusic.kt`, `MediaMetadataRetriever` for tags), plays local files, shows them under a new **On device** shelf, and merges local albums and artists into its own Albums and Artists tabs. Settings gained a music-folder row and a rescan.

### The part that was almost silently broken
A local track's id has to be **identical on both devices** or every synced playlist entry dangles. The desktop first indexed relative to each music folder (`AC-DC/…`) while the phone indexed relative to the shared root (`musictest/AC-DC/…`) — the same file, two different ids. The canonical path is now `<library folder name>/<path inside it>` on both sides, which is exactly the shared layout, and the tag fallback counts from the *end* of the path so it stays right however deep a library sits.

### Verified end to end
- Desktop indexed 4 files: tags, track numbers and durations read; embedded art extracted; art beside the files picked up; an untagged file correctly resolved to `Untagged Band / Some Album / Nameless Song` from its path alone
- "Copy music into the sync folder" copied 4 files and was a no-op on the second run
- Syncthing carried all 4 to the Pixel; the app indexed them on launch with the same tags, artists, albums and track numbers
- **Track ids compared directly between the two databases: identical**
- Playing a local file on the phone loaded it into the player with no extractor involved
- Deleting the music again propagated, and the phone's rescan dropped the index to 0 while leaving playlists and saved items untouched — the wholesale-replace design picks up deletions
- Settings opens on the desktop with no warnings; the app syncs and runs clean

### Note
The test audio used above (1-second tones) was removed from the sync folder afterwards, so no music folder is configured yet — add yours in Settings → Music.

## 2026-09-25 — Saved albums, artists and playlists now sync

### Fixed
- **What**: albums saved in the desktop player never appeared on Android
- **Why**: the desktop keeps saved catalogue items (albums, artists, playlists) in its own `library` table, but `muzika-library.json` only ever carried playlists and favourites. Those saves had no way to travel. The sync file now has a `library` key and both clients read and write it.
- **What**: Android's Albums tab was derived from track metadata, so it could only ever show albums a saved *song* happened to name — which is why it read "No albums yet" against three saved albums on the desktop.
- **What**: track subtitles showed a stray "&" ("6uff • & • TENI")
- **Why**: YouTube Music emits its separators as their own runs, and only " • " was being filtered out.

### Added
- **What**: a `saved` table on Android (schema v3, migrated in place) mirroring the desktop's `library`
- **What**: a bookmark button on every album, artist and online playlist page, so saving works from the phone too
- **What**: Library's Albums, Artists and Playlists tabs merge saved catalogue items with the ones derived from your own songs; a saved one wins, so tapping it opens the real page
- **What**: `docs/sync-format.md` documents the `library` key and its merge rule

### Merge rule
Saved items are **unioned on `(kind, id)`**, like favourites — unsaving an album on one device does not unsave it on the other. Deletions still do not propagate; silent loss across devices is the worse failure.

### Verified end to end
- Desktop exported 7 saved items (3 albums, 2 artists, 2 playlists); Syncthing carried the 35,259-byte file to the Pixel
- Android's Library then showed **Albums · 3** — Painkiller / Judas Priest, Bix-Ray / Bix, Priesaika / Thundertale, matching the desktop exactly — and **Playlists · 4**, the two local ones plus both saved Katedra playlists
- Opening Painkiller loaded the real album ("Album • 1990 • Judas Priest") with its tracks, bookmark already filled
- **Reverse direction**: saved *IRON ORE* on the phone; the desktop import reported `library_added: 1` and now lists four saved albums
- Albums tab ends at 4 with no crashes

## 2026-09-25 — Android app: crash fix, recommendations, Explore, Library types, settings

### Fixed
- **What**: "Muzika keeps stopping" — `MuzikaPlayer.loadCurrent()` called `startForegroundService()` on *every* track load
- **Why**: on Android 12+ that throws when the app is backgrounded, which is exactly what happens when a track auto-advances with the screen off. The service is now started once, from the foreground, in `MainActivity.onCreate()`.
- **What**: lazy-list keys were bare track/item ids
- **Why**: a YouTube Music playlist may legitimately list the same song twice, and a repeated key crashes a Compose lazy list. Keys now pair the id with the position.
- **What**: opening an artist or album in the library ran a full SQLite scan on the main thread from the click handler; it now filters the list already in memory.

### Added
- **What**: `sources/Innertube.kt` — YouTube Music's own browse/search/next API, called anonymously
- **Why**: it answers without an account (only the *player* endpoint requires one, which is why streams still go through NewPipeExtractor). This is what makes albums, artists, playlists, moods and radios possible at all.
- **What**: `sources/Discover.kt` — home-page suggestions built on this device
- **Why**: requested "newest playlists adapting to my style". Top artists are derived from local play history and favourites, and each seeds a YouTube Music radio. No account, no profile on a server: the taste model is the phone's own history table.
- **What**: **Explore** tab — YouTube Music moods and genres (36 categories), each opening its shelves of playlists
- **What**: **Search** now returns songs, albums, artists and playlists, with filter chips, alongside SoundCloud and Bandcamp results
- **What**: **Library** now has Playlists, Songs, Artists, Albums and Liked, with counts on each chip
- **What**: **Settings** — light/dark/system theme, wallpaper colours, sync backend, sync folder, Dropbox, source toggles, clear history, refresh suggestions
- **What**: **Dropbox sync** (`data/Dropbox.kt`) as an alternative to the synced folder
- **Why**: requested. Dropbox has no anonymous mode, so it takes an access token the user generates in their own app console — no app secret is baked into the APK and no password is ever seen.
- **What**: artist and album pages, a mix page, and a real back stack so Home → mix → artist → album → back works
- **What**: `album` column on tracks (schema v2, migrated in place) and carried in `muzika-library.json`

### Changed
- **What**: grid-and-carousel layout with 544px artwork, 56dp rows, 48dp controls, larger mini-player
- **Why**: requested bigger icons and grid layout; the old UI was uniform 44dp list rows with no visual hierarchy.
- **What**: playlist reordering is now a mode behind one button instead of two buttons on every row, and gained "move down"
- **What**: every empty state now offers the action that fixes it

### Fixed (second pass, found by driving the app on the device)
- **What**: a detail page's button stayed on "Play" while that very page was playing
- **Why**: `MuzikaPlayer.source` was a plain field. The header compares against it to decide Play vs Pause, but a plain field never invalidates a Compose scope, so the header kept a stale answer. It is Compose state now.
- **What**: the library invented an artist called "G"
- **Why**: artist names were split on a bare `&` and `,`, so "G&G Sindikatas" became "G". Splitting now only happens on separators actually surrounded by spaces, which leaves AC/DC, G&G Sindikatas and "Tyler, The Creator" whole.
- **What**: "1 songs"
- **What**: every catalogue card repeated its own type in its subtitle ("Playlist • YouTube Music")

### Verified on the Pixel
- Build succeeds; APK installed (22.1 MB); no crashes across every screen
- **Live parser tests** (`app/src/test/.../InnertubeTest.kt`, 7 tests, all passing against the real API): songs search returns 20 tracks with durations, artists and album names; albums/artists/playlists are typed correctly; *Master of Puppets* returns its 8 tracks with cover and durations; a 100-track playlist loads; the Metallica artist page returns 5 top songs and 7 shelves including "Fans might also like"; a radio returns 50 tracks; 36 moods load and "Chill" alone yields 10 shelves
- **Schema v2 migrated in place** with no data loss: `user_version=2`, album column on all three tables, acdc 112 / MyTop 4 / 7 favourites intact, and `muzika-library.json` rewritten stamped `"device": "Pixel 7 Pro"`
- **Home adapts**: "Tuned to AC/DC and Thundertale", with Metallica radio / Relaxing Blues Music radio / AC/DC radio under "Made for you", AC/DC albums, AC/DC playlists, and Creedence Clearwater Revival / Mötley Crüe / Ozzy Osbourne under "Artists you might like"
- **Playback**: opening Metallica radio and pressing Play produced `AudioPlaybackConfiguration ... state:started usage=USAGE_MEDIA ... sampleRate=48000` from Muzika's uid, with the mini player, the equaliser glyph and the header's Pause state all correct
- **Explore** lists 36 moods; "Chill" opens Coffee shop blends, Unwind + explore and the rest with real artwork
- **Search** for a term returns Artists, Albums (with years) and Playlists side by side
- **Library** shows Playlists · 2, Songs · 124, Artists · 9, Albums
- **Dark theme** applies instantly across every surface including the mini player
- A 50-track radio scrolls to its last track with nothing hidden behind the mini player

### Removed
- **What**: OuterTune (`com.dd3boh.outertune.debug`), InnerTune (`com.zionhuang.music.debug`) and SimpMusic (`com.maxrave.simpmusic`), uninstalled from the Pixel
- **Why**: all three were dead-end fork attempts, superseded by our own app. They were present in the PlayStore (10) and povelniu (11) profiles, not Owner; removed from both, verified clean across users 0/10/11, with `lt.a777.muzika` left in place and relaunching with no crashes.

## 2026-09-25 — Muzika for Android, built from scratch

### Added
- **What**: our own Android app (`~/muzapp` on Debian, `lt.a777.muzika`), not a fork — Kotlin, Compose Material3, Media3 ExoPlayer with a MediaSessionService, plain SQLite, OkHttp, Coil
- **Why**: every existing client failed structurally. NewPipeExtractor resolves YouTube **anonymously** (verified: 5 audio streams, WEBMA_OPUS 160k, HTTP 206), which is what no innertube-based fork could still do.
- **Features**: multi-source search (YouTube, SoundCloud, Bandcamp), queue with shuffle and repeat, playlists with reorder/rename/delete, favourites, history, lyrics from LRCLIB/NetEase/KuGou with synced highlighting, and library sync over the same `muzika-library.json`.

### Verified on the device
- Installs and runs with zero crashes
- **Sync round-trip**: Fedora exported 33330 bytes → Syncthing → the app imported and wrote back 33817 bytes stamped `"device": "Pixel 7 Pro"` → Syncthing carried it back to Fedora. Both ends now hold MyTop (4) and acdc (112) with 7 favourites; folder reports `need=0 errors=0`.
- `muzika.db` created and populated in the app's data directory
- On-device playback was confirmed later the same day — see the entries above.

## 2026-09-25 — SoundCloud and Bandcamp as first-class sources

### Added
- **What**: `muzika/sources.py` — a source layer beyond YouTube Music, starting with **SoundCloud** and **Bandcamp**
- **Why**: requested. Neither needs an account or API key: SoundCloud search goes through yt-dlp's own `scsearch`, Bandcamp through the autocomplete endpoint its website uses, and yt-dlp extracts streams for both.
- **What**: search results now show SoundCloud and Bandcamp sections alongside YouTube's
- **What**: `Api.stream()` takes a whole track instead of a bare video id
- **Why**: non-YouTube tracks carry a page URL that an id cannot express. The player passes the track through.
- **What**: `source` and `url` columns on `favourites` and `playlist_tracks`, added by migration for existing databases, and carried in `muzika-library.json`
- **Why**: without them a Bandcamp track degraded to an unplayable YouTube id as soon as it was saved or synced.

### Verified
- All three sources play through the real player: soundcloud PLAYS, bandcamp PLAYS, youtube PLAYS
- Search renders `['Songs', 'SoundCloud', 'Bandcamp']`
- A Bandcamp track survives save → export → import with its source and URL intact
- The existing library migrated with no loss (acdc 112 songs, MyTop 4)
- A source that fails is skipped; it costs results, never the whole search

## 2026-09-25 — Lyrics: four sources, synced always preferred

### Fixed
- **What**: lyrics showed but did not follow the song
- **Why**: YouTube Music sometimes returns *plain* (untimed) lyrics, and we accepted them and stopped looking. Untimed text can never follow playback. The chain now treats **synced lyrics from any source as better than plain text from a nearer one**, and only falls back to plain when no provider has timings.

---

## Earlier entries were lost

On 2026-09-25 two changelog updates were written with `open(path, "w").write(new + open(path).read())`.
Python opens the file for writing — truncating it — *before* the read runs, so each of those
updates destroyed everything already in the file. The project is not under version control, so
there was no copy to restore from. Everything above was reassembled from drafts and from the
session transcript; the entries below existed and are gone. Their headings are recorded here
because that is all that survived:

- `## 2026-09-24 — Fixed: import crashed on FOREIGN KEY constraint`
- `## 2026-09-24 — Syncthing sync to the Pixel; debug overlay removed`
- `## 2026-09-24 — Android app (OuterTune fork) with Muzika sync`
- `## 2026-09-24 — Library sync through a synced folder`
- `## 2026-09-24 — Library tabs drop to icons instead of truncating`
- and further 2026-09-24 entries covering the desktop player's own construction, whose
  headings were never captured. the system-level changelog in the home directory has a 2026-09-24 entry describing
  that work from the system side and is the nearest surviving account.
