# VIP 조수석 — 수치 근거표 (Evidence Sheet)

작성 2026-10-26 · 구현 범위 대조 갱신 2026-10-29 · 대상: 앱·기획서·시안·영상에 쓰인 모든 숫자. 코드의 한계값은 `app/core/.../model/CargoPreset.kt`에 있고, 이 표의 유도식은 `core/physics/CargoPhysics.kt`와 `CargoLimitRationaleTest`가 그대로 계산해 고정합니다(값을 바꾸면 어느 근거가 깨지는지 테스트가 말해 줍니다).

**근거 등급**
- **A** 법규·표준·제조사 공식 자료·직접 실측
- **B** 동료심사 문헌·공공기관 지침(언론 보도를 거친 경우 표기)
- **C** 물리 유도(식과 입력값을 함께 적음)
- **D** 팀 가정 — 예정된 실측·플랫폼 확인 결과가 확보된 항목만 갱신. 일정 경과를 확인 완료로 취급하지 않음

**적용 범위.** A/B는 인용한 자료·측정 자체의 근거 등급이지 앱의 보호 효과에 대한 인증이 아닙니다. 분류별 한계 G는 문헌·단순 물리식을 참고해 선택한 초기 설계값이며 물건의 실측 파손·넘침 한계로 확정하지 않았습니다. 공조 설정값은 상자 내부·식품 온도가 아닙니다. 실제 케이크·포장·좌석 조건의 검증 결과가 없으므로 파손 감소율이나 보관 시간 연장을 주장하지 않습니다.

**현재 구현과 계획의 구분(2026-10-29 소스 대조).** `CargoPreset.kt`의 분류별 값·공조 요청, `CargoStateMachine.kt`의 도어 제안·관성 코칭·정상 도착, `PleosVehicle.kt`의 제어·저장값 복원을 확인했습니다. 열선 30분 감열·45분 종료, 수동 공조 변경 시 제어 소유권 양도, 프로세스 종료 후 세션 복구, 지오펜스 등록·연결, 리포트 영구 저장·공유는 확인한 현재 실행 코드에서 완료 근거를 찾지 못했으므로 출시 전 계획으로 구분합니다. 추정기 차종 자동 선택·가속도 센서 융합·조향각 미제공 시 방위각 대체도 향후 항목입니다. 내비 제한속도 이벤트는 현재 코칭 조건에 반영하지 않습니다.

**검증의 종류.** 기존 릴리스 기록의 앱 단위 테스트 73건은 로직 검증, 공식 AVD 실행은 해당 환경의 SDK 연동 확인입니다. 브라우저 시뮬레이션 36개 검사와 A/B 결과 일치는 기능·회귀 검증이며 실차 시험이나 실제 케이크 파손률의 증거가 아닙니다. 화면 시안 S1~S5는 목표 UX이고 S6는 현재 에뮬레이터 실행 화면이므로 완성 범위를 구별합니다.

---

## 1. 관성 한계 G (보호 분류 9종)

### 1.1 공통 참조값

| 참조값 | 내용 | 등급 | 출처 |
|---|---|---|---|
| 서 있는 승객 준정적 횡가속 | 불편 없는 한계 ≈ 0.10 g, 약 0.12 g는 불편(British Railways 연구) | B | Powell & Palacín, *Urban Rail Transit* 1 (2015), doi:10.1007/s40864-015-0012-y |
| 버스 승객 횡가속 불편 등급 | 평균 횡가속 1.5 / 1.75 / 2.0 m/s² = 불편 / 매우 불편 / 극도로 불편(26명+11명, 착석·기댐·서 있음; 서 있는 승객이 가장 불편) | B | Nguyen, NguyenDinh, Lechner, Wong, "Insight into the lateral ride discomfort thresholds of young-adult bus passengers at multiple postures: Case of Singapore" |
| 착석·벨트 승객(승용차, 시험주행로) | 2 m/s²는 자주 "너무 느림", 3 m/s²가 균형, 4 m/s²는 "너무 빠름"(28명, 곡선 5개) | B | Peng et al., *Transportation Research Part F* 122, 103760 (2026) |
| 실도로 승객 생리반응 | 예측 가능한 선회에서 4.20 m/s²까지 허용; 2.37~2.73 m/s² 구간에서 **저크 5.14 m/s³ 초과 시 불편 증가** | B | Wang, Zhou, Rong, Dong, *Transportation Research Record* (2026) |
| 도로 설계 최소 곡선반경 | 설계속도 30 km/h → 30 m, 20 km/h → 15 m, 40 km/h → 50~60 m(최대 편경사 6~8%); 도시지역 최대 편경사 6% | A | 도로의 구조·시설 기준에 관한 규칙 제19조·제21조 |
| 설계 횡마찰계수의 성격 | f_max는 타이어 마찰 한계가 아니라 **운전자 쾌적 임계**에 기반한 수요값 | B | FHWA-HRT-17-098 (Horizontal curve design) |

