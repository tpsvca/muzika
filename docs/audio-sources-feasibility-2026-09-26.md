# Which services Muzika can add as sources — 2026-09-26

A feasibility study of TikTok, the paid streaming services, and the open
alternatives. Everything below was measured, not assumed; the commands are
given so it can be re-run when the answers go stale.

**Versions tested:** yt-dlp `2026.08.19`, NewPipeExtractor `v0.26.5`,
Muzika `v1.6.0`.

## Verdict

| Service | Search | Stream | Android | Add it? |
|---|---|---|---|---|
| **Audius** | public API, no key | direct MP3 | yes, plain HTTP | **yes** |
| **Internet Archive** | public API, no key | direct FLAC/MP3 | yes, plain HTTP | **yes** |
| **Radio Browser** | public API, no key | direct ICY | yes, plain HTTP | yes, as its own tab |
| Mixcloud | public API, no key | yt-dlp only | no | low priority |
| Jamendo | needs a `client_id` | — | — | no |
| TikTok | none exists | yt-dlp only | no | not as a source |
| Amazon Music | none exists | none exists | no | **impossible** |
| Spotify / Apple Music / Deezer / Tidal / Qobuz / Pandora / Napster | none exists | none exists | no | **impossible** |

## What a new source has to provide

Muzika's source model is two functions: `search(query) -> items` and
`resolve(item) -> stream URL`. Both apps need both, and they get them
differently:

- **Desktop** resolves through yt-dlp, so it can reach anything yt-dlp
  supports.
- **Android** has no yt-dlp. It resolves through NewPipeExtractor, which
  ships exactly four services:

      $ unzip -l NewPipeExtractor-v0.26.5.jar | grep -oE 'services/[a-z]+/' | sort -u
      bandcamp  peertube  soundcloud  youtube

So a service that yt-dlp supports but NewPipe does not is **desktop-only** —
unless its own API hands over a plain URL, in which case Android can fetch it
with no extractor at all. That exception is what makes the three
recommendations below workable on both platforms.

## The paid services: a hard wall, not a gap

    $ python3 -c "import yt_dlp.extractor as e; ..."
    amazon   -> AmazonMiniTV, AmazonReviews, AmazonStore   (not Amazon Music)
    apple    -> apple:podcasts, apple:music:connect        (a retired social feature)
    spotify  NONE   deezer  NONE   tidal  NONE
    qobuz    NONE   pandora NONE   napster NONE

None of these has an extractor anywhere, and that is not an oversight waiting
to be fixed. They all serve Widevine-DRM'd audio to a paid account. yt-dlp
declines DRM as a matter of policy and NewPipe has no CDM either, so there is
nothing to build against. **Amazon Music cannot work the way YouTube Music
does.** Revisit only if a service publishes an unencrypted public API, which
none of them has reason to do.

## TikTok: extraction works, everything else does not

yt-dlp has eight TikTok extractors (`TikTok`, `tiktok:sound`, `tiktok:user`,
`vm.tiktok`, …) and they work — a live pull returned ten audio-bearing
formats, best AAC, with `track` and `artist` fields populated.

Two things still rule it out as a *source*:

1. **No search exists.** yt-dlp's complete set of search-capable extractors is
   `bilisearch, gvsearch, nicosearch, nicosearchdate, prxseries, prxstories,
   rkfnsearch, scsearch, ytsearch, yvsearch`. TikTok's own web search needs
   signed parameters (`X-Bogus`/`msToken` plus a device fingerprint) that
   rotate constantly, which is why nobody implements it. Without search there
   is nothing to put on the Search screen.
2. **Android cannot play it.** No TikTok extractor in NewPipeExtractor, no
   yt-dlp on the phone.

And the content is wrong for a music library anyway. Durations from a live
sample: **42, 52, 58, 68, 120 seconds** — clips of songs, frequently sped up
or pitch-shifted with speech over them, and `track` often reads
`"original sound"` rather than a song title.

**If TikTok is wanted anyway**, the shape that works is a desktop *Import from
a link*: paste a URL, yt-dlp extracts the audio, it is tagged into the local
library, and Syncthing carries the file to the phone where it plays as a local
track. That sidesteps both blockers — no search because you paste, no Android
extractor because by then it is a file — and it would cover all ~1,800 sites
yt-dlp knows, not just TikTok. Not built.

## The three worth adding

All three were probed live on 2026-09-26; each returned a playable stream with
no account and no API key.

### Audius — the closest thing to a fourth source

    GET https://api.audius.co                     -> discovery node
    GET {node}/v1/tracks/search?query=…&app_name=Muzika
    GET {node}/v1/tracks/{id}/stream?app_name=Muzika
      -> HTTP 200, Content-Type: audio/mpeg

Open by design, no account, and the stream is a plain MP3 — so both apps can
play it with no extractor.

Caveats worth designing around:

- Only **5 of 10** search results were streamable. Filter on
  `is_streamable` or the queue will stall on dead entries.
- The catalogue is independent, electronic and lofi-heavy. A search for a
  major-label artist returns fan remixes, not the official release.
- Durations vary wildly (one result was a 3,620-second mix), so the duration
  filters used for lyrics matching are not safe here.

### Internet Archive — large, legal, direct files

    GET https://archive.org/advancedsearch.php?q=collection:(etree)+AND+mediatype:(audio)&output=json
    GET https://archive.org/metadata/{identifier}          -> file list
    GET https://archive.org/download/{identifier}/{file}
      -> HTTP 200, Content-Type: audio/flac

The Live Music Archive (`etree`) is concert recordings from artists who permit
taping, and there are public-domain and 78rpm collections besides. Serves FLAC
directly. A natural fit for an app already built around your own files. One
identifier returned 48 files of which 20 were audio, so the file list needs
filtering by extension.

### Radio Browser — cheap, and a different thing

    GET https://de1.api.radio-browser.info/json/stations/search?name=…&hidebroken=true
      -> station.url_resolved -> HTTP 200, Content-Type: audio/aacp

Roughly 50k stations for very little work. But it is **live radio**: no
seeking, no duration, no queue semantics. It belongs as its own tab, not mixed
into search results with tracks.

## Rejected, with reasons

- **Mixcloud** — keyless search API works and yt-dlp resolves it (2 audio
  formats), but there is no NewPipe support and no direct URL, so it is
  desktop-only. DJ sets and long mixes; poor fit for a track-based library.
- **Jamendo** — `api.jamendo.com` returns 0 results without a `client_id`.
  Baking a key into the app breaks unmodified third-party installs, which is
  the same reason no other key is embedded. Skip.
- **Bilibili / Niconico** — have search in yt-dlp, but regional catalogues and
  no NewPipe support.
- **PeerTube** — NewPipe *does* support it, so it is technically viable on
  both, but it is federated video with no central search and little music.

## Re-check when

- NewPipeExtractor adds a service (would unblock Android for that service).
- yt-dlp adds a `SEARCH_KEY` for anything above — re-run the search-key probe.
- Audius changes its discovery-node handshake; the node list is fetched at
  runtime and must not be hard-coded.
