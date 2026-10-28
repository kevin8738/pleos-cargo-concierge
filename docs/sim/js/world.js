/**
 * world.js — 밤 도시 환경: 노면·차선, 가로등, 건물 실루엣, 별, 표지판, 지도용 라벨.
 */
import * as THREE from 'three';
import { TRACK, COLORS, FONT } from './config.js';

const Y = { road: 0.04, glow: 0.06, line: 0.08 }; // 겹침(z-fighting) 방지용 높이
const LAMP_GAP = 66;      // 가로등 간격 (m), 좌우 번갈아
const LAMP_SIDE = 7.2;    // 가로등 기둥: 주행선에서 좌우 오프셋 (m)
const SIGN_SIDE = -4.6;   // 표지판 위치: 주행선 오른쪽 오프셋 (m)
const SEED = 20261028;
const POLE_MAT = new THREE.MeshStandardMaterial({ color: 0x4a505a, roughness: 0.6 });
const BACK_MAT = new THREE.MeshStandardMaterial({ color: 0x20242c });

/** 결정적 의사난수 (mulberry32) — 매번 같은 도시 */
function rng(a) {
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** 메시를 만들어 parent 에 (x, y, z) 위치로 붙인다 */
function put(parent, geo, mat, x, y, z) {
  const m = new THREE.Mesh(geo, mat);
  m.position.set(x, y, z);
  parent.add(m);
  return m;
}

/** 2D 캔버스 텍스처 생성 헬퍼 */
function canvasTex(w, h, draw) {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  draw(c.getContext('2d'), w, h);
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

/** 글자 판 텍스처 (첫 줄 크게, 둘째 줄 작게, 가운데 정렬). bg 가 null 이면 투명 배경 */
function textTex(lines, { bg = COLORS.bg, fg = COLORS.champ, size = 110, w = 512, h = 256 } = {}) {
  return canvasTex(w, h, (c) => {
    if (bg) { c.fillStyle = bg; c.fillRect(0, 0, w, h); c.strokeStyle = fg; c.lineWidth = 10; c.strokeRect(8, 8, w - 16, h - 16); }
    c.fillStyle = fg; c.textAlign = 'center'; c.textBaseline = 'middle';
    lines.forEach((t, i) => {
      c.font = `bold ${i === 0 ? size : size * 0.42}px ${FONT}`;
      c.fillText(t, w / 2, h / 2 + (i - (lines.length - 1) / 2) * size * 0.75);
    });
  });
}

/** 방사형 그라디언트 (가로등 빛 웅덩이·후광) */
const glowTex = () => canvasTex(128, 128, (c) => {
  const g = c.createRadialGradient(64, 64, 0, 64, 64, 64);
  g.addColorStop(0, 'rgba(255,255,255,1)'); g.addColorStop(1, 'rgba(255,255,255,0)');
  c.fillStyle = g; c.fillRect(0, 0, 128, 128);
});

/** 경로를 따라 좌우 오프셋 [o0, o1] 띠 메시. keep(s)가 false 인 구간은 비운다 */
function ribbon(track, o0, o1, y, mat, keep = null, stride = 2) {
  const pos = [];
  const pt = (i, o) => [track.xs[i] - Math.sin(track.hs[i]) * o, y, -(track.ys[i] + Math.cos(track.hs[i]) * o)];
  for (let i = 0; i < track.n; i += stride) {
    if (keep && !keep(i * track.step)) continue;
    const j = Math.min(i + stride, track.n) % track.n;
    const a0 = pt(i, o0), a1 = pt(i, o1), b0 = pt(j, o0), b1 = pt(j, o1);
    pos.push(...a0, ...b0, ...a1, ...a1, ...b0, ...b1); // 윗면이 앞면이 되도록 감는다
  }
  const geo = new THREE.BufferGeometry().setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  geo.computeVertexNormals();
  return new THREE.Mesh(geo, mat);
}

/** 경로 s 지점 옆(lateral)에 오브젝트를 놓고, 다가오는 차를 바라보게 돌린다 (로컬 +X = 진행 방향 오른쪽) */
function placeFacing(obj, track, s, lateral) {
  const p = track.offsetAt(s, lateral);
  obj.position.set(p.x, 0, -p.y);
  obj.rotation.y = p.h - Math.PI / 2;
  return obj;
}

/** 기둥 + 글자 판 표지판 */
function makeSign(tex, w, h, height) {
  const g = new THREE.Group(), y = height + h / 2;
  put(g, new THREE.CylinderGeometry(0.06, 0.06, height), POLE_MAT, 0, height / 2, 0);
  put(g, new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ map: tex }), 0, y, 0.06);
  put(g, new THREE.BoxGeometry(w, h, 0.05), BACK_MAT, 0, y, 0);
  return g;
}

