# VIP 조수석 Cargo Concierge

케이크·포장음식 등 차량에 실은 짐을 선택하면 지원되는 차량 설정·주행 코칭·도착 챙김을 이어 주는 **Pleos Connect 차량용 앱**입니다. 팀명은 **VIPSeat**입니다.

## 제출 자료

| 자료 | 최종본 |
|---|---|
| 접수양식 | [접수양식 v18](docs/submission/접수양식_VIP조수석_v18.docx) — 개인정보·동의·서명 기입 전 보관본 |
| 보완·검증 | [v1.1.0 변경·검증](docs/검증_v1.1.0.md) · [실측·사용자 검증 실행표](docs/검증자료/실측_사용자검증_실행표.md) |
| 기획서·근거 | [심사자료 본문](docs/기획서_VIP조수석.md) · [수치 출처와 등급](docs/근거_EVIDENCE.md) |
| 실행 APK | [VIPSeat_CargoConcierge_release.apk](apk/VIPSeat_CargoConcierge_release.apk) — Pleos SDK 연동, release 서명 |
| 실행 화면 | [화면 목록](docs/README.md) |
| 시연 영상 | [에뮬레이터 시연](https://github.com/kevin8738/pleos-cargo-concierge/releases/download/v1.0.0/vip-seat_emulator-demo_2m51s.mp4) — v1.0.0 당시 동작, 2분 49.9초 |
| 컨셉 영상 | [컨셉 광고](https://github.com/kevin8738/pleos-cargo-concierge/releases/download/v1.0.0/vip-seat_concept-ad_61s_1080p.mp4) — 61.2초 |
| 주행 시뮬레이션 | [실행 방법과 모델 설명](docs/sim/README.md) — v2.3, 아이오닉 5와 좌우 동시 비교 |

v1.0.0 당시 시연·컨셉 영상은 [Release v1.0.0](https://github.com/kevin8738/pleos-cargo-concierge/releases/tag/v1.0.0)에 있습니다. 시연 영상의 기존 다운로드 파일명은 링크 호환을 위해 유지했습니다. 저장소의 `apk/`에는 제출 대상 Pleos release APK 한 개를 둡니다.

## APK 실행

공식 **Pleos_Connect_v2.1** 에뮬레이터를 부팅한 뒤 저장소 루트에서 실행합니다.

```bash
adb install -r -g apk/VIPSeat_CargoConcierge_release.apk
adb shell am start -n ai.vipseat.cargoconcierge/.MainActivity
```

기존 debug 앱과 서명이 달라 설치가 실패하면 해당 앱을 제거한 뒤 설치해야 합니다. 앱 제거 시 기존 앱 데이터도 지워집니다.

에뮬레이터는 조수석 착좌를 기본으로 보고합니다. 적재 제안과 코칭을 재현할 때는 데모 브리지로 착좌를 해제하고 30초 억제 시간을 지난 뒤 시나리오를 시작합니다.

```bash
demo/demo_broadcast.sh occupied false
sleep 31
demo/demo_broadcast.sh scenario start
```

앱의 **케이크 시나리오** 버튼 또는 [PT 대본](demo/PT_SCRIPT.md)도 사용할 수 있습니다. 차량 신호 주입과 앱의 G 추정값을 구분하며, 공조·시트 제어 결과는 실제 SDK 응답을 표시합니다. v1.1.0은 원래 설정을 읽을 수 없으면 변경을 보류하므로 이전 영상과 적용 항목이 다를 수 있습니다. 수동 신호 주입은 기어·속도를 2초 안에 갱신해야 하며, 일회 주입 후 멈추면 신호 확인 상태로 전환됩니다.

## 테스트 환경과 설정

| 항목 | 환경 |
|---|---|
| 앱 | 패키지 `ai.vipseat.cargoconcierge`, versionCode 3, v1.1.0-pleos, minSdk 28, targetSdk 34 |
| 실행 검증 | Pleos_Connect_v2.1, Android 14 / API 34, arm64, 2560×1440 |
| 빌드 | JDK 17, Gradle 8.9, AGP 8.6.1, Kotlin 2.0.21, Android SDK 34 |
| Pleos SDK | Vehicle 2.3.2, NaviHelper 3.0.5, FusedLocation 1.1.3, TTS/STT 3.3.1 |
| 권한·신호 | `adb install -g`로 런타임 권한 부여, 접수용 APK에 DUMP 권한 보유 송신자용 데모 브리지 포함 |

공식 [개발 환경 안내](https://appdevchallenge.auto-hmg.io/guide/development)와 [Pleos 문서](https://document.pleos.ai/)의 SDK Manager 추가 저장소 절차로 에뮬레이터를 설치합니다. `local.properties`의 `sdk.dir`에는 로컬 Android SDK 위치를 설정합니다. SDK 경로와 서명 키는 Git에 포함하지 않습니다.

## 소스 빌드와 검증

저장소를 복제한 루트에서 실행합니다. 첫 빌드는 Gradle·Android·Pleos 의존성을 받을 네트워크가 필요합니다.

```bash
./gradlew :core:test
./gradlew assemblePleosDebug
```

생성 APK는 `app/build/outputs/apk/pleos/debug/app-pleos-debug.apk`입니다. 표준 AAOS용 개발 빌드는 `./gradlew assembleMockDebug`로 생성할 수 있습니다.

제출 APK의 무결성은 다음 명령으로 확인합니다.

```bash
cd apk
shasum -a 256 -c SHA256SUMS.txt
```

접수용 APK는 release 키로 서명했으며, 키는 배포하지 않습니다. 소스에서 재빌드한 APK는 제출 APK와 서명이 다를 수 있습니다. 제출 APK 정보는 [APK 안내](apk/README.md)를 참고하세요.

## 3D 비교 시연

```bash
cd docs/sim
python3 -m http.server 8788 --bind 127.0.0.1
```

[로컬 시뮬레이션](http://127.0.0.1:8788/)에서 **비교 시작**을 누르면 사람 기준과 케이크 기준 주행을 동시에 볼 수 있습니다. `1`은 체이스, `2`는 캐빈, `3`은 지도입니다. 첫 로딩에는 Three.js와 글꼴을 받을 네트워크가 필요합니다.

2021 아이오닉 5 공식 사진을 참고한 차체·실내·20인치 휠을 사용합니다. 케이크는 조수석 시트 위에 있으며 화면·핸들·제동등이 주행에 연동됩니다. 시각화 모델의 세부 형상은 사진 기반 근사입니다. 계산 결과와 손상 표현은 실차 시험값이 아니며, 수치의 출처·등급은 [근거표](docs/근거_EVIDENCE.md)에 구분했습니다.

## 검증 범위와 알려진 제한

- 앱 release 기준 검증: 새 버전 검사 내역은 [v1.1.0 검증 기록](docs/검증_v1.1.0.md)에 모았습니다.
- 시뮬레이션 v2.3: 전체 코스 검사 36개 통과, 콘솔·페이지 오류 0. A/B 주행 결과는 기존 기준과 일치합니다.
- v1.1.0은 원값을 읽은 항목만 변경하고 현재값 대조·요청 후 읽기로 조건부 복원합니다. 실패 시 새 설정을 보류하고 P 정차 후 재확인할 수 있습니다. 프로세스 종료 후 자동 복원과 읽기/쓰기 사이 경쟁 처리는 남은 제한입니다.
- 보온 열선 ON은 기존 동의와 무관하게 검증 대기로 차단합니다. 30/45분은 검증된 타이머가 아닙니다.
- 주행 안내는 음성·효과음 공통 8초 간격, 합계 6회(음성 최대 3회)입니다. 이는 시제품 정책이며 실제 재생·순정 내비와의 중첩 검증은 별도입니다.
- G는 차량 신호로 계산한 추정값입니다. 실차 센서 융합과 품목별 한계 실측은 추가 검증 대상입니다.
- 에뮬레이터 시연은 주행 신호를 데모 브리지로 주입합니다. 영상의 차량 음성은 앱이 요청한 TTS 문장을 후처리 합성한 부분을 명시했습니다.
- 컨셉 영상은 생성형 AI 연출이며, 차량 화면에는 에뮬레이터 캡처를 합성했습니다.
- Gleo 호출어·외부 연동은 확장 계획입니다. 현재 음성 입력은 앱 내 말하기 버튼과 Pleos/Android STT·텍스트 파서를 사용합니다.

## 저장소 구성

| 경로 | 내용 |
|---|---|
| `app/` | Compose 화면, ViewModel, 차량·음성 연결, 데모 브리지 |
| `core/` | SDK 독립 상태 머신, G 추정·코칭, 프리셋과 단위 테스트 |
| `vehicle-pleos/` | Pleos 차량·내비·위치·음성 SDK 어댑터 |
| `apk/` | 제출용 Pleos release APK와 체크섬 |
| `docs/` | 최신 접수양식, 기획서, 근거, 실행 화면, 주행 시뮬레이션 |
| `demo/` | 시연·녹화 재현 스크립트와 PT 대본 |

[공식 제출 가이드](https://appdevchallenge.auto-hmg.io/guide/submission)에 따라 접수양식의 참가 정보·동의·서명을 완료하고, 비공개 저장소의 심사 계정 접근을 확인해야 합니다. 개인정보를 기입한 제출 사본은 로컬에서 관리합니다.

코드 라이선스는 [MIT](LICENSE), 글꼴은 [Pretendard OFL](licenses/Pretendard-OFL-1.1.txt)입니다. 시뮬레이션 에셋의 출처·권리는 [별도 안내](docs/sim/README.md)에 정리했습니다.
