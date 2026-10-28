/**
 * split.js — 동시 비교(좌 A · 우 B) 화면의 반쪽 HUD. 각 반쪽 아래에 계기(속도·G 바·최대 G·손상·랩타임)와
 * 센터 디스플레이 요약(배지·말풍선·로그 3줄)을 한 패널로 합쳐 보여 주고, 위에는 반쪽 이름표, 도착하면 가운데 도착 띠를 띄운다.
 * Hud 와 같은 say/log/quiet/tick/bubble 인터페이스를 가져 main 이 단독/비교를 가리지 않고 쓸 수 있다.
 */
import { APP } from './config.js';
import { fmtTime } from './hud.js';

const TELE_INTERVAL = 1 / 15, LOG_LINES = 3, BAR_SCALE = 2;
const BADGE_TEXT = { OK: 'OK · 여유', NEAR: 'NEAR · 한계 근접', OVER: 'OVER · 한계 초과' };

export class SideHud {
  /** root = #split, id = 'A'|'B', side = 'L'|'R' */
  constructor(root, id, side) {
    Object.assign(this, { id, side, teleTimer: 0, bubbleTimer: 0, bubble: '', lastBadge: '' });
    const el = this.el = document.createElement('section');
    el.className = `side panel side-${side}`; el.id = `side-${id}`;
    el.innerHTML = `
      <div class="s-head"><span class="s-label"></span><span class="badge ok s-badge">OK · 여유</span></div>
      <div class="s-main">
        <div class="speed"><span class="s-speed">0</span><small>km/h</small></div>
        <div class="s-g"><div class="gbar-top"><span class="at80">80 %</span></div>
          <div class="gbar"><div class="s-gfill"></div><i class="tick80"></i><i class="tick100"></i></div>
          <div class="gbar-legend"><span class="at0">0</span><span class="at100 s-limit">한계</span><span class="atEnd">×2</span></div></div>
      </div>
      <dl class="s-stats"><dt>총 G</dt><dd class="s-total">0.00</dd><dt>최대 G</dt><dd class="s-max">0.00</dd>
        <dt>손상도</dt><dd class="s-damage">0 %</dd><dt>랩타임</dt><dd class="s-lap">0:00.0</dd></dl>
      <div class="bubble s-bubble">조용히 지켜보는 중</div>
      <ol class="s-log"></ol>`;
    root.appendChild(el);
    this.tag = document.createElement('div'); this.tag.className = `side-tag side-${side}`; root.appendChild(this.tag);
    this.doneEl = document.createElement('div'); this.doneEl.className = `side-done side-${side} hidden`; root.appendChild(this.doneEl);
    this.q = (sel) => el.querySelector(sel);
  }

  /** 반쪽 이름표·라벨·한계 */
  setLabel(short, label, limit) {
    this.tag.innerHTML = short;
    this.q('.s-label').innerHTML = short; this.q('.s-label').title = label;
    this.q('.s-limit').textContent = `한계 ${limit.toFixed(2)} G`;
  }

  telemetry(d, dt) {
    if ((this.teleTimer -= dt) > 0) return;
    this.teleTimer = TELE_INTERVAL;
    const set = (sel, v) => { this.q(sel).textContent = v; };
    set('.s-speed', Math.round(d.v * 3.6)); set('.s-total', d.totalG.toFixed(2)); set('.s-max', d.maxG.toFixed(2));
    set('.s-damage', d.damage >= 100 ? '100 % 망가짐' : `${d.damage.toFixed(0)} %`);
    this.q('.s-damage').className = `s-damage ${d.damage >= 100 ? 'bad' : d.damage > 0 ? 'warn' : ''}`;
    set('.s-lap', fmtTime(d.lapTime));
    const fill = this.q('.s-gfill');
    fill.style.width = `${Math.min(100, (d.totalG / (d.limit * BAR_SCALE)) * 100)}%`;
    fill.className = `s-gfill ${d.badge.toLowerCase()}`;
    if (d.badge !== this.lastBadge) {
      this.lastBadge = d.badge;
      set('.s-badge', BADGE_TEXT[d.badge]);
      this.q('.s-badge').className = `badge s-badge ${d.badge.toLowerCase()}`;
    }
  }

  say(text, kind, sticky = false) {
    this.bubble = text; this.bubbleTimer = sticky ? Infinity : APP.bubbleSec;
    const b = this.q('.s-bubble'); b.textContent = text; b.className = `bubble s-bubble ${kind}`;
  }
  quiet() { this.say('조용히 지켜보는 중', ''); Object.assign(this, { bubble: '', bubbleTimer: 0 }); }
  log(text, t) {
    const ol = this.q('.s-log'), li = document.createElement('li'), time = document.createElement('time');
    time.textContent = fmtTime(t); li.append(time, text); ol.prepend(li);
    while (ol.children.length > LOG_LINES) ol.lastChild.remove();
  }
  clearLog() { this.q('.s-log').replaceChildren(); }
  tick(dt) {
    if (this.bubbleTimer === Infinity || this.bubbleTimer <= 0) return;
    if ((this.bubbleTimer -= dt) <= 0) this.quiet();
  }

  /** 도착 띠: 랩타임·손상도 (다른 쪽이 아직 달리는 동안 보인다) */
  done(r) {
    const ok = r.damage < 100;
    this.doneEl.innerHTML = `<b>도착</b> ${fmtTime(r.lapTime)} · <span class="${ok ? 'good' : 'bad'}">${r.cat.noun} ${ok ? '무사' : '손상 100 %'}</span> · 최대 ${r.maxG.toFixed(2)} G`;
    this.doneEl.classList.remove('hidden');
  }
  clearDone() { this.doneEl.classList.add('hidden'); }
}
