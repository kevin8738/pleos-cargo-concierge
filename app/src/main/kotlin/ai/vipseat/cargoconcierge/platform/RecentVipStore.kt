package ai.vipseat.cargoconcierge.platform

import ai.vipseat.cargoconcierge.core.model.CargoKind
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One VIP the user has used: their word for it, its protection category, how often, when last. */
data class RecentVip(val name: String, val kind: CargoKind, val uses: Int, val lastUsedMs: Long) {
    fun toPreset(): CargoPreset = CargoPreset.named(name, kind)
}

/**
 * "이전에 쓴 VIP 먼저": the sheet and the voice parser consult this list before the category
 * templates. On-device only (SharedPreferences JSON), most recently used first, capped at [MAX].
 */
class RecentVipStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("cargo_recent_vips", Context.MODE_PRIVATE)
    private val _recents = MutableStateFlow(load())
    val recents: StateFlow<List<RecentVip>> = _recents

    /** Presets in sheet order (most recent first). */
    val presets: List<CargoPreset> get() = _recents.value.map { it.toPreset() }

    fun record(preset: CargoPreset, nowMs: Long = System.currentTimeMillis()) {
        val name = preset.displayName.trim()
        if (name.isEmpty()) return
        val cur = _recents.value
        val hit = cur.firstOrNull { it.name == name }
        val next = listOf(RecentVip(name, preset.kind, (hit?.uses ?: 0) + 1, nowMs)) + cur.filter { it.name != name }
        save(next.take(MAX))
    }

    /** Change the category of a remembered VIP (e.g. 수박: 일반 짐 → 신선식품). */
    fun reclassify(name: String, kind: CargoKind) = save(_recents.value.map { if (it.name == name) it.copy(kind = kind) else it })

    fun remove(name: String) = save(_recents.value.filter { it.name != name })

    fun clear() = save(emptyList())

    /** Demo/PT seeding: "도자기:FRAGILE,강아지:PET" — replaces the list. */
    fun seed(spec: String, nowMs: Long = System.currentTimeMillis()) {
        val items = spec.split(",").mapNotNull { token ->
            val parts = token.trim().split(":")
            val name = parts.getOrNull(0)?.trim().orEmpty()
            val kind = runCatching { CargoKind.valueOf(parts.getOrNull(1)?.trim()?.uppercase() ?: "GENERAL") }.getOrDefault(CargoKind.GENERAL)
            if (name.isEmpty()) null else RecentVip(name, kind, 1, nowMs)
        }
        save(items.mapIndexed { i, it -> it.copy(lastUsedMs = nowMs - i * 60_000L) }.take(MAX))
    }

    private fun save(list: List<RecentVip>) {
        _recents.value = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.name).put("k", it.kind.name).put("u", it.uses).put("t", it.lastUsedMs)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun load(): List<RecentVip> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RecentVip(o.getString("n"), runCatching { CargoKind.valueOf(o.getString("k")) }.getOrDefault(CargoKind.GENERAL), o.optInt("u", 1), o.optLong("t", 0L))
        }.sortedByDescending { it.lastUsedMs }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "recent_vips_json"
        const val MAX = 8
    }
}
