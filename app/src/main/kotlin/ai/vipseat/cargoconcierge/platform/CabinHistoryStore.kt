package ai.vipseat.cargoconcierge.platform

import ai.vipseat.cargoconcierge.core.history.CabinHistoryCodec
import ai.vipseat.cargoconcierge.core.history.CabinHistoryRecorder
import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-private display reference, independent of the pending marker and all command authority. */
class CabinHistoryStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "cabin_reference_v1"))
    val recorder = CabinHistoryRecorder(CoroutineScope(SupervisorJob() + Dispatchers.IO), read = {
        try { file.openRead().use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            var count = input.read(buffer)
            while (count >= 0) {
                require(bytes.size() + count <= CabinHistoryCodec.MAX_ENCODED_SIZE)
                bytes.write(buffer, 0, count)
                count = input.read(buffer)
            }
            String(bytes.toByteArray(), Charsets.US_ASCII)
        } } catch (_: java.io.FileNotFoundException) { null }
    }, write = { encoded ->
        val stream = file.startWrite()
        try {
            stream.write(encoded.toByteArray(Charsets.US_ASCII))
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    })
}