유도(C): 설계속도로 최소반경을 돌 때 횡가속 v²/R = 0.236 g(30 km/h·30 m), 0.210 g(20 km/h·15 m). 편경사 6%를 빼면 쾌적 기준 마찰 수요 f ≈ 0.15~0.18. 도심 교차로 우회전(30 km/h·R 20 m)은 0.354 g. 이 예시의 30 km/h·R 20 m 선회에서는 모든 초기 한계값(0.12~0.25 g)을 넘지만, 더 큰 반경·낮은 속도에서는 결과가 달라집니다. 모든 교차로나 차선 변경을 같은 G로 일반화하지 않습니다. 잦은 알림의 가능성을 검증하기 위해 발동 횟수를 실측할 계획입니다(기획서 5항).

### 1.2 분류별 값

| 분류 | 값 | 유도·근거 | 등급 | 실측·확인 |
|---|---|---|---|---|
| 음료·국물, 뜨거운 음식 | **0.12 g** | 가속 중인 용기의 액면은 atan(a/g)만큼 기움(강체 가속 유체정역학). 지름 d, 여유 높이 h일 때 넘침 조건 **a/g = 2h/d**. 12 oz 컵(d≈9 cm, h 1 cm): 정적 0.22 g, 급작스러운 조향(계단 입력)은 감쇠가 작은 출렁임 모드를 최대 2배 과도응답시키므로 0.11 g. 넓은 포장 용기(d 16 cm, h 1 cm): 정적 **0.125 g**. 0.12 g는 두 값 사이·이하. 출렁임 고유진동수는 지름·수위로 정해지고 잡음(저크)이 넘침을 증폭한다는 실험 결과와 부합 | C + B + D(초기값 선택) | 커피 캐리어·국물 포장 실차 측정(11/1~11/8) |
| 깨지기 쉬운 물건 | **0.12 g** | 전도 조건 **a/g = (밑변/2)/무게중심 높이**. 화병 8×30 cm(무게중심 15 cm) → 0.27 g; 안전계수 2 → 0.13 g. 서 있는 승객이 불편을 느끼는 0.12 g와 같은 수준 | C + B + D(초기값 선택) | 도자기·액자 미끄러짐·전도 시작 G |
| 케이크·디저트, 신선식품(장보기) | **0.15 g** | 상자 미끄러짐 조건 **a = μs·g**. 골판지–시트 직물 정지마찰계수의 공개 측정값은 없음(SAE 2009-01-1672는 의복–시트 마찰) → 하한 0.3 가정 → 0.30 g, 안전계수 2 → 0.15 g. 버스 승객 "불편" 1.5 m/s² = 0.153 g와 일치. 케이크의 실제 파손은 상자 미끄러짐이 아니라 크림·단 밀림이므로 실측으로만 확정 가능 | C + D | **기울기 시험으로 μs 측정(미끄러지기 시작하는 각도의 tan)** + 실제 생크림 케이크의 밀림 시작 G |
| 일반 짐, 반려동물, 꽃·식물 | **0.20 g** | 버스 승객 불편 평가 2.0 m/s² = 0.204 g를 참고한 초기값. 사람 연구가 꽃·반려동물·일반 짐의 내성을 검증한 것은 아님 | B + D | 발동 횟수·품목별 적합성 검증 |
| 전자기기·악기 | **0.25 g** | 포장된 강체의 미끄러짐 한계를 0.30 g로 가정하고 그 아래를 선택. 모든 기기·악기의 충격 내성이 같거나 미끄러짐만 문제라고 확정할 근거는 없음 | C + D | 포장·품목별 적합성 검증 |
| 급커브 속도 계산 예시(운전자에게 지시하지 않음) | 20 / 25 / 30 km/h | v = √(a·g·R), R = 30 m(제19조 30 km/h 최소반경) → 0.12 g 20 km/h, 0.15 g 25, 0.20 g 30(5 km/h 반올림) | A(반경 참고) + C + D(실제 도로 대신 R 30 m 가정) | 앱 코칭은 구체 속도를 지시하지 않음. 실제 내비 곡률 제공은 미확인 |

