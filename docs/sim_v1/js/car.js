/**
 * car.js — 로우폴리 세단(짙은 남색, 로고·번호판 없음) + 캐빈 실내 + 조수석 케이크 상자.
 * 차는 로컬 +X 방향을 보고, 왼쪽이 -Z(운전석), 오른쪽이 +Z(조수석)이다.
 */
import * as THREE from 'three';
import { DEG, STEER_RATIO, CAR, CARGO, COLORS, FONT } from './config.js';

const NAVY = 0x22386a;
const AXLE_X = 1.45;                                      // 앞·뒤 차축 위치 (축거 2.9 m)
const TRACK_HALF = 0.8;                                   // 바퀴 좌우 간격의 절반
const SIDE_Z = 0.906;                                     // 차체 옆면 바로 바깥 (휠 하우스·벨트라인 위치)
const CARGO_BASE = new THREE.Vector3(0.05, 0.68, 0.42);  // 조수석 방석 윗면 중앙
const BRAKE_LIGHT_ACCEL = -0.5;                           // 이 감속보다 크면 브레이크등 (m/s²)
const TAIL_OFF = 0xa0101a, TAIL_ON = 0xff3030;            // 미등 / 브레이크등 색
const ROAD_Y = 0.04;                                      // 노면 높이 (world.js 와 동일)
const TILT_FOLLOW = 8;                                    // 상자 기울기 추종 속도 (1/s)
/** 캐빈 캠: 운전석 헤드레스트 앞(운전자 머리 위치)에서 오른쪽 앞을 본다 — 센터 디스플레이(위 가운데), 조수석 상자(가운데 오른쪽),
 *  대시보드, 조수석 창. 화면 구석의 HUD 패널을 피하도록 방위 41°·아래 27.5°, 세로 시야 80° */
export const CABIN_VIEW = { pos: new THREE.Vector3(-0.2, 1.27, -0.4), look: new THREE.Vector3(0.4695, 0.8083, 0.182), fov: 80 };
const GAUGE_YAW = -0.93;                                  // 손상도 게이지를 체이스 캠(뒤)과 캐빈 캠(왼쪽 앞) 사이로 돌린다

/** 단위 상자의 윗면·아랫면을 다른 범위로 늘려 사다리꼴 기둥(유리 캐빈 등)을 만든다. open 이면 위·아래 면 제거 */
function frustum(bot, top, y0, y1, open = false) {
  const g = new THREE.BoxGeometry(1, 1, 1), p = g.attributes.position;
  for (let i = 0; i < p.count; i++) {
    const up = p.getY(i) > 0, r = up ? top : bot, u = p.getX(i) + 0.5;
    p.setXYZ(i, r.x0 + u * (r.x1 - r.x0), up ? y1 : y0, p.getZ(i) * r.w);
  }
  if (open) {                             // BoxGeometry 면 순서 +x,-x,+y,-y,+z,-z (면당 인덱스 6개) → ±y 면 제거
    g.setIndex(Array.from(g.index.array).filter((_, k) => k < 12 || k >= 24));
    g.clearGroups();
  }
  g.computeVertexNormals();
  return g;
}

/** 상자 메시를 만들어 parent 에 붙인다 */
function box(w, h, d, mat, x, y, z, parent) {
  const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat);
  m.position.set(x, y, z);
  parent.add(m);
  return m;
}

