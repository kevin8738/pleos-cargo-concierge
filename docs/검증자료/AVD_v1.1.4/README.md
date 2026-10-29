# 공식 AVD v1.1.4 검증 원자료

**현재 후보는 runtime-06입니다. 빌드·단위 시험, 정상 범위/경고 경계·음향 대조·FGS 실패/복구·순정 내비 비전면 수신·강제 종료/쉘 작업 제거를 확인했으며 위치 설정·재연결은 관측 범위를 구분했습니다. 제어 회귀 10조건·연속 동선 9조건도 통과했습니다.** runtime-04 제어 실패·runtime-05 도착 처리 ANR과 개별 통과 이력을 분리해 보존합니다. 2026-10-30 구현 검증 기준입니다.

| 대상 | 값 |
|---|---|
| 후보 | runtime-06 / 1.1.4-pleos / versionCode 7 / 47,871,331바이트 |
| SHA-256 | `8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c` |
| AVD | Pleos Connect v2.1·Android 14/API 34·crp_avd_arm64 |
| 차량 제어 조합 | Vehicle SDK 2.3.2·Service 2.3.22/code 74 |
| 빌드 | core 158 + app 24 = 182시험, 실패·오류·건너뜀 0; release·서명·Mock 컴파일 PASS; lint 오류 0·경고 33 |
| 캐시 회귀 | 앱 24건에 포함된 9건. 수정 전 assertion 실패 8건 → 수정 후 9건 통과. 독립 코드 검토 필수 수정 0건 |
| 종료 대기 회귀 | 앱 24건에 포함된 새 drain 3건·기존 시작 게이트 9건 통과. 수정 전 재현 1건 실패. 독립 코드 검토 필수 수정 0건 |

모든 현재 실행 자료는 설치 APK의 **위 전체 해시**와 연결합니다. 시험 수·AVD 조건·trace 표본·콜백을 합산하지 않습니다. 아래 상대 경로는 제출용 공개 자료 배치 기준입니다. 공개 사본·DOCX·패키지의 생성과 최종 해시 검수는 실행 시험 결과와 별도입니다.

## 현재 후보의 실행별 색인

| 주장·시험 | 상태 | 원자료 배치 | 범위 |
|---|---|---|---|
| 빌드·서명·182시험 | **확인** | `build-summary.json` | 소프트웨어 빌드·단위 시험. 실제 차량 연동 결과와 별도 |
| 차량 설정·복원 10조건 | **PASS 10/10 (`avd-03`)**, 시험자 정리 확인 | `environment.json`, `results.json`, `C*-*.json`, `tester-cleanup.json` | 조건별 요청·재조회·UI와 시험자 정리. C3 단독 성공을 전체 통과로 확대하지 않음 |
| 순정 내비·비전면 | **PASS (`runtime-avd-03`)**, 정리 확인 | `runtime/environment.json`, `result.json`, `navigation-window.json`, `live-navigation-callbacks.json`, `background-ticks.json`, `cleanup.json` | 직접 비표시 관측창과 전체 경로 창, DEMO 유무를 구분 |
| FGS 실패·복구 | **PASS (`fgs-failure-03`)** | `fgs/environment.json`, `result.json`, `ignore/result.json`, `deny/result.json`, `cleanup.json` | Android ignore/deny의 실제 거절·승격 확인과 수동 복구 |
| 강제 종료 | **PASS (`runtime-force-stop-02`)**, 정리 확인 | `force-stop/environment.json`, `result.json`, `input-ledger.jsonl`, `cleanup.json` | 자동 재시작·새 처리·명시적 재실행의 상태 |
| 쉘 작업 제거 | **PASS (`runtime-shell-task-removal-01`)**, 정리 확인 | `task-removal/result.json`, `input-ledger.jsonl`, `stack-preflight.json`, `stack-after-1.json`, `cleanup.json` | VIP 단독 root task의 쉘 제거. Recents 스와이프·onTaskRemoved 직접 계측과 구분 |
| 위치 ON/OFF·실행 중 OFF | **CONTROL_BOUNDARY_OBSERVED_ONLY**, 실행·정리 확인 | `location-on/`, `location-off/`, `location-active-off/`의 `result.json`, `access-before.json`, `input-ledger.jsonl`, `observations.jsonl`, `cleanup-snapshot.json` | 시스템 설정·권한·구독 경계와 GPS 데이터 검증을 구분 |
| 유휴 재연결 | **부분 관측 / REVIEW_REQUIRED**, 실행·정리 확인 | `location-reconnect/result.json`, `observations.jsonl`, `input-ledger.jsonl`, `cleanup-snapshot.json` | 초기화 로그와 fresh 데이터의 직접 관측을 구분 |
| 정상 범위·경고 경계 | **PASS (`known-boundary-02`)**, 정리 확인 | `known-boundary/environment.json`, `oracle-result.json`, `oracle-comparisons.json`, `trace.json`, `report.png`, `cleanup.json` | 실제 수신 시각 독립 계산·배지·허용 요청·억제·보고서. 음향 모두 OFF, 실재생·실차 정확도와 별도 |
| 실제 에뮬레이터 음향 | **PASS_WITH_SCOPE_LIMITATIONS (`matrix_03`)**, 정리·설정 복원 확인 | `audio/environment-before.json`, `environment-after.json`, `summary.json`, `verification-runtime06.json`, 수신 PCM·패킷 메타데이터·WAV·설정 XML | 출력과 정책 요청을 구분. sparse PCM 길이를 연속 wall time으로 쓰지 않음 |
| 연속 흐름·화면 | **PASS 9/9 (`flow-01`)**, 정리·전후 해시 확인 | `전체동선_무음.mp4`, `flow-timeline.json`, `flow-sdk-flow.log`, `flow-results.json`, `video-audit.json`, `flow-tester-cleanup.json` | 합성 차량 입력·실제 SDK 제어·후처리 여부와 원본 속성 |

