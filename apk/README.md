# APK (접수용 release 빌드, 2026-10-28)

| 파일 | 플레이버 | 대상 | 크기 |
|---|---|---|---|
| `VIPSeat_CargoConcierge_release.apk` | `pleos` | 공식 Pleos 에뮬레이터 `Pleos_Connect_v2.1` / Pleos Connect 차량 (Pleos Vehicle 2.3.2 · NaviHelper 3.0.5 · FusedLocation 1.1.3 · TTS/STT 3.3.1 연동) | 47.7 MB |
| `VIPSeat_CargoConcierge_mock_release.apk` | `mock` | 표준 AAOS 에뮬레이터(Automotive, API 34). 차량 신호는 CarPropertyManager + 데모 브리지 | 25.3 MB |

- 패키지 `ai.vipseat.cargoconcierge` · versionName `1.0.0` · versionCode 2 · minSdk 28 / targetSdk 34 · 두 파일 모두 같은 release 키(RSA 2048, `keystore/release.jks`, 저장소에 포함하지 않음)로 서명 · `apksigner verify` 통과.
- 파일명 규칙 `팀명_앱이름_release.apk` — 팀명 **VIPSeat**, 앱 이름 CargoConcierge(VIP 조수석).
- 무결성: `SHA256SUMS.txt` (`shasum -a 256 -c SHA256SUMS.txt`).
- 설치: `adb install -g apk/VIPSeat_CargoConcierge_release.apk` (`-g`: RECORD_AUDIO 등 런타임 권한 선부여). 이전에 debug 빌드가 설치돼 있으면 서명이 달라 `adb uninstall ai.vipseat.cargoconcierge` 먼저.
- 같은 파일이 GitHub Release `v1.0.0`에도 첨부되어 있습니다(시연 영상 2종 포함).
