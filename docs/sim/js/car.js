/**
 * car.js (v2) — 실사형 세단: 이미지→3D 로 만든 차체(assets/dist/sedan_body.glb, 파트 노드 paint/glass/light_front/light_rear/dark)에
 * 실제 재질(클리어코트 도장·틴팅 유리·발광 등화)을 입히고, 바퀴 4개(회전·조향)·전조등 SpotLight·실내(생성 시트·대시보드·핸들)·
 * 조수석 VIP 품목(분류별 생성 GLB)·센터 디스플레이(캔버스)를 조립한다. 생성 GLB 가 없으면 v1.1 의 프로시저럴 세단으로 폴백.
 * 차는 로컬 +X 방향을 보고, 왼쪽이 -Z(운전석), 오른쪽이 +Z(조수석)이다.
 */
import * as THREE from 'three';
import { DEG, STEER_RATIO, CAR, CARGO, COLORS, FONT, ROAD, SEDAN, VIP_ASSETS } from './config.js';

const NAVY = 0x1f3566;
const AXLE_X = SEDAN.axle, TRACK_HALF = SEDAN.track, SIDE_Z = 0.906;
const BRAKE_LIGHT_ACCEL = -0.5;
const ROAD_Y = ROAD.y;
const TILT_FOLLOW = 8;
/** 캐빈 캠: 운전석 헤드레스트 앞에서 오른쪽 앞(센터 디스플레이·조수석 품목·대시보드·조수석 창) */
export const CABIN_VIEW = { pos: new THREE.Vector3(-0.2, 1.25, -0.4), look: new THREE.Vector3(0.55, 0.82, 0.2), fov: 80 };
const GAUGE_YAW = -0.93;
/** 조수석 방석 윗면 중앙 (생성 시트 기준으로 측정해 보정) */
const CARGO_BASE = new THREE.Vector3(-0.05, 0.66, 0.42);   // y 는 생성 시트를 레이캐스트해 실측으로 덮어쓴다
/** 파트 분류용 팔레트 (project_color.py --rule car 와 동일, 선형 RGB) */
const PALETTE = { paint: [0.06, 0.09, 0.19], glass: [0.02, 0.03, 0.05], light_front: [0.95, 0.97, 1.0], light_rear: [0.85, 0.05, 0.05], dark: [0.08, 0.08, 0.09] };

function frustum(bot, top, y0, y1, open = false) {
  const g = new THREE.BoxGeometry(1, 1, 1), p = g.attributes.position;
  for (let i = 0; i < p.count; i++) {
    const up = p.getY(i) > 0, r = up ? top : bot, u = p.getX(i) + 0.5;
    p.setXYZ(i, r.x0 + u * (r.x1 - r.x0), up ? y1 : y0, p.getZ(i) * r.w);
  }
  if (open) { g.setIndex(Array.from(g.index.array).filter((_, k) => k < 12 || k >= 24)); g.clearGroups(); }
  g.computeVertexNormals();
  return g;
}
/** a→b 를 잇는 각진 지주 (필러) */
function strut(a, b, t, mat, parent) {
  const A = new THREE.Vector3(...a), B = new THREE.Vector3(...b), d = B.clone().sub(A), L = d.length();
  const m = new THREE.Mesh(new THREE.BoxGeometry(L, t, t), mat);
  m.position.copy(A).addScaledVector(d, 0.5);
  m.quaternion.setFromUnitVectors(new THREE.Vector3(1, 0, 0), d.normalize());
  parent.add(m);
  return m;
}
function box(w, h, d, mat, x, y, z, parent) {
  const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat);
  m.receiveShadow = true;
  m.position.set(x, y, z);
  parent.add(m);
  return m;
}
/** 메시의 파트 이름: 노드 이름 → 없으면 COLOR_0 첫 정점으로 팔레트 분류 */
function partName(o) {
  const n = (o.name || o.parent?.name || '').toLowerCase();
  for (const k of Object.keys(PALETTE)) if (n.includes(k)) return k;
  const c = o.geometry.attributes.color;
  if (!c) return 'paint';
  const r = c.getX(0), g = c.getY(0), b = c.getZ(0);
  let best = 'paint', bd = 1e9;
  for (const [k, v] of Object.entries(PALETTE)) { const d = (r - v[0]) ** 2 + (g - v[1]) ** 2 + (b - v[2]) ** 2; if (d < bd) { bd = d; best = k; } }
  return best;
}

