#!/bin/bash
# place_widget.sh SERIAL - put the Muzika widget on the home screen via the launcher UI
S=$1; D=$(dirname $0); A="$HOME/android/sdk/platform-tools/adb -s $S"; U="python3 $D/ui.py $S"
$A shell dumpsys appwidget | grep -q 'lt.a777.muzika/lt.a777.muzika.widget.NowPlayingWidget}}' && \
  $A shell dumpsys appwidget | grep -A2 '^  \[' | grep -q . ; 
$A shell input keyevent BACK; sleep 1; $A shell input keyevent HOME; sleep 2
python3 $D/ui.py $S texts 'widget_title' | grep -q . && { echo "widget already placed"; exit 0; }
$A shell input keyevent HOME; sleep 2; $A shell input keyevent BACK; sleep 1; $A shell input keyevent HOME; sleep 2
$A shell input swipe 540 1300 540 1300 1500; sleep 1
$U tap '^Widgets$' >/dev/null || exit 1; sleep 2
if $U tap 'search_bar_edit_text|^Search( widgets)?$' >/dev/null; then $A shell input text Muzika; sleep 2; fi
for i in 1 2 3 4 5 6 7 8 9 10; do $U texts '^Muzika$' | grep -q . && break; $A shell input swipe 540 1800 540 700 400; sleep 1; done
# newer launchers: expand the app, tap the preview, press Add
$U tap '^Muzika$' >/dev/null; sleep 2
if $U texts 'widget_preview|Muzika widget' | grep -q .; then
  $U tap 'Muzika widget|widget_title' >/dev/null; sleep 1
  $U tap '^Add$' >/dev/null && { sleep 2; $A shell input keyevent HOME; sleep 2; python3 $D/ui.py $S texts 'widget_title' | grep -q . && exit 0; }
fi
# older launchers: long-press the preview and drag it onto the workspace
xy=$($U texts 'widget_title|Nothing playing|widget_preview' | head -1 | grep -oE '\[[0-9]+,[0-9]+\]' | head -1 | tr -d '[]' | tr , ' ')
[ -z "$xy" ] && xy=$($U texts '^Muzika$' | tail -1 | grep -oE '\[[0-9]+,[0-9]+\]' | tail -1 | tr -d '[]' | tr , ' ')
set -- $xy; x=$1; y=$(( $2 + 60 ))
$A shell input draganddrop $x $y 540 900 3000 2>/dev/null || $A shell input swipe $x $y 540 900 3000
sleep 3; $U tap '^(Add|Create|OK)$' >/dev/null; sleep 1
python3 $D/ui.py $S texts 'widget_title' | grep -q .
