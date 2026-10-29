# v1.1.5 공식 AVD 원자료

앱1.1.5-pleos/code8, SHA-256 `6a4a41ed2aa8f73fb0a7dd2b3bf499d3074a5d2ec9ecd21273763611097df287`. 공식 crp_avd_arm64/API34/VehicleService2.3.22(code74), SDK2.3.2에서 수행했습니다. 실차·사용자 효과 검증은 아닙니다.

| 실행 | 결과 | 원자료 |
|---|---|---|
| 과거 기록 | 6/6 PASS | [결과](history-01/results.json) · [원상 확인](history-01/tester-cleanup.json) · [과거값 화면](history-01/03-open-after.png) |
| 제어·복원 | 10/10 PASS | [결과](control-01/results.json) · [원상 확인](control-01/tester-cleanup.json) |
| 연속 동선 | 9/9 PASS | [결과](flow-01/results.json) · [원상 확인](flow-01/tester-cleanup.json) · [무음 원본](flow-01/전체동선_무음.mp4) |
| 시험·빌드 | 고유203개/실행228회·실패0, release/서명/mock PASS·lint0/33 | [시험 집계](tests/summary.md) · [빌드 당시 요약](build/build-summary.json) |

[실행 종료 기록](device-run.json), [영상 메타](flow-media.json), [공개 사본 명세](manifest.json)를 함께 확인합니다. 빌드 요약의 AVD=NOT_RUN은 설치 전 빌드 단계 원본이며 이후 실행 결과는 위 세 실행과 device-run에 기록돼 있습니다. 명세의 source hash는 로컬 원본, sha256은 공개 사본입니다. 로컬 경로·시험 호스트명만 익명화한 항목은 transformation에 명시했습니다. 원 자체 artifact-sha256은 원본 기준이며 공개 사본의 해시를 대신하지 않습니다.

영상은78.689944초·2560×1440·52,174,992바이트·audio0입니다. [첫 기본 null-muxer 진단](flow-decode.log)과 [원 demux 시간축 검사](flow-decode-demux.log)를 보존했습니다. 후자는 exit0/오류0이며 무음 전체 프레임 decode만 확인합니다. 최대0.32G는 합성 입력의 표시값으로 실차 정확도·이전영상 대비 개선을 뜻하지 않습니다.

기록 조회의 선택 SDK 로그 부재는 시험 창의 관측입니다. 모든 차량 버스 메시지·모든 중단 시점·사람의 이해를 보장하지 않습니다. 현장 데이터는0건이며 과거 버전의 음향 실패/미확인 결과를 이번 기능시험으로 대체하지 않습니다.
