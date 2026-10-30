# 제출용 APK

[`VIPSeat_CargoConcierge_release.apk`](VIPSeat_CargoConcierge_release.apk)는 **Pleos SDK 연동 release 서명본**입니다.

- 크기: 47,936,867 bytes
- SHA256: `6a4a41ed2aa8f73fb0a7dd2b3bf499d3074a5d2ec9ecd21273763611097df287`
- 패키지: `ai.vipseat.cargoconcierge`, minSdk 28 / targetSdk 34
- 비착좌 확인 대상: Vehicle SDK 2.3.2 / VehicleService 2.3.22(versionCode 74) / crp_avd_arm64 API 34
- 실제 제어 준비: 공식 Emulator Console heartbeat 활성·SYNC OFF. 제출 검증에서는 heartbeat API를 사용하고 브라우저 physics 쓰기를 동시에 실행하지 않았습니다. 공조 전원은 앱이 켜지 않으며 설정값 확인 당시 상태를 표시합니다. 실제 SDK의 P단·정차·비착좌와 원값을 확인해야 합니다.
- 파일명: 공모전의 `팀명_앱이름_release.apk` 규칙 적용
- release 빌드·`apksigner verify` 통과. 코어 176개·앱 27개, 고유 203개 테스트 통과(플레이버 중복 포함 실행 228회, 실패·오류·건너뜀 0), lint 오류 0·경고 33. 서명 키는 저장소에 포함하지 않습니다.
- APK 식별·서명·빌드 검사 결과는 [제출 APK 검증 기록](../docs/검증.md), 기록 조회 6·제어 10·전체 동선 9개 판정은 [AVD 증거](../docs/검증자료/제출_APK/README.md), 연속 무음 78.689944초 화면은 [검증 영상](../docs/검증자료/제출_APK/flow-01/전체동선_무음.mp4)을 확인하세요.
- [별도 관측 APK 검증](../docs/별도관측.md)은 SHA-256 `8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c`의 관측과 음향 수집 실패입니다. 제출 APK의 실행 결과로 합산하지 않습니다. 실제 물건 보호·실차·사용자 검증은 별도로 남아 있습니다.

과거 공조 관측은 정차 중 참고용입니다. 조회만으로 차량 설정을 적용하거나 복원하지 않으며, 비정상 종료 뒤 자동 재개하지 않습니다.

저장소 루트에서 설치합니다.

```bash
adb install -r -g apk/VIPSeat_CargoConcierge_release.apk
adb shell am start -n ai.vipseat.cargoconcierge/.MainActivity
```

설치 후 Android 설정 → 앱 → VIP 조수석에서 위치를 **앱 사용 중에만 허용**, 알림을 허용합니다. GPS 보조 입력을 사용하려면 시스템 위치를 켭니다. 현재 시험은 실제 설정 UI로 이 상태를 확인했습니다. `-g` 옵션만으로 설정 확인을 대신하지 않습니다. 보호 세션을 시작하면 알림이 있는 포그라운드 서비스가 활성화됩니다. 비정상 종료 후에는 자동으로 재개하지 않습니다.

무결성 확인:

```bash
cd apk
shasum -a 256 -c SHA256SUMS.txt
```

데모 착좌 신호는 실제 제어 허용 근거가 아닙니다. 정확한 준비·환경 조건은 [실행 안내](../README.md#apk-실행)를 따릅니다. 표준 AAOS 테스트용 mock APK는 소스에서 `./gradlew assembleMockDebug`로 생성할 수 있습니다. 제출 대상은 이 폴더의 Pleos release APK입니다.
