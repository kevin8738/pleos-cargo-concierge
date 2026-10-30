/**
 * car.js — 공식 사진과 TRELLIS.2 형상을 바탕으로 재구성한 2021 아이오닉 5.
 * 차체·실내·격자 휠의 재질과 피벗을 유지하고 조향·등화·듀얼 화면·조수석 VIP 품목을 조립한다.
 * 전용 GLB 가 없으면 기존 생성 차체와 프로시저럴 세단으로 폴백한다.
 * 차는 로컬 +X 방향을 보고, 왼쪽이 -Z(운전석), 오른쪽이 +Z(조수석)이다.
 */
import * as THREE from 'three';
import { DEG, STEER_RATIO, CAR, CARGO, COLORS, FONT, ROAD, SEDAN, VIP_ASSETS, BODIES, BODY_ORDER } from './config.js';

const NAVY = 0x1f3566;   // 프로시저럴 폴백 색
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
  for (const k of Object.keys(PALETTE)) if (n === k || n.startsWith(`${k}_`)) return k;
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
      rim: new THREE.MeshStandardMaterial({ color: 0xb9bec4, metalness: 0.85, roughness: 0.25, envMap: env, envMapIntensity: 1.2 }),
      rimDark: new THREE.MeshStandardMaterial({ color: 0x17191b, metalness: 0.6, roughness: 0.45, envMap: env, envMapIntensity: 0.6 }),
      band: new THREE.MeshStandardMaterial({ color: 0x0a0b0d, roughness: 0.6, metalness: 0.1 }),
      light_rear_dim: new THREE.MeshStandardMaterial({ color: 0x5a0a10, emissive: 0xff2a2a, emissiveIntensity: 0.4, roughness: 0.3, toneMapped: false }),
      trim: new THREE.MeshStandardMaterial({ color: 0x1b1e24, roughness: 0.8, envMapIntensity: 0.2 }),
    };
    // 가죽 PBR 이 있으면 시트·트림에 적용
    const leather = lib?.pbrSets?.get('fabric_leather_01') ? lib.pbr('fabric_leather_01', { color: 0x2a2d35, roughness: 0.75 }) : null;
    if (leather) M.seat = leather;
    this.wheelMeta = null; this.body = null; this.roofY = null;
    if (!this._bodyGltf(M)) this._bodyProc(M);
    this.body ??= { id: 'proc', ...BODIES.sedan_body };
    this.roofY ??= this.body.H;
    // 실내 색: 차체별 (아이오닉 5 는 밝은 회색 투톤)
    if (this.body.trim) M.trim.color.set(this.body.trim);
    if (this.body.carpet) M.carpet.color.set(this.body.carpet);
    if (!this.body.nativeMirrors) this._mirrors(M);
    if (this.body.textured) {
      M.glass.color.set(0x6a787d); M.glass.metalness = 0; M.glass.roughness = 0.10;
      M.glass.opacity = 0.38; M.glass.envMapIntensity = 0.35;
      M.light_front.emissive.set(0xeef4ff); M.light_front.emissiveIntensity = 2.0;
      M.light_rear.emissiveIntensity = 1.3;
    }
    this._pixelLights(M);
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
    const id = BODY_ORDER.find((k) => this.lib?.models?.has(k));
    if (!id) return false;
    const B = this.body = { id, ...BODIES[id] }, item = this.lib.items?.get(id);
    this.wheelMeta = item?.car_meta?.wheels ?? null;
    M.paint.color.set(B.paint);
    const body = B.authored ? this._authoredScene(id) : this.lib.instance(id, { forward: '+z' });
    if (!body) return false;
    this.bodyMeshes = [];
    body.traverse((o) => {
      if (!o.isMesh) return;
      const part = partName(o);
      this.bodyMeshes.push(o);
      if (!o.geometry.attributes.normal) o.geometry.computeVertexNormals();   // 분할 GLB 에 법선이 없으면 계산 (없으면 검게 보인다)
      if (B.authored) {
        for (const mat of [].concat(o.material)) {
          mat.envMapIntensity = part === 'glass' ? 0.5 : B.pbr.envIntensity;
          if (part === 'glass' && mat.transparent) mat.depthWrite = false;
          if (part === 'light_rear') {
            this.rearLampMaterials ??= new Set(); this.rearLampMaterials.add(mat);
          }
        }
        o.castShadow = part !== 'glass'; o.receiveShadow = false;
        o.renderOrder = part === 'glass' ? 2 : 0;
        return;
      }
      const keepVC = B.photoPaint && (part === 'paint' || part === 'dark') && !!o.geometry.attributes.color;
      if (B.textured && part !== 'glass' && o.material?.map) { // 창면은 별도 투명 재질, 외장은 원본 PBR 유지
        const src = o.material;
        const material = new THREE.MeshPhysicalMaterial();
        THREE.MeshStandardMaterial.prototype.copy.call(material, src);
        material.defines = { ...material.defines, PHYSICAL: '' };
        material.metalness *= B.pbr?.metalnessScale ?? 1;
        material.clearcoat = B.pbr?.clearcoat ?? 0.7;
        material.clearcoatRoughness = 0.20;
        material.envMap = this.lib?.envMap ?? null;
        material.envMapIntensity = B.pbr?.envIntensity ?? 0.6;
        material.side = THREE.FrontSide;
        o.material = material;
        this._texturedBody = true;
      } else if (keepVC) { this._paintVC ??= Object.assign(M.paint.clone(), { vertexColors: true }); this._paintVC.color.set(0xffffff); o.material = part === 'paint' ? this._paintVC : Object.assign(M.dark.clone(), { vertexColors: true, color: new THREE.Color(0xffffff) }); }
      else { o.material = M[part] ?? M.paint; if (o.geometry.attributes.color) o.geometry.deleteAttribute('color'); }   // 팔레트색은 재질로 대체
      o.castShadow = part !== 'glass'; o.receiveShadow = false;
      o.renderOrder = part === 'glass' ? 2 : 0;
    });
    // instance() 는 바닥을 y=0 에 맞추지만, 바퀴를 뺀 차체의 최저점은 타이어 바닥보다 높다 → 원래 전고만큼 올려 지상고 복원
    const fullH = item?.car_meta?.size_m?.[1] ?? B.H;
    body.position.y = B.nativeCoordinates ? 0 : Math.max(0, fullH - (body.userData?.size?.y ?? fullH));
    this.bodyLift = body.position.y;
    this.group.add(body);
    this.roofY = this._surfaceY(-0.06 * B.L, 0) ?? B.H;          // 지붕 실측 (헤드라이너·실내등·눈높이 기준)
    this.used.body = `${id}.glb — ${B.label}, ${B.paintName} (${B.authored ? '공식 참조 기반 형상·부품 재구성' : B.textured ? 'TRELLIS.2 텍스처 차체' : '이미지→3D 형상 + 규칙 팔레트 → 파트 재질'})`;
    return true;
  }

  /** Authored assets already share the simulator's metre coordinates and pivots. */
  _authoredScene(id) {
    const source = this.lib?.models?.get(id)?.gltf?.scene;
    if (!source) return null;
    const obj = source.clone(true);
    const materials = new Map();
    obj.traverse(o => {
      if (!o.isMesh) return;
      const clone = src => {
        if (!materials.has(src)) {
          const mat = src.clone();
          if (mat.isMeshStandardMaterial) { mat.envMap = this.lib.envMap; mat.envMapIntensity = 0.35; }
          materials.set(src, mat);
        }
        return materials.get(src);
      };
      o.material = Array.isArray(o.material) ? o.material.map(clone) : clone(o.material);
      o.castShadow = ![].concat(o.material).some(m => m.transparent);
      o.receiveShadow = true;
    });
    return obj;
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
  /** 옆면 반폭(절대값), 실측이 안 되면 fb */
  _half(x, y, fb) { const z = this._sideZ(x, y); return z != null ? Math.abs(z) : fb; }
  /** A·B 필러: 유리 외피를 레이캐스트로 더듬어 그 4 cm 안쪽에 지주를 놓는다 (차체 형상이 바뀌어도 밖으로 삐져나오지 않게) */
  _pillars(M) {
    if (!this.bodyMeshes) return;
    const g = this.group;
    const B = this.body, F = B.floor;
    for (const sgn of [-1, 1]) {
      // A 필러: 카울 → 지붕 앞. 각 x 에서 앞유리 가운데 높이보다 10 cm 아래의 옆면 반폭 − 5 cm 를 따라간다
      const pts = [];
      for (const dx of [0.05, 0.3, 0.55, 0.8]) {
        const x = B.cowlX - dx, yc = this._surfaceY(x, 0);
        if (yc == null) continue;
        const z = this._half(x, yc - 0.1, 0.7) - 0.05, y = this._surfaceY(x, sgn * z);
        if (y != null) pts.push([x, y - 0.045, sgn * z]);
      }
      for (let i = 0; i + 1 < pts.length; i++) strut(pts[i], pts[i + 1], 0.05, M.trim, g).name = 'pillar';
      // B 필러: 벨트라인 → 지붕, 옆면 반폭 안쪽
      const bp = [], bx = B.seatX - 0.38;
      for (const y of [F + 0.47, F + 0.67, F + 0.87, this.roofY - 0.08]) {
        const z = this._sideZ(bx, y);
        if (z != null) bp.push([bx, y, sgn * (Math.abs(z) - 0.04)]);
      }
      for (let i = 0; i + 1 < bp.length; i++) strut(bp[i], bp[i + 1], 0.05, M.trim, g).name = 'pillar';
    }
  }

  /** 파라메트릭 픽셀 등화 (아이오닉 5): 차체 앞/뒷면을 레이캐스트해 표면 1.2 cm 밖에 작은 발광 사각형을 격자로 붙인다 */
  _pixelLights(M) {
    const P = this.body?.pixels;
    if (!P || !this.bodyMeshes) return;
    const g = this.group; this.group.updateMatrixWorld(true);
    const step = P.pitch ?? P.size * 1.45, planes = new Map();
    const hitAt = (dir, y, z) => {
      const rc = new THREE.Raycaster(new THREE.Vector3(dir * 4, y, z), new THREE.Vector3(-dir, 0, 0), 0, 8);
      return rc.intersectObjects(this.bodyMeshes, false)[0];
    };
    // A common fitted plane per frame/cluster keeps the grid intact and follows
    // the curved fascia without copying each noisy triangle's normal.
    const planeAt = (dir, z0) => {
      const key = `${dir}:${z0.toFixed(3)}`;
      if (planes.has(key)) return planes.get(key);
      const y0 = (dir > 0 ? P.frontY : P.rearY) - step * 0.5;
      const span = dir > 0 ? 0.09 : z0 === 0 ? Math.max(0.1, P.rearZ - 3 * step) : 0.17;
      const points = [];
      for (const dy of [-0.05, 0, 0.05]) for (const dz of [-span, 0, span]) {
        const hit = hitAt(dir, y0 + dy, z0 + dz);
        if (hit) points.push({ x: hit.point.x, dy, dz });
      }
      if (!points.length) { planes.set(key, null); return null; }
      const mean = k => points.reduce((s, p) => s + p[k], 0) / points.length;
      const mx = mean('x'), my = mean('dy'), mz = mean('dz');
      let yy = 0, zz = 0, yz = 0, xy = 0, xz = 0;
      for (const p of points) {
        const y = p.dy - my, z = p.dz - mz, x = p.x - mx;
        yy += y * y; zz += z * z; yz += y * z; xy += x * y; xz += x * z;
      }
      const det = yy * zz - yz * yz;
      const sy = det > 1e-10 ? (xy * zz - xz * yz) / det : 0;
      const sz = det > 1e-10 ? (xz * yy - xy * yz) / det : 0;
      const intercepts = points.map(p => p.x - sz * p.dz - sy * p.dy);
      const x0 = (dir > 0 ? Math.max(...intercepts) : Math.min(...intercepts)) + dir * 0.008;
      const plane = { x0, y0, z0, sy, sz }; planes.set(key, plane); return plane;
    };
    const place = (dir, y, z, mat, w = P.size, h = P.size, anchorZ = null) => {
      const z0 = anchorZ ?? (Math.abs(z) < P.rearZ - 3 * step ? 0 : Math.sign(z) * P.rearZ);
      const plane = P.flat ? planeAt(dir, z0) : null;
      const hit = plane ? null : hitAt(dir, y, z);
      if (!hit && !plane) return;
      const q = new THREE.Mesh(new THREE.PlaneGeometry(w, h), mat);
      if (plane) {
        const pos = q.geometry.attributes.position;
        for (let i = 0; i < pos.count; i++) {
          const dz = -dir * pos.getX(i), dy = pos.getY(i);
          pos.setXYZ(i, plane.sz * dz + plane.sy * dy, dy, dz);
        }
        q.geometry.computeVertexNormals();
        q.position.set(plane.x0 + plane.sz * (z - plane.z0) + plane.sy * (y - plane.y0) + (mat === M.band ? -dir * 0.004 : 0), y, z);
      } else {
        const n = hit.face.normal.clone().transformDirection(hit.object.matrixWorld);
        q.position.copy(hit.point).addScaledVector(n, mat === M.band ? 0.006 : 0.012);
        q.quaternion.setFromUnitVectors(new THREE.Vector3(0, 0, 1), n);
      }
      q.name = 'pixel'; g.add(q);
    };
    if (P.bands) {                                                                       // 램프 하우징 검정 밴드 (앞 1.74×0.16, 뒤 1.74×0.14) — 표면 6 mm 밖
      for (let z = -0.85; z <= 0.85 + 1e-6; z += 0.1) { place(1, P.frontY - step * 0.5, z, M.band, 0.1, 0.16); place(-1, P.rearY - step * 0.5, z, M.band, 0.1, 0.14); }
    }
    for (const sgn of [-1, 1]) {
      if (P.flat) {
        place(-1, P.rearY - step * 0.5, sgn * P.rearZ, M.band, 0.37, 0.13);
      }
      if (P.frames) {                                                                    // 앞: 프레임형 DRL 2개/측 (0.18×0.12, 테두리 0.015, 간격 0.06)
        for (const dz of [-0.12, 0.12]) {
          const zc = sgn * (P.frontZ + dz), yc = P.frontY - step * 0.5, w = 0.18, h = 0.12, t = 0.015;
          if (P.flat) place(1, yc, zc, M.band, w + 0.015, h + 0.015, zc);
          place(1, yc + h / 2 - t / 2, zc, M.light_front, w, t, zc); place(1, yc - h / 2 + t / 2, zc, M.light_front, w, t, zc);
          place(1, yc, zc - sgn * (w / 2 - t / 2), M.light_front, t, h, zc); place(1, yc, zc + sgn * (w / 2 - t / 2), M.light_front, t, h, zc);
        }
      } else for (let r = 0; r < 2; r++) for (let c = 0; c < 4; c++) place(1, P.frontY - r * step, sgn * (P.frontZ + (c - 1.5) * step), M.light_front);   // 앞 DRL 2×4
      const cols = P.rearCols ?? 6;
      for (let r = 0; r < 2; r++) for (let c = 0; c < cols; c++) place(-1, P.rearY - r * step, sgn * (P.rearZ + (c - (cols - 1) / 2) * step), M.light_rear);
    }
    for (let z = -(P.rearZ - 3 * step); z <= P.rearZ - 3 * step + 1e-6; z += 0.1) place(-1, P.rearY - step * 0.5, z, M.light_rear_dim, 0.1, 0.01, 0);   // 뒤 연결 바 (얇고 어둡게)
  }

  /** 사이드미러 하우징 (생성 차체에서는 미러를 잘라냈다 — 안팎 모두 매끈한 닫힌 형상으로 대체) */
  _mirrors(M) {
    const g = this.group;
    const B = this.body ?? BODIES.sedan_body, mx = B.cowlX - 0.2;
    for (const sgn of [-1, 1]) {
      const y = this.bodyMeshes ? (this._surfaceY(mx, sgn * 0.436 * B.W) ?? (B.floor + 0.65)) + 0.06 : B.floor + 0.67;
      const door = this.bodyMeshes ? this._half(mx, y - 0.03, 0.43 * B.W) : 0.46 * B.W;        // 도어 표면 반폭 → 받침대가 도어에 닿게
      const z = sgn * (door + 0.17), zStalk = sgn * (door - 0.02);
      const h = new THREE.Mesh(new THREE.CapsuleGeometry(0.055, 0.11, 4, 12).rotateX(Math.PI / 2), M.paint);
      h.scale.set(1, 0.8, 1); h.position.set(mx, y, z); h.castShadow = true; g.add(h);
      box(0.012, 0.085, 0.15, M.chrome, mx - 0.075, y, z, g);                                     // 거울면(뒤쪽)
      box(0.05, 0.03, Math.abs(z - zStalk) + 0.02, M.dark, mx, y - 0.03, (z + zStalk) / 2, g);    // 받침대
    }
  }

  /** 프로시저럴 3박스 세단 (폴백) */
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
    const g = this.group, B = this.body;
    for (const z of [-0.32 * B.W, 0.32 * B.W]) {
      const spot = new THREE.SpotLight(0xfff1d0, 260, 70, 0.42, 0.55, 2);
      spot.position.set(0.44 * B.L, 0.49 * B.H, z);
      spot.target.position.set(22, 0, z * 1.5);
      spot.castShadow = false;
      g.add(spot, spot.target);
    }
    const dome = new THREE.SpotLight(0xffe2b0, 0.12, 2.2, 1.1, 0.8, 2);   // 은은한 실내등 (cd 단위: 0.1 대여야 흰 상자가 타지 않는다)
    dome.position.set(0, this.roofY - 0.1, 0.1); dome.target.position.set(0.3, B.floor + 0.27, 0.2);
    g.add(dome, dome.target);
    const sc = document.createElement('canvas'); sc.width = 256; sc.height = 128;          // 부드러운 접지 그림자 (타원 그라데이션)
    const sx = sc.getContext('2d'), grad = sx.createRadialGradient(128, 64, 10, 128, 64, 64);
    grad.addColorStop(0, 'rgba(0,0,0,0.85)'); grad.addColorStop(0.55, 'rgba(0,0,0,0.55)'); grad.addColorStop(1, 'rgba(0,0,0,0)');
    sx.save(); sx.scale(1, 1); sx.fillStyle = grad; sx.fillRect(0, 0, 256, 128); sx.restore();
    const shadow = new THREE.Mesh(new THREE.PlaneGeometry(B.L + 1.2, B.W + 1.1).rotateX(-Math.PI / 2),
      new THREE.MeshBasicMaterial({ map: new THREE.CanvasTexture(sc), transparent: true, depthWrite: false }));
    shadow.position.set(-0.05, 0.012, 0);
    g.add(shadow);
  }

  /** 실내: 생성 시트 2개·대시보드·핸들 (없으면 간단 모델), 바닥·천장 트림.
   *  치수는 차체를 레이캐스트로 실측한 실내 외피(벨트라인·창턱 반폭, 지붕 높이)에 BODIES 의 바닥 높이·카울 위치·시트 위치를 더해 정한다 */
  _interior(M) {
    if (this.body.cabin && this._authoredInterior()) return;
    const g = this.group, lib = this.lib, B = this.body, L = B.L, F = B.floor;
    const roof = this.roofY, headY = roof - 0.06;
    const trimXs = B.textured ? [-0.28 * L, -0.14 * L, 0, 0.18 * L] : [-0.05 * L];
    const beltZ = Math.min(...trimXs.map(x => this._half(x, F + 0.30, 0.43 * B.W))) - (B.textured ? 0.025 : 0);
    const sillZ = Math.min(...trimXs.map(x => this._half(x, F + 0.62, 0.39 * B.W))) - (B.textured ? 0.025 : 0);
    if (B.textured) {
      const rear = -0.34 * L, front = B.cowlX - 0.08;
      box(front - rear, 0.04, 2 * beltZ, M.carpet, (front + rear) / 2, F, 0, g);
      const footZ = Math.min(beltZ, this._half(front, F + 0.25, beltZ) - 0.04);
      box(0.04, 0.50, 2 * footZ, M.carpet, front, F + 0.23, 0, g); // 대시보드 아래 격벽
      for (const sgn of [-1, 1]) {
        const rearKick = 0.17 * L;
        box(front - rearKick, 0.45, 0.04, M.trim, (front + rearKick) / 2, F + 0.205, sgn * (footZ - 0.02), g);
      }
    } else box(0.56 * L, 0.04, 2 * beltZ - 0.06, M.carpet, -0.06 * L, F, 0, g);
    box(0.28 * L, 0.02, 2 * (this._half(-0.08 * L, roof - 0.12, 0.3 * B.W) - 0.03), M.trim, -0.08 * L, headY, 0, g);   // 천장(헤드라이너)
    for (const sgn of [-1, 1]) {
      box(0.46 * L, 0.42, 0.05, M.trim, -0.05 * L, F + 0.24, sgn * (beltZ - 0.035), g);                  // 도어 트림 (벨트라인 아래)
      const up = box(0.44 * L, 0.21, 0.02, M.trim, -0.05 * L, F + 0.55, sgn * ((beltZ + sillZ) / 2 - 0.02), g);   // 벨트라인~창턱 윗 패널 (안쪽으로 기움)
      up.rotation.x = -sgn * Math.atan2(Math.max(0, beltZ - sillZ), 0.21);
    }
    this._pillars(M);
    const shelfW = 2 * (this._half(-0.31 * L, F + 0.67, 0.37 * B.W) - 0.05), wallW = 2 * (this._half(-0.39 * L, F + 0.32, 0.42 * B.W) - 0.05);
    box(0.1 * L, 0.05, shelfW, M.trim, -0.31 * L, F + 0.67, 0, g);                                         // 뒤 선반 (해치백이면 러기지 커버)
    box(0.06, 0.6, wallW, M.trim, -0.39 * L, F + 0.32, 0, g);                                              // 트렁크 격벽
    const seatSet = B.seatPbr && lib?.pbrSets?.get(B.seatPbr) ? B.seatPbr : 'fabric_leather_01';
    const leatherDark = lib?.pbrSets?.get(seatSet) ? lib.pbr(seatSet, { color: B.seat ?? 0x1b1d23, roughness: 0.72, envMapIntensity: 0.4 }) : M.seat;
    const skin = (obj, mat) => obj?.traverse((o) => { if (o.isMesh) { o.material = mat; if (o.geometry.attributes.color) o.geometry.deleteAttribute('color'); o.castShadow = true; o.receiveShadow = true; } });
    const seat = lib?.instance?.('car_seat', { forward: '+z' });
    CARGO_BASE.set(B.seatX + 0.07, F + 0.33, B.seatZ);
    if (seat) { skin(seat, leatherDark);
      let pass = null;
      for (const sgn of [-1, 1]) { const s = sgn < 0 ? seat : seat.clone(true); s.position.set(B.seatX, F, sgn * B.seatZ); g.add(s); if (sgn > 0) pass = s; }
      this.used.seat = 'car_seat.glb';
      // 조수석 방석 윗면을 실측: 위에서 아래로 레이캐스트 (시트 모델이 바뀌어도 품목이 공중에 뜨거나 파묻히지 않게)
      g.updateMatrixWorld(true);
      const rc = new THREE.Raycaster(new THREE.Vector3(CARGO_BASE.x, F + 1.0, B.seatZ), new THREE.Vector3(0, -1, 0), 0, 2);
      const hit = rc.intersectObject(pass, true)[0];
      if (hit) CARGO_BASE.y = hit.point.y + 0.005;
    } else {
      for (const sgn of [-1, 1]) {
        const z = sgn * B.seatZ;
        box(0.52, 0.12, 0.5, M.seat, B.seatX + 0.17, F + 0.29, z, g);
        box(0.1, 0.5, 0.5, M.seat, B.seatX - 0.14, F + 0.575, z, g).rotation.z = 0.12;
        box(0.09, 0.17, 0.28, M.seat, B.seatX - 0.19, F + 0.92, z, g);
      }
      this.used.seat = '프로시저럴 시트';
      CARGO_BASE.y = F + 0.35;
    }
    const rearSeatW = 2 * (this._half(-0.235 * L, F + 0.8, 0.345 * B.W) - 0.05);
    box(0.5, 0.12, rearSeatW, M.seat, -0.17 * L, F + 0.29, 0, g);                                           // 뒷좌석 (간단)
    box(0.1, 0.5, rearSeatW, M.seat, -0.235 * L, F + 0.57, 0, g).rotation.z = 0.12;
    // 대시보드: 생성 모델은 -Z 가 앞유리 쪽(윗단), +Z 로 센터콘솔이 길게 이어진다 → forward '-z'. 깊이 절반, 폭은 카울 안쪽 실측
    const dashSpec = B.dashboard && lib?.models?.has(B.dashboard.id) ? B.dashboard : { id: 'dashboard', forward: '-z' };
    const dash = lib?.instance?.(dashSpec.id, { forward: dashSpec.forward });
    const dashW = 2 * (this._half(B.cowlX - 0.25, F + 0.6, 0.4 * B.W) - 0.05);
    if (dash) { skin(dash, new THREE.MeshStandardMaterial({ color: B.dash ?? 0x1a1c21, roughness: 0.6, metalness: 0.05 })); dash.scale.set(0.5, 1, dashW / 1.46); dash.position.set(B.cowlX - 0.26, F + 0.17, 0); g.add(dash); this.used.dashboard = `${dashSpec.id}.glb`; }
    else { box(0.32, 0.24, dashW, M.dark, B.cowlX - 0.2, F + 0.53, 0, g); this.used.dashboard = '프로시저럴 대시보드'; }
    const pivot = new THREE.Group();
    pivot.position.set(B.cowlX - 0.65, F + 0.67, -B.seatZ); pivot.rotation.z = 0.35;
    this.wheelSpinner = new THREE.Group();
    const swSpec = B.steering && lib?.models?.has(B.steering.id) ? B.steering : { id: 'steering_wheel', forward: '+z' };
    const sw = lib?.instance?.(swSpec.id, { forward: swSpec.forward });
    if (sw) { skin(sw, new THREE.MeshStandardMaterial({ color: 0x15171c, roughness: 0.55, metalness: 0.15 })); sw.rotation.y += Math.PI; sw.position.y = -0.19; this.wheelSpinner.add(sw); this.used.steering = `${swSpec.id}.glb`; }
    else {
      this.wheelSpinner.add(new THREE.Mesh(new THREE.TorusGeometry(0.18, 0.022, 8, 24).rotateY(Math.PI / 2), M.dark));
      box(0.03, 0.3, 0.035, M.rim, 0, 0, 0, this.wheelSpinner); this.used.steering = '프로시저럴 핸들';
    }
    pivot.add(this.wheelSpinner);
    g.add(pivot);
    /** 캐빈 캠: 운전석 머리 위치(헤드라이너 15 cm 아래)에서 오른쪽 앞(디스플레이·조수석 품목·조수석 창) */
    this.cabinView = { pos: new THREE.Vector3(B.seatX - 0.08, headY - 0.15, -B.seatZ + 0.02), look: new THREE.Vector3(B.cowlX - 0.55, F + 0.49, 0.2), fov: 80 };
  }

  _authoredInterior() {
    const id = this.body.cabin, cabin = this._authoredScene(id);
    if (!cabin) return false;
    this.group.add(cabin);
    const meta = this.lib.items.get(id)?.car_meta ?? {};
    this.cabinMeta = meta;
    CARGO_BASE.fromArray(meta.cargo?.position ?? [0.07, 0.725, 0.4]);
    this.wheelSpinner = cabin.getObjectByName('steering_wheel') ?? new THREE.Group();
    const cv = meta.cabinView ?? { pos: [-0.1, 1.36, -0.38], look: [0.65, 0.93, 0.22], fov: 80 };
    this.cabinView = { pos: new THREE.Vector3().fromArray(cv.pos), look: new THREE.Vector3().fromArray(cv.look), fov: cv.fov };
    this.used.seat = this.used.dashboard = this.used.steering = `${id}.glb — 2021 공식 실내 참조`;
    return true;
  }

  /** 바퀴 4개: 타이어(원통) + 림(디스크·스포크). 위치는 생성 차체의 바퀴 중심 메타가 있으면 그것을 쓴다 */
  _wheels(M) {
    this.spinners = []; this.frontPivots = [];
    let specs = [];
    if (this.wheelMeta && this.wheelMeta.length === 4) {
      // 메타는 GLB 좌표(+z 앞) → 차 로컬(+x 앞): (x, y, z) → (z, y, -x)
      const halfW = (this.body?.W ?? SEDAN.W) / 2;
      specs = this.wheelMeta.map((w) => { if (this.body.nativeCoordinates) return { x: w.center[0], y: w.center[1], z: w.center[2], r: w.radius, w: w.width };
        const wd = Math.min(0.26, w.width), zc = Math.sign(-w.center[0]) * Math.min(Math.abs(w.center[0]), halfW - wd / 2 - 0.012);   // 타이어 바깥면이 차체 밖으로 안 나오게
        return { x: w.center[2], y: w.center[1], z: zc, r: w.radius, w: wd }; });
    } else {
      for (const x of [AXLE_X, -AXLE_X]) for (const z of [-TRACK_HALF, TRACK_HALF]) specs.push({ x, y: CAR.wheelRadius, z, r: CAR.wheelRadius, w: 0.24 });
    }
    for (const s of specs) {
      const pivot = new THREE.Group(), spin = new THREE.Group();
      const authoredWheel = this.body.wheelAsset && this._authoredScene(this.body.wheelAsset);
      if (authoredWheel) {
        if (s.z < 0) authoredWheel.rotation.y = Math.PI;
        spin.add(authoredWheel); pivot.position.set(s.x, s.y, s.z); pivot.add(spin); this.group.add(pivot);
        this.spinners.push(spin); if (s.x > 0) this.frontPivots.push(pivot);
        continue;
      }
      const tire = new THREE.Mesh(new THREE.CylinderGeometry(s.r, s.r, s.w, 28).rotateX(Math.PI / 2), M.tire);
      const rr = Math.min(this.body.rimRadius ?? s.r * 0.66, s.r * 0.8), spokes = this.body.spokes ?? 5;
      const rim = new THREE.Mesh(new THREE.CylinderGeometry(rr, rr, s.w * 1.02, 40).rotateX(Math.PI / 2), M.rimDark);
      spin.add(tire, rim);
      for (let k = 0; k < spokes; k++) {
        const sp = new THREE.Mesh(new THREE.BoxGeometry(rr * 0.67, spokes > 5 ? 0.018 : 0.05, s.w * 1.04), M.rim);
        const h = new THREE.Group(); h.rotation.z = (k / spokes) * Math.PI * 2; sp.position.set(rr * 0.62, 0, 0); h.add(sp); spin.add(h);
      }
      if (this.body.rimRadius) for (const side of [-1, 1]) {
        const lip = new THREE.Mesh(new THREE.TorusGeometry(rr, 0.006, 6, 40), M.rim);
        lip.position.z = side * s.w * 0.525; spin.add(lip);
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
    const spec = this.cabinMeta?.display;
    this.dispCanvas = document.createElement('canvas');
    this.dispCanvas.width = 512; this.dispCanvas.height = spec ? Math.round(512 * spec.height / spec.width) : 160;
    this.dispTex = new THREE.CanvasTexture(this.dispCanvas);
    this.dispTex.colorSpace = THREE.SRGBColorSpace;
    const panel = new THREE.Mesh(new THREE.PlaneGeometry(spec?.width ?? 0.56, spec?.height ?? 0.175), new THREE.MeshBasicMaterial({ map: this.dispTex, toneMapped: false }));
    if (spec) {
      this._placeDisplay(panel, spec);
      const cluster = this.cabinMeta.instrumentDisplay;
      if (cluster) {
        this.clusterCanvas = document.createElement('canvas'); this.clusterCanvas.width = 512;
        this.clusterCanvas.height = Math.round(512 * cluster.height / cluster.width);
        this.clusterTex = new THREE.CanvasTexture(this.clusterCanvas); this.clusterTex.colorSpace = THREE.SRGBColorSpace;
        const screen = new THREE.Mesh(new THREE.PlaneGeometry(cluster.width, cluster.height), new THREE.MeshBasicMaterial({ map: this.clusterTex, toneMapped: false }));
        this._placeDisplay(screen, { ...cluster, position: cluster.position ?? cluster.center });
      }
      return;
    }
    const B = this.body, px = B.cowlX - 0.2, py = B.floor + 0.55;
    panel.position.set(px, py, 0.1);                                   // 대시보드 윗단 뒷면에 붙은 와이드 디스플레이
    panel.rotation.y = -Math.PI / 2;
    this.group.add(panel);
    const bezel = box(0.015, 0.21, 0.62, this.mats.dark, px + 0.008, py, 0.1, this.group);
    bezel.renderOrder = -1;
  }

  _placeDisplay(panel, spec) {
    const n = new THREE.Vector3().fromArray(spec.normal).normalize();
    const up = new THREE.Vector3().fromArray(spec.up ?? [0, 1, 0]).normalize();
    const right = new THREE.Vector3().crossVectors(up, n).normalize();
    up.crossVectors(n, right).normalize();
    panel.position.fromArray(spec.position).addScaledVector(n, 0.002);
    panel.quaternion.setFromRotationMatrix(new THREE.Matrix4().makeBasis(right, up, n));
    this.group.add(panel);
  }

  drawDisplay(badge, totalG, limit, msg) {
    const c = this.dispCanvas.getContext('2d'), W = this.dispCanvas.width, H = this.dispCanvas.height;
    const col = badge === 'OVER' ? COLORS.red : badge === 'NEAR' ? COLORS.champ : COLORS.mint;
    c.fillStyle = COLORS.bg; c.fillRect(0, 0, W, H);
    if (this.cabinMeta) {
      c.save(); c.scale(1, H / 240);
      c.textAlign = 'left'; c.textBaseline = 'middle'; c.fillStyle = COLORS.text; c.font = `600 25px ${FONT}`; c.fillText('VIP 조수석', 24, 30);
      c.fillStyle = col; c.font = `bold 27px ${FONT}`; c.fillText(badge, 24, 79);
      c.font = `bold 62px ${FONT}`; c.fillStyle = COLORS.text; c.fillText(`${totalG.toFixed(2)} G`, 170, 104);
      c.font = `23px ${FONT}`; c.fillStyle = COLORS.dim; c.fillText(`한계 ${limit.toFixed(2)} G`, 170, 151);
      c.fillStyle = COLORS.champ; c.font = `23px ${FONT}`; let text = msg || '조용히 지켜보는 중';
      while (text && c.measureText(text).width > W - 48) text = text.slice(0, -1);
      c.fillText(text, 24, 208); c.restore(); this.dispTex.needsUpdate = true; return;
    }
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
    for (const mat of this.rearLampMaterials ?? []) mat.emissiveIntensity = braking ? 2.5 : 0.8;
    if (this.clusterCanvas) {
      const c = this.clusterCanvas.getContext('2d'), W = this.clusterCanvas.width, H = this.clusterCanvas.height;
      c.fillStyle = '#e6e9e8'; c.fillRect(0, 0, W, H);
      c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillStyle = '#253336';
      c.font = `500 66px ${FONT}`; c.fillText(Math.round(Math.abs(veh.v) * 3.6).toString(), W * 0.32, H * 0.46);
      c.font = `19px ${FONT}`; c.fillText('km/h', W * 0.32, H * 0.79);
      c.font = `500 25px ${FONT}`; c.fillText('IONIQ 5', W * 0.75, H * 0.35);
      c.strokeStyle = '#8aacae'; c.lineWidth = 3; c.beginPath(); c.moveTo(W * 0.59, H * 0.72); c.lineTo(W * 0.91, H * 0.72); c.stroke();
      this.clusterTex.needsUpdate = true;
    }
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
