package ai.vipseat.cargoconcierge.pleos

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.vipseat.cargoconcierge.core.model.NaviEvent
import ai.vipseat.cargoconcierge.core.model.TurnDirection
import ai.vipseat.cargoconcierge.core.port.NaviPort
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * NaviHelper 3.0.5 adapter (verified: NaviHelper(context), initialize(onSuccess, onFailure),
 * addListener/removeListener(NaviHelperEventListener), getTBTInfo(), release(); listener callbacks
 * onDestinationArrived / onWayPointArrived / onTBTInfo / onCautionPointInfo / onCurrentRoadSpeedLimit /
 * onNavigationStatus / onError).
 *
 * Curve geometry is not part of the NaviHelper data model, so "sharp curve ahead" is derived from
 * TBT turn types (TURN_SHARP_L/R, TURN_UTURN, TURN_EXIT_ROUNDABOUT). ASSUMPTION: TBTInfo.distance is
 * metres. When the default navigation app does not deliver TBT data, the demo injector takes over.
 */
class PleosNavi(context: Context) : NaviPort {
    override val name: String = "Pleos NaviHelper 3.0.5"
    private val app = context.applicationContext
    private val helper = NaviHelper(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null
    private var navigating = false
    private var lastCurveId: String? = null
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<NaviEvent> = _events

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationArrived(info: DestinationArrivedInfo) { emit(NaviEvent.DestinationArrived(info.name)) }
        override fun onWayPointArrived(info: WaypointArrivedInfo) { emit(NaviEvent.WaypointArrived(info.name)) }
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) { emit(NaviEvent.RoadSpeedLimit(speedLimit)) }
        override fun onNavigationStatus(status: NavigationStatus) {
            navigating = status == NavigationStatus.ROUTE_DRIVE
            emit(NaviEvent.Status(navigating, status.name))
        }
        override fun onError(errorCode: NaviErrorCode) { emit(NaviEvent.Status(navigating, "error:${errorCode.name}")) }
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) { tbtInfoList.firstOrNull()?.let(::onTurn) }
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            cautionPointList.firstOrNull { it.type == CautionPointType.SPEED_BUMP && it.remainDistance in 1..BUMP_WARN_M }
                ?.let { emit(NaviEvent.SpeedBumpAhead(it.remainDistance, source = "navihelper")) }
        }
    }

    private fun onTurn(t: TBTInfo) {
        val direction = when (t.type) {
            TurnType.TURN_SHARP_R, TurnType.TURN_UTURN -> TurnDirection.RIGHT
            TurnType.TURN_SHARP_L -> TurnDirection.LEFT
            TurnType.TURN_EXIT_ROUNDABOUT -> TurnDirection.UNKNOWN
            else -> return
        }
        val distanceM = t.distance.roundToInt()
        if (distanceM !in 1..CURVE_WARN_M) return
        val id = "${t.type}@${(distanceM / 50)}" // de-duplicate repeated TBT updates for the same turn
        if (id == lastCurveId) return
        lastCurveId = id
        emit(NaviEvent.SharpCurveAhead(distanceM, direction, source = "navihelper:${t.type.name}"))
    }

    override suspend fun connect() {
        try {
            helper.initialize(
                { Log.i(TAG, "NaviHelper ready"); emit(NaviEvent.Status(false, "navihelper ready")) },
                { t -> Log.w(TAG, "NaviHelper init failed: ${t.message}"); emit(NaviEvent.Status(false, "navihelper init failed: ${t.message}")) },
            )
            helper.addListener(listener)
        } catch (e: Exception) {
            Log.w(TAG, "NaviHelper unavailable: ${e.message}")
            emit(NaviEvent.Status(false, "navihelper unavailable: ${e.message}"))
            return
        }
        // TBT is request/response as well as pushed; poll while a route is active so we never miss a sharp turn.
        pollJob = scope.launch {
            while (isActive) {
                if (navigating) runCatching { helper.getTBTInfo() }
                delay(TBT_POLL_MS)
            }
        }
    }

    override suspend fun disconnect() {
        pollJob?.cancel()
        runCatching { helper.removeListener(listener) }
        runCatching { helper.release() }
    }

    private fun emit(e: NaviEvent) { _events.tryEmit(e) }

    private companion object {
        const val TAG = "PleosNavi"
        const val CURVE_WARN_M = 300
        const val BUMP_WARN_M = 200
        const val TBT_POLL_MS = 3_000L
    }
}