/** 노면에 붙는 평면(가로 across × 세로 along m)을 경로 s, 옆 lateral 위치에 진행 방향으로 놓는다 */
function decal(scene, track, s, lateral, across, along, mat) {
  const p = track.offsetAt(s, lateral);
  put(scene, new THREE.PlaneGeometry(across, along), mat, p.x, Y.line, -p.y)
    .rotation.set(-Math.PI / 2, 0, p.h - Math.PI / 2); // 로컬 X = 오른쪽, 로컬 Y = 진행 방향
}

/** 노면·차선·출발선·주차 구획 */
function buildRoad(scene, track) {
  const asphalt = new THREE.MeshStandardMaterial({ color: 0x3a3d43, roughness: 0.95 });
  const paint = new THREE.MeshBasicMaterial({ color: 0xd9d9d9, polygonOffset: true, polygonOffsetFactor: -2 });
  const { roadRight: r, roadLeft: l, centerLine: c } = TRACK;
  scene.add(ribbon(track, r, l, Y.road, asphalt));
  scene.add(ribbon(track, r + 0.1, r + 0.25, Y.line, paint));
  scene.add(ribbon(track, l - 0.25, l - 0.1, Y.line, paint));
  scene.add(ribbon(track, c - 0.07, c + 0.07, Y.line, paint, (s) => s % 9 < 3));     // 차로 구분 점선
  decal(scene, track, 0, (r + c) / 2, c - r, 0.4, paint);                             // 출발선
  const pk = track.parkS + 1.5;                                                       // 자동 주행 정지 지점
  for (const d of [-3.5, 3.5]) decal(scene, track, pk + d, 0, 3.2, 0.15, paint);     // 주차 구획
  decal(scene, track, pk, 0, 2.6, 2.6, new THREE.MeshBasicMaterial({
    map: textTex(['P'], { bg: null, fg: '#7fb2ff', size: 220, w: 256, h: 256 }), transparent: true, polygonOffset: true, polygonOffsetFactor: -2 }));
}

/** 가로등: 기둥·팔·등기구(발광)·후광 + 노면 빛 웅덩이(가산 합성) */
function buildLamps(scene, track) {
  const tex = glowTex();
  const headM = new THREE.MeshBasicMaterial({ color: 0xffe2a8 });
  const poolM = new THREE.MeshBasicMaterial({ map: tex, color: 0xffb860, transparent: true, opacity: 0.55, blending: THREE.AdditiveBlending, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -1 });
  const haloM = new THREE.SpriteMaterial({ map: tex, color: 0xffd08a, blending: THREE.AdditiveBlending, depthWrite: false });
  const poleG = new THREE.CylinderGeometry(0.09, 0.12, 7), armG = new THREE.BoxGeometry(2.6, 0.1, 0.1), headG = new THREE.BoxGeometry(0.7, 0.12, 0.35);
  const poolG = new THREE.PlaneGeometry(16, 16).rotateX(-Math.PI / 2);
  for (let s = 20, i = 0; s < track.length - 10; s += LAMP_GAP, i++) {
    const side = i % 2 ? -1 : 1;                                   // 왼쪽(+)·오른쪽(−) 번갈아: 양쪽 창으로 보이게
    const g = placeFacing(new THREE.Group(), track, s, side * LAMP_SIDE);
    put(g, poleG, POLE_MAT, 0, 3.5, 0);
    put(g, armG, POLE_MAT, 1.3 * side, 7, 0);                      // 팔은 항상 도로 쪽
    put(g, headG, headM, 2.5 * side, 6.9, 0);
    const halo = new THREE.Sprite(haloM);
    halo.position.set(2.5 * side, 6.8, 0); halo.scale.set(3.2, 3.2, 1);
    scene.add(g.add(halo));
    const c = track.offsetAt(s, side * (LAMP_SIDE - 2.5));
    put(scene, poolG, poolM, c.x, Y.glow, -c.y);
  }
}