export class CarModel {
  constructor(lib) {
    this.lib = lib;
    this.group = new THREE.Group();
    this.slide = new THREE.Vector2();
    this.tilt = new THREE.Vector2();
    this.used = {};                      // CHANGES/디버그: 어떤 에셋을 썼나
    const env = lib?.envMap ?? null;
    const M = this.mats = {
      // 네이비 메탈릭 + 클리어코트. 밤 HDRI(역 천장 조명)가 밝아 envMapIntensity 를 낮춰야 크롬처럼 보이지 않는다
      paint: new THREE.MeshPhysicalMaterial({ color: NAVY, metalness: 0.3, roughness: 0.36, clearcoat: 1.0, clearcoatRoughness: 0.08, envMap: env, envMapIntensity: 0.5, side: THREE.FrontSide }),
      glass: new THREE.MeshPhysicalMaterial({ color: 0x0b1930, metalness: 0.25, roughness: 0.04, transparent: true, opacity: 0.5, envMap: env, envMapIntensity: 1.0, side: THREE.FrontSide, depthWrite: false }),
      dark: new THREE.MeshStandardMaterial({ color: 0x14171c, roughness: 0.75, metalness: 0.1, side: THREE.FrontSide }),
      light_front: new THREE.MeshStandardMaterial({ color: 0xffffff, emissive: 0xfff2d6, emissiveIntensity: 4.0, roughness: 0.3, toneMapped: false }),
      light_rear: new THREE.MeshStandardMaterial({ color: 0x8a0a12, emissive: 0xff2a2a, emissiveIntensity: 1.2, roughness: 0.3, toneMapped: false }),
      seat: new THREE.MeshStandardMaterial({ color: 0x3a3f4a, roughness: 0.85, envMapIntensity: 0.25 }),
      carpet: new THREE.MeshStandardMaterial({ color: 0x15171b, roughness: 0.96, envMapIntensity: 0.12 }),
      chrome: new THREE.MeshStandardMaterial({ color: 0xd7dce3, metalness: 0.8, roughness: 0.2, envMap: env, envMapIntensity: 1.2 }),
      tire: new THREE.MeshStandardMaterial({ color: 0x0c0c0d, roughness: 0.92 }),
      rim: new THREE.MeshStandardMaterial({ color: 0xb8bec6, metalness: 0.85, roughness: 0.25, envMap: env, envMapIntensity: 1.2 }),
      trim: new THREE.MeshStandardMaterial({ color: 0x1b1e24, roughness: 0.8, envMapIntensity: 0.2 }),
    };
    // 가죽 PBR 이 있으면 시트·트림에 적용
    const leather = lib?.pbrSets?.get('fabric_leather_01') ? lib.pbr('fabric_leather_01', { color: 0x2a2d35, roughness: 0.75 }) : null;
    if (leather) M.seat = leather;
    this.wheelMeta = lib?.items?.get('sedan_body')?.car_meta?.wheels ?? null;
    if (!this._bodyGltf(M)) this._bodyProc(M);
    this._mirrors(M);
    this._lights(M);
    this._interior(M);
    this._wheels(M);
    this._cargo();
    this._display();
  }

  /** 렌더 레이어 지정 (좌우 비교에서 차마다 다른 레이어 → 자기 화면에만 보인다). 이후 교체되는 품목에도 적용 */
  setLayer(n) {
    this.layer = n;
    this.group.traverse((o) => o.layers.set(n));
  }

