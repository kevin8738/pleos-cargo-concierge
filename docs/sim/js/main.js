/**
 * main.js (v2.1) — 진입점. 에셋 로딩(진행률) → 실사형 월드 → 주행 인스턴스(Runner) → 렌더러(ACES·그림자·약한 블룸)·입력·고정 스텝 루프.
 * 비교는 화면을 좌(A 사람 기준)·우(B 케이크 기준)로 나눠 같은 코스를 **동시에** 달린다. 단독 주행(A/B/C)은 한 화면.
 */
import * as THREE from 'three';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';
import { PHYS_DT, MAX_STEPS_PER_FRAME, CAR, CATEGORIES, COLORS, APP, RENDER, GENERATED_IDS } from './config.js';
import { Track } from './track.js';
import { AssetLibrary } from './assets.js';
import { buildWorld } from './world.js';
import { Runner } from './runner.js';
import { SideHud } from './split.js';
import { Hud, fmtTime, modeLabel } from './hud.js';

const MAP_M_PER_G = 120, MAP_PAD = { top: 12, bottom: 285, margin: 40 }, ARROW_HEAD = 14;
const COMPARE_CARD_DELAY = 2.0, DISPLAY_HZ = 10, GAP_HZ = 5;
const KEYMAP = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Space: 'space' };
/** 렌더 레이어: 1 = A(단독 주행도 이 차), 2 = B(비교 전용), 3 = 지도 오버레이 */
const LAYER = { A: 1, B: 2, MAP: 3 };
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
let world = null, composer = null, bloomPass = null, lib = null;

const mapCam = new THREE.OrthographicCamera(-1, 1, 1, -1, 1, 3000);
for (const l of [LAYER.A, LAYER.B, LAYER.MAP]) mapCam.layers.enable(l);
mapCam.up.set(0, 0, -1);
const overlays = { A: buildMapOverlay(COLORS.champ), B: buildMapOverlay(COLORS.mint) };

// ---------- 상태 ----------
const runners = { A: null, B: null };                  // A: 단독 주행에도 쓰는 차, B: 동시 비교 전용
const sides = { A: new SideHud($('split'), 'A', 'L'), B: new SideHud($('split'), 'B', 'R') };
const state = { mode: 'A', cat: CATEGORIES[0], cam: 1, speed: 1, running: false, split: false, cardWait: -1, results: {}, keys: {}, lowFps: 0 };
const hud = new Hud({
  keys: state.keys,
  onMode: (k) => startMode(k), onCategory: (id) => setCategory(id),
  onCompare: () => startCompare(), onReset: () => (state.split ? startCompare() : startMode(state.mode)), onCam: (n) => setCam(n),
  onSpeed: (n) => { state.speed = n; hud.setActive('speed', n); },
  onCardClose: () => { if (!state.running && !state.split) startMode(state.mode); },
});
const active = () => (state.split ? [runners.A, runners.B] : [runners.A]);
const sink = (r) => (state.split ? sides[r.id] : hud);   // 이벤트·계기 출력처: 비교면 반쪽 HUD, 단독이면 기본 HUD

function buildMapOverlay(color) {
  const mk = (geo, c) => { const m = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ color: c, depthTest: false, transparent: true, side: THREE.DoubleSide })); m.layers.set(LAYER.MAP); m.renderOrder = 10; scene.add(m); return m; };
  const shape = (pts) => new THREE.ShapeGeometry(new THREE.Shape(pts.map(([x, y]) => new THREE.Vector2(x, y)))).rotateX(-Math.PI / 2);
  return {
    marker: mk(shape([[14, 0], [-9, 8], [-5, 0], [-9, -8]]), color),
    ring: mk(new THREE.RingGeometry(0.96, 1, 64).rotateX(-Math.PI / 2), color),
    shaft: mk(new THREE.PlaneGeometry(1, 5).translate(0.5, 0, 0).rotateX(-Math.PI / 2), COLORS.champ),
    head: mk(shape([[ARROW_HEAD, 0], [0, 8], [0, -8]]), COLORS.champ),
  };
}

