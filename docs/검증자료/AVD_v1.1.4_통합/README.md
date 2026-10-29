# 단일 native/VIP 통합 관측 — v24 관측자료

**최초 전체 판정은 false, 실행 오류 0, cleanup=true다.** 원 판정·4개 미충족 항목·원자료·보충 설명·독립 대조를 보존했다. 같은 runtime06 APK(1.1.4-pleos/code7, SHA `8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c`)의 한 세션·한 수집·한 회차이며 재실행하지 않았다.

이 폴더는 원판정과 증거를 묶은 자료다. [root 미디어·개인정보 검토](verification/root-media-privacy-review.json)는 PNG 직접 확인, 원 WAV의 ASR 보조 개인정보 선별, 공개 POI XML 및 본인 앱 알림 범위를 기록한다. 이는 완전한 개인정보 인증·청취 명료도·발화 귀속 검증이 아니다. ASR 원문은 포함하지 않았다.

## 먼저 읽을 자료

- [원 전체·개별 판정](integration-01/integration-result.json), [원 정리](integration-01/cleanup.json)
- [관측 사실과 실패 원인](integration-01-supplement/README.md), [AVD 반환·원값 확인](integration-01-supplement/handoff.json)
- [독립 원자료 대조](integration-verification/README.md), [57개 대조·오류0 결과](integration-verification/verification.json)
- [실행 환경·시계 시작](integration-01/environment.json), [시계 종료](integration-01/clock-end.json), [원 oracle](integration-01/oracle-result.json)
- [공개 manifest: source/published 해시 구분](manifest.json), [재현 코드와 읽기 전용 검산](reproduction/README.md)

## 확인 범위와 남은 공백

순정 ROUTE_DRIVE → VIP 비전면의 합성 정상/WARN/OVER·회복 → 실제 FREE_DRIVE → P0 → 합성 ARRIVED가 같은 COFFEE 세션에서 관측됐다. 비전면 직접 샘플38개·runtime tick72개와 VIP START→DONE 주행2쌍/도착1쌍이 저장됐다. TBT는 전체30개 중 빈 목록3개를 제외한 실제 경로 요청~종료 창27개, 직접 비표시 샘플 사이25개다. 각 분모를 섞지 않는다. DEMO 입력은 프로세스를 깨울 수 있으므로 자연 비전면 생존이나 연속 비표시의 증거가 아니다.

| 유지된 원 false 항목 | 원인·한계 |
|---|---|
| `motionOracle` | steering MotionTrace 1행 공백 후 counter302건·도착 counter1건 불일치. 남아 있는 G값 산술과 최대값은 맞지만 누락 행·원인은 확정하지 못함 |
| `clockEndpointsConsistent` | 시작·종료 보정 구간 사이9.253914ms 간극. 원 고정 offset 시간창의 true 항목도 엄격한 공통 시계 검증으로 승격할 수 없음 |
| `mixedPcmSignalInEachResponse` | 주행3창 진폭 대조는 충족했으나 도착 직전 큰 미귀속 혼합 신호가 있어 도착3배 기준 미충족. 무음이라는 뜻은 아님 |
| `parkedArrivalAndReport` | oracle 성공이 결합된 조건이라 false. 실제 P0·단일 합성 ARRIVED·보고서 자체는 존재 |

native 경로·TBT·혼합 PCM만으로 **native 음성 귀속·실제 발화 겹침·focus 우선순위**를 입증하지 않는다. 셋 모두 미검증이다. COFFEE는 캐빈 적용0개이며 공조 적용/복원 효과도 입증하지 않는다. 실차 G 정확도·적재물 보호·운전자 부담·실제 사용자 검증은 추가되지 않았다. 독립57개 대조는 자료의 정합 검사이며 앱182시험에 더하거나 통합 PASS로 부르지 않는다.

## 실제 수신 음향과 화면

한 `streamAudio`에서15,545패킷·9,026,060B PCM을 수신했다. 실제 수신 프레임 길이는 **47.010729초**, wall time은 **179.977896초**다. WAV는 희소 수신 프레임을 이어 담은 컨테이너이므로 **180초 연속 녹음이 아니다**. 다른 회차와 합치거나 후처리·재동기화·개별 패킷 파일 분할을 하지 않았다.

- [원 metadata·timestamp·패킷 bytes](integration-01/capture/metadata.json)
- [원 PCM](integration-01/capture/audio.s16le.pcm), [동일 프레임 WAV](integration-01/capture/audio.wav)
- [원 응답별 사후 계산](integration-01/capture/packet-analysis.json)
- [보고서 PNG](integration-01/report.png), [보고서 XML](integration-01/report.xml)
- [독립 개인정보·범위 선별 목록](integration-verification/privacy-selection.json)

도착 response145패킷·1.764708초, tail246패킷·3.007초는 실제로 존재한다. 시계 불일치 한계를 적용하며, 도착 TTS와 SUCCESS 효과음 성분을 분리했다고 주장하지 않는다. PNG는 독립 검토자와 root가 직접 확인했다. root의 원 WAV ASR 보조 개인정보 선별은 청취·발화 귀속·경합 검증을 뜻하지 않는다. 준비자는 원 오디오를 청취·전사하지 않았다.

## 원본 보존과 파일 구조

`integration-01/`에는 원 manifest의118파일과 그 원 manifest를 한 번씩 보존한다. `integration-01-supplement/`는 원 판정을 바꾸지 않는 별도 설명이며, `integration-verification/`는 독립 읽기 전용 검산이다. source/published 해시는 [manifest](manifest.json)에 따로 기록했다. 원 `artifact-sha256.json`은 원본 인벤토리라는 의미를 유지하며 공개 사본의 최종 바이트 인벤토리로 바꾸지 않았다. 변경이 필요한 경로 익명화가 있으면 파일별 transformations에 기록한다.

개인 인증 연결 파일·token·전체 오디오 focus/intent dump는 포함하지 않는다. 공개 목적지 fixture XML과 본인 앱으로 필터된 서비스·알림 자료는 원 범위를 유지했으며 최종 선별 목록에 표시했다. 원 스위치·로그 속성·P0/Idle·같은 APK/PID·차량값·서비스/알림0·본인 helper/forward/token 정리 기록을 함께 보존한다.
