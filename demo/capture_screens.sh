#!/usr/bin/env bash
# Captures the "real screen" shots (R1 home, R2 sheet, R3 loaded, R4/R5 badge, R6 report, R7 settings, R1b held) used by
# submission/mockups/compose_real.py and video/assemble_v2.py inserts, from a running emulator.
#   ./capture_screens.sh <apk> <out_dir>      (out_dir: submission/mockups/real)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
APK=${1:?apk}; OUT=${2:?out dir}; mkdir -p "$OUT"
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}
export ADB="$SDK/platform-tools/adb"
B="$HERE/demo_broadcast.sh"
PKG=ai.vipseat.cargoconcierge
shot() { sleep "${2:-1.5}"; $ADB exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }

$ADB install -r -g "$APK" | tail -1
$ADB shell am force-stop $PKG || true
$ADB shell am start -W -n $PKG/.MainActivity >/dev/null
sleep 7
"$B" gear P; "$B" speed 0; "$B" occupied false   # emulator default = occupied → clearing it starts the 30 s suppression
"$B" occupied false REAR_LEFT; "$B" occupied false REAR_CENTER; "$B" occupied true REAR_RIGHT; "$B" spot FRONT_PASSENGER
"$B" recent "도자기:FRAGILE,강아지:PET,꽃다발:FLOWERS"
shot R1_home 2.5
echo "waiting out the 30 s passenger-exit suppression"; sleep 31
"$B" door PASSENGER open; sleep 1.5; "$B" door PASSENGER closed
shot R2_sheet 2
"$B" occupied false REAR_RIGHT          # ad insert: sheet with no occupied seat (driver alone in the scene)
shot R2b_sheet_empty 1.2
"$B" occupied true REAR_RIGHT
"$B" voice "케이크 실었어"
shot R3_loaded 6
# drive: badge (OK → WARN), then arrival report — so every real screen carries the current art
"$B" gear D; "$B" speed 30
shot R4_badge_ok 2.5
"$B" steer 45
shot R5_badge_warn 1.2
"$B" steer 0; "$B" speed 0; "$B" gear P; "$B" door DRIVER open
shot R6_report 3
"$B" ack; sleep 1.5
"$B" settings on
shot R7_settings 2
"$B" settings off
"$B" door DRIVER closed
# bonus: an occupied seat picked as the VIP spot ("들고 타기") on the home map
"$B" spot REAR_RIGHT
shot R1b_home_held 2
"$B" spot FRONT_PASSENGER
echo done
