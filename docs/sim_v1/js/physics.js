/**
 * physics.js — 자전거 모델 차량과 G 추정기.
 * 식은 앱 core/physics/GEstimator.kt 와 같다:
 *   δ = δ_sw / 15,  a_lat = v²·tan(δ)/L,  a_lon = dv/dt,  총 G = √(a_lat² + a_lon²) / g,  EMA 0.5
 */
import { G, DEG, STEER_RATIO, WHEELBASE, EMA_ALPHA, CAR } from './config.js';

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

/** 핸들각(°)과 속도(m/s)로 횡가속도(m/s²)를 구한다. 양수 = 왼쪽 */
export function lateralAccel(v, steerDeg) {
  return (v * v * Math.tan((steerDeg / STEER_RATIO) * DEG)) / WHEELBASE;
}

/** 운동학적 자전거 모델 차량. steer 는 핸들각(°, 양수 = 왼쪽) */
export class Vehicle {
  constructor() { this.reset(0, 0, 0); }

  /** 위치·방향을 정하고 정지 상태로 되돌린다 */
  reset(x, y, h) {
    Object.assign(this, { x, y, h, v: 0, steer: 0, aLon: 0, spin: 0 });
  }

  /** 핸들을 목표각 쪽으로 초당 steerRate 이내로 돌린다 (목표 0 = 손 놓으면 복원) */
  steerToward(target, dt) {
    const t = clamp(target, -CAR.maxSteer, CAR.maxSteer), maxD = CAR.steerRate * dt;
    this.steer += clamp(t - this.steer, -maxD, maxD);
  }

  /** 종가속 명령(m/s²)을 적용하고 속도·방향·위치를 적분한다. 후진은 없다 */
  step(accel, dt) {
    const v0 = this.v;
    this.v = Math.max(0, v0 + accel * dt);
    this.aLon = (this.v - v0) / dt;
    const delta = (this.steer / STEER_RATIO) * DEG;
    this.h += ((this.v * Math.tan(delta)) / WHEELBASE) * dt;
    this.x += this.v * Math.cos(this.h) * dt;
    this.y += this.v * Math.sin(this.h) * dt;
    this.spin += (this.v * dt) / CAR.wheelRadius;
  }
}

/** 횡·종 가속도를 EMA로 부드럽게 해 G 단위로 내준다 */
export class GEstimator {
  constructor() { this.reset(); }

  /** 추정값 초기화 */
  reset() { this.lat = 0; this.lon = 0; this.total = 0; }

  /** 한 스텝 갱신. lat/lon 은 m/s², total 은 G */
  update(v, steerDeg, aLon) {
    this.lat = EMA_ALPHA * lateralAccel(v, steerDeg) + (1 - EMA_ALPHA) * this.lat;
    this.lon = EMA_ALPHA * aLon + (1 - EMA_ALPHA) * this.lon;
    this.total = Math.hypot(this.lat, this.lon) / G;
    return this;
  }

  get latG() { return this.lat / G; }
  get lonG() { return this.lon / G; }
}
