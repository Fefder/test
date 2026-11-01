package com.edgeai.agent.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.edgeai.agent.MainActivity
import com.edgeai.agent.R
import java.io.File

// AgentForegroundService: persistent background service for Edge AI daemon
class AgentForegroundService : Service() {
    companion object {
        const val TAG = "AgentForegroundService"
        private const val CHANNEL_ID = "edge_agent_foreground_channel"
        private const val NOTIFICATION_ID = 1001
        private const val BOOT_RECOVERY_NOTIFICATION_ID = 1002
        private const val RESTART_REQUEST_CODE = 1001
        private const val WAKELOCK_TAG = "EdgeAIAgent:DaemonWakeLock"
        private const val PREFERENCES = "edge_agent_service"
        private const val PREF_USER_ENABLED = "user_enabled"
        private const val PREF_BOOT_RESTART_PENDING = "boot_restart_pending"
        private const val HEARTBEAT_WAKELOCK_MS = 10_000L
        internal const val IMMEDIATE_RESTART_DELAY_MS = 1_000L
        internal const val BOOT_RETRY_DELAY_MS = 3_000L

        // Abstract socket name (starts with \0, 0 bytes on disk)
        private const val SOCKET_NAME = "edge_agent.sock"

        // Volatile flag for visibility across CPU threads
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var bootRestartPendingInProcess: Boolean = false

        fun start(
            context: Context,
            rememberUserChoice: Boolean = true,
        ): Boolean =
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AgentForegroundService::class.java),
                )
                markBootRestartPending(context, false)
                if (rememberUserChoice) {
                    context
                        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                        ?.edit()
                        ?.putBoolean(PREF_USER_ENABLED, true)
                        ?.apply()
                }
                true
            }.onFailure { error ->
                Log.e(TAG, "Unable to start foreground service", error)
            }.getOrDefault(false)

        fun shouldStartAfterBoot(context: Context): Boolean =
            bootRestartPendingInProcess ||
                (
                    context
                        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                        ?.getBoolean(PREF_USER_ENABLED, false) ?: false
                )

        fun isBootRestartPending(context: Context): Boolean =
            bootRestartPendingInProcess ||
                (
                    context
                        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                        ?.getBoolean(PREF_BOOT_RESTART_PENDING, false) ?: false
                )

        fun markBootRestartPending(
            context: Context,
            pending: Boolean = true,
        ) {
            bootRestartPendingInProcess = pending
            runCatching {
                context
                    .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putBoolean(PREF_BOOT_RESTART_PENDING, pending)
                    ?.apply()
            }
        }

        /**
         * Fallback path when Android 12+ rejects direct FGS start from BOOT_COMPLETED /
         * MY_PACKAGE_REPLACED: records pending state for AccessibilityService / MainActivity,
         * schedules an AlarmManager retry, and posts a recovery notification.
         */
        fun scheduleDeferredBootRecovery(
            context: Context,
            delayMs: Long = BOOT_RETRY_DELAY_MS,
        ): Boolean {
            markBootRestartPending(context, true)
            val alarmScheduled = scheduleRestart(context, delayMs)
            postBootRecoveryNotification(context)
            return alarmScheduled
        }

        fun scheduleRestart(
            context: Context,
            delayMs: Long = IMMEDIATE_RESTART_DELAY_MS,
        ): Boolean {
            return try {
                val appContext = context.applicationContext ?: context
                val intent = Intent(appContext, AgentForegroundService::class.java)
                val pendingIntent =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        PendingIntent.getForegroundService(
                            appContext,
                            RESTART_REQUEST_CODE,
                            intent,
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    } else {
                        PendingIntent.getService(
                            appContext,
                            RESTART_REQUEST_CODE,
                            intent,
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    }
                val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                val triggerAt = SystemClock.elapsedRealtime() + delayMs
                scheduleRestartAlarm(alarmManager, Build.VERSION.SDK_INT, triggerAt, pendingIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule restart alarm: ${e.message}")
                false
            }
        }

        internal interface RestartAlarmDriver {
            fun canScheduleExactAlarms(): Boolean

            fun setExactAndAllowWhileIdle(
                type: Int,
                triggerAtMillis: Long,
            )

            fun setAndAllowWhileIdle(
                type: Int,
                triggerAtMillis: Long,
            )

            fun setExact(
                type: Int,
                triggerAtMillis: Long,
            )

            fun set(
                type: Int,
                triggerAtMillis: Long,
            )
        }

        internal enum class AlarmScheduleMode {
            NONE,
            EXACT_ALLOW_WHILE_IDLE,
            INEXACT_ALLOW_WHILE_IDLE,
            EXACT,
            INEXACT,
        }

        /**
         * Uses exact idle-bypassing alarm (`setExactAndAllowWhileIdle`) when permitted for
         * 1-second immediate resurrection, with graceful fallback to `setAndAllowWhileIdle`
         * if `SCHEDULE_EXACT_ALARM` is not currently granted on Android 12+.
         */
        internal fun scheduleRestartAlarm(
            alarmManager: AlarmManager?,
            sdkInt: Int,
            triggerAtElapsedRealtimeMs: Long,
            pendingIntent: PendingIntent?,
        ): Boolean {
            if (alarmManager == null || pendingIntent == null) return false
            val driver =
                object : RestartAlarmDriver {
                    override fun canScheduleExactAlarms(): Boolean =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            alarmManager.canScheduleExactAlarms()
                        } else {
                            true
                        }

                    override fun setExactAndAllowWhileIdle(
                        type: Int,
                        triggerAtMillis: Long,
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            alarmManager.setExactAndAllowWhileIdle(type, triggerAtMillis, pendingIntent)
                        }
                    }

                    override fun setAndAllowWhileIdle(
                        type: Int,
                        triggerAtMillis: Long,
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            alarmManager.setAndAllowWhileIdle(type, triggerAtMillis, pendingIntent)
                        }
                    }

                    override fun setExact(
                        type: Int,
                        triggerAtMillis: Long,
                    ) {
                        alarmManager.setExact(type, triggerAtMillis, pendingIntent)
                    }

                    override fun set(
                        type: Int,
                        triggerAtMillis: Long,
                    ) {
                        alarmManager.set(type, triggerAtMillis, pendingIntent)
                    }
                }
            return scheduleRestartWithDriver(
                driver = driver,
                sdkInt = sdkInt,
                triggerAtElapsedRealtimeMs = triggerAtElapsedRealtimeMs,
            ) != AlarmScheduleMode.NONE
        }

        internal fun scheduleRestartWithDriver(
            driver: RestartAlarmDriver,
            sdkInt: Int,
            triggerAtElapsedRealtimeMs: Long,
        ): AlarmScheduleMode =
            try {
                when {
                    sdkInt >= Build.VERSION_CODES.S -> {
                        val canExact =
                            runCatching { driver.canScheduleExactAlarms() }
                                .getOrDefault(false)
                        if (canExact) {
                            try {
                                driver.setExactAndAllowWhileIdle(
                                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                    triggerAtElapsedRealtimeMs,
                                )
                                AlarmScheduleMode.EXACT_ALLOW_WHILE_IDLE
                            } catch (se: SecurityException) {
                                Log.w(TAG, "Exact alarm rejected despite check; falling back to inexact idle alarm", se)
                                driver.setAndAllowWhileIdle(
                                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                    triggerAtElapsedRealtimeMs,
                                )
                                AlarmScheduleMode.INEXACT_ALLOW_WHILE_IDLE
                            }
                        } else {
                            Log.w(TAG, "SCHEDULE_EXACT_ALARM not granted; falling back to setAndAllowWhileIdle")
                            driver.setAndAllowWhileIdle(
                                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                triggerAtElapsedRealtimeMs,
                            )
                            AlarmScheduleMode.INEXACT_ALLOW_WHILE_IDLE
                        }
                    }
                    sdkInt >= Build.VERSION_CODES.M -> {
                        try {
                            driver.setExactAndAllowWhileIdle(
                                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                triggerAtElapsedRealtimeMs,
                            )
                            AlarmScheduleMode.EXACT_ALLOW_WHILE_IDLE
                        } catch (se: SecurityException) {
                            Log.w(TAG, "Exact alarm rejected; falling back to setAndAllowWhileIdle", se)
                            driver.setAndAllowWhileIdle(
                                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                triggerAtElapsedRealtimeMs,
                            )
                            AlarmScheduleMode.INEXACT_ALLOW_WHILE_IDLE
                        }
                    }
                    sdkInt >= Build.VERSION_CODES.KITKAT -> {
                        driver.setExact(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAtElapsedRealtimeMs,
                        )
                        AlarmScheduleMode.EXACT
                    }
                    else -> {
                        driver.set(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAtElapsedRealtimeMs,
                        )
                        AlarmScheduleMode.INEXACT
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Alarm scheduling failed: ${e.message}")
                AlarmScheduleMode.NONE
            }

        private fun postBootRecoveryNotification(context: Context) {
            runCatching {
                val appContext = context.applicationContext ?: context
                val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel =
                        NotificationChannel(
                            CHANNEL_ID,
                            appContext.getString(R.string.foreground_service_channel),
                            NotificationManager.IMPORTANCE_LOW,
                        )
                    manager.createNotificationChannel(channel)
                }
                val openApp =
                    PendingIntent.getActivity(
                        appContext,
                        BOOT_RECOVERY_NOTIFICATION_ID,
                        Intent(appContext, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                val notification =
                    NotificationCompat
                        .Builder(appContext, CHANNEL_ID)
                        .setContentTitle(appContext.getString(R.string.boot_recovery_notification_title))
                        .setContentText(appContext.getString(R.string.boot_recovery_notification_text))
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentIntent(openApp)
                        .setAutoCancel(true)
                        .setCategory(NotificationCompat.CATEGORY_SERVICE)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .build()
                manager.notify(BOOT_RECOVERY_NOTIFICATION_ID, notification)
            }.onFailure { error ->
                Log.w(TAG, "Unable to post boot recovery notification: ${error.message}")
            }
        }
    }

    // Receiver for user unlock event
    private var userPresentReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        markBootRestartPending(this, false)
        Log.i(TAG, "Starting AgentForegroundService (specialUse)...")

        // 1. Create system notification channel
        createNotificationChannel()

        // 2. Persistent notification; do not claim unmeasured CPU usage.
        val notification = buildPersistentNotification(getString(R.string.foreground_service_idle))

        // 3. specialUse exists on Android 14+. Older versions use the untyped overload.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 4. WakeLocks are acquired only around bounded socket work.
        registerUserPresentReceiver()
        // 5. Log Linux kernel OOM score
        logOomScore()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Return START_STICKY to ensure OS resurrects service if killed under memory pressure
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Triggered when task is swiped away from recent apps
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "App task removed from recent apps. Scheduling immediate auto-restart...")
        // Immediate resurrection via AlarmManager (1s exact-when-permitted + idle fallback)
        scheduleImmediateRestart()
    }

    override fun onDestroy() {
        isRunning = false
        unregisterUserPresentReceiver()
        super.onDestroy()
    }

    // Builds ongoing notification with low priority
    private fun buildPersistentNotification(statusText: String): Notification {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.foreground_service_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // Creates notification channel for Android 8.0+
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.foreground_service_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    // Listens for screen unlock (ACTION_USER_PRESENT) to pre-warm mmap model pages
    private fun registerUserPresentReceiver() {
        userPresentReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action == Intent.ACTION_USER_PRESENT) {
                        Log.i(TAG, "User present event: pre-warming C++ engine via abstract socket...")
                        sendHeartbeatPing()
                    }
                }
            }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        registerReceiver(userPresentReceiver, filter)
    }

    private fun unregisterUserPresentReceiver() {
        userPresentReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering receiver: ${e.message}")
            }
            userPresentReceiver = null
        }
    }

    // Sends ping into abstract namespace socket (@edge_agent.sock)
    private fun sendHeartbeatPing() {
        Thread({
            val wakeLock =
                (getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            try {
                wakeLock?.acquire(HEARTBEAT_WAKELOCK_MS)
                LocalSocket().use { socket ->
                    val endpoint = LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT)
                    socket.connect(endpoint)
                    val header = EdgeIpcFrameFactory.ping().toBytes()
                    socket.outputStream.write(header)
                    socket.outputStream.flush()
                }
            } catch (e: Exception) {
                // Socket daemon may be sleeping or restarting
                Log.d(TAG, "Heartbeat ping skipped (daemon offline)")
            } finally {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }, "edge-agent-heartbeat").apply { isDaemon = true }.start()
    }

    // Schedules restart via AlarmManager if task is swiped
    private fun scheduleImmediateRestart() {
        scheduleRestart(applicationContext ?: this, IMMEDIATE_RESTART_DELAY_MS)
    }

    // Reads /proc/self/oom_score_adj (DoD target: <= 200)
    private fun logOomScore() {
        try {
            val file = File("/proc/self/oom_score_adj")
            if (file.exists() && file.canRead()) {
                val score = file.readText().trim()
                Log.i(TAG, "Current process /proc/self/oom_score_adj: $score (Target: <= 200)")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Cannot read oom_score_adj: ${e.message}")
        }
    }
}
