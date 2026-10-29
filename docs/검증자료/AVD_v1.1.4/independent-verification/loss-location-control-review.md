# runtime06 종료·위치·공조 최종 자료 검수

판정: **PASS_WITH_SCOPE_LIMITATIONS**. 종료 자료 48/48, 위치 자료 66/66, 공조 `avd-03` 자료 42/42 대조 일치. ADB·AVD·빌드·생산 코드 수정 없이 보관된 자료만 읽었다. 모든 새 실행의 설치 SHA는 runtime06 `8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c`이다.

## 강제 종료와 task 제거

`runtime-force-stop-02`는 18.212755초 관측 후 수동 재실행까지 자동 ready·가드 상태·관측 성공 쓰기 0이다. 1/8/18초 dump에서 PID·서비스·알림이 없고, 수동 재실행은 PID 26822→25541로 바뀐 Idle이다.

`runtime-shell-task-removal-01`은 user10의 VIP 단독 root/task 1000400을 제거했다. 직전 inventory는 0.773166ms 전이며 raw stack에도 소유 component가 일치한다. 이후 1/8/18초 raw stack에서 VIP task가 없어졌고 matching session의 `lost` 한 번, 서비스·알림 소멸을 확인했다. 18.327456초 뒤 수동 재실행은 PID25541→8300이며 자동 가드는 없다. **onTaskRemoved 직접 marker나 Recents swipe 성공을 검증한 것은 아니다.** runtime04 `runtime-task-removal-01`의 Recents 제어 불가 실패는 보존한다.

두 실행은 캐빈 변경 0인 COFFEE에서 관측했다. 필터 로그와 읽기값 보존은 모든 차량 버스 쓰기 부재나 진행 중인 실제 명령 취소 증명이 아니다. 종료 후 자동 복원 일반화도 하지 않는다. 음향 설정과 외부 fixture 읽기값 복원은 일치한다.

## 위치와 재연결

| 자료 | 직접 확인한 구간·결과 |
|---|---|
| location-on-03 | COFFEE 요청→cleanup End 11.378216초, subscription active1, FGS type `0x40000008`. 첫 foreground dump0.288312초와 첫 알림11.336514초를 분리한다. |
| location-off-02 | 같은 기준1.423278초, active0, FGS type `0x40000000`. cleanup이 위치 ON을 복원한 뒤의 active는 이 가드 구간에 포함하지 않는다. |
| location-active-off-02 | OFF 확인→ON 요청4.004521초. OFF 후54.508ms에 stopped. 다시 ON 확인→End5.054423초 동안 active0, 같은 session tick5. finished 뒤6.83ms의 active는 종료된 뒤 visible Idle lease다. |
| location-reconnect-02 | 복귀→snapshot1.291836초/1.428383초. 각각 AOSP/Navi/combined setup 로그1개. 저장 시점 trace12/12개이며, snapshot까지 넓혀 세면14/13개다. 모든 관측 trace는 Idle이다. |

권한 granted/flags·COARSE/FINE app-op·system location 및 로그 속성의 before/after JSON이 모두 일치한다. retained cabin subset 및 XML 음향 toggle도 같다. 이번 위치 자료의 차량 snapshot은 전체 상태가 아닌 보존한 subset이므로 gear를 포함한 전체 차 상태 동일성으로 확대하지 않는다. 검수기에서도 해당 subset 구조로 대조했다.

실제 GPS fix 또는 cache 값은 주입·기록하지 않았다. 재연결 setup 로그와 speed trace는 실제 fresh gear/Pleos getter나 네이티브 경로 callback 검증이 아니다. 관측 파일은 선택해 저장한 구조화 stream이며 전체 raw log는 별도로 없다. host 시각 보정 자료가 없어 표시된 간격은 host 수신 기준이다.

## 최종 공조 avd-03

manifest 63개 파일의 해시와 누락 여부를 검산했다. 10개 통합 판정이 true이며, 주요 Console 설정값·SDK getter·명령 결과·UI가 일치한다. 후보 source manifest21개도 현재 파일과 같다. runtime05와 공통 manifest 중 바뀐 파일은 CargoSession뿐이며, source 구분 motion cache·2초 freshness 관련 파일은 그대로다.

- C3: 조수석22→18°C, 운전석22 유지. SDK22 읽기→명령 성공→SDK18 읽기→VALUE_CONFIRMED/APPLIED 순서.
- C4: 적용 뒤 현재18 읽기→명령 성공→원래22 읽기→restore APPLIED. 양쪽22.
- C9: SYNC ON/양쪽24 유지, apply FAILED, 온도 명령 성공 없음. C9B에서 외부 SYNC ON 복원을 보류하고18 유지, C9C의 명시적 재시도에서는 현재18→명령→원래22 읽기 뒤 APPLIED(운전석18 유지).
- C10/C11: Drive에서 복원 DRIVING_RESTRICTED 및18 유지, P 정차 후 재시도에서22 읽기 확인 뒤 APPLIED.
- C12: PID9676→9768 재시작, 초기 SDK18·Console18 유지, Idle에서 이전 공조 복원 미확인 안내. 보관 로그에 자동 apply/restore·온도 명령 성공·가드 시작 없음.
- 착좌값2/비착좌 확인 불가 차단, 외부21 보존, 비착좌 확인 상실 복원, 전원OFF 설정값18 및 전원 꺼짐 안내, SYNC OFF driver24 유지도 일치한다.

공조의 뜻은 공식 API34 AVD/Vehicle Service2.3.22에서 **설정값**을 확인했다는 것이다. 실내·짐 온도 또는 냉각 효과는 측정하지 않았다. C8/C9 End 후 driver/SYNC는 harness assertion은 있으나 별도 after-End checkpoint가 없어 독립 raw 대조는 before/apply까지다. C12는 한 번의 재시작 checkpoint·필터 로그 범위다. 정확한 지연을 위한 입력 장부·시계 보정은 없다. tester cleanup은 외부 도구의 fixture 복구로, 앱의 C4/C9C/C11 복원과 구분한다.

runtime04 C3 최신 차속 불명/실제22 실패 및 runtime05 이후 ANR은 그대로 남아 있다. 새 통과 결과로 이전 실패를 덮어쓰지 않았다. 검수기 C1 문자열은 실제 로그의 `BLOCKED_BY_OCCUPANCY / 조수석 비착좌 확인 불가` 및 getter2로 검사한다. 생산 로그에 영문 UNKNOWN이 반드시 있다는 가정은 제거했다.

재현 스크립트와 결과: `verify_loss_location.py` → `loss-verification.json`, `location-verification.json`; `verify_control.py` → `control-verification.json`.
