/**
 * hud.js — DOM HUD: 좌상단 조작 패널, 좌하단 계기, 우하단 센터 디스플레이(배지·말풍선·로그),
 * 결과 카드, 터치 버튼, WebAudio 비프. 앱 음성은 TTS 없이 말풍선 텍스트로 표현한다.
 */
import { CATEGORIES, APP, MODES } from './config.js';

const $ = (id) => document.getElementById(id);
const TELE_INTERVAL = 1 / 15;   // 계기 DOM 갱신 주기 (s)
const FPS_WINDOW_MS = 500;      // FPS 표시 갱신 주기
const LOG_LINES = 5;
const BAR_SCALE = 2;            // G 바 전체 = 한계 × 2 (80 % 눈금 40 %, 한계선 50 % 위치)
const BEEP = { hz: 880, gain: 0.12, sec: 0.15 };
const BADGE_TEXT = { OK: 'OK · 여유', NEAR: 'NEAR · 한계 근접', OVER: 'OVER · 한계 초과' };
const B_RULE = `한계의 ${Math.round(MODES.B.latFactor * 100)} % 이내`;

/** 랩타임 표기 m:ss.s */
export const fmtTime = (t) => {
  const m = Math.floor(t / 60);
  return `${m}:${(t - m * 60).toFixed(1).padStart(4, '0')}`;
};
const fmtKmh = (v) => (v === null ? '—' : `${v.toFixed(0)} km/h`);
const fmtNO = (r) => `NEAR ${r.nearCount}회 · OVER ${r.overCount}회`;

/** 모드 이름 (B는 분류 이름과 규칙을 함께 보여 준다) */
export const modeLabel = (key, cat) => ({ A: 'A. 자동 주행 · 사람 기준', B: `B. 자동 주행 · ${cat.noun} 기준 · ${B_RULE}`, C: 'C. 직접 운전' })[key];

export class Hud {
  constructor(h) {
    Object.assign(this, { h, teleTimer: 0, bubbleTimer: 0, bubble: '', audio: null, lastBadge: '', cardAction: null, fpsN: 0, fpsT0: performance.now() });
    const sel = $('category');
    CATEGORIES.forEach((c) => sel.add(new Option(`${c.label} (${c.limit.toFixed(2)} G)`, c.id)));
    sel.addEventListener('change', () => { sel.blur(); h.onCategory(sel.value); });
    // 버튼 클릭 후 포커스를 풀어 Space(급제동)가 버튼을 다시 누르지 않게 한다
    const click = (el, fn) => el.addEventListener('click', (e) => { e.currentTarget.blur(); fn(); });
    const groups = { mode: (v) => h.onMode(v), cam: (v) => h.onCam(+v), speed: (v) => h.onSpeed(+v) };
    for (const [attr, fn] of Object.entries(groups)) document.querySelectorAll(`[data-${attr}]`).forEach((b) => click(b, () => fn(b.dataset[attr])));
    click($('btn-compare'), () => h.onCompare());
    click($('btn-reset'), () => h.onReset());
    click($('card-action'), () => { const fn = this.cardAction; this.hideCard(); fn?.(); });
    click($('card-close'), () => this.closeCard());
    // 터치 버튼: 누르는 동안 키 입력과 같게
    document.querySelectorAll('#touch [data-key]').forEach((b) => {
      const set = (v) => (e) => { e.preventDefault(); h.keys[b.dataset.key] = v; };
      b.addEventListener('pointerdown', set(true));
      ['pointerup', 'pointerleave', 'pointercancel'].forEach((ev) => b.addEventListener(ev, set(false)));
    });
    // 오디오는 첫 사용자 입력 후에만 연다 (자동재생 정책)
    const unlock = () => { try { this.audio ??= new AudioContext(); this.audio.resume(); } catch { /* 오디오 미지원 */ } };
    addEventListener('pointerdown', unlock, { once: true });
    addEventListener('keydown', unlock, { once: true });
  }

  /** 모드 버튼 강조·라벨·조작 안내·터치 버튼 */
  setMode(key, cat) {
    this.setActive('mode', key);
    $('mode-b-sub').textContent = `${cat.noun} 기준 · ${B_RULE}`;
    $('t-mode').textContent = modeLabel(key, cat);
    $('hint').textContent = key === 'C'
      ? '↑ 가속 · ↓ 제동 · ←/→ 핸들 · Space 급제동 · R 리셋 · 주차장에 멈추면 P'
      : '자동 주행 중 · 1/2/3 카메라 · R 리셋 · H 패널 숨김 · F FPS';
    $('touch').classList.toggle('hidden', !(key === 'C' && matchMedia('(pointer: coarse)').matches));
  }

