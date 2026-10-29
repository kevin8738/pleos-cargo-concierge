# v24 integration-01 독립 원자료 검토

**원 전체 판정은 false 그대로다.** 독립 오프라인 정합 검사 **57개, 오류 0개**는 원자료·원판정·추가 설명의 대조 결과이며 앱 시험 수나 통합 실행 PASS가 아니다. 원118개 파일 SHA, 실행 하네스 `ec0618aa…`, runtime06 APK `8971e207…`, 동일 PID22692를 확인했다. 추가 설명 폴더 해시도 대조했다. ADB·AVD·빌드·재실행·원자료 수정은 하지 않았다.

## 원 false의 원인과 직접 관측 분리

| 원 조건 | 독립 대조 | 유지할 결론 |
|---|---|---|
| motionOracle=false | 원303 issues = 후속 카운터 불일치302 + 도착 카운터1. guard782행은 speed261/brake261/steering260. 최초 row480 직전 gSamples174→다음 brake176이며 사이 steering MotionTrace 없음 | 한 기록의 공백 이후 연쇄 오프셋이다. 누락 원인은 미확정. 303개의 서로 다른 계산 오류라고 하지 않고 없는 행을 생성하지 않음 |
| clockEndpointsConsistent=false | 시작54.962120±10.861500ms, 종료22.562706±12.284000ms. 두 구간 사이9.253914ms 공백 | 하나의 상수 offset이 두 측정을 만족하지 못한다. 선형 보간/최적화로 원FAIL을 고치지 않음 |
| mixedPcmSignalInEachResponse=false | 도착 전 peak1449/RMS146.3273, 응답1480/245.2670. peak1.0214배/RMS1.6762배 | 사전3배 기준 실패. 도착 음향이 없거나 무음이었다는 뜻이 아님. 사전 신호의 원천도 미확정 |
| parkedArrivalAndReport=false | 원 predicate에 `oracle.passed`가 결합됨. 실제 P/0·같은 세션·합성 ARRIVED 1개·보고서 자체는 존재 | 정차/도착 관측과 원 결합 판정의 실패를 분리하며 원false는 유지 |

실제 보존된 guard782행의 G값3,128개와 longitudinalFresh782개를 원 속도·시각·모델로 독립 계산해 불일치0을 확인했다. 수신 speed dt는0.401–0.603초다. 관측 최대0.12354462329191915G는 보고서0.12354462329191918G와 허용 오차 내 일치한다. 관측 motion461 대 보고서462, fresh154는 그대로다. **완전한 원신호→횟수→보고서 검증은 아니다.** 정확한 누락 행·누락 원인은 확정하지 않았다.

native 추가 코칭은 이번 카운터 오류의 원인으로 지지되지 않는다. 원 audio oracle의 chime1/speech2·COALESCED1이 실제 trip과 일치하고 admission 불일치는0이다. native TBT 수신 자체를 추가 VIP 발화로 세지 않는다.

## 시간·음향의 확인 범위

- hostEpoch−hostMonotonic 차이가 전체 관측 중33.631ms 변했다. deviceEpoch−elapsed 차이의 범위는 약4ms다. host wall-clock 변화와 offset 변화가 부합하지만 시스템 원인은 확정하지 않았다. 고정 offset에 의존한 원 true 창을 엄격한 공통 시간축 검증으로 승격하지 않는다.
- 요청→순정 종료 관측창은86.810583초다. 직접 비표시38샘플의 첫~끝은75.386467초이며 샘플 최대 간격은2.102328초다. 양 창의 positive TBT 수신 분모는 각각27개/25개다. 전체 TBT30개에는 종료 후 빈 목록3개가 포함된다. 연속 비표시·자연 주행·DEMO 깨움 없는 생존으로 해석하지 않는다.
- profile 창의 runtime tick72개, device timestamp 최대 간격1.013초가 보존됐다. 같은 runtime request15/startedMs19613878과 COFFEE 세션이다.
- 단일 수집의15,545응답·9,026,060B PCM·2,256,515 stereo frames를 재계산했다. 실제 PCM47.010729초, wall179.977896초이며 WAV 프레임은 원PCM과 동일하다. 희소 PCM을180초 연속 소리로 표현하지 않는다.
- 주행2쌍과 도착1쌍의 START→DONE 순서는 device 자체 시계와 원 로그로 직접 보인다. START 전 STOP을 보존했고 쌍 내부 STOP/ERROR는 없다. 발화 ID가 없으므로 native 발화 귀속이나 실제 중첩 증거는 아니다.
- 원 창 경계에 따른 각 표본/진폭 계산은 원 바이트와 일치한다. 주행3개 진폭 대조는 충족, 도착만 미충족이다. 도착 response145패킷·1.764708초, tail246패킷·3.007초가 실제로 있다. tail은 마지막 수신 전에 위치하지만 시계 보정의 원 한계를 적용한다.

## 정리·화면·개인정보

원 차량 JSON은 `gear/actual/timestamp_ms`만 바뀌었고 HVAC/seat/body/controls 등 나머지 값은 같다. 세 스위치 모두 원 true로 복원됐으며 native/VIP Idle XML·마지막 IDLE/P0 trace·서비스0·알림0·동일 APK/PID가 실제 저장 자료와 일치한다. 본인 Console lease의 closed=true/오류0도 확인했다. 원 빈 진단 속성 복원은 검토한 하네스의 getprop assert와 무오류 경로 및 handoff 설명에 의존한다. **별도 신규 getprop/forward 원출력까지 독립 관측한 것은 아니다.** 원manifest는 실행 후에도 불변이며 recovery는 수행하지 않았다.

`report.png`를 실제 이미지로 읽었다. P/0, 합성 최대0.12G, 음성요청2·효과음요청1, 모의입력·실주행평가불가, 캐빈 적용0 및 복원할 값 없음이 표시된다. 개인정보·서명·연락처는 관측하지 않았다. 이미지 SHA는 `bb39c234cbb18cb6ecb56224b6bc384508c4102b577b0a343921ddc51b5a52d6`다.

텍스트115파일의 홈경로·이메일·휴대전화·대표 비밀값 패턴 검사는0건이다. 이는 완전한 개인정보 인증이 아니다. PCM/WAV는 청취·전사하지 않았으므로 공개 전 root의 내용 검토가 필요하다. native 검색/목적지 XML과 비어 있지 않은 앱 알림3개는 공개 fixture·앱 범위를 최종 선별해야 한다. 구체 파일/SHA는 `privacy-selection.json`에 있다. 원FAIL·시계·원oracle·원정리·원로그를 선택에서 빼서 성공처럼 만들면 안 된다.

산출물: `verify_integration.py`(읽기 전용 검산), `verification.json`(57개 대조·원 한계), `privacy-selection.json`(검토 필요 목록). native 발화 귀속·실제 음향 경합·focus 우선순위·실차 G 정확도·물건/사용자 효과는 추가 입증되지 않았다.
