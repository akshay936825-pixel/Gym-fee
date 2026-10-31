#!/usr/bin/env bash
# Measures cold and warm start of an installed build on a connected phone (USB debugging on).
# Usage: scripts/measure_startup.sh com.gym.hisab [runs]
#   Run it once on the OLD build and once on the NEW build (same phone, same data), then compare medians.
# Cold  = process killed first (am force-stop).  Warm = app in background, process alive (HOME, then relaunch).
# TotalTime is time until the first Activity frame; "fully drawn" is when Home had its data (reportFullyDrawn).
set -euo pipefail
PKG="${1:?package id, e.g. com.gym.hisab or com.gym.hisab.debug}"
RUNS="${2:-10}"
ACT="$PKG/com.gym.hisab.MainActivity"

median() { sort -n | awk '{a[NR]=$1} END{ if(NR==0){print "n/a"} else if(NR%2){print a[(NR+1)/2]} else {print (a[NR/2]+a[NR/2+1])/2} }'; }

cold=(); warm=(); full=()
for i in $(seq 1 "$RUNS"); do
  adb shell am force-stop "$PKG"; sleep 1
  adb logcat -c
  out=$(adb shell am start -W -n "$ACT")
  t=$(echo "$out" | awk -F': ' '/TotalTime/ {gsub("\r","",$2); print $2}')
  cold+=("$t")
  sleep 2
  f=$(adb logcat -d -s ActivityTaskManager:I | grep -o "Fully drawn $PKG.*" | grep -o '+[0-9a-z]*' | head -1 || true)
  [ -n "$f" ] && full+=("$f")
  adb shell input keyevent KEYCODE_HOME; sleep 1
  out=$(adb shell am start -W -n "$ACT")
  w=$(echo "$out" | awk -F': ' '/TotalTime/ {gsub("\r","",$2); print $2}')
  warm+=("$w")
done
echo "cold TotalTime ms (each run): ${cold[*]}"
echo "cold median ms: $(printf '%s\n' "${cold[@]}" | median)"
echo "warm TotalTime ms (each run): ${warm[*]}"
echo "warm median ms: $(printf '%s\n' "${warm[@]}" | median)"
echo "fully drawn (logcat, each run): ${full[*]:-not captured}"
