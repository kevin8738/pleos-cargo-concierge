package ai.vipseat.cargoconcierge.core.model

/**
 * Protection *categories*. A VIP is any item the user names ("도자기", "강아지", "수박"); the category
 * decides the acceleration budget and the cabin plan. The first five double as ready-made suggestions.
 */
enum class CargoKind(val label: String) {
    CAKE("케이크·디저트"),
    HOT_FOOD("뜨거운 음식"),
    FLOWERS("꽃·식물"),
    GROCERIES("신선식품"),
    COFFEE("음료·국물"),
    FRAGILE("깨지기 쉬운 물건"),
    ELECTRONICS("전자기기·악기"),
    PET("반려동물"),
    GENERAL("일반 짐"),
}

/** HVAC zones we may touch. FRONT_PASSENGER maps to SeatArea(ROW_1_RIGHT); REAR to row 2. */
enum class CabinZone { FRONT_PASSENGER, REAR }

/** How air should reach the cargo. */
enum class AirflowMode { DIRECT_FACE, INDIRECT_FLOOR, OFF, UNCHANGED }

/**
 * What a preset asks the cabin to do. Every field is optional: `null` / UNCHANGED means
 * "leave as is", so a preset never touches more of the car than it needs to.
 */
data class CabinPlan(
    val temperatureC: Double? = null,
    val fanLevel: Int? = null,
    val airflow: AirflowMode = AirflowMode.UNCHANGED,
    val seatHeaterLevel: Int? = null,
    val seatVentLevel: Int? = null,
    val rearZoneTemperatureC: Double? = null,
) {
    val isEmpty: Boolean
        get() = temperatureC == null && fanLevel == null && airflow == AirflowMode.UNCHANGED &&
            seatHeaterLevel == null && seatVentLevel == null && rearZoneTemperatureC == null

    /** True when the plan changes the VIP's seat or its HVAC zone — a person sitting down there overrides it. */
    val touchesSeat: Boolean
        get() = temperatureC != null || fanLevel != null || airflow != AirflowMode.UNCHANGED ||
            seatHeaterLevel != null || seatVentLevel != null
}

/**
 * A VIP: the item on the passenger seat ([displayName], free text), its protection category
 * ([kind]), the acceleration budget it tolerates and the cabin plan that keeps it comfortable.
 * Limits are in g (1 g = 9.80665 m/s²). Templates live in the companion; a user-named VIP is
 * `CargoPreset.named("도자기", CargoKind.FRAGILE)` — same budget and plan as its template.
 *
 * Where the numbers come from (full table with sources: `submission/근거_EVIDENCE.md`;
 * pinned by `CargoLimitRationaleTest`):
 *  - 0.12 g  liquids / fragile — quasi-static spill of a 1 cm-headspace tub (2h/d = 0.125 g), the
 *            transient spill of a 12 oz cup (0.11 g) and the level standing rail passengers call
 *            uncomfortable (~0.12 g).
 *  - 0.15 g  cake / groceries — half the sliding onset of a box on fabric (μs ≥ 0.3 → 0.30 g) and the
 *            1.5 m/s² that bus passengers rate "uncomfortable".
 *  - 0.20 g  general / pet / flowers — the 2.0 m/s² bus passengers rate "extremely uncomfortable";
 *            still below what belted car passengers find comfortable (~3 m/s²).
 *  - 0.25 g  electronics — rigid, usually bagged: only sliding matters (below 0.30 g).
 *  Cabin plans: 18 ℃ is near the lowest setting a cabin HVAC offers (not a fridge — the app says so);
 *  22 ℃ / indirect airflow keeps pets inside the 7–29 ℃ band of USDA 9 CFR §3.15/3.19 and flowers
 *  away from the vent draft that florist/extension guides warn about; seat heater OFF for electronics
 *  follows device ambient limits (Apple: operate 0–35 ℃). Positive heating requests are withheld by
 *  CabinCommandPolicy until thermal tests; no duration limit has been validated.
 *  All limits are hypotheses until the 11/1–11/8 measurements (slide/spill onset, box temperature).
 */
