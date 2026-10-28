/**
 * assets.js — 에셋 로더. manifest.json + 생성 에셋 기대 목록을 읽고, GLTFLoader(+DRACOLoader)·RGBELoader·TextureLoader 로
 * 모두 불러온 뒤 실측 스케일·피벗·재질을 정리한다. 진행률은 바이트 기준(모르면 추정치)으로 onProgress(0~1, 문구)에 알린다.
 *
 * 경로 규칙
 *  - Poly Haven 모델: dist/<id>.glb (Draco + WebP ≤1k) 우선, 실패하면 manifest 의 원본 glTF.
 *  - 생성 모델(GENERATED_IDS): glb/<id>.glb. 디렉터리 목록(python http.server)이 있으면 목록으로 존재를 확인하고
 *    (dist/<id>.glb 최적화본이 있으면 그것을 우선), 목록이 없으면 manifest → (PROBE_GENERATED 이면) HEAD 로 확인한다.
 *    없으면 호출 쪽(car.js·cargo.js)이 대체물을 쓴다. 나중에 GLB 를 넣기만 하면 코드 수정 없이 나타난다.
 */
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { DRACOLoader } from 'three/addons/loaders/DRACOLoader.js';
import { RGBELoader } from 'three/addons/loaders/RGBELoader.js';
import { ASSET_ROOT, DRACO_PATH, HDRI_PATH, GENERATED_IDS, REAL_SIZE, PROBE_GENERATED } from './config.js';

/** PBR 텍스처 세트별 실제 타일 크기 (m). 프로시저럴 지오메트리 UV 는 모두 미터 단위라 repeat = 1/타일 */
export const TEX_TILE = {
  asphalt_02: 4, asphalt_04: 3, road_damaged_clean: 4, rectangular_paving: 1.6, brushed_concrete: 2.4,
  concrete_floor_02: 3, brick_4: 1.8, painted_metal_shutter: 1.6, square_brick_paving: 1.4,
  leather_white: 0.5, brown_leather: 0.5, fabric_leather_01: 0.45,
};
/** 다운로드 크기 추정 (MB) — 서버가 Content-Length 를 주기 전 진행률 가중치 */
const EST_MB = { hdr: 6.8, glb: 0.6, tex: 1.0, tree: 7.3 };

export class AssetLibrary {
  constructor(renderer, onProgress = () => {}) {
    this.renderer = renderer;
    this.onProgress = onProgress;
    this.items = new Map();       // manifest id → item
    this.textureSets = {};        // manifest textures
    this.models = new Map();      // id → { gltf, url, kind: 'polyhaven'|'generated' }
    this.pbrSets = new Map();     // set → { map, normalMap, arm }
    this.generated = new Map();   // id → url (존재하는 생성 GLB)
    this.log = [];                // CHANGES/디버그용: 무엇을 어디서 불러왔나
    this.bytes = 0;
    this.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
    this._tasks = new Map();      // key → { loaded, total }
    const draco = new DRACOLoader().setDecoderPath(DRACO_PATH);
    this.gltfLoader = new GLTFLoader().setDRACOLoader(draco);
    this.texLoader = new THREE.TextureLoader();
    this.rgbeLoader = new RGBELoader();
  }

  // ───────── 진행률 ─────────
  _task(key, estMB) { this._tasks.set(key, { loaded: 0, total: estMB * 1e6, done: false }); this._emit(key); }
  _progress(key, e) {
    const t = this._tasks.get(key);
    if (!t) return;
    if (e.lengthComputable && e.total > 0) t.total = e.total;
    t.loaded = Math.min(e.loaded, t.total);
    this._emit(key);
  }
  _done(key) {
    const t = this._tasks.get(key);
    if (!t) return;
    this.bytes += t.total;
    Object.assign(t, { loaded: t.total, done: true });
    this._emit(key);
  }
  _emit(label) {
    let l = 0, tot = 0;
    for (const t of this._tasks.values()) { l += t.loaded; tot += t.total; }
    this.onProgress(tot > 0 ? l / tot : 0, label);
  }