  /** 생성 차체: 파트별 실제 재질. 성공하면 true */
  _bodyGltf(M) {
    const body = this.lib?.instance?.('sedan_body', { forward: '+z' });
    if (!body) return false;
    this.bodyMeshes = [];
    body.traverse((o) => {
      if (!o.isMesh) return;
      const part = partName(o);
      this.bodyMeshes.push(o);
      if (!o.geometry.attributes.normal) o.geometry.computeVertexNormals();   // 분할 GLB 에 법선이 없으면 계산 (없으면 검게 보인다)
      o.material = M[part] ?? M.paint;
      o.castShadow = part !== 'glass'; o.receiveShadow = false;
      if (o.geometry.attributes.color) o.geometry.deleteAttribute('color');   // 팔레트색은 재질로 대체
      o.renderOrder = part === 'glass' ? 2 : 0;
    });
    // instance() 는 바닥을 y=0 에 맞추지만, 바퀴를 뺀 차체의 최저점은 타이어 바닥보다 높다 → 원래 전고(1.46)만큼 올려 지상고 복원
    const fullH = this.lib?.items?.get('sedan_body')?.car_meta?.size_m?.[1] ?? SEDAN.H;
    body.position.y = Math.max(0, fullH - (body.userData?.size?.y ?? fullH));
    this.bodyLift = body.position.y;
    this.group.add(body);
    this.used.body = 'sedan_body.glb (Hunyuan3D-2mv 형상 + 규칙 팔레트 → 파트 재질)';
    return true;
  }

  /** 차체 외피 실측: 위에서 내려 쏜 레이가 닿는 높이 / 옆에서 쏜 레이가 닿는 반폭 (차 로컬, 차체가 없으면 null) */
  _surfaceY(x, z) {
    if (!this.bodyMeshes) return null;
    this.group.updateMatrixWorld(true);
    const rc = new THREE.Raycaster(new THREE.Vector3(x, 3, z), new THREE.Vector3(0, -1, 0), 0, 4);
    const h = rc.intersectObjects(this.bodyMeshes, false)[0];
    return h ? h.point.y : null;
  }
  _sideZ(x, y) {
    if (!this.bodyMeshes) return null;
    const rc = new THREE.Raycaster(new THREE.Vector3(x, y, 3), new THREE.Vector3(0, 0, -1), 0, 4);
    const h = rc.intersectObjects(this.bodyMeshes, false)[0];
    return h ? h.point.z : null;
  }
  /** A·B 필러: 유리 외피를 레이캐스트로 더듬어 그 4 cm 안쪽에 지주를 놓는다 (차체 형상이 바뀌어도 밖으로 삐져나오지 않게) */
  _pillars(M) {
    if (!this.bodyMeshes) return;
    const g = this.group;
    for (const sgn of [-1, 1]) {
      // A 필러: 카울(x 1.05) → 지붕 앞(x 0.3), 폭은 벨트라인 쪽 0.68 → 지붕 0.54
      const pts = [];
      for (const [x, z] of [[1.05, 0.68], [0.8, 0.64], [0.55, 0.59], [0.3, 0.54]]) {
        const y = this._surfaceY(x, sgn * z);
        if (y != null) pts.push([x, y - 0.045, sgn * z]);
      }
      for (let i = 0; i + 1 < pts.length; i++) strut(pts[i], pts[i + 1], 0.05, M.trim, g).name = 'pillar';
      // B 필러: 벨트라인(y 0.8) → 지붕(y 1.38), 옆면 반폭 안쪽
      const bp = [];
      for (const y of [0.8, 1.0, 1.2, 1.38]) {
        const z = this._sideZ(-0.5, y);
        if (z != null) bp.push([-0.5, y, sgn * (z - 0.04)]);
      }
      for (let i = 0; i + 1 < bp.length; i++) strut(bp[i], bp[i + 1], 0.05, M.trim, g).name = 'pillar';
    }
  }

