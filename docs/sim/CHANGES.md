# CHANGES — v2 「실사형 에셋」 (2026-10-28)

## 원칙
- v1.1의 **물리·운전 프로파일·앱 규칙 미러·HUD·비교 카드는 그대로**(`physics.js`, `driver.js`, `monitor.js`, `track.js`, `hud.js`, `config.js`의 운전·한계 상수는 v1.1과 동일). 비교 결과(최대 G·손상도·랩타임)는 v1.1과 같아야 하며, 아래 검수에서 확인했다.
- **에셋 먼저**: `assets/`에 실제 에셋(Poly Haven CC0 + 자체 생성 GLB)을 모으고 `manifest.json`에 실측 치수를 기록한 뒤, 장면·차·실내 치수를 그 실측값에 맞춰 조립했다(`SPEC.md`).

## 에셋 파이프라인 (`assets/`)
1. `fetch_polyhaven.py` — 모델 29종·HDRI 4종·PBR 텍스처 12세트 내려받기(CC0, `LICENSES.md`).
2. `gen_refs.py` — 생성 대상(세단 4면, 시트, 핸들, 대시보드, VIP 품목 10종)의 참조 사진을 gpt-image-2로 만들고 배경 제거.
3. `space_ui_h3d.py` — Hunyuan3D-2 / 2mv(세단은 4면 멀티뷰)로 형상 생성(공개 Space, 텍스처 없음).
4. `project_color.py` — 참조 사진을 실루엣 IoU로 자세 맞춤 후 정점색으로 투영(상자·차는 규칙 팔레트: 흰 상자+금색 띠 / 도장·유리·등화·하부).
5. `split_car.py` — 세단을 팔레트별 파트 노드(paint/glass/light_front/light_rear/dark)로 분할, 바퀴 면 제거 + 바퀴 중심·반지름 메타, Taubin 스무딩 8회, 지붕 유리→도장 보정, 사이드미러 절제(실내에서 막대처럼 보여 sim 쪽 하우징으로 대체).
6. `finalize_generated.sh` / `optimize.sh` — 15만 면 이하로 단순화 + Draco(생성물), Poly Haven 은 WebP 1k + Draco. `build_manifest.py` 로 `manifest.json`.
7. `pack_sim.py` — sim이 참조하는 에셋만 골라 `docs/sim/assets/`로 복사(약 80 MB).

## 구현 (`js/`)
- `assets.js` AssetLibrary: manifest → GLTF+Draco, RGBE HDRI → PMREM 환경, PBR 세트(Diffuse/Normal/ARM, 반복·이방성), `instance(id)`가 실측 치수(`real_size_m` → `REAL_SIZE`)로 스케일하고 바닥 중심 피벗으로 감싼다.
- `world.js` 월드: 트랙을 따라 60 m 청크(도로 PBR·중앙선·연석·보도·건물 파사드·가로등·가로수·소품), 베이커리(간판 "달빛 베이커리", 쇼윈도, 칠판, 테이블)와 주차장, 원경 실루엣, 하늘 그라데이션+별, 가로등 SpotLight 풀 6개(가까운 2개 그림자), 320 m 밖 청크 숨김.
- `car.js` 차: 생성 차체 파트별 실제 재질(클리어코트 도장·틴팅 유리·발광 등화), 바퀴는 메타 위치에 프로시저럴, 실내는 생성 시트·대시보드·핸들 + 레이캐스트 실측 치수의 트림·필러, 조수석 품목은 분류별 생성 GLB → Poly Haven → 프로시저럴 순 폴백, 와이드 디스플레이(캔버스).
- `main.js`: 로딩 화면(진행률) → 월드 → 차 → 루프. EffectComposer(UnrealBloom 0.22, threshold 1.0) + OutputPass, 55 fps 미만 3초면 블룸 해제. QA 훅 `window.__dbgView`(차 로컬 좌표 카메라 고정), `__car`, `__scene`.

## 폴백(대체물) 사용
| 분류 | 의도 | 실제 사용 | 이유 |
| --- | --- | --- | --- |
| 깨지기 쉬운 물건 | `pottery_vase.glb` 생성 | Poly Haven `ceramic_vase_01` | 생성 시간 부족(공개 Space 대기열) |
| 전자기기·악기 | `laptop_box.glb` 생성 | Poly Haven `classic_laptop` | 같음 |
| 케이크(보조) | `cake_slice_box.glb` | 미사용(기본 `cake_box.glb`가 있음) | 같음 |
| 바퀴 | 생성 차체의 바퀴 | 프로시저럴 타이어·림(메타 위치) | 생성 바퀴는 회전·조향이 안 되고 형상이 거칠어서 |
| 사이드미러 | 생성 차체의 미러 | 프로시저럴 하우징 | 생성 미러의 얇은 꼬리가 실내에서 막대처럼 보여서 |
| 실내 트림·필러·뒷좌석 | — | 프로시저럴(실측 외피 안쪽) | 생성 대상이 아니었음 |

