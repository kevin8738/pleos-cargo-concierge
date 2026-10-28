# VIP 조수석 (Cargo Concierge) — 3D 주행 시뮬레이션 v2.3 「2021 IONIQ 5」

「VIP 조수석」의 핵심 아이디어를 브라우저 3D로 보여 주는 설명용 시뮬레이션입니다.
**사람 기준 0.40 G가 아니라 케이크 기준(한계 0.15 G의 75 % 이내)으로 운전하면 케이크가 무사하다**는 것을
같은 야간 코스를 두 방식으로 달려 비교합니다.

v2는 v1.1(`../sim_v1/`)의 물리·운전 프로파일·앱 코칭 규칙을 유지하며 차·실내·환경·VIP 품목에 3D 에셋과 PBR 재질을 적용한 판입니다. v2.1에서는 같은 순간 출발하는 좌우 동시 비교를 추가했습니다.

**v2.3 소스와 패킹본은 각각 전체 코스 기능 검사 36개를 통과했고 콘솔·페이지 오류는 0입니다.** A/B 결과의 7개 항목이 기존 물리 결과와 일치합니다. 중립 조명 7뷰의 8개 검사와 실제 시뮬레이터 5뷰(오류 0)를 확인했습니다. 패킹본 체이스·캐빈·4× 진행과 소스 파일/에셋 17개 일치도 확인했습니다. 외형 유사성 피드백을 반영해 현대자동차가 공개한 **2021년형 페이스리프트 이전 IONIQ 5의 실제 사진**으로 외형·실내·20인치 휠을 다시 제작했습니다.

공식 전방 ¾ 사진의 배경을 이미지 편집으로 제거하고, TRELLIS.2 1536 해상도로 형상 기준을 생성했습니다. 차체 `ioniq5_2021`은 공식 제원·사진을 기준으로 매끈한 규칙 표면을 재구성하는 방식입니다. TRELLIS 원본의 기여는 제한된 저주파 후드·지붕 곡률이며, 원본 메시 삼각형과 UV 텍스처를 그대로 유지하지 않습니다. 차체·유리·등화에는 파트별 PBR 재질을 적용하고, 원본 기여 범위는 제작 스크립트 `build_ioniq5_clean.py`와 최종 메타에 기록합니다. 통합 모델은 차체 r9·실내 r3·휠 r2입니다. 차체는 어깨선에 작은 곡면 연결을 적용하고, 213,026면의 전체 토폴로지를 16비트 정밀도 Draco 압축으로 보존합니다. 제조사 CAD나 제조사가 인증한 정밀 모델은 아닙니다. 모서리·지붕 연결부, 타원형 미러와 휠 격자는 근사 표현이며, 유리는 참조 사진보다 맑게 보입니다.

## 실행

빌드나 npm 설치는 필요 없습니다. ES 모듈·importmap·Draco 압축 GLB를 쓰므로 `file://`이 아니라 로컬 서버로 엽니다.

```bash
cd docs/sim            # 이 폴더 (index.html 과 assets/ 가 같이 있어야 합니다)
python3 -m http.server 8787
# Chrome 최신에서 http://127.0.0.1:8787/
```

- Three.js r160과 Draco 디코더는 jsdelivr CDN(`three@0.160.0`)에서 받으므로 인터넷 연결이 필요합니다.
- 에셋 패키지는 약 85 MB(`assets/`, Draco GLB + WebP/JPG 텍스처 + 2k HDRI)입니다. 처음에는 로딩 화면(진행률)이 뜹니다.
- 권장: 데스크톱 Chrome, 전용 GPU 없이도 M1급 내장 GPU에서 55 fps 이상. 55 fps 아래로 3초 지속되면 블룸부터 자동으로 끕니다.

## 조작

