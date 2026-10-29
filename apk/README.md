# 제출용 APK

[`VIPSeat_CargoConcierge_release.apk`](VIPSeat_CargoConcierge_release.apk)는 **Pleos SDK 연동 release 서명본 v1.1.3-pleos**입니다.

- 크기: 47,821,703 bytes
- SHA256: `a994a151706c9091562b81b19e35c75b72f6b9b61b403f114ef6e6d26d1d8a84`
- 패키지: `ai.vipseat.cargoconcierge`, versionCode 6, minSdk 28 / targetSdk 34
- 비착좌 확인 대상: Vehicle SDK 2.3.2 / VehicleService 2.3.22(versionCode 74) / crp_avd_arm64 API 34
- 실제 제어 준비: 공식 Emulator Console 브라우저의 heartbeat 활성·SYNC OFF. 공조 전원은 앱이 켜지 않으며 설정값 확인 당시 상태를 표시합니다. 실제 SDK의 P단·정차·비착좌와 원값을 확인해야 합니다.
- 파일명: 공모전의 `팀명_앱이름_release.apk` 규칙 적용
- release 빌드·`apksigner verify` 통과. 코어 158개·앱 3개 테스트 통과(실패·건너뜀 0), lint 오류 0·경고 33. 서명 키는 저장소에 포함하지 않습니다.
- APK 버전·서명·빌드 검사 결과는 [v1.1.3 검증 기록](../docs/검증_v1.1.3.md), 정상 설정값 왕복 등 10개 판정는 [AVD 증거](../docs/검증자료/AVD_v1.1.3/README.md), 연속 무음 82.23초 화면은 [검증 영상](../docs/검증자료/AVD_v1.1.3/전체동선_무음.mp4)을 확인하세요.
- 기존 Release v1.0.0 APK·영상과 v1.1.0 화면은 당시 이력입니다. 실제 물건 보호·실차·사용자 검증은 별도로 남아 있습니다.

저장소 루트에서 설치합니다.

```bash
adb install -r -g apk/VIPSeat_CargoConcierge_release.apk
adb shell am start -n ai.vipseat.cargoconcierge/.MainActivity
```

무결성 확인:

```bash
cd apk
shasum -a 256 -c SHA256SUMS.txt
```

데모 착좌 신호는 실제 제어 허용 근거가 아닙니다. 정확한 준비·버전 조건은 [실행 안내](../README.md#apk-실행)를 따릅니다. 표준 AAOS 테스트용 mock APK는 소스에서 `./gradlew assembleMockDebug`로 생성할 수 있습니다. 제출 대상은 이 폴더의 Pleos release APK입니다.
