/**
 * main.js — 진입점. 렌더러·카메라·입력·고정 스텝 루프·모드 진행(비교 시퀀스)을 묶는다.
 */
import * as THREE from 'three';
import { PHYS_DT, MAX_STEPS_PER_FRAME, CAR, CATEGORIES, MODES, TRACK, COLORS, APP } from './config.js';
import { Track } from './track.js';
import { Vehicle, GEstimator } from './physics.js';
import { AutoDriver } from './driver.js';
import { AppMonitor } from './monitor.js';
import { buildWorld } from './world.js';
import { CarModel, CABIN_VIEW } from './car.js';
import { Hud, fmtTime, modeLabel } from './hud.js';

const CHASE = { back: 12.5, up: 5.5, look: 30, lift: 0, follow: 2.5, fov: 55 }; // 체이스 캠: 뒤·위 오프셋, 주시점(m), 추종 속도(1/s)
const MAP_M_PER_G = 120;      // 지도 G 화살표 길이 (m / G)
const MAP_PAD = { top: 12, bottom: 285, margin: 40 }; // 지도: 화면 위·아래(하단 패널) 여백 px, 코스 둘레 여백 m
const ARROW_HEAD = 14;        // 지도 화살촉 길이 (m)
const COMPARE_PAUSE = 3;      // 비교: A 도착 후 B 출발까지 (s)
const PARK_HOLD = 1.0;        // 직접 운전: 주차 구역 정지 후 자동 P (s)
const PARK_MIN_LAP = 0.8;     // 랩의 80 % 이상 달린 뒤에만 주차 인정
const STOP_V = 0.05;          // 정지 판정 속도 (m/s)
const DISPLAY_HZ = 10;        // 3D 센터 디스플레이 갱신 주기
const KEYMAP = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Space: 'space' };

// ---------- 장면·렌더러 ----------
const renderer = new THREE.WebGLRenderer({ canvas: document.getElementById('scene'), antialias: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.15;
const scene = new THREE.Scene(), track = new Track(), car = new CarModel();
const world = buildWorld(scene, track);
scene.add(car.group);

const chaseCam = new THREE.PerspectiveCamera(CHASE.fov, 1, 0.3, 4000);
const cabinCam = new THREE.PerspectiveCamera(CABIN_VIEW.fov, 1, 0.05, 4000);
const mapCam = new THREE.OrthographicCamera(-1, 1, 1, -1, 1, 3000);
mapCam.layers.enable(1);           // 레이어 1 = 지도 전용 표시
mapCam.up.set(0, 0, -1);           // 화면 위 = 수학 좌표 +y (위치는 resize 에서)
const overlay = buildMapOverlay();
const chaseLook = new THREE.Vector3();   // 체이스 캠 주시점 (부드럽게 따라감)

// ---------- 시뮬레이션 상태 ----------
const veh = new Vehicle(), gEst = new GEstimator(), driver = new AutoDriver(track), monitor = new AppMonitor(track);
const state = {
  mode: 'A', cat: CATEGORIES[0], cam: 1, speed: 1, running: false,
  gear: 'D', sLap: 0, sHint: 0, parkHold: 0, compare: null, results: {}, keys: {}, snap: true,
};
const hud = new Hud({
  keys: state.keys,
  onMode: (k) => startMode(k), onCategory: (id) => setCategory(id),
  onCompare: () => startCompare(), onReset: () => startMode(state.mode), onCam: (n) => setCam(n),
  onSpeed: (n) => { state.speed = n; hud.setActive('speed', n); },
  onCardClose: () => { if (!state.running) startMode(state.mode); },
});

/** 지도 전용 오버레이(레이어 1): 차 표시, G 벡터 화살표, 한계 G 원 */
function buildMapOverlay() {
  const mk = (geo, color) => {
    const m = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ color, depthTest: false, transparent: true, side: THREE.DoubleSide }));
    m.layers.set(1); m.renderOrder = 10; scene.add(m);
    return m;
  };
  const shape = (pts) => new THREE.ShapeGeometry(new THREE.Shape(pts.map(([x, y]) => new THREE.Vector2(x, y)))).rotateX(-Math.PI / 2);
  return {
    marker: mk(shape([[14, 0], [-9, 8], [-5, 0], [-9, -8]]), COLORS.mint),
    ring: mk(new THREE.RingGeometry(0.96, 1, 64).rotateX(-Math.PI / 2), COLORS.mint),
    shaft: mk(new THREE.PlaneGeometry(1, 5).translate(0.5, 0, 0).rotateX(-Math.PI / 2), COLORS.champ),
    head: mk(shape([[ARROW_HEAD, 0], [0, 8], [0, -8]]), COLORS.champ),
  };
}

