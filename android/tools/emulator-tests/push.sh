#!/bin/bash
# push.sh SERIAL CURSOR [playing] - write the desktop's handoff file the way Syncthing does (temp + rename)
A="$HOME/android/sdk/platform-tools/adb -s $1"; TMP=$(mktemp)
python3 $(dirname $0)/handoff.py $2 $3 > $TMP
$A push -q $TMP /data/local/tmp/h.json
$A shell 'cat /data/local/tmp/h.json > /sdcard/Muzika/.syncthing.muzika-nowplaying.json.tmp && mv /sdcard/Muzika/.syncthing.muzika-nowplaying.json.tmp /sdcard/Muzika/muzika-nowplaying.json'
rm -f $TMP
