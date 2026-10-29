# v1.1.4 수집 보완 관측 — 실패 기록

앱 1.1.4-pleos/code7의 단일 관측입니다. 새 앱 1.1.5의 기능 검증과 구분합니다. [요약 수치](REPORT.json)와 [원 판정](integration-01/integration-result.json)을 보존했습니다.

PCM 수집기가 공통 시작 103.795579459초에 ValueError로 종료되어 전체 관측은 FAIL입니다. RPC는 CANCELLED, 저장 wire 9,138개·해석 9,137개, 실제 수신 PCM 32.4610208333초입니다. 희소 조각의 합이며 연속 녹음이 아닙니다. 합성 도착 요청 0회로 도착 전 대조창·DONE 이후 수신 검증은 미완료입니다. 수집기 로컬 fixture 30개 통과를 실관측 성공이나 앱 시험에 더하지 않습니다.

logcat의 제한된 수신/저장/deadline 계약은 PASS(2,823행·파싱 오류0)지만 실제 종료 -15와 기존 수집기 엄격 판정 FAIL은 그대로입니다. 원 cleanup=false도 유지합니다. 별도 [반환 확인](handoff-01.json)의 상태·자원 10항목은 true이며 수동복구·재수집은 없었습니다. heartbeat 종료 기록은 있으나 마지막 forward 목록의 별도 읽기 기록은 없습니다.

생산자 완전성·실제 내비 발화 귀속·음향 경합·실차 청취·사용자 효과는 미검증입니다. 이전 v24 통합 실패도 바꾸지 않았습니다. 공개 사본은 판정/요약/입력 원장의 일부이며 전체 PCM·private stderr·Console 원값·intent/focus·인증 정보는 포함하지 않습니다. REPORT의 artifact-sha256 등 공개하지 않은 원본 참조는 로컬 보존 파일의 해시입니다. [공개 파일 명세](manifest.json)는 이 폴더의 사본 범위만 설명합니다.