## 검수 중 고친 것 (헤드리스 Chromium·SwiftShader + 차 로컬 고정 카메라 진단 뷰)
1. 분할 GLB에 법선이 없어 차가 검게 보임 → 로드 시 `computeVertexNormals()`.
2. 바퀴를 뺀 차체를 `instance()`가 바닥 y=0에 맞춰 9 cm 가라앉음(전고 1.37) → 원래 전고(1.46)만큼 올려 지상고 복원.
3. 실내 파트(헤드레스트·필러·도어 트림·뒤 선반·대시보드)가 차체 밖으로 삐져나옴 → 차체를 레이캐스트로 실측(벨트라인 반폭 0.80, 창 높이 반폭 0.65, 지붕 안쪽 1.40)해 시트 높이 0.98 m, 대시보드 폭 1.46 m·깊이 절반, 트림 폭을 그 안으로 재배치. 대시보드는 앞 방향이 모델 -Z였는데 -X로 돌려 놓았던 것을 수정(후드를 뚫고 나오던 원인). 필러는 유리 외피를 더듬어 4 cm 안쪽에 자동 배치.
4. 도장이 크롬처럼 보임(밤 HDRI의 역 천장 조명 반사) → envMapIntensity 1.1 → 0.5, metalness 0.3, roughness 0.36. 블룸 0.22/threshold 1.0, 반구광 0.3.
5. 실내가 대낮처럼 밝음 → 실내 재질 envMapIntensity 0.12~0.25, 실내등 0.12 cd, 실내 오브젝트 `receiveShadow`(지붕이 가로등 그림자를 드리움), 흰 품목 알베도 0.72.
6. 센터 디스플레이가 대시보드 안에 묻힘 → 대시보드 윗단 뒷면(x 0.90, y 0.88)의 와이드 패널로 이동.
7. 접지 그림자가 네모난 판 → 타원 그라데이션.
8. 차체 표면 잔물결 → Taubin 스무딩 8회(체적 보존) 후 단순화(15만 면 → 7.9만 면, 136 KB).
9. 생성 미러의 꼬리 → 미러 면 절제 + 프로시저럴 하우징.

## 검수 결과 (2026-10-28, 헤드리스 Chromium + SwiftShader, `assets/qa_sim.py --no-map --small` · `diag_car.py`)
- 콘솔 에러 0 (GPU 성능 경고만), 저장소 사본(`docs/sim/`, `ASSET_ROOT='assets/'`)에서 4xx 응답 0. 사용 에셋: body `sedan_body.glb`, seat `car_seat.glb`, dashboard `dashboard.glb`, steering `steering_wheel.glb`, vip `cake_box`(생성).
- 진단 뷰(측면·후상방·전방 ¾·탑뷰·캐빈 2종): 실내 파트가 차체 밖으로 나오지 않음, 지상고 정상(차체 0.00~1.46 m), 네이비 클리어코트·접지 그림자·미러 하우징·필러 정상, 캐빈에서 와이드 디스플레이("OK · 0.00 G · 한계 0.15 G")·핸들·조수석 케이크 상자·게이지 보임.
- 비교 주행(케이크·디저트 0.15 G, 4×): 소프트웨어 렌더링은 0.3~3 fps 라 15분 안에 A 주행 1:02까지만 진행 — 그 구간의 값은 v1.1과 같다(최대 0.42 G, 0:26.8 케이크 망가짐 → 손상 100 %, 코칭 3/3, R60 구간 OVER). `physics.js`·`driver.js`·`monitor.js`·`track.js`·`hud.js`와 `config.js`의 운전·한계 상수는 v1.1과 바이트 단위로 같고 물리는 고정 스텝이므로 전체 결과(A 0.417 G·100 %·1:35.5 / B 0.117 G·0 %·2:13.5 / R30 39.5 vs 20.8 km/h)는 v1.1과 동일하다. 실제 GPU(Chrome)에서의 fps 와 전체 비교 카드는 사용자 환경에서 확인할 것.
- 알려진 한계: 생성 차체는 로고 없는 일반형 세단으로 전면 범퍼에 잔물결이 남아 있음(스무딩 8회 후), 전조등 영역은 발광 면으로만 표현. 깨지기 쉬운 물건·전자기기 분류는 Poly Haven 대체물.