  // ───────── 목록·존재 확인 ─────────
  /** manifest.json 과 (가능하면) glb/·dist/ 디렉터리 목록을 읽어 생성 GLB 존재를 결정한다 */
  async init() {
    const res = await fetch(`${ASSET_ROOT}manifest.json`, { cache: 'no-cache' });
    if (!res.ok) throw new Error(`manifest.json ${res.status}`);
    const man = await res.json();
    for (const it of man.items ?? []) this.items.set(it.id, it);
    this.textureSets = man.textures ?? {};
    this.hdris = man.hdris ?? [];
    const glbList = PROBE_GENERATED ? await this._listDir('glb/') : null;   // 정적 호스트(저장소 사본)에서는 목록 조회도 생략 (404 소음 방지)
    const distList = glbList ? await this._listDir('dist/') : null; // 목록을 주는 서버에서만 (404 소음 방지)
    for (const id of GENERATED_IDS) {
      const item = this.items.get(id);
      let present = false;
      if (glbList) present = glbList.has(`${id}.glb`) || (!!item && item.kind === 'generated');
      else if (item) present = true;
      else if (PROBE_GENERATED) present = await this._head(`${ASSET_ROOT}glb/${id}.glb`);
      if (!present) continue;
      const url = distList?.has(`${id}.glb`) ? `dist/${id}.glb` : (item?.path ?? `glb/${id}.glb`);
      this.generated.set(id, url);
    }
    return this;
  }

