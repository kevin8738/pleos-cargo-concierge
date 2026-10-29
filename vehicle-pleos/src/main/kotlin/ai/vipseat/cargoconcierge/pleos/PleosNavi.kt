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
 * TBT turn types (TURN_SHARP_L/R, TURN_UTURN, TURN_EXIT_ROUNDABOUT). The 3.0.5 API documents
 * TBTInfo.distance and CautionPoint.remainDistance in metres; list ordering is not documented.
 * Demo events are a separate synthetic source and do not prove delivery by the navigation service.
 */
class PleosNavi(context: Context) : NaviPort {
    override val name: String = "Pleos NaviHelper 3.0.5"
    private val app = context.applicationContext
    private data class Connection(val id: Long, val helper: NaviHelper, val listener: NaviHelperEventListener)
    private val connectionLock = Any()
    private var generation = 0L
    private var connection: Connection? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null
    private var navigating = false
    private var lastCurveId: String? = null
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<NaviEvent> = _events

    /** A released SDK instance cannot deliver events into the next visible/guard session. */
    private inline fun current(id: Long, block: () -> Unit) = synchronized(connectionLock) {
        if (connection?.id == id) block()
    }

    private fun listener(id: Long) = object : NaviHelperEventListener {
        override fun onDestinationArrived(info: DestinationArrivedInfo) = current(id) {
            Log.i(TAG, "SDK callback destination-arrived")
            emit(NaviEvent.DestinationArrived(info.name))
        }
        override fun onWayPointArrived(info: WaypointArrivedInfo) = current(id) {
            Log.i(TAG, "SDK callback waypoint-arrived")
            emit(NaviEvent.WaypointArrived(info.name))
        }
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = current(id) { emit(NaviEvent.RoadSpeedLimit(speedLimit)) }
        override fun onNavigationStatus(status: NavigationStatus) = current(id) {
            Log.i(TAG, "SDK callback navigation-status=${status.name}")
            navigating = status == NavigationStatus.ROUTE_DRIVE
            emit(NaviEvent.Status(navigating, status.name))
        }
        override fun onError(errorCode: NaviErrorCode) = current(id) {
            Log.w(TAG, "SDK callback error=${errorCode.name}")
            emit(NaviEvent.Status(navigating, "error:${errorCode.name}"))
        }
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = current(id) {
            // Log types and distances only: destination names, addresses and coordinates stay out.
            Log.d(TAG, "SDK callback tbt count=${tbtInfoList.size} first=${tbtInfoList.take(2).map { "${it.type.name}:${it.distance}m" }}")
            tbtInfoList.firstOrNull()?.let(::onTurn)
        }
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) = current(id) {
            Log.d(TAG, "SDK callback caution count=${cautionPointList.size} bumps=${cautionPointList.count { it.type == CautionPointType.SPEED_BUMP }}")
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
        val next = synchronized(connectionLock) {
            if (connection != null) return
            val id = ++generation
            navigating = false
            lastCurveId = null
            Connection(id, NaviHelper(app), listener(id)).also { connection = it }
        }
        try {
            next.helper.initialize(
                { current(next.id) { Log.i(TAG, "NaviHelper ready"); emit(NaviEvent.Status(false, "navihelper ready")) } },
                { t -> current(next.id) {
                    Log.w(TAG, "NaviHelper init failed: ${t.javaClass.simpleName}")
                    emit(NaviEvent.Status(false, "navihelper init failed"))
                } },
            )
            next.helper.addListener(next.listener)
        } catch (e: Exception) {
            current(next.id) {
                Log.w(TAG, "NaviHelper unavailable: ${e.javaClass.simpleName}")
                emit(NaviEvent.Status(false, "navihelper unavailable"))
            }
            disconnect()
            return
        }
        // TBT is request/response as well as pushed. A new connection uses a new SDK object.
        pollJob = scope.launch {
            while (isActive) {
                val request = synchronized(connectionLock) { connection === next && navigating }
                if (request) runCatching { next.helper.getTBTInfo() }
                delay(TBT_POLL_MS)
            }
        }
    }

    override suspend fun disconnect() {
        val old = synchronized(connectionLock) {
            val value = connection
            connection = null
            generation++
            navigating = false
            lastCurveId = null
            value
        }
        pollJob?.cancel()
        pollJob = null
        old?.let {
            runCatching { it.helper.removeListener(it.listener) }
            runCatching { it.helper.release() }
        }
    }

    private fun emit(e: NaviEvent) { _events.tryEmit(e) }

    private companion object {
        const val TAG = "PleosNavi"
        const val CURVE_WARN_M = 300
        const val BUMP_WARN_M = 200
        const val TBT_POLL_MS = 3_000L
    }
}