| 구분 | 조작 |
| --- | --- |
| 모드 | 좌상단 **A · 자동(사람 기준)** / **B · 자동(케이크 기준 · 한계의 75 % 이내)** / **C · 직접 운전** |
| 비교 | **비교 시작 (좌 A · 우 B 동시)**: 화면이 좌우로 나뉘어 왼쪽 A(사람 기준)·오른쪽 B(케이크 기준)가 **같은 순간 출발해 같은 코스를 동시에** 달립니다. 반쪽마다 계기·배지·말풍선·로그, 가운데 위에 두 차의 거리 차(먼저 도착하면 남은 거리), 도착한 쪽에는 도착 띠. 둘 다 서면 비교 카드(최대 G, 손상도, 급커브 R30 통과 속도, NEAR/OVER 진입 횟수, 코칭, 랩타임) |
| 분류 | **VIP 분류** 드롭다운 9종. 한계 G·사전 안내 속도·B 운전 방식이 바뀌고, 조수석 위 품목도 그 분류의 실제 물건으로 바뀝니다 |
| 카메라 | `1` 체이스 · `2` 캐빈(운전자 머리 위치에서 와이드 디스플레이·조수석 품목·조수석 창) · `3` 탑다운 지도(G 벡터 + 한계 G 원). 동시 비교에서는 1·2가 양쪽에 함께 적용되고, 3은 두 차(A 샴페인·B 민트 표식)가 한 지도에 보입니다 |
| 배속 | 1× / 2× / 4× (물리는 고정 스텝 1/60 s라 배속을 바꿔도 결과가 같습니다) |
| 화면 | `H` 조작 패널 숨김(영상 캡처용) · `F` FPS · `Esc` 카드 닫기 |
| 직접 운전 | `↑` 가속 · `↓` 제동 · `←/→` 핸들 · `Space` 급제동 · `R` 리셋 · 주차장 구역에 멈추면 1초 뒤 P |

## 무엇이 실제 에셋이고 무엇이 대체물인가

### 차 (`js/car.js`, v2.3)

| 부위 | 에셋 | 비고 |
| --- | --- | --- |
| 차체 | `assets/dist/ioniq5_2021.glb` | 공식 사진 배경 제거 → TRELLIS.2 1536 형상 기준 생성 → 공식 제원·사진에 맞춘 매끈한 규칙 표면 재구성. 원본의 제한된 저주파 후드·지붕 곡률만 활용하며 메시 삼각형·UV 텍스처는 그대로 유지하지 않음 |
| 등화 | 차체에 포함된 전후 등화 파트 | 앞쪽은 한쪽당 열린 U자 DRL 2개, 뒤쪽은 한쪽당 픽셀 사각 윤곽 2개를 공식 디테일 사진에 맞춰 제작. 소스의 중립 조명·시뮬레이터 진단 뷰 확인 |
| 바퀴 ×4 | `assets/dist/ioniq5_wheel20.glb` | 255/45 R20 타이어와 20인치 다이아몬드 격자 휠을 포함한 독립 에셋. 넓은 절삭 가공 금속면과 어두운 격자 홈을 양쪽 면에 표현하고 메타의 차축·윤거 위치에서 회전·조향 |
| 실내 | `assets/dist/ioniq5_cabin.glb` | 실제 2021 밝은 실내 사진에 맞춘 곡면 대시보드·HVAC 패널, 흰 베젤 안의 독립 듀얼 디스플레이, 열린 바닥, 별도 Universal Island, 밝은 시트·도어 트림. r3에서 실내 문턱·앞쪽 발밑 측면·창 아래 트림의 틈을 닫음. 두 활성 화면은 각각 0.291 × 0.111 m(대각 약 12.3인치)의 사진 기반 제작값 |
| 핸들·화면 | 실내 에셋의 핸들 그룹과 화면 위치 메타 | 네 점이 있는 둥근 사각 중심 패드·가로 스포크·하단 지지대. 핸들 그룹만 조향 연동하며 조수석 쪽 인포테인먼트 화면에 코칭 캔버스를 표시 |
| 조수석 VIP | 기존 분류별 에셋 | 소스·패킹본 기능 검사에서 조수석 방석 위 배치 확인. 패킹본 캐빈 뷰 확인 |
| 조명 | 전조등 SpotLight 2, 실내등, 접지 그림자 | 소스·패킹본 접지 검사와 진단 뷰 확인 |

