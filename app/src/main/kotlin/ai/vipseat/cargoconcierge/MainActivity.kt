package ai.vipseat.cargoconcierge

import ai.vipseat.cargoconcierge.ui.CargoRoute
import ai.vipseat.cargoconcierge.ui.theme.CargoTheme
import ai.vipseat.cargoconcierge.vm.CargoViewModel
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val graph by lazy { CargoApp.graph(this) }
    private val viewModel: CargoViewModel by viewModels { CargoViewModel.Factory(graph) }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { graph.session.refreshLocationAccess() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMissingPermissions()
        setContent {
            CargoTheme {
                CargoRoute(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        graph.uxMonitor.start()
        graph.session.setUiVisible(true)
    }

    override fun onStop() {
        graph.session.setUiVisible(false)
        graph.uxMonitor.stop()
        super.onStop()
    }

    private fun requestMissingPermissions() {
        val missing = graph.runtimePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}
