#!/bin/bash
# Profiles the running Zomdroid game process with simpleperf for N seconds and prints where the
# CPU time went: per library, per thread, and the hottest symbols. Meant for the "driving stutters"
# question: how much of a frame is Bullet, how much the renderer, how much the JVM.
#
# Needs: a debuggable build installed, the game already running (drive when the countdown starts),
# NDK simpleperf. Run from Git Bash on the PC:  tools/profile-driving.sh [seconds] [serial]
set -e
export MSYS_NO_PATHCONV=1
SECS=${1:-90}
SERIAL=${2:-RFGYB08X13B}
NDK=${NDK:-/c/Users/user/AppData/Local/Android/Sdk/ndk/29.0.14206865}
ADB=${ADB:-/c/Users/user/AppData/Local/Android/Sdk/platform-tools/adb.exe}
OUT=${OUT:-/c/Users/user/Desktop/PZ/profiles/$(date +%Y%m%d_%H%M)}
mkdir -p "$OUT"
export ANDROID_SERIAL=$SERIAL

PID=$("$ADB" shell pidof com.zomdroid | tr -d '\r')
[ -n "$PID" ] || { echo "com.zomdroid is not running - start the game first"; exit 1; }
echo "game pid $PID; recording $SECS s into $OUT (drive now)"

# app_profiler pushes simpleperf into the app's sandbox (run-as), attaches to the running process
# and afterwards copies the libraries it saw into ./binary_cache, which the report needs for
# symbols. cpu-clock, not task-clock: it works on every kernel; -g = call stacks.
cd "$OUT"
python "$NDK/simpleperf/app_profiler.py" -p com.zomdroid --disable_adb_root \
    -r "-e cpu-clock -f 1000 -g --duration $SECS" -o perf.data 2>&1 | tail -5

REPORT="$NDK/simpleperf/bin/windows/x86_64/simpleperf.exe"
SYM="--symfs binary_cache"
echo "=== CPU by library"; "$REPORT" report -i perf.data $SYM --sort dso 2>/dev/null | head -25 | tee by_dso.txt
echo "=== CPU by thread"; "$REPORT" report -i perf.data $SYM --sort comm 2>/dev/null | head -20 | tee by_thread.txt
echo "=== hottest symbols in Bullet / renderer / JVM"; "$REPORT" report -i perf.data $SYM --sort dso,symbol \
    --dsos libPZBullet64.so,libzfa.so,libgl4es.so,libng_gl4es.so,libjvm.so,libLighting.dylib,libPZPathFind.dylib,libPZPopMan.dylib \
    2>/dev/null | head -60 | tee hot_symbols.txt
"$REPORT" report -i perf.data $SYM --sort dso,symbol > full_report.txt 2>/dev/null || true
echo "saved: $OUT"
