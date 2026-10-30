# VIP 조수석 — 3D 주행 시뮬레이션

[웹에서 시뮬레이션 실행](https://vip-seat-cargo-concierge.ivory-haven-1059.chatgpt.site) — 합성 비교 계산을 보여 주는 설명용 자료입니다.
2021 IONIQ 5를 묘사한 차량으로 같은 야간 코스를 동시에 달리는 설명용 시뮬레이션입니다. 왼쪽 A는 사람 기준 0.40 G, 오른쪽 B는 선택한 VIP 한계의 75 %를 코너 목표로 사용합니다. 기본 VIP는 케이크(한계 0.15 G)입니다.

## 실행

프로젝트 루트에서 실행한 뒤 Chrome으로 <http://127.0.0.1:8788/>을 엽니다.

```bash
cd app/docs/sim
python3 -m http.server 8788 --bind 127.0.0.1
```

빌드나 npm 설치는 필요 없습니다. ES 모듈을 사용하므로 로컬 서버가 필요합니다. Three.js r160과 Draco 디코더를 jsDelivr CDN에서 받아 인터넷 연결이 필요하며, 처음에는 약 82 MB의 실행 파일과 에셋을 읽습니다. `index.html`, `js/`, `style.css`, `assets/`를 함께 유지하세요.

## 조작

| 기능 | 조작 |
| --- | --- |
| 동시 비교 | **비교 시작**: 좌 A·우 B가 함께 출발하고, 둘 다 도착하면 최대 G·손상도·코칭·랩타임을 비교합니다 |
| 단독 주행 | A 사람 기준 / B VIP 기준 / C 직접 운전 |
| VIP 변경 | 분류 드롭다운 9종. 조수석 품목과 한계 G가 함께 바뀝니다 |
| 카메라 | `1` 체이스 · `2` 캐빈 · `3` 지도 |
| 배속 | 1× / 2× / 4× |
| 직접 운전 | 방향키 가속·제동·조향, `Space` 급제동, `R` 리셋 |
| 화면 | `H` 패널 숨김 · `F` FPS · `Esc` 카드 닫기 |

## 포함 에셋

차량은 `ioniq5_2021.glb`(차체), `ioniq5_cabin.glb`(실내), `ioniq5_wheel20.glb`(바퀴)를 사용합니다. 흰 베젤의 듀얼 화면, 열린 바닥, Universal Island, 네 점 핸들, 20인치 다이아몬드 격자 휠을 반영했습니다. 조향·바퀴 회전·제동등·코칭 화면이 주행에 반응합니다.

| VIP | 기본 에셋 |
| --- | --- |
| 케이크·디저트 | `cake_box.glb` |
| 뜨거운 음식 | `tteokbokki_bag.glb` |
| 꽃·식물 | `bouquet.glb` |
| 신선식품 | `grocery_bag.glb` |
| 음료·국물 | `coffee_carrier.glb` |
| 깨지기 쉬운 물건 | `ceramic_vase_01.glb`(화병 대체물) |
| 전자기기·악기 | `classic_laptop.glb`(노트북 대체물) |
| 반려동물 | `pet_carrier.glb` |
| 일반 짐 | `cardboard_box_01.glb` |

거리의 건물·가로등·나무·베이커리 소품, PBR 텍스처와 야간 HDRI는 Poly Haven 에셋을 사용합니다. 목록과 경로는 [manifest](assets/manifest.json), 항목별 출처는 [Poly Haven 라이선스 목록](assets/LICENSES_polyhaven.md)에 있습니다.

## 제작 기준과 해석

외형은 현대자동차의 2021년형 공식 사진을 연구 참조로 제작했습니다. 공식 전방 사진에서 생성한 TRELLIS.2 형상의 제한된 저주파 후드·지붕 곡률을 활용하고, 공식 제원·사진에 맞춰 규칙 표면과 파트별 PBR 재질을 재구성했습니다. 원본 메시 삼각형·UV 텍스처를 그대로 유지하지 않으며, 세부 형상과 미러·휠에는 근사가 남습니다. 제작 정보는 [차체 메타](assets/dist/ioniq5_2021_meta.json)와 [실내 메타](assets/dist/ioniq5_cabin_meta.json)에 있습니다.

전장 4.635 m·미러 제외 전폭 1.890 m·전고 1.605 m·시각 축거 3.000 m는 [2021 공식 제원](https://www.hyundai.com/kr/ko/brand/brandstory/model/ioniq-history/2021-ioniq5)을 기준으로 합니다. 앞/뒤 윤거 1.628/1.637 m는 [공식 기술자료](https://www.hyundai.news/newsroom/dam/eu/models/20210630_ioniq_5_press_kit/hyundai-ioniq-5-technical-data-0621.pdf)의 20인치 사양입니다. 물리 추정기는 앱과 같은 축거 2.9 m를 사용합니다. 수치 등급은 [근거표](../근거_EVIDENCE.md) §3에서 확인할 수 있습니다.

기본 케이크 비교의 모델 결과는 A 최대 0.417348 G·손상도 100 %·95.4667초, B 최대 0.117259 G·손상도 0 %·133.5167초입니다. 손상도는 한계 초과분 누적의 단순 모델이며, 실차 시험 결과나 안전 보증이 아닙니다. 규칙은 [SPEC](SPEC.md), 구현 구성은 [IMPLEMENTATION](IMPLEMENTATION.md)에 정리했습니다.

Poly Haven 에셋은 CC0, Three.js는 MIT입니다. 차량은 설명용 재현 에셋으로 공식 CAD가 아닙니다. 참조 사진의 저작권과 차종 명칭·디자인은 현대자동차에 있으며 사진에 CC0나 새로운 사용허락을 부여하지 않습니다. VIP 생성 에셋은 프로젝트의 생성 참조와 제작 파이프라인으로 만들었습니다.
