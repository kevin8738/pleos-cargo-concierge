package ai.vipseat.cargoconcierge.core.voice

import ai.vipseat.cargoconcierge.core.model.CargoKind
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.core.model.CargoSpot

sealed interface VoiceIntent {
    /**
     * [preset] carries the user's own word for the item; [isNew] = no known category, registered as 일반 짐;
     * [spot] is the place named in the sentence ("트렁크에", "뒷자리 오른쪽에"), null when none was said.
     */
    data class LoadPreset(val preset: CargoPreset, val matchedAlias: String, val isNew: Boolean = false, val spot: CargoSpot? = null) : VoiceIntent {
        val kind: CargoKind get() = preset.kind
    }
    data object EndTrip : VoiceIntent
    data object OpenSheet : VoiceIntent
    data class Unknown(val text: String) : VoiceIntent
}

/**
 * Maps a Korean utterance to an intent. Three tiers, deterministic, no network — the seam where
 * Gleo Interface / Pleos STT plug in:
 *  1. a VIP the user used before ("도자기 실었어" after registering 도자기) wins outright,
 *  2. a category alias ("떡볶이" → 뜨거운 음식) sets the category; the spoken noun stays the VIP's name,
 *  3. any "<noun> 실었어/탔어/샀어…" with an unknown noun registers it as 일반 짐 (0.20 G) — the user can
 *     reclassify on screen. Only sentences with no loading verb and no alias are Unknown.
 * A place phrase anywhere in the sentence ("트렁크에", "조수석 뒤에", "뒷좌석 가운데") becomes the spot and is
 * removed before the noun is read.
 */
object VoiceIntentParser {
    private val endPhrases = listOf("종료", "그만", "끝났어", "내렸어", "꺼줘", "해제")
    private val openPhrases = listOf("뭐 실었", "뭘 실었", "프리셋", "메뉴", "VIP 목록", "브이아이피")
    private val loadVerbs = listOf("실었", "실어", "샀", "사왔", "사 왔", "봤", "있어", "있음", "탔", "태웠", "태워", "담았", "넣었", "놨", "놓았", "올렸", "가져왔", "들고")

    /** Aliases that are categories or verbs rather than things one would call the VIP by. */
    private val notItemNames = setOf("뜨거운", "포장", "배달", "장", "장봤어", "장 봤어", "깨지기", "반려", "전자기기", "악기", "신선식품", "식료품", "마트", "음식", "음료", "물건", "짐")

    /** Place phrases, longest-first alternatives, each followed by an optional particle. */
    private val spotRx = Regex(
        "(?:운전석\\s*뒤|조수석\\s*뒤|(?:뒷자리|뒷좌석|뒷\\s*좌석|뒤)\\s*(?:왼쪽|좌측|가운데|중앙|오른쪽|우측)|뒷자리|뒷좌석|뒤에|트렁크|짐칸|조수석|앞자리|옆자리|앞좌석)(?:에다가|에다|에|엔)?",
    )
    private val nounRx = Regex("^(.+?)\\s*(?:을|를)?\\s*(?:" + loadVerbs.joinToString("|") { Regex.escape(it) } + ")")
    private val counterRx = Regex("\\s*(?:한|두|세|네|다섯|여섯|\\d+)\\s*(?:개|잔|병|상자|박스|봉지|마리|통|송이|다발|판|조각|세트)?$")

