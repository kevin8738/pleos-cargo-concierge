# VIP 조수석 (Cargo Concierge) — Pleos Connect 앱 프로토타입

> "조수석 시트 열선은 떡볶이 워머였고, 조수석 에어컨은 케이크 쇼케이스였다."
> 운전자 기준이 아니라 **적재물 기준 0.15G**로 운전하도록 돕는 Android Automotive OS(Pleos Connect) 앱.

- 패키지 `ai.vipseat.cargoconcierge` · Kotlin · Jetpack Compose · minSdk 28 / targetSdk 34 · `android.hardware.type.automotive`
- 핵심 로직은 **SDK 독립 상태 머신**(`:core`, 순수 JVM)으로 분리되어 JUnit으로 검증됨. 차량/내비/음성은 포트(interface)로 추상화되고, Mock · AOSP(CarPropertyManager) · Pleos SDK 어댑터가 꽂힌다.

---

## 0. 제출 요약 (공모전 GitHub 제출 요건: 앱 이름·한 줄 소개 / 실행 방법 / 테스트 환경·필요한 설정 / APK 위치)

| 항목 | 내용 |
|---|---|
| 앱 이름 | **VIP 조수석 (Cargo Concierge)** |
| 한 줄 소개 | 조수석·뒷좌석·트렁크에 실은 ‘오늘의 VIP’(케이크·포장음식·꽃·장보기·반려동물 등 무엇이든)를 차가 도어·시동·픽업 매장 신호로 먼저 알아채 묻고, 원탭이면 그 자리의 공조·시트 열선과 주행 코칭이 사람이 아닌 **그 물건 기준**(예: 케이크 0.15 G·18 ℃)으로 바뀌는 Pleos Connect(AAOS) 앱 |
| APK 위치 | `apk/VIPSeat_CargoConcierge_release.apk` — Pleos SDK 연동 release(서명됨, 47.7 MB). 표준 AAOS 에뮬레이터용 `apk/VIPSeat_CargoConcierge_mock_release.apk`(25.3 MB). 체크섬 `apk/SHA256SUMS.txt`. 같은 파일 + 시연 영상 2종: GitHub Release [v1.0.0](https://github.com/kevin8738/pleos-cargo-concierge/releases/tag/v1.0.0) |
| 실행 방법 | 아래 **빠른 실행**(APK 설치, 3분) 또는 §3 소스 빌드(`./gradlew assembleRelease`) |
| 테스트 환경 | 공식 Pleos 에뮬레이터 **`Pleos_Connect_v2.1`**(Android 14 / API 34, arm64, 2560×1440) · macOS arm64 + Android Emulator 37.2.12 · JDK 17 · Gradle 8.9 / AGP 8.6.1 / Kotlin 2.0.21 · minSdk 28 / targetSdk 34 · Pleos SDK Vehicle 2.3.2 · NaviHelper 3.0.5 · FusedLocation 1.1.3 · TextToSpeech/SpeechToText 3.3.1 (Gradle 8.0+ · API 28+ 요건 충족) |
| 필요한 설정 | ① Pleos 에뮬레이터: SDK Manager에 Pleos 추가 저장소를 등록해 `system-images;android-34;pleos_car_emulator;arm64-v8a`를 설치하고 AVD 생성(§10 절차, 공식 가이드 `document.pleos.ai … setup-connect-sdk-emulator`) ② 설치 시 `adb install -g`(런타임 권한 선부여) ③ 에뮬레이터는 조수석 착좌를 기본 ‘있음’으로 보고하므로 적재 프롬프트를 보려면 `demo/demo_broadcast.sh occupied false` 후 30 초 대기 ④ 차량 신호(도어·속도·조향·내비)는 `demo/demo_broadcast.sh`로 주입(접수용 release 빌드에서도 동작, `BuildConfig.DEMO_BRIDGE`) — 공조·시트 제어와 TTS/STT는 실제 Pleos SDK 호출 |
| 시연 영상 | 에뮬레이터 시연(2 분 51 초, 화면 녹화 + 주입 신호·G 추정값 HUD): [`vip-seat_emulator-demo_2m51s.mp4`](https://github.com/kevin8738/pleos-cargo-concierge/releases/download/v1.0.0/vip-seat_emulator-demo_2m51s.mp4) · 컨셉 영상(61 초, 생성형 AI 연출, 디스플레이는 에뮬레이터 실제 캡처 합성): [`vip-seat_concept-ad_61s_1080p.mp4`](https://github.com/kevin8738/pleos-cargo-concierge/releases/download/v1.0.0/vip-seat_concept-ad_61s_1080p.mp4) |
| 문서 | 기획서(접수양식 02 심사자료 본문) `docs/기획서_VIP조수석.md` · 수치 근거표 `docs/근거_EVIDENCE.md` · 실제 실행 화면 `docs/screens/` · PT 대본 `demo/PT_SCRIPT.md` · **3D 주행 시뮬레이션** `docs/sim/`(§15, 실사형 에셋 v2 · 브라우저에서 사람 기준 vs 케이크 기준 비교, v1.1은 `docs/sim_v1/`) |

**빠른 실행** (Pleos_Connect_v2.1 AVD가 부팅된 상태):
```bash
adb uninstall ai.vipseat.cargoconcierge 2>/dev/null; adb install -g apk/VIPSeat_CargoConcierge_release.apk
adb shell am start -n ai.vipseat.cargoconcierge/.MainActivity
demo/demo_broadcast.sh occupied false && sleep 31 && demo/demo_broadcast.sh scenario start   # 70초 케이크 시나리오(적재 → 주행 코칭 → 도착 챙김 → 리포트)
demo/demo_broadcast.sh voice "뒷자리에 떡볶이 실었어"                                          # 음성 등록(Gleo/STT 텍스트 훅)
```

## 1. 기능 ↔ 구현 맵

| # | 요구사항 | 구현 위치 | 상태 |
|---|---|---|---|
| 1 | 기어 P + 속도 0 + 조수석(ROW_1_RIGHT)/뒷좌석 좌우(ROW_2_*)/트렁크 **열림→닫힘** → 닫힌 문의 자리로 10초 프리셋 시트 — 착좌 중·동승자 하차 30 s 이내·같은 트리거 3회 연속 무응답이면 억제, P 이탈 시 즉시 닫힘; 픽업 매장 지오펜스/부팅 전 도어 이력은 `Action.ExternalOffer` (케이크 0.15G/18℃, 뜨거운 음식 0.12G/열선2(기본)+송풍OFF, 꽃 0.20G/간접송풍 22℃, 장보기 0.15G/뒷좌석 쿨링, 커피 0.12G) | `core/.../engine/CargoStateMachine.kt` `onDoor()`, `model/CargoPreset.kt`, UI `PresetSheetScreen.kt` | 구현 + 단위테스트 |
| 2 | 프리셋 적용: 조수석 존 온도/풍량, 시트 열선/통풍(**빈 좌석 열선 동의** 게이트, OCS 차단 처리), 주행모드 | `VehiclePort.applyCabin()` — Mock/AOSP/Pleos 3종, 결과는 `FeatureStatus`(APPLIED / UNSUPPORTED / PERMISSION_DENIED / BLOCKED_BY_OCCUPANCY / SKIPPED_NO_CONSENT / DRIVING_RESTRICTED)로 UI 칩에 그대로 표시 | 구현. Pleos SDK 2.3.2에는 **주행모드 setter가 없음** → 읽기만 하고 SPORT 감지 시 음성으로 에코 권장 |
| 3 | 속도·조향각·브레이크로 횡/종가속 추정, 한계 80 % *진입 순간* 효과음 1회+정적 배지, 초과 시 TTS 코칭 — 주행당 3회 상한, 0.6 g 이상 긴급 조작·15 km/h 미만 선회(주차)에서는 음성 억제. 주행 중 UI는 정적 배지만 | `physics/GEstimator.kt`(공식 아래), `onMotion()`, `DrivingBadgeScreen.kt`, `CargoRoute.kt`(UiMode + CarUxRestrictions) | 구현 + 단위테스트 |
| 4 | NaviHelper 경로 정보 구독, 곡률 없으면 데모 "100 m 앞 급커브" 주입, TTS "100미터 앞 급커브, 조금 줄여 부드럽게" | `vehicle-pleos/PleosNavi.kt`(TBT `TURN_SHARP_*`/U턴, `CautionPoint.SPEED_BUMP`), `core/mock/MockNavi.kt`, `GEstimator.recommendedKph()` | 구현 + 단위테스트 |
| 5 | 도착: `DestinationArrived`(아직 D단이면 P 전환까지 원복 대기) 또는 P + 운전석 도어 열림 → TTS. 조수석 착좌가 감지되면 사람 우선으로 즉시 원복·종료 "조수석 케이크 챙기세요", 공조/시트 원복, 리포트(최대 G·평균 온도·코칭 횟수) | `arrive()`, `VehiclePort.restoreCabin()`, `ArrivalReportScreen.kt` | 구현 + 단위테스트 |
| 6 | Gleo/STT 훅: "케이크 실었어/떡볶이 실었어" → 프리셋 | `core/voice/VoiceIntentParser.kt`, `PleosVoiceInput`(Pleos STT 3.3.1) / `AndroidSttVoiceInput` / 텍스트 박스 / `am broadcast VOICE_TEXT` | 구현 + 단위테스트. Gleo Interface는 미공개 → 동일 파서에 텍스트를 넣는 seam만 제공 |

---

## 2. 아키텍처

```
┌──────────────────────────────────────────── :app (Android, Compose) ─────────────────────────────────────────────┐
│  MainActivity ── CargoViewModel (host)                                                                             │
│     │   signals/events ──► CargoStateMachine.dispatch(action, now) ──► effects                                     │
│     │                                                                  │  Speak → SpeechPort (TTS)                │
│     │   UiState(state, uiMode, snapshot, log…) ◄───────────────────────┤  Chime → ChimePort (ToneGenerator)       │
│     ▼                                                                  │  ApplyCabin/RestoreCabin → VehiclePort   │
│  CargoRoute: !interactive → DrivingBadgeScreen (정적 배지)              └  Log → 이벤트 로그                        │
│              Idle → Home · Offering → PresetSheet · Guarding → Loaded · Arrived → ArrivalReport · Settings         │
│                                                                                                                    │
│  di/FlavorSources  (flavor별 포트 조립)                                                                           │
│   mock  : MergedVehicle(AospCarVehicle?, MockVehicle) · MockNavi · AndroidTts · AndroidStt                         │
│   pleos : MergedVehicle([AospCarVehicle?, PleosVehicle], MockVehicle) · MergedNavi(PleosNavi, MockNavi) · Pleos TTS/STT │
│  demo/DemoBroadcastReceiver ─ adb am broadcast ─► DemoBridge ─► MockVehicle / MockNavi / UiCommand                 │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
          ▲ VehiclePort / NaviPort / SpeechPort / VoiceInputPort / ChimePort (interfaces)
┌──────────────────────── :core (pure Kotlin/JVM, no Android) ────────────────────────┐   ┌──── :vehicle-pleos (AAR) ────┐
│ model/   CargoPreset · VehicleSignal/Snapshot · NaviEvent · TripStats/Report         │   │ PleosVehicle  (Vehicle 2.3.2) │
│ physics/ GEstimator (a_lat = v²·tan(δ/ratio)/L, a_long = dv/dt ⊕ brake%)             │   │ PleosLocationSpeed (Fused 1.1.3)│
│          LocationSpeedEstimator (haversine/Δt)                                       │   │ PleosNavi     (NaviHelper 3.0.5)│
│ engine/  CargoStateMachine: Idle → Offering(10 s) → Guarding → Arrived               │   │ PleosSpeech / PleosVoiceInput   │
│ port/    VehiclePort · NaviPort · SpeechPort · VoiceInputPort · ChimePort · SpeedArbiter │   │   (TTS/STT 3.3.1)               │
│ mock/    MockVehicle · MockNavi · RecordingPorts · DemoScenario (70 s 타임라인)       │   │ PleosErrors (ErrorCode 분류)    │
│ voice/   VoiceIntentParser                                                           │   └──────────────────────────────┘
└──────────────────────────────────────────────────────────────────────────────────────┘
```

### 상태 머신 (core/engine/CargoStateMachine.kt)
```
Idle ──(P ∧ v≈0 ∧ PASSENGER|TRUNK open→closed)──► Offering(deadline = now+10 s)
Offering ──SelectPreset/VoicePreset──► Guarding      Offering ──Tick≥deadline | Dismiss | 주행 시작──► Idle
Guarding ──Speed/Steering/Brake──► GEstimate → ratio = |a|/limit
           ratio ≥ 0.8 → badge WARN + Chime(SOFT, 5 s 쿨다운)
           ratio ≥ 1.0 → badge OVER + Speak(코칭, 8 s 쿨다운)
         ──Navi SharpCurveAhead──► Speak("{d}미터 앞 급커브, {v}킬로미터 이하로")  (15 s 쿨다운)
         ──DestinationArrived | (P ∧ v≈0 ∧ DRIVER door open)──► Arrived(report) + Speak("조수석 {noun} 챙기세요") + RestoreCabin
Arrived ──Acknowledge──► Idle
UiMode = DRIVING iff v ≥ 0.5 m/s ∨ gear ∈ {D,R,N}  (+ CarUxRestrictions.isRequiresDistractionOptimization)
```

### G 추정 공식 (core/physics/GEstimator.kt)
- **횡가속** (기구학적 자전거 모델): `δ_road = δ_wheel / steeringRatio(15)`, `κ = tan(δ_road) / L (L = 2.9 m)`, `a_lat = v² · κ`.
  예: 30 km/h·핸들 90° → δ_road 6° → κ 0.0362 → 2.52 m/s² = **0.26 g**. 타이어 슬립/언더스티어를 무시하므로 고속에서 약간 과대추정(보수적 가드).
- **종가속**: `a_long = dv/dt`(EMA α=0.5). 브레이크 페달 %가 있으면 `a_brake = −(pct/100)·8 m/s²`와 비교해 큰 쪽. Pleos SDK의 브레이크는 boolean뿐이라 부호 귀속에만 사용.
- `total = √(a_lat² + a_long²)`, `ratio = total / limit`. 급커브 권장속도 `v = √(limit·g·R)`, R = 30 m(급커브 가정), 5 km/h 반올림 → 케이크 25, 꽃 30, 뜨거운 음식/커피 20.
- **속도 소스**: AOSP는 `PERF_VEHICLE_SPEED`. Pleos Vehicle SDK 2.3.2에는 **속도 API가 없어** FusedLocation 위치 차분(`LocationSpeedEstimator`)으로 대체 — 1 Hz 수준이라 종가속 추정이 거칠다(알려진 위험 참조).

---

## 3. 빌드 · 실행

```bash
# 요구: JDK 17, Android SDK (platforms;android-34, build-tools;34.0.0). 네트워크(첫 빌드: Gradle 8.9, AGP 8.6.1, Pleos AAR)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home   # 예시(macOS/Homebrew)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools                     # 또는 local.properties의 sdk.dir
cd hmg-app-contest/app

./gradlew :core:test                         # SDK 독립 단위테스트 (Android SDK 없이도 동작)
./gradlew assembleDebug testDebugUnitTest    # mockDebug + pleosDebug APK, 모든 단위테스트
./gradlew assembleMockDebug                  # 표준 AVD / AAOS 에뮬레이터용
./gradlew assemblePleosDebug                 # Pleos_Connect_v2.1 (Pleos SDK 포함)
./gradlew -PincludePleos=false assembleDebug # Pleos Maven 접근 불가(오프라인) 시: pleos 모듈/플레이버 제외

adb install -r app/build/outputs/apk/mock/debug/app-mock-debug.apk      # 또는 app/build/outputs/apk/pleos/debug/app-pleos-debug.apk
adb shell am start -n ai.vipseat.cargoconcierge/.MainActivity
```

릴리스(서명) 빌드 — 접수용 APK는 이렇게 만들었다:
```bash
# keystore/keystore.properties (gitignored): storeFile=keystore/release.jks / storePassword / keyAlias / keyPassword
# 파일이 없으면 release 빌드는 debug 키로 서명되므로 소스만 받아도 assembleRelease가 설치 가능한 APK를 만든다.
./gradlew :core:test assembleRelease
cp app/build/outputs/apk/pleos/release/app-pleos-release.apk apk/VIPSeat_CargoConcierge_release.apk
cp app/build/outputs/apk/mock/release/app-mock-release.apk  apk/VIPSeat_CargoConcierge_mock_release.apk
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs apk/VIPSeat_CargoConcierge_release.apk
```

도구 설치가 안 된 macOS에서 비대화형으로 준비하는 절차(이 저장소를 만들 때 실제로 사용):
```bash
brew install openjdk@17 && brew install --cask android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
yes | sdkmanager --sdk_root=$ANDROID_HOME --licenses
sdkmanager --sdk_root=$ANDROID_HOME --install "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

### 플레이버
| 플레이버 | 차량 | 내비 | 음성 | 용도 |
|---|---|---|---|---|
| `mock` (기본) | `AospCarVehicle`(자동차 기기면 CarPropertyManager) ⊕ `MockVehicle` | 데모 주입 | Android TTS/STT | 표준 AVD, AAOS 에뮬레이터, 단위테스트 |
| `pleos` | `PleosVehicle`(Vehicle 2.3.2, 제어 대상) ⊕ `AospCarVehicle`(CarPropertyManager: 속도/기어) ⊕ `MockVehicle` — 속도는 `SpeedArbiter`로 데모 > CarPropertyManager > FusedLocation 순 | `PleosNavi`(NaviHelper 3.0.5) ⊕ 데모 주입 | Pleos TTS/STT 3.3.1 → 실패 시 Android 엔진 | Pleos_Connect_v2.1, 실차 |

두 플레이버 모두 데모 브리지를 합쳐(merge) 두므로, 에뮬레이터가 못 내는 신호는 adb로 보충할 수 있다. **캐빈 제어 결과는 절대 가짜로 만들지 않는다** — 실제 소스가 거부하면 거부로 표시된다.

---

## 4. 데모

1. **앱 내 원버튼**: 홈/로드 화면 → `▶ 케이크 시나리오 (70초)`. `core/mock/DemoScenario.kt`의 타임라인을 실시간 재생(모든 에뮬레이터).
2. **adb 브로드캐스트** (`demo/demo_broadcast.sh`): 어떤 에뮬레이터에서든 MockVehicle/MockNavi를 조작. `./demo_broadcast.sh voice "케이크 실었어"`, `./demo_broadcast.sh navi curve 100`, `./demo_broadcast.sh scenario start`, `./demo_broadcast.sh ack` (리포트 확인), `./demo_broadcast.sh settings on|off`.
3. **녹화** (`demo/record_emulator.sh <apk> <out_dir>`): 부팅 대기 → `install -g` → 실행 → 착좌 해제 후 34 s 안정화(에뮬레이터 기본값이 조수석 착좌라 ‘동승자 하차 30 s 억제’에 걸리지 않도록) → 에뮬레이터 내장 `screenrecord`(webm) → `scenario_3min.sh`(기본) 또는 `SCENARIO=scenario_video.sh`(영상용: 자리 선택 장면·읽을 시간 추가) → 리포트 확인 → (`EPILOGUE_VOICE="조수석 뒤에 떡볶이 실었어"`면 음성 자리+물건 등록 장면) → 설정 탭. 타임라인(`timeline_*.log`)과 `logcat -v epoch`를 함께 저장한다.
   - 빠른 편집: `demo/make_demo_video.py --video … --timeline … --logcat … --out …` → 1080p H.264 + 타이틀/엔드 카드 + 자막(주입 신호·`TTS:` 줄).
   - **제출용 정교 편집**: `demo/compose_demo.py --video … --timeline … --logcat … --title-png ../../video/cards/title.png --end-png ../../video/cards/end.png --out …` (장면 스크립트 `demo/demo_script.py`, 카드 `demo/render_cards.py`). 녹화 자체의 로그에서 모든 것을 뽑는다: 왼쪽 패널(챕터·주입 신호 HUD·**G 게이지 = 앱이 logcat에 남기는 `CargoVM: G lat=… lon=… total=… limit=… badge=…` 디버그 줄**·Pleos SDK `setProperty` 결과 티커), 근거 태그가 달린 콜아웃·하이라이트·줌, 내레이션(Edge 신경망 음성 `ko-KR-InJoonNeural`)과 **차량 음성(앱이 Pleos TTS에 넘긴 `TTS:` 문장을 `ko-KR-SunHiNeural`로 합성 — 에뮬레이터에 온디바이스 TTS 엔진이 없어 `ON_DEVICE_ENGINE_ERROR`로 소리가 나지 않기 때문; 영상 카드·패널에 명시)**, 효과음(`AudioTrack` 타임스탬프에 ToneGenerator PROP 톤 재현), 두 음성의 자막. 앵커가 이벤트 기반(`ev:`/`log:`/`car:`/`say:`)이라 재녹화해도 자막·콜아웃·내레이션이 자동 정렬된다. `--preview-at 12,40,100`으로 프레임만 뽑아 확인, `--no-tts`로 음성 없이 레이아웃만. 내레이션이 차량 음성과 겹치면 뒤로 밀리고 5 s 이상 밀리면 경고를 출력한다.
4. **VHAL 주입** (`demo/inject_vhal.sh`): 표준 AAOS 에뮬레이터에서 `cmd car_service inject-vhal-event / set-property-value`로 실제 CarPropertyManager 경로를 탄다. 속도/기어는 `pm grant`로 권한 부여 가능(dangerous), 도어/조향/시트/공조는 privileged라 일반 앱은 못 읽음 → 그 신호는 2번으로 보충.
5. **3분 PT**: `demo/PT_SCRIPT.md` (멘트 + 타임라인), `demo/scenario_3min.sh` (호스트에서 타임라인 송출, `MODE=broadcast|vhal`).

데모 브리지와 수동 신호 버튼은 **정차(PARKED) UI에서만** 보이고, 브로드캐스트 리시버는 debug 빌드에서만 동작한다.

---

## 5. 무엇이 진짜이고 무엇이 목인가

| 영역 | 진짜 (코드가 실제 API를 호출) | 목 / 대체 |
|---|---|---|
| 상태 머신·프리셋·G 추정·음성 파서 | 순수 Kotlin, 54개 JUnit 테스트 | — |
| Pleos Vehicle | `Vehicle → getDoor/getGear/getBrake/getSteeringWheel/getSeat/getHVAC/getDrivingMode` 등록·조회·설정 (아래 표) | 속도: SDK에 없음 → FusedLocation 차분. 주행모드 설정: SDK에 없음 → 읽기+권장 음성 |
| Pleos NaviHelper | `initialize/addListener/getTBTInfo`, `onDestinationArrived/onTBTInfo/onCautionPointInfo/...` | 곡률 데이터 없음 → TBT 급회전 타입으로 근사, 없으면 데모 주입 |
| Pleos TTS/STT | `TextToSpeech(ctx, Mode.HYBRID).initialize/speak`, `SpeechToText.request/addListener` | 초기화 실패 시 Android 플랫폼 TTS/SpeechRecognizer |
| Gleo Interface | — (미공개) | `VoiceIntentParser`에 텍스트를 넣는 seam (`VOICE_TEXT` 브로드캐스트, 텍스트 박스) |
| AOSP 경로 | `CarPropertyManager.registerCallback/set*Property`, `CarUxRestrictionsManager` | privileged 권한 없는 신호는 데모 브리지 |
| 차량 센서 | 속도·조향각·브레이크·도어·착좌·공조온도 | 가속도계 미사용(의도) |

### SDK 호출: 검증됨 vs 가정
검증 방법: Nexus(`nexus-playground.pleos.ai`)에서 실제 AAR을 내려받아 `javap -p`로 시그니처 확인 + 공식 샘플(github.com/Pleos-Playground/pleos-sample-*-app) 호출 패턴 대조. `:vehicle-pleos` 모듈이 실제 아티팩트에 대해 **컴파일**되므로 이름/타입 오류는 빌드가 잡는다.

**검증됨 (javap + 샘플)**
- `ai.pleos.playground.vehicle.Vehicle(Context)`, `initialize()`, `release()`, `getDoor()/getGear()/getBrake()/getSteeringWheel()/getSeat()/getHVAC()/getDrivingMode()`
- Door: `checkDoorPositionCapabilities((CapabilityConfig, Map<DoorArea, AreaBounds<Int>>)->Unit, (Exception)->Unit)`, `getCurrentDoorPosition(DoorArea, (Int)->Unit, (Exception)->Unit)`, `registerDoorPosition/unregisterDoorPosition(DoorArea, DoorPositionListener)`; `DoorArea(DoorValue.ROW_1_RIGHT)`, `DoorValue.{ROW_1_LEFT,ROW_1_RIGHT,REAR}`; `DoorPositionListener.onPositionUpdated(Int?)`, `VehicleListener.onFailed(Exception)`
- Gear: `getCurrentGearSelectionState((VehicleGear)->Unit, …)`, `registerGearSelection(GearSelectionListener)`; `VehicleGear.GEAR_PARK/DRIVE/REVERSE/NEUTRAL/UNKNOWN`
- Brake: `getCurrentBrakePedalStatus((Boolean)->Unit, …)`, `registerBrakePedalStatus(BrakePedalStatusListener)` (boolean only)
- SteeringWheel: `checkSteeringWheelAngleCapabilities`, `getCurrentSteeringWheelAngle((Float)->Unit, …)`, `registerSteeringWheelAngle(SteeringWheelAngleListener)`
- HVAC: `setHvacTemperature(SeatArea, Float, TemperatureUnit, (SeatArea, Float)->Unit, …)`, `getHvacTemperature(SeatArea, TemperatureUnit, (Float)->Unit, …)`, `setHvacFanSpeed/getHvacFanSpeed`, `setHvacFanDirection/getHvacFanDirection(FanDirectionType.FACE/FLOOR)`, `registerHvacTemperature(SeatArea, HvacTemperatureListener)`, `checkHvacTemperatureCapabilities/checkHvacFanSpeedCapabilities/checkHvacFanDirectionCapabilities`; `TemperatureUnit.CELSIUS`
- Seat: `setHvacSeatTemperature/getHvacSeatTemperature(SeatArea, Int …)`, `setHvacSeatVentilation/getHvacSeatVentilationState`, `checkSeatTemperatureCapabilities/checkSeatVentilationCapabilities`, `getCurrentSeatOccupancy(SeatArea, (Boolean)->Unit, …)`, `registerSeatOccupancy(SeatArea, SeatOccupancyListener)`; `SeatArea(SeatValue.ROW_1_RIGHT)`
- DrivingMode: `checkDrivingModeStateCapabilities`, `getCurrentDrivingModeState((DrivingModeType)->Unit, …)`, `registerDrivingModeState(DrivingModeStateListener)`; `DrivingModeType.NORMAL/ECO/SPORT` (setter 없음)
- Capability 모델: `CapabilityConfig.accessType: AccessType{NONE,READ,WRITE,READ_WRITE}`, `AreaBounds<T>.minValue/maxValue`
- 권한 문자열: `PleosPermission.CAR_*/CONTROL_CAR_*` = `pleos.car.permission.*`; 샘플 매니페스트의 `CAR_SPEED`, `CAR_POWERTRAIN`, `READ_CAR_STEERING`; 에러 코드 `ErrorCode.{ERROR_SECURITY_PERMISSION, ERROR_NOT_SUPPORTED_PROPERTY/AREA/VALUE_TYPE, ERROR_DRIVING_RESTRICTED, …}`
- NaviHelper: `NaviHelper(Context)`, `initialize()`/`initialize(onSuccess, onFailure)`, `addListener/removeListener(NaviHelperEventListener)`, `getTBTInfo()`, `getRouteStateInfo()`, `release()`; 리스너 `onDestinationArrived(DestinationArrivedInfo{name,address,duration,distance})`, `onWayPointArrived(WaypointArrivedInfo)`, `onTBTInfo(List<TBTInfo{type: TurnType, distance: Double, description}>)`, `onCautionPointInfo(List<CautionPoint{id, type: CautionPointType, remainDistance: Int}>)`, `onCurrentRoadSpeedLimit(Int)`, `onNavigationStatus(NavigationStatus{INACTIVE,INITIALIZING,FREE_DRIVE,ROUTE_DRIVE})`, `onError(NaviErrorCode)`; `TurnType.TURN_SHARP_R/L, TURN_UTURN, TURN_EXIT_ROUNDABOUT`; `CautionPointType.SPEED_BUMP`
- FusedLocation: `FusedLocation(Context)`, `isSupported()`, `initialize()`, `registerFusedLocationCallback/unregisterFusedLocationCallback(FusedLocationListener{onChanged(LocationData), onError(Exception)})`, `release()`; `LocationData{latitude, longitude, altitude, heading, confidence, timestampMillis}`(속도 필드 없음); `addProximityAlert(ProximityAlertRequest(lat, lon, radiusMeters, Set<ProximityTransition>), ProximityAlertListener)`; 권한 `pleos.car.permission.FUSED_LOCATION`
- TTS 3.3.1: `TextToSpeech(Context, Mode{SERVER,ON_DEVICE,HYBRID})`, `initialize(OnInitializeListener{onSuccess, onFailure(ErrorType)})`, `speak(String, Boolean)`, `stop()`, `setLocale(Locale)`, `release()`, `addEventListener(EventListener)`
- STT 3.3.1: `SpeechToText(Context, Mode)`, `initialize(OnInitializeListener)`, `addListener/removeListener(ResultListener{onUpdated(String, Boolean), onStartedRecognition, onEndedRecognition, onError(ErrorType), onReady, onUpdatedRms, onUpdatedEpdData})`, `request()`, `stop()`, `setLocale(Locale)`
- AOSP: `Car.createCar`, `CarPropertyManager.registerCallback/getCarPropertyConfig/get*Property/set*Property`, `VehiclePropertyIds.{PERF_VEHICLE_SPEED, GEAR_SELECTION, PERF_STEERING_ANGLE, DOOR_POS, SEAT_OCCUPANCY, HVAC_TEMPERATURE_SET, HVAC_FAN_SPEED, HVAC_FAN_DIRECTION, HVAC_SEAT_TEMPERATURE, HVAC_SEAT_VENTILATION}`, `VehicleAreaSeat`, `VehicleGear`, `CarUxRestrictionsManager.registerListener`, `CarUxRestrictions.isRequiresDistractionOptimization` (android-34 `android.car.jar`)

**가정 (코드에 ASSUMPTION/TODO로 표시, 런타임에서만 확인 가능)**
- `Vehicle.initialize()` 후 서비스 바인딩이 비동기 완료된다고 보고 800 ms 뒤 `register*` 호출 (샘플은 사용자 조작 시점에 호출). 실패 시 상태줄에 표시.
- `TextToSpeech.speak(text, flag)`의 boolean = 큐 flush. HYBRID 모드가 `registerApp()` 없이 동작. (실패하면 Android TTS로 폴백)
- `ResultListener.onUpdated(text, isFinal)`의 boolean = 최종 결과 표시.
- `TBTInfo.distance` 단위 = m. TBT는 push + `getTBTInfo()` 3초 폴링으로 보강.
- Vehicle SDK 예외 클래스는 난독화(`callback.exception.a`)되어 있어 `ErrorCode` 반환 메서드를 리플렉션으로 읽음(없으면 메시지 휴리스틱).
- Pleos 에뮬레이터(user 빌드)에서는 `cmd car_service inject-vhal-event/get-property-value`가 `SecurityException: requires non-user build`로 막힌다 → 데모 브리지가 1순위(확인: 2026-10-26).
- AOSP 도어 영역 상수(`VehicleAreaDoor`)는 public SDK에 없어 숫자(0x1/0x4/0x20000000) 사용; 브레이크 페달 % 속성(`291504910`)은 API 35 전용.
- `inject_vhal.sh`의 명령 구문은 AOSP `CarShellCommand` 소스 기준. Pleos 에뮬레이터에서는 위 이유로 실행 불가, 표준 AAOS(userdebug) 에뮬레이터에서만 의미가 있다.

---

## 6. 알려진 위험
1. **속도 신호(Pleos)**: Vehicle SDK 2.3.2 typed API에 속도가 없다. FusedLocation 차분은 ~1 Hz라 종가속 추정이 거칠고 지하/터널에서 끊긴다. 완화: 횡가속(조향각 기반, 코너 진입 전 코칭)이 주력; Pleos가 `PERF_VEHICLE_SPEED` typed API를 열면 `PleosVehicle`에 리스너 한 줄 추가.
2. **캐빈 제어 권한/정책**: `CONTROL_CAR_CLIMATE/SEATS`는 App Market 사전 협의 대상이며 제어는 정차 조건(`ERROR_DRIVING_RESTRICTED`)이 붙을 수 있다. 앱은 거부를 UI/음성으로 그대로 보여주며 가드 기능은 읽기 전용으로도 완결된다.
3. **빈 좌석 열선(OCS)**: 많은 차량이 빈 좌석 열선을 막는다. 동의 토글 + 읽기 되돌림(read-back 0 → `BLOCKED_BY_OCCUPANCY`) + 송풍 OFF 대체 보온.
4. **내비 데이터 의존**: NaviHelper 이벤트는 기본 내비 앱의 지원 범위에 따른다. 곡률은 제공되지 않아 TBT 급회전 타입으로 근사(가짜 정밀도 금지), 데모는 주입 이벤트.
5. **TTS/STT 가용성**: Pleos 에뮬레이터에는 Android TTS 엔진이 없고(`TTS init failed: -1`), Pleos TTS/STT는 매니페스트에 `pleos.car.permission.TTS_SERVICE`/`STT_SERVICE`를 선언해야 초기화된다(선언 후 `Pleos TTS ready`/`Pleos STT ready` 확인). 폴백 체인 + 로그 패널의 `TTS:` 줄은 그대로 유지.
6. **Distraction 심사**: 주행 중 화면은 정적 배지 1개(애니메이션·수치 변화 없음), 모든 상호작용은 PARKED 전용. `CarUxRestrictionsManager`도 함께 반영.
7. **에뮬레이터 VHAL 주입**: 표준 AAOS 에뮬레이터에서 일반 앱이 받을 수 있는 건 speed/gear(dangerous 권한)뿐. priv-app 설치(adb root/remount + privapp-permissions XML)는 가능하지만 이 세션에서 검증하지 않았다.

---

## 7. 빌드/테스트 결과 (작성 세션, macOS arm64, JDK 17.0.21, Gradle 8.9, AGP 8.6.1, Kotlin 2.0.21)
| 항목 | 결과 |
|---|---|
| `./gradlew assembleDebug testDebugUnitTest` | **BUILD SUCCESSFUL** (mockDebug + pleosDebug 모두 빌드, Pleos AAR 실제 해석·컴파일) |
| `:core:test` | **73 tests, 0 failures** — GEstimator 8 · CargoStateMachine 35 · CargoSpot 8 · CargoLimitRationale 8 · DemoScenarioReplay 1 · MockVehicle 4 · VoiceIntentParser 6 · Preset 1 · SpeedArbiter 2 (2026-10-26 밤 재실행, JDK 17.0.21) |
| `:app` 단위테스트 | NO-SOURCE (앱 모듈은 UI/배선만; 로직은 전부 `:core`) |
| APK | `app/build/outputs/apk/mock/debug/app-mock-debug.apk` (24.8 MB) · `app/build/outputs/apk/pleos/debug/app-pleos-debug.apk` (49.2 MB) |
| 매니페스트 검증 (`aapt2 dump badging`) | package `ai.vipseat.cargoconcierge`, minSdk 28 / targetSdk 34, `uses-feature android.hardware.type.automotive`, `uses-library android.car (optional)`, 액티비티 `distractionOptimized=true`, pleos 플레이버에 `pleos.car.permission.*` 15개 포함 |
| 컴파일러가 잡아준 SDK 사실 | ① `ErrorCode`/`PleosPermission`은 `vehicle-data`/`fused-location-data`에 있고 Pleos POM이 runtime 스코프로만 선언 → 명시 의존성 추가 ② `GearSelectionListener.onSelected(VehicleGear?)`, `DrivingModeStateListener.onDrivingModeStateUpdated(DrivingModeType?)`, `getCurrent*` 콜백 값이 **nullable** ③ TTS/STT가 `ai.pleos.playground.security:universal-access:0.6.0`(하위 그룹)을 끌어옴 → 저장소 필터를 정규식으로 ④ `registerGearSelection`이 **deprecated — "use the Google GEAR_SELECTION property"** → Pleos가 표준 CarPropertyManager 경로를 권장한다는 신호라 pleos 플레이버에도 AOSP 어댑터를 병합(속도/기어), `SpeedArbiter`로 소스 간 속도 충돌 방지 |
| 실행 검증 | **공식 `Pleos_Connect_v2.1` AVD에서 실행 확인(2026-10-26, §10).** Vehicle 서비스 바인딩·이벤트 수신, 조수석 온도·열선·통풍 제어 적용과 원복, Pleos TTS/STT 초기화, 70초 시나리오 전 구간, 화면 녹화까지 확인. VHAL 셸 주입은 user 빌드라 불가 |

재현: 위 §3 명령 그대로. 첫 빌드는 네트워크 필요(Gradle 8.9 배포본, AGP/Kotlin 플러그인, Pleos AAR). 이 세션의 도구 설치 + 전체 빌드에 약 45분 소요(그중 다운로드 ~3분).

### 접수용 릴리스 빌드 (2026-10-28)
| 항목 | 결과 |
|---|---|
| `./gradlew :core:test assembleRelease` | **BUILD SUCCESSFUL** · `:core:test` 73 tests, 0 failures (JUnit XML 기준, cleanTest 후 재실행) |
| 서명 | `keystore/release.jks`(RSA 2048, 저장소 미포함) · 두 APK 동일 인증서 SHA-256 `6a2a8c48…5964` · `apksigner verify` 통과 |
| APK | `apk/VIPSeat_CargoConcierge_release.apk` 47.7 MB (versionName 1.0.0-pleos · versionCode 2) · `apk/VIPSeat_CargoConcierge_mock_release.apk` 25.3 MB |
| 실행 검증 | `Pleos_Connect_v2.1`에 release APK `adb install -g` → 실행: 홈 화면 표시, 하단 연결 표시 “AOSP CarPropertyManager + Pleos Vehicle SDK 2.3.2 + demo bridge / Pleos NaviHelper 3.0.5”, 데모 브리지 음성 등록(`voice "케이크 실었어"`) 동작 |

## 8. 디렉터리
```
app/                         ← Gradle 루트 (이 README)
├── settings.gradle.kts · build.gradle.kts · gradle.properties · gradle/libs.versions.toml · gradlew
├── core/                    순수 Kotlin: model · physics · engine · port · mock · voice  (+ src/test JUnit)
├── app/                     Android 앱: Compose UI, ViewModel, 플랫폼 어댑터, AOSP 어댑터, 데모 브리지, flavors mock|pleos
├── vehicle-pleos/           Pleos SDK 어댑터 라이브러리 (Vehicle · NaviHelper · FusedLocation · TTS · STT)
├── demo/                    demo_broadcast.sh · scenario_3min.sh · scenario_video.sh · record_emulator.sh · make_demo_video.py · compose_demo.py + demo_script.py · render_cards.py · capture_screens.sh · inject_vhal.sh · PT_SCRIPT.md
├── apk/                     접수용 release APK 2종 + SHA256SUMS.txt + README (apk/README.md)
├── docs/                    기획서(02 심사자료 본문) · 수치 근거표 · 실제 실행 화면 PNG
├── keystore/ (gitignored)   release.jks · keystore.properties — 저장소에 포함하지 않음
├── LICENSE (MIT) · .gitignore · local.properties(gitignored)
```

MIT License — see `LICENSE`.

## 9. v4 정책 (2026-10-26, 6인 심사 패널 지적 반영)

| 지적 | 반영 (전부 `CargoStateMachine`에서 단위 테스트로 고정) |
|---|---|
| 시동 OFF 적재가 가장 흔한데 도어 트리거가 못 받음 → 시동 직후 매번 팝업 | 자동 제안은 ① 도어 ② 부팅 전 도어 이력 ③ 픽업 매장 지오펜스에서만. ②③은 호스트가 `Action.ExternalOffer(reason)`로 올림(데모: `demo_broadcast.sh offer "..."`). 그 외에는 수동 칩·음성만 |
| 동승자 하차·착좌 오탐, 착좌≠사람 | 조수석 착좌 중 또는 착좌 후 30 s 이내(`passengerExitSuppressMs`)에는 제안 안 함(트렁크 제외). 착좌가 들어오면 열린 시트는 닫고, 조수석을 건드리는 프리셋은 즉시 원복·종료(사람 우선) |
| 알림 피로 | 같은 트리거 3회 연속 무응답 → 음소거(`mutedTriggers`, `unmuteTriggers()`), 프리셋 선택 시 해제. 수동 열기는 집계하지 않음. P 이탈 시 즉시 닫힘 |
| 0.12~0.15 g는 교차로 선회마다 울림, 긴급 상황 억제 없음 | 효과음은 WARN/OVER *진입* 순간 1회, 음성은 초과 시에만 주행당 3회(`maxVoiceCoachPerTrip`), 0.6 g 이상(`emergencyG`)에서는 음성 억제, 15 km/h 미만 조향 위주 초과(주차)는 기록만 |
| 도착 원복이 "P단에서만 set"과 충돌 | `DestinationArrived`가 D단에서 오면 안내만 하고 `arrivalPending` 유지 → P 전환(또는 정차) 시 원복·리포트 |
| 열선 3단 45분의 열 축적 | 뜨거운 음식 프리셋 기본 2단(3단은 설정). 30분 뒤 1단·45분 OFF 타이머는 호스트(ViewModel) 책임 |
| 조향비·축거 미명시 | `GEstimator(DEFAULT_STEERING_RATIO = 15, DEFAULT_WHEELBASE_M = 2.9)` — 차종별 파라미터로 교체 가능. IMU 융합·방지턱은 미구현(SensorManager 접근 여부 확인 후) |
| 풍량·풍향이 조수석 단독 존이 아닐 때 | `PleosVehicle`이 capability의 존 구성을 읽어 조수석만 덮는 존이 있을 때만 set, 운전석과 공유하는 존이면 `UNCHANGED("운전석과 공유하는 존이라 유지")` — 에뮬레이터 확인 결과 반영 |

## 10. Pleos 에뮬레이터 실행 결과 (2026-10-26, macOS arm64 · Emulator 37.2.12)

| 항목 | 결과 |
|---|---|
| 이미지 입수 | Pleos 문서의 SDK 추가 저장소 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml` → 패키지 `system-images;android-34;pleos_car_emulator;arm64-v8a`(zip 4.35 GB, 해제 18.6 GB 중 실데이터 6.4 GB). 메일 신청 불필요 |
| AVD | 기기 프로필 `Pleos_Connect_v2_0`(2560×1440 @160dpi, Automotive), RAM 8 GB, `disk.dataPartition.size=2G`, `-no-snapshot -gpu host`. 빌드 `RELEASE.release_connect_v2.1.0 test-keys`, 모델 `Car on arm64 emulator` |
| 앱 설치·실행 | `app-pleos-debug.apk` `install -r -g` → 실행 OK. 앱은 우측 1600 px 패널에 표시(좌측은 Pleos 주행 정보 패널) |
| Vehicle SDK 2.3.2 | `ServiceConnected` 후 VENDOR_GEAR_SELECTION·PERF_STEERING_ANGLE·BRAKE_PEDAL_STATUS·DOOR_POS(운전석·조수석·트렁크 포함 6개 영역)·SEAT_OCCUPANCY·HVAC_TEMPERATURE_SET·DRIVING_MODE 이벤트 수신. 기본값: 기어 P, 조향 0°, 브레이크 뗌, 도어 닫힘, **조수석 착좌 ‘있음’**, 공조 22 ℃ |
| AOSP CarPropertyManager | `CAR_SPEED` 런타임 권한 부여 후 speed·gear 구독 OK, steering/door/occupancy/hvac는 privileged라 absent(→ Pleos SDK가 담당) |
| 캐빈 제어(케이크 프리셋) | TEMPERATURE APPLIED · SEAT_HEATER APPLIED · SEAT_VENT APPLIED · FAN/AIRFLOW: 조수석 단독 존 없음(1열 공유) → UNCHANGED(유지). 수정 전에는 `area ID: 0x4 not supported for HVAC_FAN_SPEED/HVAC_FAN_DIRECTION` 예외였음 |
| HVAC 읽기 | `getHvacTemperature(ROW_1_RIGHT)` → `PropertyNotAvailableException(area 0x4, vendor error 0)` — set은 성공. 원복값은 리스너의 마지막 값으로 보관 |
| TTS/STT | 매니페스트에 `pleos.car.permission.TTS_SERVICE`/`STT_SERVICE` 선언 후 `Pleos TTS ready`/`Pleos STT ready`. Android TTS 엔진 없음 |
| 시나리오 | `scenario_3min.sh`(broadcast) 70초 전 구간: 도어 트리거 → 카드(착좌 해제 후 34 s 안정화 필요) → 음성 선택 → 가드 → 효과음/음성 코칭(최대 0.49 G, 음성 3회, 경고음 4회) → 내비 급커브 안내 → 급제동 → P + 운전석 도어 → 도착 안내·리포트·원복. 영상용 `scenario_video.sh`는 앞에 자리 선택(뒷좌석 오른쪽 탑승 중 → 들고 타기, 트렁크) 장면과 읽을 시간을 더하고, 뒤에 “조수석 뒤에 떡볶이 실었어” 에필로그(자리+물건 한 번에, 들고 타기 → 열선·통풍 제외, 뒷좌석 송풍 UNSUPPORTED 표시)를 붙인다 |
| 녹화 | `adb emu screenrecord`(VP9 2560×1440 24 fps, Vorbis — 오디오 트랙은 사실상 무음) → `make_demo_video.py`(빠른 편집) 또는 `compose_demo.py`(제출용) → 1080p H.264. 최종 테이크 `video/emulator/demo_20261027_111152.*`(새 래스터 에셋), 결과 `video/out/VIP조수석_emulator_demo.mp4` 2:51 |
| 조향각 heartbeat | Pleos SDK가 `PERF_STEERING_ANGLE = 0.0` onChangeEvent를 약 3 s마다 변화 없이 재발행 → 병합 시 주입 조향각을 덮어씀. `PleosVehicle`은 직전 값과 같은 조향 이벤트를 버린다(실차에선 연속 값이라 무해) |
| 에뮬레이터 TTS | `[GAAS][SDK]TtsCallback onError(ON_DEVICE_ENGINE_ERROR)` — Pleos TTS 서비스는 붙지만 온디바이스 엔진이 없어 발화 소리가 나지 않음. 앱은 `TTS:` 줄과 Pleos `doSpeak` 호출을 남기므로 영상에서는 그 문장을 후처리 합성(명시). 실차 TTS 동작은 10/28 질문 |
| 미해결 | 착좌 기본값의 의미(실차 OCS), HVAC get 불가의 실차 동작, 풍량·풍향 존 구성, 자동 카드 정책 — 10/28 질문 목록 |

## 11. 비주얼 키트 (2026-10-26)

| 항목 | 내용 |
|---|---|
| 소스 | `design/build_kit.py` 한 파일이 `design/svg/*.svg` 35종을 생성하고 `design/preview/kit.png` 미리보기를 렌더한다(헤드리스 Chrome). 수정은 SVG가 아니라 이 스크립트에서 |
| 픽토그램 | 64그리드 ‘소프트 솔리드’ 5종(케이크·뜨거운 음식·꽃·장보기·커피) — 이모지 대체. 앱에서는 품목색 라디얼 디스크 위에 올린다(`PresetArt`) |
| 라인 아이콘 | 24그리드 2 px 라운드 23종(도어·트렁크·기어·조향·브레이크·시트·온도·내비·마이크·슬라이더·닫기·체크·셰브론·벨·웨이브·원복·차량·시계·깃발·경로·재생·스파클·속도·실드) — 단색, 호스트에서 틴트 |
| 마크·런처 | 조수석 프로파일 + 샴페인 스파클. `ic_mark`(48), 어댑티브 런처 `ic_launcher_foreground/background`(108, 세이프존 66) |
| 일러스트 | `il_hero`(560×320, 시트에 벨트로 고정된 케이크 상자), `il_route`(리포트 경로 스트립) |
| 변환 | `npx svg2vectordrawable -f design/svg -o app/src/main/res/drawable` — 변환기가 `<circle>/<ellipse>`를 버리므로 생성기가 모두 path로 내보낸다. 그라데이션은 런처 2종에만(`aapt:attr`) |
| 서체 | Pretendard Regular/Medium/SemiBold/Bold/ExtraBold(OTF, `res/font/`, SIL OFL 1.1 — `licenses/Pretendard-OFL-1.1.txt`) → `ui/theme/Type.kt` |
| 토큰 | `ui/theme/Theme.kt`: Ink #0B0F14 · Panel #121922 · Mint #4FD1C5 · Champagne #F2D18B · 품목색 Rose/Amber/Lilac/Leaf/Mocha · 헤어라인 6 % |
| 재사용 | 제출용 시안(`submission/mockups/build.py`)과 영상 카드(`demo/render_cards.py`), 자막(`make_demo_video.py`)도 같은 SVG·서체를 쓴다 |
| 광고(컨셉) 영상 v2 | `video/assemble_v2.py`: 기존 생성 클립 → 1080p 그레이드·전환 → 실제 앱 화면 인서트(`submission/mockups/real/`, 리포트는 제목+타일 합성)·키네틱 캡션·3음성(내레이션 InJoon / 차량 SunHi / 운전자 Hyunsu) + 차임 + 절차 음악(리빌 멜로디·음성 더킹). 새 장면은 `video/gen_stills.py`(gpt-image-2, 우리 프레임 참조)로 사진 비트(`video/stills/`)를 만들어 켄 번즈로 삽입 — Sora 2 영상 API는 2026-09-24 종료. 심사 라운드 보고서 `video/review_v2/judge{1,2,3}_*.md` |
| 광고(컨셉) 영상 v3 (10/27 저녁) | **Flow(Veo 3.1 Fast)로 13컷 전면 재생성**(`video/shots_flow_v3.md`, 인물·차량 레퍼런스 이미지 고정, 2테이크씩 → `video/clips_v3/`) → `video/assemble_v3.py`: v2.5 파이프라인 그대로에 컷별 디스플레이 사각형 재측정(`video/tools/fit_display_quad.py`, 티얼 글로우 자동 피팅 · 어두운 패널은 크로마 매트), 번호판 키프레임 블러(`blur_kf`), 간판·중복 인물은 줌/크롭, 인서트 위치·크기 오버라이드. 출력 `video/out/VIP조수석_ad_v3_1080p.mp4`(v3.5, 61.2 s, −14.1 LUFS; 2–7차 재생성, 현대 그랜저 명시, 케이크는 운전석 문 시점·조수석 창밖 시점으로 조수석 위임이 명확, 주행 컷은 글랜스 프레임을 시작 프레임으로 준 프레임→동영상 생성(PW3b_v1); 디스플레이 합성은 `ChromaTracker`/`EdgeTracker`(림 가림·유리 너머 대응)). v2.5(`assemble_v2.py`, 구 클립 `video/clips/`)는 폴백으로 유지 |
| 래스터 아트 (10/27) | 품목 픽토그램 9종과 홈 히어로는 Codex 이미지 생성(`design/ASSET_BRIEF_codex.md` 브리프 → `design/raster/sheet_items.png`, `il_hero.png`)을 `design/import_raster.py`로 슬라이스·정리한 `res/drawable-nodpi/art_item_*.png`, `art_hero.png`(512 px·투명). `ui/Icons.kt`의 `itemArt()`/`HeroIllustration`이 사용하고 벡터 `ic_item_*`·`il_hero`는 폴백·카드용으로 남김 |

## 12. 일반화된 VIP 모델 (2026-10-26 저녁)

VIP는 고정 5종이 아니라 **사용자가 이름 붙인 임의 품목**이다. 보호 *분류* 9종(`CargoKind`: 케이크·디저트 / 뜨거운 음식 / 꽃·식물 / 신선식품 / 음료·국물 / 깨지기 쉬운 물건 / 전자기기·악기 / 반려동물 / 일반 짐)이 한계 G와 캐빈 플랜을 정하고, `CargoPreset.named("도자기", FRAGILE)`처럼 사용자 말이 이름·도착 안내에 그대로 쓰인다.

| 단계 | 동작 | 위치 |
|---|---|---|
| 말로 등록 | `VoiceIntentParser.parse(text, recents)` 3단계: ① 이전에 쓴 VIP 이름 → ② 분류 별칭(“떡볶이”→뜨거운 음식, 이름은 말한 명사) → ③ 적재 동사가 있는 모르는 명사(“수박 실었어”)는 일반 짐 0.20 G로 등록(`isNew`). 조사 처리: 을/를은 항상 제거, 이/가/은/는/도는 어간이 알려진 단어일 때만(“강아지가 탔어”→강아지, “떡볶이 실었어”는 유지) | `core/voice/VoiceIntentParser.kt` |
| 최근 VIP 먼저 | `RecentVipStore`(SharedPreferences JSON, 최대 8, 최근 사용순). 시트 1행 = 말하기 타일 + 최근 VIP(없으면 추천 5종), 2행 = 분류 9종. 홈 칩·설정 목록·파서 1순위에 같은 목록 | `platform/RecentVipStore.kt`, `ui/PresetSheetScreen.kt` |
| 분류 바꾸기 | 정차 중 적재 화면에서 분류 칩을 누르면 같은 이름으로 다른 분류로 가드 재시작, 목록에도 반영 | `CargoViewModel.reclassify` |
| 데모 | `demo_broadcast.sh recent "도자기:FRAGILE,강아지:PET"` 시드, `recent clear`; `scenario_3min.sh`는 T+0에 최근 VIP 3개를 시드해 ‘이전에 쓴 VIP 먼저’를 보여준다 | `demo/` |

## 13. 수치 근거 (2026-10-26 밤)

모든 숫자의 출처는 `../submission/근거_EVIDENCE.md`에 A(법규·공식·실측)/B(문헌·공공지침)/C(물리 유도)/D(팀 가정) 등급으로 정리했다. 분류별 한계 G의 유도식은 `core/physics/CargoPhysics.kt`(넘침 2h/d·과도응답 2배·미끄러짐 μs·전도 (b/2)/h·곡선 v²/R)에 있고 `CargoLimitRationaleTest`(8건)가 현재 값을 그 유도에 고정한다 — 값을 바꾸면 어느 근거가 깨지는지 테스트가 말해 준다. 에뮬레이터 실측: `HVAC_TEMPERATURE_SET` 설정 범위 15.0~30.0 ℃·0.5 ℃ 단위(`dumpsys car_service`).

## 14. VIP 자리 (2026-10-26 밤)

| 항목 | 내용 | 파일 |
|---|---|---|
| 자리 모델 | `CargoSpot` = 조수석 · 뒷좌석 왼쪽/가운데/오른쪽 · 트렁크. 한계 G는 자리와 무관(물건의 물리), 공조 플랜만 자리에 매핑. `DoorId`에 REAR_LEFT/REAR_RIGHT 추가, 착좌는 좌석별(`SeatOccupancy(seat, occupied)`, `VehicleSnapshot.occupancy`) | `core/model/Signals.kt` |
| 제안·억제 | 닫힌 문의 자리로 시트 제안(`CargoSpot.forDoor`); 착좌·하차 30 s 억제는 그 자리에만; 자동 제안은 그 자리에 사람이 앉으면 닫힘 | `core/engine/CargoStateMachine.kt` `offerAutomatically`/`onOccupancy` |
| 들고 타기 | 사람이 타 있는 자리도 선택 가능. 선택 당시 착좌면 `Guarding.heldByPassenger`=true → 호스트가 열선·통풍을 플랜에서 빼고 적용, 이후 착좌 신호로 종료하지 않음. 비어 있던 자리에 사람이 앉으면 기존대로 사람 우선 종료 | `startGuard`, `CargoViewModel.handle(ApplyCabin)` |
| 자리 이동 | `Action.SelectSpot`: Idle → 기본 자리 기억, Offering → 시트 전환, Guarding(정차) → 원복 후 새 자리에 적용(뷰모델 `Mutex`로 순서 보장), 주행 중 무시 | 상태머신 `onSelectSpot` |
| 어댑터 매핑 | 조수석: 기존 / 뒷좌석 좌·우: 좌석 열선·통풍은 해당 SeatArea, 온도는 1열 좌석이 없는 후석 존만(없으면 `UNSUPPORTED "뒷좌석 단독 존 없음"`), 송풍·풍향은 미지원 / 가운데: 열선 없음 / 트렁크: 전부 `UNSUPPORTED "트렁크는 공조 영역이 아니에요"`. AOSP는 좌석 비트, Pleos는 `SeatValue.ROW_2_*`(javap 확인) | `MockVehicle`, `AospCarVehicle`, `PleosVehicle` |
| 음성 | “트렁크에 수박 실었어”, “조수석 뒤에 강아지 탔어”, “뒷좌석 가운데 케이크 실었어” → 자리 + 물건(`VoiceIntentParser.extractSpot`; 맨 ‘뒷자리’는 오른쪽으로 추정) | `core/voice/VoiceIntentParser.kt` |
| UI | 홈 왼쪽은 그대로, 오른쪽은 차량 내부 맵(`CabinMapPanel`: LIVE 스트립 + 운전석/조수석/뒷좌석 3칸/트렁크, 착좌 초록, 선택 민트, 둘 다면 “들고 타기”), 이벤트 로그는 하단 가로 스트립(`EventLogStrip`). 시트 헤더에 자리 칩, 적재 화면에 자리·들고 타기 칩, 도착 음성·화면은 자리 이름 | `ui/CabinMap.kt`, `ui/HomeScreen.kt`, `PresetSheetScreen.kt`, `LoadedScreen.kt` |
| 에뮬레이터 착좌 기본값 | Pleos 에뮬레이터는 조수석뿐 아니라 뒷좌석 좌/중/우도 착좌 ‘있음’을 초기값으로 보고한다(occupancy-rear_* 리스너 ok). 데모·캡처 스크립트는 시작 시 조수석·뒷좌석 좌/중을 `occupied false`로 주입하고 30 s 억제가 끝난 뒤 도어 사이클을 보낸다. 실차 OCS 의미는 10/28 질문 | `demo/scenario_3min.sh`, `capture_screens.sh`, `record_emulator.sh` |
| 데모 | `demo_broadcast.sh occupied true REAR_RIGHT`, `spot TRUNK`, `door REAR_LEFT open`; 3분 시나리오 T+0에 뒷좌석 오른쪽 탑승 표시, T+0.5에 자리 선택 장면. 실제 화면 캡처는 `demo/capture_screens.sh <apk> ../submission/mockups/real`(에뮬레이터 기본 착좌 해제 후 30 s 억제가 끝나길 기다렸다가 도어 사이클) | `demo/` |
| 테스트 | `CargoSpotTest` 8건(문별 제안, 좌석별 억제, 들고 타기, 사람 우선, 자리 이동, 도착 문구, 음성 자리 구문, 목 어댑터 매핑) → 총 73건 | `core/src/test` |

## 15. 3D 주행 시뮬레이션 (2026-10-29, v2.3 소스·패킹본 검수 완료)

"사람 기준 0.40 G vs 케이크 기준 0.15 G"를 같은 야간 코스(R30·R60·R120 커브, 1.4 km)를 좌우 분할 화면에서 동시에 달려 비교하는 브라우저 3D(Three.js 0.160, 빌드 없음). 앱의 추정식(자전거 모델, 조향비 15:1, 축거 2.9 m, EMA 0.5)과 배지·코칭 규칙(80 % NEAR 진입 시 효과음 1회, 초과 시 음성 코칭 주행당 3회, 0.6 G 긴급·15 km/h 미만 선회 억제, R30 100 m 전 사전 안내, P 정차 시 챙김 안내)을 그대로 옮겼다. 케이크 손상은 "한계 초과분 누적"의 단순 모델이며 화면에 그렇게 표기한다. 물리는 고정 스텝(1/60 s)이다.

- 실행: `cd docs/sim && python3 -m http.server 8787` → `http://127.0.0.1:8787/` (Three.js·Draco 디코더를 CDN에서 받으므로 인터넷 필요, Chrome 권장. 에셋 패키지 약 85 MB, 처음에 로딩 화면).
- 조작: **비교 시작 = 좌우 화면 분할 동시 주행**(왼쪽 A 사람 기준 · 오른쪽 B 케이크 기준이 같은 순간 출발, 반쪽마다 계기·배지·말풍선, 가운데 거리 차 칩, 먼저 도착한 쪽은 도착 띠, 둘 다 서면 비교 카드) · 카메라 1 체이스 / 2 캐빈(조수석 품목·코칭 화면) / 3 지도(두 차 표식·G 벡터) · C 직접 운전(방향키, Space 급제동) · R 리셋(비교 중이면 다시 비교) · H 조작 패널 숨김(녹화용) · F FPS · VIP 분류 9종 선택(한계 G·권장 속도·조수석 위 실제 품목 연동).
- 소스 회귀 검증 결과(케이크·디저트 0.15 G, 기존 물리 결과와 일치): A 사람 기준 — 최대 0.417 G, 손상 100 %(R60 커브 안에서), 코칭 3/3, 1:35.5 · B 케이크 기준 — 최대 0.117 G, 손상 0 %, NEAR 0회, 2:13.5(+38.1 s) · 급커브 R30 통과 속도 39.5 vs 20.8 km/h. v2.3 소스·패킹본 모두 전체 코스 36/36 검사 통과·오류 0이며, A/B의 7개 결과 항목은 v2.2 대비 차이가 0이다.
- **v2.3 2021 IONIQ 5:** 현대자동차가 공개한 2021년형 실제 사진으로 외형·실내·20인치 휠을 다시 제작했다. 공식 전방 ¾ 사진의 배경 제거 → TRELLIS.2 1536 형상 기준 생성 → 공식 제원·사진에 맞춘 매끈한 규칙 표면 재구성으로 `ioniq5_2021`을 제작했다. TRELLIS 원본의 기여는 제한된 저주파 후드·지붕 곡률이며, 원본 메시 삼각형·UV 텍스처를 그대로 유지하지 않는다. 재구성 파트에 PBR 재질을 적용하며 실제 기여 범위는 최종 메타에 기록했다. `ioniq5_cabin`은 흰 베젤의 동일 크기 듀얼 화면·곡면 대시보드와 HVAC 패널·열린 바닥·별도 Universal Island·네 점 핸들을, `ioniq5_wheel20`은 255/45 R20 타이어와 넓은 절삭 가공 금속면이 있는 양쪽 다이아몬드 격자 휠을 포함한다. 공식 CAD가 아니며, 세부 표면·실내 치수는 사진을 해석한 제작값이다. 차체 r9·실내 r3·휠 r2를 통합했으며, 실내 문턱·앞쪽 발밑 측면·창 아래 트림을 연결해 경계 틈을 닫았다. 모서리·지붕 연결부, 타원형 미러, 휠 격자의 근사 표현과 사진보다 맑은 유리가 남는다.
- 외형 제작 기준: 전장 4.635 × 미러 제외 전폭 1.890 × 전고 1.605 m·시각 축거 3.000 m([현대자동차 2021 공식 제원](https://www.hyundai.com/kr/ko/brand/brandstory/model/ioniq-history/2021-ioniq5), 근거 A). 20인치 앞/뒤 윤거 1.628/1.637 m는 [2021년 6월 기술자료](https://www.hyundai.news/newsroom/dam/eu/models/20210630_ioniq_5_press_kit/hyundai-ioniq-5-technical-data-0621.pdf), 타이어 공칭 반지름 0.36875 m는 규격 계산값(근거 C)이다. 미러 포함 폭은 별도 메시 측정값이며, 관성 추정기의 기존 축거 2.9 m는 유지한다(`docs/근거_EVIDENCE.md` §3). 결과는 실차 시험값이 아니다.
- 환경은 Poly Haven CC0 모델·PBR 텍스처·야간 HDRI, 조수석 VIP는 기존 생성 에셋과 대체물 2종을 유지한다. 제조사 참조 사진의 저작권은 현대자동차에 있으며 CC0가 아니다. 에셋 목록·대체물·검수 기록은 `docs/sim/README.md`·`docs/sim/CHANGES.md`, 환경 출처는 `docs/sim/assets/LICENSES_polyhaven.md`에 기록한다.
- **검수 상태:** 소스에서 접지·시각 축거·조향/회전·제동등·듀얼 화면·조수석 케이크·비교 카드·단독 복귀와 전체 코스 물리 회귀를 확인했다. 최종 압축본의 중립 조명 7뷰·8개 검사 통과와 시뮬레이터 5뷰·오류 0을 확인했다. 패킹본 전체 코스 36/36 통과·오류 0, 체이스·캐빈·4× 진행·화면 갱신과 패킹본 진단 5뷰 오류 0, 소스 JS/에셋 17개 일치와 민감정보 패턴 검사를 확인했다. v2.2의 생성 참조 기반 Astra 점수를 새 모델의 승인 근거로 사용하지 않는다. v1.1은 `docs/sim_v1/`에 보관한다.