  /** 좌우 동시 비교 켜기/끄기: body.split 로 반쪽 HUD 표시, 모드 버튼 대신 비교 버튼 강조 */
  setSplit(on) {
    document.body.classList.toggle('split', on);
    $('btn-compare').classList.toggle('on', on);
    if (on) { this.setActive('mode', '-'); $('hint').textContent = '동시 비교 중 · 왼쪽 A 사람 기준 · 오른쪽 B 분류 기준 · 1/2/3 카메라 · R 다시 비교 · H 패널 숨김'; }
  }

  /** 분류 표시 갱신 */
  setCategory(cat) {
    $('category').value = cat.id;
    $('d-cat').textContent = `${cat.label} · 한계 ${cat.limit.toFixed(2)} G`;
    $('t-limit').textContent = `한계 ${cat.limit.toFixed(2)} G`;
  }

  /** 버튼 그룹(data-attr)에서 선택 표시 */
  setActive(attr, value) {
    document.querySelectorAll(`[data-${attr}]`).forEach((b) => b.classList.toggle('on', b.dataset[attr] === String(value)));
  }

  /** 좌하단 계기와 배지 (15 Hz 로 제한) */
  telemetry(d, dt) {
    if ((this.teleTimer -= dt) > 0) return;
    this.teleTimer = TELE_INTERVAL;
    const set = (id, v) => { $(id).textContent = v; };
    set('t-speed', Math.round(d.v * 3.6)); set('t-steer', `${Math.round(d.steer)}°`);
    set('t-lat', d.latG.toFixed(2)); set('t-lon', d.lonG.toFixed(2)); set('t-total', d.totalG.toFixed(2));
    set('t-max', d.maxG.toFixed(2));
    set('t-damage', d.damage >= 100 ? '100 % 망가짐' : `${d.damage.toFixed(0)} %`);
    $('t-damage').className = d.damage >= 100 ? 'bad' : d.damage > 0 ? 'warn' : '';
    set('t-coach', `${d.coach}/${APP.coachMax}`); set('t-lap', fmtTime(d.lapTime)); set('t-gear', d.gear);
    $('t-gfill').style.width = `${Math.min(100, (d.totalG / (d.limit * BAR_SCALE)) * 100)}%`;
    $('t-gfill').className = d.badge.toLowerCase();
    if (d.badge !== this.lastBadge) {
      this.lastBadge = d.badge;
      set('d-badge', BADGE_TEXT[d.badge]);
      $('d-badge').className = `badge ${d.badge.toLowerCase()}`;
    }
  }

  /** 디스플레이 말풍선 (kind: coach | notice | arrive). sticky 면 사라지지 않는다 */
  say(text, kind, sticky = false) {
    this.bubble = text;
    this.bubbleTimer = sticky ? Infinity : APP.bubbleSec;
    $('d-bubble').textContent = text;
    $('d-bubble').className = `bubble ${kind}`;
  }

  /** 말풍선 비우기 */
  quiet() {
    this.say('조용히 지켜보는 중', '');
    Object.assign(this, { bubble: '', bubbleTimer: 0 });
  }

  /** 이벤트 로그 (최신이 위, 5줄) */
  log(text, t) {
    const ol = $('d-log'), li = document.createElement('li'), time = document.createElement('time');
    time.textContent = fmtTime(t);
    li.append(time, text);
    ol.prepend(li);
    while (ol.children.length > LOG_LINES) ol.lastChild.remove();
  }

  /** 로그 비우기 */
  clearLog() { $('d-log').replaceChildren(); }

  /** NEAR 진입 효과음: 짧은 비프 1회 (오디오가 열려 있을 때만) */
  beep() {
    const ac = this.audio;
    if (!ac || ac.state !== 'running') return;
    const o = ac.createOscillator(), g = ac.createGain(), t = ac.currentTime;
    o.frequency.value = BEEP.hz;
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(BEEP.gain, t + 0.01);
    g.gain.exponentialRampToValueAtTime(0.0001, t + BEEP.sec);
    o.connect(g).connect(ac.destination);
    o.start(t); o.stop(t + BEEP.sec + 0.02);
  }

  /** 말풍선 시간 경과 처리 (실시간 dt) */
  tick(dt) {
    if (this.bubbleTimer === Infinity || this.bubbleTimer <= 0) return;
    if ((this.bubbleTimer -= dt) <= 0) this.quiet();
  }

  /** 가운데 카드 표시. action = { label, fn } */
  showCard(title, html, action = null, closeLabel = '닫기') {
    $('card-title').textContent = title;
    $('card-body').innerHTML = html;
    $('card-close').textContent = closeLabel;
    $('card-action').classList.toggle('hidden', !action);
    $('card-action').textContent = action?.label ?? '';
    this.cardAction = action?.fn;
    $('card').classList.remove('hidden');
  }

  /** 카드 숨기기 */
  hideCard() { $('card').classList.add('hidden'); }

  /** 카드 닫기(닫기 버튼·Esc): 숨기고, 아직 주행 전이면 main 이 A 주행을 시작한다 */
  closeCard() {
    if ($('card').classList.contains('hidden')) return;
    this.hideCard();
    this.h.onCardClose();
  }

