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

// ─────────────────────────────────────────────────────────────────────────────
// v2 "실사형 에셋" — 렌더링·에셋 상수. (위의 운전·한계 상수는 v1.1 과 한 글자도 다르지 않다)
// ─────────────────────────────────────────────────────────────────────────────

/** 에셋 경로 (index.html 기준 상대 경로). 저장소에서는 docs/sim/assets/ 로 함께 복사된다 */
export const ASSET_ROOT = 'assets/';
export const DRACO_PATH = 'https://cdn.jsdelivr.net/npm/three@0.160.0/examples/jsm/libs/draco/gltf/';
export const HDRI_PATH = 'polyhaven/hdris/dresden_station_night_2k.hdr';
/** 디렉터리 목록(python http.server 등)이 없는 정적 호스트에서, manifest 에 없는 생성 GLB 를 HEAD 로 찾아볼지.
 *  true = GLB 를 넣기만 하면 보인다(대신 없는 파일마다 콘솔에 404 1줄). build_manifest.py 를 다시 돌리면 탐색이 필요 없다 */
export const PROBE_GENERATED = false;

/** 이미지→3D 로 만들 예정인 생성 에셋 id (assets/glb/<id>.glb). 없으면 각자 대체물로 폴백 */
export const GENERATED_IDS = [
  'sedan', 'sedan_body', 'car_seat', 'steering_wheel', 'dashboard',
  'cake_box', 'tteokbokki_bag', 'bouquet', 'grocery_bag', 'coffee_carrier', 'pet_carrier',
  'watermelon_net', 'laptop_box', 'pottery_vase', 'cake_slice_box',
];

/** 실제 크기 표 (m). 생성 GLB 는 보통 단위 정규화돼 나오므로, manifest 에 실측 치수(real_size_m)가 없거나
 *  size_m 이 이 값과 2배 이상 다르면 이 표로 자동 스케일한다. axis: 'len' 수평 최장 변, 'h' 높이, 'max' 3축 최장 */
export const REAL_SIZE = {
  sedan: { axis: 'len', m: 4.99 },          // 4.99 × 1.88 × 1.46 m 대형 세단
  sedan_body: { axis: 'len', m: 4.99 },     // 바퀴 제거 차체(파트 노드), 실측 저장
  car_seat: { axis: 'h', m: 0.98 },        // 바닥(0.33)부터 헤드레스트 꼭대기까지 — 지붕 안쪽(1.40) 아래
  steering_wheel: { axis: 'max', m: 0.38 },
  dashboard: { axis: 'len', m: 1.46 },      // A필러 사이 실내 폭에 맞춤 (생성 차체 실측 1.46 m)
  cake_box: { axis: 'len', m: 0.25 },       // 스펙: 케이크 상자 한 변 0.25 m
  cake_slice_box: { axis: 'len', m: 0.14 },
  tteokbokki_bag: { axis: 'h', m: 0.32 },
  bouquet: { axis: 'max', m: 0.55 },
  grocery_bag: { axis: 'h', m: 0.40 },
  coffee_carrier: { axis: 'len', m: 0.25 },
  pet_carrier: { axis: 'len', m: 0.48 },
  watermelon_net: { axis: 'max', m: 0.30 },
  laptop_box: { axis: 'len', m: 0.42 },
  pottery_vase: { axis: 'h', m: 0.34 },
  // Poly Haven 소품을 VIP 로 쓸 때 시트 위 크기 (원본이 실측이라도 소품 성격상 축소가 필요한 것만)
  potted_plant_01: { axis: 'h', m: 0.48 },
};

/** VIP 분류 → 후보 목록 (앞에서부터 있는 것을 쓴다). g: 생성 GLB, ph: Poly Haven, proc: 절제된 프로시저럴 */
export const VIP_ASSETS = {
  cake: ['g:cake_box', 'g:cake_slice_box', 'proc:cake_box'],
  hot: ['g:tteokbokki_bag', 'proc:paper_bag'],
  flower: ['g:bouquet', 'ph:potted_plant_01'],
  fresh: ['g:grocery_bag', 'g:watermelon_net', 'ph:wicker_basket_01'],
  soup: ['g:coffee_carrier', 'proc:coffee_carrier'],
  fragile: ['g:pottery_vase', 'ph:ceramic_vase_01'],
  device: ['g:laptop_box', 'ph:classic_laptop'],
  pet: ['g:pet_carrier', 'proc:pet_carrier'],
  luggage: ['ph:cardboard_box_01'],
};

/** 세단 치수 (차 로컬 +X = 앞, +Z = 조수석). 축거 2.9 m 는 물리와 같다 */
export const SEDAN = { L: 4.99, W: 1.88, H: 1.46, nose: 2.45, tail: -2.54, axle: 1.45, track: 0.81 };

/** 도로 단면 (주행선 기준 좌우 오프셋, m). 7 m 2차로 + 0.25 m 측구, 연석 0.15 m, 보도 2.5 m */
export const ROAD = {
  y: 0.02, edgeR: -1.75, edgeL: 5.25, curbH: 0.15, curbW: 0.2, walk: 2.5,
  lampGap: 30, treeGap: 15,  // 가로등 30 m(양쪽 엇갈림), 가로수 15 m
};

/** 렌더링 품질 (스펙 6) */
export const RENDER = {
  exposure: 0.9, fog: 0.004, shadowMap: 2048,
  bloom: { threshold: 0.9, strength: 0.35, radius: 0.4 },
  lampSpots: 6, lampShadows: 2,  // 실제 SpotLight 는 카메라 근처 6개, 그중 가까운 2개만 그림자
  nearTrees: 14, nearTreeDist: 75, // 3D 가로수 (나머지는 같은 모델로 구운 임포스터)
  chunk: 60, chunkCull: 320,    // 건물·가로 시설 청크 길이 (m), 이보다 먼 청크는 숨김 (안개 + 원경 실루엣이 대신)
  targetFps: 55,                // 이보다 낮게 3초 지속되면 품질 단계를 하나 내린다 (블룸 → 유리 투과 → 해상도)
};
