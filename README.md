# VIP 조수석 Cargo Concierge

[웹에서 시뮬레이션 실행](https://vip-seat-cargo-concierge.ivory-haven-1059.chatgpt.site) — 합성 비교 계산을 보여 주는 설명용 자료입니다.
케이크·포장음식 등 차량에 실은 짐을 선택하면 지원되는 차량 설정·주행 코칭·도착 챙김을 이어 주는 **Pleos Connect 차량용 앱**입니다. 팀명은 **VIPSeat**입니다.

[제출 파일 내려받기](https://github.com/kevin8738/pleos-cargo-concierge/releases/tag/submission)

## 제출 자료

| 자료 | 파일·상태 |
|---|---|
| 접수양식 | [VIP 조수석 접수양식](docs/submission/접수양식_VIP조수석.docx) — 개인정보·서명 공란 공개본 |
| 제출 요건 | [공식 양식·개발·제출 요건 확인](docs/제출요건_확인.md) |
| 기획서·근거 | [심사자료](docs/기획서_VIP조수석.md) · [수치 출처·등급](docs/근거_EVIDENCE.md) |
| 실행 APK | [VIPSeat_CargoConcierge_release.apk](apk/VIPSeat_CargoConcierge_release.apk) · [APK 안내](apk/README.md) |
| 제출 검증 | [검증 요약](docs/검증.md) · [원자료](docs/검증자료/제출_APK/README.md) — 고유 시험 203개·제어 10조건·기록 조회 6조건·동선 9항목 통과 |
| 실행 화면·영상 | [문서와 화면 색인](docs/README.md) · [전체 동선 무음 원본](docs/검증자료/제출_APK/flow-01/전체동선_무음.mp4) — 78.689944초·2560×1440·오디오 0개 |
| 별도 APK 관측 | [결과·한계](docs/별도관측.md) — 제어·서비스·음향·통합 관측과 실패 기록 |
| 알림 정책 계산 | [고정 합성 입력 54계산](docs/검증자료/알림_분석/README.md) — 입력 주기별 제약 포함 |
| 현장 검증 준비 | [실행표](docs/검증자료/실측_사용자검증_실행표.md) · [빈 CSV·진행 대본](docs/검증자료/현장검증_진행키트/README.md) |
| 발표·시뮬레이션 | [PT 실행 범위표](docs/PT_실행범위표.md) · [시뮬레이션 실행 안내](docs/sim/README.md) |

제출 APK는 **47,936,867바이트**, SHA-256 `6a4a41ed2aa8f73fb0a7dd2b3bf499d3074a5d2ec9ecd21273763611097df287`입니다. 별도 관측 APK의 SHA-256은 `8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c`이며, 해당 관측을 제출 APK의 실행 결과로 합산하지 않습니다.

## APK 실행

공식 **Pleos_Connect_v2.1** 에뮬레이터를 부팅한 뒤 저장소 루트에서 실행합니다. 비착좌 확인을 허용하는 환경은 Vehicle SDK **2.3.2**, VehicleService **2.3.22(versionCode 74)**, **crp_avd_arm64 / API 34** 조합입니다.

```bash
adb install -r -g apk/VIPSeat_CargoConcierge_release.apk
adb shell am start -n ai.vipseat.cargoconcierge/.MainActivity
```

설치 후 Android **설정 → 앱 → VIP 조수석 → 권한 → 위치**에서 **앱 사용 중에만 허용**을 선택하고 앱 알림을 허용합니다. GPS 보조 입력에는 시스템 위치 ON도 필요합니다. `-g` 설치 옵션만으로 실제 설정 UI의 권한 확인을 대신하지 않습니다. 위치를 사용하지 않아도 앱은 동작하지만 GPS 보조 입력은 중단됩니다.

설치된 debug 앱과 서명이 다르면 설치가 실패할 수 있습니다. 앱을 제거하면 기존 데이터도 지워지므로 필요한 기록을 보관한 뒤 설치합니다.

차량 제어에는 공식 Emulator Console heartbeat, 실제 SDK의 P단·정차·비착좌 확인이 필요합니다. 온도 변경은 SYNC OFF를 확인한 뒤 시작하며, 원래 설정값·상태·SYNC를 확인하지 못하면 보류합니다. 공조 전원이 꺼져 있어도 설정값 조회·변경은 가능하지만 앱은 전원을 켜지 않습니다. 제출 검증에서는 Console heartbeat API를 사용하고 브라우저 physics 쓰기를 동시에 실행하지 않았습니다. [조건별 원자료](docs/검증자료/제출_APK/README.md)를 참고하세요.

앱의 **케이크 시나리오** 버튼과 [PT 대본](demo/PT_SCRIPT.md)으로 화면·코칭을 재현할 수 있습니다. 실제 차량 제어 직전 조건은 SDK로 다시 확인합니다. 수동으로 화면 신호를 주입할 때는 기어·속도를 2초 안에 갱신해야 하며, 갱신이 멈추면 신호 확인 상태로 전환됩니다.

## 테스트 환경과 설정

| 항목 | 환경 |
|---|---|
| 앱 | 패키지 `ai.vipseat.cargoconcierge`, minSdk 28, targetSdk 34 |
| AVD | Pleos_Connect_v2.1, crp_avd_arm64, Android 14 / API 34, 2560×1440 |
| VehicleService | 2.3.22, versionCode 74; 공식 Console heartbeat 활성 |
| 빌드 | JDK 17, Gradle 8.9, AGP 8.6.1, Kotlin 2.0.21, Android SDK 34 |
| Pleos SDK | Vehicle 2.3.2, NaviHelper 3.0.5, FusedLocation 1.1.3, TTS/STT 3.3.1 |
| 권한·신호 | 위치 ‘앱 사용 중에만 허용’·알림 허용; GPS 보조 입력은 시스템 위치 ON. DUMP 권한 보유 송신자용 데모 브리지 포함 |

공식 [개발 환경 안내](https://appdevchallenge.auto-hmg.io/guide/development)와 [Pleos 문서](https://document.pleos.ai/)의 SDK Manager 추가 저장소 절차로 에뮬레이터를 설치합니다. `local.properties`의 `sdk.dir`에는 로컬 Android SDK 위치를 설정합니다. SDK 경로와 서명 키는 Git에 포함하지 않습니다.

## 소스 빌드와 검증

복제한 저장소 루트에서 실행합니다. 첫 빌드는 Gradle·Android·Pleos 의존성을 받을 네트워크가 필요합니다.

```bash
./gradlew :core:test :app:testPleosDebugUnitTest
./gradlew assemblePleosDebug
```

생성 APK는 `app/build/outputs/apk/pleos/debug/app-pleos-debug.apk`입니다. 표준 AAOS용 개발 빌드는 `./gradlew assembleMockDebug`로 생성합니다.

```bash
cd apk
shasum -a 256 -c SHA256SUMS.txt
```

접수용 APK는 release 키로 서명했고 키는 배포하지 않습니다. 소스에서 재빌드한 APK는 제출 APK와 서명이 다를 수 있습니다.

## 3D 비교 시연

```bash
cd docs/sim
python3 -m http.server 8788 --bind 127.0.0.1
```

[로컬 시뮬레이션](http://127.0.0.1:8788/)에서 **비교 시작**을 누르면 일반 주행 비교 설정과 케이크 기준 주행을 동시에 볼 수 있습니다. ‘사람 기준’은 비교용 설정명이며 사람의 안전 한계나 실측값을 뜻하지 않습니다. `1`은 체이스, `2`는 캐빈, `3`은 지도입니다. 첫 로딩에는 Three.js와 글꼴을 받을 네트워크가 필요합니다.

차체·실내는 2021 아이오닉 5 공식 사진을 참고한 근사 모델입니다. 계산·손상 표현은 실차 시험값이 아니며 수치 출처와 등급은 [근거표](docs/근거_EVIDENCE.md)에 구분했습니다.

## 검증 범위와 제한

- 고유 시험 **203개(코어 176·앱 27)**, 실패·오류·건너뜀 0입니다. 공통 25개를 Mock/Pleos에서 각각 실행한 횟수를 포함하면 **228회(코어 176·Mock 25·Pleos 27)**입니다. release 빌드·서명·Mock 컴파일 PASS, lint 오류 0·경고 33입니다.
- AVD 제어 10조건·과거 기록 조회 6조건·전체 동선 9항목과 각 실행의 정리를 확인했습니다. 과거 기록은 당시 조회값·요청값·독립 확인값과 시각을 보여 주는 로컬 참고 기능이며, 현재값·복원 권한으로 사용하지 않습니다. 최신 P·정차·UX 조건에서만 조회합니다.
- 전체 동선 영상의 최대 추정 0.32G는 합성 입력의 표시입니다. 별도 관측의 4.49G와 실행 조건이 달라 정확도 개선으로 비교하지 않습니다. 음향·실차 G·적재물 보호 효과의 검증도 아닙니다.
- 별도 통합 관측은 조향 기록 공백·시계 보정 불일치·도착 음향 대비 미충족으로 전체 false입니다. 별도 수집 진단은 PCM ValueError·조기 CANCELLED로 FAIL이며 도착 요청은 0회입니다. 원 실패·cleanup=false와 별도 상태 반환 기록을 보존했습니다.
- 설정값 확인은 냉방·상자 온도·짐 보호 효과의 계측이 아닙니다. 읽기/쓰기 사이 외부 변경 경쟁과 프로세스 종료 후 자동 복원은 지원 범위 밖입니다. 보온 열선 ON은 검증 대기로 차단했고 30/45분 안전 타이머를 구현하지 않았습니다.
- 음성·효과음 공통 8초, 세션 합계 6회·음성 최대 3회와 품목 한계는 검증 전 정책(D)입니다. 합성 계산 54개는 실제 재생·청취 부담·실차 효과의 관측과 구분합니다.
- 실제 참가자 과업·G·온도 계측·재사용·구매·채널 결과는 0건이며 현장 CSV 8종은 모두 데이터 0행입니다. Gleo 호출어·외부 연동은 확장 계획이며 현재 음성 입력은 앱 내 말하기 버튼과 Pleos/Android STT·텍스트 파서입니다.

## 저장소 구성

| 경로 | 내용 |
|---|---|
| `app/` | Compose 화면, ViewModel, 차량·음성 연결, 데모 브리지 |
| `core/` | SDK 독립 상태 머신, G 추정·코칭, 프리셋과 단위 테스트 |
| `vehicle-pleos/` | Pleos 차량·내비·위치·음성 SDK 어댑터 |
| `apk/` | 제출용 Pleos release APK와 체크섬 |
| `docs/` | 접수양식, 기획서, 근거, 실행 화면, 주행 시뮬레이션 |
| `demo/` | 시연·녹화 재현 스크립트와 PT 대본 |

[공식 제출 가이드](https://appdevchallenge.auto-hmg.io/guide/submission)에 따라 참가 정보·동의·서명을 완료하고 비공개 저장소의 심사 계정 접근을 확인해야 합니다. 개인정보를 기입한 제출 사본은 로컬에서 관리합니다.

코드 라이선스는 [MIT](LICENSE), 글꼴은 [Pretendard OFL](licenses/Pretendard-OFL-1.1.txt)입니다. 시뮬레이션 에셋의 출처·권리는 [별도 안내](docs/sim/README.md)에 정리했습니다.
