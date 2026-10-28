/**
 * main.js (v2) — 진입점. 에셋 로딩(진행률) → 실사형 월드·차 → 렌더러(ACES·그림자·약한 블룸)·카메라·입력·고정 스텝 루프·비교 시퀀스.
 */
import * as THREE from 'three';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';
import { PHYS_DT, MAX_STEPS_PER_FRAME, CAR, CATEGORIES, MODES, TRACK, COLORS, APP, RENDER, GENERATED_IDS } from './config.js';
import { Track } from './track.js';
import { Vehicle, GEstimator } from './physics.js';
import { AutoDriver } from './driver.js';
import { AppMonitor } from './monitor.js';
import { AssetLibrary } from './assets.js';
import { buildWorld } from './world.js';
import { CarModel, CABIN_VIEW } from './car.js';
import { Hud, fmtTime, modeLabel } from './hud.js';

const CHASE = { back: 12.5, up: 5.5, look: 30, lift: 0, follow: 2.5, fov: 55 };
const MAP_M_PER_G = 120, MAP_PAD = { top: 12, bottom: 285, margin: 40 }, ARROW_HEAD = 14;
const COMPARE_PAUSE = 3, PARK_HOLD = 1.0, PARK_MIN_LAP = 0.8, STOP_V = 0.05, DISPLAY_HZ = 10;
const KEYMAP = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Space: 'space' };
/** world.js 가 쓰는 PBR 세트·Poly Haven 모델 */
const PBR_SETS = ['asphalt_02', 'asphalt_04', 'road_damaged_clean', 'brushed_concrete', 'concrete_floor_02', 'rectangular_paving', 'brick_4', 'painted_metal_shutter', 'fabric_leather_01'];
const WORLD_MODELS = ['street_lamp_01', 'street_lamp_02', 'modular_urban_apartments_facade', 'modular_factory_facade', 'modular_electricity_poles',
  'modular_street_seating', 'korean_public_payphone_01', 'fire_hydrant', 'utility_box_01', 'concrete_road_barrier', 'water_manhole_cover', 'trashbag',
  'standing_chalkboard_01', 'outdoor_table_chair_set_01', 'croissant', 'hamburger_buns', 'strawberry_chocolate_cake', 'carrot_cake', 'potted_plant_01',
  'cardboard_box_01', 'ceramic_vase_01', 'classic_laptop', 'wicker_basket_01'];
const TREE = { id: 'island_tree_01', distFile: 'island_tree_01_lod.glb', estMB: 7 };

const $ = (id) => document.getElementById(id);
const loadTxt = $('load-txt'), loadBar = $('load-bar');
function progress(p, label) { loadBar.style.width = `${Math.round(p * 100)}%`; loadTxt.textContent = `에셋 불러오는 중 ${Math.round(p * 100)} % · ${label ?? ''}`; }
function fail(err) { console.error(err); loadTxt.className = 'err'; loadTxt.textContent = `불러오기 실패: ${err?.message ?? err}\n(로컬 서버: cd docs/sim && python3 -m http.server 8787 → http://127.0.0.1:8787/)`; }

// ---------- 렌더러 ----------
const renderer = new THREE.WebGLRenderer({ canvas: $('scene'), antialias: true, powerPreference: 'high-performance' });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = RENDER.exposure;
renderer.shadowMap.enabled = true; renderer.shadowMap.type = THREE.PCFSoftShadowMap;
const scene = new THREE.Scene(), track = new Track();
let world = null, car = null, composer = null, bloomPass = null, lib = null;

const chaseCam = new THREE.PerspectiveCamera(CHASE.fov, 1, 0.3, 4000);
const cabinCam = new THREE.PerspectiveCamera(CABIN_VIEW.fov, 1, 0.05, 4000);
const mapCam = new THREE.OrthographicCamera(-1, 1, 1, -1, 1, 3000);
mapCam.layers.enable(1); mapCam.up.set(0, 0, -1);
const chaseLook = new THREE.Vector3();
const overlay = buildMapOverlay();

// ---------- 상태 ----------
const veh = new Vehicle(), gEst = new GEstimator(), driver = new AutoDriver(track), monitor = new AppMonitor(track);
const state = { mode: 'A', cat: CATEGORIES[0], cam: 1, speed: 1, running: false, gear: 'D', sLap: 0, sHint: 0, parkHold: 0, compare: null, results: {}, keys: {}, snap: true, lowFps: 0 };
const hud = new Hud({
  keys: state.keys,
  onMode: (k) => startMode(k), onCategory: (id) => setCategory(id),
  onCompare: () => startCompare(), onReset: () => startMode(state.mode), onCam: (n) => setCam(n),
  onSpeed: (n) => { state.speed = n; hud.setActive('speed', n); },
  onCardClose: () => { if (!state.running) startMode(state.mode); },
});

