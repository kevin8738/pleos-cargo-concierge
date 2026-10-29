# 새 Console flow 독립 검수

오프라인 대조 27개에서 불일치 0건. manifest 42개, raw trace 600개 중 Guarding 326개·source 1 차속 273개, accepted pair 270개를 확인했다. 50ms 미만 두 행은 기존 baseline을 유지하는 분기를 직접 계산했고 nonincreasing 행과 관측된 2초 초과 공백은 없었다. 모든 계산·주행 이벤트 237개·fresh 199개와 같은 세션 15297239의 도착 보고서가 일치한다.

새 최대값은 **4.437252817516956G**, 화면은 **4.44G**다. source 1의 수신 시각 15,312,771ms에서 9.444444656m/s, 15,312,872ms에서 0.277777791m/s가 accepted pair다. 차이는 101ms·−9.166666865m/s, raw 가속도 −90.759077875m/s²다. 직전 EMA 3.729907189에 α=0.5를 적용하면 EMA −43.514585343m/s², 종 G −4.437252818이 나온다. 해당 행의 횡 G는 0이며 독립 계산·trace·trip 최대·보고서가 연결된다.

이는 **새 합성 수신열의 계산 일치**다. 101ms는 이번 trace의 실제 수신 시각 차이며 Console HTTP 요청 간격을 대입한 값이 아니다. 어떤 setter 또는 Console 내부 동작이 이 수신열을 만들었는지의 근본 원인이나 과거 원영상 4.49G의 누락 입력쌍을 확정하지 않는다. HIGH_G_UNASSESSED·SYNTHETIC·canCompare=false와 ‘모의 입력·실주행 평가 불가·원인 미확인’ 표시를 보존한다.

원 A1–A4 Console 요청 순서·값, 실제 22→18→22 설정값, 동일 APK/PID, 원설정·Console 항목·Idle/P0·서비스/알림 없음과 cleanup=true를 대조했다. 진단 속성 복원은 저장 하네스의 재조회 assertion과 cleanup으로 확인되며 별도 stdout 파일은 없다. 온도는 설정값, 영상은 무음이며 물건 보호·실차 G 정확도·주행 음향을 증명하지 않는다. 원 영상 해시만 이 검수에 포함하며 화면·전체 디코드 검수는 root 기록을 별도로 따른다.

상세 결과는 `flow01-verification.json`, 재계산 코드는 `verify_flow01.py`에 보관한다.