function resetRunner(r, mode) {
  r.reset(mode, state.cat);
  const s = sink(r);
  s.clearLog(); s.quiet();
  s.log(`${modeLabel(mode, state.cat)} 출발 · 한계 ${state.cat.limit.toFixed(2)} G`, 0);
}
function leaveSplit() {
  if (!state.split) return;
  state.split = false;
  runners.B.setVisible(false); hud.setSplit(false);
  for (const s of Object.values(sides)) s.clearDone();
  resize();
}
/** 단독 주행 (A/B/C) */
function startMode(key) {
  leaveSplit();
  Object.assign(state, { mode: key, running: true, cardWait: -1 });
  hud.setMode(key, state.cat); hud.hideCard();
  resetRunner(runners.A, key);
}
/** 동시 비교: 왼쪽 A(사람 기준) · 오른쪽 B(분류 기준)가 같은 순간 출발 */
function startCompare() {
  const c = state.cat;
  Object.assign(state, { split: true, running: true, cardWait: -1, mode: 'A' });
  runners.B.setVisible(true); hud.setSplit(true); hud.hideCard();
  sides.A.setLabel('<b>A</b> 사람 기준 · 코너 0.40 G', modeLabel('A', c), c.limit);
  sides.B.setLabel(`<b>B</b> ${c.noun} 기준 · 한계 ${c.limit.toFixed(2)} G의 75 %`, modeLabel('B', c), c.limit);
  for (const s of Object.values(sides)) s.clearDone();
  resetRunner(runners.A, 'A'); resetRunner(runners.B, 'B');
  resize();
}
function setCategory(id) {
  state.cat = CATEGORIES.find((c) => c.id === id) ?? CATEGORIES[0];
  state.results = {};
  hud.setCategory(state.cat);
  for (const r of Object.values(runners)) r.car.setVip(state.cat.id);
  if (state.split) startCompare(); else startMode(state.mode);
}
function setCam(n) { state.cam = n; world?.setMapMode(n === 3); document.body.classList.toggle('map', n === 3); hud.setActive('cam', n); }
function manualControl() {
  const k = state.keys;
  const steer = k.left && !k.right ? CAR.maxSteer : k.right && !k.left ? -CAR.maxSteer : 0;
  let accel = -CAR.coastDecel;
  if (k.up) accel = CAR.maxAccel; if (k.down) accel = -CAR.brake; if (k.space) accel = -CAR.hardBrake;
  return { steer, accel };
}
function handleEvent(r, e) {
  const s = sink(r), t = r.monitor.lapTime;
  if (e.kind === 'near') { hud.beep(); s.log(`NEAR 진입 · ${e.text} (효과음)`, t); }
  else if (e.kind === 'coach') { s.say(e.text, 'coach'); s.log(`음성 코칭 ${r.monitor.coachCount}/${APP.coachMax} · ${e.text}`, t); }
  else if (e.kind === 'notice') { s.say(e.text, 'notice'); s.log(`사전 안내 · ${e.detail}`, t); }
  else if (e.kind === 'arrive') onArrive(r, e.result);
  else s.log(e.text, t);
}
function onArrive(r, res) {
  const s = sink(r);
  s.say(`조수석 ${state.cat.noun} 챙기세요`, 'arrive', true);
  s.log(`주차장 도착 (P) · 랩타임 ${fmtTime(res.lapTime)}`, res.lapTime);
  if (state.split) {
    sides[r.id].done(res);
    if (runners.A.arrived && runners.B.arrived) state.cardWait = COMPARE_CARD_DELAY;   // 둘 다 서면 잠시 뒤 비교 카드
    return;
  }
  if (r.mode !== 'C') state.results[r.mode] = res;
  const { A, B } = state.results;
  if (r.mode !== 'C' && A && B) hud.showCompare(A, B, startCompare);
  else hud.showReport(res, modeLabel(r.mode, state.cat), () => startMode(r.mode));
}
/** 가운데 위 칩: 두 차의 거리 차 / 먼저 도착한 쪽과 남은 거리 */
function updateGap() {
  if (!state.split) return;
  const a = runners.A, b = runners.B, goal = track.parkS, el = $('split-gap');
  if (!a.arrived && !b.arrived) { const d = a.sLap - b.sLap; el.textContent = Math.abs(d) < 1 ? '같은 자리에서 동시에 출발' : `${d > 0 ? 'A' : 'B'}가 ${Math.abs(d).toFixed(0)} m 앞섬`; }
  else if (a.arrived && !b.arrived) el.textContent = `A 도착 ${fmtTime(a.result.lapTime)} · B 남은 거리 ${Math.max(0, goal - b.sLap).toFixed(0)} m`;
  else if (!a.arrived && b.arrived) el.textContent = `B 도착 ${fmtTime(b.result.lapTime)} · A 남은 거리 ${Math.max(0, goal - a.sLap).toFixed(0)} m`;
  else el.textContent = `두 차 모두 도착 · 랩타임 차 ${Math.abs(b.result.lapTime - a.result.lapTime).toFixed(1)}초`;
}
function updateOverlays() {
  if (state.cam !== 3) return;
  for (const [id, o] of Object.entries(overlays)) {
    const r = runners[id], on = state.split || id === 'A';
    for (const m of Object.values(o)) m.visible = on;
    if (!on) continue;
    const p = r.position, h = r.veh.h, g = r.gEst;
    o.marker.position.set(p.x, 2, p.z); o.marker.rotation.y = h;
    o.ring.position.set(p.x, 1, p.z); o.ring.scale.setScalar(state.cat.limit * MAP_M_PER_G);
    const wx = Math.cos(h) * g.lonG - Math.sin(h) * g.latG, wy = Math.sin(h) * g.lonG + Math.cos(h) * g.latG;
    const len = Math.hypot(wx, wy) * MAP_M_PER_G, head = Math.min(len, ARROW_HEAD), body = len - head, ang = Math.atan2(wy, wx);
    const col = r.monitor.badge === 'OVER' ? COLORS.red : r.monitor.badge === 'NEAR' ? COLORS.champ : COLORS.mint;
    o.shaft.visible = o.head.visible = len > 2;
    for (const m of [o.shaft, o.head]) { m.material.color.set(col); m.rotation.y = ang; }
    o.shaft.position.set(p.x, 3, p.z); o.shaft.scale.x = Math.max(0.01, body);
    o.head.position.set(p.x + Math.cos(ang) * body, 3, p.z - Math.sin(ang) * body); o.head.scale.setScalar(head / ARROW_HEAD);
  }
}
function resize() {
  const w = innerWidth, h = innerHeight, b = track.bounds, m = 2 * MAP_PAD.margin;
  renderer.setSize(w, h); composer?.setSize(w, h);
  const aspect = (state.split ? w / 2 : w) / h;
  for (const r of Object.values(runners)) r?.setAspect(aspect);
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
  else if (e.code === 'KeyR') { if (state.split) startCompare(); else startMode(state.mode); }
  else if (e.code === 'KeyH') hud.toggle('panel-top');
  else if (e.code === 'KeyF') hud.toggle('fps');
  else if (['Digit1', 'Digit2', 'Digit3'].includes(e.code)) setCam(+e.code.slice(5));
  else if (e.code === 'KeyP' && !state.split && state.mode === 'C' && runners.A.gear === 'D') {
    if (runners.A.inParkZone()) onArrive(runners.A, runners.A.arrive()); else hud.log('주차장 구역에서 멈춘 뒤 P를 누르세요', runners.A.monitor.lapTime);
  }
});
addEventListener('keyup', (e) => { if (KEYMAP[e.code]) { e.preventDefault(); state.keys[KEYMAP[e.code]] = false; } });
addEventListener('blur', () => { for (const k of Object.keys(state.keys)) state.keys[k] = false; });
addEventListener('resize', resize);