function buildMapOverlay() {
  const mk = (geo, color) => { const m = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ color, depthTest: false, transparent: true, side: THREE.DoubleSide })); m.layers.set(1); m.renderOrder = 10; scene.add(m); return m; };
  const shape = (pts) => new THREE.ShapeGeometry(new THREE.Shape(pts.map(([x, y]) => new THREE.Vector2(x, y)))).rotateX(-Math.PI / 2);
  return {
    marker: mk(shape([[14, 0], [-9, 8], [-5, 0], [-9, -8]]), COLORS.mint),
    ring: mk(new THREE.RingGeometry(0.96, 1, 64).rotateX(-Math.PI / 2), COLORS.mint),
    shaft: mk(new THREE.PlaneGeometry(1, 5).translate(0.5, 0, 0).rotateX(-Math.PI / 2), COLORS.champ),
    head: mk(shape([[ARROW_HEAD, 0], [0, 8], [0, -8]]), COLORS.champ),
  };
}

function resetRun() {
  const p = track.sampleAt(0);
  veh.reset(p.x, p.y, p.h); gEst.reset(); monitor.reset(state.cat); driver.configure(state.mode, state.cat.limit);
  car.resetCargo();
  Object.assign(state, { gear: 'D', sLap: 0, sHint: 0, parkHold: 0, snap: true });
  hud.clearLog(); hud.quiet();
  hud.log(`${modeLabel(state.mode, state.cat)} 출발 · 한계 ${state.cat.limit.toFixed(2)} G`, 0);
}
function startMode(key, keepCompare = false) {
  if (!keepCompare) state.compare = null;
  Object.assign(state, { mode: key, running: true });
  hud.setMode(key, state.cat); hud.hideCard(); resetRun();
}
function startCompare() { state.compare = { stage: 'A', wait: -1 }; startMode('A', true); }
function setCategory(id) {
  state.cat = CATEGORIES.find((c) => c.id === id) ?? CATEGORIES[0];
  state.results = {};
  hud.setCategory(state.cat);
  car.setVip(state.cat.id);
  startMode(state.mode);
}
function setCam(n) { state.cam = n; world?.setMapMode(n === 3); hud.setActive('cam', n); }
function manualControl() {
  const k = state.keys;
  const steer = k.left && !k.right ? CAR.maxSteer : k.right && !k.left ? -CAR.maxSteer : 0;
  let accel = -CAR.coastDecel;
  if (k.up) accel = CAR.maxAccel; if (k.down) accel = -CAR.brake; if (k.space) accel = -CAR.hardBrake;
  return { steer, accel };
}
function step(dt) {
  const cmp = state.compare;
  if (cmp && cmp.wait >= 0) { if ((cmp.wait -= dt) < 0) { cmp.stage = 'B'; startMode('B', true); } return; }
  if (state.gear === 'P') return;
  const ctl = MODES[state.mode].auto ? driver.control(veh, state.sLap, gEst.lat) : manualControl();
  veh.steerToward(ctl.steer, dt); veh.step(ctl.accel, dt);
  gEst.update(veh.v, veh.steer, veh.aLon);
  const near = track.nearest(veh.x, veh.y, state.sHint), L = track.length;
  state.sLap += ((near.s - state.sHint + 1.5 * L) % L) - L / 2;
  state.sHint = near.s;
  const events = monitor.update({ dt, latG: gEst.latG, lonG: gEst.lonG, totalG: gEst.total, v: veh.v, steer: veh.steer, sLap: state.sLap });
  events.forEach(handleEvent);
  car.updateCargo(gEst.latG, gEst.lonG, gEst.total, dt);
  state.parkHold = inParkZone() ? state.parkHold + dt : 0;
  if (state.parkHold > (MODES[state.mode].auto ? 0 : PARK_HOLD)) arrive();
}
function handleEvent(e) {
  const t = monitor.lapTime;
  if (e.kind === 'near') { hud.beep(); hud.log(`NEAR 진입 · ${e.text} (효과음)`, t); }
  else if (e.kind === 'coach') { hud.say(e.text, 'coach'); hud.log(`음성 코칭 ${monitor.coachCount}/${APP.coachMax} · ${e.text}`, t); }
  else if (e.kind === 'notice') { hud.say(e.text, 'notice'); hud.log(`사전 안내 · ${e.detail}`, t); }
  else hud.log(e.text, t);
}
function inParkZone() {
  return state.sLap > track.length * PARK_MIN_LAP && veh.v < STOP_V && Math.abs(track.wrap(state.sLap) - track.parkS) < TRACK.parkZone;
}
function arrive() {
  state.gear = 'P'; gEst.reset();
  const r = monitor.arrive(state.mode);
  hud.say(`조수석 ${state.cat.noun} 챙기세요`, 'arrive', true);
  hud.log(`주차장 도착 (P) · 랩타임 ${fmtTime(r.lapTime)}`, r.lapTime);
  if (state.mode !== 'C') state.results[state.mode] = r;
  const { A, B } = state.results;
  if (state.compare?.stage === 'A') { state.compare.wait = COMPARE_PAUSE; hud.log(`${COMPARE_PAUSE}초 뒤 B(${state.cat.noun} 기준) 출발`, r.lapTime); }
  else if (state.mode !== 'C' && A && B) { state.compare = null; hud.showCompare(A, B, startCompare); }
  else hud.showReport(r, modeLabel(state.mode, state.cat), () => startMode(state.mode));
}
function updateCameras(dt) {
  const p = car.group.position, fx = Math.cos(veh.h), fz = -Math.sin(veh.h);
  const want = new THREE.Vector3(p.x - fx * CHASE.back, p.y + CHASE.up, p.z - fz * CHASE.back);
  const look = new THREE.Vector3(p.x + fx * CHASE.look, p.y + CHASE.lift, p.z + fz * CHASE.look);
  const k = Math.min(1, dt * CHASE.follow * state.speed);
  if (state.snap) { chaseCam.position.copy(want); chaseLook.copy(look); state.snap = false; }
  else { chaseCam.position.lerp(want, k); chaseLook.lerp(look, Math.min(1, 2 * k)); }
  chaseCam.lookAt(chaseLook);
  car.group.updateMatrixWorld();
  if (window.__dbgView) {                                   // QA 전용: 차 로컬 좌표로 체이스 캠 고정 ({pos:[x,y,z], look:[x,y,z]})
    const v = window.__dbgView, m = car.group.matrixWorld;
    chaseCam.position.fromArray(v.pos).applyMatrix4(m);
    chaseCam.lookAt(new THREE.Vector3().fromArray(v.look).applyMatrix4(m));
  }
  cabinCam.position.copy(CABIN_VIEW.pos).applyMatrix4(car.group.matrixWorld);
  cabinCam.lookAt(CABIN_VIEW.look.clone().applyMatrix4(car.group.matrixWorld));
}
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
  o.shaft.position.set(p.x, 3, p.z); o.shaft.scale.x = Math.max(0.01, body);
  o.head.position.set(p.x + Math.cos(ang) * body, 3, p.z - Math.sin(ang) * body); o.head.scale.setScalar(head / ARROW_HEAD);
}
function resize() {
  const w = innerWidth, h = innerHeight, b = track.bounds, m = 2 * MAP_PAD.margin;
  renderer.setSize(w, h); composer?.setSize(w, h);
  for (const c of [chaseCam, cabinCam]) { c.aspect = w / h; c.updateProjectionMatrix(); }
  const availH = Math.max(h * 0.55, h - MAP_PAD.top - MAP_PAD.bottom);
  const scale = Math.min(availH / (b.maxY - b.minY + m), (w * 0.92) / (b.maxX - b.minX + m));
  const offset = (h / 2 - (MAP_PAD.top + availH / 2)) / scale;
  Object.assign(mapCam, { left: -w / 2 / scale, right: w / 2 / scale, top: h / 2 / scale, bottom: -h / 2 / scale });
  mapCam.position.set(0, 1000, offset); mapCam.lookAt(0, 0, offset); mapCam.updateProjectionMatrix();
}

