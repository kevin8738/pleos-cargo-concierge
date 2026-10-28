/**
 * world.js — 실사형 밤 도시: PBR 도로·연석·보도·차선 데칼·횡단보도·맨홀·배수구, Poly Haven 가로등(근처 6개만 실제 SpotLight)·
 * 가로수(근처 3D + 같은 모델로 구운 임포스터)·가로 시설, 파사드 모듈로 조립한 건물(청크 인스턴싱, 창문 일부 발광),
 * 베이커리(출발)·주차장(도착), 원경 실루엣·밤하늘·안개, 지도용 라벨.
 * 좌표: 트랙 (x, y) → 장면 (x, 0, -y). "도로 프레임" = 로컬 +X 진행 방향, +Z 오른쪽(차와 같은 규칙).
 */
import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';
import { TRACK, COLORS, FONT, ROAD, RENDER } from './config.js';
import { KitCutter, flattenParts, partsBox } from './assets.js';

const SEED = 20261028;
const YR = ROAD.y, YW = ROAD.y + ROAD.curbH;                 // 노면·보도 높이
const R_CURB = TRACK.roadRight, L_CURB = TRACK.roadLeft;       // 연석 면 (주행선 기준)
const R_WALK = R_CURB - ROAD.curbW, L_WALK = L_CURB + ROAD.curbW;
const R_OUT = R_WALK - ROAD.walk, L_OUT = L_WALK + ROAD.walk;  // 보도 바깥 끝 (-4.7 / 8.2)
const R_FACADE = R_OUT - 0.05, L_FACADE = L_OUT + 0.05;        // 건물 벽면
const ROAD_MID = (R_CURB + L_CURB) / 2;                        // 도로 중심선 (주행선 +1.75)
const ROAD_HALF_CLEAR = (L_OUT - R_OUT) / 2 + 0.1;             // 도로 중심선 → 보도 바깥까지
const LAMP_LAT = { R: R_WALK - 0.4, L: L_WALK + 0.4 };
const TREE_LAT = { R: R_OUT + 0.8, L: L_OUT - 0.8 };
const LAMP_HEAD_Y = 3.27;                                      // street_lamp_01 전구 높이 (m)
const ENV_WORLD = 0.45;                                        // 도시 재질의 HDRI 반사 세기
const CROSSWALK_S = 34;                                        // 횡단보도 위치 (출발 34 m 앞)
const BAKERY = { s0: -3, s1: 15 };                             // 베이커리 건물 (오른쪽) s 범위
const PARK = { s0: -46, s1: -6 };                              // 주차장 (오른쪽, 출발점 기준 음수 = 랩 끝)
const DRAIN_GAP = 25, MANHOLE_GAP = 70;

