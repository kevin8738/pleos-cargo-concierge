package ai.vipseat.cargoconcierge.core.history

import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.port.CabinFeature
import ai.vipseat.cargoconcierge.core.port.FeatureConfirmation
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/** Versioned, bounded display-only format. Invalid records are discarded as a whole. */
object CabinHistoryCodec {
    private const val VERSION = 1
    const val MAX_ENCODED_SIZE = 131_072

    fun encode(archive: CabinHistoryArchive): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            listOf(archive.previous, archive.current).forEach { session ->
                out.writeBoolean(session != null)
                if (session != null) {
                    require(session.rows.size <= CabinHistoryArchive.MAX_ROWS)
                    out.writeUTF(session.id); out.writeLong(session.startedAtMs)
                    out.writeBoolean(session.ended); out.writeBoolean(session.normalEnd); out.writeBoolean(session.gap)
                    out.writeInt(session.rows.size)
                    session.rows.forEach { r ->
                        out.writeUTF(r.cycleId); out.writeUTF(r.address.source)
                        out.writeUTF(r.address.property.name); out.writeUTF(r.address.area); out.writeUTF(r.spot.name)
                        out.writeUTF(r.features.sortedBy { it.name }.joinToString(",") { it.name })
                        out.writeLong(r.operation); out.writeLong(r.atMs)
                        out.optional(r.original); out.optional(r.requested); out.optional(r.confirmed)
                        out.optional(r.applyRequested); out.writeLong(r.confirmedAtMs ?: 0L)
                        out.writeBoolean(r.restoring); out.writeUTF(r.phase.name)
                        out.optional(r.status?.name); out.writeUTF(r.confirmation.name)
                    }
                }
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray()).also { require(it.length <= MAX_ENCODED_SIZE) }
    }

    fun decode(encoded: String?): CabinHistoryArchive? = runCatching {
        require(!encoded.isNullOrBlank() && encoded.length <= MAX_ENCODED_SIZE)
        val input = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded)))
        input.use {
            require(it.readInt() == VERSION)
            val sessions = List(2) {
                if (!input.readBoolean()) null else {
                    val id = input.text(); val started = input.readLong().also { require(it > 0) }
                    val ended = input.readBoolean(); val normal = input.readBoolean(); val gap = input.readBoolean()
                    require(!normal || ended)
                    val count = input.readInt().also { require(it in 0..CabinHistoryArchive.MAX_ROWS) }
                    val rows = List(count) {
                        val cycle = input.text(); val source = input.text()
                        val property = CabinProperty.valueOf(input.text()); val area = input.text()
                        val spot = CargoSpot.valueOf(input.text())
                        val features = input.text().split(',').map(CabinFeature::valueOf).toSet()
                        val operation = input.readLong().also { require(it > 0) }
                        val at = input.readLong().also { require(it > 0) }
                        val original = input.optional(); val target = input.optional(); val confirmed = input.optional()
                        val applyTarget = input.optional(); val confirmedAt = input.readLong().also { require(it >= 0) }.takeIf { it > 0 }
                        listOfNotNull(original, target, confirmed, applyTarget).forEach { require(property.accepts(it)) }
                        val restoring = input.readBoolean(); val phase = CabinHistoryPhase.valueOf(input.text())
                        val status = input.optional()?.let(FeatureStatus::valueOf)
                        val confirmation = FeatureConfirmation.valueOf(input.text())
                        require((confirmed != null) == (confirmedAt != null))
                        require(confirmation != FeatureConfirmation.VALUE_CONFIRMED || confirmed != null)
                        CabinHistoryRow(cycle, CabinAddress(source, property, area), spot, features, operation, at,
                            original, target, confirmed, applyTarget, confirmedAt, restoring, phase, status, confirmation)
                    }
                    require(rows.map { it.cycleId to it.address }.distinct().size == rows.size)
                    CabinHistorySession(id, started, ended, normal, gap, rows)
                }
            }
            require(input.available() == 0)
            CabinHistoryArchive(sessions[0], sessions[1])
        }
    }.getOrNull()

    private fun DataOutputStream.optional(value: String?) { writeBoolean(value != null); if (value != null) writeUTF(value) }
    private fun DataInputStream.optional(): String? = if (readBoolean()) text() else null
    private fun DataInputStream.text(): String = readUTF().also {
        require(it.length in 1..192 && it.none { ch -> ch.isISOControl() })
    }
}