/** 건물 실루엣(창문 발광) — InstancedMesh 한 번의 드로우콜 */
function buildCity(scene, track) {
  const rand = rng(SEED), b = track.bounds, spots = [];
  const win = canvasTex(64, 128, (c, w, h) => {
    c.fillStyle = '#07090d'; c.fillRect(0, 0, w, h);
    for (let y = 6; y < h - 4; y += 8) for (let x = 4; x < w - 4; x += 8) {
      if (rand() < 0.28) { c.fillStyle = rand() < 0.7 ? '#ffcf7a' : '#9fd8ff'; c.fillRect(x, y, 4, 4); }
    }
  });
  const farFromRoad = (x, y, r) => {
    for (let i = 0; i < track.n; i += 10) if ((track.xs[i] - x) ** 2 + (track.ys[i] - y) ** 2 < r * r) return false;
    return true;
  };
  for (let k = 0; k < 400 && spots.length < 170; k++) {        // 코스 주변 블록 (도로와 겹치지 않게)
    const w = 12 + rand() * 22, d = 12 + rand() * 22, h = 10 + rand() * 50;
    const x = b.minX - 150 + rand() * (b.maxX - b.minX + 300), y = b.minY - 150 + rand() * (b.maxY - b.minY + 300);
    if (farFromRoad(x, y, 22 + Math.max(w, d) * 0.75)) spots.push([x, y, w, d, h]);
  }
  for (let k = 0; k < 140; k++) {                              // 먼 스카이라인
    const a = rand() * Math.PI * 2, r = 650 + rand() * 450;
    spots.push([Math.cos(a) * r, Math.sin(a) * r, 25 + rand() * 40, 25 + rand() * 40, 40 + rand() * 130]);
  }
  const geo = new THREE.BoxGeometry(1, 1, 1).translate(0, 0.5, 0);
  const mat = new THREE.MeshStandardMaterial({ color: 0x161a22, roughness: 0.9, emissive: 0xffffff, emissiveMap: win, emissiveIntensity: 0.75 });
  const mesh = new THREE.InstancedMesh(geo, mat, spots.length), m = new THREE.Matrix4(), col = new THREE.Color();
  spots.forEach(([x, y, w, d, h], i) => {
    mesh.setMatrixAt(i, m.makeScale(w, h, d).setPosition(x, 0, -y));
    mesh.setColorAt(i, col.setHSL(0.6, 0.15, 0.6 + rand() * 0.4));
  });
  scene.add(mesh);
}

/** 별이 있는 밤하늘 */
function buildSky(scene) {
  const rand = rng(SEED + 1), pos = [], r = 2200;
  for (let i = 0; i < 900; i++) {
    const a = rand() * Math.PI * 2, e = 0.08 + rand() * 1.4;
    pos.push(Math.cos(a) * Math.cos(e) * r, Math.sin(e) * r, Math.sin(a) * Math.cos(e) * r);
  }
  const geo = new THREE.BufferGeometry().setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  scene.add(new THREE.Points(geo, new THREE.PointsMaterial({ color: 0xb8c4ff, size: 1.6, sizeAttenuation: false, fog: false, transparent: true, opacity: 0.8 })));
}

/** 베이커리(출발)·주차장(도착)·급커브 표지와 R30 커브 갈매기 표지 */
function buildSigns(scene, track) {
  scene.add(placeFacing(makeSign(textTex(['베이커리', '출발'], { bg: '#2a170b', fg: COLORS.champ }), 4, 2, 2.6), track, 6, SIGN_SIDE));
  scene.add(placeFacing(makeSign(textTex(['P 주차장', '도착 · 정지 후 P'], { bg: '#123a8a', fg: '#ffffff' }), 4, 2, 2.6), track, track.parkS - 8, SIGN_SIDE));
  scene.add(placeFacing(makeSign(textTex(['급커브', 'R30 · 감속'], { bg: '#f2c14e', fg: '#111111' }), 3.4, 1.7, 2.4), track, track.sharp.s0 - 55, SIGN_SIDE));
  const chevron = textTex(['◀'], { bg: '#111111', fg: '#f2c14e', size: 200, w: 256, h: 256 });
  for (let s = track.sharp.s0 + 6; s < track.sharp.s1; s += 12) scene.add(placeFacing(makeSign(chevron, 1, 1, 1.1), track, s, SIGN_SIDE + 0.4));
}

/** 탑다운 지도 전용(레이어 1) 커브 반지름·출발 라벨 */
function buildMapLabels(scene, track) {
  const add = (text, s, lateral) => {
    const p = track.offsetAt(s, lateral);
    const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: textTex([text], { bg: null, fg: COLORS.champ, size: 120, w: 768, h: 160 }), depthTest: false, transparent: true }));
    sp.scale.set(96, 20, 1); sp.position.set(p.x, 5, -p.y); sp.layers.set(1); sp.renderOrder = 5;
    scene.add(sp);
  };
  for (const a of track.arcs) add(a.sharp ? `R${a.R} 급커브` : `R${a.R}`, (a.s0 + a.s1) / 2, a.sharp ? 55 : 45);
  add('출발 · 주차장', track.length - 20, -24);
}

/** 장면 전체 구성. 지도 모드에서 끄고 켤 안개를 돌려준다 */
export function buildWorld(scene, track) {
  scene.background = new THREE.Color(0x04060b);
  const fog = scene.fog = new THREE.FogExp2(0x070a12, 0.0016);
  const moon = new THREE.DirectionalLight(0x9aa8d8, 0.55);
  moon.position.set(-300, 500, 200);
  scene.add(new THREE.HemisphereLight(0x50608a, 0x0a0a10, 0.9), moon);
  put(scene, new THREE.PlaneGeometry(5000, 5000).rotateX(-Math.PI / 2), new THREE.MeshStandardMaterial({ color: 0x0b0e13, roughness: 1 }), 0, 0, 0);
  buildRoad(scene, track);
  buildLamps(scene, track);
  buildCity(scene, track);
  buildSky(scene);
  buildSigns(scene, track);
  buildMapLabels(scene, track);
  return { fog };
}