// ───────────────────────── 공용 헬퍼 ─────────────────────────
/** 결정적 의사난수 (mulberry32) — 매번 같은 도시 */
function rng(a) {
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const _q = new THREE.Quaternion(), _p = new THREE.Vector3(), _sc = new THREE.Vector3(), UP = new THREE.Vector3(0, 1, 0);
/** 도로 프레임 행렬: s 지점 옆 lateral, 높이 y, 진행 방향 + yaw 회전, 균일 스케일 */
function frameAt(track, s, lat, y = 0, yaw = 0, scale = 1) {
  const p = track.offsetAt(s, lat);
  _q.setFromAxisAngle(UP, p.h + yaw);
  return new THREE.Matrix4().compose(_p.set(p.x, y, -p.y), _q, _sc.set(scale, scale, scale));
}
/** 장면 좌표 점 */
function worldAt(track, s, lat, y = 0) {
  const p = track.offsetAt(s, lat);
  return new THREE.Vector3(p.x, y, -p.y);
}

/** 2D 캔버스 텍스처 */
function canvasTex(w, h, draw, srgb = true) {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  draw(c.getContext('2d'), w, h);
  const t = new THREE.CanvasTexture(c);
  if (srgb) t.colorSpace = THREE.SRGBColorSpace;
  t.anisotropy = 8;
  return t;
}

/** 글자 판 텍스처 (첫 줄 크게, 나머지 작게) */
function textTex(lines, { bg = COLORS.bg, fg = COLORS.champ, size = 110, w = 512, h = 256, border = true } = {}) {
  return canvasTex(w, h, (c) => {
    if (bg) { c.fillStyle = bg; c.fillRect(0, 0, w, h); }
    if (bg && border) { c.strokeStyle = fg; c.lineWidth = 10; c.strokeRect(8, 8, w - 16, h - 16); }
    c.fillStyle = fg; c.textAlign = 'center'; c.textBaseline = 'middle';
    lines.forEach((t, i) => {
      c.font = `bold ${i === 0 ? size : size * 0.42}px ${FONT}`;
      c.fillText(t, w / 2, h / 2 + (i - (lines.length - 1) / 2) * size * 0.75);
    });
  });
}

/** 방사형 그라디언트 (빛 웅덩이·후광) */
const glowTex = (inner = 'rgba(255,255,255,1)') => canvasTex(128, 128, (c) => {
  const g = c.createRadialGradient(64, 64, 0, 64, 64, 64);
  g.addColorStop(0, inner); g.addColorStop(0.35, 'rgba(255,255,255,0.35)'); g.addColorStop(1, 'rgba(255,255,255,0)');
  c.fillStyle = g; c.fillRect(0, 0, 128, 128);
});

/** 지오메트리 누적기: 경로 스윕·상자 등을 한 BufferGeometry 로 (UV 는 미터 단위) */
class GeoBuilder {
  constructor() { this.p = []; this.n = []; this.uv = []; this.idx = []; }
  get count() { return this.p.length / 3; }
  /** 경로를 따라 단면 폴리라인 profile [[lat, y], ...] 을 쓸어 만든다. 면 방향 = 진행 × 단면 진행 방향 */
  sweep(track, profile, s0, s1, step = 1) {
    const n = Math.max(2, Math.ceil(Math.abs(s1 - s0) / step) + 1), m = profile.length, base = this.count;
    const L = [0];
    for (let k = 1; k < m; k++) L.push(L[k - 1] + Math.hypot(profile[k][0] - profile[k - 1][0], profile[k][1] - profile[k - 1][1]));
    for (let i = 0; i < n; i++) {
      const s = s0 + ((s1 - s0) * i) / (n - 1), q = track.sampleAt(s), sh = Math.sin(q.h), ch = Math.cos(q.h);
      for (let k = 0; k < m; k++) {
        const [lat, y] = profile[k];
        this.p.push(q.x - sh * lat, y, -(q.y + ch * lat));
        // 법선: 단면 방향 d = (dl, dy) → 진행 f × d. 꼭짓점별로 앞뒤 선분 평균
        const a = profile[Math.max(0, k - 1)], b = profile[Math.min(m - 1, k + 1)];
        const dl = b[0] - a[0], dy = b[1] - a[1], len = Math.hypot(dl, dy) || 1;
        // f × (dl·left + dy·up): left=( -sh,0,-ch ) up=(0,1,0) f=(ch,0,-sh) → f×left = up, f×up = right=(sh,0,ch)
        const nx = (dy / len) * sh, ny = dl / len, nz = (dy / len) * ch;
        this.n.push(nx, ny, nz);
        this.uv.push(L[k], s);
      }
    }
    for (let i = 0; i < n - 1; i++) for (let k = 0; k < m - 1; k++) {
      const a = base + i * m + k, b = a + m, c = a + 1, d = b + 1;
      this.idx.push(a, b, c, c, b, d);
    }
    return this;
  }
  /** 축 정렬 상자 (로컬) 를 행렬로 옮겨 추가. faces: 넣을 면 ['px','nx','py','ny','pz','nz'] */
  box(x0, y0, z0, x1, y1, z1, matrix, faces = ['px', 'nx', 'py', 'pz', 'nz']) {
    const nm = new THREE.Matrix3().getNormalMatrix(matrix), v = new THREE.Vector3(), nn = new THREE.Vector3();
    const F = {
      px: [[x1, y0, z1], [x1, y0, z0], [x1, y1, z1], [x1, y1, z0], [1, 0, 0], 'zy'],
      nx: [[x0, y0, z0], [x0, y0, z1], [x0, y1, z0], [x0, y1, z1], [-1, 0, 0], 'zy'],
      py: [[x0, y1, z1], [x1, y1, z1], [x0, y1, z0], [x1, y1, z0], [0, 1, 0], 'xz'],
      ny: [[x0, y0, z0], [x1, y0, z0], [x0, y0, z1], [x1, y0, z1], [0, -1, 0], 'xz'],
      pz: [[x0, y0, z1], [x1, y0, z1], [x0, y1, z1], [x1, y1, z1], [0, 0, 1], 'xy'],
      nz: [[x1, y0, z0], [x0, y0, z0], [x1, y1, z0], [x0, y1, z0], [0, 0, -1], 'xy'],
    };
    for (const f of faces) {
      const [a, b, c, d, nrm, plane] = F[f], base = this.count;
      nn.set(...nrm).applyMatrix3(nm).normalize();
      for (const q of [a, b, c, d]) {
        v.set(...q).applyMatrix4(matrix);
        this.p.push(v.x, v.y, v.z); this.n.push(nn.x, nn.y, nn.z);
        const u = plane === 'zy' ? q[2] : q[0], w = plane === 'xz' ? q[2] : q[1];
        this.uv.push(f === 'nx' || f === 'pz' ? u : -u, w);
      }
      this.idx.push(base, base + 1, base + 2, base + 2, base + 1, base + 3);
    }
    return this;
  }
  build() {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(this.p, 3));
    g.setAttribute('normal', new THREE.Float32BufferAttribute(this.n, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(this.uv, 2));
    g.setIndex(this.idx);
    g.computeBoundingSphere();
    return g;
  }
}

/** 메시 추가 헬퍼 */
function addMesh(parent, geo, mat, { receive = false, cast = false, name = '' } = {}) {
  const m = new THREE.Mesh(geo, mat);
  m.receiveShadow = receive; m.castShadow = cast; m.name = name;
  parent.add(m);
  return m;
}

// ───────────────────────── 청크 인스턴싱 ─────────────────────────
/** 프로토타입 = 재질별 지오메트리 목록. glass 파트는 발광/어두운 두 변형으로 나눈다 */
let protoSeq = 0;
function makeProto(parts, { glassLit = null, glassDark = null, envIntensity = ENV_WORLD } = {}) {
  const out = [];
  for (const p of parts) {
    if (p.material && 'envMapIntensity' in p.material) p.material.envMapIntensity = envIntensity;
    const isGlass = /glass/i.test(p.material?.name ?? '');
    if (isGlass && glassLit) {
      out.push({ uid: `p${protoSeq++}`, geometry: p.geometry, material: glassLit, when: 'lit', colored: true });
      out.push({ uid: `p${protoSeq++}`, geometry: p.geometry, material: glassDark, when: 'dark' });
    } else out.push({ uid: `p${protoSeq++}`, geometry: p.geometry, material: p.material });
  }
  return out;
}

class Chunks {
  constructor(scene, track) {
    this.track = track;
    this.root = new THREE.Group(); this.root.name = 'chunks';
    scene.add(this.root);
    this.map = new Map();
  }
  /** s·좌우로 청크를 골라 프로토 인스턴스 1개를 넣는다 */
  add(s, side, proto, matrix, { lit = false, color = null } = {}) {
    const key = `${side}${Math.floor(this.track.wrap(s) / RENDER.chunk)}`;
    let ch = this.map.get(key);
    if (!ch) this.map.set(key, (ch = { batches: new Map(), box: new THREE.Box3(), group: new THREE.Group() }));
    _p.setFromMatrixPosition(matrix);
    ch.box.expandByPoint(_p);
    for (const part of proto) {
      if (part.when && part.when !== (lit ? 'lit' : 'dark')) continue;
      let b = ch.batches.get(part.uid);
      if (!b) ch.batches.set(part.uid, (b = { part, mats: [], cols: [] }));
      b.mats.push(matrix);
      if (part.colored) b.cols.push(color ?? new THREE.Color(1, 0.8, 0.5));
    }
  }
  /** 모든 배치를 InstancedMesh 로 만든다 */
  finalize() {
    let draws = 0, instances = 0;
    for (const ch of this.map.values()) {
      for (const b of ch.batches.values()) {
        const im = new THREE.InstancedMesh(b.part.geometry, b.part.material, b.mats.length);
        b.mats.forEach((m, i) => im.setMatrixAt(i, m));
        if (b.part.colored) b.cols.forEach((c, i) => im.setColorAt(i, c));
        im.computeBoundingSphere();
        ch.group.add(im);
        draws++; instances += b.mats.length;
      }
      ch.center = ch.box.getCenter(new THREE.Vector3());
      ch.radius = ch.box.getSize(new THREE.Vector3()).length() / 2 + 30;
      this.root.add(ch.group);
      ch.batches = null;
    }
    this.stats = { chunks: this.map.size, draws, instances };
  }
  /** 먼 청크 숨김 (안개 + 원경 실루엣이 대신) */
  update(cam) {
    for (const ch of this.map.values()) ch.group.visible = ch.center.distanceTo(cam) < RENDER.chunkCull + ch.radius;
  }
}

// ───────────────────────── 하늘·바닥 ─────────────────────────
function buildSky(scene) {
  const sky = new THREE.Mesh(new THREE.SphereGeometry(3000, 32, 16), new THREE.ShaderMaterial({
    side: THREE.BackSide, depthWrite: false, fog: false,
    uniforms: { top: { value: new THREE.Color(0x020309) }, mid: { value: new THREE.Color(0x0a0f1c) }, horizon: { value: new THREE.Color(0x2a2630) } },
    vertexShader: 'varying vec3 vP; void main(){ vP = normalize(position); gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }',
    fragmentShader: `uniform vec3 top; uniform vec3 mid; uniform vec3 horizon; varying vec3 vP;
      void main(){ float h = clamp(vP.y, -0.1, 1.0); vec3 c = mix(horizon, mid, smoothstep(0.0, 0.12, h)); c = mix(c, top, smoothstep(0.12, 0.7, h));
      gl_FragColor = vec4(c, 1.0);
      #include <tonemapping_fragment>
      #include <colorspace_fragment>
      }`,
  }));
  sky.renderOrder = -10; sky.name = 'sky';
  scene.add(sky);
  const rand = rng(SEED + 1), pos = [], r = 2800;
  for (let i = 0; i < 1100; i++) {
    const a = rand() * Math.PI * 2, e = 0.12 + rand() * 1.35;
    pos.push(Math.cos(a) * Math.cos(e) * r, Math.sin(e) * r, Math.sin(a) * Math.cos(e) * r);
  }
  const geo = new THREE.BufferGeometry().setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  const stars = new THREE.Points(geo, new THREE.PointsMaterial({ color: 0xc8d2ff, size: 1.5, sizeAttenuation: false, fog: false, transparent: true, opacity: 0.75, depthWrite: false }));
  stars.name = 'stars';
  scene.add(stars);
  return [sky, stars];
}

function buildGround(scene, lib) {
  const size = 6000, geo = new THREE.PlaneGeometry(size, size).rotateX(-Math.PI / 2);
  const uv = geo.attributes.uv;
  for (let i = 0; i < uv.count; i++) uv.setXY(i, uv.getX(i) * size, uv.getY(i) * size);
  const mat = lib.pbr('concrete_floor_02', { color: 0x5a5c60, roughness: 0.9, envMapIntensity: 0.2 });
  addMesh(scene, geo, mat, { receive: true, name: 'ground' }).position.y = -0.01;
}

// ───────────────────────── 도로 ─────────────────────────
function buildRoad(scene, track, lib, mats) {
  const L = track.length;
  const road = new GeoBuilder().sweep(track, [[R_CURB, YR], [L_CURB, YR]], 0, L, 1);
  addMesh(scene, road.build(), mats.asphalt, { receive: true, name: 'road' });
  // 연석(콘크리트)·보도(블록) — 단면 순서로 면 방향이 정해진다 (주석: GeoBuilder.sweep)
  const curb = new GeoBuilder()
    .sweep(track, [[R_WALK, YW], [R_CURB, YW], [R_CURB, YR]], 0, L, 1)
    .sweep(track, [[L_CURB, YR], [L_CURB, YW], [L_WALK, YW]], 0, L, 1);
  addMesh(scene, curb.build(), mats.curb, { receive: true, name: 'curb' });
  const walk = new GeoBuilder()
    .sweep(track, [[R_OUT, 0], [R_OUT, YW], [R_WALK, YW]], 0, L, 1)
    .sweep(track, [[L_WALK, YW], [L_OUT, YW], [L_OUT, 0]], 0, L, 1);
  addMesh(scene, walk.build(), mats.paving, { receive: true, name: 'sidewalk' });

  // 차선 데칼: 흰 실선(가장자리), 노란 이중 실선(중앙), 횡단보도·정지선, 주차 칸
  const Y = YR + 0.004, white = new GeoBuilder(), yellow = new GeoBuilder();
  const notCross = (s) => { const w = track.wrap(s - CROSSWALK_S - 2); return Math.min(w, L - w) > 3.2; };
  const runs = (keep, fn) => { // keep(s) 가 참인 구간들을 이어서 fn(s0, s1)
    let a = null;
    const end = Math.ceil(L);
    for (let i = 0; i <= end; i++) {
      const s = Math.min(i, L), on = i < end && keep(s);
      if (on && a === null) a = s;
      if (!on && a !== null) { fn(a, s); a = null; }
    }
  };
  runs(() => true, (a, b) => {
    white.sweep(track, [[ROAD.edgeR, Y], [ROAD.edgeR + 0.15, Y]], a, b, 1);
    white.sweep(track, [[ROAD.edgeL - 0.15, Y], [ROAD.edgeL, Y]], a, b, 1);
  });
  runs(notCross, (a, b) => {
    yellow.sweep(track, [[TRACK.centerLine - 0.21, Y], [TRACK.centerLine - 0.06, Y]], a, b, 1);
    yellow.sweep(track, [[TRACK.centerLine + 0.06, Y], [TRACK.centerLine + 0.21, Y]], a, b, 1);
  });
  for (let l = ROAD.edgeR + 0.25; l + 0.5 <= ROAD.edgeL - 0.2; l += 1.0) white.sweep(track, [[l, Y], [l + 0.5, Y]], CROSSWALK_S, CROSSWALK_S + 4, 4);
  white.sweep(track, [[ROAD.edgeR, Y], [TRACK.centerLine - 0.25, Y]], CROSSWALK_S - 3, CROSSWALK_S - 2.55, 1); // 정지선
  const pk = track.parkS + 1.5;
  for (const d of [-3.5, 3.5]) white.sweep(track, [[-1.6, Y], [1.6, Y]], pk + d - 0.08, pk + d + 0.08, 1);    // 도착 주차 칸
  white.sweep(track, [[-1.6, Y], [-1.45, Y]], pk - 3.5, pk + 3.5, 1);
  addMesh(scene, white.build(), mats.paintW, { receive: true, name: 'paint-white' });
  addMesh(scene, yellow.build(), mats.paintY, { receive: true, name: 'paint-yellow' });

  // 노면 P 마크 (도착 지점)
  const p = worldAt(track, pk, 0, Y + 0.002), h = track.sampleAt(pk).h;
  const pMark = new THREE.Mesh(new THREE.PlaneGeometry(2.4, 2.4), new THREE.MeshStandardMaterial({
    map: textTex(['P'], { bg: null, fg: '#e8eef8', size: 230, w: 256, h: 256 }), transparent: true, roughness: 0.55,
    polygonOffset: true, polygonOffsetFactor: -4, envMapIntensity: ENV_WORLD, depthWrite: false,
  }));
  pMark.position.copy(p); pMark.rotation.set(-Math.PI / 2, 0, h - Math.PI / 2);
  pMark.receiveShadow = true; pMark.name = 'P-mark';
  scene.add(pMark);
}

/** 배수구 격자 텍스처 (주철) */
function grateTex() {
  return canvasTex(256, 96, (c, w, h) => {
    c.fillStyle = '#2a2b2d'; c.fillRect(0, 0, w, h);
    c.fillStyle = '#050505';
    for (let x = 14; x < w - 10; x += 16) c.fillRect(x, 12, 8, h - 24);
    c.strokeStyle = '#3c3d40'; c.lineWidth = 6; c.strokeRect(3, 3, w - 6, h - 6);
  });
}
/** 가로수 보호 덮개 텍스처 */
function treeGrateTex() {
  return canvasTex(256, 256, (c, w) => {
    c.fillStyle = '#1d1e20'; c.fillRect(0, 0, w, w);
    c.strokeStyle = '#3a3b3e'; c.lineWidth = 5;
    for (let r = 30; r < 128; r += 18) { c.beginPath(); c.arc(128, 128, r, 0, Math.PI * 2); c.stroke(); }
    for (let a = 0; a < 16; a++) { c.beginPath(); c.moveTo(128, 128); c.lineTo(128 + Math.cos(a * 0.3927) * 180, 128 + Math.sin(a * 0.3927) * 180); c.stroke(); }
    c.fillStyle = '#0d0b09'; c.beginPath(); c.arc(128, 128, 26, 0, Math.PI * 2); c.fill();
  });
}

/** 노면 소품: 맨홀(모델), 배수구·가로수 덮개(데칼) */
function buildRoadDetails(scene, track, lib, rand) {
  const L = track.length, m4 = [];
  // 맨홀: water_manhole_cover (테두리 위가 노면과 같게 3 cm 내려 놓음)
  const man = lib.instance('water_manhole_cover');
  if (man) {
    const parts = makeProto(flattenParts(man));
    for (let s = 20, i = 0; s < L - 5; s += MANHOLE_GAP, i++) m4.push(frameAt(track, s + rand() * 10, i % 2 ? 3.6 : 0.4, YR - 0.03, rand() * 6));
    for (const part of parts) {
      const im = new THREE.InstancedMesh(part.geometry, part.material, m4.length);
      m4.forEach((m, i) => im.setMatrixAt(i, m));
      im.computeBoundingSphere(); im.receiveShadow = true; im.name = 'manholes';
      scene.add(im);
    }
  }
  // 배수구: 양쪽 연석 앞
  const drainMat = new THREE.MeshStandardMaterial({ map: grateTex(), metalness: 0.7, roughness: 0.45, polygonOffset: true, polygonOffsetFactor: -3, envMapIntensity: ENV_WORLD });
  const drainGeo = new THREE.PlaneGeometry(0.9, 0.36).rotateX(-Math.PI / 2);
  const drains = [];
  for (let s = 12; s < L; s += DRAIN_GAP) {
    drains.push(frameAt(track, s, R_CURB + 0.2, YR + 0.003));
    drains.push(frameAt(track, s + 12, L_CURB - 0.2, YR + 0.003));
  }
  const di = new THREE.InstancedMesh(drainGeo, drainMat, drains.length);
  drains.forEach((m, i) => di.setMatrixAt(i, m));
  di.computeBoundingSphere(); di.receiveShadow = true; di.name = 'drains';
  scene.add(di);
}

// ───────────────────────── 가로등 ─────────────────────────
/** 가로등 위치 목록 (오른쪽 30 m, 왼쪽은 15 m 엇갈림) */
function lampSpots(track) {
  const out = [], L = track.length;
  for (let s = 0; s < L - 5; s += ROAD.lampGap) out.push({ s, side: 'R', lat: LAMP_LAT.R });
  for (let s = ROAD.lampGap / 2; s < L - 5; s += ROAD.lampGap) out.push({ s, side: 'L', lat: LAMP_LAT.L });
  return out;
}

function buildLamps(ctx) {
  const { scene, track, lib, chunks } = ctx;
  const lamp = lib.instance('street_lamp_01');
  const glowMat = ctx.lampGlow;
  const spots = lampSpots(track);
  const heads = [];
  if (lamp) {
    const parts = flattenParts(lamp).map((p) => (/glass|bulb/i.test(p.material.name) ? { ...p, material: glowMat } : p));
    const proto = makeProto(parts);
    for (const sp of spots) {
      const m = frameAt(track, sp.s, sp.lat, YW, sp.side === 'R' ? 0 : Math.PI);
      chunks.add(sp.s, sp.side, proto, m);
    }
  }
  for (const sp of spots) heads.push(worldAt(track, sp.s, sp.lat, YW + LAMP_HEAD_Y));
  // 후광 (Points 1개) + 노면 빛 웅덩이 (멀리 있는 가로등의 "발광 텍스처" — 실제 조명이 붙으면 줄어든다)
  const tex = glowTex();
  const halo = new THREE.Points(new THREE.BufferGeometry().setFromPoints(heads), new THREE.PointsMaterial({
    map: tex, color: 0xffcf8a, size: 2.2, sizeAttenuation: true, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, opacity: 0.85,
  }));
  halo.name = 'lamp-halos';
  scene.add(halo);
  const poolGeo = new THREE.PlaneGeometry(9, 9).rotateX(-Math.PI / 2);
  const pools = new THREE.InstancedMesh(poolGeo, new THREE.MeshBasicMaterial({
    map: tex, color: 0xffffff, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, polygonOffset: true, polygonOffsetFactor: -6,
  }), heads.length);
  const poolColor = new THREE.Color(0.55, 0.36, 0.16);
  heads.forEach((hp, i) => {
    pools.setMatrixAt(i, new THREE.Matrix4().makeTranslation(hp.x, YW + 0.012, hp.z));
    pools.setColorAt(i, poolColor);
  });
  pools.computeBoundingSphere(); pools.name = 'lamp-pools';
  scene.add(pools);

  // 실제 SpotLight 풀: 카메라 근처 6개 (앞 2개만 그림자). 개수가 일정해야 셰이더 재컴파일이 없다
  const lights = [];
  for (let i = 0; i < RENDER.lampSpots; i++) {
    const L = new THREE.SpotLight(0xffd7a0, 0, 26, 1.12, 0.75, 2);
    L.castShadow = i < RENDER.lampShadows;
    if (L.castShadow) {
      L.shadow.mapSize.set(RENDER.shadowMap, RENDER.shadowMap);
      L.shadow.camera.near = 0.5; L.shadow.camera.far = 14;
      L.shadow.bias = -0.0004; L.shadow.normalBias = 0.03;
    }
    scene.add(L, L.target);
    lights.push(L);
  }
  // 젖은 노면 반사 줄기 (가까운 가로등 8개, 카메라를 향해 길게)
  const streakTex = canvasTex(64, 256, (c, w, h) => {
    const g = c.createLinearGradient(0, 0, 0, h);
    g.addColorStop(0, 'rgba(255,255,255,0)'); g.addColorStop(0.45, 'rgba(255,255,255,0.9)'); g.addColorStop(0.55, 'rgba(255,255,255,0.9)'); g.addColorStop(1, 'rgba(255,255,255,0)');
    c.fillStyle = g; c.fillRect(0, 0, w, h);
    const g2 = c.createLinearGradient(0, 0, w, 0);
    g2.addColorStop(0, 'rgba(0,0,0,1)'); g2.addColorStop(0.5, 'rgba(0,0,0,0)'); g2.addColorStop(1, 'rgba(0,0,0,1)');
    c.globalCompositeOperation = 'destination-out'; c.fillStyle = g2; c.fillRect(0, 0, w, h);
  });
  const streaks = new THREE.InstancedMesh(new THREE.PlaneGeometry(1, 1).rotateX(-Math.PI / 2), new THREE.MeshBasicMaterial({
    map: streakTex, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, polygonOffset: true, polygonOffsetFactor: -8,
  }), 8);
  streaks.frustumCulled = false; streaks.name = 'wet-streaks';
  for (let i = 0; i < 8; i++) streaks.setColorAt(i, new THREE.Color(0, 0, 0));
  scene.add(streaks);
  return { heads, lights, pools, poolColor, halo, streaks };
}

const _order = [], _m = new THREE.Matrix4(), _c = new THREE.Color(), _v = new THREE.Vector3(), _v2 = new THREE.Vector3();
/** 가로등 갱신: 가까운 6개에 SpotLight, 웅덩이 데칼은 실제 조명이 붙은 만큼 줄이고, 반사 줄기 배치 */
function updateLamps(L, focus, cam, mapMode) {
  const { heads, lights, pools, poolColor, streaks } = L;
  _order.length = 0;
  for (let i = 0; i < heads.length; i++) _order.push([heads[i].distanceToSquared(focus), i]);
  _order.sort((a, b) => a[0] - b[0]);
  for (let i = 0; i < heads.length; i++) pools.setColorAt(i, poolColor);
  lights.forEach((l, k) => {
    const [d2, i] = _order[k], d = Math.sqrt(d2), w = mapMode ? 0 : THREE.MathUtils.clamp((52 - d) / 14, 0, 1);
    const hp = heads[i];
    l.position.set(hp.x, hp.y - 0.05, hp.z);
    l.target.position.set(hp.x, 0, hp.z);
    l.intensity = 240 * w;   // 보이기/숨기기 대신 세기만 바꾼다 (광원 수가 바뀌면 셰이더 재컴파일)
    pools.setColorAt(i, _c.copy(poolColor).multiplyScalar(1 - 0.85 * w));
  });
  pools.instanceColor.needsUpdate = true;
  // 반사 줄기: 반사점 = 카메라 → (노면 아래로 뒤집은 가로등) 직선이 노면과 만나는 점
  const hc = Math.max(0.3, cam.y - YR);
  let n = 0;
  if (!mapMode) {
    for (let k = 0; k < Math.min(14, _order.length) && n < 8; k++) {
      const hp = heads[_order[k][1]], hl = hp.y - YR;
      const t = hc / (hc + hl);
      _v.set(cam.x + (hp.x - cam.x) * t, YR + 0.006, cam.z + (hp.z - cam.z) * t);
      const dx = cam.x - hp.x, dz = cam.z - hp.z, dist = Math.hypot(dx, dz);
      if (dist < 4 || dist > 90) continue;
      const len = THREE.MathUtils.clamp(2.5 + dist * 0.12, 3, 9), fade = THREE.MathUtils.clamp((90 - dist) / 40, 0, 1);
      _q.setFromAxisAngle(UP, Math.atan2(dx, dz));
      _m.compose(_v, _q, _v2.set(0.7, 1, len));
      streaks.setMatrixAt(n, _m);
      streaks.setColorAt(n, _c.setRGB(0.42, 0.27, 0.12).multiplyScalar(fade));
      n++;
    }
  }
  for (let k = n; k < 8; k++) streaks.setColorAt(k, _c.setRGB(0, 0, 0));
  streaks.instanceMatrix.needsUpdate = true;
  streaks.instanceColor.needsUpdate = true;
}

// ───────────────────────── 가로수 ─────────────────────────
/** 같은 모델을 정사영으로 렌더해 임포스터 텍스처(옆·위)를 굽는다 */
function bakeImpostor(renderer, parts) {
  const box = partsBox(parts), size = box.getSize(new THREE.Vector3()), c = box.getCenter(new THREE.Vector3());
  const sc = new THREE.Scene();
  for (const p of parts) sc.add(new THREE.Mesh(p.geometry, p.material));
  sc.add(new THREE.AmbientLight(0xffffff, 2.2));
  const key = new THREE.DirectionalLight(0xffffff, 1.4); key.position.set(0.4, 1, 0.8); sc.add(key);
  const w = Math.max(size.x, size.z) * 1.02;
  const mk = (W, H) => {
    const rt = new THREE.WebGLRenderTarget(W, H, { generateMipmaps: true, minFilter: THREE.LinearMipmapLinearFilter, magFilter: THREE.LinearFilter });
    return rt;
  };
  const side = mk(512, Math.round(512 * Math.min(2, size.y / w))), top = mk(512, 512);
  const prevTarget = renderer.getRenderTarget(), prevColor = renderer.getClearColor(new THREE.Color()), prevAlpha = renderer.getClearAlpha();
  const camS = new THREE.OrthographicCamera(-w / 2, w / 2, box.max.y, box.min.y, 0.1, 200);
  camS.position.set(c.x, 0, c.z + 100); camS.lookAt(c.x, 0, c.z);
  const camT = new THREE.OrthographicCamera(-w / 2, w / 2, w / 2, -w / 2, 0.1, 200);
  camT.position.set(c.x, box.max.y + 50, c.z); camT.up.set(0, 0, -1); camT.lookAt(c.x, 0, c.z);
  renderer.setClearColor(0x000000, 0);
  for (const [rt, cam] of [[side, camS], [top, camT]]) { renderer.setRenderTarget(rt); renderer.clear(); renderer.render(sc, cam); }
  renderer.setRenderTarget(prevTarget);
  renderer.setClearColor(prevColor, prevAlpha);
  return { side: side.texture, top: top.texture, size, center: c, box };
}

function buildTrees(ctx) {
  const { scene, track, lib, renderer, rand, reserve } = ctx;
  const tree = lib.instance('island_tree_01');
  if (!tree) return null;
  const parts = flattenParts(tree);
  for (const p of parts) p.material.envMapIntensity = 0.25;
  const spotsT = [];
  for (const side of ['R', 'L']) {
    for (let s = (side === 'R' ? 7.5 : 0); s < track.length - 4; s += ROAD.treeGap) {
      if (reserve(s, side, 'tree')) continue;
      const scale = 0.95 + rand() * 0.3, yaw = rand() * Math.PI * 2;
      const pos = worldAt(track, s, TREE_LAT[side], YW - 0.02);
      spotsT.push({ pos, m: new THREE.Matrix4().compose(pos, _q.setFromAxisAngle(UP, yaw), _sc.set(scale, scale, scale)).clone(), s, side });
    }
  }
  // 가까운 3D 나무 (InstancedMesh, 매 0.25 s 다시 고름)
  const near = parts.map((p) => {
    const im = new THREE.InstancedMesh(p.geometry, p.material, RENDER.nearTrees);
    im.count = 0; im.frustumCulled = false; im.castShadow = false; im.name = 'trees-near';
    scene.add(im);
    return im;
  });
  // 임포스터 (옆 십자 2장 + 위 1장)
  const imp = bakeImpostor(renderer, parts), { size, box } = imp, w = Math.max(size.x, size.z);
  const crossGeo = mergeGeometries([
    new THREE.PlaneGeometry(w, size.y).translate(0, (box.max.y + box.min.y) / 2, 0),
    new THREE.PlaneGeometry(w, size.y).translate(0, (box.max.y + box.min.y) / 2, 0).rotateY(Math.PI / 2),
  ]);
  const topGeo = new THREE.PlaneGeometry(w, w).rotateX(-Math.PI / 2).translate(0, box.min.y + size.y * 0.72, 0);
  for (const g of [crossGeo, topGeo]) { const n = g.attributes.normal; for (let i = 0; i < n.count; i++) n.setXYZ(i, 0, 1, 0); }
  const impMat = (map) => new THREE.MeshStandardMaterial({ map, alphaTest: 0.5, side: THREE.DoubleSide, roughness: 0.95, envMapIntensity: 0.25 });
  const far = [new THREE.InstancedMesh(crossGeo, impMat(imp.side), spotsT.length), new THREE.InstancedMesh(topGeo, impMat(imp.top), spotsT.length)];
  far.forEach((im) => { spotsT.forEach((t, i) => im.setMatrixAt(i, t.m)); im.computeBoundingSphere(); im.name = 'trees-impostor'; scene.add(im); });
  // 나무 아래 덮개 (데칼)
  const gm = new THREE.InstancedMesh(new THREE.PlaneGeometry(1.2, 1.2).rotateX(-Math.PI / 2), new THREE.MeshStandardMaterial({
    map: treeGrateTex(), metalness: 0.6, roughness: 0.55, polygonOffset: true, polygonOffsetFactor: -3, envMapIntensity: ENV_WORLD,
  }), spotsT.length);
  spotsT.forEach((t, i) => gm.setMatrixAt(i, new THREE.Matrix4().makeTranslation(t.pos.x, YW + 0.004, t.pos.z)));
  gm.computeBoundingSphere(); gm.receiveShadow = true; gm.name = 'tree-grates';
  scene.add(gm);
  return { spots: spotsT, near, far, timer: 0, zero: new THREE.Matrix4().makeScale(0, 0, 0), nearSet: new Set() };
}

function updateTrees(T, cam, dt, mapMode) {
  if (!T) return;
  if ((T.timer -= dt) > 0) return;
  T.timer = 0.25;
  const cand = [];
  if (!mapMode) for (let i = 0; i < T.spots.length; i++) {
    const d = T.spots[i].pos.distanceToSquared(cam);
    if (d < RENDER.nearTreeDist ** 2) cand.push([d, i]);
  }
  cand.sort((a, b) => a[0] - b[0]);
  const next = new Set(cand.slice(0, RENDER.nearTrees).map((c) => c[1]));
  for (const i of T.nearSet) if (!next.has(i)) for (const im of T.far) im.setMatrixAt(i, T.spots[i].m);
  for (const i of next) for (const im of T.far) im.setMatrixAt(i, T.zero);
  for (const im of T.far) im.instanceMatrix.needsUpdate = true;
  let k = 0;
  for (const i of next) { for (const im of T.near) im.setMatrixAt(k, T.spots[i].m); k++; }
  for (const im of T.near) { im.count = k; im.instanceMatrix.needsUpdate = true; }
  T.nearSet = next;
}

// ───────────────────────── 건물 (파사드 모듈) ─────────────────────────
/** 키트 좌표의 모듈 정의 [T.x, T.y, 폭] — 3 m 격자, 로컬 x∈[-폭,0], y∈[0,3], +Z 가 거리 쪽 */
const KITS = {
  apt: {
    id: 'modular_urban_apartments_facade',
    ground: [[11, 0], [15, 0], [19, 0]], wall: [0, 0], shopBays: [[11, 4], [19, 4]],
    upper: [[[11, 4], [11, 8]], [[15, 4], [15, 8]], [[19, 4], [19, 8]], [[11, 12], [15, 12]]],
    base: { T: [0, -2], y: [0, 0.8], z: [-0.1, 0.3] },
    top: [{ T: [0, 3.4], y: [0, 0.22], z: [-0.1, 0.12], dy: 0 }, { T: [0, 4], y: [0, 0.8], z: [-0.2, 0.42], dy: 0.2 }],
  },
  fac: {
    id: 'modular_factory_facade',
    ground: [[11, 0], [15, 0], [19, 0], [23, 0]], wall: [-17, 0], shopBays: [[3, 4], [15, 4]],
    upper: [[[3, 4], [3, 8]], [[7, 4], [7, 8]], [[11, 4], [15, 4]], [[-9, 4], [-1, 4]]],
    base: { T: [-17, -2], y: [0, 0.8], z: [-0.1, 0.3] },
    top: [{ T: [-17, 4], y: [-0.05, 0.12], z: [-0.2, 0.08], dy: 0 }, { T: [-17, 5], y: [0, 0.18], z: [-0.2, 0.1], dy: 0.12 },
      { T: [-17, 6], y: [0, 0.17], z: [-0.15, 0.1], dy: 0.29 }, { T: [-17, 7], y: [0, 0.34], z: [-0.05, 0.18], dy: 0.45 }],
  },
};

/** 키트에서 모듈 프로토를 잘라 만든다 */
function buildKit(lib, key, glassLit, glassDark) {
  const K = KITS[key], root = lib.models.get(K.id)?.gltf.scene;
  if (!root) return null;
  const cut = new KitCutter(root);
  const mod = ([x, y], w = 3) => makeProto(cut.cut(new THREE.Box3(new THREE.Vector3(x - w - 0.05, y - 0.05, -0.65), new THREE.Vector3(x + 0.05, y + 3.05, 0.45)), new THREE.Vector3(x, y, 0)), { glassLit, glassDark });
  const band = (b) => makeProto(cut.cut(new THREE.Box3(new THREE.Vector3(b.T[0] - 3.05, b.T[1] + b.y[0] - 0.02, b.z[0]), new THREE.Vector3(b.T[0] + 0.05, b.T[1] + b.y[1] + 0.02, b.z[1])), new THREE.Vector3(b.T[0], b.T[1], 0)));
  // 벽 재질 (코어 상자 옆면에 같이 쓴다)
  let wallMat = null;
  for (const s of cut.sets) if (/plaster|brick/i.test(s.material.name) && !/door/i.test(s.material.name)) { wallMat = s.material; break; }
  return {
    key,
    ground: K.ground.map((t) => mod(t)), wall: mod(K.wall), shop: K.shopBays.map((t) => mod(t)),
    upper: K.upper.map((pair) => pair.map((t) => mod(t))),
    base: band(K.base), top: K.top.map((b) => ({ proto: band(b), dy: b.dy })), wallMat,
  };
}

/** 지향 사각형(장면 xz) 겹침 — 분리축 */
function obbOverlap(a, b) {
  for (const poly of [a, b]) for (let i = 0; i < 4; i++) {
    const p = poly[i], q = poly[(i + 1) % 4], nx = q.z - p.z, nz = p.x - q.x;
    let amin = Infinity, amax = -Infinity, bmin = Infinity, bmax = -Infinity;
    for (const v of a) { const d = v.x * nx + v.z * nz; amin = Math.min(amin, d); amax = Math.max(amax, d); }
    for (const v of b) { const d = v.x * nx + v.z * nz; bmin = Math.min(bmin, d); bmax = Math.max(bmax, d); }
    if (amax < bmin || bmax < amin) return false;
  }
  return true;
}

function buildBuildings(ctx) {
  const { scene, track, lib, chunks, rand, reserve, mats } = ctx;
  const glassLit = new THREE.MeshBasicMaterial({ color: 0xffffff, name: 'window-lit', toneMapped: true });
  const glassDark = new THREE.MeshStandardMaterial({ color: 0x07090c, roughness: 0.07, metalness: 0.2, envMapIntensity: 0.9, name: 'window-dark' });
  const kits = ['apt', 'fac'].map((k) => buildKit(lib, k, glassLit, glassDark)).filter(Boolean);
  ctx.footprints = [];
  if (!kits.length) return;
  const center = [];
  for (let i = 0; i < track.n; i += 4) { const p = track.offsetAt(i * track.step, ROAD_MID); center.push(p.x, -p.y); }
  const clearOfRoad = (pts, margin) => {
    for (const v of pts) for (let i = 0; i < center.length; i += 2) if ((center[i] - v.x) ** 2 + (center[i + 1] - v.z) ** 2 < margin * margin) return false;
    return true;
  };
  const cores = new Map();   // material → GeoBuilder
  const roofB = new GeoBuilder();
  const coreFor = (m) => { if (!cores.has(m)) cores.set(m, new GeoBuilder()); return cores.get(m); };
  const warm = [new THREE.Color(1.25, 0.82, 0.45), new THREE.Color(1.1, 0.9, 0.62), new THREE.Color(1.35, 0.7, 0.32)];
  const cool = [new THREE.Color(0.62, 0.8, 1.15), new THREE.Color(0.8, 0.88, 1.0)];
  const litColor = () => (rand() < 0.72 ? warm[(rand() * warm.length) | 0] : cool[(rand() * cool.length) | 0]).clone().multiplyScalar(0.55 + rand() * 0.6);
  const shutter = ctx.shutterProto;
  let count = 0;

  /** 건물 1동: B = 건물 프레임(정면 오른쪽 아래, +X 정면 따라, +Z 거리 쪽) */
  const place = (B, s, side, nb, nf, depth, kit) => {
    const W = nb * 3, H = nf * 3;
    const corners = [[-0.3, 0.7], [W + 0.3, 0.7], [W + 0.3, -depth], [-0.3, -depth]].map(([x, z]) => new THREE.Vector3(x, 0, z).applyMatrix4(B));
    if (!clearOfRoad(corners, ROAD_HALF_CLEAR - 0.4)) return false;
    for (const f of ctx.footprints) if (obbOverlap(f, corners)) return false;
    ctx.footprints.push(corners);
    const upperPick = kit.upper[(rand() * kit.upper.length) | 0], door = (rand() * nb) | 0, M = new THREE.Matrix4();
    for (let j = 0; j < nb; j++) {
      const X = j * 3 + 3;
      // 1층: 출입문 1칸 + 상점 창/닫힌 셔터/벽
      let g, lit = rand() < 0.55, shut = false;
      if (j === door) g = kit.ground[(rand() * kit.ground.length) | 0];
      else { const r = rand(); if (r < 0.45) g = kit.shop[(rand() * kit.shop.length) | 0]; else if (r < 0.7) { g = kit.wall; shut = true; } else g = kit.wall; }
      M.makeTranslation(X, 0, 0).premultiply(B);
      chunks.add(s, side, g, M.clone(), { lit, color: litColor() });
      chunks.add(s, side, kit.base, M.clone());
      if (shut && shutter) chunks.add(s, side, shutter, new THREE.Matrix4().makeTranslation(X - 1.5, 0, 0.03).premultiply(B));
      for (let f = 1; f < nf; f++) {
        const mod = upperPick[(j + f) % 2 === 0 ? 0 : 1];
        M.makeTranslation(X, f * 3, 0).premultiply(B);
        chunks.add(s, side, mod, M.clone(), { lit: rand() < 0.3, color: litColor() });
      }
      for (const t of kit.top) chunks.add(s, side, t.proto, new THREE.Matrix4().makeTranslation(X, H + t.dy, 0).premultiply(B));
    }
    // 코어 상자 (옆·뒤 벽 = 키트 벽 재질, 지붕 = 콘크리트), 양 끝 마감
    const wallB = coreFor(kit.wallMat ?? mats.brick);
    wallB.box(0, 0, -depth, W, H + 0.3, -0.5, B, ['px', 'nx', 'nz', 'pz']);
    wallB.box(-0.25, 0, -0.5, 0, H + 0.25, 0.18, B, ['nx', 'pz', 'px']);
    wallB.box(W, 0, -0.5, W + 0.25, H + 0.25, 0.18, B, ['px', 'pz', 'nx']);
    roofB.box(0, H + 0.3, -depth, W, H + 0.32, -0.5, B, ['py']);
    count++;
    return true;
  };

  // 직선 구간: 좌우 모두 연속 블록. 원호: 바깥쪽만 2칸 건물, 급커브 안쪽은 광장
  let sAcc = 0;
  for (const seg of track.segments) {
    const s0 = sAcc, s1 = sAcc + seg.len;
    sAcc = s1;
    for (const side of ['R', 'L']) {
      const inside = side === 'L';
      if (seg.type === 'arc' && inside && seg.R < 50) continue;
      let s = s0 + (seg.type === 'arc' ? 4 : 10);
      while (s < s1 - 8) {
        const kit = kits[(rand() * kits.length) | 0];
        const nb = seg.type === 'arc' ? 2 : 3 + ((rand() * 4) | 0), W = nb * 3;
        const along = seg.type === 'arc' ? (W / seg.R) * (seg.R + (inside ? -10 : 10)) : W; // 원호 위 s 길이 (벽면 반지름 보정)
        const sa = s, sb = s + (seg.type === 'arc' ? W * (seg.R / (seg.R + (side === 'R' ? 4.75 : -8.25))) : W);
        if (sb > s1 - 4) break;
        if (reserve(sa, side, 'bldg') || reserve(sb, side, 'bldg') || reserve((sa + sb) / 2, side, 'bldg')) { s += 3; continue; }
        const nf = 3 + ((rand() * 4) | 0), depth = 10 + rand() * 6;
        const B = side === 'R' ? frameAt(track, sb, R_FACADE, 0, Math.PI) : frameAt(track, sa, L_FACADE, 0, 0);
        if (seg.type === 'arc') { // 원호: 현(chord) 방향으로 정렬
          const a = worldAt(track, side === 'R' ? sb : sa, side === 'R' ? R_FACADE : L_FACADE), b = worldAt(track, side === 'R' ? sa : sb, side === 'R' ? R_FACADE : L_FACADE);
          const yaw = Math.atan2(-(b.z - a.z), b.x - a.x);
          B.compose(a, _q.setFromAxisAngle(UP, yaw), _sc.set(1, 1, 1));
        }
        place(B, (sa + sb) / 2, side, nb, nf, depth, kit);
        s = sb + (rand() < 0.25 ? 2 + rand() * 4 : 0.6) + (along - along);
      }
    }
  }
  for (const [mat, b] of cores) addMesh(scene, b.build(), mat, { name: 'building-cores' });
  addMesh(scene, roofB.build(), mats.roof, { name: 'roofs' });
  ctx.buildingCount = count;
}

/** 닫힌 셔터 프로토 (painted_metal_shutter PBR + 셔터 박스) — 로컬 x 중심 0, 바닥 0 */
function makeShutterProto(mats) {
  const g = new GeoBuilder();
  const I = new THREE.Matrix4();
  g.box(-1.35, 0.05, 0, 1.35, 2.55, 0.02, I, ['pz']);
  g.box(-1.45, 2.55, -0.05, 1.45, 2.9, 0.22, I, ['pz', 'py', 'ny', 'px', 'nx']);
  return makeProto([{ geometry: g.build(), material: mats.shutter }]);
}

// ───────────────────────── 가로 시설 ─────────────────────────
function buildProps(ctx) {
  const { scene, track, lib, chunks, rand, reserve } = ctx;
  const L = track.length;
  const P = (id) => { const o = lib.instance(id); return o ? makeProto(flattenParts(o)) : null; };
  const bench = P('modular_street_seating'), box = P('utility_box_01'), bag = P('trashbag');
  let hydrant = null;
  const hy = lib.models.get('fire_hydrant')?.gltf.scene;
  if (hy) hydrant = makeProto(new KitCutter(hy).cut(new THREE.Box3(new THREE.Vector3(-0.7, -0.1, -0.5), new THREE.Vector3(-0.02, 1.2, 0.5)), new THREE.Vector3(-0.3, 0, 0)));
  const counts = { bench: 0, hydrant: 0, box: 0, bag: 0, pole: 0, phone: 0 };
  for (const side of ['R', 'L']) {
    const toRoad = side === 'R' ? Math.PI : 0;                 // 로컬 +Z(앞)가 도로 쪽을 보게
    const back = side === 'R' ? R_OUT + 0.55 : L_OUT - 0.55, curb = side === 'R' ? R_WALK - 0.35 : L_WALK + 0.35;
    for (let s = 18 + (side === 'L' ? 40 : 0); s < L - 10; s += 85 + rand() * 30) {
      if (bench && !reserve(s, side, 'prop')) { chunks.add(s, side, bench, frameAt(track, s, back + (side === 'R' ? 0.1 : -0.1), YW, toRoad)); counts.bench++; }
    }
    for (let s = 50 + (side === 'L' ? 60 : 0); s < L - 10; s += 130 + rand() * 40) {
      if (hydrant && !reserve(s, side, 'prop')) { chunks.add(s, side, hydrant, frameAt(track, s, curb, YW, toRoad + rand())); counts.hydrant++; }
    }
    for (let s = 70 + (side === 'L' ? 25 : 0); s < L - 10; s += 95 + rand() * 50) {
      if (box && !reserve(s, side, 'prop')) { chunks.add(s, side, box, frameAt(track, s, back - (side === 'R' ? -0.05 : 0.05), YW, toRoad)); counts.box++; }
    }
    for (let s = 30 + rand() * 20; s < L - 10; s += 45 + rand() * 40) {
      if (!bag || reserve(s, side, 'prop')) continue;
      const n = 2 + ((rand() * 2) | 0);
      for (let k = 0; k < n; k++) { chunks.add(s, side, bag, frameAt(track, s + k * 0.5 - 0.4, back + (rand() - 0.5) * 0.3, YW, rand() * 6, 0.85 + rand() * 0.3)); counts.bag++; }
    }
  }
  // 전신주 + 전선 (오른쪽 직선 구간, 45 m 간격)
  const poles = lib.models.get('modular_electricity_poles')?.gltf.scene;
  if (poles) {
    const proto = makeProto(new KitCutter(poles).cut(new THREE.Box3(new THREE.Vector3(-7.6, -0.2, -1.7), new THREE.Vector3(-5.4, 7.2, 0.6)), new THREE.Vector3(-6.5, 0, 0)));
    const wires = new GeoBuilder(), wireMat = new THREE.MeshStandardMaterial({ color: 0x111214, roughness: 0.6, metalness: 0.4 });
    const tube = (a, b) => {
      const mid = a.clone().lerp(b, 0.5); mid.y -= a.distanceTo(b) * 0.035;
      const g = new THREE.TubeGeometry(new THREE.QuadraticBezierCurve3(a, mid, b), 12, 0.012, 4);
      wireGeos.push(g);
    };
    const wireGeos = [];
    let sAcc = 0;
    for (const seg of track.segments) {
      const s0 = sAcc; sAcc += seg.len;
      if (seg.type !== 'line' || seg.len < 100) continue;
      let prev = null;
      for (let s = s0 + 8; s < sAcc - 8; s += 45) {
        if (reserve(s, 'R', 'pole')) { prev = null; continue; }
        const lat = R_OUT + 0.35;
        chunks.add(s, 'R', proto, frameAt(track, s, lat, YW, Math.PI / 2));
        counts.pole++;
        const tops = [5.55, 5.25, 4.9].map((y, k) => worldAt(track, s, lat + (k - 1) * 0.35, YW + y));
        if (prev) for (let k = 0; k < 3; k++) tube(prev[k], tops[k]);
        prev = tops;
      }
    }
    if (wireGeos.length) addMesh(scene, mergeGeometries(wireGeos), wireMat, { name: 'wires' });
  }
  // 공중전화 (기둥 위)
  const phone = lib.instance('korean_public_payphone_01');
  if (phone) {
    const parts = flattenParts(phone);
    const post = new GeoBuilder().box(-0.05, 0, -0.05, 0.05, 1.05, 0.05, new THREE.Matrix4(), ['px', 'nx', 'pz', 'nz', 'py']);
    const proto = makeProto([...parts.map((p) => ({ geometry: p.geometry.clone().translate(0, 0.78, 0.06), material: p.material })),
      { geometry: post.build(), material: new THREE.MeshStandardMaterial({ color: 0x8a9096, metalness: 0.8, roughness: 0.35 }) }]);
    for (const [s, side] of [[BAKERY.s1 + 9, 'R'], [480, 'L'], [905, 'R']]) {
      chunks.add(s, side, proto, frameAt(track, s, side === 'R' ? R_OUT + 0.45 : L_OUT - 0.45, YW, side === 'R' ? Math.PI : 0)); counts.phone++;
    }
  }
  ctx.propCounts = counts;
}

// ───────────────────────── 원경 블록·실루엣 ─────────────────────────
function windowTex(rand, cols = 16, rows = 32) {
  return canvasTex(cols * 8, rows * 8, (c, w, h) => {
    c.fillStyle = '#000'; c.fillRect(0, 0, w, h);
    for (let y = 0; y < rows; y++) for (let x = 0; x < cols; x++) {
      if (rand() < 0.26) { c.fillStyle = rand() < 0.7 ? '#ffc874' : '#a8d4ff'; c.globalAlpha = 0.5 + rand() * 0.5; c.fillRect(x * 8 + 2, y * 8 + 2, 4, 5); }
    }
    c.globalAlpha = 1;
  });
}

function buildBackdrop(ctx) {
  const { scene, track, rand } = ctx;
  const b = track.bounds, blocks = [], fps = ctx.footprints;
  const center = [];
  for (let i = 0; i < track.n; i += 8) { const p = track.offsetAt(i * track.step, ROAD_MID); center.push(p.x, -p.y); }
  const far = (x, z, r) => { for (let i = 0; i < center.length; i += 2) if ((center[i] - x) ** 2 + (center[i + 1] - z) ** 2 < r * r) return false; return true; };
  for (let k = 0; k < 900 && blocks.length < 260; k++) {
    const w = 14 + rand() * 22, d = 14 + rand() * 22, h = 12 + rand() * 40;
    const x = b.minX - 220 + rand() * (b.maxX - b.minX + 440), z = -(b.minY - 220 + rand() * (b.maxY - b.minY + 440));
    if (!far(x, z, 30 + Math.max(w, d) * 0.72)) continue;
    const yaw = rand() * Math.PI;
    const poly = [[-w / 2, -d / 2], [w / 2, -d / 2], [w / 2, d / 2], [-w / 2, d / 2]].map(([px, pz]) => new THREE.Vector3(x + px * Math.cos(yaw) + pz * Math.sin(yaw), 0, z - px * Math.sin(yaw) + pz * Math.cos(yaw)));
    if (fps.some((f) => obbOverlap(f, poly))) continue;
    blocks.push([x, z, w, d, h, yaw]);
  }
  const geo = new THREE.BoxGeometry(1, 1, 1).translate(0, 0.5, 0);
  const winA = windowTex(rand);
  winA.wrapS = winA.wrapT = THREE.RepeatWrapping;
  const mat = new THREE.MeshStandardMaterial({ color: 0x23262c, roughness: 0.9, emissive: 0xffffff, emissiveMap: winA, emissiveIntensity: 0.9, envMapIntensity: 0.2 });
  const mk = (list, m, name) => {
    const im = new THREE.InstancedMesh(geo, m, list.length), M = new THREE.Matrix4(), col = new THREE.Color();
    list.forEach(([x, z, w, d, h, yaw], i) => {
      M.compose(_p.set(x, 0, z), _q.setFromAxisAngle(UP, yaw), _sc.set(w, h, d));
      im.setMatrixAt(i, M);
      im.setColorAt(i, col.setHSL(0.6, 0.08, 0.55 + rand() * 0.4));
    });
    im.computeBoundingSphere(); im.name = name;
    scene.add(im);
    return im;
  };
  mk(blocks, mat, 'back-blocks');
  // 먼 스카이라인: 안개 대신 하늘보다 살짝 어두운 실루엣 + 희미한 창
  const sky = [];
  for (let k = 0; k < 170; k++) {
    const a = rand() * Math.PI * 2, r = 700 + rand() * 500;
    sky.push([Math.cos(a) * r, Math.sin(a) * r, 30 + rand() * 50, 30 + rand() * 50, 40 + rand() * 150, rand() * Math.PI]);
  }
  const winB = windowTex(rng(SEED + 7), 12, 40);
  const silMat = new THREE.MeshBasicMaterial({ color: 0x0b0d14, fog: false });
  silMat.onBeforeCompile = (sh) => { // 실루엣에도 아주 희미한 창 불빛 (안개 없이)
    sh.uniforms.winMap = { value: winB };
    sh.vertexShader = sh.vertexShader.replace('#include <common>', '#include <common>\nvarying vec2 vWUv;').replace('#include <begin_vertex>', '#include <begin_vertex>\nvWUv = vec2(uv.x * 3.0, uv.y * 4.0);');
    sh.fragmentShader = sh.fragmentShader.replace('#include <common>', '#include <common>\nuniform sampler2D winMap; varying vec2 vWUv;')
      .replace('#include <opaque_fragment>', 'outgoingLight += texture2D(winMap, fract(vWUv)).rgb * 0.16;\n#include <opaque_fragment>');
  };
  mk(sky, silMat, 'skyline');
  ctx.backdropCount = blocks.length + sky.length;
}

// ───────────────────────── 베이커리 ─────────────────────────
function bakerySignTex() {
  return canvasTex(1024, 128, (c, w, h) => {
    const g = c.createLinearGradient(0, 0, 0, h); g.addColorStop(0, '#3a2414'); g.addColorStop(1, '#22140a');
    c.fillStyle = g; c.fillRect(0, 0, w, h);
    c.strokeStyle = COLORS.champ; c.lineWidth = 4; c.strokeRect(6, 6, w - 12, h - 12);
    c.fillStyle = '#ffe2a6'; c.textAlign = 'center'; c.textBaseline = 'middle';
    c.font = `bold 78px ${FONT}`; c.fillText('달빛 베이커리', w / 2 - 80, h / 2 + 4);
    c.font = `500 30px ${FONT}`; c.fillStyle = COLORS.champ; c.fillText('BAKERY · 케이크', w / 2 + 330, h / 2 + 6);
  });
}
function chalkTex() {
  return canvasTex(256, 384, (c, w, h) => {
    c.fillStyle = '#1d2420'; c.fillRect(0, 0, w, h);
    c.fillStyle = 'rgba(255,255,255,0.05)'; for (let i = 0; i < 60; i++) c.fillRect(Math.random() * w, Math.random() * h, 30, 2);
    c.textAlign = 'center'; c.fillStyle = '#f4efe2';
    c.font = `bold 46px ${FONT}`; c.fillText('베이커리', w / 2, 70);
    c.font = `30px ${FONT}`; c.fillStyle = '#ffd7e0'; c.fillText('오늘의 케이크', w / 2, 140);
    c.fillStyle = '#f4efe2'; c.font = `26px ${FONT}`; c.fillText('딸기 초코 · 당근', w / 2, 190);
    c.fillText('크루아상 갓 나옴', w / 2, 240);
    c.fillStyle = COLORS.champ; c.fillText('OPEN ~ 23:00', w / 2, 320);
  });
}
function interiorTex() {
  return canvasTex(512, 256, (c, w, h) => {
    const g = c.createLinearGradient(0, 0, 0, h); g.addColorStop(0, '#f7dcae'); g.addColorStop(1, '#c98f52');
    c.fillStyle = g; c.fillRect(0, 0, w, h);
    c.fillStyle = '#7a4a26';
    for (const y of [70, 140, 205]) c.fillRect(20, y, w - 40, 8);
    c.fillStyle = '#b8742e';
    for (const y of [70, 140, 205]) for (let x = 34; x < w - 40; x += 34) { c.beginPath(); c.ellipse(x, y - 12, 13, 10, 0, 0, Math.PI * 2); c.fill(); }
  });
}

function buildBakery(ctx) {
  const { scene, track, lib, mats, chunks } = ctx;
  const g = new THREE.Group(); g.name = 'bakery';
  const W = 18, H1 = 3;   // 6칸: 앞 2칸 닫힌 상점(셔터), 뒤 4칸 베이커리
  const B = frameAt(track, BAKERY.s1, R_FACADE, 0, Math.PI);
  g.applyMatrix4(B);
  scene.add(g);
  const brick = mats.brick, add = (geo, mat, x, y, z, o = {}) => { const m = addMesh(g, geo, mat, o); m.position.set(x, y, z); return m; };
  const gb = new GeoBuilder(), I = new THREE.Matrix4();
  // 1층 골조: 기둥·하부 벽·간판대 (brick_4)
  for (const [x0, x1] of [[0, 0.5], [5.8, 6.4], [14.2, 14.6], [17.5, 18]]) gb.box(x0, 0, -0.35, x1, 3.0, 0.12, I, ['pz', 'px', 'nx']);
  gb.box(6.4, 0, -0.3, 14.2, 0.45, 0.08, I, ['pz', 'py']);
  gb.box(0, 2.4, -0.35, W, 3.0, 0.14, I, ['pz', 'ny']);
  add(gb.build(), brick, 0, 0, 0, { receive: true });
  // 위층: 아파트 파사드 모듈 2층 + 크라운, 코어
  const kit = ctx.aptKit;
  if (kit) {
    const M = new THREE.Matrix4();
    for (let j = 0; j < 6; j++) {
      for (let f = 1; f < 3; f++) chunks.add(BAKERY.s0 + 9, 'R', kit.upper[0][f - 1], M.makeTranslation(j * 3 + 3, f * 3, 0).premultiply(B).clone(), { lit: (j + f) % 3 === 0, color: new THREE.Color(1.2, 0.8, 0.45) });
      for (const t of kit.top) chunks.add(BAKERY.s0 + 9, 'R', t.proto, new THREE.Matrix4().makeTranslation(j * 3 + 3, 9 + t.dy, 0).premultiply(B));
    }
  }
  const core = new GeoBuilder();
  core.box(0, 0, -14, W, 9.3, -0.5, I, ['px', 'nx', 'nz']);
  core.box(0, 3, -0.5, W, 9.3, -0.45, I, ['pz']);
  add(core.build(), kit?.wallMat ?? brick, 0, 0, 0);
  add(new GeoBuilder().box(0, 9.3, -14, W, 9.32, -0.5, I, ['py']).build(), mats.roof, 0, 0, 0);
  ctx.footprints.push([[-0.3, 1], [W + 0.3, 1], [W + 0.3, -14], [-0.3, -14]].map(([x, z]) => new THREE.Vector3(x, 0, z).applyMatrix4(B)));
  // 실내 (따뜻한 빛): 뒷벽 그림, 천장 조명, 바닥, 진열대
  const interior = new THREE.MeshStandardMaterial({ map: interiorTex(), emissive: 0xffffff, emissiveMap: null, emissiveIntensity: 0.0, roughness: 0.8, envMapIntensity: 0.2 });
  interior.emissive.set(0x5a3a1c);
  const room = new GeoBuilder();
  room.box(6.4, 0.0, -3.4, 17.5, 0.02, -0.2, I, ['py']);
  room.box(6.4, 2.38, -3.4, 17.5, 2.4, -0.2, I, ['ny']);
  room.box(6.4, 0, -3.4, 6.45, 2.4, -0.2, I, ['px']);
  room.box(17.45, 0, -3.4, 17.5, 2.4, -0.2, I, ['nx']);
  add(room.build(), new THREE.MeshStandardMaterial({ color: 0x8a6440, roughness: 0.7, emissive: 0x2a1708, envMapIntensity: 0.2 }), 0, 0, 0);
  add(new THREE.PlaneGeometry(11, 2.4), interior, 11.95, 1.2, -3.38);
  for (let x = 8; x < 17; x += 2.2) add(new THREE.PlaneGeometry(1.2, 0.25).rotateX(Math.PI / 2), new THREE.MeshBasicMaterial({ color: 0xfff0d0 }), x, 2.37, -1.6);
  const counter = add(new THREE.BoxGeometry(6.5, 0.95, 0.7), new THREE.MeshStandardMaterial({ color: 0x4a2c17, roughness: 0.55, envMapIntensity: 0.4 }), 11.2, 0.475, -1.9);
  counter.receiveShadow = true;
  add(new THREE.BoxGeometry(7.2, 0.06, 0.55), new THREE.MeshStandardMaterial({ color: 0xd8c7a8, roughness: 0.5 }), 10.3, 0.75, -0.55); // 창가 진열 선반
  const warm = new THREE.PointLight(0xffc27a, 60, 11, 2);
  warm.position.set(11, 2.1, -1.0); g.add(warm);
  // 유리창·문 (반사 위주 투명 유리) + 셔터 일부 내림 + 옆 상점 셔터 닫힘
  const glass = new THREE.MeshPhysicalMaterial({ color: 0xcfe0e8, transparent: true, opacity: 0.16, roughness: 0.04, metalness: 0, envMapIntensity: 1.1, depthWrite: false, side: THREE.DoubleSide });
  add(new THREE.PlaneGeometry(7.8, 1.95), glass, 10.3, 1.425, 0.02);
  add(new THREE.PlaneGeometry(2.6, 2.3), glass, 16.05, 1.2, -0.12);
  const frame = new THREE.MeshStandardMaterial({ color: 0x1a1b1d, metalness: 0.7, roughness: 0.35 });
  for (const x of [8.3, 12.3, 14.75, 17.35]) add(new THREE.BoxGeometry(0.06, 2.3, 0.08), frame, x, 1.2, 0.0);
  add(new THREE.BoxGeometry(11, 0.06, 0.08), frame, 12, 2.38, 0.02);
  const shutterBox = new GeoBuilder().box(6.4, 2.12, -0.05, 14.2, 2.4, 0.26, I, ['pz', 'ny', 'py']).build();
  add(shutterBox, mats.shutter, 0, 0, 0);
  add(new GeoBuilder().box(6.4, 1.9, 0.05, 14.2, 2.12, 0.07, I, ['pz']).build(), mats.shutter, 0, 0, 0);
  add(new GeoBuilder().box(0.5, 0.02, 0.04, 5.8, 2.4, 0.06, I, ['pz']).build(), mats.shutter, 0, 0, 0);
  // 간판 (한글, 캔버스, 백라이트 발광)
  const sign = add(new THREE.PlaneGeometry(8.6, 0.5), new THREE.MeshStandardMaterial({ map: bakerySignTex(), emissive: 0xffffff, emissiveMap: null, emissiveIntensity: 0.0, roughness: 0.5 }), 11.9, 2.7, 0.16);
  sign.material.emissiveMap = sign.material.map; sign.material.emissiveIntensity = 1.1;
  // 벽등 (street_lamp_02) 2개
  const wl = lib.instance('street_lamp_02');
  if (wl) {
    wl.traverse((o) => { if (o.isMesh) o.material = [].concat(o.material).map((m) => (/glass|bulb/i.test(m.name) ? ctx.lampGlow : m)); });
    for (const x of [6.1, 14.4]) { const c = wl.clone(); c.position.set(x, 1.85, 0.12); c.scale.setScalar(0.8); g.add(c); }
  }
  // 빵·케이크 진열 (실제 모델)
  const putModel = (id, x, y, z, yaw = 0, s = 1) => { const o = lib.instance(id); if (!o) return null; o.position.set(x, y, z); o.rotation.y = yaw; o.scale.setScalar(s); g.add(o); return o; };
  for (let i = 0; i < 6; i++) putModel('croissant', 7.6 + i * 0.42, 0.78, -0.55, 0.3 * i);
  putModel('hamburger_buns', 10.6, 0.78, -0.55);
  putModel('strawberry_chocolate_cake', 11.6, 0.78, -0.55, 0.4);
  putModel('carrot_cake', 12.6, 0.78, -0.55);
  for (let i = 0; i < 4; i++) putModel('croissant', 9 + i * 0.5, 0.95, -1.9, -0.2 * i);
  putModel('strawberry_chocolate_cake', 12.2, 0.95, -1.9, 1.2);
  putModel('potted_plant_01', 15.0 - 0.6, 0, 0.55, 0, 0.8);
  putModel('potted_plant_01', 17.2, 0, 0.55, 1, 0.8);
  // 보도: 칠판 입간판 + 야외 테이블 (크루아상·빵) — 장면 좌표로 직접 둔다
  const out = (id, s, lat, yaw, s2 = 1) => { const o = lib.instance(id); if (!o) return null; o.applyMatrix4(frameAt(track, s, lat, YW, yaw, s2)); o.traverse((m) => { if (m.isMesh) m.castShadow = true; }); scene.add(o); return o; };
  const board = out('standing_chalkboard_01', 7.2, R_OUT + 1.4, -Math.PI / 2 + 0.35);
  if (board) {
    const face = new THREE.Mesh(new THREE.PlaneGeometry(0.66, 1.0), new THREE.MeshStandardMaterial({ map: chalkTex(), roughness: 0.9 }));
    face.position.set(0.014, 0.88, 0.162); face.rotation.x = -0.262;
    board.children[0].add(face);
  }
  for (const [s, lat] of [[1.5, R_OUT + 1.0], [4.6, R_OUT + 1.0]]) {
    const t = out('outdoor_table_chair_set_01', s, lat, Math.PI / 2);
    if (!t) continue;
    const top = worldAt(track, s, lat, YW + 0.73);
    for (const [id, dx, dz] of [['croissant', 0.06, 0.05], ['hamburger_buns', -0.1, -0.08]]) {
      const o = lib.instance(id); if (!o) continue;
      o.position.set(top.x + dx, top.y, top.z + dz); if (id === 'hamburger_buns') o.scale.setScalar(0.6);
      scene.add(o);
    }
  }
}

// ───────────────────────── 주차장 ─────────────────────────
function buildParking(ctx) {
  const { scene, track, lib, mats } = ctx;
  const s0 = track.length + PARK.s0, s1 = track.length + PARK.s1;
  const lot = new GeoBuilder().sweep(track, [[-24, 0.012], [R_OUT, 0.012]], s0, s1, 1);
  addMesh(scene, lot.build(), mats.lot, { receive: true, name: 'parking-lot' });
  const lines = new GeoBuilder(), Y = 0.02;
  for (let s = s0 + 2; s < s1 - 1; s += 2.6) lines.sweep(track, [[-11, Y], [R_OUT - 0.6, Y]], s, s + 0.12, 1);
  for (let s = s0 + 2; s < s1 - 1; s += 2.6) lines.sweep(track, [[-23, Y], [-17, Y]], s, s + 0.12, 1);
  addMesh(scene, lines.build(), mats.paintW, { receive: true, name: 'parking-lines' });
  const big = new THREE.Mesh(new THREE.PlaneGeometry(5, 5), new THREE.MeshStandardMaterial({
    map: textTex(['P'], { bg: null, fg: '#dfe8f5', size: 230, w: 256, h: 256 }), transparent: true, depthWrite: false, roughness: 0.6, polygonOffset: true, polygonOffsetFactor: -4,
  }));
  big.position.copy(worldAt(track, (s0 + s1) / 2, -14, 0.03)); big.rotation.set(-Math.PI / 2, 0, track.sampleAt((s0 + s1) / 2).h);
  scene.add(big);
  // 바리케이드 (concrete_road_barrier) — 주차 칸 옆 보도 끝, 배전함
  const bar = lib.instance('concrete_road_barrier');
  if (bar) {
    const parts = makeProto(flattenParts(bar));
    const pk = track.parkS + 1.5;
    const ms = [-6, -4.4, 4.4, 6].map((d) => frameAt(track, pk + d, R_WALK - 0.55, YW, 0));
    for (let k = 0; k < 4; k++) ms.push(frameAt(track, s0 + 4 + k * 9, -24.5, 0.012, Math.PI / 2 * 0));
    for (const part of parts) {
      const im = new THREE.InstancedMesh(part.geometry, part.material, ms.length);
      ms.forEach((m, i) => im.setMatrixAt(i, m)); im.computeBoundingSphere(); im.castShadow = true; im.receiveShadow = true; im.name = 'barriers';
      scene.add(im);
    }
  }
  const ub = lib.instance('utility_box_01');
  if (ub) { ub.applyMatrix4(frameAt(track, track.parkS - 8, R_OUT + 0.5, YW, Math.PI)); scene.add(ub); }
  ctx.footprints.push([[-0, 0], [0, 0], [0, 0], [0, 0]].map(() => new THREE.Vector3()));
  const lotPoly = [[s0, R_OUT], [s1, R_OUT], [s1, -25], [s0, -25]].map(([s, l]) => worldAt(track, s, l));
  ctx.footprints.push(lotPoly);
}

// ───────────────────────── 표지판·지도 라벨 ─────────────────────────
function makeSign(tex, w, h, height, poleMat) {
  const g = new THREE.Group(), y = height + h / 2;
  addMesh(g, new THREE.CylinderGeometry(0.05, 0.05, height + h, 12), poleMat).position.y = (height + h) / 2;
  addMesh(g, new THREE.PlaneGeometry(w, h), new THREE.MeshStandardMaterial({ map: tex, roughness: 0.4, emissive: 0xffffff, emissiveMap: tex, emissiveIntensity: 0.25 })).position.set(0, y, 0.035);
  addMesh(g, new THREE.BoxGeometry(w, h, 0.05), new THREE.MeshStandardMaterial({ color: 0x9aa0a8, metalness: 0.8, roughness: 0.4 })).position.set(0, y, 0);
  return g;
}

function buildSigns(ctx) {
  const { scene, track } = ctx;
  const poleMat = new THREE.MeshStandardMaterial({ color: 0x8b9199, metalness: 0.85, roughness: 0.35, envMapIntensity: 0.6 });
  const put = (obj, s, lat) => { obj.applyMatrix4(frameAt(track, s, lat, YW, Math.PI / 2)); scene.add(obj); };
  put(makeSign(textTex(['P 주차장', '도착 · 정지 후 P'], { bg: '#123a8a', fg: '#ffffff' }), 2.4, 1.2, 2.2, poleMat), track.parkS - 9, R_WALK - 0.45);
  put(makeSign(textTex(['급커브', 'R30 · 감속'], { bg: '#f2c14e', fg: '#111111' }), 2.0, 1.0, 2.2, poleMat), track.sharp.s0 - 55, R_WALK - 0.45);
  const chevron = textTex(['◀'], { bg: '#111111', fg: '#f2c14e', size: 200, w: 256, h: 256 });
  for (let s = track.sharp.s0 + 6; s < track.sharp.s1; s += 12) put(makeSign(chevron, 0.8, 0.8, 1.0, poleMat), s, R_WALK - 0.45);
}

function buildMapLabels(scene, track) {
  const add = (text, s, lateral) => {
    const p = track.offsetAt(s, lateral);
    const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: textTex([text], { bg: null, fg: COLORS.champ, size: 120, w: 768, h: 160 }), depthTest: false, transparent: true }));
    sp.scale.set(96, 20, 1); sp.position.set(p.x, 40, -p.y); sp.layers.set(1); sp.renderOrder = 5;
    scene.add(sp);
  };
  for (const a of track.arcs) add(a.sharp ? `R${a.R} 급커브` : `R${a.R}`, (a.s0 + a.s1) / 2, a.sharp ? 55 : 45);
  add('출발 · 주차장', track.length - 20, -34);
}

