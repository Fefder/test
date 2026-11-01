package com.edgeai.agent.services

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import com.edgeai.agent.security.RestrictedSettingsHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleAndOnboardingTest {
    private class FakeRestartAlarmDriver(
        var exactAllowed: Boolean = true,
        var throwSecurityOnExact: Boolean = false,
    ) : AgentForegroundService.Companion.RestartAlarmDriver {
        var exactIdleCalls = 0
        var inexactIdleCalls = 0
        var exactCalls = 0
        var inexactCalls = 0
        var lastTriggerAt = 0L

        override fun canScheduleExactAlarms(): Boolean = exactAllowed

        override fun setExactAndAllowWhileIdle(
            type: Int,
            triggerAtMillis: Long,
        ) {
            if (throwSecurityOnExact) {
                throw SecurityException("SCHEDULE_EXACT_ALARM denied")
            }
            exactIdleCalls += 1
            lastTriggerAt = triggerAtMillis
        }

        override fun setAndAllowWhileIdle(
            type: Int,
            triggerAtMillis: Long,
        ) {
            inexactIdleCalls += 1
            lastTriggerAt = triggerAtMillis
        }

        override fun setExact(
            type: Int,
            triggerAtMillis: Long,
        ) {
            exactCalls += 1
            lastTriggerAt = triggerAtMillis
        }

        override fun set(
            type: Int,
            triggerAtMillis: Long,
        ) {
            inexactCalls += 1
            lastTriggerAt = triggerAtMillis
        }
    }

    @Test
    fun immediateRestartUsesExactAndAllowWhileIdleWhenPermitted() {
        val driver = FakeRestartAlarmDriver(exactAllowed = true)

        val mode =
            AgentForegroundService.scheduleRestartWithDriver(
                driver = driver,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                triggerAtElapsedRealtimeMs = 1_000L,
            )

        assertEquals(AgentForegroundService.Companion.AlarmScheduleMode.EXACT_ALLOW_WHILE_IDLE, mode)
        assertEquals(1, driver.exactIdleCalls)
        assertEquals(0, driver.inexactIdleCalls)
        assertEquals(1_000L, driver.lastTriggerAt)
    }

    @Test
    fun immediateRestartFallsBackToAllowWhileIdleWhenExactNotGrantedOrRejected() {
        val deniedDriver = FakeRestartAlarmDriver(exactAllowed = false)

        val deniedMode =
            AgentForegroundService.scheduleRestartWithDriver(
                driver = deniedDriver,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                triggerAtElapsedRealtimeMs = 1_000L,
            )
        assertEquals(AgentForegroundService.Companion.AlarmScheduleMode.INEXACT_ALLOW_WHILE_IDLE, deniedMode)
        assertEquals(0, deniedDriver.exactIdleCalls)
        assertEquals(1, deniedDriver.inexactIdleCalls)

        val throwingDriver = FakeRestartAlarmDriver(exactAllowed = true, throwSecurityOnExact = true)
        val fallbackMode =
            AgentForegroundService.scheduleRestartWithDriver(
                driver = throwingDriver,
                sdkInt = Build.VERSION_CODES.S,
                triggerAtElapsedRealtimeMs = 1_000L,
            )
        assertEquals(AgentForegroundService.Companion.AlarmScheduleMode.INEXACT_ALLOW_WHILE_IDLE, fallbackMode)
        assertEquals(0, throwingDriver.exactIdleCalls)
        assertEquals(1, throwingDriver.inexactIdleCalls)
    }

    @Test
    fun bootReceiverStartsImmediatelyWhenAllowed() {
        val context = ContextWrapper(null)
        var startCalled = false
        var fallbackCalled = false
        val receiver =
            BootCompletedReceiver(
                shouldStartCheck = { true },
                serviceStarter = {
                    startCalled = true
                    true
                },
                fallbackScheduler = {
                    fallbackCalled = true
                    true
                },
            )

        val outcome = receiver.handleBootAction(context, Intent.ACTION_BOOT_COMPLETED)
        assertEquals(BootCompletedReceiver.BootRestartOutcome.STARTED_IMMEDIATELY, outcome)
        assertTrue(startCalled)
        assertFalse(fallbackCalled)
    }

    @Test
    fun bootReceiverSchedulesFallbackWhenAndroid12RejectsDirectFgsStart() {
        val context = ContextWrapper(null)
        var startCalled = false
        var fallbackCalled = false
        val receiver =
            BootCompletedReceiver(
                shouldStartCheck = { true },
                serviceStarter = {
                    startCalled = true
                    false
                },
                fallbackScheduler = { ctx ->
                    fallbackCalled = true
                    AgentForegroundService.markBootRestartPending(ctx, true)
                    true
                },
            )

        val outcome = receiver.handleBootAction(context, Intent.ACTION_BOOT_COMPLETED)
        assertEquals(BootCompletedReceiver.BootRestartOutcome.FALLBACK_SCHEDULED, outcome)
        assertTrue(startCalled)
        assertTrue(fallbackCalled)
        assertTrue(AgentForegroundService.isBootRestartPending(context))

        AgentForegroundService.markBootRestartPending(context, false)
        assertFalse(AgentForegroundService.isBootRestartPending(context))
    }

    @Test
    fun bootReceiverIgnoresUnsupportedActionsAndDisabledState() {
        val context = ContextWrapper(null)
        val receiver =
            BootCompletedReceiver(
                shouldStartCheck = { false },
                serviceStarter = { true },
                fallbackScheduler = { true },
            )

        assertEquals(
            BootCompletedReceiver.BootRestartOutcome.IGNORED_ACTION,
            receiver.handleBootAction(context, Intent.ACTION_BATTERY_LOW),
        )
        assertEquals(
            BootCompletedReceiver.BootRestartOutcome.NOT_ENABLED,
            receiver.handleBootAction(context, Intent.ACTION_MY_PACKAGE_REPLACED),
        )
    }

    @Test
    fun onboardingDialogIsOfferedOnlyOnceAcrossActivityRecreations() {
        val activity = Activity()
        RestrictedSettingsHelper.resetOnboardingPromptState(activity)

        var dialogCount = 0
        val firstShow =
            RestrictedSettingsHelper.maybeShowOnboardingOnce(activity) {
                dialogCount += 1
            }
        assertTrue(firstShow)
        assertEquals(1, dialogCount)
        assertTrue(RestrictedSettingsHelper.isOnboardingOffered(activity))

        // Simulate Activity recreation (new Activity instance while Accessibility remains off).
        val recreatedActivity = Activity()
        val secondShow =
            RestrictedSettingsHelper.maybeShowOnboardingOnce(recreatedActivity) {
                dialogCount += 1
            }
        assertFalse(secondShow)
        assertEquals(1, dialogCount)

        RestrictedSettingsHelper.markOnboardingDismissed(recreatedActivity)
        assertTrue(RestrictedSettingsHelper.isOnboardingDismissed(recreatedActivity))
        assertFalse(RestrictedSettingsHelper.shouldAutoShowOnboarding(recreatedActivity))

        RestrictedSettingsHelper.resetOnboardingPromptState(recreatedActivity)
    }
}
