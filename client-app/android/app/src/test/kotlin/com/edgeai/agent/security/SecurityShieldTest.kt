package com.edgeai.agent.security

import android.view.accessibility.AccessibilityEvent
import com.edgeai.agent.services.AgentAccessibilityService
import com.edgeai.agent.shizuku.ShizukuAction
import com.edgeai.agent.shizuku.ShizukuActionExecutor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecurityShieldTest {
    @Before
    fun setUp() {
        SecurityShield.resumeAutomation()
        SecurityShield.observeWindow("org.telegram.messenger", "org.telegram.ui.LaunchActivity")
    }

    @After
    fun tearDown() {
        SecurityShield.resumeAutomation()
        SecurityShield.observeWindow("org.telegram.messenger", "org.telegram.ui.LaunchActivity")
    }

    @Test
    fun safeMessengerAllowsTypedAction() {
        val state = SecurityShield.currentState()
        assertFalse(state.blocked)
        assertEquals(SecurityShieldReason.SAFE, state.reason)
        assertTrue(SecurityShield.validateAction("org.telegram.messenger").isSuccess)
    }

    @Test
    fun safeForegroundBecomesUnknownAndExecutorRejectsInputWhenWindowsChangedLosesActiveRoot() {
        val service = AgentAccessibilityService()
        service.activeWindowRootProvider = { null }

        val state =
            service.handleAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                packageName = null,
                className = null,
            )

        assertEquals(SecurityShieldReason.FOREGROUND_UNKNOWN, state.reason)
        assertNull(state.packageName)
        assertTrue(SecurityShield.validateAction("org.telegram.messenger").isFailure)
        assertNull(service.foregroundPackageName())

        listOf(
            ShizukuAction.Tap(10f, 20f),
            ShizukuAction.Text("must-not-be-injected"),
            ShizukuAction.Key(4),
        ).forEach { action ->
            val result = ShizukuActionExecutor.execute(action)
            assertFalse("$action must not reach an input path", result.success)
            assertEquals("shield", result.channel)
            assertEquals(PackageShieldFilter.ERR_FOREGROUND_UNKNOWN, result.detail)
        }
    }

    @Test
    fun foregroundQueryDoesNotReturnCachedSafeOwnerWhenRootDisappearsWithoutEvent() {
        val service = AgentAccessibilityService()
        service.activeWindowRootProvider = { null }

        assertNull(service.foregroundPackageName())
        assertEquals(SecurityShieldReason.FOREGROUND_UNKNOWN, SecurityShield.currentState().reason)
        assertTrue(SecurityShield.validateAction("org.telegram.messenger").isFailure)
    }

    @Test
    fun bankWindowBlocksEveryAction() {
        val state = SecurityShield.observeWindow("com.ftband.mono", "com.ftband.MainActivity")
        assertTrue(state.blocked)
        assertEquals(SecurityShieldReason.PACKAGE_BLOCKED, state.reason)
        assertTrue(SecurityShield.validateAction("org.telegram.messenger").isFailure)

        val keyboardNoise =
            SecurityShield.observePotentialSecuritySignal(
                "com.google.android.inputmethod.latin",
                "InputMethodService",
            )
        assertEquals(SecurityShieldReason.PACKAGE_BLOCKED, keyboardNoise.reason)
        assertEquals("com.ftband.mono", keyboardNoise.packageName)
    }

    @Test
    fun bankAppPlusImeAndOverlayWindowTransitionsStayBlockedUntilRealForegroundApp() {
        val service = AgentAccessibilityService()
        var activeRoot: AgentAccessibilityService.WindowNodeSnapshot? = null
        service.activeWindowRootProvider = { activeRoot }

        // 1. Banking app opens; FLAG_SECURE often causes rootInActiveWindow to be null.
        val bankState =
            service.handleAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = "com.ftband.mono",
                className = "com.ftband.MainActivity",
            )
        assertTrue(bankState.blocked)
        assertEquals(SecurityShieldReason.PACKAGE_BLOCKED, bankState.reason)
        assertEquals("com.ftband.mono", bankState.packageName)

        // 2. IME pops up over the banking app with TYPE_WINDOW_STATE_CHANGED and rootInActiveWindow == null.
        val imeStateChanged =
            service.handleAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = "com.google.android.inputmethod.latin",
                className = "android.inputmethodservice.InputMethodService",
            )
        assertTrue(imeStateChanged.blocked)
        assertEquals(SecurityShieldReason.PACKAGE_BLOCKED, imeStateChanged.reason)
        assertEquals("com.ftband.mono", imeStateChanged.packageName)
        assertTrue(SecurityShield.validateAction("org.telegram.messenger").isFailure)

        // 3. IME emits TYPE_WINDOWS_CHANGED with rootInActiveWindow == null.
        val imeWindowsChanged =
            service.handleAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                packageName = "com.samsung.android.honeyboard",
                className = "android.inputmethodservice.SoftInputWindow",
            )
        assertTrue(imeWindowsChanged.blocked)
        assertEquals(SecurityShieldReason.PACKAGE_BLOCKED, imeWindowsChanged.reason)
        assertEquals("com.ftband.mono", imeWindowsChanged.packageName)

        // 4. Even if rootInActiveWindow temporarily points at the IME window, keep bank package blocked.
        activeRoot =
            AgentAccessibilityService.WindowNodeSnapshot(
                packageName = "com.google.android.inputmethod.latin",
                className = "android.widget.FrameLayout",
            )
        val imeFocusedRoot =
            service.handleAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = "com.google.android.inputmethod.latin",
                className = "android.inputmethodservice.SoftInputWindow",
            )
        assertEquals(SecurityShieldReason.PACKAGE_BLOCKED, imeFocusedRoot.reason)
        assertEquals("com.ftband.mono", imeFocusedRoot.packageName)

        // 5. Overlay / SystemUI window transition with rootInActiveWindow == null must not fail-open.
        activeRoot = null
        val overlayTransition =
            service.handleAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = "com.example.overlay",
                className = "android.widget.PopupWindow",
            )
        assertEquals(SecurityShieldReason.PACKAGE_BLOCKED, overlayTransition.reason)
        assertEquals("com.ftband.mono", overlayTransition.packageName)

        // 6. Direct observeWindow call from an IME while bank app is blocked also stays fail-closed.
        val directImeObserve =
            SecurityShield.observeWindow(
                "com.google.android.inputmethod.latin",
                "android.inputmethodservice.InputMethodService",
            )
        assertEquals(SecurityShieldReason.PACKAGE_BLOCKED, directImeObserve.reason)
        assertEquals("com.ftband.mono", directImeObserve.packageName)

        // 7. Only when a real foreground application appears in the active root does the shield clear.
        activeRoot =
            AgentAccessibilityService.WindowNodeSnapshot(
                packageName = "org.telegram.messenger",
                className = "android.widget.FrameLayout",
            )
        val telegramState =
            service.handleAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = "org.telegram.messenger",
                className = "org.telegram.ui.LaunchActivity",
            )
        assertFalse(telegramState.blocked)
        assertEquals(SecurityShieldReason.SAFE, telegramState.reason)
        assertEquals("org.telegram.messenger", telegramState.packageName)
        assertTrue(SecurityShield.validateAction("org.telegram.messenger").isSuccess)
    }

    @Test
    fun biometricBlockSurvivesSamePackageContentUntilConfirmedDismissTransition() {
        val service = AgentAccessibilityService()
        service.activeWindowRootProvider = {
            AgentAccessibilityService.WindowNodeSnapshot(
                packageName = "com.android.systemui",
                className = "android.widget.FrameLayout",
            )
        }

        val biometric =
            SecurityShield.observeWindow(
                "com.android.systemui",
                "android.hardware.biometrics.BiometricPrompt",
            )
        assertEquals(SecurityShieldReason.BIOMETRIC_ACTIVE, biometric.reason)

        val samePackageContent =
            service.handleAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                packageName = "com.android.systemui",
                className = "android.widget.FrameLayout",
            )
        assertEquals(SecurityShieldReason.BIOMETRIC_ACTIVE, samePackageContent.reason)

        // The pure policy entry point must also reject the original fail-open sequence.
        val directSamePackageContent =
            SecurityShield.observeWindow(
                "com.android.systemui",
                "android.widget.FrameLayout",
            )
        assertEquals(SecurityShieldReason.BIOMETRIC_ACTIVE, directSamePackageContent.reason)

        val confirmedDismiss =
            service.handleAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = "com.android.systemui",
                className = "android.widget.FrameLayout",
            )
        assertEquals(SecurityShieldReason.SAFE, confirmedDismiss.reason)

        val ordinarySystemUiContent =
            service.handleAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                packageName = "com.android.systemui",
                className = "android.widget.FrameLayout",
            )
        assertEquals(SecurityShieldReason.SAFE, ordinarySystemUiContent.reason)
    }

    @Test
    fun secureWindowSignalBlocksExecution() {
        val state = SecurityShield.markSecureWindow("com.example.documents", "SecureDocumentActivity")
        assertEquals(SecurityShieldReason.SECURE_WINDOW, state.reason)
        assertTrue(state.blocked)

        val laterContentEvent =
            SecurityShield.observeWindow(
                "com.example.documents",
                "DocumentContentView",
                secureWindowDetected = false,
            )
        assertEquals(SecurityShieldReason.SECURE_WINDOW, laterContentEvent.reason)
    }

    @Test
    fun manualPauseSurvivesForegroundEventsUntilExplicitResume() {
        SecurityShield.pauseAutomation()
        val paused = SecurityShield.observeWindow("org.telegram.messenger", "LaunchActivity")
        assertEquals(SecurityShieldReason.MANUALLY_PAUSED, paused.reason)

        val resumed = SecurityShield.resumeAutomation()
        assertEquals(SecurityShieldReason.SAFE, resumed.reason)
    }

    @Test
    fun missingForegroundFailsClosed() {
        val state = SecurityShield.observeWindow(null, null)
        assertEquals(SecurityShieldReason.FOREGROUND_UNKNOWN, state.reason)
        assertTrue(state.blocked)
    }
}
