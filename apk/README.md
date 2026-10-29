# 제출용 APK

[`VIPSeat_CargoConcierge_release.apk`](VIPSeat_CargoConcierge_release.apk)는 **Pleos SDK 연동 release 서명본**입니다. 공식 Pleos_Connect_v2.1 에뮬레이터에서 실행을 확인했습니다.

- 크기: 47,772,551 bytes
- 패키지: `ai.vipseat.cargoconcierge`, versionCode 3, versionName 1.1.0-pleos, minSdk 28 / targetSdk 34
- 서명 검증: `apksigner verify` 통과. 서명 키는 저장소에 포함하지 않습니다.
- 파일명: 공모전의 `팀명_앱이름_release.apk` 규칙 적용
- 현재 제출본은 이 폴더의 v1.1.0입니다. 기존 Release v1.0.0의 APK·영상은 당시 이력입니다.
- [변경·검증 기록](../docs/검증_v1.1.0.md): 코어 테스트 109개, lint 오류 0·경고 33. 실차 시험과 구분합니다.

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

표준 AAOS 테스트용 mock APK는 소스에서 `./gradlew assembleMockDebug`로 생성할 수 있습니다. 제출 대상 파일은 이 폴더의 Pleos release APK입니다.