export class CarModel {
  constructor() {
    this.group = new THREE.Group();
    this.slide = new THREE.Vector2();   // 상자 밀림 (x = 차 앞뒤, y = 차 좌우), tilt 도 같은 축
    this.tilt = new THREE.Vector2();
    const M = this.mats = {
      body: new THREE.MeshStandardMaterial({ color: NAVY, metalness: 0.25, roughness: 0.45 }),
      dark: new THREE.MeshStandardMaterial({ color: 0x1c2027, roughness: 0.85 }),
      seat: new THREE.MeshStandardMaterial({ color: 0x3a3f4a, roughness: 0.85 }),
      glass: new THREE.MeshStandardMaterial({ color: 0x0a1626, transparent: true, opacity: 0.4, roughness: 0.05, side: THREE.DoubleSide, depthWrite: false }),
      chrome: new THREE.MeshStandardMaterial({ color: 0xd7dce3, metalness: 0.5, roughness: 0.2, emissive: 0x2a2e34 }),
      tire: new THREE.MeshStandardMaterial({ color: 0x111111, roughness: 0.9 }),
      rim: new THREE.MeshStandardMaterial({ color: 0x9aa3ad, metalness: 0.7, roughness: 0.3 }),
      head: new THREE.MeshBasicMaterial({ color: 0xfff3d6 }),
      tail: new THREE.MeshBasicMaterial({ color: TAIL_OFF }),
    };
    this._body(M); this._interior(M); this._wheels(M); this._cargo(); this._display();
  }

  /** 3박스 세단: 바닥·후드(앞이 낮은 코)·캐빈(좁아지는 지붕)·트렁크, 범퍼·그릴·벨트라인·미러·등화 */
  _body(M) {
    const g = this.group, mesh = (geo, mat) => g.add(new THREE.Mesh(geo, mat));
    box(4.5, 0.22, 1.76, M.body, 0, 0.40, 0, g);                                                          // 바닥·사이드 실
    mesh(frustum({ x0: 0.95, x1: 2.42, w: 1.80 }, { x0: 0.95, x1: 2.25, w: 1.66 }, 0.51, 0.86), M.body);   // 후드
    mesh(frustum({ x0: -2.42, x1: -1.35, w: 1.80 }, { x0: -2.28, x1: -1.35, w: 1.70 }, 0.51, 0.97), M.body); // 트렁크
    for (const z of [-0.865, 0.865]) box(2.3, 0.47, 0.07, M.body, -0.2, 0.745, z, g);                      // 도어(실내는 비워 둔다)
    for (const z of [-SIDE_Z, SIDE_Z]) box(2.32, 0.035, 0.02, M.chrome, -0.2, 0.975, z, g);                // 크롬 벨트라인
    mesh(frustum({ x0: -1.35, x1: 0.95, w: 1.70 }, { x0: -0.85, x1: 0.40, w: 1.42 }, 0.92, 1.395, true), M.glass); // 틴팅 유리
    mesh(frustum({ x0: -0.88, x1: 0.43, w: 1.46 }, { x0: -0.80, x1: 0.36, w: 1.38 }, 1.40, 1.46), M.body);  // 지붕
    for (const x of [2.36, -2.36]) box(0.16, 0.2, 1.82, M.dark, x, 0.37, 0, g);                            // 앞·뒤 범퍼
    box(0.04, 0.12, 0.8, M.dark, 2.35, 0.64, 0, g);                                                        // 그릴(무늬·로고 없음)
    for (const z of [-0.62, 0.62]) { box(0.06, 0.11, 0.36, M.head, 2.3, 0.76, z, g); box(0.06, 0.1, 0.42, M.tail, -2.33, 0.84, z, g); }
    for (const z of [-0.97, 0.97]) box(0.14, 0.09, 0.16, M.body, 0.8, 1.03, z, g);                          // 사이드 미러
    const spot = new THREE.SpotLight(0xfff1d0, 400, 80, 0.5, 0.6, 2);
    spot.position.set(2.3, 0.8, 0);
    spot.target.position.set(18, 0, 0);
    const dome = new THREE.SpotLight(0xffe2b0, 2.2, 3, 1.0, 0.6, 2);    // 실내등(아래로만): 상자·시트·대시보드가 밤에도 보이게
    dome.position.set(0, 1.36, 0.35); dome.target.position.set(0.05, 0.6, 0.42);
    const shadow = new THREE.Mesh(new THREE.PlaneGeometry(5.2, 2.4).rotateX(-Math.PI / 2),
      new THREE.MeshBasicMaterial({ color: 0x000000, transparent: true, opacity: 0.45, depthWrite: false }));
    shadow.position.y = 0.03;
    g.add(spot, spot.target, dome, dome.target, shadow);
  }

