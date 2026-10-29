# 공식 AVD의 전체 동선과 조건별 제어 검증

APK **1.1.3-pleos / code6**, 2026-10-29. Pleos Connect v2.1·Android14/API34·crp_avd_arm64·Vehicle SDK2.3.2·Service2.3.22/code74에서 검증했습니다. [환경·APK 해시](environment.json), [빌드161시험](build-summary.json), [10항목 판정](results.json)을 제공합니다.

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

C1~C12는 보조 체크포인트를 포함하며 자동판정 항목은10개입니다. 이 조합을 한 차례 끝까지 통과한 기록이고, 모든 조건을3회 반복했다는 주장은 하지 않습니다. v1.1.3 최종 APK로 전체를 다시 실행했으며 공조 제어 정책은 이전 버전과 같습니다.

공조 전원 표시는 적용 후 조회한 역사적 상태입니다. 실제 냉방 효과나 복원 후 전원 재확인을 뜻하지 않습니다. SYNC 상태가 false로 확인되지 않으면 온도 요청·복원 모두 보류합니다. 사용자의 외부변경과 읽기/쓰기 사이 경쟁을 원자적으로 막는 기능은 아닙니다.

## 전체 사용 흐름 영상

[82.23초 전체 동선 무음 원본](전체동선_무음.mp4)을 끊김 없이 녹화했습니다. [시점 기록](flow-timeline.json), [SDK 로그](flow-sdk-flow.log), [9항목 판정](flow-results.json), [영상 정보](video-audit.json)를 제공합니다. 약7초 케이크1탭 →11초18℃확인 →25초주행배지 →46초P·운전석문 →51초도착·22℃복원 →62초공조OFF →77초착좌미확인보류 순서입니다. 모든 차량 입력은 공식 Console/DDS의 합성 신호입니다.

이번 보고서는 최대 계산 **3.69G**를 그대로 표시하면서 **모의 입력·실주행 평가 불가·고G 구간 원인 미확인**을 함께 보여 줍니다. 로그의 provenance=SYNTHETIC, quality=HIGH_G_UNASSESSED/INITIAL_BASELINE과 일치합니다. 이전4.53G와 새3.69G는 서로 다른 합성 재생의 출력이며 수치 감소를 정확도 개선으로 해석하지 않습니다. 알고리즘 상한으로 자르지 않았고 [v1.1.2 원본](../AVD_v1.1.2/README.md)을 유지합니다. 실제 차량·적재물·음향 검증은 아닙니다.

원본 길이82.233889초,2560×1440,35,460,679bytes입니다. 오디오 스트림이 없고 Android screenrecord의 데이터 스트림2개를 보존했습니다. 영상 전체 디코딩과 화면A1~A5를 확인했습니다. [흐름 시험자 정리](flow-tester-cleanup.json)는 앱 제어 결과와 별도로 기록합니다.

## 재현

공식 Console을 브라우저에서 열어 DDS 활성 세션을 유지한 상태에서 실행합니다. 스크립트는 실물기기·다른product/API/Service·다른APK버전·잘못된포워드를 거절합니다.

```bash
adb -s emulator-5554 install -r -g apk/VIPSeat_CargoConcierge_release.apk
adb -s emulator-5554 forward tcp:8088 tcp:8088
# http://127.0.0.1:8088/ 를 브라우저에서 열고 유지
python3 demo/verify_pleos_roundtrip.py --out /tmp/vipseat-avd-new-run
```

새 출력 폴더를 지정하고P·속도/페달/조향0에서 시작합니다. 모든 조건 실행에는 수 분이 걸립니다. 실제 차량 입력 대신 Mock 신호를 제어 허가 근거로 쓰지 않습니다. preset/end/ack는 시험용UI동작이며 복원 재시도는 관측한UI버튼을 눌렀습니다. [시험자 정리](tester-cleanup.json)는 앱을 중지한 뒤 원래 전원·온도·SYNC·착좌·기어로 돌아갔는지를 따로 확인합니다.

## 보고서 품질 회귀

[161개 빌드·단위 시험](build-summary.json), [독립 코드 검토](code-review.json), [변경과 한계](../../검증_v1.1.3.md)에 근거를 모았습니다. 모의 출처 보존,0.599/0.600G 경계, 큰 같은 출처 가감속 원값 유지, 부분 축·누락·무효/순서 역전, 새 차분 횟수, 출처 전환 직후 짧은 간격과 수치 overflow를 검사했습니다. 0.6G는 기존 음성 억제와 공유하는 시제품 판정 보류 정책이며 물리적 상한이나 오류 검출 기준이 아닙니다.

[실제 사용자 검증 준비](../현장검증_진행키트/README.md)는 데이터0행의 실행 준비 자료입니다.