이 표의 완료 실행은 위 runtime-06 해시와 일치합니다. 경계 원자료 30개·음향 원자료 5,179개의 manifest 해시를 대조해 불일치 0개를 확인했습니다. 이 파일 개수·음향 패킷 수는 시험 건수와 별개입니다. 대기 항목은 통과 건수에 포함하지 않습니다.

## 현재 후보의 차량 설정·복원

`avd-03`의 환경 해시는 runtime-06과 일치하며 독립 판정 10개가 모두 true, 시험자 정리는 verified=true입니다. 조건별 JSON·주요 UI와 원자료 63개 manifest 해시를 대조했습니다. C 번호는 절차 시점이며 판정 수와 다릅니다.

| 조건 | runtime-06 관측 |
|---|---|
| C1 착좌 원시값 2 | 미확인으로 보류, 조수석 22℃ 유지 |
| C2~C4 정상 적용·종료 | 22℃ 조회 → 18℃ 설정·재조회 → 종료 후 22℃ 복원·재조회 |
| C5 외부 수동 변경 | 외부에서 바꾼 21℃를 종료 후 유지 |
| C6 빈 좌석 확인 상실 | 세션 종료 후 조건부 22℃ 복원 |
| C7 공조 전원 OFF | 18℃ 설정값과 공조 전원 꺼짐을 함께 표시, 전원 OFF 유지 |
| C8 동기화 OFF | 조수석 18℃·운전석 24℃, 동기화 OFF 유지 |
| C9 동기화 ON | 온도 변경 보류, 양쪽 24℃·동기화 ON 유지 |
| C9B~C9C 적용 후 외부 동기화 변경 | 복원 보류·18℃ 유지 → 사용자가 동기화 해제 후 명시적 재시도로 조수석 22℃ 복원 |
| C10~C11 D단 종료·P단 재시도 | D단에서 18℃ 유지·복원 보류 → P단 정차 후 명시적 재시도로 조수석 22℃ 복원 |
| C12 프로세스 종료·재실행 | 18℃ 유지, 복원 미확인 수동 안내. 자동 원값 복원 없음 |

정리 후 Park·속도 0, 시험 전 차량 설정 복구를 확인했습니다. 이 정리는 앱 자체의 조건부 복원 성공과 별도입니다. C12는 차량 설정과 재실행 안내를 확인한 조건이며, 별도 강제 종료의 서비스/알림 관측이나 작업 제거 시험을 대신하지 않습니다.

## 현재 후보의 정상 범위·경고 경계

`known-boundary-02`는 COFFEE 0.12G·주의 비율 0.8에서 합성 차속 145개를 production 추정기·배지·정책·보고서까지 연결했습니다. 수신 간격은 0.304~0.640초이며 실제 dispatch 시각으로 Δv/Δt를 계산했습니다. 5m/s 유지 구간의 60° 약 0.06147G·90° 약 0.09239G는 OK, 100° 약 0.10275G는 WARN, 120° 약 0.12354G는 OVER이며 60° 회복에서 배지 유지 정책을 거쳐 OK로 돌아왔습니다.