// ---------- 입력 ----------
addEventListener('keydown', (e) => {
  if (e.target.tagName === 'SELECT') return;
  const k = KEYMAP[e.code];
  if (k) { e.preventDefault(); state.keys[k] = true; return; }
  if (e.repeat) return;
  if (e.code === 'Escape') hud.closeCard();
  else if (e.code === 'KeyR') startMode(state.mode);
  else if (e.code === 'KeyH') hud.toggle('panel-top');
  else if (e.code === 'KeyF') hud.toggle('fps');
  else if (['Digit1', 'Digit2', 'Digit3'].includes(e.code)) setCam(+e.code.slice(5));
  else if (e.code === 'KeyP' && state.mode === 'C' && state.gear === 'D') { if (inParkZone()) arrive(); else hud.log('주차장 구역에서 멈춘 뒤 P를 누르세요', monitor.lapTime); }
});
addEventListener('keyup', (e) => { if (KEYMAP[e.code]) { e.preventDefault(); state.keys[KEYMAP[e.code]] = false; } });
addEventListener('blur', () => { for (const k of Object.keys(state.keys)) state.keys[k] = false; });
addEventListener('resize', resize);

// ---------- 루프 ----------
const clock = new THREE.Clock();
let acc = 0, dispTimer = 0, fpsAcc = 0, fpsN = 0;
function frame() {
  requestAnimationFrame(frame);
  const dt = Math.min(0.1, clock.getDelta());
  if (state.running) {
    acc += dt * state.speed; let n = 0;
    while (acc >= PHYS_DT && n < MAX_STEPS_PER_FRAME) { step(PHYS_DT); acc -= PHYS_DT; n++; }
    if (n === MAX_STEPS_PER_FRAME) acc = 0;
  }
  car.update(veh); car.setDamage(monitor.damage / 100);
  updateCameras(dt); updateOverlay();
  const cam = state.cam === 2 ? cabinCam : state.cam === 3 ? mapCam : chaseCam;
  world.update(cam, car.group.position, dt);
  if ((dispTimer -= dt) <= 0) { dispTimer = 1 / DISPLAY_HZ; car.drawDisplay(monitor.badge, gEst.total, state.cat.limit, hud.bubble); }
  hud.telemetry({ v: veh.v, steer: veh.steer, latG: gEst.latG, lonG: gEst.lonG, totalG: gEst.total, limit: state.cat.limit, gear: state.gear,
    maxG: monitor.maxG, damage: monitor.damage, coach: monitor.coachCount, lapTime: monitor.lapTime, badge: monitor.badge }, dt);
  hud.tick(dt); hud.fps();
  // 품질 자동 조절: 3초 이상 목표 fps 미만이면 블룸부터 끈다
  fpsAcc += dt; fpsN++;
  if (fpsAcc >= 1) { const f = fpsN / fpsAcc; fpsAcc = 0; fpsN = 0; state.lowFps = f < RENDER.targetFps ? state.lowFps + 1 : 0; if (state.lowFps >= 3 && bloomPass?.enabled) { bloomPass.enabled = false; console.info('[quality] bloom off'); } }
  if (composer && state.cam !== 3 && bloomPass?.enabled) { composer.passes[0].camera = cam; composer.render(); }
  else renderer.render(scene, cam);
}

