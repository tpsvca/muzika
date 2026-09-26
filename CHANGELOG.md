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
