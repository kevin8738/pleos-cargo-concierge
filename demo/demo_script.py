# -*- coding: utf-8 -*-
"""Scene script for compose_demo.py (VIP 조수석 시연 영상).

Everything is anchored to the recording's own events, so a re-recording re-aligns captions, callouts and narration:
  "rec"                     start of the screen recording (right after the title card)
  "title" / "end"           start of the title card / start of the end card
  "ev:<substring>[#n]"      n-th (1-based) timeline line 'Broadcast completed ... data="<...>"' whose data contains <substring>
  "say:<substring>"         timeline narrative line (scenario 'say' text or '[epi]' marker)
  "log:<substring>[#n]"     n-th logcat CargoVM line containing <substring> (e.g. "log:TTS: 100미터")
  "car:<substring>"         END of the synthesized car-voice line whose text contains <substring>
An anchor may carry an offset: "ev:gear=D+0.4", "log:도착-1.0".
Rects are (x, y, w, h) in the 1920×1080 output frame; the app occupies x 640‥1900, y 68‥976.
"""

NARRATOR_VOICE = "ko-KR-InJoonNeural"      # 내레이션 (남)
CAR_VOICE = "ko-KR-SunHiNeural"            # 차량 음성 안내 — 앱이 Pleos TTS 에 넘긴 문장(logcat 'TTS:')을 그대로 합성
NARRATOR_RATE = "+12%"
CAR_RATE = "+8%"

CHAPTERS = [
    ("정차 · VIP 자리 고르기", "rec"),
    ("적재 감지 → 10초 제안", "ev:door PASSENGER open=true"),
    ("말로 등록 · 캐빈 적용", "ev:voice: 케이크"),
    ("주행 가드 · 음성 코칭", "ev:gear=D"),
    ("도착 · 원복 · 리포트", "ev:gear=P#2"),
    ("자리 + 물건 한 번에", "say:[epi] 음성"),
    ("설정 · 읽기 전용이 기본", "say:[epi] 설정 화면"),
]

# Narration lines: scheduled at the anchor, pushed later only as far as needed to never overlap a car-voice line.
NARRATION = [
    dict(at="title+0.4",                      text="조수석에 탄 소중한 것 기준으로 운전하는 차. VIP 조수석입니다."),
    dict(at="rec+0.6",                        text="베이커리 앞에 섰습니다. 기어는 피, 속도는 0. 오른쪽 차량 맵에서 VIP가 탈 자리를 고릅니다."),
    dict(at="ev:spot=REAR_RIGHT+0.3",         text="탑승 중인 자리도 고를 수 있습니다. 사람이 들고 탈 수 있으니까요. 그땐 열선과 통풍을 건드리지 않습니다."),
    dict(at="ev:spot=TRUNK+0.3",              text="트렁크는 공조 영역이 아니라서 관성 가드와 도착 챙김만 동작합니다."),
    dict(at="ev:door PASSENGER open=true+0.2", text="조수석 문이 열렸다 닫히면, 차는 무언가 실렸다는 걸 압니다."),
    dict(at="ev:door PASSENGER open=false+1.2", text="10초 안에 고르지 않으면 조용히 사라집니다. 운전자를 귀찮게 하지 않도록요."),
    dict(at="car:케이크 VIP 모드+0.3",          text="말로 하면 됩니다. 케이크 기준 0.15G는 상자 미끄러짐 한계의 절반, 버스 승객이 불편해하는 가속도입니다."),
    dict(at="car:케이크 VIP 모드+9.0",          text="조수석 존만 18도, 열선은 끕니다. SDK 호출 결과를 칩으로 그대로 보여줍니다."),
    dict(at="ev:gear=D+0.3",                  text="출발하면 화면은 배지 하나만 남습니다. 주행 중 안내는 전부 음성입니다."),
    dict(at="ev:steer=45deg+0.3",             text="완만한 코너는 허용치의 85%. 효과음 하나로 끝납니다."),
    dict(at="car:핸들을 조금만+0.3",            text="0.29G. 가속도계 없이 속도와 조향각, 축거 2.9미터로 계산합니다. 그래서 코너 진입 전에 말할 수 있습니다."),
    dict(at="car:100미터 앞+0.3",              text="급커브 예고엔 케이크 기준 권장 속도를 먼저 말합니다. 반경 30미터면 시속 25킬로미터입니다."),
    dict(at="ev:steer=85deg+0.3",             text="코칭대로 줄이면 같은 코너가 한계 안으로 들어옵니다."),
    dict(at="car:브레이크를 조금만+0.3",         text="급제동도 같은 모델로 잡습니다. 음성 코칭은 주행당 세 번까지, 긴급 조작 중엔 말하지 않습니다."),
    dict(at="car:챙기세요+0.3",                text="피에서 운전석 문이 열리면 챙김 안내와 리포트. 공조와 시트는 출발 전 상태로 돌려놓습니다."),
    dict(at="say:[epi] 리포트 확인+0.6",        text="이벤트 로그에 주입 신호, SDK 호출, 음성 안내가 남습니다."),
    dict(at="car:떡볶이 VIP 모드+0.3",      text="자리와 물건을 한 번에 말해도 됩니다. 아이가 들고 있는 뒷좌석이라 열선과 통풍은 두고 가드만 켭니다. 미지원 기능은 숨기지 않고 표시합니다."),
    dict(at="say:[epi] 설정 화면+0.5",          text="기본은 읽기 전용 가드입니다. 빈 좌석 열선처럼 제어가 필요한 기능은 동의 위에서만 켭니다."),
    dict(at="end+0.5",                        text="VIP 조수석. 소중한 것 기준으로 운전하는 차입니다."),
]

