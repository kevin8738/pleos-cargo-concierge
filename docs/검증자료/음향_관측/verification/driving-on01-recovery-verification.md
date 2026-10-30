# ON 별도 복구 검수

저장 자료 12개 대조에서 불일치 0건. 별도 시험자 복구를 확인하며 원 ON result=false·cleanup=false는 유지한다.

원 recovery.json의 false는 영문 제목을 기대한 UI parser 때문이었다. 저장 idle.xml의 한국어 `VIP 조수석`, 고유 초기 질문·프리셋·P·0 km/h·내비 대기와 SHA를 대조해 새 recovery-confirmed.json의 판정 수정이 타당함을 확인했다. 원 recovery 및 기존 10항목 manifest는 그대로다. 추가 확인 JSON은 그 manifest 이후 생성됐으므로 별도 SHA로 고정했다.

전후 동일 APK/PID, 빈 MotionTrace 원값 기록, 원설정 세 스위치, Console 네 영역의 원값, Park/0, 서비스·알림 없음이 일치했다. MotionTrace 빈값은 recovery JSON의 직접 저장 값이며 별도 shell stdout 파일은 없다. 이는 시험자 복구·재조회이고 앱의 자동 복구나 최초 cleanup 성공이 아니다. ON 관측을 재실행 없이 보존하고 OFF를 별도로 연결하는 판단을 유지한다.
