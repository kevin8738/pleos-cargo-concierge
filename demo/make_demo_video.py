#!/usr/bin/env python3
"""Emulator screen recording + timeline/logcat -> submission demo video (1920x1080 H.264).

Adds a title card, an end card, a persistent environment badge and bottom captions:
  - "차량 신호" captions from the scenario timeline (record_emulator.sh writes it)
  - "음성 안내" captions from the app's `TTS:` log lines (logcat -v epoch, aligned with DEV_OFFSET/T0)
Captions are PNG overlays rendered with Pillow (the local ffmpeg has no drawtext/libass).

usage: make_demo_video.py --video rec.webm --timeline timeline.log [--logcat logcat.txt] --out demo.mp4
"""
import argparse, os, re, subprocess, sys, tempfile
from PIL import Image, ImageDraw, ImageFont

W, H = 1920, 1080
DESIGN = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "design"))
FONTS = [os.path.join(DESIGN, "fonts", "Pretendard-SemiBold.otf"), "/System/Library/Fonts/AppleSDGothicNeo.ttc", os.path.expanduser("~/Library/Fonts/NanumGothic.ttf")]
TEAL, AMBER, INK, MUTED = (76, 220, 200, 255), (255, 196, 90, 255), (240, 244, 247, 255), (170, 180, 190, 255)
BOX = (8, 14, 20, 215)

def font(size, bold=False):
    for path in FONTS:
        if not os.path.exists(path):
            continue
        if "Pretendard" in path:
            weight = "Bold" if bold else "Medium"
            cand = os.path.join(os.path.dirname(path), f"Pretendard-{weight}.otf")
            return ImageFont.truetype(cand if os.path.exists(cand) else path, size)
        if path.endswith(".ttc"):
            want = ("Bold", "SemiBold", "Heavy") if bold else ("Regular", "Medium")
            for idx in range(0, 16):
                try:
                    f = ImageFont.truetype(path, size, index=idx)
                except Exception:
                    break
                if f.getname()[1] in want:
                    return f
            return ImageFont.truetype(path, size, index=0)
        return ImageFont.truetype(path, size)
    return ImageFont.load_default()

def wrap(text, f, max_w):
    words, lines, cur = text.split(" "), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if f.getlength(t) <= max_w or not cur:
            cur = t
        else:
            lines.append(cur); cur = w
    if cur:
        lines.append(cur)
    return lines[:2] if len(lines) <= 2 else [lines[0], lines[1][: max(0, len(lines[1]) - 1)] + "…"]

CAP_X, CAP_W, CAP_BASE = 664, 1232, 990   # app panel: x 640..1920 in the 1080p frame; HVAC bar starts ~y 1000

def caption_png(path, label, text, color, width=CAP_W):
    fl, ft = font(26, True), font(40, True)
    lines = wrap(text, ft, width - 80)
    height = 112 if len(lines) == 1 else 156
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, width - 1, height - 1), radius=18, fill=BOX)
    d.rectangle((0, 18, 8, height - 18), fill=color)
    d.text((34, 16), label, font=fl, fill=color)
    y = 50 if len(lines) == 1 else 46
    for ln in lines:
        d.text((34, y), ln, font=ft, fill=INK); y += 48
    img.save(path)
    return height

def badge_png(path, text):
    """Two-line badge (split on ' | '), drawn over the Pleos media widget at the bottom-left."""
    f = font(22, True)
    lines = text.split(" | ")
    w = int(max(f.getlength(l) for l in lines)) + 64
    h = 24 + 34 * len(lines)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, w - 1, h - 1), radius=14, fill=(8, 14, 20, 215))
    d.ellipse((20, 20, 34, 34), fill=TEAL)
    for i, l in enumerate(lines):
        d.text((48, 12 + 34 * i), l, font=f, fill=INK)
    img.save(path)

