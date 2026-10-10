# Emulator tests: handoff, widget and notification

Shell scripts that run the cross-device handoff on Android emulators, from
Android 8.0 (API 26, the app's minSdk) to Android 16 (API 36). They play the
desktop's part by writing `muzika-nowplaying.json` the way Syncthing does: a
temporary file renamed into place.

## Setup (once)

User-local SDK in `~/android` (JDK 17 in `~/android/jdk17`), then:

```
sdkmanager "platform-tools" "emulator" "system-images;android-36;google_apis;x86_64"   # one per API
avdmanager create avd -n api36 -k "system-images;android-36;google_apis;x86_64" -d pixel_6
```

AVDs are named `apiNN`. Build the APK first: `./gradlew :app:assembleDebug`.

## Run

```
./start.sh 36 5560                 # boot api36 headless on port 5560
./run.sh emulator-5560 36          # full scenario, prints PASS/FAIL lines
./fgs.sh emulator-5560 36          # widget play with the process killed
./fail.sh emulator-5560 36 3       # same, network cut 3 s after the tap (needs `adb root` on API 26)
```

`run.sh` installs the APK, places the widget through the launcher's own UI,
and checks:

| Test | Situation | Expected |
|------|-----------|----------|
| T1 | desktop wrote, then the app is opened | widget and notification show the desktop's song |
| T2 | app in the background, process alive | both follow within ~4 s |
| T3 | process killed | the widget follows (HandoffJob wakes the app) |
| T4 | left alone 90 s (cached, frozen on 14+) | the widget follows |
| T5 | play pressed on the widget | the desktop's song plays from the desktop's spot |
| T6 | paused on the phone | the phone writes its own pause for the desktop |

## Gotchas

- Use `-gpu host` (what `start.sh` does). On Fedora 44 with emulator 37.2,
  SwiftShader (`swiftshader_indirect`, `guest`) segfaults while SurfaceFlinger starts.
- A freshly installed app that was never opened sits in standby bucket 50 and
  its jobs never run. `run.sh` opens it once, as a real user would.
- The scripts read the screen with `uiautomator dump`, which returns nothing
  while a widget's resize frame is showing; they press Back and Home first.
- YouTube sometimes answers 403 after many plays from one IP. A T5 failure
  with `Response code: 403` in logcat is that, not the app.