  /** 사이드미러 하우징 (생성 차체에서는 미러를 잘라냈다 — 안팎 모두 매끈한 닫힌 형상으로 대체) */
  _mirrors(M) {
    const g = this.group;
    for (const sgn of [-1, 1]) {
      const y = this.bodyMeshes ? (this._surfaceY(0.9, sgn * 0.82) ?? 0.98) + 0.06 : 1.0;
      const door = this.bodyMeshes ? Math.abs(this._sideZ(0.9, y - 0.03) ?? 0.8) : 0.86;        // 도어 표면 반폭 → 받침대가 도어에 닿게
      const z = sgn * (door + 0.17), zStalk = sgn * (door - 0.02);
      const h = new THREE.Mesh(new THREE.CapsuleGeometry(0.055, 0.11, 4, 12).rotateX(Math.PI / 2), M.paint);
      h.scale.set(1, 0.8, 1); h.position.set(0.9, y, z); h.castShadow = true; g.add(h);
      const face = box(0.012, 0.085, 0.15, M.chrome, 0.9 - 0.075, y, z, g); face.rotation.y = 0;          // 거울면(뒤쪽)
      box(0.05, 0.03, Math.abs(z - zStalk) + 0.02, M.dark, 0.9, y - 0.03, (z + zStalk) / 2, g);           // 받침대
    }
  }

  /** v1.1 프로시저럴 3박스 세단 (폴백) */
  _bodyProc(M) {
    const g = this.group, mesh = (geo, mat) => g.add(new THREE.Mesh(geo, mat));
    box(4.5, 0.22, 1.76, M.paint, 0, 0.40, 0, g);
    mesh(frustum({ x0: 0.95, x1: 2.42, w: 1.80 }, { x0: 0.95, x1: 2.25, w: 1.66 }, 0.51, 0.86), M.paint);
    mesh(frustum({ x0: -2.42, x1: -1.35, w: 1.80 }, { x0: -2.28, x1: -1.35, w: 1.70 }, 0.51, 0.97), M.paint);
    for (const z of [-0.865, 0.865]) box(2.3, 0.47, 0.07, M.paint, -0.2, 0.745, z, g);
    for (const z of [-SIDE_Z, SIDE_Z]) box(2.32, 0.035, 0.02, M.chrome, -0.2, 0.975, z, g);
    mesh(frustum({ x0: -1.35, x1: 0.95, w: 1.70 }, { x0: -0.85, x1: 0.40, w: 1.42 }, 0.92, 1.395, true), M.glass);
    mesh(frustum({ x0: -0.88, x1: 0.43, w: 1.46 }, { x0: -0.80, x1: 0.36, w: 1.38 }, 1.40, 1.46), M.paint);
    for (const x of [2.36, -2.36]) box(0.16, 0.2, 1.82, M.dark, x, 0.37, 0, g);
    box(0.04, 0.12, 0.8, M.dark, 2.35, 0.64, 0, g);
    for (const z of [-0.62, 0.62]) { box(0.06, 0.11, 0.36, M.light_front, 2.3, 0.76, z, g); box(0.06, 0.1, 0.42, M.light_rear, -2.33, 0.84, z, g); }
    for (const z of [-0.97, 0.97]) box(0.14, 0.09, 0.16, M.paint, 0.8, 1.03, z, g);
    this.used.body = '프로시저럴 세단 (sedan_body.glb 없음)';
  }

  /** 전조등 SpotLight 2개(노면 콘), 실내등, 바닥 접지 그림자 */
  _lights(M) {
    const g = this.group;
    for (const z of [-0.6, 0.6]) {
      const spot = new THREE.SpotLight(0xfff1d0, 260, 70, 0.42, 0.55, 2);
      spot.position.set(2.2, 0.72, z);
      spot.target.position.set(22, 0, z * 1.5);
      spot.castShadow = false;
      g.add(spot, spot.target);
    }
    const dome = new THREE.SpotLight(0xffe2b0, 0.12, 2.2, 1.1, 0.8, 2);   // 은은한 실내등 (cd 단위: 0.1 대여야 흰 상자가 타지 않는다)
    dome.position.set(0, 1.36, 0.1); dome.target.position.set(0.3, 0.6, 0.2);
    g.add(dome, dome.target);
    const sc = document.createElement('canvas'); sc.width = 256; sc.height = 128;          // 부드러운 접지 그림자 (타원 그라데이션)
    const sx = sc.getContext('2d'), grad = sx.createRadialGradient(128, 64, 10, 128, 64, 64);
    grad.addColorStop(0, 'rgba(0,0,0,0.85)'); grad.addColorStop(0.55, 'rgba(0,0,0,0.55)'); grad.addColorStop(1, 'rgba(0,0,0,0)');
    sx.save(); sx.scale(1, 1); sx.fillStyle = grad; sx.fillRect(0, 0, 256, 128); sx.restore();
    const shadow = new THREE.Mesh(new THREE.PlaneGeometry(6.2, 3.0).rotateX(-Math.PI / 2),
      new THREE.MeshBasicMaterial({ map: new THREE.CanvasTexture(sc), transparent: true, depthWrite: false }));
    shadow.position.set(-0.05, 0.012, 0);
    g.add(shadow);
  }

