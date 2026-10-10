#!/bin/bash
# fgs.sh SERIAL API - widget play with the process killed: must play, in the foreground, no ANR
S=$1; API=$2; D=$(dirname $0); A="$HOME/android/sdk/platform-tools/adb -s $S"
$A install -r -g $HOME/projects/gnome-android/muzika/android/app/build/outputs/apk/debug/app-debug.apk >/dev/null
$A shell input keyevent HOME; sleep 1; $A shell input keyevent HOME; sleep 1
$D/push.sh $S 1; sleep 2
$A shell am kill lt.a777.muzika; sleep 1; $A shell am force-stop com.android.systemui >/dev/null 2>&1
$A logcat -c 2>/dev/null; sleep 6
python3 $D/ui.py $S tap widget_toggle >/dev/null; sleep 12
st=$($A shell dumpsys media_session | grep -A16 'package=lt.a777.muzika' | grep -oE 'state=([A-Z]+\()?[0-9]\)?, position=[0-9]+' | head -1)
fg=$($A shell dumpsys activity services lt.a777.muzika | grep -oE 'isForeground=true' | head -1)
anr=$($A logcat -d | grep -cE 'ANR in lt.a777|Bringing down service while still waiting|FATAL EXCEPTION')
echo "[api$API] widget play from dead process: ${st:-none} ${fg:-NOT-foreground} anr/crash=$anr"
$A shell input keyevent 127; sleep 2
