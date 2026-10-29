# 공식 AVD 설정 적용과 복원 증거

2026-10-29, APK **1.1.1-pleos / versionCode 4**. 조수석 온도 **설정값 22→18→22℃**의 SDK 재조회와 앱 표시를 확인했습니다. 실제 실내·식품 온도, 짐 손상, 운전자 평가를 측정한 결과가 아닙니다.

## 조건과 관측

- Pleos Connect v2.1, Android 14/API 34, product `crp_avd_arm64`, 화면 2560×1440.
- Vehicle SDK 2.3.2, Vehicle Service 2.3.22/code 74. APK 해시는 [환경 기록](environment.json)에 있습니다.
- 공식 Virtual Console 8088 포트와 대상 emulator 연결을 확인하고 브라우저의 활성 세션을 유지했습니다. 공조 전원 ON, P·0km/h·조향 0, 조수석 22℃를 시험 시작 조건으로 준비했습니다.
- 착좌·온도는 공식 Console을 통해 AVD에 입력했습니다. 앱의 데모 브리지에서는 `preset/end/ack` UI 동작만 실행했으며 Mock 착좌·속도·온도를 차량 제어 허가에 쓰지 않았습니다.
- SDK 원시 로그, Console의 실제 구독 상태, 앱 화면을 함께 대조했습니다. setter 응답만으로 성공 판정하지 않았습니다.

| 조건 | 관측 결과 | 증거 |
|---|---|---|
| Console 착좌 true, SDK raw 2 | 앱은 미확인으로 표시, TEMPERATURE BLOCKED_BY_OCCUPANCY, 22℃ 유지 | [C1 화면](C1-occupied-blocked.png) · [로그](C1-occupied-blocked.log) |
| Console 착좌 false, SDK raw 1 | 빈 좌석 확인, 설정 전 22℃ | [C2 화면](C2-vacant-before.png) |
| 케이크 선택 | 원값22 조회 →18 요청 →18 재조회, APPLIED | [C3 화면](C3-applied.png) · [로그](C3-applied.log) |
| 종료 | 현재18 조회 →22 요청 →22 재조회, APPLIED 및 복원 확인 문구 | [C4 화면](C4-restored.png) · [로그](C4-restored.log) |
| 적용 후 Console에서21로 수동 변경 | 종료 시21 유지, UNCHANGED·덮어쓰기 없음 | [C5 화면](C5-external-change.png) · [로그](C5-external-change.log) |
| 적용18 중 Console 착좌 true | 비착좌 확인 상실→가드 종료→조건부22 복원·재조회 | [C6 화면](C6-vacancy-lost.png) · [로그](C6-vacancy-lost.log) |

[자동 판정](results.json)은 정상 왕복·착좌 보류·외부값 유지·확인 상실 복원 4조건을 나타냅니다. 수정 후 첫 실행과 포트 격리/정리 검사를 보강한 두 번째 실행에서 같은 4조건이 통과했습니다. 이 묶음은 두 번째 실행의 원자료입니다. 현장 실행표가 요구하는 모든 오류 조건 3회 반복을 완료한 것은 아닙니다.

[30초 무음 원본 영상](온도왕복_무음.mp4)은 같은 APK를 별도 실행해 연속 `adb screenrecord`로 캡처했습니다. 약0초22℃, 5초케이크 선택, 15초종료·22℃복원입니다. 편집·배속·음성 후처리는 없으며 실제 음향 재생 시험을 대신하지 않습니다. [영상 시점](video-timeline.json)과 [SDK 로그](video-roundtrip.log)를 함께 제공합니다.

공유 풍량·풍향은 유지, 열선·통풍 OFF는 이미 같은 값이라 유지했습니다. **별도 탐색에서 공조 전원 OFF 상태도 설정값18℃ 요청·재조회는 성공했습니다.** 따라서 설정값 성공은 냉방 작동이나 물리적 온도 감소를 의미하지 않습니다. 전원OFF를 getter 실패 재현으로 세지 않았습니다.

## 재현

허용된 로컬 공식 AVD에서 실행합니다. 스크립트는 다른 product/API/Service·실물 장치·잘못된8088포워딩을 거절합니다. 시험 대상 앱을 다시 시작하고 에뮬레이터 입력을 변경하므로 진행 중인 시연을 끝내고 실행합니다.

```bash
adb -s emulator-5554 install -r -g apk/VIPSeat_CargoConcierge_release.apk
adb -s emulator-5554 forward tcp:8088 tcp:8088
# http://127.0.0.1:8088/ 를 브라우저에서 열고 유지
python3 demo/verify_pleos_roundtrip.py --out /tmp/vipseat-avd-new-run
```

입력 전 P·0속도·0페달·0조향을 확인합니다. 자동 검사는 1분 안팎이며 `--out`에는 새 폴더를 지정합니다. 시험 뒤 앱을 중지하고 시험 전 온도·전원·착좌값을 운영자 정리로 되돌립니다. [정리 확인](tester-cleanup.json)은 앱의 복원 성공 증거와 별도입니다. 실패해도 이미 저장된 화면·로그·판정 파일을 보존합니다.

## 빌드와 검증 한계

[빌드 결과](build-summary.json): 코어121개·앱 매핑2개, 합계123개 모두 실패/오류/건너뜀0. Pleos release 빌드·서명 검증 통과, lint 오류0·기존경고33. 조회 반영 지연·1초상한·null·제3값·취소는 코어 가상시계 회귀로 검사했습니다.

실제 AVD에서 getter timeout 강제 주입, 여러 좌석을 읽는 사이의 지연, 권한 회수·연결 끊김의 전 조합은 수행하지 않았습니다. SDK의 Boolean 손실 때문에 현재 조합에서는 빈 좌석만 확정하고 착좌·미확인은 구별하지 못합니다. 실차·다른 Service/OS에 이 매핑을 일반화하지 않습니다. 읽기와 쓰기 사이 외부 변경 경쟁, 같은 값 수동 조작의 주체 식별, 프로세스 종료 후 자동 복원은 남은 제한입니다.

[공식 Console 실행 안내](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator) · [전체 변경·검증 기록](../../검증_v1.1.1.md) · [현장 진행 키트](../현장검증_진행키트/README.md)
