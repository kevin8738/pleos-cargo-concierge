/**
 * config.js — 시뮬레이션 전역 상수.
 * 물리식·앱 규칙 값은 앱(core/physics/GEstimator.kt, 코칭 상태머신)과 같은 숫자를 쓴다.
 */
export const G = 9.80665;             // 표준 중력가속도 (m/s²)
export const DEG = Math.PI / 180;     // 도 → 라디안
export const STEER_RATIO = 15;        // 조향비 15:1 (핸들각 → 바퀴각)
export const WHEELBASE = 2.9;         // 축거 L (m)
export const EMA_ALPHA = 0.5;         // G 추정값 EMA 계수
export const PHYS_DT = 1 / 60;        // 고정 물리 스텝 (s) — 프레임레이트와 무관한 결정적 결과
export const MAX_STEPS_PER_FRAME = 16; // 탭 지연 시 따라잡기 상한

/** 차량 운동 한계 (스펙: 가속 2.5, 제동 4, 급제동 8 m/s², 핸들 180°/s) */
export const CAR = {
  maxAccel: 2.5, brake: 4, hardBrake: 8,
  coastDecel: 0.3,                     // 페달을 모두 뗐을 때 구름저항 감속 (단순화)
  steerRate: 180, maxSteer: 450,       // 핸들 최대 회전 속도 (°/s), 최대 각 (°)
  wheelRadius: 0.34,                   // 바퀴 반지름 (m)
};

/** 케이크 상자 손상·밀림 모델 (단순화) */
export const CARGO = {
  damageRate: 400,                     // 초과 G × 초 당 손상 %
  slideG: 0.30,                        // 시트-상자 마찰 한계: 이 G를 넘을 때만 밀림
  slideMax: 0.08, slideRate: 0.5,      // 최대 밀림 (m), 초과 G 1당 밀림 속도 (m/s)
  tiltMax: 0.14, tiltGain: 1.2,        // 최대 기울기 (rad, 약 8°), 초과 G → 기울기 (rad/G)
};

/** 앱 상태머신 규칙 */
export const APP = {
  nearRatio: 0.8, overRatio: 1.0,
  nearRearmSec: 1.0,     // OK 상태가 이만큼 유지돼야 NEAR 효과음 재무장 (떨림 방지)
  coachMax: 3, coachGapSec: 4,         // 주행당 음성 코칭 상한, 코칭 사이 최소 간격 (s)
  urgentG: 0.6,          // 긴급 조작: 이 이상이면 말하지 않음
  parkingKmh: 15, parkingSteerDeg: 30, // 저속 선회(주차) 판정
  noticeDist: 100, noticeRoundKmh: 5,  // 급커브 사전 안내 거리 (m), 권장 속도 표기 단위
  bubbleSec: 5,          // 말풍선 표시 시간 (s)
};

/** 코스: 정삼각형 모서리(외각 120°)를 R60 → R120 → R30 원호로 둥글린 닫힌 루프 */
export const TRACK = {
  side: 563,             // 삼각형 한 변 (m) → 1바퀴 약 1.40 km
  startOffset: 80,       // R30 커브 출구 ~ 출발점(베이커리) 거리 (m)
  parkBeforeEnd: 25,     // 주차장 = 출발점 25 m 전
  parkZone: 10,          // 주차 인정 반경 (m)
  sampleStep: 0.5,       // 경로 샘플 간격 (m)
  roadRight: -2.0, roadLeft: 5.5, // 주행선(오른쪽 차로 중앙) 기준 노면 좌우 끝 (m)
  centerLine: 1.75,      // 차로 구분선 위치 (m)
  corners: [{ R: 60 }, { R: 120 }, { R: 30, sharp: true }],
};

/** VIP 분류별 한계 G (앱과 동일). noun은 도착 안내 문장에 쓰인다 */
export const CATEGORIES = [
  { id: 'cake', label: '케이크·디저트', limit: 0.15, noun: '케이크' },
  { id: 'hot', label: '뜨거운 음식', limit: 0.12, noun: '음식' },
  { id: 'flower', label: '꽃·식물', limit: 0.20, noun: '꽃' },
  { id: 'fresh', label: '신선식품', limit: 0.15, noun: '신선식품' },
  { id: 'soup', label: '음료·국물', limit: 0.12, noun: '음료' },
  { id: 'fragile', label: '깨지기 쉬운 물건', limit: 0.12, noun: '물건' },
  { id: 'device', label: '전자기기·악기', limit: 0.25, noun: '기기' },
  { id: 'pet', label: '반려동물', limit: 0.20, noun: '반려동물' },
  { id: 'luggage', label: '일반 짐', limit: 0.20, noun: '짐' },
];

/** 주행 모드 (v1.1). A: 가속 1.4(0.15 G 바로 아래)·제동 3.0·코너 0.40 G. B: 선택 분류 한계의 75 % 코너, 가감속 ≤ 1.0 m/s² */
export const MODES = {
  A: { auto: true, latG: 0.40, accel: 1.4, brake: 3.0, vmax: 60 / 3.6 },
  B: { auto: true, latFactor: 0.75, accelMax: 1.0, accelFactor: 0.7, budgetFactor: 0.78, vmax: 60 / 3.6 },
  C: { auto: false },
};

/** 자동 운전자 튜닝 */
export const DRIVER = {
  kp: 2.5,            // 속도 오차 → 가속 명령 이득
  previewSec: 0.4,    // 속도 프로파일 미리보기 시간
  profileBrake: 0.85, // 프로파일은 제동 한계의 85 %로 짜고, 남는 15 %는 P 제어 지연 보정에 쓴다
  cornerMargin: 10,   // 커브 속도를 원호 시작 이만큼 전부터 적용 (m)
  creep: 1.0, creepFrom: 0.5, stopAt: 0.3, // 주차 지점 0.5 m 전까지 최소 1 m/s 서행, 0.3 m 전부터 정지 제동
  minBrake: 0.3,      // 합성 G 예산이 0 이어도 남기는 최소 제동 (m/s²)
  ldBase: 3, ldGain: 0.45, ldMin: 5, ldMax: 12, // 순수추종 전방주시 거리 (m)
};

/** 앱 컬러 */
export const COLORS = { bg: '#0B0F14', mint: '#4FD1C5', champ: '#F2D18B', red: '#F56565', text: '#E6EDF3', dim: '#8B98A5' };
export const FONT = '"Apple SD Gothic Neo", "Malgun Gothic", "Noto Sans KR", sans-serif';