  /** 실내: 생성 시트 2개·대시보드·핸들 (없으면 간단 모델), 바닥·천장 트림.
   *  치수는 생성 차체를 레이캐스트로 실측한 실내 외피(벨트라인 반폭 0.80, 창 높이 반폭 0.65, 지붕 안쪽 1.40) 안에 들어가게 잡았다 */
  _interior(M) {
    const g = this.group, lib = this.lib;
    box(2.8, 0.04, 1.56, M.carpet, -0.3, 0.33, 0, g);                    // 바닥 카펫
    box(1.4, 0.02, 1.1, M.trim, -0.4, 1.40, 0, g);                       // 천장(헤드라이너) — 지붕(1.46) 바로 아래
    for (const z of [-0.77, 0.77]) {
      box(2.3, 0.42, 0.05, M.trim, -0.25, 0.57, z, g);                                     // 도어 트림 (벨트라인 아래, 차체 안쪽)
      const up = box(2.2, 0.21, 0.02, M.trim, -0.25, 0.88, Math.sign(z) * 0.75, g);         // 벨트라인~창턱: 안쪽으로 기운 윗 패널 (도어 외피 0.80→0.73 반폭 안쪽)
      up.rotation.x = -Math.sign(z) * 0.33;
    }
    this._pillars(M);
    box(0.5, 0.05, 1.4, M.trim, -1.55, 1.0, 0, g);                       // 뒤 선반
    box(0.06, 0.6, 1.5, M.trim, -1.95, 0.65, 0, g);                      // 트렁크 격벽
    const leatherDark = lib?.pbrSets?.get('fabric_leather_01') ? lib.pbr('fabric_leather_01', { color: 0x1b1d23, roughness: 0.72, envMapIntensity: 0.4 }) : M.seat;
    const skin = (obj, mat) => obj?.traverse((o) => { if (o.isMesh) { o.material = mat; if (o.geometry.attributes.color) o.geometry.deleteAttribute('color'); o.castShadow = true; o.receiveShadow = true; } });
    const seat = lib?.instance?.('car_seat', { forward: '+z' });
    const FLOOR = 0.33;
    if (seat) { skin(seat, leatherDark);
      let pass = null;
      for (const z of [-0.42, 0.42]) { const s = z < 0 ? seat : seat.clone(true); s.position.set(-0.12, FLOOR, z); g.add(s); if (z > 0) pass = s; }
      this.used.seat = 'car_seat.glb';
      // 조수석 방석 윗면을 실측: 위에서 아래로 레이캐스트 (시트 모델이 바뀌어도 품목이 공중에 뜨거나 파묻히지 않게)
      g.updateMatrixWorld(true);
      const rc = new THREE.Raycaster(new THREE.Vector3(CARGO_BASE.x, 1.3, 0.42), new THREE.Vector3(0, -1, 0), 0, 2);
      const hit = rc.intersectObject(pass, true)[0];
      CARGO_BASE.y = hit ? hit.point.y + 0.005 : FLOOR + 0.33;
    } else {
      for (const z of [-0.42, 0.42]) {
        box(0.52, 0.12, 0.5, M.seat, 0.05, 0.62, z, g);
        box(0.1, 0.5, 0.5, M.seat, -0.26, 0.905, z, g).rotation.z = 0.12;
        box(0.09, 0.17, 0.28, M.seat, -0.31, 1.25, z, g);
      }
      this.used.seat = '프로시저럴 시트';
      CARGO_BASE.y = 0.68;
    }
    box(0.5, 0.12, 1.26, M.seat, -0.85, 0.62, 0, g);                     // 뒷좌석 (간단)
    box(0.1, 0.5, 1.26, M.seat, -1.17, 0.9, 0, g).rotation.z = 0.12;
    // 대시보드: 생성 모델은 -Z 가 앞유리 쪽(윗단), +Z 로 센터콘솔이 길게 이어진다 → forward '-z'. 깊이만 절반으로 줄여 카울(x≈1.1)과 핸들 사이에 둔다
    const dash = lib?.instance?.('dashboard', { forward: '-z' });
    if (dash) { skin(dash, new THREE.MeshStandardMaterial({ color: 0x1a1c21, roughness: 0.6, metalness: 0.05 })); dash.scale.set(0.5, 1, 1); dash.position.set(0.84, 0.5, 0); g.add(dash); this.used.dashboard = 'dashboard.glb'; }
    else { box(0.32, 0.24, 1.46, M.dark, 0.90, 0.86, 0, g); this.used.dashboard = '프로시저럴 대시보드'; }
    const pivot = new THREE.Group();
    pivot.position.set(0.45, 1.0, -0.42); pivot.rotation.z = 0.35;
    this.wheelSpinner = new THREE.Group();
    const sw = lib?.instance?.('steering_wheel', { forward: '+z' });
    if (sw) { skin(sw, new THREE.MeshStandardMaterial({ color: 0x15171c, roughness: 0.55, metalness: 0.15 })); sw.rotation.y += Math.PI; sw.position.y = -0.19; this.wheelSpinner.add(sw); this.used.steering = 'steering_wheel.glb'; }
    else {
      this.wheelSpinner.add(new THREE.Mesh(new THREE.TorusGeometry(0.18, 0.022, 8, 24).rotateY(Math.PI / 2), M.dark));
      box(0.03, 0.3, 0.035, M.rim, 0, 0, 0, this.wheelSpinner); this.used.steering = '프로시저럴 핸들';
    }
    pivot.add(this.wheelSpinner);
    g.add(pivot);
  }

