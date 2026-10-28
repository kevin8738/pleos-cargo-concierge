#!/usr/bin/env bash
# Runs the PT timeline from the host. MODE=broadcast (default, any emulator) or MODE=vhal (stock AAOS).
# Pair it with PT_SCRIPT.md: the talk track is timed to these events.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
MODE=${MODE:-broadcast}
B="$HERE/demo_broadcast.sh"
V="$HERE/inject_vhal.sh"

say() { printf '\n[%3ss] %s\n' "$SECONDS" "$*"; }

if [ "$MODE" = vhal ]; then
  say "VHAL mode: granting dangerous permissions, then running the VHAL scenario (navi curve via broadcast)"
  "$V" grant
  ( sleep 33; "$B" navi curve 100 ) &
  ( sleep 7;  "$B" preset CAKE ) &      # or tap 케이크 on the sheet
  "$V" scenario
  wait
  exit 0
fi

say "T+0  베이커리 앞 정차 (P, 0 km/h, 조수석 비어 있음 · 뒷좌석 오른쪽에 아이 탑승) · 최근 VIP 3개 · VIP 자리 = 조수석";   "$B" gear P; "$B" speed 0; "$B" occupied false; "$B" occupied false REAR_LEFT; "$B" occupied false REAR_CENTER; "$B" occupied true REAR_RIGHT; "$B" spot FRONT_PASSENGER; "$B" recent "도자기:FRAGILE,강아지:PET,꽃다발:FLOWERS"
sleep 0.5; say "T+0.5 차량 맵에서 VIP 자리를 잠깐 뒷좌석 오른쪽(탑승 중 → 들고 타기)으로, 다시 조수석으로"; "$B" spot REAR_RIGHT; sleep 1.6; "$B" spot FRONT_PASSENGER
sleep 1.2; say "T+3.3 조수석 도어 열림";                          "$B" door PASSENGER open
sleep 2.5; say "T+4  도어 닫힘 → 프리셋 시트 (10초)";             "$B" door PASSENGER closed
sleep 6;   say "T+10 '케이크' 선택 (음성 경로로 시연)";             "$B" voice "케이크 실었어"
sleep 4;   say "T+14 출발 D";                                     "$B" gear D
sleep 1;   "$B" speed 10; sleep 2; "$B" speed 17; sleep 2; "$B" speed 24; sleep 2; "$B" speed 30
sleep 2;   say "T+20 완만한 코너 45° → 0.13G 경고음";              "$B" steer 45
sleep 3;   "$B" steer 0; "$B" speed 30
sleep 3;   say "T+26 급한 코너 100° → 0.29G 음성 코칭";            "$B" steer 100
sleep 3;   "$B" steer 0; "$B" speed 30
sleep 4;   say "T+33 내비: 100 m 앞 급커브 → '조금 줄여 부드럽게'";   "$B" navi curve 100
sleep 2;   "$B" speed 27; sleep 2; "$B" speed 24; sleep 2; "$B" speed 22
sleep 2;   say "T+41 코너 통과 85° @22 km/h → 한계 안";            "$B" steer 85
sleep 3;   "$B" steer 0; "$B" speed 25
sleep 3;   "$B" speed 30; sleep 3; "$B" speed 30
sleep 1;   say "T+51 급제동 → 0.5G 음성 코칭";                     "$B" brake 60; "$B" speed 12
sleep 1;   "$B" speed 0; "$B" brake 0
sleep 4;   "$B" speed 10; sleep 3; "$B" speed 20; sleep 3; "$B" speed 12; sleep 2; "$B" speed 6; sleep 2; "$B" speed 0
sleep 2;   say "T+68 기어 P";                                     "$B" gear P
sleep 2;   say "T+70 운전석 도어 열림 → '조수석 케이크 챙기세요' + 리포트"; "$B" door DRIVER open
say "done"