// ---------- 루프 ----------
const clock = new THREE.Clock();
let acc = 0, dispTimer = 0, gapTimer = 0, fpsAcc = 0, fpsN = 0;
function frame() {
  requestAnimationFrame(frame);
  const dt = Math.min(0.1, clock.getDelta());
  const act = active();
  if (state.running) {
    acc += dt * state.speed; let n = 0;
    while (acc >= PHYS_DT && n < MAX_STEPS_PER_FRAME) {
      for (const r of act) for (const e of r.step(PHYS_DT, manualControl)) handleEvent(r, e);
      acc -= PHYS_DT; n++;
    }
    if (n === MAX_STEPS_PER_FRAME) acc = 0;
    if (state.cardWait >= 0 && (state.cardWait -= dt * state.speed) < 0) {
      state.cardWait = -1; state.running = false;
      hud.showCompare(runners.A.result, runners.B.result, startCompare);
    }
  }
  for (const r of act) r.update(dt, state.speed, r.id === 'A' ? window.__dbgView : null);
  updateOverlays();
  const cams = act.map((r) => (state.cam === 2 ? r.cabinCam : r.chaseCam));
  world.update(state.cam === 3 ? [mapCam] : cams, act.map((r) => r.position), dt);
  if ((dispTimer -= dt) <= 0) { dispTimer = 1 / DISPLAY_HZ; for (const r of act) r.car.drawDisplay(r.monitor.badge, r.gEst.total, state.cat.limit, sink(r).bubble); }
  for (const r of act) sink(r).telemetry({ v: r.veh.v, steer: r.veh.steer, latG: r.gEst.latG, lonG: r.gEst.lonG, totalG: r.gEst.total, limit: state.cat.limit, gear: r.gear,
    maxG: r.monitor.maxG, damage: r.monitor.damage, coach: r.monitor.coachCount, lapTime: r.monitor.lapTime, badge: r.monitor.badge }, dt);
  hud.tick(dt); for (const s of Object.values(sides)) s.tick(dt); hud.fps();
  if ((gapTimer -= dt) <= 0) { gapTimer = 1 / GAP_HZ; updateGap(); }
  // 품질 자동 조절: 3초 이상 목표 fps 미만이면 블룸부터 끈다
  fpsAcc += dt; fpsN++;
  if (fpsAcc >= 1) { const f = fpsN / fpsAcc; fpsAcc = 0; fpsN = 0; state.lowFps = f < RENDER.targetFps ? state.lowFps + 1 : 0; if (state.lowFps >= 3 && bloomPass?.enabled) { bloomPass.enabled = false; console.info('[quality] bloom off'); } }
  // 렌더: 지도는 전체 폭 1장, 비교는 좌우 반씩(시저), 단독은 블룸 포함 1장
  const w = innerWidth, h = innerHeight;
  if (state.cam === 3) renderer.render(scene, mapCam);
  else if (state.split) {
    const hw = Math.floor(w / 2);
    renderer.setScissorTest(true);
    renderer.setViewport(0, 0, hw, h); renderer.setScissor(0, 0, hw, h); renderer.render(scene, cams[0]);
    renderer.setViewport(hw, 0, w - hw, h); renderer.setScissor(hw, 0, w - hw, h); renderer.render(scene, cams[1]);
    renderer.setScissorTest(false); renderer.setViewport(0, 0, w, h);
  } else if (composer && bloomPass?.enabled) { composer.passes[0].camera = cams[0]; composer.render(); }
  else renderer.render(scene, cams[0]);
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
  runners.A = new Runner({ id: 'A', track, lib, scene, layer: LAYER.A });
  runners.B = new Runner({ id: 'B', track, lib, scene, layer: LAYER.B });
  runners.B.setVisible(false);
  console.info('[assets] used:', runners.A.car.used, 'generated:', [...lib.generated.keys()], 'world:', world.stats);
  Object.assign(window, { __car_used: runners.A.car.used, __car: runners.A.car, __runners: runners, __scene: scene, __gen: [...lib.generated.keys()], __state: state });
  const rp = new RenderPass(scene, runners.A.chaseCam);
  bloomPass = new UnrealBloomPass(new THREE.Vector2(innerWidth, innerHeight), 0.22, RENDER.bloom.radius, 1.0);
  composer = new EffectComposer(renderer); composer.addPass(rp); composer.addPass(bloomPass); composer.addPass(new OutputPass());
  resize();
  hud.setCategory(state.cat); hud.setMode(state.mode, state.cat);
  hud.setActive('cam', 1); hud.setActive('speed', 1);
  resetRunner(runners.A, 'A');
  $('loading').classList.add('hidden');
  hud.showIntro(startCompare);
  frame();
}
init().catch(fail);
