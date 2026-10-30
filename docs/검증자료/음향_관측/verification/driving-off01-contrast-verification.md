# OFF 및 후속 ON/OFF 대조 검수

오프라인 대조 **39개, 불일치 0건**. OFF 원본 manifest 48개, 520 trace/513 Guarding/171 차속 요청, 독립 수신시각 계산·417 주행 이벤트·fresh 139개·같은 세션 도착을 확인했다. OFF의 설정 재조회·최종 정리 true와 원값/Idle/P0/서비스·알림 없음, 전후 동일 APK/PID가 일치한다.

OFF streamAudio 메타데이터 응답은 **3,437건**, ON은 **3,386건**이다. 새 `.s16le` 분할 파일을 만든 수치가 아니며 의 사후 분할 5,090조각과 별개다. 원본 PCM/WAV/metadata/packet-analysis 네 파일을 그대로 보존한다.

원 PCM과 각 창의 표본 수·RMS·peak를 독립 계산했다. WARN 차임은 ON peak 152/RMS 12.7941 대 OFF peak 2/RMS 1.49377, OVER 두 발화는 ON peak 1,345/RMS 195.8587·196.3952 대 OFF peak 2/RMS 1.49399·1.49357이다. 모든 ON pre·ON response·OFF response 창에 실제 수신 표본이 있고 사전 3배 대비 기준을 충족한다. OFF 주행 창에는 TTS START/DONE/STOP/ERROR 콜백이 없다.

`continued-comparison.json`의 9개 원자료 해시, 세 비교값과 판정·실패/복구 구분이 일치한다. 원 ON result=false·cleanup=false, 별도 복구 true, OFF true, `singleSuccessfulPairedRun=false`를 유지한다. 같은 앱/프로세스의 서로 다른 두 합성 세션을 사후 대조한 결과다. 이는 운전자 청취 명료도·내비 음향 경합·실차 스피커·보호 효과의 증거가 아니며 도착 음향은 주행 캡처 밖이다.

상세 검사와 재계산은 `driving-off01-contrast-verification.json`, `verify_driving_off01.py`에 보관한다.