  /** 바퀴 4개: 타이어(원통) + 림(디스크·스포크). 위치는 생성 차체의 바퀴 중심 메타가 있으면 그것을 쓴다 */
  _wheels(M) {
    this.spinners = []; this.frontPivots = [];
    let specs = [];
    if (this.wheelMeta && this.wheelMeta.length === 4) {
      // 메타는 GLB 좌표(+z 앞) → 차 로컬(+x 앞): (x, y, z) → (z, y, -x)
      specs = this.wheelMeta.map((w) => ({ x: w.center[2], y: w.center[1], z: -w.center[0], r: w.radius, w: Math.min(0.26, w.width) }));
    } else {
      for (const x of [AXLE_X, -AXLE_X]) for (const z of [-TRACK_HALF, TRACK_HALF]) specs.push({ x, y: CAR.wheelRadius, z, r: CAR.wheelRadius, w: 0.24 });
    }
    for (const s of specs) {
      const pivot = new THREE.Group(), spin = new THREE.Group();
      const tire = new THREE.Mesh(new THREE.CylinderGeometry(s.r, s.r, s.w, 28).rotateX(Math.PI / 2), M.tire);
      const rim = new THREE.Mesh(new THREE.CylinderGeometry(s.r * 0.64, s.r * 0.64, s.w * 1.02, 24).rotateX(Math.PI / 2), M.rim);
      spin.add(tire, rim);
      for (let k = 0; k < 5; k++) {
        const sp = new THREE.Mesh(new THREE.BoxGeometry(s.r * 0.56, 0.05, s.w * 1.04), M.rim);
        sp.position.x = s.r * 0.3; sp.rotation.z = (k / 5) * Math.PI * 2;
        const h = new THREE.Group(); h.rotation.z = (k / 5) * Math.PI * 2; sp.position.set(s.r * 0.3, 0, 0); sp.rotation.z = 0; h.add(sp); spin.add(h);
      }
      tire.castShadow = true;
      pivot.position.set(s.x, s.y, s.z);
      pivot.add(spin);
      this.group.add(pivot);
      this.spinners.push(spin);
      if (s.x > 0) this.frontPivots.push(pivot);
    }
    this.wheelR = specs[0]?.r ?? CAR.wheelRadius;
  }

