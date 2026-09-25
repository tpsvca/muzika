# Muzika — Android

Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer, plain SQLite.
No Room, no Hilt, no account.

See the [main README](../README.md) for what it does and how to install a
release APK.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # live tests against YouTube Music
```

JDK 17 and an Android SDK are required. Point at the SDK with a
`local.properties` containing `sdk.dir=/path/to/Android/Sdk`, or set
`ANDROID_HOME`. `local.properties` is deliberately not committed.

## Layout

| Path | What lives there |
|---|---|
| `sources/Innertube.kt` | YouTube Music's own API: search, albums, artists, moods, radios |
| `sources/Sources.kt` | every source behind one interface, plus stream resolution |
| `sources/Discover.kt` | the home page's suggestions, built from local history |
| `player/MuzikaPlayer.kt` | the queue and playback |
| `player/PlaybackService.kt` | media session, notification, lock screen |
| `data/Store.kt` | SQLite |
| `data/Sync.kt`, `data/Dropbox.kt` | the library file and its backends |
| `data/LocalMusic.kt` | indexing audio files in the sync folder |
| `ui/` | the screens |
| `widget/` | the home-screen widget |

## Release builds

Signing comes from the environment, never from a file in the repo:
`MUZIKA_KEYSTORE`, `MUZIKA_KEYSTORE_PASSWORD`, `MUZIKA_KEY_ALIAS`,
`MUZIKA_KEY_PASSWORD`. With none of them set, `assembleRelease` produces an
unsigned APK rather than failing, so a fork builds out of the box.
