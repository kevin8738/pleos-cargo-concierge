#!/usr/bin/env python3
"""Title / end cards for the demo video, rendered from the visual kit with headless Chrome (1920×1080).

usage: render_cards.py <out_dir>   -> <out_dir>/title.png, <out_dir>/end.png
"""
import glob, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DESIGN = os.path.abspath(os.path.join(HERE, "..", "..", "design"))
OUT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "cards"))
os.makedirs(OUT, exist_ok=True)

def uri(p): return "file://" + p
FONT = "".join(f'@font-face{{font-family:Pretendard;font-weight:{w};src:url("{uri(os.path.join(DESIGN, "fonts", f"Pretendard-{n}.otf"))}")}}'
               for w, n in [(400, "Regular"), (500, "Medium"), (600, "SemiBold"), (700, "Bold"), (800, "ExtraBold")])
CSS = f"""{FONT}
*{{box-sizing:border-box;margin:0;padding:0}}
html,body{{width:1920px;height:1080px;overflow:hidden;background:#0B0F14;color:#E6EDF3;font-family:Pretendard,sans-serif;letter-spacing:-.01em}}
.card{{position:relative;width:1920px;height:1080px;padding:120px 140px;display:flex;flex-direction:column;justify-content:center;
  background:radial-gradient(900px 520px at 78% 60%,rgba(79,209,197,.14),transparent 70%),linear-gradient(180deg,#0F161E,#0B0F14)}}
.bar{{position:absolute;left:0;top:0;right:0;height:10px;background:linear-gradient(90deg,#4FD1C5,#1FA396)}}
.brand{{display:flex;align-items:center;gap:22px}} .brand img{{width:84px;height:84px}}
.brand .n{{font-size:44px;font-weight:700}} .brand .e{{font-size:18px;letter-spacing:.16em;color:#8B9BB0;font-weight:600;margin-top:6px}}
.eb{{display:flex;align-items:center;gap:12px;color:#F2D18B;font-size:26px;font-weight:600;margin-top:70px}}
.eb img{{width:28px;height:28px;filter:brightness(0) saturate(100%) invert(84%) sepia(28%) saturate(600%) hue-rotate(350deg)}}
h1{{font-size:88px;font-weight:800;line-height:1.1;letter-spacing:-.03em;margin-top:18px}}
h1 b{{color:#4FD1C5;font-weight:800}}
.sub{{font-size:30px;color:#8B9BB0;line-height:1.5;margin-top:28px;max-width:1040px}}
.hero{{position:absolute;right:120px;top:260px;width:620px}}
.foot{{position:absolute;left:140px;bottom:128px;right:140px;display:flex;justify-content:space-between;align-items:flex-end;color:#8B9BB0;font-size:22px}}
.pills{{display:flex;gap:12px;margin-top:40px;flex-wrap:wrap}}
.pill{{display:inline-flex;align-items:center;gap:10px;height:56px;padding:0 20px;border-radius:999px;border:1px solid rgba(230,237,243,.16);
  background:rgba(230,237,243,.04);font-size:22px;font-weight:600;color:#E6EDF3}}
.pill img{{width:30px;height:30px}}
.pill img.ln{{filter:brightness(0) invert(92%)}}
"""
PICS = [("ic_item_cake", "케이크"), ("ic_item_hotfood", "뜨거운 음식"), ("ic_item_flowers", "꽃"), ("ic_item_groceries", "장보기"), ("ic_item_coffee", "커피"),
        ("ic_seat", "조수석 · 뒷좌석"), ("ic_trunk", "트렁크")]
pills = "".join(f'<span class="pill"><img class="{"ln" if n.startswith("ic_") and not n.startswith("ic_item") else ""}" src="{uri(os.path.join(DESIGN, "svg", n + ".svg"))}">{t}</span>' for n, t in PICS)
mark = uri(os.path.join(DESIGN, "svg", "ic_mark.svg")); hero = uri(os.path.join(DESIGN, "svg", "il_hero.svg")); spark = uri(os.path.join(DESIGN, "svg", "ic_sparkle.svg"))

TITLE = f"""<!doctype html><html><head><meta charset="utf-8"><style>{CSS}</style></head><body><div class="card"><div class="bar"></div>
<div class="brand"><img src="{mark}"><div><div class="n">VIP 조수석</div><div class="e">CARGO CONCIERGE</div></div></div>
<div class="eb"><img src="{spark}">Pleos Connect v2.1 에뮬레이터 시연 · VIP 자리 선택 + 케이크 시나리오</div>
<h1>조수석에 탄 <b>소중한 것</b> 기준으로<br>운전하는 차</h1>
<div class="sub">공식 Pleos Connect v2.1 AVD에서 pleos 플레이버 APK를 실행한 화면 녹화입니다. 차량 신호(기어·속도·조향·브레이크·도어·착좌)는 adb 데모 브리지로 주입하고, 공조·시트 제어와 음성 안내는 Pleos Vehicle/TTS SDK를 실제로 호출합니다. 왼쪽 패널의 G 게이지는 앱이 계산한 추정값입니다.</div>
<img class="hero" src="{hero}">
<div class="foot"><span>2026 현대자동차그룹 차량용 앱 개발 공모전 출품작</span><span>Pleos Playground SDK · Vehicle 2.3.2 · NaviHelper 3.0.5 · FusedLocation 1.1.3 · TTS/STT 3.3.1</span></div>
</div></body></html>"""

END = f"""<!doctype html><html><head><meta charset="utf-8"><style>{CSS}</style></head><body><div class="card"><div class="bar"></div>
<div class="brand"><img src="{mark}"><div><div class="n">VIP 조수석</div><div class="e">CARGO CONCIERGE</div></div></div>
<h1 style="margin-top:70px">자리 선택 · 적재 감지 프롬프트 · 품목별 프리셋<br><b>관성 가드</b> 음성 코칭 · 도착 알림과 원복</h1>
<div class="pills">{pills}</div>
<div class="sub">이 영상의 차량 신호는 시연용 주입값이며, 한계치(0.12~0.25 G)는 물리 유도·문헌 기반의 실측 전 가설값입니다(근거표 참조). 공조·열선은 보조 수단이며 식품 보관 온도를 보장하지 않습니다. 에뮬레이터에 온디바이스 TTS 엔진이 없어 음성 안내는 앱이 로그에 남긴 TTS 문장을 후처리로 합성했습니다.</div>
<div class="foot"><span>2026 현대자동차그룹 차량용 앱 개발 공모전 출품작</span><span>Pleos Connect v2.1 AVD · Pleos Vehicle SDK 2.3.2 · 음성 Pleos TTS 3.3.1</span></div>
</div></body></html>"""

chrome = (glob.glob(os.path.expanduser("~/Library/Caches/ms-playwright/chromium-*/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"))
          or ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"])[0]
for name, html in [("title", TITLE), ("end", END)]:
    path = os.path.join(OUT, f"{name}.html")
    open(path, "w").write(html)
    subprocess.run([chrome, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--allow-file-access-from-files", "--window-size=1920,1080",
                    f"--screenshot={os.path.join(OUT, name + '.png')}", "file://" + path], check=True, capture_output=True)
    print("card ->", os.path.join(OUT, name + ".png"))
