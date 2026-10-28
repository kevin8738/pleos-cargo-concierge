package ai.vipseat.cargoconcierge

import ai.vipseat.cargoconcierge.di.AppGraph
import android.app.Application

class CargoApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    companion object {
        fun graph(context: android.content.Context): AppGraph = (context.applicationContext as CargoApp).graph
    }
}