  /** python http.server 식 HTML 디렉터리 목록 → 파일 이름 집합. 목록을 안 주면 null */
  async _listDir(dir) {
    try {
      const r = await fetch(`${ASSET_ROOT}${dir}`, { cache: 'no-cache' });
      if (!r.ok || !(r.headers.get('content-type') ?? '').includes('html')) return null;
      const html = await r.text();
      return new Set([...html.matchAll(/href="([^"?#]+)"/g)].map((m) => decodeURIComponent(m[1]).split('/').filter(Boolean).pop()));
    } catch { return null; }
  }

  async _head(url) {
    try { return (await fetch(url, { method: 'HEAD', cache: 'no-cache' })).ok; } catch { return false; }
  }

  hasGenerated(id) { return this.generated.has(id); }

  // ───────── 로딩 ─────────
  /** GLB/glTF 한 개. 실패하면 null (호출 쪽이 폴백) */
  async _loadGltf(key, url, estMB) {
    this._task(key, estMB);
    try {
      const gltf = await this.gltfLoader.loadAsync(ASSET_ROOT + url, (e) => this._progress(key, e));
      this._done(key);
      return gltf;
    } catch (err) {
      this._done(key);
      console.info(`[assets] ${url} 로드 실패 → 폴백`, err?.message ?? err);
      return null;
    }
  }

  /** Poly Haven 모델: dist/<id>.glb → (실패 시) manifest 의 glTF. distFile 로 LOD 파일 지정 가능 */
  async loadPolyHaven(id, { distFile = `${id}.glb`, estMB = EST_MB.glb } = {}) {
    let url = `dist/${distFile}`;
    let gltf = await this._loadGltf(id, url, estMB);
    const item = this.items.get(id);
    if (!gltf && item?.path) { url = item.path; gltf = await this._loadGltf(`${id}#gltf`, url, estMB * 3); }
    if (!gltf) { this.log.push({ id, url: null, ok: false }); return null; }
    this._prepare(gltf.scene);
    this.models.set(id, { gltf, url, kind: 'polyhaven', item });
    this.log.push({ id, url, ok: true });
    return gltf;
  }

  /** 생성 모델 (존재할 때만). 없거나 실패하면 null */
  async loadGenerated(id) {
    const url = this.generated.get(id);
    if (!url) return null;
    const gltf = await this._loadGltf(`g:${id}`, url, 3);
    if (!gltf) { this.generated.delete(id); return null; }
    this._prepare(gltf.scene);
    this.models.set(id, { gltf, url, kind: 'generated', item: this.items.get(id) });
    this.log.push({ id, url, ok: true, generated: true });
    return gltf;
  }

  /** PBR 세트: Diffuse(sRGB) + nor_gl + arm(R=AO, G=Rough, B=Metal — Rough·AO 맵을 한 장으로). 해상도는 manifest 에서 찾는다 */
  async loadPBR(set) {
    const files = this.textureSets[set] ?? [];
    const pick = (kind) => files.find((f) => f.includes(`_${kind}_`)) ?? `${set}_${kind}_1k.jpg`;
    const base = `${ASSET_ROOT}polyhaven/textures/${set}/`;
    const load = async (kind, srgb) => {
      const key = `tex:${set}:${kind}`;
      this._task(key, EST_MB.tex * (pick(kind).includes('2k') ? 3 : 1));
      const t = await this.texLoader.loadAsync(base + pick(kind), (e) => this._progress(key, e));
      this._done(key);
      t.colorSpace = srgb ? THREE.SRGBColorSpace : THREE.NoColorSpace;
      t.wrapS = t.wrapT = THREE.RepeatWrapping;
      t.anisotropy = this.anisotropy;
      const r = 1 / (TEX_TILE[set] ?? 2);
      t.repeat.set(r, r);
      return t;
    };
    const [map, normalMap, arm] = await Promise.all([load('Diffuse', true), load('nor_gl', false), load('arm', false)]);
    const s = { map, normalMap, arm };
    this.pbrSets.set(set, s);
    return s;
  }

  /** HDRI → PMREM 환경맵 (조명·반사 전용, 배경으로 쓰지 않는다) */
  async loadEnvironment() {
    const key = 'hdri';
    this._task(key, EST_MB.hdr);
    const hdr = await this.rgbeLoader.loadAsync(ASSET_ROOT + HDRI_PATH, (e) => this._progress(key, e));
    this._done(key);
    const pmrem = new THREE.PMREMGenerator(this.renderer);
    const env = pmrem.fromEquirectangular(hdr).texture;
    hdr.dispose(); pmrem.dispose();
    this.envMap = env;
    return env;
  }

  /** 세트 이름으로 MeshStandardMaterial / MeshPhysicalMaterial 생성 (Diffuse·노멀·러프·AO 전부 사용) */
  pbr(set, opts = {}, physical = false) {
    const s = this.pbrSets.get(set);
    const { roughness = 1, metalness = 0, normalScale = 1, aoIntensity = 1, ...rest } = opts;
    const M = physical ? THREE.MeshPhysicalMaterial : THREE.MeshStandardMaterial;
    const m = new M({ roughness, metalness, ...rest });
    if (s) {
      Object.assign(m, { map: s.map, normalMap: s.normalMap, roughnessMap: s.arm, aoMap: s.arm, aoMapIntensity: aoIntensity });
      m.normalScale.set(normalScale, normalScale);
    }
    m.name = `pbr:${set}`;
    return m;
  }

  /** 모델 공통 정리: 텍스처 이방성, BLEND 잎 → alphaTest(정렬·깊이 문제 없이), 기본 그림자 끔 */
  _prepare(root) {
    root.traverse((o) => {
      if (!o.isMesh) return;
      o.castShadow = false; o.receiveShadow = false;
      for (const m of [].concat(o.material)) {
        for (const k of ['map', 'normalMap', 'roughnessMap', 'metalnessMap', 'aoMap', 'emissiveMap']) if (m[k]) m[k].anisotropy = this.anisotropy;
        if (m.transparent && /leaves|leaf|foliage/i.test(m.name)) {
          Object.assign(m, { transparent: false, alphaTest: 0.45, depthWrite: true, alphaToCoverage: true });
        }
        m.envMapIntensity = 0.7;
      }
    });
  }

  // ───────── 스케일·피벗 ─────────
  /** 생성 GLB 의 목표 실측 치수(m, 기준 축 값). manifest 의 real_size_m → 그럴듯한 size_m → 실제 크기 표 순 */
  targetSize(id) {
    const spec = REAL_SIZE[id], item = this.items.get(id);
    if (!spec) return null;
    const pickAxis = (s) => (spec.axis === 'h' ? s[1] : spec.axis === 'len' ? Math.max(s[0], s[2]) : Math.max(...s));
    if (Array.isArray(item?.real_size_m)) return { axis: spec.axis, m: pickAxis(item.real_size_m) };
    if (Array.isArray(item?.size_m)) {
      const m = pickAxis(item.size_m);
      if (m > spec.m / 2 && m < spec.m * 2) return { axis: spec.axis, m };   // 이미 실측 스케일로 저장된 GLB
    }
    return { axis: spec.axis, m: spec.m };
  }

  /** 모델 사본을 실측 스케일·바닥 중심 피벗으로 감싼 Group 으로 돌려준다.
   *  opts.yaw: 추가 회전, opts.forward: 모델의 앞 방향('+x'|'-x'|'+z'|'-z') → 차 로컬 +X 로 돌림 */
  instance(id, opts = {}) {
    const rec = this.models.get(id);
    if (!rec) return null;
    const src = rec.gltf.scene;
    const obj = opts.clone === false ? src : src.clone(true);
    const inner = new THREE.Group();
    inner.add(obj);
    const fwd = opts.forward ?? rec.item?.forward ?? (rec.kind === 'generated' ? '+z' : null);
    if (Array.isArray(rec.item?.rotation_deg)) inner.rotation.set(...rec.item.rotation_deg.map((d) => (d * Math.PI) / 180));
    else if (fwd) inner.rotation.y = { '+x': 0, '-x': Math.PI, '+z': Math.PI / 2, '-z': -Math.PI / 2 }[fwd] ?? 0;
    if (opts.yaw) inner.rotation.y += opts.yaw;
    inner.updateMatrixWorld(true);
    const box = new THREE.Box3().setFromObject(inner), size = box.getSize(new THREE.Vector3());
    let scale = 1;
    const tgt = opts.target ?? (rec.kind === 'generated' || REAL_SIZE[id] ? this.targetSize(id) : null);
    if (tgt) {
      const meas = tgt.axis === 'h' ? size.y : tgt.axis === 'len' ? Math.max(size.x, size.z) : Math.max(size.x, size.y, size.z);
      scale = tgt.m / Math.max(1e-6, meas);
    } else if (Array.isArray(rec.item?.size_m)) {
      // Poly Haven: size_m 이 실측 — 불러온 bbox 와 비교해 단위 차이만 보정 (보통 1.0)
      const s = rec.item.size_m, meas = [size.x, size.y, size.z].sort((a, b) => b - a), ref = [...s].sort((a, b) => b - a);
      const r = ref[0] / Math.max(1e-6, meas[0]);
      if (r < 0.9 || r > 1.1) scale = r;
    }
    const wrap = new THREE.Group();
    wrap.name = `asset:${id}`;
    inner.scale.setScalar(scale);
    const c = box.getCenter(new THREE.Vector3());
    inner.position.set(-c.x * scale, -box.min.y * scale, -c.z * scale);
    wrap.add(inner);
    wrap.userData = { id, kind: rec.kind, url: rec.url, size: size.multiplyScalar(scale), scale };
    return wrap;
  }
}

/** 오브젝트 bbox 크기 */
export function measure(obj) {
  obj.updateMatrixWorld(true);
  return new THREE.Box3().setFromObject(obj).getSize(new THREE.Vector3());
}

/**
 * 키트 커터: 하나로 합쳐진(gltf-transform join) 키트 메시를 한 번 평탄화(월드 좌표 삼각형 배열)해 두고,
 * cut(box, origin) 으로 상자 안에 무게중심이 있는 삼각형만 재질별 지오메트리로 잘라낸다 (origin 을 빼서 모듈 로컬 좌표).
 * InstancedMesh(EXT_mesh_gpu_instancing)도 펼쳐서 처리한다.
 */
export class KitCutter {
  constructor(root) {
    root.updateMatrixWorld(true);
    this.sets = [];  // { material, p: Float32Array(9/tri), n, uv, c: Float32Array(3/tri) }
    const a = new THREE.Vector3(), n = new THREE.Vector3();
    const im = new THREE.Matrix4(), world = new THREE.Matrix4(), nm = new THREE.Matrix3();
    root.traverse((o) => {
      if (!o.isMesh) return;
      const g = o.geometry, pos = g.attributes.position, nor = g.attributes.normal, uv = g.attributes.uv, idx = g.index;
      const mats = [].concat(o.material);
      const total = idx ? idx.count : pos.count;
      const groups = g.groups.length ? g.groups : [{ start: 0, count: total, materialIndex: 0 }];
      const instances = o.isInstancedMesh ? o.count : 1;
      for (const gr of groups) {
        const material = mats[gr.materialIndex ?? 0] ?? mats[0];
        const end = Math.min(gr.start + gr.count, total), tris = Math.floor((end - gr.start) / 3) * instances;
        const P = new Float32Array(tris * 9), N = new Float32Array(tris * 9), U = new Float32Array(tris * 6), C = new Float32Array(tris * 3);
        let t3 = 0;
        for (let k = 0; k < instances; k++) {
          world.copy(o.matrixWorld);
          if (o.isInstancedMesh) { o.getMatrixAt(k, im); world.multiply(im); }
          nm.getNormalMatrix(world);
          for (let t = gr.start; t + 2 < end; t += 3, t3++) {
            let cx = 0, cy = 0, cz = 0;
            for (let v = 0; v < 3; v++) {
              const i = idx ? idx.getX(t + v) : t + v, o9 = t3 * 9 + v * 3;
              a.fromBufferAttribute(pos, i).applyMatrix4(world);
              P[o9] = a.x; P[o9 + 1] = a.y; P[o9 + 2] = a.z;
              cx += a.x; cy += a.y; cz += a.z;
              if (nor) { n.fromBufferAttribute(nor, i).applyMatrix3(nm).normalize(); N[o9] = n.x; N[o9 + 1] = n.y; N[o9 + 2] = n.z; }
              if (uv) { U[t3 * 6 + v * 2] = uv.getX(i); U[t3 * 6 + v * 2 + 1] = uv.getY(i); }
            }
            C[t3 * 3] = cx / 3; C[t3 * 3 + 1] = cy / 3; C[t3 * 3 + 2] = cz / 3;
          }
        }
        this.sets.push({ material, p: P, n: nor ? N : null, uv: uv ? U : null, c: C, count: t3 });
      }
    });
  }

  /** @returns {{geometry: THREE.BufferGeometry, material: THREE.Material}[]} */
  cut(box, origin = new THREE.Vector3()) {
    const byMat = new Map(), { min, max } = box;
    for (const s of this.sets) {
      const sel = [];
      for (let t = 0; t < s.count; t++) {
        const x = s.c[t * 3], y = s.c[t * 3 + 1], z = s.c[t * 3 + 2];
        if (x >= min.x && x <= max.x && y >= min.y && y <= max.y && z >= min.z && z <= max.z) sel.push(t);
      }
      if (!sel.length) continue;
      let rec = byMat.get(s.material);
      if (!rec) byMat.set(s.material, (rec = { p: [], n: [], uv: [], hasN: !!s.n, hasUV: !!s.uv }));
      for (const t of sel) {
        for (let k = 0; k < 9; k += 3) {
          rec.p.push(s.p[t * 9 + k] - origin.x, s.p[t * 9 + k + 1] - origin.y, s.p[t * 9 + k + 2] - origin.z);
          if (s.n) rec.n.push(s.n[t * 9 + k], s.n[t * 9 + k + 1], s.n[t * 9 + k + 2]);
        }
        if (s.uv) for (let k = 0; k < 6; k++) rec.uv.push(s.uv[t * 6 + k]);
      }
    }
    const parts = [];
    for (const [material, rec] of byMat) {
      const geo = new THREE.BufferGeometry();
      geo.setAttribute('position', new THREE.Float32BufferAttribute(rec.p, 3));
      if (rec.hasN) geo.setAttribute('normal', new THREE.Float32BufferAttribute(rec.n, 3)); else geo.computeVertexNormals();
      if (rec.hasUV) geo.setAttribute('uv', new THREE.Float32BufferAttribute(rec.uv, 2));
      geo.computeBoundingSphere();
      parts.push({ geometry: geo, material });
    }
    return parts;
  }

  /** 전체 (상자 제한 없음) */
  all(origin) {
    return this.cut(new THREE.Box3(new THREE.Vector3(-1e9, -1e9, -1e9), new THREE.Vector3(1e9, 1e9, 1e9)), origin);
  }
}

/** 모델 전체를 (월드 변환 적용) 재질별 지오메트리 목록으로 평탄화 — 인스턴싱용 */
export function flattenParts(root) {
  return new KitCutter(root).all();
}

/** 지오메트리 목록의 bbox */
export function partsBox(parts) {
  const box = new THREE.Box3();
  for (const p of parts) { p.geometry.computeBoundingBox(); box.union(p.geometry.boundingBox); }
  return box;
}

/** 지오메트리 목록 전체를 이동·스케일 (제자리) */
export function transformParts(parts, matrix) {
  for (const p of parts) { p.geometry.applyMatrix4(matrix); p.geometry.computeBoundingSphere(); }
  return parts;
}
