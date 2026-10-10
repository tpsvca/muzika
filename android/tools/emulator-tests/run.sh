#!/bin/bash
# run.sh SERIAL API - full handoff scenario on one emulator
S=$1; API=$2; D=$(dirname $0); A="$HOME/android/sdk/platform-tools/adb -s $S"
APK=$HOME/projects/gnome-android/muzika/android/app/build/outputs/apk/debug/app-debug.apk
ok(){ echo "[api$API] PASS $*"; }; bad(){ echo "[api$API] FAIL $*"; }
widget(){ python3 $D/ui.py $S texts 'widget_title' | cut -d"'" -f2; }
session(){ $A shell dumpsys media_session | grep -A14 'package=lt.a777.muzika' | grep -oE 'description=[^,]*' | head -1 | cut -d= -f2; }
home(){ $A shell input keyevent HOME; sleep 1; $A shell input keyevent HOME; sleep 1; }
expect(){ # label expected-title check-session(0/1)
  local w=$(widget) s=$(session)
  [ "$w" = "$2" ] && ok "$1 widget=$w" || bad "$1 widget='$w' want '$2'"
  if [ "$3" = 1 ]; then [ "$s" = "$2" ] && ok "$1 notification=$s" || bad "$1 notification='$s' want '$2'"; fi
}
$A install -r -d -g $APK >/dev/null || { bad install; exit 1; }
[ $API -ge 30 ] && $A shell appops set lt.a777.muzika MANAGE_EXTERNAL_STORAGE allow
$A shell mkdir -p /sdcard/Muzika; $A shell rm -f /sdcard/Muzika/muzika-nowplaying.json
$A shell settings put system screen_off_timeout 1800000; $A shell svc power stayon true
$A shell monkey -p lt.a777.muzika -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4
$A logcat -d | grep -E 'FATAL|AndroidRuntime: ' | grep -A3 FATAL | head -5
home
$D/place_widget.sh $S || { bad "widget placement"; exit 1; }
home
# T1: app opened after the desktop wrote
$D/push.sh $S 0; sleep 1
$A shell monkey -p lt.a777.muzika -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4; home
expect "T1 open app" "Never Gonna Give You Up" 1
# T2: in the background, process alive
$D/push.sh $S 1; sleep 8
expect "T2 background" "Bohemian Rhapsody" 1
# T3: process killed
$A shell am kill lt.a777.muzika; sleep 1
$D/push.sh $S 2; sleep 12
expect "T3 killed" "Gangnam Style" 0
# T4: left alone until cached/frozen (90 s)
sleep 90
fz=$($A shell dumpsys activity processes lt.a777.muzika | grep -oE 'isFrozen=true' | head -1)
echo "[api$API] info process: $($A shell pidof lt.a777.muzika || echo dead) ${fz:-not-frozen}, bucket $($A shell am get-standby-bucket lt.a777.muzika)"
$D/push.sh $S 3; sleep 12
expect "T4 idle 90s" "Despacito" 0
# T5: widget play takes the desktop's track and spot
$A logcat -c
python3 $D/ui.py $S tap 'widget_toggle' >/dev/null; sleep 10
st=$($A shell dumpsys media_session | grep -A14 'package=lt.a777.muzika' | grep -oE 'state=([A-Z]+\()?[0-9]\)?, position=[0-9]+' | head -1)
echo "$st" | grep -qE 'PLAYING|state=3,' && ok "T5 widget play: $st $(session)" || bad "T5 widget play: '$st'"
$A shell input keyevent 127; sleep 3
pl=$($A shell cat /sdcard/Muzika/muzika-nowplaying.json | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["device_id"]!="desktop-test", d["playing"], d["track_id"])' 2>&1)
[ "$pl" = "True False kJQP7kiw5Fk" ] && ok "T6 phone wrote its pause for the desktop" || bad "T6 handoff written: $pl"
$A logcat -d | grep -E 'FATAL EXCEPTION' -A6 | head -8
