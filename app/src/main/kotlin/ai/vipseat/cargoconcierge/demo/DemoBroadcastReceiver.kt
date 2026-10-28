package ai.vipseat.cargoconcierge.demo

import ai.vipseat.cargoconcierge.BuildConfig
import ai.vipseat.cargoconcierge.CargoApp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Debug-only entry point for the adb demo scripts. No-op in release builds. */
class DemoBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEMO_BRIDGE) return   // contest/prototype builds keep the adb demo bridge; set DEMO_BRIDGE=false for an App Market build
        val result = CargoApp.graph(context).demoBridge.handle(intent)
        Log.i(DemoBridge.TAG, "broadcast → $result")
        resultData = result
    }
}