  /** 요소 보이기/숨기기 토글 (H: 조작 패널, F: FPS) */
  toggle(id) { $(id).classList.toggle('hidden'); }

  /** 프레임마다 호출: 0.5초 창으로 FPS 계산해 표시 */
  fps() {
    const now = performance.now(), n = ++this.fpsN;
    if (now - this.fpsT0 < FPS_WINDOW_MS) return;
    $('fps').textContent = `${Math.round((n * 1000) / (now - this.fpsT0))} fps`;
    Object.assign(this, { fpsN: 0, fpsT0: now });
  }

  /** 첫 화면 안내 (Esc 또는 닫기 = A부터 둘러보기) */
  showIntro(onCompare) {
    this.showCard('같은 코스, 두 가지 운전', `
      <p>사람에게 편한 <b>0.40 G</b>로 달리면 조수석 케이크가 망가지고, 케이크 한계 <b>0.15 G</b>의 75 % 이내로 달리면 무사합니다.
      「VIP 조수석」 앱의 추정식으로 같은 코스를 두 번 달려 비교합니다.</p>
      <ul><li><b>비교 시작</b>: 왼쪽 A(사람 기준)·오른쪽 B(케이크 기준)가 같은 코스를 <b>동시에</b> 달리고, 둘 다 도착하면 비교 카드</li>
      <li>카메라: <b>1</b> 체이스 · <b>2</b> 캐빈(상자가 밀리는 모습) · <b>3</b> 지도(G 벡터)</li>
      <li><b>C 직접 운전</b>: 방향키 · Space 급제동 · R 리셋 · <b>H</b> 패널 숨김 · <b>F</b> FPS</li></ul>`,
    { label: '비교 시작 (좌 A · 우 B)', fn: onCompare }, 'A부터 둘러보기 (Esc)');
  }

  /** 한 주행 리포트 */
  showReport(r, label, onRetry) {
    const ok = r.damage < 100, tr = (k, v) => `<tr><th>${k}</th><td>${v}</td></tr>`;
    this.showCard(`${label} — 주행 리포트`, `
      <p class="${ok ? 'good' : 'bad'}">${r.cat.noun} ${ok ? '무사 도착' : '손상 100 %'}</p>
      <table>${tr('최대 G', `${r.maxG.toFixed(2)} G`)}${tr('손상도', `${r.damage.toFixed(0)} %`)}${tr('코칭', `${r.coach}/${APP.coachMax}회`)}
      ${tr('급커브 R30 통과 속도', fmtKmh(r.r30Kmh))}${tr('NEAR/OVER 진입 횟수', fmtNO(r))}${tr('랩타임', fmtTime(r.lapTime))}</table>
      <p class="sub">A와 B를 모두 달리면 비교 카드가 나와요.</p>`, { label: '다시 달리기', fn: onRetry });
  }

  /** A vs B 비교 카드 (R30 통과 속도·NEAR/OVER 횟수는 주행 중 측정값) */
  showCompare(a, b, onRetry) {
    const dt = b.lapTime - a.lapTime, noun = b.cat.noun, abs = Math.abs(dt).toFixed(1);
    const row = (k, x, y) => `<tr><th>${k}</th><td>${x}</td><td>${y}</td></tr>`;
    this.showCard(`비교 — ${b.cat.label} (한계 ${b.cat.limit.toFixed(2)} G)`, `
      <p class="headline">${noun} 기준은 <b>${abs}초 ${dt >= 0 ? '더 걸렸지만' : '더 빨랐고'}</b>, 손상도는 <b>${a.damage.toFixed(0)} % → ${b.damage.toFixed(0)} %</b></p>
      <table class="cmp"><tr><th></th><th>A 사람 기준</th><th>B ${noun} 기준</th></tr>
      ${row('최대 G', `${a.maxG.toFixed(2)} G`, `${b.maxG.toFixed(2)} G`)}${row('손상도', `${a.damage.toFixed(0)} %`, `${b.damage.toFixed(0)} %`)}
      ${row('급커브 R30 통과 속도', fmtKmh(a.r30Kmh), fmtKmh(b.r30Kmh))}
      ${row('NEAR/OVER 진입 횟수', fmtNO(a), fmtNO(b))}
      ${row('코칭', `${a.coach}/${APP.coachMax}회`, `${b.coach}/${APP.coachMax}회`)}
      ${row('랩타임', fmtTime(a.lapTime), `${fmtTime(b.lapTime)} (${dt >= 0 ? '+' : '−'}${abs}초)`)}</table>
      <p class="sub">R30 통과 속도는 원호 길이 ÷ 통과 시간(측정값). 손상 모델은 한계 초과분 누적의 단순화입니다.</p>`, { label: '다시 비교', fn: onRetry });
  }
}
