#!/usr/bin/env bash
# =====================================================================================
#  VIP 조수석 — 에뮬레이터 시연 녹화 (Pleos_Connect_v2.1 또는 표준 AAOS AVD)
# =====================================================================================
#  1) 부팅 완료 대기 → 2) debug APK 설치(-g: 런타임 권한 일괄 허용) → 3) 앱 실행
#  4) 에뮬레이터 내장 녹화 시작(호스트 webm, 최대 180초) → 5) scenario_3min.sh 타임라인 송출
#  6) 녹화 종료, logcat/타임라인 로그 저장.  오디오(TTS)는 녹화에 포함되지 않으므로 로그의
#     "TTS:" 줄과 타임라인 로그를 자막으로 쓴다.
#
#    ./record_emulator.sh <apk> <out_dir> [pre_roll_s=6] [post_roll_s=14]
#  env: ANDROID_SDK_ROOT (기본: /opt/homebrew/share/android-commandlinetools), SERIAL(기본 emulator-5554)
#       SCENARIO=scenario_video.sh (기본 scenario_3min.sh) · EPILOGUE_VOICE="조수석 뒤에 떡볶이 실었어" (리포트 확인 뒤 음성 자리+물건 등록 장면)
#       HOME_HOLD / EPILOGUE_HOLD / SETTINGS_HOLD (초)
# =====================================================================================
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
APK=${1:?apk path}; OUT=${2:?output dir}; PRE=${3:-6}; POST=${4:-14}
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}
export ADB="$SDK/platform-tools/adb"
SERIAL=${SERIAL:-emulator-5554}
ADBS="$ADB -s $SERIAL"
PKG=ai.vipseat.cargoconcierge
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
STAMP=$(date +%Y%m%d_%H%M%S)
WEBM="$OUT/demo_${STAMP}.webm"; TL="$OUT/timeline_${STAMP}.log"; LC="$OUT/logcat_${STAMP}.txt"

say() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

say "waiting for $SERIAL to boot"
$ADBS wait-for-device
until [ "$($ADBS shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
sleep 5
say "device: $($ADBS shell getprop ro.product.model | tr -d '\r') / $($ADBS shell getprop ro.build.display.id | tr -d '\r') / $($ADBS shell wm size | tr -d '\r')"

say "install $APK"
$ADBS install -r -g "$APK" | tail -1
$ADBS shell settings put secure immersive_mode_confirmations confirmed >/dev/null 2>&1 || true

say "launch app"
$ADBS shell am force-stop $PKG || true
$ADBS logcat -c || true
$ADBS shell am start -W -n $PKG/.MainActivity | grep -E "Status|TotalTime" || true
sleep 4
PID=$($ADBS shell pidof $PKG | tr -d '\r' || true)
say "pid=$PID"
( $ADBS logcat -v epoch ${PID:+--pid=$PID} > "$LC" 2>&1 ) & LOGPID=$!

# The Pleos emulator reports the passenger seat (and the rear seats) as occupied by default; the demo bridge clears them.
# Clearing it counts as "passenger just left" (30 s suppression of auto prompts), so settle first.
say "settle: occupied=false, wait ${SETTLE:-34}s so the door prompt is not suppressed"
ADB="$ADB -s $SERIAL" "$HERE/demo_broadcast.sh" occupied false >/dev/null
for seat in REAR_LEFT REAR_CENTER; do ADB="$ADB -s $SERIAL" "$HERE/demo_broadcast.sh" occupied false "$seat" >/dev/null; done  # emulator default: every seat occupied
sleep "${SETTLE:-34}"

say "screenrecord start -> $WEBM"
$ADBS emu screenrecord start --time-limit 180 "$WEBM" | tr -d '\r' | grep -v "^$" || true
T0=$(date +%s.%N)
DEV_NOW=$($ADBS shell date +%s | tr -d '\r'); HOST_NOW=$(date +%s)
echo "T0=$T0" > "$TL"; echo "DEV_OFFSET=$((DEV_NOW - HOST_NOW))" >> "$TL"   # device clock - host clock (s); logcat is -v epoch
sleep "$PRE"

say "run scenario (broadcast mode): ${SCENARIO:-scenario_3min.sh}"
MODE=broadcast ADB="$ADB -s $SERIAL" "$HERE/${SCENARIO:-scenario_3min.sh}" 2>&1 | while IFS= read -r line; do
  printf '%.2f %s\n' "$(echo "$(date +%s.%N) - $T0" | bc)" "$line" | tee -a "$TL"
done

sleep "$POST"
# Arrival report -> 확인 -> home -> (epilogue: voice with a spot phrase) -> 설정, driven through the demo bridge (no screen-coordinate taps)
tl() { printf '%.2f [epi] %s\n' "$(echo "$(date +%s.%N) - $T0" | bc)" "$*" | tee -a "$TL"; }
say "ack report"; tl "리포트 확인 → 홈"
ADB="$ADB -s $SERIAL" "$HERE/demo_broadcast.sh" ack >/dev/null; sleep "${HOME_HOLD:-4}"
if [ -n "${EPILOGUE_VOICE:-}" ]; then
  say "epilogue voice: $EPILOGUE_VOICE"; tl "음성 \"$EPILOGUE_VOICE\""
  ADB="$ADB -s $SERIAL" "$HERE/demo_broadcast.sh" voice "$EPILOGUE_VOICE" >/dev/null; sleep "${EPILOGUE_HOLD:-11}"
  tl "세션 종료 (내렸어요)"; ADB="$ADB -s $SERIAL" "$HERE/demo_broadcast.sh" end >/dev/null; sleep 3
fi
say "open settings"; tl "설정 화면"
ADB="$ADB -s $SERIAL" "$HERE/demo_broadcast.sh" settings on >/dev/null; sleep "${SETTINGS_HOLD:-9}"
say "screenrecord stop"
$ADBS emu screenrecord stop | tr -d '\r' | grep -v "^$" || true
sleep 3
kill $LOGPID 2>/dev/null || true
say "done: $(ls -la "$WEBM" 2>/dev/null | awk '{print $5" bytes"}')"
printf '%s\n' "$WEBM"
