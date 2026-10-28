# VIP 조수석 3D 주행 시뮬레이션 — 스펙 v2 "실사형 에셋" (2026-10-28)

v1.1(`sim_claude/`)의 물리·운전 프로파일·앱 로직 미러·HUD·비교 카드는 **그대로 유지**하고, 보이는 모든 것을 실제 에셋으로 바꾼다.
기존 모듈 중 `physics.js`, `driver.js`, `monitor.js`, `track.js`, `config.js`(운전·한계 상수)는 재사용하고, `world.js`·`car.js`·`main.js`(렌더링·카메라·로딩)와 `hud.js`(디스플레이 패널 텍스처)를 다시 쓴다.

## 에셋 (모두 `assets/` 아래, `assets/manifest.json`에 경로·실측 치수·라이선스)
- 환경 모델(Poly Haven CC0, glTF+텍스처): 가로등 `street_lamp_01/02`, 파사드 모듈 `modular_urban_apartments_facade`·`modular_factory_facade`, 가로수 `jacaranda_tree`·`island_tree_01`, `korean_public_payphone_01`, `fire_hydrant`, `utility_box_01`, `modular_street_seating`, `concrete_road_barrier`, `water_manhole_cover`, `trashbag`, `modular_electricity_poles`, 베이커리 소품 `standing_chalkboard_01`·`outdoor_table_chair_set_01`·`croissant`·`hamburger_buns`, 케이크 `strawberry_chocolate_cake`·`carrot_cake`, VIP 대체 `ceramic_vase_01`(깨지기 쉬운 물건)·`classic_laptop`(전자기기)·`cardboard_box_01`(일반 짐)·`wicker_basket_01`+`food_apple_01`+`bananas`(신선식품 보조)·`wine_bottles_01`.
- HDRI(야간): `assets/polyhaven/hdris/*.hdr` 4종 중 기본 `dresden_station_night_2k.hdr`(환경광·반사), 배경은 HDRI가 아니라 장면의 건물·하늘(HDRI는 조명/반사 전용, 하늘은 어두운 그라데이션 + 별).
- PBR 텍스처: 도로 `asphalt_02`(2k) / `road_damaged_clean`, 보도 `brushed_concrete`·`rectangular_paving`, 시트 `leather_white`·`brown_leather`·`fabric_leather_01`, 셔터 `painted_metal_shutter`, 벽 `brick_4`. 디퓨즈·노멀·러프·AO 맵을 전부 쓰고 반복(repeat)과 anisotropy 8.
- 생성 에셋(GLB, 자체 참조 이미지로 image-to-3D): 세단 외관 `sedan.glb`, 시트 `car_seat.glb`, 핸들 `steering_wheel.glb`, 대시보드 `dashboard.glb`, VIP: `cake_box.glb`(기본), `tteokbokki_bag.glb`, `bouquet.glb`, `grocery_bag.glb`, `coffee_carrier.glb`, `pet_carrier.glb`, `watermelon_net.glb`, `laptop_box.glb`. **없는 파일은 manifest에 없으니 그 항목만 Poly Haven 대체 또는 절제된 프로시저럴로 폴백**(폴백 사용 시 CHANGES.md에 명시).
- 로딩: GLTFLoader + DRACOLoader(`three/addons/libs/draco/`), RGBELoader, 로딩 화면(진행률). 모든 에셋은 manifest의 `size_m`으로 **실측 스케일 보정**(차 길이 5.0 m, 가로등 높이 3.9 m, 케이크 상자 한 변 0.25 m 등).