  /** 대시보드·시트·핸들 (캐빈 캠용 간단 모델) */
  _interior(M) {
    const g = this.group;
    box(0.32, 0.24, 1.66, M.dark, 0.80, 0.86, 0, g);
    for (const z of [-0.42, 0.42]) {
      box(0.52, 0.12, 0.5, M.seat, 0.05, 0.62, z, g);
      box(0.1, 0.5, 0.5, M.seat, -0.26, 0.905, z, g).rotation.z = 0.12;
      box(0.09, 0.17, 0.28, M.seat, -0.31, 1.25, z, g);
    }
    box(0.5, 0.12, 1.5, M.seat, -0.85, 0.62, 0, g);
    box(0.1, 0.5, 1.5, M.seat, -1.17, 0.9, 0, g).rotation.z = 0.12;
    const pivot = new THREE.Group();            // 핸들 기둥(운전석 쪽으로 기울어짐)
    pivot.position.set(0.5, 1.0, -0.42);
    pivot.rotation.z = 0.35;
    this.wheelSpinner = new THREE.Group();      // 핸들 회전축 = pivot 의 X 축
    const rim = new THREE.Mesh(new THREE.TorusGeometry(0.18, 0.022, 8, 24).rotateY(Math.PI / 2), M.dark);
    this.wheelSpinner.add(rim);
    box(0.03, 0.3, 0.035, M.rim, 0, 0, 0, this.wheelSpinner);
    pivot.add(this.wheelSpinner);
    g.add(pivot);
  }

  /** 바퀴 4개(앞바퀴는 조향 피벗, 모두 회전 그룹) + 휠 하우스(어두운 반원) + 차체색 아치 */
  _wheels(M) {
    this.spinners = [];
    this.frontPivots = [];
    const R = CAR.wheelRadius;
    const tireG = new THREE.CylinderGeometry(R, R, 0.24, 20).rotateX(Math.PI / 2);
    const spokeG = new THREE.BoxGeometry(0.5, 0.07, 0.25);
    const wellG = new THREE.CircleGeometry(R + 0.08, 18, 0, Math.PI), lipG = new THREE.TorusGeometry(R + 0.09, 0.03, 6, 18, Math.PI);
    for (const x of [AXLE_X, -AXLE_X]) for (const z of [-TRACK_HALF, TRACK_HALF]) {
      const pivot = new THREE.Group(), spin = new THREE.Group(), side = Math.sign(z);
      const spokeA = new THREE.Mesh(spokeG, M.rim), spokeB = spokeA.clone();
      spokeB.rotation.z = Math.PI / 2;
      spin.add(new THREE.Mesh(tireG, M.tire), spokeA, spokeB);
      pivot.position.set(x, R, z);
      pivot.add(spin);
      for (const [geo, mat, dz] of [[wellG, M.dark, 0], [lipG, M.body, 0.006]]) {                       // 바깥(±Z)을 보게 놓는다
        const m = new THREE.Mesh(geo, mat);
        m.position.set(x, R, side * (SIDE_Z + dz));
        m.rotation.y = side > 0 ? 0 : Math.PI;
        this.group.add(m);
      }
      this.group.add(pivot);
      this.spinners.push(spin);
      if (x > 0) this.frontPivots.push(pivot);
    }
  }

