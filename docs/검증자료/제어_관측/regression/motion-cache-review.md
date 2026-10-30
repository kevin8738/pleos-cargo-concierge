# 차속 캐시 분리 독립 코드 검토

이 문서는 별도 관측 APK와 해당 실행 당시 소스를 대조한 기록입니다. 아래 “현재”·“후보”·“수정 전후”는 그 검수 시점의 범위이며 제출 APK의 추가 검증 결과가 아닙니다.


판정: **APPROVE FOR INTEGRATION — 완료본에서 must-fix 발견 없음.** root의 전체 빌드 및 새 후보 APK의 실제 C3 재검증 진행 가능. 검토자는 코드·AVD·빌드를 실행/변경하지 않았으며 이 문서만 작성했다.

## 확인한 경계

- `MergedVehicle.kt:62`의 `priority > 0 && src !== controlTarget`으로 mock과 controlTarget 자신의 위치 feed가 외부 command cache에 들어가지 않는다. revoke 직전에 이미 downstream에 큐잉된 own GPS Speed도 이 경로로 외부 캐시를 갱신할 수 없다.
- `PleosVehicle.kt:121–137`의 `externalRealSpeed`는 다른 실제 포트 전용이다. 위치 lease 해제·GPS 오류가 이 캐시를 지우지 않는다. own 위치는 기존 `locationSpeed.freshSpeedMps`에서만 읽으며 접근권한/lease 및 freshness 재검사를 거친다. 따라서 신선한 AOSP 차속 보존과 철회된 GPS 복사본 제거를 별도로 처리한다.
- 선택된 외부 source의 `SourceStatus(false)`는 전체 결합 연결이 true여도 해당 캐시를 비우고 arbiter를 초기화한다. 그 source는 connected true 전까지 late Speed가 거부된다. 무관한 source의 false는 선택된 정상 캐시를 지우지 않는다.
- `disconnect()`는 observer/arbiter와 동일한 잠금에서 `realMotionActive=false` 및 캐시 무효화를 먼저 수행한다. teardown 중 또는 종료 뒤 속도 관측이 캐시를 다시 채우지 못한다. 명시적 새 `connect()`는 source의 connect 호출 전에 gate를 열어 AOSP의 connected status보다 먼저 나오는 실제 초기 getter를 수용한다.
- 실제 runtime은 기존 connection mutex로 연결 수명주기를 직렬화하고 이전 collector를 취소·join한 뒤 disconnect한다. replay=0 source와 결합해 이전 collector의 queued 항목을 새 연결의 초기값으로 재사용하지 않는 기존 계약을 유지한다. 이번 수정으로 SDK callback 전체에 새로운 timestamp/generation 증명을 추가한 것은 아니다.
- 외부 차속 hold 및 command freshness는 **2,000 ms**, 정차 허용 경계와 P·비착좌·공조 동기화·연결 세대 검사는 유지됐다. 핵심 G/오디오 정책 변경이나 새 의존성은 없다. constructor의 clock/provenance 인자는 테스트용 주입 지점이며 기본 동작은 기존 값이다.

## 검증 근거와 한계

`motion-routing-red-complete.log`는 컴파일 이후 9개 중 8개 assertion 실패를 기록한다. `motion-routing-green.log`의 BUILD SUCCESSFUL 및 `app/app/build/test-results/testMockDebugUnitTest/TEST-ai.vipseat.cargoconcierge.vehicle.RealMotionRoutingTest.xml`의 **9 tests / 0 failures / 0 errors / 0 skipped**를 직접 대조했다. own 위치 loopback·우선순위·만료 시각, 선택/무관 source 종료, mock의 실제 차속 대체 금지, teardown/late sample, 초기 getter를 포함한다.

이들은 fake port를 통한 routing 회귀다. 실제 Pleos 서비스에서 위치 lease가 바뀌는 동안의 공조 명령 성공과 GPS callback 경합을 측정한 시험은 아니다. 제어 실패 관측 APK `avd-01` C3 실패를 보존하고, root가 새 APK에서 C3 및 필요한 통합 검증을 별도 완료해야 한다.

## 검토한 파일 SHA-256

| 파일 (`app/` 저장소 기준) | SHA-256 |
|---|---|
| `app/src/main/kotlin/ai/vipseat/cargoconcierge/vehicle/MergedVehicle.kt` | `08d84595d9352877382e9e7e57ba3e97e7ccbd39c74e0231e0f0a02f3b2ef891` |
| `core/src/main/kotlin/ai/vipseat/cargoconcierge/core/port/RealMotionObserver.kt` | `b25aa1ef5f7d7755ba92f6fa2a8fbb18c750957e7a8e014cfc5070059c0c78cf` |
| `vehicle-pleos/src/main/kotlin/ai/vipseat/cargoconcierge/pleos/PleosVehicle.kt` | `dcf42bfba4e19ab28dcb1b16ba45aa1d0a019d339a1a03cf1f791bf465a24c80` |
| `app/src/test/kotlin/ai/vipseat/cargoconcierge/vehicle/RealMotionRoutingTest.kt` | `5708991c7050977dcab2634c7c598cf4425df557bf7cfa2fba55409a5d3cacc0` |
