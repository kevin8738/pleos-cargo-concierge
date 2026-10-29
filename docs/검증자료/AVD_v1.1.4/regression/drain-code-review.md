# 공조 종료 대기 ANR 최소 수정 독립 검토

판정: **APPROVE FOR INTEGRATION — 완료본에서 must-fix 없음.** 새 APK 통합 빌드 및 arrival/End 재시험 진행 가능. 검토자는 ADB·AVD·빌드·코드 변경을 수행하지 않았다.

## 실제 실패와 원인

`known-boundary-diagnosis-01/dropbox-own-anr-0.txt`에는 PID12526, Frozen=false, Foreground=Yes, DEMO broadcast ANR이 있다. main Runnable의 `JobSupport.join → CargoSession.finishAfterCabinWork:265` 스택은 MockNavi.arrived/receiver 호출까지 연결된다. 완료된 job의 join은 즉시 반환하지만 set 제거는 같은 Main dispatcher의 `invokeOnCompletion { scope.launch { … } }`에 대기한다. 기존 while은 set이 비기 전에 계속 돌아 제거 callback과 timeout 실행을 막을 수 있다.

runtime05의 이 ANR을 실제 앱 실패로 보존한다. `avd-02` 공조 control PASS는 당시 측정한 설정 적용·복원 사례의 제한된 결과이며 runtime05 전체 안정성 통과를 의미하지 않는다.

## 수정의 안전 경계

- `CabinWorkDrain.kt`는 현재 set의 batch를 snapshot하고 **모든 join이 반환한 뒤** 그 batch만 직접 제거한다. 완료 콜백이 아직 큐에 있어도 반복당 제거가 진행되므로 같은 완료 job을 무한히 join하지 않는다. 나중에 실행되는 callback의 중복 제거는 set에서 안전한 no-op이다.
- 첫 batch를 기다리는 동안 추가된 job은 removeAll(batch)에 포함되지 않아 다음 반복에서 기다린다. set 전체 clear나 완료 전 제거가 없다. 별도 실행 job을 fire-and-forget으로 바꾸지 않는다.
- helper는 cancellation/timeout을 잡아 성공으로 바꾸지 않는다. 어떤 join이 완료되기 전에 취소되면 해당 batch를 제거하는 줄까지 진행하지 않는다. 미완료 job은 caller의 기존 cancel 및 pending 표시에 남는다.
- `CargoSession`의 15,000 ms timeout, cabinGate 무효화, job cancel, 복원 미확인 persistence/UI, `starts.finished(id)` 검사, location lease 해제와 서비스 stop 순서는 그대로다. 정상 drain에서는 등록된 공조 coroutine 완료 전에 service stop에 도달하지 않는다. timeout에서는 기존처럼 불확실성을 표시하고 취소를 요청하며, 이미 전달된 SDK 명령의 물리적 철회를 보장하지 않는다.
- set 변경은 기존 session의 Main dispatcher confinement를 유지한다. 새 mutex·dispatcher·의존성·공조/G/음향 정책 변경은 없다. 테스트를 위해 추출한 내부 helper와 호출 1곳으로 범위를 한정했다.

## 재현과 회귀 근거

`cabin-drain-regression/red.xml`은 실제 StandardTestDispatcher에 완료 제거 callback을 큐잉한 상태에서 기존 루프를 실행해 1개 테스트가 실패한 근거다. 테스트 전용 bounded spin detector로 worker를 무한 정지시키지 않고 실패를 드러냈다. 성공 모형이나 sleep만으로 대체한 테스트가 아니다.

완료본 JUnit XML과 `result.json`, `green.log`를 대조했다: **CabinWorkDrain 3 + GuardStartGate 9 = 12 tests, failures/errors/skipped 모두 0.** 3개 drain 회귀는 queued completion 제거, 대기 중 추가된 job의 완료 보장, 15초 timeout 뒤 미완료 job 보존이다. Android Main/실제 SDK/AVD 재실행 증거는 아직 없으며 root의 새 후보 검증에서 별도로 확보해야 한다.

## 검토 소스 SHA-256 (`app/` 저장소 기준)

| 파일 | SHA-256 |
|---|---|
| `app/src/main/kotlin/ai/vipseat/cargoconcierge/runtime/CabinWorkDrain.kt` | `235100cd7904931ad5b813f1a3cd62c2a0ac99f6f4c7b03818df8618af3befd0` |
| `app/src/main/kotlin/ai/vipseat/cargoconcierge/runtime/CargoSession.kt` | `3b8440364b8d9c14afa7196ee2cd5e1e51fcb5f4e89bb50a074009bff88faff1` |
| `app/src/test/kotlin/ai/vipseat/cargoconcierge/runtime/CabinWorkDrainTest.kt` | `6fc3fb16fb747d88f26b0109630b96100381b40e1fbcba8d4b5320ccc2c871b5` |