횡/총 G 최대 계산 차이는 2.7755575615628914e-17G, 종 G 차이는 0입니다. 보고서는 최대 0.12354462329191918G(화면 0.12G), SYNTHETIC·canCompare=false·INITIAL_BASELINE을 유지했습니다. guard trace 435행·이동 중 이벤트 416개·새 속도 차분 139개는 별도 집계입니다. 도착 직후 보고서 UI 응답과 서비스 종료 로그를 확인했습니다. 이 자료에 서비스 dump는 없습니다. 최종 IDLE/PARK/속도 0 trace는 이후 End/ack 정리에서 확인했으며, 설정·진단 속성 복원과 실행/정리 오류 없음도 대조했습니다. 도착 즉시 Idle로 전이했다고 해석하지 않습니다.

음성 2회·효과음 1회의 **허용 요청**, COALESCED 억제 1회가 독립 정책 계산과 보고서에 일치했습니다. 허용 간격은 9.996초·8.044초로 공통 8초를 지켰습니다. 이번 실행은 음향 둘 다 OFF이므로 실재생 근거가 아니며, 총 6회·음성 3회 한도를 모두 소진한 시험도 아닙니다. 한계값·배지 유지·안내 예산은 팀 정책(D)이며 실차 정확도·짐 보호 효과로 일반화하지 않습니다. 정상 범위와 경고 경계를 함께 확인한 하나의 통합 실행으로 집계합니다.

## 현재 후보의 실제 음향 대조

`matrix_03`은 COFFEE 선택을 네 번 사용한 TTS-only·효과음-only·모두 OFF 두 대조입니다. 전후 APK 해시·PID가 같고 원설정 복원·최종 홈/P0를 확인했습니다. 수신 PCM·패킷 메타데이터·WAV의 바이트·시간창·해시 일치 판정은 `PASS_WITH_SCOPE_LIMITATIONS`, errors=[]입니다. 패킷 파일 5,090조각은 캡처 후 PCM을 메타데이터 길이로 분할한 자료입니다. 이 대조는 분할·재구성·산술의 무결성을 확인하며 별도 wire 캡처를 뜻하지 않습니다. 별도 독립 검수에서도 경계 24개·음향 54개 대조가 오류 0으로 확인됐습니다. 이 대조 수는 앱 시험 수에 합산하지 않습니다.

| 선택 후 0~8초 창 | peak absolute S16 | 수신 표본 RMS S16 | TTS 시작/완료/오류 |
|---|---:|---:|---|
| TTS ON / 효과음 OFF | 1,432 | 185.48 | 1 / 1 / 0 |
| 모두 OFF(TTS 대조) | 2 | 1.45 | 0 / 0 / 0 |
| TTS OFF / 효과음 ON | 146 | 16.21 | 0 / 0 / 0 |
| 모두 OFF(효과음 대조) | 2 | 1.47 | 0 / 0 / 0 |

이는 설정별 에뮬레이터 출력과 앱 로그의 TTS 생명주기 관측입니다. 둘 다 ON·주행 경고 발화·외부 내비/미디어 경합·명료도·실차 스피커·운전자 부담은 측정하지 않았습니다. PCM/WAV 길이는 수신 표본의 연결 길이이며 연속 wall time이나 발화 길이로 쓰지 않습니다. 최종 홈/P0·설정 복원·서비스 정리 로그를 확인했지만 이 음향 자료에는 최종 서비스/알림 dump가 없습니다.

## 현재 후보의 서비스 실패·복구

`fgs-failure-03`은 실제 Android 14 package app-op `ignore`와 `deny` 두 조건 모두 PASS입니다. 각각 ready 0·Guarding trace 0·관측 차량 처리 로그 0, 실패 안내·서비스/알림 없음이 확인됐습니다. `ignore`에서는 일반 서비스 변경·승격 거절 시스템 로그와 앱 IllegalStateException 처리를 관측했고 `deny`에서는 시작 실패를 확인했지만 앱 예외 클래스는 직접 기록되지 않았습니다.