    fun parse(raw: String, recents: List<CargoPreset> = emptyList()): VoiceIntent {
        val text = normalize(raw)
        if (text.isEmpty()) return VoiceIntent.Unknown(raw)
        if (endPhrases.any { text.contains(normalize(it)) }) return VoiceIntent.EndTrip

        val (spot, rest) = extractSpot(raw)

        // 1. A VIP the user already named — longest name wins.
        recents.filter { it.displayName.length >= 2 && text.contains(normalize(it.displayName)) }
            .maxByOrNull { it.displayName.length }
            ?.let { return VoiceIntent.LoadPreset(it, it.displayName, spot = spot) }

        val noun = extractNoun(rest, recents.map { normalize(it.displayName) })

        // 2. Category alias — longest alias wins so "조각케이크" beats "케이크" and "장바구니" beats "장".
        val best = CargoPreset.ALL
            .flatMap { p -> p.voiceAliases.map { alias -> p to normalize(alias) } }
            .filter { (_, alias) -> alias.length >= 2 && text.contains(alias) || alias.length == 1 && singleCharMatch(text, alias) }
            .maxByOrNull { (_, alias) -> alias.length }
        if (best != null) {
            val (template, alias) = best
            val name = when {
                noun != null && noun.length in 2..12 -> noun
                alias.length >= 2 && alias !in notItemNames -> alias
                else -> template.displayName
            }
            return VoiceIntent.LoadPreset(CargoPreset.named(name, template.kind), alias, spot = spot)
        }

        // 3. Unknown noun with a loading verb → 일반 짐.
        if (noun != null && noun.length in 1..12) {
            return VoiceIntent.LoadPreset(CargoPreset.named(noun, CargoKind.GENERAL), noun, isNew = true, spot = spot)
        }
        if (openPhrases.any { text.contains(normalize(it)) }) return VoiceIntent.OpenSheet
        return VoiceIntent.Unknown(raw)
    }

    /**
     * "트렁크에 수박 실었어" → (TRUNK, "수박 실었어"). A bare "뒷자리/뒷좌석" means the kerb-side rear seat
     * (REAR_RIGHT); "조수석 뒤" is REAR_RIGHT, "운전석 뒤" is REAR_LEFT. Returns null when no place was said.
     */
    fun extractSpot(raw: String): Pair<CargoSpot?, String> {
        val m = spotRx.find(raw) ?: return null to raw
        val phrase = normalize(m.value)
        val spot = when {
            phrase.startsWith("트렁크") || phrase.startsWith("짐칸") -> CargoSpot.TRUNK
            phrase.startsWith("조수석뒤") -> CargoSpot.REAR_RIGHT
            phrase.startsWith("운전석뒤") -> CargoSpot.REAR_LEFT
            phrase.startsWith("조수석") || phrase.startsWith("앞자리") || phrase.startsWith("옆자리") || phrase.startsWith("앞좌석") -> CargoSpot.FRONT_PASSENGER
            phrase.contains("왼쪽") || phrase.contains("좌측") -> CargoSpot.REAR_LEFT
            phrase.contains("가운데") || phrase.contains("중앙") -> CargoSpot.REAR_CENTER
            else -> CargoSpot.REAR_RIGHT
        }
        return spot to raw.removeRange(m.range)
    }

    private val knownWords: Set<String> by lazy { CargoPreset.ALL.flatMap { it.voiceAliases }.map { normalize(it) }.toSet() }

    /**
     * "도자기 두 개 실었어" → "도자기". Null when the sentence has no loading verb.
     * Object particles 을/를 are always dropped; subject particles 이/가/은/는/도 only when the word
     * without them is a known alias or a recent VIP ("강아지가 탔어" → 강아지, but "떡볶이 실었어" keeps 떡볶이).
     */
    fun extractNoun(raw: String, extraKnown: List<String> = emptyList()): String? {
        val s = raw.trim().replace(Regex("[.,!?~]"), "").replace(Regex("\\s+"), " ")
        val m = nounRx.find(s) ?: return null
        var n = m.groupValues[1].trim()
        n = n.replace(counterRx, "").trim()
        n = n.removeSuffix("을").removeSuffix("를").trim()
        if (n.length >= 3 && n.last() in "이가은는도") {
            val stem = n.dropLast(1)
            if (normalize(stem) in knownWords || normalize(stem) in extraKnown) n = stem
        }
        if (n.isEmpty() || loadVerbs.any { n.contains(it) }) return null
        return n
    }

    /** One-syllable aliases ("장") only count when followed by a loading verb. */
    private fun singleCharMatch(text: String, alias: String): Boolean =
        loadVerbs.any { verb -> text.contains(alias + normalize(verb)) }

    private fun normalize(s: String): String = s.lowercase().replace(Regex("[\\s.,!?~]"), "")
}