# Callouts over the app area. pos = top-left of the text box; rect = highlighted UI element (optional).
HOME_BOX = (700, 604)
DRIVE_BOX = (700, 110)
LOW_BOX = (700, 690)
SIDE_BOX = (1310, 300)   # over the app's right column (its signals are mirrored in the left HUD)
CALLOUTS = [
    dict(at="rec+0.8", dur=4.0, rect=(1365, 104, 505, 700), pos=HOME_BOX,
         title="VIP 자리 고르기", text="조수석 · 뒷좌석 왼/중/오 · 트렁크. 착좌 센서(OCS)가 잡은 자리는 초록 ‘탑승 중’.",
         tag="Pleos Vehicle SDK · SEAT_OCCUPANCY 5좌석 리스너"),
    dict(at="ev:spot=REAR_RIGHT+0.2", dur=5.0, rect=(1696, 452, 142, 160), pos=HOME_BOX,
         title="탑승 중인 자리도 선택 가능", text="사람이 들고 타는 경우: 열선·통풍은 그대로, 관성 가드와 도착 챙김만 켭니다.",
         tag="동승자 보호 우선 · 좌석별 억제 규칙"),
    dict(at="ev:spot=TRUNK+0.2", dur=3.6, rect=(1392, 626, 446, 114), pos=HOME_BOX,
         title="트렁크", text="공조 영역이 아니므로 가드 + 도착 챙김만. 실차 capability에 따라 자리별 플랜이 매핑됩니다.",
         tag="capability 매핑 · 미지원은 숨기지 않고 표시"),
    dict(at="ev:door PASSENGER open=true+0.1", dur=2.0, rect=(1620, 268, 218, 170), pos=HOME_BOX,
         title="적재 추정 = P + 0 km/h + 조수석 문 열림→닫힘", text="차 안에서만 얻는 신호 조합. 부팅 전 도어 이력·픽업 매장 지오펜스는 확장 트리거.",
         tag="F1 · 운전자 개입 없이 ‘제안’까지만"),
    dict(at="ev:door PASSENGER open=false+1.0", dur=7.0, rect=(676, 160, 520, 66), pos=(1240, 96),
         title="10초 제안 시트", text="최근 VIP(말로 등록한 것 우선) · 보호 분류 9종 · 자리 칩. 무응답 3회면 그 트리거는 음소거.",
         tag="주의분산: 정차(P) 중에만 표시 · 10초 뒤 자동 닫힘"),
    dict(at="car:케이크 VIP 모드+0.4", dur=6.6, rect=(802, 252, 500, 34), pos=SIDE_BOX,
         title="케이크 0.15G의 근거", text="상자 미끄러짐 μs 0.3 → 0.30G의 절반 · 버스 승객이 ‘불편’이라 답한 1.5 m/s² = 0.15G",
         tag="근거 C 물리 유도 · B Nguyen et al. 2019 (근거표 §1.2)"),
    dict(at="car:케이크 VIP 모드+7.2", dur=5.0, rect=(688, 322, 426, 82), pos=SIDE_BOX,
         title="Pleos Vehicle SDK 실제 호출 결과", text="조수석 존 18℃ 적용 · 열선 OFF · 통풍 · 풍량/풍향은 1열 공유 존이라 ‘유지’ — 거부·미지원도 그대로 표시",
         tag="에뮬레이터 설정 범위 15~30℃ 실측 (dumpsys)"),
    dict(at="ev:gear=D+0.5", dur=6.0, rect=(1150, 350, 240, 240), pos=DRIVE_BOX,
         title="주행 중엔 배지 하나", text="숫자도 버튼도 없음 · 안내는 음성 · distractionOptimized. 왼쪽 G 게이지는 영상용 오버레이(앱 추정값).",
         tag="NHTSA 2013 시선 이탈 2초 / 누적 12초"),
    dict(at="ev:steer=45deg+0.2", dur=5.0, rect=None, pos=DRIVE_BOX,
         title="완만한 코너 · 허용치 80% 넘음 → 효과음 1회", text="횡 0.13G, 가속 중이라 합성은 0.15G 직전. 음성 코칭은 한계를 넘을 때만.",
         tag="a_lat = v²·tan(δ/15)/2.9 m · 30 km/h · 45°"),
    dict(at="ev:steer=100deg+0.2", dur=7.5, rect=None, pos=DRIVE_BOX,
         title="0.29G 초과 → 음성 코칭", text="가속도계 없이 속도·조향각으로 계산하므로 코너 진입 ‘전’에 코칭할 수 있습니다.",
         tag="축거 2,895 mm(그랜저 GN7) · 조향비 15 (10/28 확인)"),
    dict(at="ev:navi curve+0.2", dur=7.5, rect=None, pos=DRIVE_BOX,
         title="급커브 예고 → 권장 25 km/h", text="v = √(0.15·g·30 m) = 6.6 m/s ≈ 25 km/h · 꽃이면 30, 뜨거운 국물이면 20",
         tag="근거 A 도로구조규칙 제19조 R=30 m(설계속도 30 km/h)"),
    dict(at="ev:steer=85deg+0.2", dur=4.5, rect=None, pos=DRIVE_BOX,
         title="같은 코너, 한계 안", text="22 km/h × 85° → 약 0.13G, 허용치의 89%. 효과음만, 음성 없음.",
         tag="코칭 효과를 같은 코너로 보여줌"),
    dict(at="ev:brake=60.0%+0.2", dur=7.0, rect=None, pos=DRIVE_BOX,
         title="급제동 ≈ 0.5G → 음성 코칭", text="브레이크 60% → 종가속도 보정 · 주행당 음성 코칭 3회 상한 · 긴급(≥0.6G)엔 침묵",
         tag="UN R13-H 긴급제동신호 6 m/s² ≈ 0.61G"),
    dict(at="log:도착+0.8", dur=7.0, rect=(826, 380, 490, 160), pos=SIDE_BOX,
         title="도착 리포트", text="최대 G · 음성 코칭/경고음 횟수 · 이동 시간 · 한계 판정. 공조·시트는 출발 전 상태로 원복.",
         tag="트리거: P + 운전석 문 열림 (또는 내비 도착)"),
    dict(at="say:[epi] 리포트 확인+0.8", dur=4.4, rect=(668, 830, 1204, 122), pos=HOME_BOX,
         title="이벤트 로그", text="주입 신호 · 실제 SDK 호출 결과 · 음성 안내 문장이 순서대로 남습니다.",
         tag="심사용 투명성: 주입과 실제 호출을 구분"),
    dict(at="car:떡볶이 VIP 모드+0.4", dur=8.0, rect=(802, 212, 600, 86), pos=SIDE_BOX,
         title="자리 + 물건 한 번에", text="“조수석 뒤에 떡볶이 실었어” → 뒷좌석 오른쪽 · 뜨거운 음식 0.12G · 아이가 들고 있어 열선·통풍 제외 · 뒷좌석 송풍 제어 없음 → ‘차량 미지원’으로 표시",
         tag="F6 음성 자리 구문 · 들고 타기 모드"),
    dict(at="say:[epi] 설정 화면+0.6", dur=5.0, rect=(688, 216, 1176, 52), pos=(700, 520),
         title="빈 좌석 열선 허용 · 기본 OFF", text="제어가 필요한 기능은 동의 위에서만. OCS가 막으면 ‘빈 좌석 감지로 차단’으로 그대로 표시.",
         tag="읽기 전용 가드가 기본 → 출시 리스크 최소"),
    dict(at="say:[epi] 설정 화면+5.8", dur=3.0, rect=(670, 848, 1200, 80), pos=(700, 520),
         title="G 추정 모델", text="a_lat = v² · tan(δ_wheel / ratio) / L · L = 2.9 m, ratio = 15 (차량이 알려주면 교체)",
         tag="근거 C 물리 유도 · 근거표 §3"),
]

# Zooms into the app area (rect in frame coords, zoom factor). Eased in/out over 0.6 s.
ZOOMS = [
    dict(at="ev:spot=REAR_RIGHT+0.2", dur=4.8, rect=(1365, 240, 505, 520), zoom=1.45),
    dict(at="ev:spot=TRUNK+0.2", dur=3.4, rect=(1365, 240, 505, 520), zoom=1.45),
    dict(at="car:케이크 VIP 모드+7.0", dur=5.0, rect=(668, 170, 670, 420), zoom=1.35),
    dict(at="log:도착+0.8", dur=6.4, rect=(668, 100, 670, 560), zoom=1.3),
    dict(at="car:떡볶이 VIP 모드+0.4", dur=8.0, rect=(668, 170, 670, 420), zoom=1.35),
]

ENV_NOTE = [
    "Pleos Connect v2.1 AVD(Android 14 · 2560×1440) · Pleos Vehicle SDK 2.3.2 실제 연결",
    "차량 신호는 adb 데모 브리지 주입 · 공조·시트는 실제 SDK 호출 · G는 앱 추정값",
    "음성 안내: 에뮬레이터에 TTS 엔진이 없어 앱 로그의 TTS 문장을 후처리 합성",
]
