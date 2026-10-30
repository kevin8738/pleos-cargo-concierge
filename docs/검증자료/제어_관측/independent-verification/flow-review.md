# 별도 관측 APK 전체 동선 자료 독립 검수

이 문서는 별도 관측 APK와 해당 실행 당시 소스를 대조한 기록입니다. 아래 “현재”·“후보”·“수정 전후”는 그 검수 시점의 범위이며 제출 APK의 추가 검증 결과가 아닙니다.


**PASS_WITH_SCOPE_LIMITATIONS — 19/19 검사 일치.** `flow-01`의 9개 통합 판정, 설치 전후 SHA, Console 읽기값, SDK 로그, XML, 영상 metadata를 대조하고 `A4-arrival.png`를 직접 시각 확인했다. AVD·ADB·빌드·재촬영은 실행하지 않았다.

후보 SHA `8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c`가 전후 같다. A1 Idle/22°C→A2 케이크 선택/18°C 설정값 확인→A3 Drive/간소화 안내→A4 P·운전석 도어 열림/도착 보고서·조수석22°C 조건부 복원→A5 전원OFF/18°C 설정값 안내→A6 비착좌 확인 불가/22°C 유지가 원자료와 맞는다. SDK18/22 getter·적용/복원 APPLIED 및 착좌 차단도 확인했다.

도착 화면은 최대 **4.49G**, `모의 입력 최대 추정 G`, `모의 입력 포함 · 고G 구간 · 원인 미확인`, `실주행 평가 불가`를 함께 표시한다. `평균 설정 온도18.0℃`와 현재 조수석 설정22.0℃를 구분하고, 실제 실내·짐 온도와 냉방 효과를 확인하지 않는다는 안내가 있다. 이번 flow는 G 정확도 시험이 아니므로 4.49G를 이전3.69G/4.53G와 성능 비교하거나 실제 물리 충격으로 설명하지 않는다.

원본 MP4 해시 `74483ce733bb5a47bd3271eac06968baeffafb676e3031c091217967fdd1eda8`를 다시 계산했다. 독립 ffprobe 결과 **82.287311초,34,623,201bytes,2560×1440,영상1/audio0/data2 stream**으로 video-audit와 같다. 원본 demux timebase decode exit0·오류로그0bytes·원본불변이라는 root 검증 기록을 확인했으며 디코더는 다시 실행하지 않았다. 앞선 기본 출력 timebase의 DTS warning 기록은 보존돼 있다.

한 번의 screenrecord 시작과 최초 케이크 한 번 tap은 harness/timeline 및 loaded UI로 뒷받침되며 별도의 input-event 녹화는 없다. host의 flow_complete82.37초와 영상 길이의 약83ms 차이는 서로 다른 host/capture 시각이라 중간 편집의 근거가 아니다. 실제 소리 검증은 이 무음 영상이 아니라 matrix03의 별도 범위다. tester의 외부 fixture 복구와 앱의 도착 복원을 분리했다.

세부 체크 및 manifest 범위는 `flow-verification.json`, 재현은 `python3 .omx/qa/submission-v22/final-verification/verify_flow.py`다.
