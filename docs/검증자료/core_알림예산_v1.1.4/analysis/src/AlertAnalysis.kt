package v24.alerts

import ai.vipseat.cargoconcierge.core.engine.Action
import ai.vipseat.cargoconcierge.core.engine.CargoState
import ai.vipseat.cargoconcierge.core.engine.CargoStateMachine
import ai.vipseat.cargoconcierge.core.engine.Effect
import ai.vipseat.cargoconcierge.core.model.*
import ai.vipseat.cargoconcierge.core.physics.GEstimator
import java.io.File
import java.io.Writer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.atan

private const val START_MS = 1000L
private const val SOURCE_ID = 1
private val SYNTHETIC = MotionProvenance.SYNTHETIC
private val WHEELBASE = 2.9f.toDouble()
private val RATIO = 15f.toDouble()

private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun sha(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
private fun quote(s: String) = buildString {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
}
private fun json(v: Any?): String = when (v) {
    null -> "null"
    is String -> quote(v)
    is Boolean -> v.toString()
    is Double -> { require(v.isFinite()); v.toString() }
    is Float -> { require(v.isFinite()); v.toString() }
    is Number -> v.toString()
    is Map<*, *> -> v.entries.joinToString(",", "{", "}") { quote(it.key.toString()) + ":" + json(it.value) }
    is Iterable<*> -> v.joinToString(",", "[", "]") { json(it) }
    else -> error("Unsupported JSON type")
}
private fun newWriter(file: File): Writer {
    file.parentFile.mkdirs()
    return Files.newBufferedWriter(file.toPath(), UTF_8, CREATE_NEW, WRITE)
}
private fun writeJson(file: File, value: Any?) = newWriter(file).use { it.write(json(value) + "\n") }
private fun near(actual: Double, expected: Double, tolerance: Double = 1e-12) {
    check(actual.isFinite() && abs(actual - expected) <= tolerance) { "Arithmetic mismatch: actual=$actual expected=$expected" }
}
private fun newMachine() = CargoStateMachine(GEstimator(wheelbaseM = WHEELBASE, steeringRatio = RATIO))
private fun signal(s: VehicleSignal) = Action.Vehicle(s)
private fun speed(v: Double) = signal(VehicleSignal.Speed(v, SOURCE_ID, SYNTHETIC))
private fun steer(v: Double) = signal(VehicleSignal.SteeringAngle(v, SYNTHETIC))
private fun brake(v: Boolean) = signal(VehicleSignal.BrakePedal(v, null, SYNTHETIC))
private fun audible(e: Effect) = e is Effect.Speak || e is Effect.Chime
private fun effect(e: Effect): Map<String, Any?> = when (e) {
    is Effect.Speak -> mapOf("kind" to "Speak", "text" to e.text, "priority" to e.priority)
    is Effect.Chime -> mapOf("kind" to "Chime", "chime" to e.kind.name)
    is Effect.Log -> mapOf("kind" to "Log", "message" to e.message)
    is Effect.ApplyCabin -> mapOf("kind" to "ApplyCabin", "executed" to false)
    Effect.RestoreCabin -> mapOf("kind" to "RestoreCabin", "executed" to false)
}
private fun action(a: Action): Map<String, Any?> = when (a) {
    is Action.Vehicle -> when (val s = a.signal) {
        is VehicleSignal.Speed -> mapOf("kind" to "speed", "mps" to s.mps, "sourceId" to s.sourceId, "provenance" to s.provenance.name)
        is VehicleSignal.SteeringAngle -> mapOf("kind" to "steering", "degrees" to s.degrees, "provenance" to s.provenance.name)
        is VehicleSignal.BrakePedal -> mapOf("kind" to "brake", "pressed" to s.pressed, "percent" to s.percent, "provenance" to s.provenance.name)
        is VehicleSignal.GearChanged -> mapOf("kind" to "gear", "gear" to s.gear.name)
        else -> error("Unexpected vehicle signal")
    }
    is Action.Navi -> mapOf("kind" to "selftest_navigation", "event" to a.event.javaClass.simpleName)
    is Action.SelectPreset -> mapOf("kind" to "select", "preset" to a.preset.kind.name)
    Action.Tick -> mapOf("kind" to "tick")
    Action.EndTrip -> mapOf("kind" to "end_trip")
    else -> error("Unexpected action")
}

private class Harness(val preset: CargoPreset, private val writer: Writer? = null) {
    val machine = newMachine()
    var dispatches = 0L
    var invariantChecks = 0L
    var candidateRequests = 0L
    var firstVoice3: Long? = null
    var firstAudible6: Long? = null
    var firstTripLimit: Long? = null
    private var lastAllowed: Long? = null
    private var previousRegion = "UNAVAILABLE"
    private var previousSpeedRegion = "UNAVAILABLE"
    val allRegionEntries = linkedMapOf<String, Long>()
    val speedRegionEntries = linkedMapOf<String, Long>()
    val badgeTransitions = linkedMapOf<String, Long>()
    val candidatesByRegion = linkedMapOf<String, Long>()
    val candidatesByAction = linkedMapOf<String, Long>()

    init {
        dispatch(signal(VehicleSignal.GearChanged(Gear.PARK)), 0, "initialization")
        dispatch(speed(0.0), 1, "initialization")
        dispatch(steer(0.0), 1, "initialization")
        dispatch(brake(false), 1, "initialization")
        dispatch(Action.SelectPreset(preset), 2, "initialization")
        check(machine.state is CargoState.Guarding)
        dispatch(signal(VehicleSignal.GearChanged(Gear.DRIVE)), START_MS)
    }

    fun guard() = machine.state as CargoState.Guarding
    private fun increment(map: MutableMap<String, Long>, key: String, n: Long = 1) { map[key] = (map[key] ?: 0) + n }

    fun dispatch(a: Action, now: Long, phase: String = "driving"): List<Effect> {
        val before = machine.state as? CargoState.Guarding
        val fx = machine.dispatch(a, now)
        val after = machine.state as? CargoState.Guarding
        val estimate = after?.lastEstimate
        val region = when {
            estimate == null || !estimate.hasData -> "UNAVAILABLE"
            estimate.totalG >= preset.limitG -> "OVER"
            estimate.totalG >= 0.8 * preset.limitG -> "WARN"
            else -> "OK"
        }
        var ds = 0; var dc = 0; var requested = 0
        val suppressed = linkedMapOf<String, Int>()
        if (phase == "driving" && before != null && after != null) {
            val b = before.trip; val t = after.trip
            check(b.startedMs == t.startedMs) { "Unexpected session reset" }
            ds = t.drivingSpeechCount - b.drivingSpeechCount
            dc = t.drivingChimeCount - b.drivingChimeCount
            for (reason in AudioSuppressionReason.entries) {
                val d = (t.audioSuppressionCounts[reason] ?: 0) - (b.audioSuppressionCounts[reason] ?: 0)
                check(d >= 0)
                if (d > 0) suppressed[reason.name] = d
            }
            check(ds >= 0 && dc >= 0 && ds + dc <= 1)
            check(ds == fx.count { it is Effect.Speak } && dc == fx.count { it is Effect.Chime })
            check(t.drivingSpeechCount <= 3 && t.drivingAudibleCount <= 6)
            check(fx.count(::audible) <= 1)
            if (ds + dc > 0) {
                check(lastAllowed?.let { now - it >= 8000 } ?: true) { "Shared cooldown violated" }
                lastAllowed = now
            }
            requested = ds + dc + suppressed.values.sum()
            candidateRequests += requested
            if (t.drivingSpeechCount == 3 && firstVoice3 == null) firstVoice3 = now
            if (t.drivingAudibleCount == 6 && firstAudible6 == null) firstAudible6 = now
            if ((suppressed[AudioSuppressionReason.TRIP_LIMIT.name] ?: 0) > 0 && firstTripLimit == null) firstTripLimit = now
            if (before.badge != after.badge) increment(badgeTransitions, "${before.badge.name}->${after.badge.name}")
            if (a is Action.Vehicle && (a.signal is VehicleSignal.Speed || a.signal is VehicleSignal.SteeringAngle || a.signal is VehicleSignal.BrakePedal)) {
                if (region != previousRegion) increment(allRegionEntries, region)
                previousRegion = region
                if (a.signal is VehicleSignal.Speed) {
                    if (region != previousSpeedRegion) increment(speedRegionEntries, region)
                    previousSpeedRegion = region
                }
                if (requested > 0) {
                    increment(candidatesByRegion, region, requested.toLong())
                    increment(candidatesByAction, action(a).getValue("kind").toString(), requested.toLong())
                }
            }
            invariantChecks++
        }
        writer?.write(json(linkedMapOf(
            "seq" to dispatches, "phase" to phase, "now_ms" to now, "driving_ms" to (now - START_MS).takeIf { phase == "driving" },
            "action" to action(a), "G" to estimate?.let { listOf(it.lateralG, it.longitudinalG, it.totalG) },
            "axes_available" to estimate?.let { listOf(it.lateralAvailable, it.longitudinalAvailable) },
            "longitudinal_fresh" to estimate?.longitudinalFresh, "region" to region,
            "badge_before" to before?.badge?.name, "badge_after" to after?.badge?.name,
            "admitted_delta" to listOf(ds, dc), "candidate_total" to requested, "suppressed_delta" to suppressed,
            "cumulative_admitted" to after?.trip?.let { listOf(it.drivingSpeechCount, it.drivingChimeCount) },
            "motion_status" to after?.motionStatus?.name,
            "quality" to after?.trip?.qualityReasons?.map { it.name }?.sorted(),
            "provenance" to after?.trip?.motionProvenance?.map { it.name }?.sorted(), "effects" to fx.map(::effect)
        )) + "\n")
        dispatches++
        return fx
    }
}

private fun fresh(h: Harness, now: Long) {
    h.dispatch(speed(10.0), now); h.dispatch(steer(0.0), now); h.dispatch(brake(false), now)
}

private fun selfTests(): List<String> {
    val checks = mutableListOf<String>()
    val estimator = GEstimator(wheelbaseM = WHEELBASE, steeringRatio = RATIO)
    check(estimator.wheelbaseM == 2.9f.toDouble() && estimator.steeringRatio == 15f.toDouble())
    check(estimator.smoothing == 0.5)
    val policy = CargoStateMachine.Config()
    check(policy.maxAudiblePerTrip == 6 && policy.maxVoiceCoachPerTrip == 3 && policy.audibleCooldownMs == 8000L)
    checks += "production_initialization_and_policy"
    // tan(6 degrees) is a fixed independent known-answer constant, not a call to lateralG.
    val v = 30.0 / 3.6
    near(estimator.lateralG(v, 90.0), v * v * 0.10510423526567646 / WHEELBASE / 9.80665)
    estimator.update(12.0, 0.0, false, null, 1000, SOURCE_ID)
    near(estimator.update(12.2, 0.0, false, null, 1200, SOURCE_ID).longitudinalG, 0.5 / 9.80665)
    near(estimator.update(12.2, 0.0, false, null, 1400, SOURCE_ID).longitudinalG, 0.25 / 9.80665)
    checks += "independent_lateral_and_EMA_arithmetic"
    for ((g, expected) in listOf(0.12 - 1e-6 to "OK", 0.12 + 1e-6 to "WARN", 0.15 - 1e-6 to "WARN", 0.15 + 1e-6 to "OVER")) {
        val h = Harness(CargoPreset.CAKE)
        fresh(h, 1000)
        val angle = Math.toDegrees(atan(g * 9.80665 * WHEELBASE / 100.0)) * RATIO
        h.dispatch(steer(angle), 1001)
        near(h.guard().lastEstimate!!.totalG, g)
        check(h.guard().badge.name == expected)
    }
    checks += "WARN_OVER_boundary_sides"
    val h = Harness(CargoPreset.CAKE)
    fresh(h, 1000)
    h.dispatch(Action.Navi(NaviEvent.WaypointArrived("synthetic-selftest")), 1000)
    check(h.guard().trip.drivingSpeechCount == 1)
    fresh(h, 8999)
    check(h.dispatch(Action.Navi(NaviEvent.WaypointArrived("synthetic-selftest")), 8999).none(::audible))
    check(h.guard().lastAudioSuppression == AudioSuppressionReason.COOLDOWN)
    fresh(h, 9000)
    check(h.dispatch(Action.Navi(NaviEvent.WaypointArrived("synthetic-selftest")), 9000).count(::audible) == 1)
    checks += "shared_cooldown_7999_8000_ms"
    fresh(h, 17000)
    h.dispatch(Action.Navi(NaviEvent.WaypointArrived("synthetic-selftest")), 17000)
    check(h.guard().trip.drivingSpeechCount == 3 && h.firstVoice3 == 17000L)
    fresh(h, 25000)
    check(h.dispatch(Action.Navi(NaviEvent.WaypointArrived("synthetic-selftest")), 25000).none(::audible))
    check(h.guard().lastAudioSuppression == AudioSuppressionReason.VOICE_LIMIT)
    for (t in listOf(25000L, 40000L, 55000L)) {
        fresh(h, t)
        h.dispatch(Action.Navi(NaviEvent.SharpCurveAhead(100, TurnDirection.RIGHT, "synthetic-selftest")), t)
    }
    check(h.guard().trip.drivingSpeechCount == 3 && h.guard().trip.drivingChimeCount == 3)
    check(h.firstAudible6 == 55000L)
    fresh(h, 70000)
    check(h.dispatch(Action.Navi(NaviEvent.SharpCurveAhead(100, TurnDirection.RIGHT, "synthetic-selftest")), 70000).none(::audible))
    check(h.firstTripLimit == 70000L && h.guard().lastAudioSuppression == AudioSuppressionReason.TRIP_LIMIT)
    check(h.candidateRequests == h.guard().trip.drivingAudibleCount.toLong() + h.guard().trip.audioSuppressionCounts.values.sum())
    checks += "voice3_total6_suppression_and_effect_reconciliation"
    check(SYNTHETIC in h.guard().trip.motionProvenance)
    h.dispatch(Action.Tick, 73001)
    check(h.guard().lastEstimate == null)
    check(GQualityReason.INPUT_GAP in h.guard().trip.qualityReasons)
    checks += "freshness_and_synthetic_provenance"
    h.dispatch(Action.EndTrip, 74000, "reset")
    h.dispatch(signal(VehicleSignal.GearChanged(Gear.PARK)), 75000, "reset")
    h.dispatch(speed(0.0), 75000, "reset")
    h.dispatch(Action.SelectPreset(CargoPreset.CAKE), 75001, "reset")
    check(h.guard().trip.drivingAudibleCount == 0 && h.guard().trip.audioSuppressionCounts.isEmpty())
    check(h.guard().voiceCoachCount == 0 && h.guard().lastAudibleMs == CargoStateMachine.NEVER)
    check(h.guard().trip.startedMs == 75001L && h.guard().trip.maxG == 0.0)
    checks += "explicit_session_reset"
    return checks
}

private data class Sample(val t: Long, val v: Double, val steering: Double, val braking: Boolean)
private fun samples(file: File, period: Long): List<Sample> {
    val lines = file.readLines(UTF_8)
    require(lines.first() == "t_ms,speed_mps,steering_deg,brake_pressed")
    val rows = lines.drop(1).mapIndexed { i, line ->
        val p = line.split(','); require(p.size == 4)
        val row = Sample(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toBooleanStrict())
        require(row.t == i * period && row.v.isFinite() && row.v >= 0 && row.steering.isFinite())
        row
    }
    require(rows.first().t == 0L && rows.last().t == 120000L && rows.size.toLong() == 120000L / period + 1)
    return rows
}

private fun freeze(qa: File): String {
    val root = qa.parentFile.parentFile.parentFile.parentFile.canonicalFile
    val paths = linkedSetOf<String>()
    val manifest = File(qa, "frozen-files.tsv")
    for (line in manifest.readLines(UTF_8)) {
        val p = line.split('\t'); require(p.size == 2 && p[0].matches(Regex("[0-9a-f]{64}")))
        require(paths.add(p[1]))
        val member = File(root, p[1])
        require(!Files.isSymbolicLink(member.toPath()) && member.canonicalFile.toPath().startsWith(root.toPath()))
        check(member.isFile && sha(member) == p[0]) { "Frozen file mismatch: ${p[1]}" }
    }
    val mainSources = File(root, "app/core/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
    check(mainSources == paths.filter { it.startsWith("app/core/src/main/kotlin/") }.toSet())
    val analysisSources = File(qa, "src").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
    check(analysisSources == paths.filter { it.startsWith(qa.relativeTo(root).invariantSeparatorsPath + "/src/") }.toSet())
    val inputFiles = File(qa, "inputs").listFiles()!!.filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
    check(inputFiles.size == 6 && inputFiles == paths.filter { it.startsWith(qa.relativeTo(root).invariantSeparatorsPath + "/inputs/") }.toSet())
    return sha(manifest)
}

private fun runCase(out: File, file: File, preset: CargoPreset, frozen: String): Map<String, Any?> {
    val name = file.nameWithoutExtension
    val period = name.substringAfterLast('_').removeSuffix("ms").toLong()
    val input = samples(file, period)
    val folder = File(out, "scenarios/$name/${preset.kind.name}")
    check(!folder.exists()) { "Case output already exists" }
    var harness: Harness? = null
    return try {
        newWriter(File(folder, "events.jsonl")).use { writer ->
            val h = Harness(preset, writer); harness = h
            var nextTick = 0L
            for (row in input) {
                while (nextTick < row.t) { h.dispatch(Action.Tick, START_MS + nextTick); nextTick += 250 }
                val now = START_MS + row.t
                h.dispatch(speed(row.v), now); h.dispatch(steer(row.steering), now); h.dispatch(brake(row.braking), now)
                if (nextTick == row.t) { h.dispatch(Action.Tick, START_MS + nextTick); nextTick += 250 }
            }
            val t = h.guard().trip
            check(t.motionProvenance == setOf(SYNTHETIC))
            check(h.candidateRequests == t.drivingAudibleCount.toLong() + t.audioSuppressionCounts.values.sum())
            linkedMapOf<String, Any?>(
                "status" to "PASS_COMPUTATIONAL_CHECKS_ONLY", "scenario" to name, "preset" to preset.kind.name,
                "freeze_sha256" to frozen, "input_sha256" to sha(file), "evidence_grade" to "D-input/C-engine", "listening_observations" to 0, "field_observations" to 0,
                "initialization" to mapOf("wheelbase" to WHEELBASE, "ratio" to RATIO, "EMA" to 0.5, "sourceId" to SOURCE_ID, "provenance" to "SYNTHETIC"),
                "period_ms" to period, "duration_ms" to input.last().t, "session_start_ms" to t.startedMs, "driving_start_ms" to START_MS,
                "speed_samples" to input.size, "other_motion_samples" to 2 * input.size, "fresh_differences" to t.freshLongitudinalSamples,
                "limitG" to preset.limitG, "maxG" to t.maxG, "speed_grid_region_entries" to h.speedRegionEntries,
                "all_motion_region_entries" to h.allRegionEntries, "displayed_badge_transitions" to h.badgeTransitions,
                "gate_candidate_requests" to h.candidateRequests, "motion_candidates_by_region" to h.candidatesByRegion,
                "motion_candidates_by_action" to h.candidatesByAction, "admitted_speech_requests" to t.drivingSpeechCount,
                "admitted_chime_requests" to t.drivingChimeCount, "suppression_counts" to t.audioSuppressionCounts.mapKeys { it.key.name },
                "first_voice3_ms" to h.firstVoice3, "first_audible6_ms" to h.firstAudible6, "first_trip_limit_suppression_ms" to h.firstTripLimit,
                "first_voice3_driving_ms" to h.firstVoice3?.minus(START_MS), "first_audible6_driving_ms" to h.firstAudible6?.minus(START_MS),
                "first_trip_limit_suppression_driving_ms" to h.firstTripLimit?.minus(START_MS),
                "quality" to t.qualityReasons.map { it.name }.sorted(), "real_world_comparison" to "UNAVAILABLE_SYNTHETIC",
                "dispatches" to h.dispatches, "invariant_checks" to h.invariantChecks,
                "suppressed_request_type_breakdown" to "unavailable_public_API"
            )
        }.also { writeJson(File(folder, "summary.json"), it) }
    } catch (e: Exception) {
        linkedMapOf<String, Any?>("status" to "FAIL", "scenario" to name, "preset" to preset.kind.name,
            "freeze_sha256" to frozen, "input_sha256" to sha(file), "error" to (e.message ?: e.javaClass.simpleName),
            "partial_dispatches" to harness?.dispatches).also {
            if (!File(folder, "summary.json").exists()) writeJson(File(folder, "summary.json"), it)
        }
    }
}

fun main(args: Array<String>) {
    require(args.size == 3 && args[0] in setOf("selftest", "run")) { "mode QA-directory run-id required" }
    val (mode, directory, runId) = args
    require(runId.matches(Regex("[a-zA-Z0-9_-]+")))
    val qa = File(directory).canonicalFile
    val frozen = freeze(qa)
    val out = File(qa, "out/$runId")
    if (mode == "selftest") {
        val target = File(out, "selftest.json")
        check(!target.exists()) { "Self-test output already exists" }
        try {
            val checks = selfTests()
            writeJson(target, linkedMapOf("status" to "PASS", "freeze_sha256" to frozen, "checks" to checks,
                "scenario_cases_executed" to 0, "java_version" to System.getProperty("java.version")))
            println("Self-tests PASS; scenario cases executed: 0; freeze=$frozen")
        } catch (e: Exception) {
            if (!target.exists()) writeJson(target, mapOf("status" to "FAIL", "freeze_sha256" to frozen, "error" to (e.message ?: e.javaClass.simpleName), "scenario_cases_executed" to 0))
            throw e
        }
        return
    }
    val certificate = File(out, "selftest.json")
    val cert = certificate.readText(UTF_8)
    check(cert.contains("\"status\":\"PASS\"") && cert.contains("\"freeze_sha256\":\"$frozen\"")) { "Matching self-test PASS required" }
    check(!File(out, "scenarios").exists() && !File(out, "summary.jsonl").exists()) { "Scenario output exists; refusing replacement" }
    val results = mutableListOf<Map<String, Any?>>()
    newWriter(File(out, "summary.jsonl")).use { combined ->
        for (file in File(qa, "inputs").listFiles()!!.sortedBy { it.name }) {
            for (preset in CargoPreset.ALL) {
                val row = runCase(out, file, preset, frozen)
                results += row; combined.write(json(row) + "\n"); combined.flush()
            }
        }
    }
    check(results.size == 54)
    newWriter(File(out, "result-manifest.tsv")).use { writer ->
        out.walkTopDown().filter { it.isFile && it.name != "result-manifest.tsv" }.sortedBy { it.relativeTo(out).invariantSeparatorsPath }.forEach {
            writer.write("${sha(it)}\t${it.relativeTo(out).invariantSeparatorsPath}\n")
        }
    }
    val failed = results.count { it["status"] == "FAIL" }
    println("Retained ${results.size} cases; failed=$failed; synthetic calculations only; listening/field=0")
    check(failed == 0) { "Failed cases retained; no all-case PASS" }
}