원래 `allow`로 되돌린 뒤 사용자가 COFFEE를 다시 선택해 같은 PID에서 가드·서비스 유형 0x40000008·알림 1개를 확인했고, 정상 UI 종료 후 서비스/알림이 사라졌습니다. 원래 app-op·음향 설정·진단 속성과 정리 verified=true를 확인했습니다. 알림은 실제 posting을 기다려 관측했으며 시작 즉시 표시를 주장하지 않습니다. API 28~33 fallback·모든 실패 경로·차량 버스 전체 쓰기 부재의 증거로 확대하지 않습니다.

## 현재 후보의 순정 내비·비전면 수신

`runtime-avd-03`에서 순정 내비 전면·VIP STOPPED/visible=false를 직접 확인한 시점부터 경로 종료 요청까지 **20.297990초**, 보수적으로 tick 20개·최대 간격 1,007ms·TBT 6개를 확인했습니다. 하네스 원집계는 tick 21개이며, 장치/호스트 시각 대조의 ±17.2715ms 구간과 겹치는 경계 1개를 보수적 집계에서 제외했습니다. 원집계 21개를 수정하지 않고 함께 보존합니다. 이 구간의 DEMO 명령은 0개입니다. 경로 요청부터 VIP 복귀 전까지의 더 넓은 **37.790876초** 창은 ROUTE_DRIVE 1개·TBT 12개·FREE_DRIVE 1개이며 두 시간창의 콜백 수를 혼합하지 않습니다.

0·8·17.2초 표본은 같은 PID, isFrozen=false, 서비스 유형 0x40000008, 알림 1개였습니다. 연속 tick은 같은 세션·guarding=true·visible=false를 유지했습니다. freeze 없음은 세 시점의 표본 관측이고 세션 동작은 tick을 별도 근거로 둡니다. 같은 프로세스로 복귀한 뒤 정상 UI 종료를 확인했습니다. 첫 종료 snapshot은 FGS 해제·서비스 존재, 다음 snapshot은 서비스/알림 없음이므로 즉시 제거를 주장하지 않습니다.

정리 verified=true·실행/정리 오류 없음·원래 음향 설정과 진단 속성 복원을 확인했습니다. root가 소유한 기존 port forward는 유지했습니다. 정차한 공식 AVD의 native SDK 전달 근거이며 GPS 경로 진행·실제 목적지 도착·실차 정확도·음향 경합의 측정은 아닙니다.

## 현재 후보의 강제 종료·쉘 작업 제거

`runtime-force-stop-02`는 활성 COFFEE 세션을 강제 종료한 별도 실행입니다. 이후 1·8·18초 표본에서 프로세스·서비스·알림이 없고 새 runtime/Guarding/관측 쓰기 로그가 0개였습니다. 이후 명시적 재실행의 화면은 Idle/P0이며 가드를 자동 재개하지 않았습니다. 같은 runtime-06 해시, 정리 verified=true·실행/정리 오류 없음을 확인했습니다.

`runtime-shell-task-removal-01`은 사용자 10의 VIP 단독 root task를 직전 목록에서 식별한 뒤 쉘로 제거했습니다. 이어 같은 세션의 `GuardRuntime lost`와 종료를 관측했고, 1·8·18초 프로세스·서비스·알림 없음·자동 미재개·수동 재실행 Idle/P0·정리를 확인했습니다. **onTaskRemoved 콜백을 직접 계측하지 않았으며 lost는 다른 종료 경로와도 공유됩니다.** Recents 스와이프 성공 시험으로 쓰지 않고 이전 UI 기반 미실행 이력을 유지합니다.

두 실행은 서로 다른 종료 동작입니다. 18초 정차 AVD 관측이며 로그와 차량 값 확인을 차량 버스 모든 쓰기의 계측으로 일반화하지 않습니다. 각 정리 파일의 원설정 복구와 앱 자체 복원도 구분합니다.

## 현재 후보의 위치 설정·유휴 재연결

`location-on-03`, `location-off-02`, `location-active-off-02`의 원판정은 `CONTROL_BOUNDARY_OBSERVED_ONLY`입니다. 표준 COARSE/FINE·Pleos FUSED_LOCATION 권한은 모두 허용된 상태였고, 실제로 바꾼 것은 시스템 위치 설정입니다. ON에서 location 포함 서비스 유형 0x40000008·구독 활성, 시작 전 OFF에서 유형 0x40000000·구독 중지, 실행 중 OFF에서 구독 중지를 관측했습니다. 실행 중 다시 ON한 관측창에는 자동 구독 재개 로그가 없었고 이후 정상 종료 때 활성 로그가 나타났습니다.