data class CargoPreset(
    val kind: CargoKind,
    val displayName: String,
    val emoji: String,
    val limitG: Double,
    val cabin: CabinPlan,
    val arrivalNoun: String,
    /** Short cabin summary without the G value — screens that show it already print the allowed G next to it. */
    val tagline: String,
    val voiceAliases: List<String>,
) {
    /** The category template this VIP was derived from. */
    val template: CargoPreset get() = of(kind)

    /** True for a VIP the user named (not one of the templates). */
    val isCustom: Boolean get() = displayName != template.displayName

    companion object {
        /** 0.15 g: half the box-sliding onset (μs 0.3) ≈ bus "uncomfortable" 1.5 m/s²; 18 ℃ = cabin minimum-ish, heater OFF. */
        val CAKE = CargoPreset(
            kind = CargoKind.CAKE,
            displayName = "케이크",
            emoji = "🎂",
            limitG = 0.15,
            cabin = CabinPlan(temperatureC = 18.0, fanLevel = 3, airflow = AirflowMode.DIRECT_FACE,
                seatHeaterLevel = 0, seatVentLevel = 0),
            arrivalNoun = "케이크",
            tagline = "조수석 18℃ · 열선 OFF",
            voiceAliases = listOf("케이크", "케익", "생크림", "조각케이크", "디저트", "마카롱", "타르트", "빙수", "아이스크림케이크"),
        )
        /** 0.12 g: a wide 1 cm-headspace soup tub spills statically at 0.125 g; proposed heater level 2 is withheld by the submission policy pending thermal validation. */
        val HOT_FOOD = CargoPreset(
            kind = CargoKind.HOT_FOOD,
            displayName = "뜨거운 음식",
            emoji = "🍲",
            limitG = 0.12,
            cabin = CabinPlan(fanLevel = 0, airflow = AirflowMode.OFF, seatHeaterLevel = 2, seatVentLevel = 0), // Requested concept only; CabinCommandPolicy withholds positive heating.
            arrivalNoun = "음식",
            tagline = "송풍 OFF 요청 · 보온 열선 검증 대기",
            voiceAliases = listOf("떡볶이", "치킨", "피자", "족발", "탕", "국물", "음식", "포장", "배달", "뜨거운", "찜", "보쌈", "짜장면", "탕수육", "국밥"),
        )
        /** 0.20 g: bouquets tolerate comfort-level motion; 22 ℃ + floor airflow = cool, no vent draft (temperature is the #1 postharvest factor). */
        val FLOWERS = CargoPreset(
            kind = CargoKind.FLOWERS,
            displayName = "꽃",
            emoji = "💐",
            limitG = 0.20,
            cabin = CabinPlan(temperatureC = 22.0, fanLevel = 2, airflow = AirflowMode.INDIRECT_FLOOR,
                seatHeaterLevel = 0),
            arrivalNoun = "꽃",
            tagline = "22℃ 간접 송풍",
            voiceAliases = listOf("꽃", "꽃다발", "화분", "식물", "부케", "화환", "난", "묘목", "다육이"),
        )
        /** 0.15 g: bags tip/slide like a box; rear zone 18 ℃ only shortens heat exposure — the 5–60 ℃ danger zone and the 2 h rule still apply. */
        val GROCERIES = CargoPreset(
            kind = CargoKind.GROCERIES,
            displayName = "장보기",
            emoji = "🛒",
            limitG = 0.15,
            cabin = CabinPlan(rearZoneTemperatureC = 18.0),
            arrivalNoun = "장바구니",
            tagline = "뒷좌석 존 쿨링",
            voiceAliases = listOf("장바구니", "장봤어", "장 봤어", "마트", "식료품", "아이스크림", "신선식품", "장", "냉동", "회", "초밥", "생선", "고기", "우유", "약"),
        )
        /** 0.12 g: between the transient (0.11 g) and static (0.22 g) spill thresholds of a 12 oz cup filled 1 cm below the rim. */
        val COFFEE = CargoPreset(
            kind = CargoKind.COFFEE,
            displayName = "커피",
            emoji = "☕",
            limitG = 0.12,
            cabin = CabinPlan(),
            arrivalNoun = "커피",
            tagline = "출렁임 가드",
            voiceAliases = listOf("커피", "아메리카노", "라떼", "음료", "테이크아웃", "버블티", "주스", "스무디", "국", "찌개", "수프", "탕국"),
        )
        /** 0.12 g: ≥2× margin under the tipping threshold of an 8 × 30 cm vase (0.27 g); standing passengers call 0.12 g uncomfortable. */
        val FRAGILE = CargoPreset(
            kind = CargoKind.FRAGILE,
            displayName = "깨지기 쉬운 물건",
            emoji = "🏺",
            limitG = 0.12,
            cabin = CabinPlan(),
            arrivalNoun = "물건",
            tagline = "흔들림 최소",
            voiceAliases = listOf("도자기", "유리", "그릇", "액자", "화병", "꽃병", "거울", "접시", "와인", "술병", "깨지기", "유리잔", "도기", "조명", "전구", "달걀", "계란"),
        )
        /** 0.25 g: rigid and bagged — only sliding (≥0.30 g) matters; heater OFF because device ambient limits end at 35 ℃. */
        val ELECTRONICS = CargoPreset(
            kind = CargoKind.ELECTRONICS,
            displayName = "전자기기",
            emoji = "💻",
            limitG = 0.25,
            cabin = CabinPlan(seatHeaterLevel = 0),
            arrivalNoun = "기기",
            tagline = "열선 OFF",
            voiceAliases = listOf("노트북", "카메라", "모니터", "컴퓨터", "기타", "피아노", "바이올린", "첼로", "드론", "태블릿", "전자기기", "악기", "스피커", "프린터"),
        )
        /** 0.20 g: comfort-level motion for a crated animal; 22 ℃ sits inside USDA 9 CFR 45–85 °F (7–29 ℃); no direct draft, heater/vent OFF. */
        val PET = CargoPreset(
            kind = CargoKind.PET,
            displayName = "반려동물",
            emoji = "🐾",
            limitG = 0.20,
            cabin = CabinPlan(temperatureC = 22.0, airflow = AirflowMode.INDIRECT_FLOOR, seatHeaterLevel = 0, seatVentLevel = 0),
            arrivalNoun = "반려동물",
            tagline = "22℃ 간접풍 · 열선 OFF",
            voiceAliases = listOf("강아지", "고양이", "반려동물", "댕댕이", "냥이", "햄스터", "토끼", "새장", "이동장", "켄넬", "반려"),
        )
        /** 0.20 g: the generic "unrestrained object" budget = 2.0 m/s² (bus passengers: extremely uncomfortable). */
        val GENERAL = CargoPreset(
            kind = CargoKind.GENERAL,
            displayName = "일반 짐",
            emoji = "📦",
            limitG = 0.20,
            cabin = CabinPlan(),
            arrivalNoun = "짐",
            tagline = "기본 가드",
            voiceAliases = listOf("짐", "택배", "상자", "박스", "가방", "물건", "귀중품", "서류", "캐리어"),
        )

        /** All category templates, in sheet order. */
        val ALL: List<CargoPreset> = listOf(CAKE, HOT_FOOD, FLOWERS, GROCERIES, COFFEE, FRAGILE, ELECTRONICS, PET, GENERAL)

        /** Shown before the user has any history. */
        val SUGGESTIONS: List<CargoPreset> = listOf(CAKE, HOT_FOOD, FLOWERS, GROCERIES, COFFEE)

        fun of(kind: CargoKind): CargoPreset = ALL.first { it.kind == kind }

        /** A VIP the user named: template budget and plan, their word everywhere it is spoken or shown. */
        fun named(name: String, kind: CargoKind): CargoPreset {
            val t = of(kind)
            val n = name.trim()
            return if (n.isEmpty() || n == t.displayName) t else t.copy(displayName = n, arrivalNoun = n, voiceAliases = listOf(n) + t.voiceAliases)
        }
    }
}