## 장면 구성 규칙 (현실성)
1. **도로**: 폭 7 m 2차로 + 양쪽 보도 2.5 m(연석 0.15 m 단차), 아스팔트 PBR, 차선은 데칼(흰 실선·노란 중앙선·횡단보도 1곳), 맨홀 뚜껑·배수구. 젖은 밤길 느낌: 러프니스 0.35~0.5, 가로등 반사가 보이게.
2. **거리**: 가로등 30 m 간격(실제 SpotLight는 카메라 근처 6개만 켜고 나머지는 발광 텍스처), 가로수 15 m 간격, 벤치·소화전·배전함·공중전화·쓰레기봉투를 실제 배치 밀도로. 건물은 파사드 모듈을 3~6층으로 조립해 도로 양쪽 블록을 채우고(인스턴싱), 창문 일부만 불 켜짐(에미시브, 따뜻한 색/차가운 색 섞기). 먼 배경은 저해상 빌딩 실루엣 + 안개(FogExp2 0.004).
3. **베이커리**: 출발점에 1층 상가 — 셔터 텍스처, 유리창 뒤 따뜻한 빛, 칠판 입간판 "베이커리", 야외 테이블, 크루아상·빵 소품, 작은 간판(한글, 자체 캔버스 텍스처). **주차장**: 도착점에 노면 P 마크, 바리케이드, 배전함.
4. **차**: `sedan.glb`를 기본으로 하되 재질을 Three.js에서 재지정 — 차체 MeshPhysicalMaterial(짙은 남색, clearcoat 1.0, clearcoatRoughness 0.08, metalness 0.6, roughness 0.35, envMap 강도 1.2), 유리 MeshPhysicalMaterial(transmission 0.6, roughness 0.05, 틴팅), 휠 금속, 타이어 고무. 바퀴는 별도 노드가 없으면 메시에서 바퀴 영역을 분리하지 말고 **바퀴 4개를 Poly Haven/생성 휠 또는 토러스+디스크 PBR로 덧씌운다**(회전·조향 표현). 전조등 SpotLight 2개(노면 콘 보이게), 미등·제동등 에미시브, 번호판 없음, 로고 없음.
5. **실내(캐빈 캠)**: `car_seat.glb` 2개(운전석·조수석) + `dashboard.glb` + `steering_wheel.glb` + 도어 패널·천장·바닥 매트는 가죽/패브릭 PBR로 모델링. 센터 디스플레이 면은 앱 화면 캔버스 텍스처(v1.1과 같은 내용: 배지·말풍선·로그). 조수석 쿠션 위에 선택된 VIP GLB가 놓이고 벨트(검은 띠 메시)가 걸쳐짐. 창밖으로 가로등·건물이 지나간다.
6. **조명/후처리**: ACESFilmic 톤매핑(노출 0.9), 물리 기반 광량(renderer.useLegacyLights=false), 그림자는 차와 가로등 근처만(PCFSoft, 2048), 블룸은 약하게(UnrealBloomPass threshold 0.9 strength 0.35) — 성능 60 fps를 지키되 안 되면 블룸부터 끈다. 색: 민트/샴페인 UI 팔레트 유지.
7. **VIP 분류 9종 ↔ 에셋**: 케이크·디저트→`cake_box.glb`, 뜨거운 음식→`tteokbokki_bag.glb`, 꽃·식물→`bouquet.glb`, 신선식품→`grocery_bag.glb`, 음료·국물→`coffee_carrier.glb`, 깨지기 쉬운 물건→`ceramic_vase_01`, 전자기기·악기→`laptop_box.glb` 또는 `classic_laptop`, 반려동물→`pet_carrier.glb`, 일반 짐→`cardboard_box_01`. 분류를 바꾸면 조수석 위 물건이 바뀐다. 손상 연출: 한계 초과 시 물건이 밀리고 기울며(최대 8 cm/8°), 100 %에서 케이크 상자는 뚜껑이 열리고 `strawberry_chocolate_cake`가 기운 채 보인다(다른 품목은 넘어짐).

## 유지해야 하는 것 (v1.1 그대로)
- 운전 프로파일 A(가속 1.4 / 제동 3.0 / 코너 0.40 G), B(가감속 ≤ 1.0, 코너 한계의 75 %), 고정 스텝 물리, 배지/코칭/사전 안내/도착 규칙, 비교 카드(급커브 통과 속도·NEAR/OVER 횟수 포함), 카메라 1/2/3, 직접 운전 C, 키 F/H/R/Esc, 웰컴 다이얼로그, 한국어 UI, 하단 각주.

## 산출물·품질
- `index.html` + `js/*.js` + `style.css` + `README.md` + `CHANGES.md`(스펙 대비, 폴백 목록, 성능 수치). 에셋은 `../assets/` 상대 경로로 참조(저장소에서는 `docs/sim/assets/`로 함께 복사됨).
- Chrome에서 1920×1080 60 fps 이상, 초기 로딩 ≤ 8 s(로컬), 콘솔 에러 0, 총 다운로드 ≤ 80 MB(텍스처는 1k 이하로 리사이즈한 `assets/dist/*.glb` 사용 우선).
- 완료 전에 세 카메라 각각에서 "실제 에셋이 보이는지"를 스스로 점검하고 CHANGES.md에 스크린샷 없이도 알 수 있게 무엇이 어떤 에셋인지 표로 적는다.