`location-reconnect-02`는 유휴 화면 가림/복귀 2회 각각 새 AOSP 구독·NaviHelper ready·통합 연결 로그와 새 합성 차속 trace를 관측했습니다. 원판정 `REVIEW_REQUIRED`를 유지하며, 초기화 로그를 fresh 기어·Pleos getter·실제 경로 콜백의 검증으로 확대하지 않습니다.

네 실행 모두 현재 APK 해시, issues=[]·cleanupVerified=true, 최종 서비스/알림 없음과 원래 시스템 위치 ON·진단 속성 복원을 확인했습니다. 위치 원자료의 독립 검토는 별도입니다. GPS fix를 주입하거나 기록하지 않아 캐시 무효화 실측·차속 정확도·권한 회수·모든 늦은 GPS 콜백은 검증하지 않았습니다. JVM 캐시 회귀의 통과와 이 관측을 구분합니다.

## 현재 후보의 연속 흐름·화면

`flow-01`의 9개 판정과 시험자 정리는 모두 통과했고 전후 설치 APK 해시는 runtime-06과 일치합니다. 케이크 1탭 → 조수석 18℃ 설정값 확인 → Console/DDS 합성 주행 → P·운전석 문 열림 → 도착·22℃ 복원 → 공조 OFF 표시 → 착좌 미확인 보류를 한 번의 무음 녹화에 담았습니다.

원본은 **82.287311초·2560×1440·34,623,201바이트·오디오 스트림 0개**, SHA-256 `74483ce733bb5a47bd3271eac06968baeffafb676e3031c091217967fdd1eda8`입니다. 실행 원자료 29개 manifest 해시와 A1~A5 v1.1.4 화면의 원본/공개 사본 일치를 확인했습니다. `flow-timeline.json`의 82.37초 완료 이벤트는 녹화 외부의 절차 시각이며 미디어 길이 82.287311초와 구분합니다.

리포트 최대 **4.49G**는 원값으로 보존했고 SYNTHETIC·모의 입력·실주행 평가 불가·고G 구간 원인 미확인을 표시했습니다. 이 고G 합성 흐름과 별도 정상 범위/경계 계산 시험을 구분합니다. v1.1.3의 3.69G·v1.1.2의 4.53G와의 차이는 정확도나 보호 효과 비교가 아닙니다. 무음 영상은 실제 음향의 근거가 아니며 COFFEE 출력 대조는 별도 `audio/` 자료입니다.

A1~A5 공개 화면은 `../../screens/A1_home_v114.png`, `A2_loaded_v114.png`, `A3_driving_v114.png`, `A4_arrival_v114.png`, `A5_power_off_v114.png`입니다. A6 착좌 보류는 흐름 원자료에도 보존합니다.

## runtime-05 실패·제어 이력

이전 후보 APK SHA-256은 `14709ea8c5b7c16f8d91592d27d3d408e65fd513fd8246aea004c71f1903fe03`입니다. `known-boundary-01`은 도착 처리 중 **실제 앱 ANR로 전체 실패**했습니다. 원래 `oracle-result.json`의 passed=false와 `cleanup.json`의 verified=false를 보존합니다. main thread의 완료 작업 반복 대기를 원자료에서 확인했고 runtime-06에서 수정했습니다.

`known-boundary-diagnosis-01/recovery.json`은 별도의 시험자 수동 복구입니다. 음향 원설정·진단 속성·Idle/P0·서비스/알림 없음·관측 차량 값 유지, issues=[]를 확인했습니다. 이 복구는 원래 정리 실패를 성공으로 바꾸지 않습니다. 경고 계산·배지·허용 요청의 부분 일치도 전체 PASS로 쓰지 않습니다. 실패/진단/수동 복구 사본은 `history/runtime-05/known-boundary-01/`과 `history/runtime-05/known-boundary-diagnosis-01/`에 구분해 배치할 계획입니다.

그 전의 `avd-02/environment.json` 설치 해시는 runtime-05와 일치합니다. `results.json`의 독립 판정 10개가 모두 true이며 `tester-cleanup.json`은 verified=true입니다. 조건별 상태·UI 및 `artifact-sha256.json`의 원자료 63개 해시를 대조했습니다. 아래 C 번호는 절차의 시점이며 판정 10개와 개수가 다릅니다.

