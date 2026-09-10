package kr.safecross.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kr.safecross.mobile.MainActivity

/**
 * 실시간 보행 내비게이션 Foreground Service (TRD 4.2 준수).
 *
 * 내비게이션 세션 중에만 동작하며, 상태바에 지속적인 알림과 "안내 즉시 중지" 액션을 제공합니다.
 * Android 14+ (API 34)의 [ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION]을 충실히 준수합니다.
 */
class NavigationForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "safecross_navigation_channel"
        const val CHANNEL_NAME = "Safe Cross KR 보행 안내"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_NAVIGATION = "kr.safecross.mobile.action.START_NAVIGATION"
        const val ACTION_STOP_NAVIGATION = "kr.safecross.mobile.action.STOP_NAVIGATION"
        const val ACTION_UPDATE_NOTIFICATION = "kr.safecross.mobile.action.UPDATE_NOTIFICATION"

        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
        const val EXTRA_MANEUVER_TEXT = "extra_maneuver_text"

        // 서비스 생명주기 및 사용자 중지 이벤트를 UI/ViewModel로 전파하는 SharedFlow
        private val _stopEventFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val stopEventFlow = _stopEventFlow.asSharedFlow()

        fun startService(context: Context, statusMessage: String = "보행 안내를 시작합니다.", maneuverText: String = "") {
            val intent = Intent(context, NavigationForegroundService::class.java).apply {
                action = ACTION_START_NAVIGATION
                putExtra(EXTRA_STATUS_MESSAGE, statusMessage)
                putExtra(EXTRA_MANEUVER_TEXT, maneuverText)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, NavigationForegroundService::class.java).apply {
                action = ACTION_STOP_NAVIGATION
            }
            context.startService(intent)
        }

        fun updateNotification(context: Context, statusMessage: String, maneuverText: String) {
            val intent = Intent(context, NavigationForegroundService::class.java).apply {
                action = ACTION_UPDATE_NOTIFICATION
                putExtra(EXTRA_STATUS_MESSAGE, statusMessage)
                putExtra(EXTRA_MANEUVER_TEXT, maneuverText)
            }
            context.startService(intent)
        }
    }

    private var currentStatusMessage: String = "보행 안내 중"
    private var currentManeuverText: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_NAVIGATION -> {
                currentStatusMessage = intent.getStringExtra(EXTRA_STATUS_MESSAGE) ?: "보행 안내 중"
                currentManeuverText = intent.getStringExtra(EXTRA_MANEUVER_TEXT) ?: ""
                val notification = buildNotification(currentStatusMessage, currentManeuverText)
                startForegroundWithLocationType(notification)
            }
            ACTION_UPDATE_NOTIFICATION -> {
                currentStatusMessage = intent.getStringExtra(EXTRA_STATUS_MESSAGE) ?: currentStatusMessage
                currentManeuverText = intent.getStringExtra(EXTRA_MANEUVER_TEXT) ?: currentManeuverText
                val notification = buildNotification(currentStatusMessage, currentManeuverText)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            }
            ACTION_STOP_NAVIGATION -> {
                handleStopNavigation()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStopNavigation() {
        _stopEventFlow.tryEmit(Unit)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startForegroundWithLocationType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(status: String, maneuver: String): Notification {
        // 앱 메인 화면 열기 PendingIntent
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 즉시 중지 액션 PendingIntent
        val stopIntent = Intent(this, NavigationForegroundService::class.java).apply {
            action = ACTION_STOP_NAVIGATION
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (maneuver.isNotBlank()) "$status ($maneuver)" else status

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Safe Cross KR 보행 안내")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "안내 즉시 중지",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "실시간 보행 내비게이션 상태 및 길안내 알림"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        _stopEventFlow.tryEmit(Unit)
    }
}