  /** 흰 케이크 상자 + 금색 리본 + 손상도 게이지(상자 위) */
  _cargo() {
    const white = new THREE.MeshStandardMaterial({ color: 0xf4f1ea, roughness: 0.7, emissive: 0x2a2a2a });
    const gold = new THREE.MeshStandardMaterial({ color: 0xd9ad45, metalness: 0.6, roughness: 0.35, emissive: 0x3a2a08 });
    const t = this.cargoTilt = new THREE.Group();
    this.cargo = new THREE.Group();
    this.cargo.add(t);
    this.group.add(this.cargo);
    box(0.34, 0.24, 0.34, white, 0, 0.12, 0, t);
    box(0.35, 0.245, 0.045, gold, 0, 0.12, 0, t);
    box(0.045, 0.245, 0.35, gold, 0, 0.12, 0, t);
    box(0.12, 0.05, 0.05, gold, 0, 0.26, 0, t).rotation.y = 0.6;  // 리본 매듭
    box(0.12, 0.05, 0.05, gold, 0, 0.26, 0, t).rotation.y = -0.6;
    const gauge = new THREE.Group();                 // 상자를 따라 밀리지만 기울지는 않는 게이지
    gauge.position.y = 0.36;
    gauge.rotation.y = GAUGE_YAW;
    this.cargo.add(gauge);
    box(0.012, 0.045, 0.32, new THREE.MeshBasicMaterial({ color: 0x1b222c }), 0, 0, 0, gauge);
    this.gaugeMat = new THREE.MeshBasicMaterial({ color: COLORS.mint });
    this.gaugeFill = box(0.016, 0.03, 0.3, this.gaugeMat, -0.002, 0, -0.15, gauge);
    this.gaugeFill.geometry.translate(0, 0, 0.15);   // 왼쪽 끝 기준으로 늘어나게
    this.resetCargo();
  }

  /** 센터 디스플레이(가로로 긴 패널) — 캔버스 텍스처로 앱 화면을 축소해 그린다 */
  _display() {
    this.dispCanvas = document.createElement('canvas');
    this.dispCanvas.width = 512; this.dispCanvas.height = 160;
    this.dispTex = new THREE.CanvasTexture(this.dispCanvas);
    this.dispTex.colorSpace = THREE.SRGBColorSpace;
    const panel = new THREE.Mesh(new THREE.PlaneGeometry(0.56, 0.175), new THREE.MeshBasicMaterial({ map: this.dispTex, toneMapped: false }));
    panel.position.set(0.63, 1.075, 0);
    panel.rotation.y = -Math.PI / 2;   // 실내(-X) 쪽을 본다
    this.group.add(panel);
  }

  /** 디스플레이 내용 갱신: 배지, 현재 G, 한계, 말풍선 한 줄 */
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

  /** 차 자세·바퀴 회전·앞바퀴 조향·핸들·브레이크등 반영 */
  update(veh) {
    this.group.position.set(veh.x, ROAD_Y, -veh.y);
    this.group.rotation.y = veh.h;
    const delta = (veh.steer / STEER_RATIO) * DEG;
    for (const p of this.frontPivots) p.rotation.y = delta;
    for (const s of this.spinners) s.rotation.z = -veh.spin;
    this.wheelSpinner.rotation.x = -veh.steer * DEG;
    this.mats.tail.color.setHex(veh.aLon < BRAKE_LIGHT_ACCEL ? TAIL_ON : TAIL_OFF);
  }

  /** 관성으로 상자를 밀고 기울인다: 총 G > 0.30 일 때만, 최대 8 cm (마찰로 제자리 복귀는 없음) */
  updateCargo(latG, lonG, totalG, dt) {
    const over = totalG - CARGO.slideG;
    let tx = 0, tz = 0;
    if (over > 0) {
      const ix = -lonG / totalG, iz = latG / totalG;   // 가속 반대 방향(차 기준). 좌선회 → 오른쪽(+Z)
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

  /** 손상도 게이지 (0~1): 민트 → 샴페인 → 빨강 */
  setDamage(frac) {
    this.gaugeFill.scale.z = Math.max(0.001, Math.min(1, frac));
    this.gaugeMat.color.set(frac >= 1 ? COLORS.red : frac >= 0.5 ? COLORS.champ : COLORS.mint);
  }

  /** 상자를 제자리로 */
  resetCargo() {
    this.slide.set(0, 0);
    this.tilt.set(0, 0);
    this.updateCargo(0, 0, 0, 0);
  }
}