외형 제작 기준은 전장 **4.635 m**, 미러 제외 차체 전폭 **1.890 m**, 전고 **1.605 m**, 시각 모델의 축거 **3.000 m**입니다. [현대자동차 2021 모델 제원](https://www.hyundai.com/kr/ko/brand/brandstory/model/ioniq-history/2021-ioniq5)과 [2021년 6월 공식 기술자료](https://www.hyundai.news/newsroom/dam/eu/models/20210630_ioniq_5_press_kit/hyundai-ioniq-5-technical-data-0621.pdf)를 사용합니다(근거 **A**). 20인치 사양의 앞/뒤 윤거는 **1.628/1.637 m**이며, 타이어의 공칭 무하중 반지름 **0.36875 m**는 `20 × 0.0254 / 2 + 0.255 × 0.45`로 계산합니다(근거 **C**). 미러 포함 폭은 별도 메시 측정값입니다. 시뮬레이션 관성 추정에는 기존 **2.9 m** 축거 가정을 유지하며, 이 결과는 실차 시험값이 아닙니다. 수치 등급은 `../근거_EVIDENCE.md` §3에 정리했습니다.

외관·실내의 세부 위치는 사진을 해석해 맞춘 제작값이며 공식 실측값으로 취급하지 않습니다. 원본 사진 URL·해시·연식은 제작 소스의 `web/assets/refs/ioniq5_official/provenance.json`, 모델링 기준은 `web/assets/IONIQ5_DESIGN.md`, TRELLIS 원본의 기여 방식은 차체 메타에 기록합니다. [현대차 2021 이미지 모음](https://www.hyundai.news/eu/models/electrified/ioniq-5/images.html) 중 2021 사진을 사용하며, 기존 생성 참조와 2024/N Line 사진을 외형 판정 기준으로 사용하지 않습니다.

로드 후보 순서는 `ioniq5_2021` → 기존 `ioniq5_t2` → 비주얼 헐 `ioniq5_body` → `sedan_body`입니다. 소스 기능 검사에서 바퀴 4개 접지·시각 축거·조향과 회전·독립 제동등·듀얼 화면·케이크 배치·비교 카드·단독 복귀를 확인했습니다. A/B의 최대 G·손상도·랩타임·R30 속도·코칭·NEAR·OVER는 v2.2 기준과 차이가 0입니다(`web/assets/qa/ioniq5_v23/source_final/report.json`). 최종 압축본은 중립 조명 7뷰·8개 검사(`studio_final2/`)와 시뮬레이터 5뷰·오류 0(`diag_final2/`)를 확인했습니다. 패킹본도 전체 코스 36/36 통과·오류 0·A/B 결과 7개 항목 차이 0이며, 체이스·캐빈·4× 진행·진단 5뷰 오류 0과 소스 JS/에셋 17개 일치를 확인했습니다(`packed_final/report.json`, `pack_parity_privacy.json`). v2.2의 Astra 라벨·시각 QA 점수는 이전 생성 참조에 대한 평가이며, v2.3 승인 결과로 인용하지 않습니다.

### 조수석 VIP 품목 (`config.js` `VIP_ASSETS`, 분류를 바꾸면 교체)
| 분류 | 사용 에셋 | 종류 |
| --- | --- | --- |
| 케이크·디저트 | `cake_box.glb` 흰 상자 + 금색 띠·리본 | 생성(형상) + 규칙 채색 |
| 뜨거운 음식 | `tteokbokki_bag.glb` 포장 봉투 | 생성 + 사진 투영 채색 |
| 꽃·식물 | `bouquet.glb` 꽃다발 | 생성 + 사진 투영 채색 |
| 신선식품 | `grocery_bag.glb` 장바구니 봉투 | 생성 + 사진 투영 채색 |
| 음료·국물 | `coffee_carrier.glb` 커피 캐리어 | 생성 + 사진 투영 채색 |
| 깨지기 쉬운 물건 | Poly Haven `ceramic_vase_01` | **대체물**(도자기 화병 생성본 없음) |
| 전자기기·악기 | Poly Haven `classic_laptop` | **대체물**(노트북 상자 생성본 없음) |
| 반려동물 | `pet_carrier.glb` 이동장 | 생성 + 사진 투영 채색 |
| 일반 짐 | Poly Haven `cardboard_box_01` | CC0 실측 모델 |

### 환경 (`js/world.js`)
- 모델(Poly Haven CC0, 실측 스케일): 가로등 `street_lamp_01/02`, 파사드 모듈 `modular_urban_apartments_facade`(벽돌 건물)·`modular_factory_facade`, 가로수 `island_tree_01`(근경 3D 14그루 + 원경 LOD), 전신주 `modular_electricity_poles`, 벤치 `modular_street_seating`, `korean_public_payphone_01`, `fire_hydrant`, `utility_box_01`, `concrete_road_barrier`, `water_manhole_cover`, `trashbag`, 베이커리 앞 `standing_chalkboard_01`·`outdoor_table_chair_set_01`·`croissant`·`hamburger_buns`·`strawberry_chocolate_cake`·`carrot_cake`·`potted_plant_01`.
- PBR 텍스처(Poly Haven, Diffuse/Normal/ARM): 도로 `asphalt_02`·`asphalt_04`·`road_damaged_clean`, 보도 `brushed_concrete`·`rectangular_paving`, 바닥 `concrete_floor_02`, 벽 `brick_4`, 셔터 `painted_metal_shutter`, 아이오닉 5 시트 `leather_white`(폴백 `fabric_leather_01`).
- 조명·반사: HDRI `dresden_station_night_2k.hdr`(환경광·반사 전용), 가로등 SpotLight는 카메라 근처 6개만 실제 광원(가까운 2개 그림자), 나머지는 발광 재질. 하늘은 어두운 그라데이션 + 별, 원경은 창이 켜진 실루엣 건물.
- 성능: 60 m 청크 단위로 320 m 밖은 숨김, 안개, 가로수 LOD, 블룸 자동 해제.

### 라이선스
- Poly Haven 모델·텍스처·HDRI: CC0 (`assets/LICENSES.md`에 항목별 출처).
- v2.3 차량(`ioniq5_2021`, `ioniq5_cabin`, `ioniq5_wheel20`): 현대자동차의 공식 사진을 연구 참조로 사용해 제작한 설명용 재현 에셋입니다. 참조 사진의 저작권과 차종 명칭·디자인은 현대자동차에 있으며, 사진에 CC0나 새 사용허락을 부여하지 않습니다. 공식 CAD·다운로드한 제조사 3D 모델이 아닙니다.
- 기존 생성 에셋(`ioniq5_t2`, `ioniq5_body`, `car_seat`, `dashboard`, `steering_wheel`, VIP 품목): 프로젝트의 생성 참조 이미지와 생성 파이프라인으로 제작했습니다.
- Three.js: MIT.

## 파일
```
index.html / style.css        HUD·로딩 화면
js/main.js                    에셋 로딩 → 월드 → 차 → 루프 (카메라, 후처리, 품질 단계)
js/assets.js                  AssetLibrary: manifest, GLTF+Draco, HDRI(PMREM), PBR 세트, 실측 스케일 instance()
js/world.js                   도로·보도·건물 청크, 가로등·가로수·소품 배치, 베이커리·주차장, 원경
js/car.js                     차체·실내·완성 휠 에셋, VIP 품목, 디스플레이·조향 연결
js/runner.js                  주행 인스턴스(차량 물리·G 추정·운전자·모니터·차 모델·카메라) — 단독 1대 / 동시 비교 2대
js/split.js                   동시 비교의 반쪽 HUD(계기+디스플레이 요약, 도착 띠)
js/physics.js driver.js monitor.js track.js hud.js config.js   v1.1과 동일(운전·한계 상수)
assets/manifest.json          에셋 id → 경로·실측 치수·라이선스
assets/dist/*.glb             Draco 압축 모델, assets/polyhaven/{hdris,textures}
SPEC.md / CHANGES.md          스펙 v2와 구현 현황·검수 기록
```