// ---------- 시작: 에셋 → 월드 → 차 ----------
async function init() {
  lib = new AssetLibrary(renderer, progress);
  await lib.init();
  await lib.loadEnvironment();
  await Promise.all([
    ...PBR_SETS.map((s) => lib.loadPBR(s)),
    ...WORLD_MODELS.map((id) => lib.loadPolyHaven(id)),
    lib.loadPolyHaven(TREE.id, { distFile: TREE.distFile, estMB: TREE.estMB }),
    ...GENERATED_IDS.concat(['sedan_body']).map((id) => lib.loadGenerated(id)),
  ]);
  progress(1, '장면 구성');
  world = buildWorld(scene, track, lib, renderer);
  scene.add(new THREE.HemisphereLight(0x5a6a8a, 0x1c1814, 0.3));   // 도시 하늘빛·지면 반사: 차체·보행로가 완전히 검게 보이지 않게
  car = new CarModel(lib);
  scene.add(car.group);
  console.info('[assets] used:', car.used, 'generated:', [...lib.generated.keys()], 'world:', world.stats); window.__car_used = car.used; window.__car = car; window.__scene = scene; window.__gen = [...lib.generated.keys()];
  const rp = new RenderPass(scene, chaseCam);
  bloomPass = new UnrealBloomPass(new THREE.Vector2(innerWidth, innerHeight), 0.22, RENDER.bloom.radius, 1.0);
  composer = new EffectComposer(renderer); composer.addPass(rp); composer.addPass(bloomPass); composer.addPass(new OutputPass());
  resize();
  hud.setCategory(state.cat); hud.setMode(state.mode, state.cat);
  hud.setActive('cam', 1); hud.setActive('speed', 1);
  resetRun();
  $('loading').classList.add('hidden');
  hud.showIntro(startCompare);
  frame();
}
init().catch(fail);
