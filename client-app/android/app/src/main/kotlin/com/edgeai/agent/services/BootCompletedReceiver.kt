package com.edgeai.agent.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the foreground service when the user enabled it previously.
 *
 * On Android 12+ (API 31+), direct `startForegroundService` from `BOOT_COMPLETED` may be
 * rejected for `specialUse` unless battery optimizations are ignored or Accessibility is active.
 * When rejected, the receiver marks a pending boot restart (picked up automatically by
 * `AgentAccessibilityService.onServiceConnected` or `MainActivity.onPostResume`), schedules an
 * `AlarmManager` retry, and posts a recovery notification.
 */
class BootCompletedReceiver(
    private val shouldStartCheck: (Context) -> Boolean = { ctx ->
        AgentForegroundService.shouldStartAfterBoot(ctx)
    },
    private val serviceStarter: (Context) -> Boolean = { ctx ->
        AgentForegroundService.start(ctx, rememberUserChoice = false)
    },
    private val fallbackScheduler: (Context) -> Boolean = { ctx ->
        AgentForegroundService.scheduleDeferredBootRecovery(ctx)
    },
) : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        handleBootAction(context, intent.action)
    }

    internal fun handleBootAction(
        context: Context,
        action: String?,
    ): BootRestartOutcome {
        if (action !in supportedActions) return BootRestartOutcome.IGNORED_ACTION
        if (!shouldStartCheck(context)) return BootRestartOutcome.NOT_ENABLED
        if (serviceStarter(context)) {
            return BootRestartOutcome.STARTED_IMMEDIATELY
        }

        Log.w(TAG, "The OS rejected immediate foreground-service restart after $action; scheduling fallback recovery")
        val scheduled = fallbackScheduler(context)
        return if (scheduled) {
            BootRestartOutcome.FALLBACK_SCHEDULED
        } else {
            BootRestartOutcome.FALLBACK_PENDING_ONLY
        }
    }

    enum class BootRestartOutcome {
        IGNORED_ACTION,
        NOT_ENABLED,
        STARTED_IMMEDIATELY,
        FALLBACK_SCHEDULED,
        FALLBACK_PENDING_ONLY,
    }

    private companion object {
        const val TAG = "BootCompletedReceiver"
        val supportedActions = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