/** 현재 모드·분류로 주행을 처음 상태로 되돌린다 */
function resetRun() {
  const p = track.sampleAt(0);
  veh.reset(p.x, p.y, p.h);
  gEst.reset();
  monitor.reset(state.cat);
  driver.configure(state.mode, state.cat.limit);
  car.resetCargo();
  Object.assign(state, { gear: 'D', sLap: 0, sHint: 0, parkHold: 0, snap: true });
  hud.clearLog(); hud.quiet();
  hud.log(`${modeLabel(state.mode, state.cat)} 출발 · 한계 ${state.cat.limit.toFixed(2)} G`, 0);
}

/** 모드 시작 (keepCompare 가 아니면 비교 시퀀스 취소) */
function startMode(key, keepCompare = false) {
  if (!keepCompare) state.compare = null;
  Object.assign(state, { mode: key, running: true });
  hud.setMode(key, state.cat);
  hud.hideCard();
  resetRun();
}

/** "비교 시작": A → (대기) → B 순서로 자동 실행 */
function startCompare() {
  state.compare = { stage: 'A', wait: -1 };
  startMode('A', true);
}

/** 분류 변경: 한계 G가 바뀌므로 지난 결과를 지우고 다시 시작 */
function setCategory(id) {
  state.cat = CATEGORIES.find((c) => c.id === id) ?? CATEGORIES[0];
  state.results = {};
  hud.setCategory(state.cat);
  startMode(state.mode);
}

/** 카메라 전환. 지도에서는 안개를 끈다 */
function setCam(n) {
  state.cam = n;
  scene.fog = n === 3 ? null : world.fog;
  hud.setActive('cam', n);
}

/** 직접 운전 입력 → 핸들 목표각(손 놓으면 0으로 복원)·종가속 */
function manualControl() {
  const k = state.keys;
  const steer = k.left && !k.right ? CAR.maxSteer : k.right && !k.left ? -CAR.maxSteer : 0;
  let accel = -CAR.coastDecel;
  if (k.up) accel = CAR.maxAccel;
  if (k.down) accel = -CAR.brake;
  if (k.space) accel = -CAR.hardBrake;
  return { steer, accel };
}

/** 고정 스텝 1회: 조작 → 차량 → G 추정 → 진행 거리 → 앱 로직 → 상자 → 도착 판정 */
function step(dt) {
  const cmp = state.compare;
  if (cmp && cmp.wait >= 0) {
    if ((cmp.wait -= dt) < 0) { cmp.stage = 'B'; startMode('B', true); }
    return;
  }
  if (state.gear === 'P') return;
  const ctl = MODES[state.mode].auto ? driver.control(veh, state.sLap, gEst.lat) : manualControl();
  veh.steerToward(ctl.steer, dt);
  veh.step(ctl.accel, dt);
  gEst.update(veh.v, veh.steer, veh.aLon);
  const near = track.nearest(veh.x, veh.y, state.sHint), L = track.length;
  state.sLap += ((near.s - state.sHint + 1.5 * L) % L) - L / 2; // 감싼 거리 차이
  state.sHint = near.s;
  const events = monitor.update({ dt, latG: gEst.latG, lonG: gEst.lonG, totalG: gEst.total, v: veh.v, steer: veh.steer, sLap: state.sLap });
  events.forEach(handleEvent);
  car.updateCargo(gEst.latG, gEst.lonG, gEst.total, dt);
  state.parkHold = inParkZone() ? state.parkHold + dt : 0;
  if (state.parkHold > (MODES[state.mode].auto ? 0 : PARK_HOLD)) arrive();
}