def card_png(path, title, lines, foot):
    img = Image.new("RGBA", (W, H), (7, 12, 18, 255)); d = ImageDraw.Draw(img)
    d.rectangle((0, 0, W, 10), fill=TEAL)
    d.text((120, 300), title, font=font(96, True), fill=TEAL)
    y = 460
    for ln in lines:
        d.text((124, y), ln, font=font(40), fill=INK); y += 64
    d.text((124, H - 120), foot, font=font(28), fill=MUTED)
    img.save(path)

def parse_timeline(path):
    t0 = dev_off = None; events = []
    ev = re.compile(r"^\s*(\d+(?:\.\d+)?)s?\s+\[\s*\d+s\]\s*(?:T\+[\d.]+)?\s*(.+?)\s*$")
    for line in open(path, encoding="utf-8", errors="replace"):
        if line.startswith("T0="): t0 = float(line.split("=", 1)[1]); continue
        if line.startswith("DEV_OFFSET="): dev_off = float(line.split("=", 1)[1]); continue
        m = ev.match(line)
        if m and m.group(2) and m.group(2) != "done":
            events.append((float(m.group(1)), m.group(2)))
    return t0, dev_off, events

def parse_tts(path, t0, dev_off):
    out = []
    if not path or not os.path.exists(path) or t0 is None or dev_off is None:
        return out
    rx = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+[A-Z]\s+CargoVM\s*:\s*TTS:\s*(.+?)\s*$")
    for line in open(path, encoding="utf-8", errors="replace"):
        m = rx.match(line)
        if m:
            out.append((float(m.group(1)) - dev_off - t0, m.group(2)))
    return out

