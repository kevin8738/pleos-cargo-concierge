#!/usr/bin/env bash
# =====================================================================================
#  VIP 조수석 — demo bridge over adb broadcast (works on ANY emulator: stock AAOS, Pleos_Connect_v2.1, phone AVD)
# =====================================================================================
#  Drives the in-app MockVehicle / demo navi through DemoBroadcastReceiver (contest builds, adb shell permission required).
#  The app must be in the foreground (the mock signal flow has no replay).
#
#    ./demo_broadcast.sh gear P|D|R|N
#    ./demo_broadcast.sh speed <kph>
#    ./demo_broadcast.sh steer <deg>
#    ./demo_broadcast.sh brake <pct>              (0 = released)
#    ./demo_broadcast.sh door PASSENGER|DRIVER|REAR_LEFT|REAR_RIGHT|TRUNK open|closed
#    ./demo_broadcast.sh occupied true|false [SEAT]   (SEAT: FRONT_PASSENGER|REAR_LEFT|REAR_CENTER|REAR_RIGHT, 기본 FRONT_PASSENGER)
#    ./demo_broadcast.sh spot FRONT_PASSENGER|REAR_LEFT|REAR_CENTER|REAR_RIGHT|TRUNK   (VIP 자리 · 홈 차량 맵과 동일)
#    ./demo_broadcast.sh temp <°C>                (simulated passenger-zone reading)
#    ./demo_broadcast.sh mode SPORT|ECO|NORMAL
#    ./demo_broadcast.sh navi curve [distance_m] | bump [distance_m] | arrived
#    ./demo_broadcast.sh preset CAKE|HOT_FOOD|FLOWERS|GROCERIES|COFFEE
#    ./demo_broadcast.sh voice "케이크 실었어"        (Gleo/STT text hook)
#    ./demo_broadcast.sh scenario start|stop      (the 70 s cake run, see DemoScenario.kt)
#    ./demo_broadcast.sh offer "지오펜스: 단골 베이커리"   (F1 ②/③ 자동 제안: 부팅 전 도어 이력·픽업 매장 지오펜스 대역)
#    ./demo_broadcast.sh sheet | end | ack             (시트 열기 · 세션 종료 · 도착 리포트 확인)
#    ./demo_broadcast.sh settings on|off             (설정 화면 열기/닫기 — 녹화·PT용, 좌표 탭 불필요)
#    ./demo_broadcast.sh recent "도자기:FRAGILE,강아지:PET"   (최근 VIP 목록 시드 · clear 로 비움 · 분류: CAKE HOT_FOOD FLOWERS GROCERIES COFFEE FRAGILE ELECTRONICS PET GENERAL)
# =====================================================================================
set -euo pipefail
ADB=${ADB:-adb}
COMPONENT=ai.vipseat.cargoconcierge/.demo.DemoBroadcastReceiver
ACTION=ai.vipseat.cargoconcierge.DEMO

send() { $ADB shell am broadcast -n $COMPONENT -a $ACTION "$@" | grep -E "result|data" || true; }

case "${1:-help}" in
  gear|speed|steer|brake|temp|mode|preset|offer|settings|recent|spot) send --es cmd "$1" --es value "$2";;
  occupied) send --es cmd occupied --es value "$2" --es seat "${3:-FRONT_PASSENGER}";;
  door) send --es cmd door --es id "$2" --es value "$3";;
  navi) send --es cmd navi --es value "$2" --es distance "${3:-100}";;
  voice) $ADB shell am broadcast -n $COMPONENT -a ai.vipseat.cargoconcierge.VOICE_TEXT --es text "\"$2\"" | grep -E "result|data" || true;;
  scenario) send --es cmd scenario --es value "${2:-start}";;
  sheet|end|ack) send --es cmd "$1";;
  *) sed -n '2,25p' "$0";;
esac