/** 앱 로직 이벤트 → 디스플레이 말풍선·비프·로그 */
function handleEvent(e) {
  const t = monitor.lapTime;
  if (e.kind === 'near') { hud.beep(); hud.log(`NEAR 진입 · ${e.text} (효과음)`, t); }
  else if (e.kind === 'coach') { hud.say(e.text, 'coach'); hud.log(`음성 코칭 ${monitor.coachCount}/${APP.coachMax} · ${e.text}`, t); }
  else if (e.kind === 'notice') { hud.say(e.text, 'notice'); hud.log(`사전 안내 · ${e.detail}`, t); }
  else hud.log(e.text, t);
}

/** 주차 구역(랩 80 % 이후, 주차 지점 ±10 m)에서 정지했는가 */
function inParkZone() {
  return state.sLap > track.length * PARK_MIN_LAP && veh.v < STOP_V
    && Math.abs(track.wrap(state.sLap) - track.parkS) < TRACK.parkZone;
}

/** 도착(기어 P): 안내 문장, 리포트 저장, 비교 진행 또는 카드 표시 */
function arrive() {
  state.gear = 'P';
  gEst.reset();                       // 정지: 추정 G = 0
  const r = monitor.arrive(state.mode);
  hud.say(`조수석 ${state.cat.noun} 챙기세요`, 'arrive', true);
  hud.log(`주차장 도착 (P) · 랩타임 ${fmtTime(r.lapTime)}`, r.lapTime);
  if (state.mode !== 'C') state.results[state.mode] = r;
  const { A, B } = state.results;
  if (state.compare?.stage === 'A') {
    state.compare.wait = COMPARE_PAUSE;
    hud.log(`${COMPARE_PAUSE}초 뒤 B(${state.cat.noun} 기준) 출발`, r.lapTime);
  } else if (state.mode !== 'C' && A && B) { state.compare = null; hud.showCompare(A, B, startCompare); }
  else hud.showReport(r, modeLabel(state.mode, state.cat), () => startMode(state.mode));
}

/** 카메라 위치 갱신 (체이스: 위치·주시점 모두 부드럽게 늦게 따라감 → 차는 화면 아래 1/3, 캐빈: 차에 고정) */
function updateCameras(dt) {
  const p = car.group.position, fx = Math.cos(veh.h), fz = -Math.sin(veh.h);
  const want = new THREE.Vector3(p.x - fx * CHASE.back, p.y + CHASE.up, p.z - fz * CHASE.back);
  const look = new THREE.Vector3(p.x + fx * CHASE.look, p.y + CHASE.lift, p.z + fz * CHASE.look);
  const k = Math.min(1, dt * CHASE.follow * state.speed);
  if (state.snap) { chaseCam.position.copy(want); chaseLook.copy(look); state.snap = false; }
  else { chaseCam.position.lerp(want, k); chaseLook.lerp(look, Math.min(1, 2 * k)); }
  chaseCam.lookAt(chaseLook);
  car.group.updateMatrixWorld();
  cabinCam.position.copy(CABIN_VIEW.pos).applyMatrix4(car.group.matrixWorld);
  cabinCam.lookAt(CABIN_VIEW.look.clone().applyMatrix4(car.group.matrixWorld));
}

/** 지도 오버레이: 차 위치·방향, 현재 G 벡터(차 기준 종·횡 → 월드), 한계 G 원 */
function updateOverlay() {
  if (state.cam !== 3) return;
  const p = car.group.position, h = veh.h, o = overlay;
  o.marker.position.set(p.x, 2, p.z); o.marker.rotation.y = h;
  o.ring.position.set(p.x, 1, p.z); o.ring.scale.setScalar(state.cat.limit * MAP_M_PER_G);
  const wx = Math.cos(h) * gEst.lonG - Math.sin(h) * gEst.latG, wy = Math.sin(h) * gEst.lonG + Math.cos(h) * gEst.latG;
  const len = Math.hypot(wx, wy) * MAP_M_PER_G, head = Math.min(len, ARROW_HEAD), body = len - head, ang = Math.atan2(wy, wx);
  const col = monitor.badge === 'OVER' ? COLORS.red : monitor.badge === 'NEAR' ? COLORS.champ : COLORS.mint;
  o.shaft.visible = o.head.visible = len > 2;
  for (const m of [o.shaft, o.head]) { m.material.color.set(col); m.rotation.y = ang; }
  o.shaft.position.set(p.x, 3, p.z);
  o.shaft.scale.x = Math.max(0.01, body);
  o.head.position.set(p.x + Math.cos(ang) * body, 3, p.z - Math.sin(ang) * body);
  o.head.scale.setScalar(head / ARROW_HEAD);
}