def probe_duration(video):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", video], capture_output=True, text=True)
    try:
        return float(r.stdout.strip())
    except ValueError:
        r = subprocess.run(["ffmpeg", "-i", video, "-f", "null", "-"], capture_output=True, text=True)
        m = re.findall(r"time=(\d+):(\d+):(\d+\.\d+)", r.stderr)
        h, mi, s = m[-1]; return int(h) * 3600 + int(mi) * 60 + float(s)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True); ap.add_argument("--timeline", required=True)
    ap.add_argument("--logcat"); ap.add_argument("--out", required=True)
    ap.add_argument("--title", default="VIP 조수석 (Cargo Concierge)")
    ap.add_argument("--subtitle", default="Pleos Connect v2.1 에뮬레이터 시연 · 케이크 시나리오")
    ap.add_argument("--badge", default="Pleos Connect v2.1 AVD · Pleos Vehicle SDK 2.3.2 실제 연결 | 차량 신호(기어·속도·조향·도어)는 adb 데모 브리지 주입")
    ap.add_argument("--trim-start", type=float, default=0.0); ap.add_argument("--trim-end", type=float, default=0.0)
    ap.add_argument("--title-sec", type=float, default=3.6); ap.add_argument("--end-sec", type=float, default=4.5)
    ap.add_argument("--title-png"); ap.add_argument("--end-png")
    a = ap.parse_args()

    t0, dev_off, events = parse_timeline(a.timeline)
    tts = parse_tts(a.logcat, t0, dev_off)
    dur = probe_duration(a.video) - a.trim_start - a.trim_end
    tmp = tempfile.mkdtemp(prefix="demo_video_")
    inputs, filters, idx = ["-ss", str(a.trim_start), "-i", a.video], [], 1
    chain = "[0:v]fps=30,scale=%d:%d:flags=lanczos,setsar=1,trim=duration=%.3f,setpts=PTS-STARTPTS,tpad=stop_mode=clone:stop_duration=%.2f[v0]" % (W, H, dur, a.end_sec)
    filters.append(chain); last = "v0"

    def add_overlay(png, x, y, enable, fade=None):
        nonlocal idx, last
        inputs.extend(["-loop", "1", "-framerate", "30", "-i", png])
        src = f"[{idx}:v]"
        if fade:
            filters.append(f"{src}format=rgba,{fade}[f{idx}]"); src = f"[f{idx}]"
        filters.append(f"[{last}]{src}overlay={x}:{y}:enable='{enable}':shortest=1[v{idx}]")
        last = f"v{idx}"; idx += 1

    # captions: events (track 0, bottom) and TTS (track 1, above)
    evs = [(t - a.trim_start, txt) for t, txt in events if t - a.trim_start >= 0]
    for i, (t, txt) in enumerate(evs):
        end = min(evs[i + 1][0] - 0.15 if i + 1 < len(evs) else t + 7, t + 7)
        png = os.path.join(tmp, f"ev{i}.png"); h = caption_png(png, "차량 신호 주입 (adb → 앱)", txt, TEAL)
        add_overlay(png, CAP_X, CAP_BASE - h, f"between(t,{t:.2f},{end:.2f})")
    tt = [(t - a.trim_start, txt) for t, txt in tts if t - a.trim_start >= 0]
    for i, (t, txt) in enumerate(tt):
        end = min(tt[i + 1][0] - 0.15 if i + 1 < len(tt) else t + 5, t + 5)
        png = os.path.join(tmp, f"tts{i}.png"); h = caption_png(png, "음성 안내 (Pleos TTS)", "“" + txt + "”", AMBER)
        add_overlay(png, CAP_X, CAP_BASE - 156 - 14 - h, f"between(t,{t:.2f},{end:.2f})")
    # badge (whole video), title card, end card
    b = os.path.join(tmp, "badge.png"); badge_png(b, a.badge)
    bh = Image.open(b).size[1]
    add_overlay(b, 24, 992 - bh, f"between(t,{a.title_sec:.2f},{dur:.2f})")
    tc = a.title_png or os.path.join(tmp, "title.png")
    if not a.title_png: card_png(tc, a.title, [a.subtitle, "실제 Pleos Connect v2.1 에뮬레이터(AVD)에서 pleos 플레이버 APK를 실행한 화면 녹화입니다.",
                           "차량 신호(기어·속도·조향·브레이크·도어)는 adb 데모 브리지로 주입하고,",
                           "조수석 공조·시트 제어와 음성 안내는 Pleos Vehicle/TTS SDK를 실제로 호출합니다."],
             "VIP 조수석 · 2026 현대자동차그룹 차량용 앱 개발 공모전 출품작")
    add_overlay(tc, 0, 0, f"between(t,0,{a.title_sec:.2f})", fade=f"fade=t=out:st={a.title_sec - 0.6:.2f}:d=0.6:alpha=1")
    ec = a.end_png or os.path.join(tmp, "end.png")
    if not a.end_png: card_png(ec, "VIP 조수석", ["조수석에 탄 ‘소중한 것’ 기준으로 운전하는 차.",
                                "적재 감지 프롬프트 · 품목별 공조/시트 프리셋 · 관성 가드 음성 코칭 · 도착 알림과 원복",
                                "이 영상의 차량 신호는 시연용 주입값이며, 한계치는 실측 전 가설값입니다."],
             "Pleos Playground SDK: Vehicle 2.3.2 · NaviHelper 3.0.5 · FusedLocation 1.1.3 · TTS/STT 3.3.1")
    add_overlay(ec, 0, 0, f"gte(t,{dur:.2f})", fade=f"fade=t=in:st={dur:.2f}:d=0.6:alpha=1")

    cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *inputs,
           "-filter_complex", ";".join(filters), "-map", f"[{last}]", "-map", "0:a?",
           "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-r", "30",
           "-c:a", "aac", "-b:a", "128k", "-af", "apad", "-t", f"{dur + a.end_sec:.2f}", "-movflags", "+faststart", a.out]
    print(f"events={len(evs)} tts={len(tt)} duration={dur:.1f}s (+{a.end_sec:.0f}s end card) -> {a.out}")
    subprocess.run(cmd, check=True)
    print("ok", a.out)

if __name__ == "__main__":
    main()
