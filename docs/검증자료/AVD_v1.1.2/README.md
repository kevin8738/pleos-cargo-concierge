# 공식 AVD의 전체 동선과 조건별 제어 검증

APK **1.1.2-pleos / code5**, 2026-10-29. Pleos Connect v2.1·Android14/API34·crp_avd_arm64·Vehicle SDK2.3.2·Service2.3.22/code74에서 검증했습니다. [환경·APK 해시](environment.json), [빌드138시험](build-summary.json), [10항목 판정](results.json)을 제공합니다.

이 자료는 소프트웨어 연동 기록입니다. AVD의 입력은 합성 차량 신호이며 실제 차량·짐·운전자·물리 온도·음향을 측정한 결과가 아닙니다.

## 조건별 관측

| 조건 | 결과 | 실제 화면과 SDK 로그 |
|---|---|---|
| 착좌 raw2 | 미확인·설정 보류,22℃유지 | [C1](C1-occupied-blocked.png) · [로그](C1-occupied-blocked.log) |
| 비착좌 raw1 →케이크선택→종료 | 22→18→22℃,각 단계 재조회 후확인 | [적용](C3-applied.png) · [복원](C4-restored.png) · [로그](C4-restored.log) |
| 외부에서21℃변경 | 덮어쓰지 않고21℃유지 | [C5](C5-external-change.png) · [로그](C5-external-change.log) |
| 적용중 비착좌확인상실 | 가드종료,조건충족시22℃복원 | [C6](C6-vacancy-lost.png) · [로그](C6-vacancy-lost.log) |
| 전원OFF에서설정 | 18℃설정확인과전원꺼짐동시표시,OFF유지 | [C7](C7-power-off-setting-only.png) · [로그](C7-power-off-setting-only.log) |
| SYNC OFF | 조수석18℃요청·복원,운전석과SYNC유지 | [C8](C8-dual-false.png) · [로그](C8-dual-false.log) |
| SYNC ON | 온도변경보류,24℃와SYNC유지 | [C9](C9-dual-true.png) · [로그](C9-dual-true.log) |
| 적용후외부SYNC ON | 복원보류18℃유지,사용자가SYNC해제후UI재시도로22℃복원 | [보류](C9B-external-sync-restore-deferred.png) · [재시도](C9C-sync-off-retry-restored.png) |
| 적용후D단에서종료 | 18℃복원보류,P정차후UI재시도로22℃복원 | [D보류](C10-drive-restore-deferred.png) · [P복원](C11-park-retry-restored.png) |
| 적용후강제종료·재시작 | 18℃를임의로쓰지않고유지,이전복원미확인안내 | [C12](C12-restart-manual-check.png) · [로그](C12-restart-manual-check.log) |

C1~C12는 보조 체크포인트를 포함하며 자동판정 항목은10개입니다. 이 조합을 한 차례 끝까지 통과한 기록이고, 모든 조건을3회 반복했다는 주장은 하지 않습니다. 중간 빌드에서 발견한 SYNC 해제 문제를 수정한 뒤 같은 최종 APK로 전체를 다시 실행했습니다.

공조 전원 표시는 적용 후 조회한 역사적 상태입니다. 실제 냉방 효과나 복원 후 전원 재확인을 뜻하지 않습니다. SYNC 상태가 false로 확인되지 않으면 온도 요청·복원 모두 보류합니다. 사용자의 외부변경과 읽기/쓰기 사이 경쟁을 원자적으로 막는 기능은 아닙니다.

## 전체 사용 흐름 영상

[81.27초 전체 동선 무음 원본](전체동선_무음.mp4)은 화면을 끊지 않고 녹화했습니다. [시점 기록](flow-timeline.json), [SDK 로그](flow-sdk-flow.log), [판정](flow-results.json)을 함께 제공합니다. 약7초 최근 케이크1탭 →11초18℃설정확인 →25초주행배지 →46초P·운전석문열림 →51초챙김·22℃복원 →62초전원OFF표시 →76초착좌미확인보류 순서입니다. 모든 차량 입력은 공식 Console/DDS의 합성 신호입니다. Console 외부 속도 주입과 브라우저 물리 갱신의 불연속 전환으로 리포트에 최대4.53G가 나왔으며, 실제 차량 가속도·정확도·보호 효과를 뜻하지 않습니다. 숫자를 수정하거나 숨기지 않았습니다. 음성후처리·편집·배속이 없고 실제 음향 발생을 검증하지 않습니다. 설정값과 복원은 실제 AVD SDK 재조회로 확인했습니다.

## 재현

공식 Console을 브라우저에서 열어 DDS 활성 세션을 유지한 상태에서 실행합니다. 스크립트는 실물기기·다른product/API/Service·다른APK버전·잘못된포워드를 거절합니다.

```bash
adb -s emulator-5554 install -r -g apk/VIPSeat_CargoConcierge_release.apk
adb -s emulator-5554 forward tcp:8088 tcp:8088
# http://127.0.0.1:8088/ 를 브라우저에서 열고 유지
python3 demo/verify_pleos_roundtrip.py --out /tmp/vipseat-avd-new-run
```

새 출력 폴더를 지정하고P·속도/페달/조향0에서 시작합니다. 모든 조건 실행에는 수 분이 걸립니다. 실제 차량 입력 대신 Mock 신호를 제어 허가 근거로 쓰지 않습니다. preset/end/ack는 시험용UI동작이며 복원 재시도는 관측한UI버튼을 눌렀습니다. [시험자 정리](tester-cleanup.json)는 앱을 중지한 뒤 원래 전원·온도·SYNC·착좌·기어로 돌아갔는지를 따로 확인합니다.

## 차속 출처 전환 회귀

[수정 전](speed-handoff-before.json)과 [수정 후](speed-handoff-after.json)는 실제 코어 클래스에 합성 차속 표본을 입력한 기록입니다. 입력원만 바뀐9→10m/s/100ms에서 거짓0.509858G/음성1회가0G/0회로 사라졌고, 동일출처의 가속은 기존결과를 유지합니다. 실제 Android 신호 전환·실차 G정확도 시험은 아닙니다.

[전체 변경·한계](../../검증_v1.1.2.md) · [실제 사용자 검증 준비](../현장검증_진행키트/README.md)