/** 창 크기 변경: 원근 카메라 비율, 지도는 하단 패널 위 빈 영역에 코스 전체가 들어오게 */
function resize() {
  const w = innerWidth, h = innerHeight, b = track.bounds, m = 2 * MAP_PAD.margin;
  renderer.setSize(w, h);
  for (const c of [chaseCam, cabinCam]) { c.aspect = w / h; c.updateProjectionMatrix(); }
  const availH = Math.max(h * 0.55, h - MAP_PAD.top - MAP_PAD.bottom);
  const scale = Math.min(availH / (b.maxY - b.minY + m), (w * 0.92) / (b.maxX - b.minX + m)); // px/m
  const offset = (h / 2 - (MAP_PAD.top + availH / 2)) / scale;   // 코스를 화면 위쪽으로 올리는 양 (m)
  Object.assign(mapCam, { left: -w / 2 / scale, right: w / 2 / scale, top: h / 2 / scale, bottom: -h / 2 / scale });
  mapCam.position.set(0, 1000, offset);
  mapCam.lookAt(0, 0, offset);
  mapCam.updateProjectionMatrix();
}

// ---------- 입력 ----------
addEventListener('keydown', (e) => {
  if (e.target.tagName === 'SELECT') return;
  const k = KEYMAP[e.code];
  if (k) { e.preventDefault(); state.keys[k] = true; return; }
  if (e.repeat) return;
  if (e.code === 'Escape') hud.closeCard();
  else if (e.code === 'KeyR') startMode(state.mode);
  else if (e.code === 'KeyH') hud.toggle('panel-top');           // 영상 캡처용: 조작 패널만 숨김
  else if (e.code === 'KeyF') hud.toggle('fps');
  else if (['Digit1', 'Digit2', 'Digit3'].includes(e.code)) setCam(+e.code.slice(5));
  else if (e.code === 'KeyP' && state.mode === 'C' && state.gear === 'D') {
    if (inParkZone()) arrive();
    else hud.log('주차장 구역에서 멈춘 뒤 P를 누르세요', monitor.lapTime);
  }
});
addEventListener('keyup', (e) => { if (KEYMAP[e.code]) { e.preventDefault(); state.keys[KEYMAP[e.code]] = false; } });
addEventListener('blur', () => { for (const k of Object.keys(state.keys)) state.keys[k] = false; });
addEventListener('resize', resize);

// ---------- 루프 ----------
const clock = new THREE.Clock();
let acc = 0, dispTimer = 0;

/** 프레임마다: 고정 스텝 물리 → 모델·카메라·HUD → 렌더 */
function frame() {
  requestAnimationFrame(frame);
  const dt = Math.min(0.1, clock.getDelta());
  if (state.running) {
    acc += dt * state.speed;
    let n = 0;
    while (acc >= PHYS_DT && n < MAX_STEPS_PER_FRAME) { step(PHYS_DT); acc -= PHYS_DT; n++; }
    if (n === MAX_STEPS_PER_FRAME) acc = 0;
  }
  car.update(veh);
  car.setDamage(monitor.damage / 100);
  updateCameras(dt);
  updateOverlay();
  if ((dispTimer -= dt) <= 0) {
    dispTimer = 1 / DISPLAY_HZ;
    car.drawDisplay(monitor.badge, gEst.total, state.cat.limit, hud.bubble);
  }
  hud.telemetry({
    v: veh.v, steer: veh.steer, latG: gEst.latG, lonG: gEst.lonG, totalG: gEst.total, limit: state.cat.limit, gear: state.gear,
    maxG: monitor.maxG, damage: monitor.damage, coach: monitor.coachCount, lapTime: monitor.lapTime, badge: monitor.badge,
  }, dt);
  hud.tick(dt);
  hud.fps();
  renderer.render(scene, state.cam === 2 ? cabinCam : state.cam === 3 ? mapCam : chaseCam);
}

// ---------- 시작 ----------
resize();
hud.setCategory(state.cat);
hud.setMode(state.mode, state.cat);
hud.setActive('cam', 1); hud.setActive('speed', 1);
resetRun();
hud.showIntro(startCompare);
frame();
