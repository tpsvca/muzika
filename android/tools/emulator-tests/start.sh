#!/bin/bash
# start.sh API PORT -> boots avd apiAPI headless on PORT, waits for boot
API=$1; PORT=$2; A=~/android/sdk/platform-tools/adb; S=emulator-$PORT
setsid nohup ~/android/sdk/emulator/emulator -avd api$API -port $PORT -no-window -no-audio -no-boot-anim -no-snapshot -gpu host > ~/android/emu-api$API.log 2>&1 < /dev/null &
for i in $(seq 1 60); do
  [ "$($A -s $S shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { echo "api$API booted ($((i*5))s)"; exit 0; }
  sleep 5
done
echo "api$API FAILED to boot"; exit 1
