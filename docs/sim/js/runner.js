/**
 * runner.js — 한 대의 주행 인스턴스: 차량 물리·G 추정·자동 운전자·앱 모니터·3D 차 모델·추적 카메라.
 * 단독 주행(A/B/C)에서는 1개, 동시 비교에서는 A·B 2개가 같은 월드에서 같은 dt 로 함께 돈다(물리는 고정 스텝이라 결과는 단독 주행과 같다).
 * 레이어: 차·전조등은 자기 레이어(1 = A/단독, 2 = B)에만 그려져, 두 차가 같은 자리에서 출발해도 서로의 화면에 겹쳐 보이지 않는다.
 */
import * as THREE from 'three';
import { MODES, TRACK } from './config.js';
import { Vehicle, GEstimator } from './physics.js';
import { AutoDriver } from './driver.js';
import { AppMonitor } from './monitor.js';
import { CarModel, CABIN_VIEW } from './car.js';

export const CHASE = { back: 12.5, up: 5.5, look: 30, lift: 0, follow: 2.5, fov: 55 };
const PARK_HOLD = 1.0, PARK_MIN_LAP = 0.8, STOP_V = 0.05;

export class Runner {
  constructor({ id, track, lib, scene, layer }) {
    Object.assign(this, { id, track, layer, mode: 'A', cat: null, gear: 'D', sLap: 0, sHint: 0, parkHold: 0, arrived: false, result: null, snap: true });
    this.veh = new Vehicle(); this.gEst = new GEstimator(); this.driver = new AutoDriver(track); this.monitor = new AppMonitor(track);
    this.car = new CarModel(lib); this.car.setLayer(layer); scene.add(this.car.group);
    this.chaseCam = new THREE.PerspectiveCamera(CHASE.fov, 1, 0.3, 4000);
    this.cabinCam = new THREE.PerspectiveCamera(CABIN_VIEW.fov, 1, 0.05, 4000);
    for (const c of [this.chaseCam, this.cabinCam]) c.layers.enable(layer);
    this.chaseLook = new THREE.Vector3();
  }

  get position() { return this.car.group.position; }
  setVisible(on) { this.car.group.visible = on; }
  setAspect(a) { for (const c of [this.chaseCam, this.cabinCam]) { c.aspect = a; c.updateProjectionMatrix(); } }

  /** 출발점에 세우고 모든 상태 초기화 (모드·분류 지정) */
  reset(mode, cat) {
    const p = this.track.sampleAt(0);
    Object.assign(this, { mode, cat, gear: 'D', sLap: 0, sHint: 0, parkHold: 0, arrived: false, result: null, snap: true });
    this.veh.reset(p.x, p.y, p.h); this.gEst.reset(); this.monitor.reset(cat); this.driver.configure(mode, cat.limit);
    this.car.resetCargo(); this.car.update(this.veh);
  }

  inParkZone() {
    const t = this.track;
    return this.sLap > t.length * PARK_MIN_LAP && this.veh.v < STOP_V && Math.abs(t.wrap(this.sLap) - t.parkS) < TRACK.parkZone;
  }

  /** 고정 스텝 1회. 이번 스텝의 앱 이벤트 배열을 돌려준다 (도착하면 kind:'arrive' 포함) */
  step(dt, manual) {
    if (this.gear === 'P') return [];
    const { veh, gEst, track } = this;
    const ctl = MODES[this.mode].auto ? this.driver.control(veh, this.sLap, gEst.lat) : manual();
    veh.steerToward(ctl.steer, dt); veh.step(ctl.accel, dt);
    gEst.update(veh.v, veh.steer, veh.aLon);
    const near = track.nearest(veh.x, veh.y, this.sHint), L = track.length;
    this.sLap += ((near.s - this.sHint + 1.5 * L) % L) - L / 2;
    this.sHint = near.s;
    const events = this.monitor.update({ dt, latG: gEst.latG, lonG: gEst.lonG, totalG: gEst.total, v: veh.v, steer: veh.steer, sLap: this.sLap });
    this.car.updateCargo(gEst.latG, gEst.lonG, gEst.total, dt);
    this.parkHold = this.inParkZone() ? this.parkHold + dt : 0;
    if (this.parkHold > (MODES[this.mode].auto ? 0 : PARK_HOLD)) events.push({ kind: 'arrive', result: this.arrive() });
    return events;
  }

  /** 주차(P) 처리 → 주행 리포트 */
  arrive() {
    this.gear = 'P'; this.gEst.reset(); this.arrived = true;
    this.result = this.monitor.arrive(this.mode);
    return this.result;
  }

  /** 프레임마다: 차 모델·손상 게이지·체이스/캐빈 카메라. dbgView = QA 용 차 로컬 고정 카메라 */
  update(dt, speed, dbgView = null) {
    this.car.update(this.veh); this.car.setDamage(this.monitor.damage / 100);
    const p = this.position, h = this.veh.h, fx = Math.cos(h), fz = -Math.sin(h);
    const want = new THREE.Vector3(p.x - fx * CHASE.back, p.y + CHASE.up, p.z - fz * CHASE.back);
    const look = new THREE.Vector3(p.x + fx * CHASE.look, p.y + CHASE.lift, p.z + fz * CHASE.look);
    const k = Math.min(1, dt * CHASE.follow * speed);
    if (this.snap) { this.chaseCam.position.copy(want); this.chaseLook.copy(look); this.snap = false; }
    else { this.chaseCam.position.lerp(want, k); this.chaseLook.lerp(look, Math.min(1, 2 * k)); }
    this.chaseCam.lookAt(this.chaseLook);
    this.car.group.updateMatrixWorld();
    const m = this.car.group.matrixWorld;
    if (dbgView) { this.chaseCam.position.fromArray(dbgView.pos).applyMatrix4(m); this.chaseCam.lookAt(new THREE.Vector3().fromArray(dbgView.look).applyMatrix4(m)); }
    const cv = this.car.cabinView ?? CABIN_VIEW;                 // 차체마다 실측한 운전자 눈 위치
    this.cabinCam.position.copy(cv.pos).applyMatrix4(m);
    this.cabinCam.lookAt(cv.look.clone().applyMatrix4(m));
  }
}
