#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""compose_demo.py — emulator take (webm + timeline + logcat) → polished 1080p demo video.

What it adds on top of the raw screen recording (all data-driven from the take itself):
  • left "control room" panel: brand, chapter list with progress, live injected-signal HUD (timeline log),
    G gauge fed by the app's own estimate (logcat `CargoVM: G lat=… lon=… total=… limit=… badge=…`),
    real Pleos Vehicle SDK set-results ticker (logcat `[Pleos][SDK]… onResult() … propKey = …`)
  • callouts with evidence tags + highlight rectangles + leader lines, eased zooms into UI regions
  • narration (Edge neural TTS) + car voice (the exact sentences the app handed to Pleos TTS, from logcat `TTS:` lines —
    the emulator has no on-device TTS engine, so the audio is synthesized afterwards) + the app's chimes re-created at the
    AudioTrack timestamps; subtitles for both voices in the bottom band
  • title / end cards (video/cards/*.png from render_cards.py)

usage:
  compose_demo.py --video take.webm --timeline timeline.log --logcat logcat.txt --out demo.mp4 \
      [--title-png cards/title.png --end-png cards/end.png] [--workdir video/work_demo] [--preview-at 12,40,100] [--no-tts]
Script (chapters / narration / callouts / zooms) lives in demo_script.py next to this file.
"""
import argparse, glob, hashlib, importlib.util, math, os, re, subprocess, sys, time, wave
import numpy as np
import cv2
from PIL import Image, ImageDraw, ImageFont

W, H, FPS, SR = 1920, 1080, 30, 48000
APP = (640, 68, 1260, 908)      # app area (x, y, w, h): Pleos status bar above, nav bar below
PANEL = (0, 68, 640, 908)       # left panel replaces the Pleos cluster widget
BAND = (0, 976, 1920, 104)      # subtitle band replaces the Pleos nav bar
HERE = os.path.dirname(os.path.abspath(__file__))
DESIGN = os.path.abspath(os.path.join(HERE, "..", "..", "design"))

INK, INK_SOFT, INK_CARD = (11, 15, 20), (15, 22, 30), (20, 29, 39)
MIST, MIST_DIM, MINT, MINT_DEEP = (230, 237, 243), (139, 155, 176), (79, 209, 197), (31, 163, 150)
CHAMP, AMBER, CORAL, LEAF, LILAC = (242, 209, 139), (246, 173, 85), (252, 129, 129), (104, 211, 145), (196, 167, 247)
AREA_LABEL = {0: "전역", 1: "운전석", 4: "조수석", 5: "1열 공유", 16: "뒷좌석 왼쪽", 64: "뒷좌석 오른쪽", 32: "뒷좌석 가운데"}

# ----------------------------------------------------------------------------------------------- text / drawing helpers
_FONTS = {}
def font(size, weight="Medium"):
    key = (size, weight)
    if key not in _FONTS:
        path = os.path.join(DESIGN, "fonts", f"Pretendard-{weight}.otf")
        if not os.path.exists(path):
            path = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
        _FONTS[key] = ImageFont.truetype(path, size)
    return _FONTS[key]

def wrap(text, f, max_w, max_lines=3):
    lines, cur = [], ""
    for word in text.split(" "):
        cand = (cur + " " + word).strip()
        if f.getlength(cand) <= max_w or not cur:
            if f.getlength(cand) > max_w:           # a single over-long token: break by characters
                piece = ""
                for ch in cand:
                    if f.getlength(piece + ch) <= max_w:
                        piece += ch
                    else:
                        lines.append(piece); piece = ch
                cur = piece
            else:
                cur = cand
        else:
            lines.append(cur); cur = word
    if cur:
        lines.append(cur)
    if len(lines) > max_lines:
        lines = lines[:max_lines]
        lines[-1] = lines[-1][: max(0, len(lines[-1]) - 1)] + "…"
    return lines

def rgba(w, h):
    return Image.new("RGBA", (max(1, int(w)), max(1, int(h))), (0, 0, 0, 0))

def to_np(img):
    return np.asarray(img, dtype=np.uint8)

def blit(frame, layer, x, y, alpha=1.0):
    """Alpha-composite an RGBA numpy layer onto the RGB frame at (x, y) with an extra global alpha."""
    if alpha <= 0.001:
        return
    x, y = int(round(x)), int(round(y))
    lh, lw = layer.shape[:2]
    x0, y0, x1, y1 = max(x, 0), max(y, 0), min(x + lw, W), min(y + lh, H)
    if x1 <= x0 or y1 <= y0:
        return
    sub = layer[y0 - y : y1 - y, x0 - x : x1 - x]
    a = sub[..., 3:4].astype(np.float32) * (alpha / 255.0)
    if not a.any():
        return
    dst = frame[y0:y1, x0:x1].astype(np.float32)
    frame[y0:y1, x0:x1] = (dst * (1.0 - a) + sub[..., :3].astype(np.float32) * a + 0.5).astype(np.uint8)

def paste(frame, layer_rgb, x, y):
    h, w = layer_rgb.shape[:2]
    frame[y : y + h, x : x + w] = layer_rgb

def ease(t):
    t = min(max(t, 0.0), 1.0)
    return t * t * (3 - 2 * t)

# ----------------------------------------------------------------------------------------------- icons (SVG → RGBA via Chrome)
ICON_NAMES = ["ic_mark", "ic_gear", "ic_speed", "ic_steering", "ic_brake", "ic_door", "ic_seat", "ic_trunk", "ic_nav", "ic_shield",
              "ic_wave", "ic_thermo", "ic_mic", "ic_flag", "ic_check", "ic_sparkle", "ic_car", "ic_route", "ic_bell", "ic_sliders",
              "ic_item_cake", "ic_item_hotfood", "ic_restore", "ic_clock"]
KEEP_COLOR = {"ic_mark", "ic_item_cake", "ic_item_hotfood"}

def ensure_icons(workdir, size=96):
    out = os.path.join(workdir, "icons"); os.makedirs(out, exist_ok=True)
    if all(os.path.exists(os.path.join(out, n + ".png")) for n in ICON_NAMES):
        return out
    cols = 6; rows = (len(ICON_NAMES) + cols - 1) // cols
    chrome = (glob.glob(os.path.expanduser("~/Library/Caches/ms-playwright/chromium-*/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"))
              or ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"])[0]
    renders = {}
    for bg in ("black", "white"):
        cells = "".join(f'<div class="c"><img class="{"" if n in KEEP_COLOR else "w"}" src="file://{DESIGN}/svg/{n}.svg"></div>' for n in ICON_NAMES)
        html = (f'<!doctype html><html><head><meta charset="utf-8"><style>*{{margin:0;padding:0}}body{{background:{bg};width:{cols*size}px;height:{rows*size}px;overflow:hidden}}'
                f'.g{{display:grid;grid-template-columns:repeat({cols},{size}px)}}.c{{width:{size}px;height:{size}px;display:flex;align-items:center;justify-content:center}}'
                f'img{{width:{size-16}px;height:{size-16}px}}img.w{{filter:brightness(0) invert(1)}}</style></head><body><div class="g">{cells}</div></body></html>')
        hp = os.path.join(out, f"sheet_{bg}.html"); open(hp, "w").write(html)
        pp = os.path.join(out, f"sheet_{bg}.png")
        subprocess.run([chrome, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--allow-file-access-from-files",
                        f"--window-size={cols*size},{rows*size}", f"--screenshot={pp}", "file://" + hp], check=True, capture_output=True)
        renders[bg] = np.asarray(Image.open(pp).convert("RGB")).astype(np.float32)
    b, w = renders["black"], renders["white"]
    alpha = np.clip(1.0 - (w - b).mean(axis=2) / 255.0, 0, 1)
    color = np.where(alpha[..., None] > 0.02, b / np.maximum(alpha[..., None], 0.02), 0)
    sheet = np.dstack([np.clip(color, 0, 255), alpha * 255]).astype(np.uint8)
    for i, n in enumerate(ICON_NAMES):
        r, c = divmod(i, cols)
        Image.fromarray(sheet[r * size : (r + 1) * size, c * size : (c + 1) * size], "RGBA").save(os.path.join(out, n + ".png"))
    return out

_ICONS = {}
def icon(name, size, color=None):
    key = (name, size, color)
    if key not in _ICONS:
        img = Image.open(os.path.join(ICON_DIR, name + ".png")).convert("RGBA").resize((size, size), Image.LANCZOS)
        if color is not None:
            arr = np.asarray(img).copy(); arr[..., :3] = color; img = Image.fromarray(arr, "RGBA")
        _ICONS[key] = img
    return _ICONS[key]

# ----------------------------------------------------------------------------------------------- parsing the take
def parse_timeline(path):
    t0 = dev = None; events, says = [], []
    rx_ev = re.compile(r'^\s*(\d+(?:\.\d+)?)\s+Broadcast completed: result=\d+, data="(.*)"\s*$')
    rx_say = re.compile(r"^\s*(\d+(?:\.\d+)?)\s+(?:\[\s*\d+s\]\s*)?(\S.*?)\s*$")
    for line in open(path, encoding="utf-8", errors="replace"):
        if line.startswith("T0="):
            t0 = float(line.split("=", 1)[1]); continue
        if line.startswith("DEV_OFFSET="):
            dev = float(line.split("=", 1)[1]); continue
        m = rx_ev.match(line)
        if m:
            events.append((float(m.group(1)), m.group(2))); continue
        m = rx_say.match(line)
        if m and m.group(2) not in ("done",) and not m.group(2).startswith("Broadcast"):
            says.append((float(m.group(1)), m.group(2)))
    return t0, dev, events, says

def parse_logcat(path):
    """Returns device-epoch-stamped lists; Composer converts them to video time once the clock offset is estimated."""
    rx = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+([A-Z])\s+(.+?)\s*:\s?(.*)$")
    applog, gsamples, chimes, sdk, bridge = [], [], [], [], []
    rx_g = re.compile(r"G lat=(-?[\d.]+) lon=(-?[\d.]+) total=([\d.]+) limit=([\d.]+) badge=(\w+) kph=(-?[\d.]+)")
    rx_sdk = re.compile(r"onResult\(\) called with: propKey = (\w+), areaId = (\d+), result = (\w+)")
    rx_at = re.compile(r"stop\(\d+\): called with (\d+) frames delivered")
    for line in open(path, encoding="utf-8", errors="replace"):
        m = rx.match(line)
        if not m:
            continue
        t = float(m.group(1))
        lvl, tag, msg = m.group(2), m.group(3).strip(), m.group(4).rstrip()
        if tag == "DemoBridge":
            bridge.append((t, msg.strip()))
        elif tag == "CargoVM":
            g = rx_g.search(msg)
            if g:
                gsamples.append((t, float(g.group(1)), float(g.group(2)), float(g.group(3)), float(g.group(4)), g.group(5), float(g.group(6))))
            elif lvl == "I":
                applog.append((t, msg))
        elif tag == "AudioTrack":
            a = rx_at.search(msg)
            if a:
                chimes.append((t, int(a.group(1))))
        elif tag.startswith("[Pleos][SDK]"):
            s = rx_sdk.search(msg)
            if s:
                sdk.append((t, s.group(1), int(s.group(2)), s.group(3) == "true"))
    return applog, gsamples, chimes, sdk, bridge

# ----------------------------------------------------------------------------------------------- injected-signal state
class SignalState:
    def __init__(self, events, T):
        self.ev = sorted((T(t), d) for t, d in events)
        self.i = 0
        self.s = dict(gear="P", kph=0.0, steer=0.0, brake=0.0, doors={}, occ={}, spot="FRONT_PASSENGER", navi_until=-1.0, voice=None, voice_until=-1.0)
    def at(self, t):
        while self.i < len(self.ev) and self.ev[self.i][0] <= t:
            self._apply(self.ev[self.i][1], self.ev[self.i][0]); self.i += 1
        return self.s
    def _apply(self, d, t):
        s = self.s
        try:
            if d.startswith("gear="): s["gear"] = d[5:]
            elif d.startswith("speed="): s["kph"] = float(d[6:].replace("kph", ""))
            elif d.startswith("steer="): s["steer"] = float(d[6:].replace("deg", ""))
            elif d.startswith("brake="): s["brake"] = float(d[6:].replace("%", ""))
            elif d.startswith("door "):
                _, did, st = d.split(" "); s["doors"][did] = st.endswith("true")
            elif d.startswith("occupied "):
                _, rest = d.split(" ", 1); seat, v = rest.split("="); s["occ"][seat] = (v == "true")
            elif d.startswith("spot="): s["spot"] = d[5:]
            elif d.startswith("navi curve"): s["navi_until"] = t + 7.0
            elif d.startswith("voice: "): s["voice"] = d[7:]; s["voice_until"] = t + 4.0
        except Exception:
            pass

SPOT_LABEL = {"FRONT_PASSENGER": "조수석", "REAR_LEFT": "뒷좌석 왼쪽", "REAR_CENTER": "뒷좌석 가운데", "REAR_RIGHT": "뒷좌석 오른쪽", "TRUNK": "트렁크"}
DOOR_LABEL = {"PASSENGER": "조수석", "DRIVER": "운전석", "TRUNK": "트렁크", "REAR_LEFT": "뒷문 왼쪽", "REAR_RIGHT": "뒷문 오른쪽"}

# ----------------------------------------------------------------------------------------------- audio
def tts_clip(text, voice, rate, cache):
    key = hashlib.sha1(f"{voice}|{rate}|{text}".encode("utf-8")).hexdigest()[:16]
    raw, mp3 = os.path.join(cache, key + ".f32"), os.path.join(cache, key + ".mp3")
    if not os.path.exists(raw):
        subprocess.run(["edge-tts", "--voice", voice, f"--rate={rate}", "--text", text, "--write-media", mp3], check=True, capture_output=True)
        af = ("silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.08:stop_periods=-1:stop_threshold=-45dB:stop_silence=0.30,"
              "areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.10,areverse")
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", mp3, "-af", af, "-ac", "1", "-ar", str(SR), "-f", "f32le", raw], check=True)
    return np.fromfile(raw, dtype=np.float32)

def chime_clip(kind):
    """Re-creation of ToneGenerator PROP tones (400 Hz + 1200 Hz dual tone): BEEP 35 ms, ACK 3×100 ms. Soft envelope."""
    def tone(ms):
        n = int(SR * ms / 1000.0); t = np.arange(n) / SR
        sig = 0.55 * np.sin(2 * np.pi * 1200 * t) + 0.45 * np.sin(2 * np.pi * 400 * t)
        env = np.minimum(1.0, np.minimum(t / 0.004, (t[-1] - t) / 0.012 + 1e-9)) if n > 1 else np.ones(n)
        return (sig * np.clip(env, 0, 1)).astype(np.float32)
    gap = np.zeros(int(SR * 0.1), np.float32)
    if kind == "ack":
        return np.concatenate([tone(100), gap, tone(100), gap, tone(100)])
    return tone(60)

def write_wav(path, stereo):
    pcm = (np.clip(stereo, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())

# ----------------------------------------------------------------------------------------------- the composer
class Composer:
    def __init__(self, a, script):
        self.a, self.sc = a, script
        self.t0, self.dev, self.events, self.says = parse_timeline(a.timeline)
        applog, gsamples, chimes, sdk, bridge = parse_logcat(a.logcat) if a.logcat else ([], [], [], [], [])
        self.dev_est = self.estimate_offset(bridge)
        conv = lambda epoch: epoch - self.dev_est - self.t0          # device epoch → seconds since recording start
        self.applog = [(conv(t), m) for t, m in applog]
        self.gsamples = [(conv(r[0]),) + tuple(r[1:]) for r in gsamples]
        self.chimes = [(conv(t), n) for t, n in chimes]
        self.sdk = [(conv(r[0]),) + tuple(r[1:]) for r in sdk]
        first_ev = min(t for t, _ in self.events) if self.events else 0.0
        self.trim = max(0.0, first_ev - a.lead) if a.trim_start is None else a.trim_start
        self.rec_dur = probe_duration(a.video) - self.trim
        self.title_sec, self.end_sec = a.title_sec, a.end_sec
        self.total = self.title_sec + self.rec_dur + self.end_sec
        self.T = lambda host_rel: host_rel - self.trim + self.title_sec   # take time → output time
        self.car_lines = []   # dicts: start, end, text, clip
        self.narr = []
        self.workdir = a.workdir; os.makedirs(os.path.join(self.workdir, "tts"), exist_ok=True)

    def estimate_offset(self, bridge):
        """DEV_OFFSET in the timeline is whole seconds. Refine it by pairing each 'Broadcast completed … data=X' host line with
        the DemoBridge logcat line that produced X (same string, same order): offset = host_rel − (device_epoch − T0)."""
        if not bridge or self.dev is None:
            return self.dev or 0.0
        pairs = []
        plain = [(t, m) for t, m in bridge if not m.startswith("broadcast")]   # the bridge logs each command twice
        for t_host, data in self.events:
            cands = [t for t, m in plain if m == data and abs((t - self.t0 - self.dev) - t_host) < 2.5]
            if cands:
                best = min(cands, key=lambda t: abs((t - self.t0 - self.dev) - t_host))
                pairs.append((best - self.t0) - t_host)      # device clock − host clock (am's return latency makes it slightly negative)
        if len(pairs) < 5:
            print(f"  ! clock offset: only {len(pairs)} bridge pairs, using DEV_OFFSET={self.dev}")
            return self.dev
        pairs.sort(); med = pairs[len(pairs) // 2]
        print(f"  clock offset: DEV_OFFSET={self.dev:.0f}s → estimated {med:.3f}s from {len(pairs)} bridge pairs (spread {pairs[-1]-pairs[0]:.2f}s)")
        return med

    # ---- anchors
    def resolve(self, spec, allow_car=True):
        m = re.match(r"^(.*?)([+-]\d+(?:\.\d+)?)$", spec)
        base, off = (m.group(1), float(m.group(2))) if m else (spec, 0.0)
        n = 1
        mm = re.match(r"^(.*)#(\d+)$", base)
        if mm:
            base, n = mm.group(1), int(mm.group(2))
        t = None
        if base == "title": t = 0.0
        elif base == "rec": t = self.title_sec
        elif base == "end": t = self.title_sec + self.rec_dur
        elif base.startswith("abs:"): t = float(base[4:])
        elif base.startswith("ev:"):
            hits = [tt for tt, d in self.events if base[3:] in d]
            if len(hits) >= n: t = self.T(hits[n - 1])
        elif base.startswith("say:"):
            hits = [tt for tt, d in self.says if base[4:] in d]
            if len(hits) >= n: t = self.T(hits[n - 1])
        elif base.startswith("log:"):
            hits = [tt for tt, d in self.applog if base[4:] in d]
            if len(hits) >= n: t = self.T(hits[n - 1])
        elif base.startswith("car:") and allow_car:
            hits = [c["end"] for c in self.car_lines if base[4:] in c["text"]]
            if len(hits) >= n: t = hits[n - 1]
        if t is None:
            return None
        return t + off

    # ---- audio build
    def build_audio(self):
        a, sc = self.a, self.sc
        cache = os.path.join(self.workdir, "tts")
        # car voice: every TTS sentence the app logged, at the moment it logged it
        for t, msg in self.applog:
            if msg.startswith("TTS: "):
                text = msg[5:].strip(); start = self.T(t)
                clip = tts_clip(text, sc.CAR_VOICE, sc.CAR_RATE, cache) if not a.no_tts else np.zeros(int(SR * 4), np.float32)
                self.car_lines.append(dict(start=start, end=start + len(clip) / SR, text=text, clip=clip))
        # push overlapping car lines apart (the app queues TTS; the emulator never spoke them)
        self.car_lines.sort(key=lambda c: c["start"])
        for i in range(1, len(self.car_lines)):
            p, c = self.car_lines[i - 1], self.car_lines[i]
            if c["start"] < p["end"] + 0.2:
                d = c["end"] - c["start"]; c["start"] = p["end"] + 0.2; c["end"] = c["start"] + d
        # narration
        prev_end = -1e9
        for n in sc.NARRATION:
            anchor = self.resolve(n["at"])
            if anchor is None:
                print(f"  ! narration anchor not found: {n['at']}  ({n['text'][:24]}…)"); continue
            clip = tts_clip(n["text"], sc.NARRATOR_VOICE, sc.NARRATOR_RATE, cache) if not a.no_tts else np.zeros(int(SR * 4), np.float32)
            dur = len(clip) / SR
            start = max(anchor, prev_end + 0.35)
            for _ in range(30):
                clash = [c for c in self.car_lines if start < c["end"] + 0.25 and start + dur > c["start"] - 0.25]
                if not clash:
                    break
                start = max(c["end"] for c in clash) + 0.25
            if start - anchor > 5.0:
                print(f"  ! narration pushed {start - anchor:.1f}s past its anchor: {n['text'][:28]}…")
            self.narr.append(dict(start=start, end=start + dur, text=n["text"], clip=clip, anchor=anchor))
            prev_end = start + dur
        # mix
        total_n = int(self.total * SR) + SR
        mix = np.zeros((total_n, 2), np.float32)
        def add(sig, t, gain):
            i = int(t * SR)
            if i < 0 or i >= total_n: return
            seg = sig[: total_n - i]
            mix[i : i + len(seg), 0] += seg * gain; mix[i : i + len(seg), 1] += seg * gain
        for n in self.narr: add(n["clip"], n["start"], 0.95)
        for c in self.car_lines: add(c["clip"], c["start"], 0.85)
        for t_end, frames in self.chimes:
            kind = "ack" if frames >= 6000 else "beep"
            add(chime_clip(kind), self.T(t_end) - frames / SR, 0.22)
        peak = float(np.abs(mix).max()) if mix.size else 0.0
        if peak > 0.98:
            mix = np.tanh(mix / peak * 1.6) / math.tanh(1.6) * 0.98
        self.wav = os.path.join(self.workdir, "mix.wav"); write_wav(self.wav, mix[: int(self.total * SR)])
        print(f"  audio: {len(self.narr)} narration · {len(self.car_lines)} car lines · {len(self.chimes)} chimes → {self.wav}")

    # ---- timeline items resolved to output time
    def build_items(self):
        sc = self.sc
        self.chapters = []
        for title, at in sc.CHAPTERS:
            t = self.resolve(at)
            if t is None: print(f"  ! chapter anchor not found: {at}"); continue
            self.chapters.append((t, title))
        self.chapters.sort()
        self.callouts = []
        for c in sc.CALLOUTS:
            t = self.resolve(c["at"])
            if t is None: print(f"  ! callout anchor not found: {c['at']}  ({c['title']})"); continue
            self.callouts.append(dict(c, t0=t, t1=t + c["dur"], img=self.render_callout(c)))
        self.zooms = []
        for z in sc.ZOOMS:
            t = self.resolve(z["at"])
            if t is None: print(f"  ! zoom anchor not found: {z['at']}"); continue
            self.zooms.append(dict(z, t0=t, t1=t + z["dur"]))
        self.sig = SignalState(self.events, self.T)
        self.g = [(self.T(t), lat, lon, tot, lim, badge, kph) for t, lat, lon, tot, lim, badge, kph in self.gsamples]
        self.g.sort()
        self.guard_windows = []
        start = None
        for t, msg in self.applog:
            if msg.startswith("가드 시작"): start = self.T(t)
            elif (msg.startswith("도착") or msg.startswith("세션 종료") or msg.startswith("캐빈 원복")) and start is not None:
                self.guard_windows.append((start, self.T(t))); start = None
        if start is not None: self.guard_windows.append((start, self.total))
        self.sdk_items = [(self.T(t), f"{prop} · areaId {area} ({AREA_LABEL.get(area, area)})", ok) for t, prop, area, ok in self.sdk]
        self.title_img = to_np(Image.open(self.a.title_png).convert("RGBA").resize((W, H))) if self.a.title_png else None
        self.end_img = to_np(Image.open(self.a.end_png).convert("RGBA").resize((W, H))) if self.a.end_png else None

    # ---- static / cached layers
    def render_callout(self, c):
        box_w = 580
        ft, fx, fg = font(27, "Bold"), font(23, "Medium"), font(19, "SemiBold")
        lines = wrap(c["text"], fx, box_w - 48, 3)
        h = 22 + 36 + len(lines) * 31 + (40 if c.get("tag") else 10) + 10
        img = rgba(box_w, h); d = ImageDraw.Draw(img)
        d.rounded_rectangle((0, 0, box_w - 1, h - 1), radius=16, fill=(*INK_CARD, 236), outline=(*MINT, 90), width=1)
        d.rectangle((0, 18, 6, h - 18), fill=MINT)
        d.text((26, 18), c["title"], font=ft, fill=MINT)
        y = 58
        for ln in lines:
            d.text((26, y), ln, font=fx, fill=MIST); y += 31
        if c.get("tag"):
            for sz in (19, 18, 17, 16, 15, 14):
                fg = font(sz, "SemiBold")
                if fg.getlength(c["tag"]) <= box_w - 80: break
            tw = int(fg.getlength(c["tag"])) + 28
            d.rounded_rectangle((26, y + 8, 26 + tw, y + 36), radius=14, fill=(*CHAMP, 36), outline=(*CHAMP, 110), width=1)
            d.text((40, y + 22 - sz // 2 - 1), c["tag"], font=fg, fill=CHAMP)
        return to_np(img)

    def panel_static(self):
        pw, ph = PANEL[2], PANEL[3]
        img = Image.new("RGBA", (pw, ph), (*INK, 255)); d = ImageDraw.Draw(img)
        d.rectangle((pw - 1, 0, pw - 1, ph), fill=(*MIST, 14))
        img.alpha_composite(icon("ic_mark", 46), (28, 22))
        d.text((88, 20), "VIP 조수석", font=font(30, "Bold"), fill=MIST)
        d.text((90, 56), "CARGO CONCIERGE · 적재물 기준으로 운전을 돕는 차", font=font(13, "SemiBold"), fill=MIST_DIM)
        # section labels
        d.text((28, 100), "장면", font=font(16, "SemiBold"), fill=MIST_DIM)
        d.line((28, 408, pw - 28, 408), fill=(*MIST, 18), width=1)
        d.ellipse((28, 423, 36, 431), fill=MINT)
        d.text((44, 416), "차량 신호 · adb 데모 브리지 주입 (실차에선 Pleos Vehicle SDK 수신)", font=font(15, "SemiBold"), fill=MIST_DIM)
        d.line((28, 612, pw - 28, 612), fill=(*MIST, 18), width=1)
        img.alpha_composite(icon("ic_shield", 18, MIST_DIM), (28, 623))
        d.text((52, 621), "관성 가드 · 앱이 속도·조향각으로 계산한 추정값 (가속도계 없음)", font=font(15, "SemiBold"), fill=MIST_DIM)
        d.line((28, 738, pw - 28, 738), fill=(*MIST, 18), width=1)
        d.ellipse((28, 751, 36, 759), fill=LEAF)
        d.text((44, 744), "Pleos Vehicle SDK · 실제 setProperty 결과 (logcat)", font=font(15, "SemiBold"), fill=MIST_DIM)
        d.line((28, 838, pw - 28, 838), fill=(*MIST, 18), width=1)
        y = 848
        for ln in self.sc.ENV_NOTE:
            d.text((28, y), ln, font=font(13, "Medium"), fill=MIST_DIM); y += 19
        return to_np(img)[..., :3].copy()

    _chapter_cache = {}
    def chapter_layer(self, idx, prog):
        key = (idx, int(prog * 60))
        if key in self._chapter_cache: return self._chapter_cache[key]
        n = len(self.chapters); img = rgba(584, 280); d = ImageDraw.Draw(img)
        for i, (_, title) in enumerate(self.chapters):
            y = i * 39
            state = "done" if i < idx else "active" if i == idx else "todo"
            col = MIST_DIM if state == "done" else MINT if state == "active" else (*MIST, 150)
            if state == "active":
                d.rounded_rectangle((0, y - 2, 583, y + 33), radius=10, fill=(*MINT, 22))
            d.ellipse((10, y + 6, 32, y + 28), outline=col if state != "done" else (*MIST_DIM, 140), width=2, fill=(*MINT, 255) if state == "active" else None)
            d.text((16 if i < 9 else 13, y + 7), str(i + 1), font=font(14, "Bold"), fill=INK if state == "active" else (col if state != "done" else (*MIST_DIM, 200)))
            if state == "done":
                d.line((15, y + 17, 20, y + 22), fill=LEAF, width=2); d.line((20, y + 22, 28, y + 12), fill=LEAF, width=2)
            d.text((46, y + 5), title, font=font(21, "Bold" if state == "active" else "Medium"), fill=col)
            if state == "active":
                d.rounded_rectangle((46, y + 31, 46 + int(520 * prog), y + 33), radius=1, fill=MINT)
        arr = to_np(img); self._chapter_cache[key] = arr
        return arr

    _hud_cache = {}
    def hud_layer(self, s, t):
        door_open = [DOOR_LABEL.get(k, k) for k, v in s["doors"].items() if v]
        occ = s["occ"]
        seats = "".join("●" if occ.get(k) else "○" for k in ("FRONT_PASSENGER", "REAR_LEFT", "REAR_CENTER", "REAR_RIGHT"))
        navi = "급커브 100 m" if t < s["navi_until"] else "대기"
        key = (s["gear"], round(s["kph"]), round(s["steer"]), round(s["brake"]), tuple(sorted(door_open)), seats, s["spot"], navi)
        if key in self._hud_cache: return self._hud_cache[key]
        img = rgba(584, 168); d = ImageDraw.Draw(img)
        cells = [("ic_gear", "기어", s["gear"] if s["gear"] else "—", MIST),
                 ("ic_speed", "속도", f"{round(s['kph'])} km/h", MIST),
                 ("ic_steering", "핸들 각도", f"{round(s['steer'])}°", AMBER if abs(s["steer"]) >= 80 else MIST),
                 ("ic_brake", "브레이크", f"{round(s['brake'])} %" if s["brake"] > 0 else "뗌", CORAL if s["brake"] > 0 else MIST),
                 ("ic_door", "도어", (" · ".join(door_open) + " 열림") if door_open else "모두 닫힘", MINT if door_open else MIST),
                 ("ic_seat", "착좌 앞·뒤L·C·R", seats, LEAF if "●" in seats else MIST),
                 ("ic_nav", "내비", navi, CHAMP if navi != "대기" else MIST),
                 ("ic_trunk" if s["spot"] == "TRUNK" else "ic_sparkle", "VIP 자리", SPOT_LABEL.get(s["spot"], s["spot"]), MINT)]
        cw, ch = 143, 82
        for i, (ic, lab, val, col) in enumerate(cells):
            r, c = divmod(i, 4)
            x, y = c * (cw + 4), r * (ch + 4)
            d.rounded_rectangle((x, y, x + cw - 1, y + ch - 1), radius=12, fill=(*INK_SOFT, 255), outline=(*MIST, 16), width=1)
            img.alpha_composite(icon(ic, 16, MIST_DIM), (x + 10, y + 10))
            d.text((x + 30, y + 9), lab, font=font(12, "SemiBold"), fill=MIST_DIM)
            fs = 26 if font(26, "Bold").getlength(val) <= cw - 20 else 20 if font(20, "Bold").getlength(val) <= cw - 20 else 16
            d.text((x + 10, y + 40 if fs >= 20 else y + 44), val, font=font(fs, "Bold"), fill=col)
        arr = to_np(img); self._hud_cache[key] = arr
        return arr

    def gauge_layer(self, value, sample, guarding):
        img = rgba(584, 84); d = ImageDraw.Draw(img)
        tx, tw, ty, th = 0, 584, 52, 12
        d.rounded_rectangle((tx, ty, tx + tw, ty + th), radius=7, fill=(*INK_SOFT, 255), outline=(*MIST, 20), width=1)
        scale = 0.40
        if guarding and sample is not None:
            _, lat, lon, tot, lim, badge, kph = sample
            ratio = tot / lim if lim > 0 else 0          # the app's own sample, not the smoothed needle
            col = {"OK": LEAF, "WARN": AMBER, "OVER": CORAL}.get(badge, LEAF)
            fw = int(tw * min(value / scale, 1.0))
            if fw > 0: d.rounded_rectangle((tx, ty, tx + max(fw, 14), ty + th), radius=7, fill=col)
            lx = tx + int(tw * min(lim / scale, 1.0))
            d.rectangle((lx - 1, ty - 7, lx + 1, ty + th + 7), fill=MIST)
            d.text((lx + 6, ty + th + 2), f"허용 {lim:.2f} G", font=font(13, "SemiBold"), fill=MIST)
            d.text((0, -4), f"{value:.2f} G", font=font(36, "Bold"), fill=col)
            info = f"횡 {abs(lat):.2f} · 종 {lon:+.2f} · 최대 {self.max_g:.2f} G · 허용치의 {int(ratio * 100)}%"
            d.text((584 - int(font(15, "Medium").getlength(info)), 8), info, font=font(15, "Medium"), fill=MIST_DIM)
            lab = {"OK": "정상", "WARN": "경고음 구간 (허용치 80% 이상)", "OVER": "초과 → 음성 코칭"}.get(badge, badge)
            d.text((0, ty + th + 2), lab, font=font(13, "SemiBold"), fill=col)
        else:
            d.text((0, 0), "가드 대기", font=font(24, "Bold"), fill=MIST_DIM)
            d.text((0, ty + th + 2), "VIP를 등록하면 주행 중 횡·종가속도를 추정합니다", font=font(13, "Medium"), fill=MIST_DIM)
        for k in range(0, 5):
            x = tx + int(tw * (k * 0.1) / scale)
            d.text((x - (0 if k == 0 else 8), ty - 16), f"{k*0.1:.1f}", font=font(11, "Medium"), fill=(*MIST_DIM, 170))
        return to_np(img)

    _sdk_cache = {}
    def sdk_layer(self, t):
        vis = [(tt, txt, ok) for tt, txt, ok in self.sdk_items if 0 <= t - tt <= 9.0][-3:]
        key = tuple((round(tt, 1), txt, ok, int(min(1.0, max(0.0, (9.0 - (t - tt)) / 1.5)) * 10)) for tt, txt, ok in vis)
        if key in self._sdk_cache: return self._sdk_cache[key]
        img = rgba(584, 70); d = ImageDraw.Draw(img)
        if not vis:
            d.text((0, 2), "최근 호출 없음 (캐빈 적용·원복 때 기록)", font=font(15, "Medium"), fill=(*MIST_DIM, 150))
        for i, (tt, txt, ok) in enumerate(reversed(vis)):
            a = int(255 * min(1.0, max(0.15, (9.0 - (t - tt)) / 1.5)))
            y = i * 24
            d.text((0, y), "✓" if ok else "✗", font=font(16, "Bold"), fill=(*(LEAF if ok else CORAL), a))
            d.text((22, y), txt, font=font(16, "Medium"), fill=(*MIST, a))
        arr = to_np(img); self._sdk_cache[key] = arr
        return arr

    _band_cache = {}
    def band_layer(self, narr_text, car_text):
        key = (narr_text, car_text)
        if key in self._band_cache: return self._band_cache[key]
        img = Image.new("RGBA", (W, BAND[3]), (*INK, 255)); d = ImageDraw.Draw(img)
        chips = rgba(W, BAND[3]); dc = ImageDraw.Draw(chips)
        rows = []
        if car_text: rows.append(("차량 음성 · Pleos TTS 문장(합성)", AMBER, "“" + car_text + "”"))
        if narr_text: rows.append(("내레이션", MIST_DIM, narr_text))
        if len(rows) == 2:
            for i, (chip, col, txt) in enumerate(rows):
                y = 10 + i * 46
                self._chip(dc, 40, y + 6, chip, col)
                lines = wrap(txt, font(27, "SemiBold"), 1480, 1)
                d.text((350, y + 2), lines[0], font=font(27, "SemiBold"), fill=col if col is AMBER else MIST)
        elif len(rows) == 1:
            chip, col, txt = rows[0]
            f = font(31, "SemiBold"); lines = wrap(txt, f, 1480, 2)
            y0 = 14 if len(lines) == 2 else 32
            self._chip(dc, 40, y0 + 6 if len(lines) == 1 else 30, chip, col)
            for i, ln in enumerate(lines):
                d.text((350, y0 + i * 38), ln, font=f, fill=col if col is AMBER else MIST)
        img.alpha_composite(chips)
        arr = to_np(img); self._band_cache[key] = arr
        return arr

    def _chip(self, d, x, y, text, col):
        f = font(15, "Bold"); w = int(f.getlength(text)) + 24
        d.rounded_rectangle((x, y, x + w, y + 28), radius=14, fill=(*col, 34), outline=(*col, 120), width=1)
        d.text((x + 12, y + 5), text, font=f, fill=col)

    # ---- per-frame
    def zoom_view(self, t):
        for z in self.zooms:
            if z["t0"] <= t <= z["t1"]:
                e = min(ease((t - z["t0"]) / 0.6), ease((z["t1"] - t) / 0.6))
                zf = 1 + (z["zoom"] - 1) * e
                if zf <= 1.001: return None
                ax, ay, aw, ah = APP
                rx, ry, rw, rh = z["rect"]; cx, cy = rx + rw / 2, ry + rh / 2
                cw, ch = aw / zf, ah / zf
                x0 = min(max(cx - cw / 2, ax), ax + aw - cw); y0 = min(max(cy - ch / 2, ay), ay + ah - ch)
                return (x0, y0, zf)
        return None

    def map_pt(self, view, x, y):
        if view is None: return x, y
        x0, y0, zf = view
        return APP[0] + (x - x0) * zf, APP[1] + (y - y0) * zf

    def compose(self, frame, t, smooth):
        a = self.a
        # zoom
        view = self.zoom_view(t)
        if view is not None:
            x0, y0, zf = view; ax, ay, aw, ah = APP
            crop = frame[int(y0) : int(y0 + ah / zf), int(x0) : int(x0 + aw / zf)]
            frame[ay : ay + ah, ax : ax + aw] = cv2.resize(crop, (aw, ah), interpolation=cv2.INTER_CUBIC)
        # callouts
        for c in self.callouts:
            if c["t0"] - 0.05 <= t <= c["t1"] + 0.3:
                al = min(ease((t - c["t0"]) / 0.35), ease((c["t1"] + 0.3 - t) / 0.3))
                if al <= 0: continue
                px, py = c["pos"]
                if c.get("rect"):
                    rx, ry, rw, rh = c["rect"]
                    (x1, y1), (x2, y2) = self.map_pt(view, rx, ry), self.map_pt(view, rx + rw, ry + rh)
                    pad = 6
                    layer = rgba(x2 - x1 + 2 * pad + 8, y2 - y1 + 2 * pad + 8); d = ImageDraw.Draw(layer)
                    d.rounded_rectangle((4, 4, layer.width - 5, layer.height - 5), radius=14, outline=(*MINT, 230), width=3)
                    d.rounded_rectangle((1, 1, layer.width - 2, layer.height - 2), radius=16, outline=(*MINT, 70), width=2)
                    blit(frame, to_np(layer), x1 - pad - 4, y1 - pad - 4, al)
                    # leader line from the box to the nearest edge of the rect
                    bw, bh = c["img"].shape[1], c["img"].shape[0]
                    bx, by = px + bw / 2, py + bh / 2
                    tx = min(max(bx, x1), x2); ty = min(max(by, y1), y2)
                    sx = px + bw if tx > px + bw else px if tx < px else bx
                    sy = py + bh if ty > py + bh else py if ty < py else by
                    if al > 0.4:
                        cv2.line(frame, (int(sx), int(sy)), (int(tx), int(ty)), MINT, 2, cv2.LINE_AA)
                        cv2.circle(frame, (int(tx), int(ty)), 5, MINT, -1, cv2.LINE_AA)
                blit(frame, c["img"], px, py + int((1 - al) * 14), al)
        # left panel
        paste(frame, self._panel, PANEL[0], PANEL[1])
        idx = 0
        for i, (ct, _) in enumerate(self.chapters):
            if t >= ct: idx = i
        nxt = self.chapters[idx + 1][0] if idx + 1 < len(self.chapters) else self.title_sec + self.rec_dur
        prog = 0.0 if t < self.chapters[0][0] else min(1.0, max(0.0, (t - self.chapters[idx][0]) / max(0.1, nxt - self.chapters[idx][0])))
        blit(frame, self.chapter_layer(idx if t >= self.chapters[0][0] else -1, prog), 28, PANEL[1] + 124)
        s = self.sig.at(t)
        blit(frame, self.hud_layer(s, t), 28, PANEL[1] + 440)
        guarding = any(g0 <= t <= g1 for g0, g1 in self.guard_windows)
        sample = None
        while self._gi < len(self.g) and self.g[self._gi][0] <= t:
            self._last_g = self.g[self._gi]; self._gi += 1
        sample = self._last_g if guarding else None
        target = sample[3] if sample else 0.0
        if sample and t >= sample[0]: self.max_g = max(self.max_g, target) if guarding else self.max_g
        if not guarding: self.max_g = 0.0
        smooth[0] += (target - smooth[0]) * (1 - math.exp(-(1 / FPS) / 0.12))
        blit(frame, self.gauge_layer(smooth[0], sample, guarding), 28, PANEL[1] + 648)
        blit(frame, self.sdk_layer(t), 28, PANEL[1] + 766)
        # cards
        if self.title_img is not None and t < self.title_sec:
            blit(frame, self.title_img, 0, 0, 1.0 if t < self.title_sec - 0.6 else ease((self.title_sec - t) / 0.6))
        te = self.title_sec + self.rec_dur
        if self.end_img is not None and t >= te - 0.6:
            blit(frame, self.end_img, 0, 0, ease((t - (te - 0.6)) / 0.6))
        # subtitle band (on top of the cards too: the hook and the closing line stay readable when muted)
        nt = next((n["text"] for n in self.narr if n["start"] <= t <= n["end"]), None)
        ct = next((c["text"] for c in self.car_lines if c["start"] <= t <= c["end"]), None)
        paste(frame, self.band_layer(nt, ct)[..., :3], BAND[0], BAND[1])
        return frame

    # ---- drivers
    def prepare(self):
        global ICON_DIR
        ICON_DIR = ensure_icons(self.workdir)
        self.build_audio()
        self.build_items()
        self._panel = self.panel_static()
        self._gi, self._last_g, self.max_g = 0, None, 0.0
        print(f"  take: trim {self.trim:.2f}s · rec {self.rec_dur:.1f}s · total {self.total:.1f}s · chapters {len(self.chapters)} · callouts {len(self.callouts)} · zooms {len(self.zooms)} · G samples {len(self.g)}")
        for n in self.narr:
            print(f"    narr {n['start']:7.2f}–{n['end']:6.2f} (anchor {n['anchor']:6.2f})  {n['text'][:40]}")
        for c in self.car_lines:
            print(f"    car  {c['start']:7.2f}–{c['end']:6.2f}  {c['text'][:40]}")

    def frame_at(self, t):
        """Decode one frame of the take at output time t (for previews)."""
        rt = min(max(0.0, t - self.title_sec) + self.trim, self.trim + self.rec_dur - 0.08)
        for args in ((["-ss", f"{rt:.3f}", "-i", self.a.video], []), (["-i", self.a.video], ["-ss", f"{rt:.3f}"])):
            r = subprocess.run(["ffmpeg", "-nostdin", "-v", "error", *args[0], *args[1], "-frames:v", "1",
                                "-vf", f"scale={W}:{H}:flags=lanczos", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True)
            if len(r.stdout) == W * H * 3:
                return np.frombuffer(r.stdout, np.uint8).reshape(H, W, 3).copy()
            print(f"  ! frame_at({t}): ffmpeg returned {len(r.stdout)} bytes rc={r.returncode} {r.stderr[:160]!r}")
        return np.zeros((H, W, 3), np.uint8)

    def preview(self, times):
        out = []
        for t in times:
            self._gi, self._last_g, self.max_g = 0, None, 0.0
            self.sig = SignalState(self.events, self.T)
            # bring the gauge smoothing close to steady state
            smooth = [0.0]
            for k in range(60):
                self.sig.at(t - (60 - k) / FPS)
                while self._gi < len(self.g) and self.g[self._gi][0] <= t - (60 - k) / FPS:
                    self._last_g = self.g[self._gi]; self._gi += 1
                guarding = any(g0 <= t - (60 - k) / FPS <= g1 for g0, g1 in self.guard_windows)
                tg = self._last_g[3] if (self._last_g and guarding) else 0.0
                smooth[0] += (tg - smooth[0]) * (1 - math.exp(-(1 / FPS) / 0.12))
            win = next(((g0, g1) for g0, g1 in self.guard_windows if g0 <= t <= g1), None)
            self.max_g = max([g[3] for g in self.g if win and win[0] <= g[0] <= t] + [0.0]) if win else 0.0
            fr = self.compose(self.frame_at(t), t, smooth)
            p = os.path.join(self.workdir, f"preview_{t:06.1f}.png"); Image.fromarray(fr).save(p); out.append(p)
        return out

    def render(self):
        a = self.a
        total_frames = int(round(self.total * FPS))
        title_frames = int(round(self.title_sec * FPS))
        dec = subprocess.Popen(["ffmpeg", "-nostdin", "-v", "error", "-ss", f"{self.trim:.3f}", "-i", a.video,
                                "-vf", f"fps={FPS},scale={W}:{H}:flags=lanczos", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                               stdout=subprocess.PIPE, bufsize=W * H * 3 * 4)
        enc = subprocess.Popen(["ffmpeg", "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                                "-i", self.wav, "-c:v", "libx264", "-preset", a.preset, "-crf", str(a.crf), "-pix_fmt", "yuv420p", "-r", str(FPS),
                                "-c:a", "aac", "-b:a", "192k", "-af", "loudnorm=I=-16:TP=-1.5:LRA=11", "-ar", "48000", "-shortest", "-movflags", "+faststart", a.out],
                               stdin=subprocess.PIPE)
        nbytes = W * H * 3
        def read_frame():
            buf = dec.stdout.read(nbytes)
            return None if len(buf) < nbytes else np.frombuffer(buf, np.uint8).reshape(H, W, 3)
        last = read_frame()
        smooth = [0.0]; t_start = time.time()
        for fi in range(total_frames):
            t = fi / FPS
            if fi >= title_frames:
                nxt = read_frame()
                if nxt is not None: last = nxt
            frame = last.copy()
            self.compose(frame, t, smooth)
            enc.stdin.write(frame.tobytes())
            if fi % 300 == 0:
                el = time.time() - t_start
                print(f"    {fi}/{total_frames} frames  {el:5.1f}s  ({fi / max(el, 1e-6):4.1f} fps)", flush=True)
        enc.stdin.close(); dec.stdout.close()
        enc.wait(); dec.wait()
        print(f"  video → {a.out}")

def probe_duration(video):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", video], capture_output=True, text=True)
    return float(r.stdout.strip())

def load_script(path):
    spec = importlib.util.spec_from_file_location("demo_script", path); mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
    return mod

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True); ap.add_argument("--timeline", required=True); ap.add_argument("--logcat")
    ap.add_argument("--out", default=None); ap.add_argument("--script", default=os.path.join(HERE, "demo_script.py"))
    ap.add_argument("--workdir", default=os.path.abspath(os.path.join(HERE, "..", "..", "video", "work_demo")))
    ap.add_argument("--title-png"); ap.add_argument("--end-png")
    ap.add_argument("--title-sec", type=float, default=4.0); ap.add_argument("--end-sec", type=float, default=5.0)
    ap.add_argument("--lead", type=float, default=1.5, help="seconds of recording kept before the first injected event")
    ap.add_argument("--trim-start", type=float, default=None)
    ap.add_argument("--preview-at", default=None, help="comma-separated output times → PNGs in workdir, no video")
    ap.add_argument("--no-tts", action="store_true"); ap.add_argument("--preset", default="medium"); ap.add_argument("--crf", type=int, default=18)
    a = ap.parse_args()
    os.makedirs(a.workdir, exist_ok=True)
    comp = Composer(a, load_script(a.script))
    comp.prepare()
    if a.preview_at:
        for p in comp.preview([float(x) for x in a.preview_at.split(",")]):
            print("  preview →", p)
        return
    if not a.out:
        sys.exit("--out required")
    comp.render()

if __name__ == "__main__":
    main()
