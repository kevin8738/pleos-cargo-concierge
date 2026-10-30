# 별도 관측 APK 경계·음향 독립 증거 검수

이 문서는 별도 관측 APK와 해당 실행 당시 소스를 대조한 기록입니다. 아래 “현재”·“후보”·“수정 전후”는 그 검수 시점의 범위이며 제출 APK의 추가 검증 결과가 아닙니다.


판정: **PASS_WITH_SCOPE_LIMITATIONS**. ADB, AVD, 빌드 또는 생산 코드 실행·수정 없이 원자료를 읽고 별도 Python 계산으로 검수했다. 후보 APK SHA-256은 `8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c`이며, 저장 APK와 설치 전후 기록이 일치한다. 후보 소스 manifest 21개 모두 현재 소스와 일치한다.

## 경계값 — 24개 검사, 오류 0

`known-boundary-02`의 manifest 30개 해시가 일치한다. 원본 MotionTrace를 다시 파싱한 439행은 저장 trace와 동일하며, GUARDING 435행·속도 145행이다. 입력 장부의 속도·조향·브레이크 435개 요청을 수신 순서 및 값과 대조했다. 고정 fixture인 Float32 2.9m, 조향비 15, 평활 0.5, 중력 9.80665, 최대 제동 8을 확인하고, 수신 시각 차이로 G를 별도 계산했다.

- 최대 추정 G `0.12354462329191915`, motion events 416, fresh longitudinal samples 139.
- 수신 속도 간격 0.304–0.640초. 모든 행의 G, 배지, 누적 횟수와 최대값이 맞는다.
- 주행 음성 요청 2, 효과음 요청 1, COALESCED 1. 허용 요청 사이 간격 9,996ms와 8,044ms.
- 도착은 `ARRIVED` 및 보고서 표시다. 도착 로그 5ms 후 공조 작업 정리 서비스 종료 로그가 있고, cleanup End보다 3,580ms 앞선다. 보고서 XML도 cleanup End 전에 응답했다. 마지막 `IDLE/P0`는 이후 명시적 End/ack cleanup 결과다.
- 보고서는 모의 입력·실주행 평가 불가를 표시한다. 차량의 공조/시트/차체/제어 읽기값, 음향 설정 복원 및 최종 Idle/P0를 확인했다.

이번 실행은 기존 drain ANR을 일으켰던 도착 경로가 진행됨을 보인다. 서비스 dump는 이 자료에 없고, 한 번의 응답 및 필터 로그로 모든 ANR 부재를 주장할 수 없다. `known-boundary-01` 실패와 ANR 관측 APK ANR은 그대로 보존해야 한다. COALESCED와 허용 간격을 검증했지만 COOLDOWN 거절, 음성/총예산 포화, 실차 G 정확도, 실제 짐 보호, 실제 재생은 검증하지 않았다. 두 음향 설정은 OFF였다.

## 음향 — 54개 검사, 오류 0

`matrix_03` manifest 5,179개 해시, 수신 packet 5,090개의 바이트·offset·timestamp·sample count·제곱합·peak·RMS를 재계산했다. 네 PCM은 packet 순서 연결과 같고 WAV 헤더(48kHz, stereo, signed 16bit) 및 payload도 같다. 각 15초 캡처는 duration limit으로 종료되어 CANCELLED/DEADLINE_EXCEEDED와 exit 0이 함께 기록된다.

| COFFEE 시작 설정 | packet 수 | 0–8초 수신 peak | 수신 sample RMS | TTS 시작/완료/오류 |
|---|---:|---:|---:|---:|
| TTS ON, 효과음 OFF | 1,264 | 1,432 | 185.4842 | 1/1/0 |
| 모두 OFF | 1,280 | 2 | 1.4515 | 0/0/0 |
| TTS OFF, 효과음 ON | 1,265 | 146 | 16.2094 | 0/0/0 |
| 모두 OFF 재대조 | 1,281 | 2 | 1.4749 | 0/0/0 |

명령 시작 host 시각 기준으로 pre/response/late 창을 독립 재계산했고 기록과 일치한다. packet epoch는 host capture 시각과 경과 시간에 대해 약 −4.85~−0.20ms 차이로 관측됐다. device logcat 시각은 별도이며, 단일 clock calibration의 device−host는 290.631073ms, 왕복시간 절반은 13.6315ms다. 이 자료는 clock drift 상한을 제공하지 않는다.

packet 파일은 캡처 뒤 PCM을 metadata 길이에 따라 나눈 파일이므로 별도의 네트워크 원본은 아니다. 일치 검산은 분할·재구성과 산술의 무결성을 입증한다. PCM 길이는 수신된 sample 연결 길이이며 연속 wall time, 발화 길이 또는 무음 길이가 아니다. 네 조건의 출력 크기 대비는 관측되지만 음성 내용 이해도, 실제 스피커, 운전자 부담, 양쪽 ON, 내비/미디어 경합은 평가하지 않았다. TTS 오류 0도 선택한 앱 log tag 범위다.

최종 XML의 Idle/P0·요청 15 서비스 정리 로그와 원래 음향 toggle 복원은 확인했다. 이 음향 자료 자체에는 최종 service/notification dump가 없다. 검수기 초안은 Compose의 평탄한 XML에서 switch를 label 자식으로 찾지 못했으며, label 수직 범위와 오른쪽 checkable 노드의 유일한 일치로 수정했다. 앱 시험 실패가 아니라 오프라인 검수기의 탐색 수정이다.

재현: `python3 .omx/qa/submission-v22/final-verification/verify_boundary_audio.py`. 세부 결과는 같은 폴더의 `boundary-verification.json`, `audio-verification.json`이다.
