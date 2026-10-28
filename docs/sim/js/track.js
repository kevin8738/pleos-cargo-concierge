/**
 * track.js — 닫힌 루프 코스 기하. 수학 좌표 (x, y), heading h (rad, +x 기준 반시계).
 * 3D 장면에서는 (x, y) → (x, 0, -y) 로 옮긴다.
 */
import { TRACK, DEG } from './config.js';

const TURN = 120 * DEG; // 각 모서리 회전각 (정삼각형 외각)
const NEAR_WINDOW = 30; // 최근접 탐색 창 (m)
const LOST_DIST = 20;   // 이보다 멀면 경로를 놓친 것으로 보고 전체 탐색 (m)

/** 반지름 R 원호가 삼각형 변에서 잘라 먹는 길이 */
const trim = (R) => R * Math.tan(TURN / 2);

/** 출발점(베이커리)부터 순서대로 직선/원호 세그먼트 목록을 만든다 */
function buildSegments() {
  const { side, startOffset, corners } = TRACK;
  const n = corners.length;
  const segs = [{ type: 'line', len: side - trim(corners[n - 1].R) - trim(corners[0].R) - startOffset }];
  corners.forEach((c, i) => {
    segs.push({ type: 'arc', R: c.R, len: c.R * TURN, sharp: !!c.sharp });
    if (i < n - 1) segs.push({ type: 'line', len: side - trim(c.R) - trim(corners[i + 1].R) });
  });
  segs.push({ type: 'line', len: startOffset });
  return segs;
}

/** 시작 자세 (x, y, h)에서 세그먼트를 따라 u 만큼 진행한 자세 */
function advance(seg, x, y, h, u) {
  if (seg.type === 'line') return [x + u * Math.cos(h), y + u * Math.sin(h), h];
  const R = seg.R, cx = x - R * Math.sin(h), cy = y + R * Math.cos(h), a = h + u / R; // 좌회전 원호
  return [cx + R * Math.sin(a), cy - R * Math.cos(a), a];
}

export class Track {
  constructor() {
    this.segments = buildSegments();
    this.length = this.segments.reduce((sum, s) => sum + s.len, 0);
    this.arcs = [];
    this._sample();
    this.parkS = this.length - TRACK.parkBeforeEnd;
    this.sharp = this.arcs.find((a) => a.sharp);
  }

  /** 일정 간격 샘플(x, y, heading)을 만들고 코스 중심을 원점으로 옮긴다 */
  _sample() {
    const n = this.n = Math.round(this.length / TRACK.sampleStep);
    this.step = this.length / n;
    const starts = [];
    let x = 0, y = 0, h = 0, s0 = 0;
    for (const seg of this.segments) {
      starts.push({ x, y, h, s0 });
      if (seg.type === 'arc') this.arcs.push({ R: seg.R, s0, s1: s0 + seg.len, sharp: seg.sharp });
      [x, y, h] = advance(seg, x, y, h, seg.len);
      s0 += seg.len;
    }
    this.closureError = Math.hypot(x, y); // 검증용: 0 에 가까워야 한다
    this.xs = new Float64Array(n); this.ys = new Float64Array(n); this.hs = new Float64Array(n);
    for (let i = 0, k = 0; i < n; i++) {
      const s = i * this.step;
      while (k < starts.length - 1 && s >= starts[k + 1].s0) k++;
      const st = starts[k];
      [this.xs[i], this.ys[i], this.hs[i]] = advance(this.segments[k], st.x, st.y, st.h, s - st.s0);
    }
    const span = (a) => [Math.min(...a), Math.max(...a)];
    const [minX, maxX] = span(this.xs), [minY, maxY] = span(this.ys), cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
    for (let i = 0; i < n; i++) { this.xs[i] -= cx; this.ys[i] -= cy; }
    this.bounds = { minX: minX - cx, maxX: maxX - cx, minY: minY - cy, maxY: maxY - cy };
  }

  /** 거리 s를 [0, L) 로 감싼다 */
  wrap(s) { const L = this.length; return ((s % L) + L) % L; }

  /** 거리 s 지점의 자세 (선형 보간) */
  sampleAt(s) {
    const u = this.wrap(s) / this.step, i = Math.floor(u) % this.n, j = (i + 1) % this.n, f = u - Math.floor(u);
    const dh = this.hs[j] - this.hs[i];
    return {
      x: this.xs[i] + (this.xs[j] - this.xs[i]) * f,
      y: this.ys[i] + (this.ys[j] - this.ys[i]) * f,
      h: this.hs[i] + Math.atan2(Math.sin(dh), Math.cos(dh)) * f,
    };
  }

  /** 경로 위 점 s에서 왼쪽으로 lateral(m) 떨어진 점 */
  offsetAt(s, lateral) {
    const p = this.sampleAt(s);
    return { x: p.x - Math.sin(p.h) * lateral, y: p.y + Math.cos(p.h) * lateral, h: p.h };
  }

  /** (x, y)에 가장 가까운 경로 거리. hint 주변 ±NEAR_WINDOW 를 찾고, 경로를 놓쳤으면 전체 탐색 */
  nearest(x, y, hintS) {
    const n = this.n, c = Math.round(this.wrap(hintS) / this.step), w = Math.ceil(NEAR_WINDOW / this.step);
    let best = 0, bd = Infinity;
    const test = (i) => {
      const d = (this.xs[i] - x) ** 2 + (this.ys[i] - y) ** 2;
      if (d < bd) { bd = d; best = i; }
    };
    for (let j = -w; j <= w; j++) test((((c + j) % n) + n) % n);
    if (bd > LOST_DIST ** 2) for (let i = 0; i < n; i++) test(i);
    return { s: best * this.step, dist: Math.sqrt(bd) };
  }
}
