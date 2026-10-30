# 단일 native/VIP 관측 — 최초 결과와 원인 분리

**최초 전체 판정은 false, 실행 오류 0, cleanup=true다. 재실행·기준 변경·원본 수정은 없다.** 단일 180초 `streamAudio`에서 15,545개 응답과 47.010729초 분량의 실제 PCM을 수신했다. 원 수집 wall time은 179.977896초다. sparse PCM/WAV를 180초 연속 녹음으로 표현하지 않는다.

- [원 판정](../integration-01/integration-result.json), [원 oracle](../integration-01/oracle-result.json), [원 수집 metadata](../integration-01/capture/metadata.json), [원 정리](../integration-01/cleanup.json), [원 118파일 해시](../integration-01/artifact-sha256.json)
- [별도 분석 JSON](observation-summary.json), [AVD 반환·원값 확인](handoff.json)

## 한 회차에서 관측한 사실

같은 별도 관측 APK APK/PID 22692와 COFFEE 세션에서 실제 순정 ROUTE_DRIVE, 합성 정상/WARN/OVER/회복, 실제 FREE_DRIVE, Console P0, 합성 ARRIVED가 순서대로 저장됐다. 주행 동안 native 전면/VIP `STOPPED/visible=false` 샘플은 38개다. 비표시 관측 사이의 연속 상태나 DEMO 없는 생존을 증명한 것은 아니다.

주행 admission은 효과음 1회·음성 2회다. VIP START→DONE은 주행 2쌍과 도착 1쌍이며, 각 START 앞 STOP을 보존했다. 도착 요청은 실제 순정 목적지 도착이 아니라 명시적 합성 입력이다. 실제 P0 snapshot·단일 ARRIVED trace·보고서 자체는 존재한다. 보고서는 `maxG=0.12354462329191918`, `gSamples=462`, `freshLongitudinalSamples=154`, SYNTHETIC/INITIAL_BASELINE, `canCompare=false`다. COFFEE 캐빈 적용은 0개이므로 공조 적용/복원 효과의 근거가 아니다.

하네스의 `sameSession`, `nativeRouteCallbacks`, 비전면 샘플·tick·VIP lifecycle·수집 창·cleanup 항목은 true다. 그러나 아래 시계 검사가 false이므로 보정 시간창에 의존하는 true를 **엄격한 공통 시간축 검증 완료**로 확대하지 않는다. 원 `parkedArrivalAndReport=false`는 actual P0가 없어서가 아니라 전체 oracle 성공을 요구하는 결합 조건에 의해 false다. 이를 고쳐 쓰지 않는다.

## false 원인

| 원 조건 | 저장된 근거 | 해석 경계 |
|---|---|---|
| motionOracle=false | 첫 불일치 guard row480. 직전 `gSamples=174`, 다음 brake 행은 176이며 사이 steering MotionTrace가 없다. guard raw는 speed261/brake261/steering260. 302개 후속 counter mismatch + 도착 counter mismatch 1개 | 303개의 서로 다른 G 계산 실패가 아니다. 인접한 steering DemoBridge/CargoVM 로그는 있지만 누락 원인은 확정하지 못했다. 한 행을 만들거나 count를 보정하지 않는다 |
| clockEndpointsConsistent=false | 시작 offset 54.962120±10.861500ms, 종료 22.562706±12.284000ms. 두 구간 사이 9.253914ms 간극 | 두 측정을 만족하는 공통 상수 offset이 없다. 실제 시계 변화 원인을 확정하거나 임의 선형 보간으로 원 결과를 PASS로 바꾸지 않는다 |
| mixedPcmSignalInEachResponse=false | 도착 전 peak1449/RMS146.3273, 도착 응답 peak1480/RMS245.2670 | 응답 RMS 비율 약1.676배로 사전 3배 기준 미충족. native 종료 뒤 도착 전 신호의 화자/원천은 확인되지 않았다 |

저장된 모든 numeric estimate 대조에서 lateral/longitudinal/total G 또는 speed 산술 mismatch는 0건이다. 관측 raw로 계산한 max `0.12354462329191915`는 보고서와 허용 오차 내 일치한다. actual received speed dt는 0.401–0.603초다. 다만 한 motion trace가 부족하므로 완전한 raw→count→report 증거라고 부르지 않는다.

원 보정 모델로 계산한 혼합 PCM 창은 다음과 같다. 표본은 실제 수신했지만 시계 불일치 한계가 적용된다.

| 창 | 사전 peak / RMS | 응답 peak / RMS | 원 진폭 대조 |
|---|---|---|---|
| WARN | 2 / 1.4508 | 152 / 12.7095 | 충족 |
| OVER 1 | 2 / 1.4770 | 1344 / 195.0512 | 충족 |
| OVER 2 | 2 / 0.1248 | 1345 / 196.4330 | 충족 |
| 도착 | 1449 / 146.3273 | 1480 / 245.2670 | 미충족 |

도착 START→DONE과 응답·후행 표본은 존재하며 tail 수신 PCM은 3.007초다. 그러나 도착 성분의 독립 진폭 대조와 native 음성 귀속·실제 동시 음향 경합·focus 우선순위는 미검증이다. 순정 route/TBT callback만으로 native 발화를 증명하지 않는다. 실차·사용자·적재물·물리 G 정확도 근거도 추가되지 않았다.

## 정리와 반환

원 TTS/chime/demo-curve는 모두 true로 복원됐고, MotionTrace/GuardRuntime 로그 속성은 원래 빈 값으로 복원됐다. 같은 APK/PID, Idle/P0, HVAC/seat/body/controls 원본 전체 일치, 서비스0·알림0, 본인 browser 종료·8088 forward 없음·임시 token0을 확인했다. 별도 recovery는 필요하지 않았다. 이후 AVD 소유권을 root에 반환했고 이 분석은 기존 파일만 읽었다.

원 manifest의 118개 해시는 모두 일치한다. 이 보충 폴더는 원본 밖에 새로 만든 설명이며 원 `integration-01`의 결과·파일·해시를 수정하지 않는다.
