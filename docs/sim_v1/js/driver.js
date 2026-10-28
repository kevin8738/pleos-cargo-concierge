/**
 * driver.js — 자동 운전자 (모드 A·B).
 * 속도: 커브 반지름에서 목표 횡 G로 정한 속도 프로파일을 제동 한계로 거꾸로 이어 붙여 따라간다.
 * 조향: 순수추종(pure pursuit). 결과 핸들각은 Vehicle 이 초당 180° 이내로 따라간다.
 */
import { G, DEG, STEER_RATIO, WHEELBASE, MODES, DRIVER } from './config.js';

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

export class AutoDriver {
  constructor(track) {
    Object.assign(this, { track, params: null, profile: null });
  }

  /** 모드와 분류 한계 G로 주행 파라미터·속도 프로파일을 만든다 (직접 운전이면 비활성) */
  configure(modeKey, limitG) {
    const m = MODES[modeKey];
    if (!m.auto) { this.params = null; return; }
    if (modeKey === 'A') {
      this.params = { latG: m.latG, accel: m.accel, brake: m.brake, budget: Infinity, vmax: m.vmax };
    } else {
      // 케이크 기준: 코너 횡 G = 한계의 75 %, 가감속 ≤ 1.0 m/s²(한계의 70 % 이하), 합성 G 예산 = 한계의 78 %
      const a = Math.min(m.accelMax, m.accelFactor * limitG * G);
      this.params = { latG: m.latFactor * limitG, accel: a, brake: a, budget: m.budgetFactor * limitG * G, vmax: m.vmax };
    }
    this.profile = this._buildProfile(this.params);
  }

  /** 거리별 최고 속도: 직선 vmax, 커브 √(latG·g·R), 주차 지점 0 — 뒤에서부터 제동 한계(85 %)로 연결 */
  _buildProfile(p) {
    const t = this.track, n = Math.ceil(t.length / t.step) + 1, b = p.brake * DRIVER.profileBrake;
    const v = new Float64Array(n).fill(p.vmax);
    for (const arc of t.arcs) {
      const vc = Math.sqrt(p.latG * G * arc.R);
      const i0 = Math.max(0, Math.floor((arc.s0 - DRIVER.cornerMargin) / t.step));
      const i1 = Math.min(n - 1, Math.ceil(arc.s1 / t.step));
      for (let i = i0; i <= i1; i++) v[i] = Math.min(v[i], vc);
    }
    for (let i = Math.round(t.parkS / t.step); i < n; i++) v[i] = 0;
    for (let i = n - 2; i >= 0; i--) v[i] = Math.min(v[i], Math.sqrt(v[i + 1] ** 2 + 2 * b * t.step));
    return v;
  }

  /** 랩 거리 s 의 프로파일 속도 */
  _profileAt(s) {
    const i = clamp(Math.round(s / this.track.step), 0, this.profile.length - 1);
    return this.profile[i];
  }

  /** 핸들 목표각(°)과 종가속 명령(m/s²)을 정한다. aLat = 현재 추정 횡가속도(m/s²) */
  control(veh, sLap, aLat) {
    const p = this.params, t = this.track;
    // 순수추종: 전방주시점을 차 좌표로 옮겨 곡률 2·y/d² 를 만든다
    const ld = clamp(DRIVER.ldBase + DRIVER.ldGain * veh.v, DRIVER.ldMin, DRIVER.ldMax);
    const tgt = t.sampleAt(sLap + ld);
    const dx = tgt.x - veh.x, dy = tgt.y - veh.y;
    const lx = Math.cos(veh.h) * dx + Math.sin(veh.h) * dy;
    const ly = -Math.sin(veh.h) * dx + Math.cos(veh.h) * dy;
    const delta = Math.atan((2 * WHEELBASE * ly) / Math.max(1e-6, lx * lx + ly * ly));
    const steer = (delta / DEG) * STEER_RATIO;

    // 속도: 프로파일을 조금 앞서 보고 P 제어, 주차 지점 직전에는 서행 후 정지
    let vt = this._profileAt(sLap + veh.v * DRIVER.previewSec);
    const toPark = t.parkS - sLap;
    if (toPark > DRIVER.creepFrom) vt = Math.max(vt, Math.min(DRIVER.creep, toPark));
    let accel = clamp(DRIVER.kp * (vt - veh.v), -p.brake, p.accel);
    if (toPark <= DRIVER.stopAt) accel = -p.brake;
    // 합성 G 예산(마찰원): 선회 중에는 가감속을 남은 만큼만 쓴다 (최소 제동력은 남긴다)
    if (p.budget < Infinity) {
      const lim = Math.sqrt(Math.max(0, p.budget ** 2 - aLat ** 2));
      accel = clamp(accel, -Math.max(lim, DRIVER.minBrake), lim);
    }
    return { steer, accel };
  }
}