// ───────────────────────── 진입점 ─────────────────────────
/** 장면 전체 구성. 반환: { fog, update(camPos, focus, dt), setMapMode(bool), stats } */
export function buildWorld(scene, track, lib, renderer) {
  const rand = rng(SEED);
  scene.background = new THREE.Color(0x04060b);
  const fog = scene.fog = new THREE.FogExp2(0x0b0e16, RENDER.fog);
  if (lib.envMap) scene.environment = lib.envMap;
  const moon = new THREE.DirectionalLight(0x8fa0d0, 0.12);
  moon.position.set(-300, 500, 200);
  scene.add(moon);

  const mats = {
    asphalt: lib.pbr('asphalt_02', { color: 0x8c8c8c, roughness: 0.62, envMapIntensity: 0.9, normalScale: 1.1 }),
    curb: lib.pbr('brushed_concrete', { color: 0xb8b8b8, roughness: 0.85, envMapIntensity: ENV_WORLD }),
    paving: lib.pbr('rectangular_paving', { color: 0xa0a0a0, roughness: 0.8, envMapIntensity: ENV_WORLD }),
    brick: lib.pbr('brick_4', { roughness: 0.9, envMapIntensity: 0.3 }),
    shutter: lib.pbr('painted_metal_shutter', { metalness: 0.4, roughness: 0.6, envMapIntensity: 0.6 }),
    roof: lib.pbr('brushed_concrete', { color: 0x6d6f73, roughness: 0.95, envMapIntensity: 0.2 }),
    lot: lib.pbr('asphalt_04', { color: 0x8a8a8a, roughness: 0.7, envMapIntensity: 0.6 }),
  };
  const ap = lib.pbrSets.get('asphalt_02');
  const paint = (color) => new THREE.MeshStandardMaterial({
    color, roughness: 0.5, normalMap: ap?.normalMap ?? null, roughnessMap: ap?.arm ?? null, polygonOffset: true, polygonOffsetFactor: -2, polygonOffsetUnits: -2, envMapIntensity: 0.7,
  });
  mats.paintW = paint(0xd8d8d4); mats.paintY = paint(0xd9a520);

  /** 예약 구역: 베이커리·주차장(오른쪽), 횡단보도 — 건물·소품을 비운다 */
  const L = track.length;
  const inRange = (s, a, b) => { const w = track.wrap(s), A = track.wrap(a); const len = b - a; return track.wrap(w - A) <= len; };
  const reserve = (s, side, what) => {
    if (side === 'R' && inRange(s, BAKERY.s0 - 3, BAKERY.s1 + 3) && what !== 'tree') return true;
    if (side === 'R' && inRange(s, L + PARK.s0 - 2, L + PARK.s1 + 1) && (what === 'bldg' || what === 'prop' || what === 'pole')) return true;
    if (inRange(s, CROSSWALK_S - 2, CROSSWALK_S + 6) && what === 'tree') return true;
    if (what === 'tree' && side === 'R' && inRange(s, BAKERY.s0 + 1, BAKERY.s1 - 1)) return true;
    return false;
  };
  const chunks = new Chunks(scene, track);
  const ctx = { scene, track, lib, renderer, rand, reserve, mats, chunks, footprints: [] };
  ctx.lampGlow = new THREE.MeshStandardMaterial({ color: 0xfff4e0, emissive: 0xffd49a, emissiveIntensity: 3.2, roughness: 0.35 });
  ctx.shutterProto = makeShutterProto(mats);
  const sky = buildSky(scene);
  buildGround(scene, lib);
  buildRoad(scene, track, lib, mats);
  buildRoadDetails(scene, track, lib, rand);
  const lamps = buildLamps(ctx);
  const trees = buildTrees(ctx);
  ctx.aptKit = buildKit(lib, 'apt', new THREE.MeshBasicMaterial({ color: 0xffffff }), new THREE.MeshStandardMaterial({ color: 0x07090c, roughness: 0.07, metalness: 0.2 }));
  buildBakery(ctx);
  buildParking(ctx);
  buildBuildings(ctx);
  buildProps(ctx);
  buildBackdrop(ctx);
  buildSigns(ctx);
  buildMapLabels(scene, track);
  chunks.finalize();

  let mapMode = false;
  const camPos = new THREE.Vector3();
  return {
    fog,
    stats: { buildings: ctx.buildingCount ?? 0, chunks: chunks.stats, props: ctx.propCounts, backdrop: ctx.backdropCount, trees: trees?.spots.length ?? 0, lamps: lamps.heads.length },
    /** 매 프레임: 청크 컬링, 가로등 풀, 가로수 LOD */
    update(camera, focus, dt) {
      camPos.copy(camera.position);
      if (!mapMode) chunks.update(camPos);
      updateLamps(lamps, focus, camPos, mapMode);
      updateTrees(trees, camPos, dt, mapMode);
    },
    /** 지도 모드: 안개·하늘·청크(파사드)·가로등 조명 끔 */
    setMapMode(on) {
      mapMode = on;
      scene.fog = on ? null : fog;
      for (const o of sky) o.visible = !on;
      chunks.root.visible = !on;
      if (on) for (const l of lamps.lights) l.visible = false;
      lamps.streaks.visible = !on;
      if (trees) trees.timer = 0;
    },
  };
}
