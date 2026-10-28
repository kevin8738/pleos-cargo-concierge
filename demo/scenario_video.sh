#!/usr/bin/env bash
# =====================================================================================
#  VIP 조수석 — 시연 영상용 타임라인 (scenario_3min.sh 의 주행 구간을 내레이션이 들어갈 만큼 늘리고, 앞에 "VIP 자리 선택"을 더함)
# =====================================================================================
#  record_emulator.sh 가 SCENARIO=scenario_video.sh 로 호출한다. 각 'say' 줄이 타임라인 로그에 남고, compose_demo.py 가
#  그 로그(주입 신호)와 logcat(앱 로그: TTS 문장·캐빈 적용 결과·G 추정값)을 읽어 자막·HUD·게이지·콜아웃을 그린다.
#  오디오: 에뮬레이터에 온디바이스 TTS 엔진이 없어(ON_DEVICE_ENGINE_ERROR) 음성 안내는 로그의 TTS 문장을 후처리로 합성한다.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
B="$HERE/demo_broadcast.sh"
say() { printf '\n[%3ss] %s\n' "$SECONDS" "$*"; }

say "T+0  베이커리 앞 정차 (P, 0 km/h) · 조수석 비어 있음 · 뒷좌석 오른쪽에 아이 탑승 · 최근 VIP 3개 · VIP 자리 = 조수석"
"$B" gear P; "$B" speed 0; "$B" occupied false; "$B" occupied false REAR_LEFT; "$B" occupied false REAR_CENTER; "$B" occupied true REAR_RIGHT
"$B" spot FRONT_PASSENGER; "$B" recent "도자기:FRAGILE,강아지:PET,꽃다발:FLOWERS"
sleep 5;   say "T+5   차량 맵: VIP 자리 → 뒷좌석 오른쪽 (탑승 중인 자리도 선택 가능 · 들고 타기)";  "$B" spot REAR_RIGHT
sleep 5.5; say "T+10.5 차량 맵: VIP 자리 → 트렁크 (공조 없음 · 가드 + 도착 챙김)";                "$B" spot TRUNK
sleep 4;   say "T+14.5 차량 맵: VIP 자리 → 조수석";                                              "$B" spot FRONT_PASSENGER
sleep 3;   say "T+17.5 조수석 도어 열림";                                                        "$B" door PASSENGER open
sleep 2.5; say "T+20  조수석 도어 닫힘 → 프리셋 시트 (10초)";                                    "$B" door PASSENGER closed
sleep 8.5; say "T+28.5 음성 \"케이크 실었어\"";                                                   "$B" voice "케이크 실었어"
sleep 17;  say "T+45.5 출발 D";                                                                  "$B" gear D
sleep 1;   "$B" speed 10; sleep 2; "$B" speed 17; sleep 2; "$B" speed 24; sleep 2; "$B" speed 30
sleep 2;   say "D+9  완만한 코너 45° @30 km/h → 0.13G 경고음";                                   "$B" steer 45
sleep 3;   "$B" steer 0; "$B" speed 30
sleep 3;   say "D+15 급한 코너 100° @30 km/h → 0.29G 음성 코칭";                                 "$B" steer 100
sleep 3;   "$B" steer 0; "$B" speed 30
sleep 11;  say "D+29 내비: 100 m 앞 급커브 → 권장 25 km/h";                                       "$B" navi curve 100
sleep 2;   "$B" speed 27; sleep 2; "$B" speed 24; sleep 2; "$B" speed 22
sleep 2;   say "D+37 코너 통과 85° @22 km/h → 한계 안";                                           "$B" steer 85
sleep 3;   "$B" steer 0; "$B" speed 25
sleep 3;   "$B" speed 30; sleep 3; "$B" speed 30
sleep 1;   say "D+47 급제동 (브레이크 60 %) → 0.5G 음성 코칭";                                    "$B" brake 60; "$B" speed 12
sleep 1;   "$B" speed 0; "$B" brake 0
sleep 4;   "$B" speed 10; sleep 3; "$B" speed 20; sleep 3; "$B" speed 12; sleep 2; "$B" speed 6; sleep 2; "$B" speed 0
sleep 2;   say "D+64 기어 P";                                                                    "$B" gear P
sleep 2;   say "D+66 운전석 도어 열림 → \"조수석 케이크 챙기세요\" + 리포트";                        "$B" door DRIVER open
say "done"