  /** 조수석 VIP 품목 + 손상도 게이지 */
  _cargo() {
    const t = this.cargoTilt = new THREE.Group();
    this.cargo = new THREE.Group();
    this.cargo.add(t);
    this.group.add(this.cargo);
    this.itemHolder = new THREE.Group();
    t.add(this.itemHolder);
    const gauge = new THREE.Group(); gauge.name = 'gauge';
    gauge.position.y = 0.36; gauge.rotation.y = GAUGE_YAW;
    this.cargo.add(gauge);
    box(0.01, 0.026, 0.26, new THREE.MeshBasicMaterial({ color: 0x1b222c, transparent: true, opacity: 0.75 }), 0, 0, 0, gauge);
    this.gaugeMat = new THREE.MeshBasicMaterial({ color: COLORS.mint });
    this.gaugeFill = box(0.014, 0.018, 0.24, this.gaugeMat, -0.002, 0, -0.12, gauge);
    this.gaugeFill.geometry.translate(0, 0, 0.12);
    this.gauge = gauge;
    this.setVip('cake');
    this.resetCargo();
  }

  /** 분류 → 시트 위 품목 교체 (생성 GLB → Poly Haven → 프로시저럴 상자 순) */
  setVip(catId) {
    this.itemHolder.clear();
    const cands = VIP_ASSETS[catId] ?? ['proc:cake_box'];
    let obj = null, used = null;
    for (const c of cands) {
      const [kind, id] = c.split(':');
      if (kind === 'g' || kind === 'ph') { obj = this.lib?.models?.has(id) ? this.lib.instance(id) : null; if (obj) { used = `${id} (${kind === 'g' ? '생성' : 'Poly Haven'})`; break; } }
      else { obj = this._procBox(); used = '프로시저럴 흰 상자'; break; }
    }
    if (!obj) { obj = this._procBox(); used = '프로시저럴 흰 상자'; }
    obj.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = true; for (const m of [].concat(o.material)) { if (m.isMeshStandardMaterial) { m.envMapIntensity = 0.3; if (m.vertexColors) { m.color.setScalar(0.72); } } } } });   // 가로등 아래서 흰 상자가 타지 않게 (종이 알베도 ≈ 0.7)
    this.itemHolder.add(obj);
    if (this.layer != null) obj.traverse((o) => o.layers.set(this.layer));
    const h = new THREE.Box3().setFromObject(obj).getSize(new THREE.Vector3()).y;
    this.gauge.position.y = h + 0.08;
    this.used.vip = used;
    this.vipId = catId;
  }

  _procBox() {
    const white = new THREE.MeshStandardMaterial({ color: 0xf4f1ea, roughness: 0.7 });
    const gold = new THREE.MeshStandardMaterial({ color: 0xd9ad45, metalness: 0.6, roughness: 0.35 });
    const t = new THREE.Group();
    box(0.25, 0.15, 0.25, white, 0, 0.075, 0, t);
    box(0.26, 0.155, 0.03, gold, 0, 0.075, 0, t);
    box(0.03, 0.155, 0.26, gold, 0, 0.075, 0, t);
    box(0.1, 0.04, 0.04, gold, 0, 0.17, 0, t).rotation.y = 0.6;
    box(0.1, 0.04, 0.04, gold, 0, 0.17, 0, t).rotation.y = -0.6;
    return t;
  }

  /** 센터 디스플레이(가로로 긴 패널) — 캔버스 텍스처로 앱 화면 */
  _display() {
    this.dispCanvas = document.createElement('canvas');
    this.dispCanvas.width = 512; this.dispCanvas.height = 160;
    this.dispTex = new THREE.CanvasTexture(this.dispCanvas);
    this.dispTex.colorSpace = THREE.SRGBColorSpace;
    const panel = new THREE.Mesh(new THREE.PlaneGeometry(0.56, 0.175), new THREE.MeshBasicMaterial({ map: this.dispTex, toneMapped: false }));
    panel.position.set(0.90, 0.88, 0.1);                               // 대시보드 윗단 뒷면(x≈0.915)에 붙은 와이드 디스플레이
    panel.rotation.y = -Math.PI / 2;
    this.group.add(panel);
    const bezel = box(0.015, 0.21, 0.62, this.mats.dark, 0.908, 0.88, 0.1, this.group);
    bezel.renderOrder = -1;
  }

  drawDisplay(badge, totalG, limit, msg) {
    const c = this.dispCanvas.getContext('2d'), W = 512, H = 160;
    const col = badge === 'OVER' ? COLORS.red : badge === 'NEAR' ? COLORS.champ : COLORS.mint;
    c.fillStyle = COLORS.bg; c.fillRect(0, 0, W, H);
    c.fillStyle = col; c.beginPath(); c.roundRect(20, 18, 150, 58, 29); c.fill();
    c.textBaseline = 'middle'; c.textAlign = 'center';
    c.fillStyle = COLORS.bg; c.font = `bold 34px ${FONT}`; c.fillText(badge, 95, 48);
    c.textAlign = 'left';
    c.fillStyle = COLORS.text; c.font = `bold 40px ${FONT}`; c.fillText(`${totalG.toFixed(2)} G`, 190, 48);
    c.fillStyle = COLORS.dim; c.font = `24px ${FONT}`; c.fillText(`한계 ${limit.toFixed(2)} G`, 362, 48);
    c.fillStyle = COLORS.champ; c.font = `28px ${FONT}`;
    let text = msg || '';
    while (text && c.measureText(text).width > W - 40) text = text.slice(0, -1);
    c.fillText(text, 20, 118);
    this.dispTex.needsUpdate = true;
  }

  update(veh) {
    this.group.position.set(veh.x, ROAD_Y, -veh.y);
    this.group.rotation.y = veh.h;
    const delta = (veh.steer / STEER_RATIO) * DEG;
    for (const p of this.frontPivots) p.rotation.y = delta;
    for (const s of this.spinners) s.rotation.z = -veh.spin * (CAR.wheelRadius / this.wheelR);
    this.wheelSpinner.rotation.x = -veh.steer * DEG;
    const braking = veh.aLon < BRAKE_LIGHT_ACCEL;
    this.mats.light_rear.emissiveIntensity = braking ? 4.0 : 1.2;
  }

  updateCargo(latG, lonG, totalG, dt) {
    const over = totalG - CARGO.slideG;
    let tx = 0, tz = 0;
    if (over > 0) {
      const ix = -lonG / totalG, iz = latG / totalG;
      this.slide.x += ix * over * CARGO.slideRate * dt;
      this.slide.y += iz * over * CARGO.slideRate * dt;
      if (this.slide.length() > CARGO.slideMax) this.slide.setLength(CARGO.slideMax);
      const tilt = Math.min(CARGO.tiltMax, over * CARGO.tiltGain);
      tx = ix * tilt; tz = iz * tilt;
    }
    const k = Math.min(1, dt * TILT_FOLLOW);
    this.tilt.x += (tx - this.tilt.x) * k;
    this.tilt.y += (tz - this.tilt.y) * k;
    this.cargo.position.set(CARGO_BASE.x + this.slide.x, CARGO_BASE.y, CARGO_BASE.z + this.slide.y);
    this.cargoTilt.rotation.set(this.tilt.y, 0, -this.tilt.x);
  }

  setDamage(frac) {
    this.gaugeFill.scale.z = Math.max(0.001, Math.min(1, frac));
    this.gaugeMat.color.set(frac >= 1 ? COLORS.red : frac >= 0.5 ? COLORS.champ : COLORS.mint);
  }

  resetCargo() {
    this.slide.set(0, 0); this.tilt.set(0, 0);
    this.updateCargo(0, 0, 0, 0);
  }
}
