/**
 * monitor.js — 앱 상태머신 미러. 배지(OK/NEAR/OVER), 음성 코칭 규칙, 급커브 사전 안내(F4),
 * 도착 리포트, 그리고 시뮬레이션 전용 케이크 손상 모델을 담당한다. DOM·Three.js 에 의존하지 않는다.
 */
import { G, APP, CARGO, CATEGORIES } from './config.js';

const MOVING_V = 0.1; // 이 속도를 넘으면 출발로 보고 랩타임 시작 (m/s)

export class AppMonitor {
  constructor(track) {
    this.track = track;
    this.reset(CATEGORIES[0]);
  }

  /** 새 주행 시작: 분류를 정하고 모든 상태를 초기화 */
  reset(cat) {
    Object.assign(this, {
      cat, badge: 'OK', coachCount: 0, maxG: 0, damage: 0, broken: false, noticeDone: false, lapTime: 0, started: false,
      arrived: false, nearArmed: true, okTime: 0, overHandled: false, urgentLogged: false, capLogged: false, lastCoach: -1e9,
      nearCount: 0, overCount: 0, r30In: null, r30Out: null,
    });
  }

  get limit() { return this.cat.limit; }

  /** F4 권장 속도 = √(limitG·g·R)·3.6 km/h, 안내 문장은 5 km/h 단위 반올림 */
  recommendKmh(R) {
    const exact = Math.sqrt(this.limit * G * R) * 3.6;
    return { exact, rounded: Math.round(exact / APP.noticeRoundKmh) * APP.noticeRoundKmh };
  }

  /** 물리 스텝마다 호출. 상태를 갱신하고 이번 스텝의 이벤트 배열을 돌려준다 */
  update({ dt, latG, lonG, totalG, v, steer, sLap }) {
    const ev = [];
    if (this.arrived) return ev;
    if (v > MOVING_V) this.started = true;
    if (this.started) this.lapTime += dt;
    this.maxG = Math.max(this.maxG, totalG);
    this._damage(totalG, dt, ev);
    this._badge(totalG, dt, ev);
    this._coach(latG, lonG, totalG, v, steer, ev);
    this._notice(sLap, ev);
    const arc = this.track.sharp;                       // 급커브 R30 진입·이탈 시각 (통과 평균 속도용)
    if (sLap >= arc.s0 && this.r30In === null) this.r30In = this.lapTime;
    if (sLap >= arc.s1 && this.r30Out === null) this.r30Out = this.lapTime;
    return ev;
  }

  /** 급커브 R30 평균 통과 속도 (km/h) = 원호 길이 / 통과 시간. 아직 못 지났으면 null */
  get r30Kmh() {
    const arc = this.track.sharp;
    return this.r30Out === null ? null : ((arc.s1 - arc.s0) / Math.max(1e-6, this.r30Out - this.r30In)) * 3.6;
  }

  /** 손상 모델(단순화): 한계 초과분 × dt × 400 % 누적 */
  _damage(g, dt, ev) {
    this.damage = Math.min(100, this.damage + Math.max(0, g - this.limit) * dt * CARGO.damageRate);
    if (this.damage >= 100 && !this.broken) {
      this.broken = true;
      ev.push({ kind: 'broken', text: this.cat.id === 'cake' ? '케이크 망가짐' : `${this.cat.noun} 손상 100 %` });
    }
  }

  /** 배지 판정. 80 % 이상 진입 순간 효과음 1회 (OK 1초 유지 후 재무장) */
  _badge(g, dt, ev) {
    const r = g / this.limit;
    const next = r > APP.overRatio ? 'OVER' : r >= APP.nearRatio ? 'NEAR' : 'OK';
    if (next === 'OK') {
      if ((this.okTime += dt) >= APP.nearRearmSec) this.nearArmed = true;
    } else {
      this.okTime = 0;
      if (this.nearArmed) { this.nearCount++; ev.push({ kind: 'near', text: `한계의 ${Math.round(r * 100)} %` }); }
      this.nearArmed = false;
    }
    if (next === 'OVER' && this.badge !== 'OVER') this.overCount++;
    if (next !== 'OVER') { this.overHandled = false; this.urgentLogged = false; }
    this.badge = next;
  }

  /** OVER 구간마다 최대 1회 음성 코칭. 상한 3회, 긴급(≥0.6 G)·주차 선회 중엔 말하지 않음 */
  _coach(latG, lonG, g, v, steer, ev) {
    if (this.badge !== 'OVER' || this.overHandled) return;
    if (this.coachCount >= APP.coachMax) {
      this.overHandled = true;
      if (!this.capLogged) { this.capLogged = true; ev.push({ kind: 'info', text: '코칭 3회 상한 — 이번 주행은 더 말하지 않아요' }); }
      return;
    }
    if (g >= APP.urgentG) {
      if (!this.urgentLogged) { this.urgentLogged = true; ev.push({ kind: 'info', text: '긴급 조작(0.6 G 이상) — 코칭 보류' }); }
      return;
    }
    if (v * 3.6 < APP.parkingKmh && Math.abs(steer) >= APP.parkingSteerDeg) return;
    if (this.lapTime - this.lastCoach < APP.coachGapSec) return;
    Object.assign(this, { overHandled: true, coachCount: this.coachCount + 1, lastCoach: this.lapTime });
    const text = Math.abs(latG) >= Math.abs(lonG) ? '핸들이 커져요. 조금만 부드럽게'
      : lonG > 0 ? '가속이 커요. 조금만 부드럽게' : '제동이 커요. 조금만 부드럽게';
    ev.push({ kind: 'coach', text });
  }

  /** 급커브(R30) 100 m 전 1회 사전 안내 */
  _notice(sLap, ev) {
    const arc = this.track.sharp;
    if (this.noticeDone || sLap < arc.s0 - APP.noticeDist || sLap > arc.s0) return;
    this.noticeDone = true;
    const r = this.recommendKmh(arc.R);
    ev.push({ kind: 'notice', text: `이번 코너는 ${r.rounded} km/h 이하로`, detail: `R${arc.R} 커브 100 m 전, 권장 ${r.exact.toFixed(1)} km/h` });
  }

  /** 주차장 정지(P) 시 도착 처리(정지 상태라 배지는 OK), 주행 리포트 반환 */
  arrive(mode) {
    this.arrived = true;
    this.badge = 'OK';
    return {
      mode, cat: this.cat, maxG: this.maxG, coach: this.coachCount, damage: this.damage, lapTime: this.lapTime,
      r30Kmh: this.r30Kmh, nearCount: this.nearCount, overCount: this.overCount,
    };
  }
}
