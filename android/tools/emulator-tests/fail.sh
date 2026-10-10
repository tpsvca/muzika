#!/bin/bash
# fail.sh SERIAL API DELAY - widget play from a dead process, network cut DELAY s after the tap
S=$1; API=$2; DL=$3; D=$(dirname $0); A="$HOME/android/sdk/platform-tools/adb -s $S"
$A shell svc wifi enable; $A shell svc data enable; sleep 3
$A shell input keyevent HOME; sleep 1
$D/push.sh $S 2; sleep 1; $A shell am kill lt.a777.muzika; sleep 2
$A logcat -c 2>/dev/null
python3 $D/ui.py $S tap widget_toggle >/dev/null; sleep $DL
$A shell svc wifi disable; $A shell svc data disable; sleep 12
anr=$($A logcat -d | grep -cE 'ANR in lt.a777|Bringing down service while still waiting|FATAL EXCEPTION')
err=$($A logcat -d | grep -cE 'Playback error|Source error')
echo "[api$API] cut after ${DL}s: playback errors=$err anr/crash=$anr proc=$($A shell pidof lt.a777.muzika || echo dead)"
$A shell svc wifi enable; $A shell svc data enable
