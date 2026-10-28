# VIP 조수석 (Cargo Concierge) — 3D 주행 시뮬레이션 v2.2 「현대 아이오닉 5」

「VIP 조수석」의 핵심 아이디어를 브라우저 3D로 보여 주는 설명용 시뮬레이션입니다.
**사람 기준 0.40 G가 아니라 케이크 기준(한계 0.15 G의 75 % 이내)으로 운전하면 케이크가 무사하다**는 것을
같은 야간 코스를 두 방식으로 달려 비교합니다.

v2는 v1.1(`../sim_v1/`)의 물리·운전 프로파일·앱 규칙 미러·HUD·비교 카드를 **한 글자도 바꾸지 않고**,
보이는 모든 것(차, 건물·가로 시설, 조수석의 VIP 품목, 재질·조명)을 **실제 에셋**으로 바꾼 판입니다.
에셋을 먼저 모으고(`assets/`), 그 실측 치수에 맞춰 장면을 조립했습니다.

v2.2는 현대 아이오닉 5(2021년형)를 묘사한 생성 이미지(gpt-image-2)를 바탕으로 **TRELLIS.2 1024 해상도 PBR 텍스처 차체**를 제작했습니다. 참조는 공식 CAD나 직접 촬영 사진이 아닙니다. 외형은 제조사 제원에 맞추고, Astra(`gpt-6-astra`, reasoning effort `max`)로 참조 이미지의 부위 라벨과 시각 QA를 보조합니다. 좌우 비교의 물리·한계 G·코칭 규칙은 기존 값을 유지합니다.

## 실행

빌드나 npm 설치는 필요 없습니다. ES 모듈·importmap·Draco 압축 GLB를 쓰므로 `file://`이 아니라 로컬 서버로 엽니다.

```bash
cd docs/sim            # 이 폴더 (index.html 과 assets/ 가 같이 있어야 합니다)
python3 -m http.server 8787
# Chrome 최신에서 http://127.0.0.1:8787/
```

- Three.js r160과 Draco 디코더는 jsdelivr CDN(`three@0.160.0`)에서 받으므로 인터넷 연결이 필요합니다.
- 에셋은 약 80 MB(`assets/`, Draco GLB + WebP/JPG 텍스처 + 2k HDRI)이며 처음 한 번 로딩 화면(진행률)이 뜹니다.
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

### 차 (`js/car.js`)
| 부위 | 에셋 | 비고 |
| --- | --- | --- |
| 차체 | `assets/dist/ioniq5_t2.glb` — 아이오닉 5를 묘사한 gpt-image-2 참조 이미지 → **TRELLIS.2**(`microsoft/TRELLIS.2-4B`) 1024 해상도 형상·PBR 텍스처 생성 → 제원 스케일·바퀴 절제·유리 영역 분할 → Draco | 미러 제외 차체 전폭 1.890 × 전고 1.605 × 전장 4.635 m(2021년 4월 모델, 근거 A·아래 공식 출처). PBR 텍스처 유지, Astra 라벨 기반 유리 분할·별도 픽셀 등화 오버레이 |
| 바퀴 | 프로시저럴(타이어 원통 + 림·스포크) | 차체 메타(`ioniq5_t2_meta.json`)의 바퀴 중심·반지름에 맞춤. 회전·조향 |
| 시트 ×2 | `car_seat.glb`(생성) + Poly Haven `leather_white` PBR(아이오닉 5), 폴백 `fabric_leather_01` | 바닥에서 헤드레스트까지 0.98 m. 조수석 방석 윗면은 로드 후 레이캐스트로 실측해 품목을 올려놓음 |
| 대시보드 | `dashboard.glb`(생성) | 폭 1.46 m(A필러 사이), 깊이 절반으로 축소. 윗단 뒷면에 와이드 디스플레이(캔버스 텍스처: 배지·총 G·한계·말풍선) |
| 핸들 | `steering_wheel.glb`(생성) | 지름 0.38 m, 핸들각에 맞춰 회전 |
| 실내 트림 | 프로시저럴(바닥 카펫, 헤드라이너, 도어 트림, A·B 필러, 뒤 선반, 뒷좌석) | 밝은 회색 시트·트림을 아이오닉 차체 내부에 배치. 캐빈에서 앱 디스플레이와 조수석 위 케이크 상자를 확인하는 일반형 설명용 실내. 아이오닉 5 순정 실내의 정밀 재현이 아님 |
| 조명 | 전조등 SpotLight 2(노면 콘), 은은한 실내등, 접지 그림자(타원 그라데이션) | |

외형 치수 출처는 [현대자동차 IONIQ 5 2021 모델 히스토리](https://www.hyundai.com/kr/ko/brand/brandstory/model/ioniq-history/2021-ioniq5)(근거 **A**, 공식 제원)입니다. 공식 전폭은 미러를 제외한 차체 기준이며, 미러 포함 메시 폭은 별도 측정값입니다. 실제 축거는 3.000 m이며, 시뮬레이션 관성 추정에는 앱과 같은 기존 2.9 m 가정을 사용합니다. 수치의 등급·출처는 `../근거_EVIDENCE.md` §3에 정리했습니다. 이 결과는 실차 시험값이 아닙니다.

차체 후보는 `ioniq5_t2` → 비주얼 헐 `ioniq5_body` → 기존 `sedan_body` 순이며, 기본 차체는 TRELLIS.2 생성본입니다. Astra(`gpt-6-astra`, effort `max`)가 만든 정면·후면·측면 부위 라벨을 헐 분류와 텍스처 차체의 유리 영역 분리에 활용합니다. PBR 텍스처를 유지하며, 생성 참조 이미지와 진단 렌더를 비교하는 Astra 시각 QA로 차체·등화·실내 배치를 보정합니다. 이 평가는 생성 참조와의 시각적 비교이며 실측 제품 정확도나 안전성 시험이 아닙니다. 생성 메시의 패널·창문 경계에는 근사 표현이 남아 있습니다.

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
- 생성 에셋(`ioniq5_t2`, `ioniq5_body`, `car_seat`, `dashboard`, `steering_wheel`, VIP 품목): 프로젝트에서 만든 참조 이미지와 생성 파이프라인으로 제작. 아이오닉 5의 차종 명칭·디자인은 현대자동차의 것이며, 생성 차체는 공모전 설명용 재현 에셋입니다.
- Three.js: MIT.

## 파일
```
index.html / style.css        HUD·로딩 화면
js/main.js                    에셋 로딩 → 월드 → 차 → 루프 (카메라, 후처리, 품질 단계)
js/assets.js                  AssetLibrary: manifest, GLTF+Draco, HDRI(PMREM), PBR 세트, 실측 스케일 instance()
js/world.js                   도로·보도·건물 청크, 가로등·가로수·소품 배치, 베이커리·주차장, 원경
js/car.js                     생성 차체 + 재질, 바퀴, 실내, VIP 품목, 디스플레이
js/runner.js                  주행 인스턴스(차량 물리·G 추정·운전자·모니터·차 모델·카메라) — 단독 1대 / 동시 비교 2대
js/split.js                   동시 비교의 반쪽 HUD(계기+디스플레이 요약, 도착 띠)
js/physics.js driver.js monitor.js track.js hud.js config.js   v1.1과 동일(운전·한계 상수)
assets/manifest.json          에셋 id → 경로·실측 치수·라이선스
assets/dist/*.glb             Draco 압축 모델, assets/polyhaven/{hdris,textures}
SPEC.md / CHANGES.md          스펙 v2와 구현 현황·검수 기록
```
