package ai.vipseat.cargoconcierge.runtime

import ai.vipseat.cargoconcierge.CargoApp
import ai.vipseat.cargoconcierge.MainActivity
import ai.vipseat.cargoconcierge.R
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log

/** Executes only the process's current in-memory request; never restores a preset from an Intent. */
class GuardForegroundService : Service() {
    private val session by lazy { CargoApp.graph(this).session }
    private var requestId: Long? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "VIP 가드 실행", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "활성 가드가 차량·내비 신호를 확인하는 동안 표시해요" },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(REQUEST_ID, -1L) ?: -1L
        if (session.isActiveForegroundRequest(id)) return START_NOT_STICKY
        val useLocation = session.pendingForegroundRequest(id)
        if (useLocation == null) {
            // A stale delivery must not stop a newer valid lease.
            if (!session.requiresForegroundService) stopSelf()
            else session.foregroundStartFailed(id)
            return START_NOT_STICKY
        }
        try {
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("VIP 조수석 가드 실행 중")
                .setContentText("차량·내비 신호를 확인하고 있어요. 앱에서 종료할 수 있어요.")
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                var type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
                if (useLocation) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                startForeground(NOTIFICATION_ID, notification, type)
                // Android can silently ignore promotion (START_FOREGROUND app-op). A normal
                // return alone must not authorize the guard or its cabin effects.
                check(if (type != 0) foregroundServiceType and type == type else legacyForegroundConfirmed())
            } else {
                startForeground(NOTIFICATION_ID, notification)
                check(legacyForegroundConfirmed())
            }
            requestId = id
            if (!session.foregroundReady(id)) {
                session.foregroundStartFailed(id)
                if (!session.requiresForegroundService) stopSelf()
            }
        } catch (error: Exception) {
            Log.w("CargoGuard", "foreground promotion failed: ${error.javaClass.simpleName}")
            if (session.isActiveForegroundRequest(id)) session.foregroundLost(id)
            else session.foregroundStartFailed(id)
            if (!session.requiresForegroundService) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        session.setUiVisible(false)
        session.foregroundLost(requestId)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // A normal stop already released the matching lease; unexpected destruction abandons it.
        requestId?.let { session.foregroundLost(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** API 28 / untyped older FGS have no nonzero type to verify. Query this service only. */
    @Suppress("DEPRECATION")
    private fun legacyForegroundConfirmed(): Boolean =
        getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)?.any {
            it.service == ComponentName(this, GuardForegroundService::class.java) &&
                it.pid == Process.myPid() && it.uid == Process.myUid() && it.foreground
        } == true

    companion object {
        const val REQUEST_ID = "guard_request_id"
        private const val CHANNEL = "cargo_active_guard"
        private const val NOTIFICATION_ID = 7301
    }
}
