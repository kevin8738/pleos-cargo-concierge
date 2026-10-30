# 별도 관측 APK FGS 실패·네이티브 내비 독립 검수

이 문서는 별도 관측 APK와 해당 실행 당시 소스를 대조한 기록입니다. 아래 “현재”·“후보”·“수정 전후”는 그 검수 시점의 범위이며 제출 APK의 추가 검증 결과가 아닙니다.


판정: **PASS_WITH_SCOPE_LIMITATIONS**. `fgs-failure-03` 50/50 검사, `runtime-avd-03` 26/26 검사 통과. 원자료·필터 로그 JSONL·dump·XML·입력 장부를 오프라인으로 재파싱했다. 저장 APK를 다시 계산한 SHA와 양쪽 설치 기록이 별도 관측 APK `8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c`에 일치한다. 별도의 기존 artifact manifest가 없는 두 묶음은 검수 시점 입력 파일 해시를 결과 JSON에 기록했다. 이는 기존 manifest와 대조했다는 뜻이 아니다.

## FGS

Android 14 / user 10 / package UID 1010175의 `START_FOREGROUND` package app-op을 실제 ignore와 deny로 바꾼 읽기값이 있다. ignore에서는 시스템의 `Service.startForeground() not allowed due to app op` 행과 ordinary start downgrade가 기록되고, 앱은 실제 승격 확인 실패 후 `IllegalStateException` 경로로 닫았다. deny는 43.927ms 안에 요청 실패가 기록된다. 이 기록에는 deny 예외 클래스/stacktrace가 없어 정확한 예외 종류까지 실측했다고 표현하지 않는다.

각 실패 구간의 motion trace는 17개이고, ready·GUARDING·캐빈 작업 기록은 0이다. UI에는 재시도 안내와 Idle 선택 항목이 있다. 실패 후 dump는 서비스·활성 알림 0이며, package mode를 원래 `allow`로 복원한 뒤 각각 한 번 정상 ready와 `0x40000008` foreground service를 확인했다. 회복 End 후에도 서비스·알림 0이다.

알림 첫 관측 시각과 승격은 구분한다. ignore 회복의 첫 foreground dump는 시작 후 약 0.256초인데, 첫 활성 알림은 약 11.357초 뒤 관측된다. 그 사이 foreground service가 계속 존재했다. 이것은 알림 posting의 샘플 관측 지연이며 11초 뒤 FGS로 승격했다는 뜻이 아니다. deny 회복도 처음부터 FGS이며 이후 알림이 관측된다.

최종 package mode `allow`, UID override `No operations / Default mode: allow`가 원본과 같고, 차량 읽기값·음향 설정·최종 Idle을 확인했다. API28–33 fallback은 미실측이다. 필터 로그의 쓰기 0과 읽기값 보존은 차량 버스 전체 무쓰기 측정이 아니다. 속성 복원은 하네스의 조회·검사 결과이며 raw after 값은 별도 보존되지 않았다.

## 네이티브 내비

직접 관측한 VIP 상태는 STOPPED / visible=false, PID 26822이다. ready부터 종료까지 request 29 / startedMs 9813615를 유지한다. 모든 active dump는 같은 PID·unfrozen·foreground type `0x40000008`·활성 알림 1이다. End 후 서비스와 알림은 약 0.588초의 다음 snapshot에서 0으로 확인된다.

| 구간 | 길이 | ROUTE_DRIVE | TBT | FREE_DRIVE |
|---|---:|---:|---:|---:|
| 경로 요청→VIP 복귀 시작 | 37.790876초 | 1 | 12 | 1 |
| 직접 숨김 관측→경로 종료 요청 | 20.297990초 | 0 | 6 | 0 |
| 직접 숨김 관측→VIP 복귀 시작 | 25.560683초 | 0 | 9 | 1 |

숨김→경로 종료 요청 구간은 하네스의 clock 오차 허용 경계로 tick 21개, 모든 경계를 오차만큼 안쪽으로 좁히면 **20개**이다. 첫 tick 한 개가 시작 경계의 clock 불확실성 안에 있다. 둘 다 최대 간격 1,007ms이고, 같은 session·visible=false·guarding=true를 유지한다. 이 구간의 DemoBridge 수신은 0이다. 실제 숨김 구간에 ROUTE_DRIVE가 왔다고 합쳐 표현하지 않아야 한다.

시계 보정은 device−host 299.806636ms, 오차(왕복 절반) 17.2715ms이며 원본 세 시각으로 재계산했다. host 수신 시각과 보정 device 시각을 함께 제한했다. 단일 보정이므로 장기 drift 상한은 없다. 정지된 공식 AVD의 공개 목적지·실시간 SDK callback을 검증했으며 실제 주행 경로 진행, 실차 센서 정확도, 재생, 물리 온도 변화는 검증하지 않았다.

검수기 초안의 실패 UI 검사는 안내 첫 문장을 노드 전체 text와 비교했다. 실제 노드에는 재시도 문장이 이어져 있으므로 첫 문장 포함 여부로 수정하고 Idle 선택 항목을 별도로 확인했다. 이는 검수기 문자열 탐색 수정이며 원본 시험 결과를 변경하지 않았다.

재현: `python3 .omx/qa/submission-v22/final-verification/verify_fgs_native.py`. 상세 판정과 입력 해시는 `fgs-verification.json`, `native-verification.json`에 있다. 이전 실패 기록은 수정하지 않았다.