### 1.3 자리(조수석·뒷좌석·트렁크)와 한계값

현재 모델은 같은 차체의 차속·조향으로 얻은 G를 자리와 무관하게 사용합니다. 이는 단순화된 설계이며 좌석별 실제 진동·수직 충격·회전 중심 차이가 없다는 뜻은 아닙니다. 자리는 공조·시트 요청 영역을 정합니다. 후석의 실제 열선·통풍·독립 온도 존은 차종 제공 범위에 따라 결과를 표시하고, 트렁크는 공조·시트 요청을 미지원으로 처리합니다. 이미 사람이 앉은 자리는 열선·통풍 요청을 제외합니다. 에뮬레이터의 좌석 속성은 10/26 `dumpsys car_service`로 확인했으나 실차 좌석별 착좌·제어 제공 범위는 아직 별도 확인 대상입니다.

---

## 2. 공조·시트 플랜

| 항목 | 값 | 근거 | 등급 | 확인 |
|---|---|---|---|---|
| 케이크 조수석 설정 온도 | 18 ℃ | 공식 Pleos 에뮬레이터 `HVAC_TEMPERATURE_SET` 설정 범위 **15.0~30.0 ℃, 0.5 ℃ 단위**(configArray [150, 300, 5, 580, 880, 10], `dumpsys car_service` 2026-10-26). 18 ℃는 하한 근처이면서 운전석으로 냉기가 넘어가도 견딜 수 있는 값. "쇼케이스"는 비유이며 냉장(5 ℃ 이하)이 아님을 앱·기획서에 명시 | A(에뮬레이터 범위) · D(18℃ 선택·실차 적용) | 실차 설정 범위·좌우 차 제한은 확인 필요 |
| 생크림 등 냉장 식품의 시간 | 리포트의 이동 시간 표시, "곧장 귀가" 안내 | 식약처: 식중독균 증식 위험구간 5~60 ℃, 냉장 5 ℃ 이하·냉동 −18 ℃ 이하, 상온에 둔 조리식품은 **가급적 2시간 이내 섭취**(언론 보도 경유) · FDA Food Code TPHC: 41 °F 이하로 시작한 식품은 최대 4시간 | B · A | 식약처 공식 페이지 링크 확보 |
| 뜨거운 음식 열선 기본 2단 / 감열·종료 타이머 계획 | 현재 2단 / 계획 30·45분 | 식약처 온장 기준 60 ℃ 이상은 시트 열선으로 불가 → "식는 속도를 늦추는 보조"로만 표현. 열선 시트 표면 온도 보고 40~48 ℃, 화상 전문의의 "40 ℃ 이하" 권고, 장시간 노출 저온화상 사례(8시간, 2도 화상) → 단계·시간 상한을 계획함. 30/45분 수치 자체는 가정이며 현재 실행 코드에서 타이머 완료 근거는 확인하지 못함. 3단 사용자 선택·겨울/여름 자동 분기도 계획과 구분 | B(문헌의 온도) · D(단계·시간) | 포장·좌석 조건·상자 내부 온도와 타이머 구현 검증 필요 |
| 꽃 22 ℃ · 하단 간접 송풍 · 열선 OFF | 22 ℃ | 절화 수명의 최대 요인은 온도, 저장온도가 오를수록 수명 감소, 유통 중 27 ℃ 초과 사례(UC Davis Postharvest). 관리 지침: 송풍구·히터·직사광선·외풍 회피, 과일 에틸렌 회피(UC ANR, Chicago Botanic Garden). 22 ℃는 운전자가 수용할 하한 가정 | B · D | — |
| 장보기 뒷좌석 존 18 ℃ | 18 ℃ | 공조 18℃는 냉장 기준을 충족하지 않음. 열 노출 완화 가능성과 도착 챙김을 목표로 한 요청값이며 상자 내부 온도 효과는 미측정 | B(배경 지침) + D(18℃ 선택) | 후석 독립 존 제공·상자 내부 온도 검증 |
| 전자기기 열선 OFF | — | Apple: 사용 0~35 ℃, 보관 −20~45 ℃, 주차 차량 내부는 범위 초과 가능. 열선 표면 40 ℃+ 보고 | A · B | — |
| 반려동물 22 ℃ · 간접풍 · 열선/통풍 OFF | 22 ℃ | 미국 9 CFR §3.19(d)의 45~85 °F(7.2~29.5 ℃) 범위는 특정 취급 환경의 자료로 참고했으며 국내 개인 차량의 적정 온도 기준으로 적용하지 않음. 22℃·간접풍·열선/통풍 OFF는 설계 가정이며 동물별 적합성 검증이 필요 | A(인용 자료) · D(앱 설정) | 조문 적용 범위·동물별 조건 확인, 일반적 건강 보장 표현 금지 |