이 10조건 통과는 당시 후보의 제한된 실행 이력이며, 이후 ANR 실패와 함께 보존합니다. 현재 결과는 같은 절차를 runtime-06에서 다시 실행한 별도 자료입니다.

## runtime-04와 이전 이력

runtime-04 SHA-256: `6d886a66a3170eda552dcbdb5b0951474134e94a3db78aad3f0923a30770947d`. 빌드 170시험 통과 후 **avd-01 C3 실패(18℃ 기대 / 22℃ 유지)**로 최종 후보에서 제외했습니다. 위치 lease 해제 시 외부 AOSP 차속까지 지운 문제가 원인이며, 실패 원자료·시험자 정리 PASS를 보존합니다.

| 이전 실행 | 당시 판정·관측 | 현재 후보와의 경계 |
|---|---|---|
| `runtime-avd-02` | PASS. 직접 비표시 관측 20.129096초: tick 20·최대 1,004ms·TBT 7. 전체 경로 요청~복귀: ROUTE 1·TBT 13·FREE 1 | 해당 구간 DEMO 없음. 같은 PID·세션, 0/8/17.2초 freeze 없는 표본. runtime-06 결과는 위 현재 후보 항목에 별도 기록 |
| `fgs-failure-02` | PASS. ignore/deny에서 가드 미시작·실패 안내·잔류 서비스/알림 없음, 차단 해제 뒤 수동 복구 | API 34 두 조건의 관측. API 28~33·모든 오류 경로 증명 아님 |
| `runtime-force-stop-01` | PASS. 1/8/18초 프로세스·서비스·알림 없음, 수동 재실행 Idle | 작업 제거와 별개 |
| `runtime-task-removal-01` | 미실행. VIP 작업만 닫는 UI를 찾지 못함. 정리 PASS | `passed=false`는 동작 미수행이며 앱 결함 관측과 구분 |
| `location-on-02`, `location-off-01`, `location-active-off-01` | 제어 경계만 관측. 권한 허용 상태의 시스템 위치 변경·구독 로그 | GPS fix·캐시 실측을 수행하지 않았으며 수정 코드 통합 검증을 대신하지 않음 |
| `location-reconnect-01` | 부분 관측. 유휴 왕복 2회의 AOSP 구독·내비 ready·합성 차속 trace | fresh 기어·Pleos getter·실제 경로 콜백 전체 확인 아님 |
| `known-answer-03` | PASS. 합성 차속 107개, 총 G 최대 계산 차이 6.938893903907228e-18G, 최대 0.06147058330461162G/UI 0.06G | 모의 입력·실주행 평가 불가. 비전면 DEMO 입력이 있어 native 지속 수신의 근거로 쓰지 않음 |

더 이전 `tts-navi-01/matrix_02`의 출력과 `trace-02/known-answer-02`의 정상 G도 별도 후보 이력입니다. 동일한 versionName/code라도 해시가 다르면 최종 검증으로 승계하지 않습니다.

## 재현·해석 범위

공식 AVD·APK 해시·정차 상태를 확인하고 새 출력 폴더에서 실행합니다. 각 환경·판정·정리 파일과 artifact 해시를 묶으며, 정리 성공과 앱 자체 복원은 구분합니다. 기존 실패·미실행 원자료를 덮어쓰지 않습니다.

설정값 확인은 냉방·상자 온도·적재물 보호 효과가 아닙니다. 로그 부재는 버스 수준의 모든 쓰기 부재를 증명하지 않습니다. 세션별 6회·음성 3회·8초와 G 초기값은 팀 정책(D)이며 계산 검증 뒤에도 등급은 유지합니다. 실제 음향 대조는 명료도·내비 경합·실차 스피커·운전자 부담까지 입증하지 않습니다.

실제 사용자·실차 계측·짐 보호 효과·재사용·구매/채널 결과는 0건이고 현장 CSV 8개는 데이터 0행입니다. AVD 직접 관측(A), 계산(C), 초기값·정책·계획(D)을 구분합니다.

[변경 요약](../../검증_v1.1.4.md) · [v1.1.3 이력](../AVD_v1.1.3/README.md) · [v1.1.2 이력](../AVD_v1.1.2/README.md) · [현장검증 진행 키트](../현장검증_진행키트/README.md)
