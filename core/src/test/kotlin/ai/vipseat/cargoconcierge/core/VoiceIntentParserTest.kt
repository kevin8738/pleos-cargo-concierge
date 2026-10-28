package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.model.CargoKind
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.core.voice.VoiceIntent
import ai.vipseat.cargoconcierge.core.voice.VoiceIntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceIntentParserTest {
    private fun load(text: String, recents: List<CargoPreset> = emptyList()) = VoiceIntentParser.parse(text, recents) as VoiceIntent.LoadPreset
    private fun kind(text: String) = load(text).kind

    @Test
    fun `maps the contest phrases`() {
        assertEquals(CargoKind.CAKE, kind("케이크 실었어"))
        assertEquals(CargoKind.HOT_FOOD, kind("떡볶이 실었어"))
        assertEquals(CargoKind.FLOWERS, kind("꽃다발 샀어"))
        assertEquals(CargoKind.GROCERIES, kind("장 봤어"))
        assertEquals(CargoKind.COFFEE, kind("아메리카노 두 잔 있어"))
    }

    @Test
    fun `longest alias wins and noise is tolerated`() {
        assertEquals(CargoKind.CAKE, kind("  조각 케이크, 실었어!! "))
        assertEquals(CargoKind.GROCERIES, kind("마트에서 장바구니 가득"))
    }

    @Test
    fun `end and unknown intents`() {
        assertEquals(VoiceIntent.EndTrip, VoiceIntentParser.parse("VIP 모드 종료"))
        assertTrue(VoiceIntentParser.parse("오늘 날씨 어때") is VoiceIntent.Unknown)
        assertTrue(VoiceIntentParser.parse("") is VoiceIntent.Unknown)
    }

    @Test
    fun `the spoken noun becomes the VIP name, category from the alias`() {
        val i = load("조수석에 떡볶이 실었어")
        assertEquals(CargoKind.HOT_FOOD, i.kind); assertEquals("떡볶이", i.preset.displayName); assertTrue(!i.isNew)
        assertEquals("도자기", load("도자기 실었어").preset.displayName); assertEquals(CargoKind.FRAGILE, kind("도자기 실었어"))
        assertEquals(CargoKind.PET, kind("강아지 탔어")); assertEquals("강아지", load("강아지가 탔어").preset.displayName)
        assertEquals("아메리카노", load("아메리카노 두 잔 있어").preset.displayName)
        assertEquals("장보기", load("장 봤어").preset.displayName)   // 1-syllable alias → template name
    }

    @Test
    fun `unknown items with a loading verb register as 일반 짐`() {
        val i = load("조수석에 수박 실었어")
        assertTrue(i.isNew); assertEquals(CargoKind.GENERAL, i.kind); assertEquals("수박", i.preset.displayName)
        assertEquals(0.20, i.preset.limitG, 1e-9)
        assertTrue(VoiceIntentParser.parse("수박") is VoiceIntent.Unknown)   // no verb, no alias
    }

    @Test
    fun `a VIP used before wins over category aliases`() {
        val recents = listOf(CargoPreset.named("도자기 화분", CargoKind.FRAGILE))
        val i = load("도자기 화분 실었어", recents)
        assertEquals(CargoKind.FRAGILE, i.kind); assertEquals("도자기 화분", i.preset.displayName)
    }
}