---

## 3. 관성 추정기·정책 상수

### 시뮬레이션 외형 제원 (v2.3, 2026-10-29)

외형 참조는 현대자동차가 공개한 **2021년형 페이스리프트 이전 IONIQ 5의 실제 사진**입니다. [2021 공식 이미지 모음](https://www.hyundai.news/eu/models/electrified/ioniq-5/images.html)에서 외형·실내·20인치 휠 사진을 선정했고, 제작 소스의 `web/assets/refs/ioniq5_official/provenance.json`에 원본 URL·해시·연식을 기록했습니다. 제조사 사진의 저작권은 현대자동차에 있으며, 자체 생성 사진이나 CC0 자료로 표기하지 않습니다.

| 외형 기준 | 값 | 근거·등급 |
|---|---|---|
| 전장 / 미러 제외 차체 전폭 / 전고 | 4.635 / 1.890 / 1.605 m | **A** — [현대자동차 2021 모델 제원](https://www.hyundai.com/kr/ko/brand/brandstory/model/ioniq-history/2021-ioniq5) |
| 시각 모델의 축거 | 3.000 m | **A** — 같은 공식 제원. 관성 추정의 2.9 m 가정과 별개 |
| 20인치 앞 / 뒤 윤거 | 1.628 / 1.637 m | **A** — [2021년 6월 공식 기술자료](https://www.hyundai.news/newsroom/dam/eu/models/20210630_ioniq_5_press_kit/hyundai-ioniq-5-technical-data-0621.pdf) 2쪽 |
| 타이어 규격 / 최저지상고 | 255/45 R20 / 0.160 m | **A** — 같은 2021년 6월 공식 기술자료 |
| 타이어 공칭 무하중 반지름 | 0.36875 m | **C** — 림 반지름 `20 × 0.0254 / 2` + 사이드월 높이 `0.255 × 0.45`. 하중을 받은 실차 반지름 측정값이 아님 |
| 실내 두 화면의 활성 영역 | 각각 0.291 × 0.111 m(대각 약 12.3인치) | **D** — 공식 실내 사진의 비례를 해석한 제작값. 활성 영역을 직접 실측한 제조사 치수가 아님 |
| 패널 경계·실내 부품 위치·미러 포함 폭 | 사진 기반 제작값·별도 메시 측정 | **D** — 사진을 해석해 맞춘 모델링 값. 제조사 CAD 치수로 취급하지 않음 |

공식 사진의 배경 제거 → TRELLIS.2 1536 형상 기준 생성 → 공식 제원·사진에 맞춘 매끈한 규칙 표면 재구성으로 제작합니다. TRELLIS 원본의 기여는 제한된 저주파 후드·지붕 곡률이며, 원본 메시 삼각형·UV 텍스처를 그대로 유지하지 않고 파트별 PBR 재질을 적용합니다. 실제 기여 범위는 제작 스크립트와 최종 에셋 메타에 기록합니다. 흰 베젤 듀얼 화면·열린 바닥·별도 Universal Island·네 점 핸들 실내와 20인치 다이아몬드 격자 휠은 별도 제작 에셋입니다. 공식 CAD나 제조사 인증 모델은 아닙니다. 소스·패킹본 각각 전체 코스 기능 검사 36개가 통과했고 오류는 0이며, 최종 압축본의 중립 조명 7뷰·시뮬레이터 5뷰를 확인했습니다. 패킹본과 소스의 JS·에셋 17개 일치도 확인했습니다.

위 제원은 시각 모델의 기준입니다. 시뮬레이션 관성 추정기는 기존 축거 **2.9 m** 가정을 유지합니다. 브라우저 자동 주행·손상 모델과 APK의 실제 신호 추정·코칭 실행은 서로 다른 검증 대상입니다. 소스·패킹본 회귀 검증에서 A/B 최대 G·손상도·랩타임·R30 속도·코칭·NEAR·OVER의 7개 결과가 기존값과 일치했습니다. 이는 계산 결과의 재현성 확인이며 아이오닉 5 실차 시험값은 아닙니다.

| 상수 | 값 | 근거 | 등급 | 확인 |
|---|---|---|---|---|
| 축거 | 2.9 m | 기존 그랜저 GN7 2,895 mm 참고값을 반올림한 기본 파라미터. 현재 코드에서 차종 자동 선택은 확인하지 못함 | D(모델에 2.9 m 적용) | 실차별 값·자동 선택 확인 필요 |
| 조향비 | 15:1 | 승용차 전체 조향비 12~20:1, 세단은 13~16:1이 일반적 → 중간값. 차종별 공식값은 미공개 | B · D | 차종별 값 확인 필요 |
| 횡가속 모델 | 기구학적 자전거 모델 κ = tan(δ/조향비)/L, a = v²κ | 기구학적 자전거 모델; 타이어 슬립·노면·수직 충격을 포함하지 않음. 실제 오차의 방향·크기는 차종·조건에 따라 검증 필요 | C + D(입력 파라미터) | 실측 센서와 오차·지연·유효 범위 기록 |
| 긴급 억제 | 0.6 g | UN R13/R13-H 관련 비상제동신호(ESS) 자료의 6 m/s² ≈ 0.61 g를 참고한 앱의 음성 억제값. 앱에 규정상 요구된 수치이거나 모든 회피 상황을 식별하는 기준은 아님 | A(참고 자료) + D(앱 정책) | 적용 조문·실차 조건 검증 필요 |
| 경고 80%, 관성 초과 음성 3회/세션, 간격 8 s, 저속 조향 15 km/h 미만 제외 | — | 설계 가정. WARN/OVER 진입 때 효과음, 한계 초과 때 음성. 내비·도착·상태 안내는 3회 상한과 별도이고 긴급 억제도 관성 음성 정책이므로 모든 소리를 억제한다고 표현하지 않음 | D | 도심 30분 발동 횟수 실측 |
| 프리셋 시트 10 s, 최근 착좌 30 s 억제, 3회 시간 초과 제안 억제, 배지 유지 3 s | — | 설계 가정. 주행 화면은 정적 배지를 우선함. NHTSA·Pleos 가이드는 설계 참고이며 실제 시선 추적 시험이나 앱마켓 적합성 통과를 뜻하지 않음. 프리셋 선택 시 무응답 누적이 초기화되며 설정 UI의 별도 복구 연결은 후속 확인 | D(정책) · A(가이드 참고) | 수락률 25% 선행지표 |

---

## 4. 배경·시장 수치 — 기존 조사 기록과 현재 사용 구분

현재 기획서는 아래 과거 판매·통근 수치를 시장 입증에 사용하지 않습니다. 2027년 모수·설치율·활성률은 모두 D등급 시나리오이며 실제 판매 전망·확정 고객으로 표현하지 않습니다. 아래 기록은 기존 조사 이력으로 보존하며 새로 인용할 때 원문·발표일·대상 모델을 다시 확인합니다.

| 수치 | 기존 기록 | 등급 | 출처 |
|---|---|---|---|
| 출·퇴근 평균 73.9분(2024-06 기준), 수도권 83.2분(2023-06 기준) | 기획서 표기대로 연도가 다름을 명시 | A | 통계청 통근 근로자 이동 특성 분석(2024·2023) |
| 더 뉴 그랜저 2026-05 출시, Pleos Connect 국내 최초 탑재, 2030년 2,000만 대 | 확인 | A | 현대차그룹 뉴스룸 |
| 그랜저 월 판매 6~9월 5,931~10,062대 | **확인**: 6월 10,062 · 7월 8,532 · 8월 5,931 · 9월 8,898(현대차 월별 판매 발표). 넉 달 모두 현대차 최다 판매 모델, 6월 국산 승용 신차 등록 1위(카이즈유 9,741대 > 쏘렌토 8,981대) | A | 현대차그룹 뉴스룸 월간 판매, 한국경제 2026-07-01, 스타뉴스 2026-08-03, 인사이트 2026-10-02 |
| 2026년 말 7~10만 대, 2027년 28만 대, 70%, 설치율·활성률·D30 | 자체 가정(기획서에 "자체 추정/가정"으로 표기됨) | D | — |

---

## 5. 미확인·확인 예정

1. 실차 HVAC 설정 범위·좌우 제한·독립 존·읽기/복원 지원 — 설명회 날짜 경과와 무관하게 아직 결과 확인 필요
2. 차종별 조향비·축거 제공, 차속·조향의 갱신 주기·손실 처리, 가속도 센서 접근 — 미확인
3. UN R13-H 통합본의 ESS 조문 번호 — 문서 확인
4. 식약처 "2시간·5~60 ℃" 공식 페이지 링크(현재 언론 보도 경유) — 확보 후 교체
5. 골판지–시트 정지마찰계수(정차 기울기 시험), 생크림 케이크 밀림·커피/국물 넘침 조건(시험대), 상자 내부 온도 — 11/1~11/8 목표, 결과 미확보
6. 모든 제어·복원 경로의 P단 재검사, 수동 변경 우선, 복원 실패 재시도·프로세스 복구·열선 타이머 — 출시 전 보완 및 검증
7. 지오펜스·부팅 이력·Gleo·리포트 공유·사용성 분석 수집 — 현재 제출본 완료 기능과 구분

---

## 6. 출처 목록

- 도로의 구조·시설 기준에 관한 규칙 제19조(평면곡선 반지름)·제21조(편경사) — 국가법령정보센터
- FHWA-HRT-17-098, *Horizontal Curve Design* (side-friction demand is comfort-based) — https://www.fhwa.dot.gov/publications/research/safety/17098/004.cfm
- J. P. Powell, R. Palacín, "Passenger Stability Within Moving Railway Vehicles: Limits on Maximum Longitudinal Acceleration," *Urban Rail Transit* 1 (2015) — https://doi.org/10.1007/s40864-015-0012-y
- T. Nguyen, N. NguyenDinh, B. Lechner, Y. D. Wong, "Insight into the lateral ride discomfort thresholds of young-adult bus passengers at multiple postures: Case of Singapore" — https://nanyangtechnologicaluniv.demo.elsevierpure.com/en/publications/insight-into-the-lateral-ride-discomfort-thresholds-of-young-adul
- C. Peng et al., "Optimal lateral acceleration for different levels of automated driving," *Transportation Research Part F* 122, 103760 (2026) — https://trid.trb.org/View/2739352
- J. Wang, C. Zhou, J. Rong, Z. Dong, "Real-World Road Experiment-Based Design of Human-Aligned Autonomous Vehicle Motion from Psychophysiological Responses," *Transportation Research Record* (2026) — https://trid.trb.org/View/2772588
- H. C. Mayer, R. Krechetnikov, "Walking with coffee: Why does it spill?" *Phys. Rev. E* 85, 046117 (2012) — https://physics.aps.org/articles/v5/s64
- 식품의약품안전처 식중독 예방 권고(위험구간 5~60 ℃, 상온 2시간) — 보도: https://biz.sbs.co.kr/amp/article/20000163986 , https://www.ddaily.co.kr/page/view/2024080210211845240
- FDA Food Code, Time as a Public Health Control(4시간/6시간) — https://www.lfchd.org/wp-content/uploads/2022/11/Time-as-a-Public-Health-Control-11.14.2022.pdf
- 9 CFR §3.19(d) (dogs and cats, 45–85 °F) — https://www.law.cornell.edu/cfr/text/9/3.19
- 도로교통법 제39조 제5항(동물을 안고 운전 금지) — 해설: https://www.dailyvet.co.kr/news/163796
- Apple, "If your iPhone or iPad gets too hot or too cold" (0–35 ℃ 사용, −20–45 ℃ 보관) — https://support.apple.com/en-in/HT201678
- UC Davis Postharvest Technology Center, cut flowers (temperature, vase life) — https://postharvest.ucdavis.edu/node/6171 ; UC ANR cut-flower care — https://www.ccfruitandnuts.ucanr.edu/sites/default/files/2017-05/262309.pdf ; Chicago Botanic Garden — https://www.chicagobotanic.org/plantinfo/february_cut_flower_care
- 열선 시트 화상: Ketterer & Hogrefe, *J Emerg Med* (2019); Sever et al., *Eplasty* (2010) — https://pmc.ncbi.nlm.nih.gov/articles/PMC2852238 ; Greenhalgh 인용(≤40 ℃ 권고) — https://www.auto123.com/en/news/amp/burns-caused-by-heated-seats-a-hot-topic-for-the-nhtsa/8812/
- NHTSA Visual-Manual Driver Distraction Guidelines (2013) — https://www.nhtsa.gov/sites/nhtsa.gov/files/811820.pdf
- Pleos Playground, "Design Considering Driving Safety" — https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline
- UN ECE/TRANS/WP.29/GRRF/2009/19 (ESS 작동 하한 6 m/s², R13·R13-H 조화) — https://digitallibrary.un.org/record/663727
- 그랜저 GN7 제원(축거 2,895 mm) — https://www.carwiki.co.kr/model/11874_2027/더_뉴_그랜저_GN7
- 조향비 범위: D. O. Neacșu, *Automotive Power Systems* (2020) 12–20:1 — https://taylorandfrancis.com/knowledge/Engineering_and_technology/Engineering_support_and_special_topics/Steering_ratio
- 현대차그룹 뉴스룸 월간 판매(2026-06/07/08) — https://www.hyundaimotorgroup.com/ko/news/hyundai-motor-company-june-2026-sales , …-july-2026-sales , …-august-2026-sales ; 9월: https://www.insight.co.kr/news/576191
- Pleos Connect 첫 탑재·2,000만 대 — https://www.hyundaimotorgroup.com/ko/story/CONT0000000000210298
- 통계청 통근 근로자 이동 특성(2024-06 기준 73.9분; 2023-06 기준 수도권 83.2분) — https://www.newsis.com/view/NISX20231220_0002565890 , https://newsseoul.co.kr/news/view/1065591020877020
- Pleos 에뮬레이터 실측: `adb shell dumpsys car_service` → `HVAC_TEMPERATURE_SET` areaId 1/4 minFloat 15.0 maxFloat 30.0, configArray [150, 300, 5, 580, 880, 10] (2026-10-26)
