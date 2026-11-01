package com.edgeai.agent.security

import java.util.concurrent.CopyOnWriteArraySet

enum class SecurityShieldReason(
    val errorCode: String,
) {
    SAFE("OK"),
    PACKAGE_BLOCKED(PackageShieldFilter.ERR_FINANCIAL_SHIELD_BLOCKED),
    FOREGROUND_UNKNOWN(PackageShieldFilter.ERR_FOREGROUND_UNKNOWN),
    BIOMETRIC_ACTIVE("ERR_BIOMETRIC_SHIELD_BLOCKED"),
    SECURE_WINDOW("ERR_SECURE_WINDOW_BLOCKED"),
    MANUALLY_PAUSED("ERR_AUTOMATION_PAUSED"),
}

data class SecurityShieldState(
    val reason: SecurityShieldReason,
    val packageName: String?,
    val windowClassName: String?,
    val trustedPackage: Boolean,
    val observedAtEpochMs: Long,
) {
    val blocked: Boolean
        get() = reason != SecurityShieldReason.SAFE

    fun toMap(): Map<String, Any?> =
        mapOf(
            "blocked" to blocked,
            "reason" to reason.name,
            "errorCode" to reason.errorCode,
            "packageName" to packageName,
            "windowClassName" to windowClassName,
            "trustedPackage" to trustedPackage,
            "observedAtEpochMs" to observedAtEpochMs,
        )
}

/**
 * Process-wide safety state. It stores no screen text and never attempts to bypass
 * FLAG_SECURE or an application's anti-fraud policy.
 */
object SecurityShield {
    private data class WindowObservation(
        val packageName: String?,
        val className: String?,
        val secureWindowDetected: Boolean,
    )

    private val lock = Any()
    private val listeners = CopyOnWriteArraySet<(SecurityShieldState) -> Unit>()

    @Volatile
    private var manuallyPaused = false

    @Volatile
    private var lastObservation = WindowObservation(null, null, false)

    @Volatile
    private var state =
        SecurityShieldState(
            reason = SecurityShieldReason.FOREGROUND_UNKNOWN,
            packageName = null,
            windowClassName = null,
            trustedPackage = false,
            observedAtEpochMs = System.currentTimeMillis(),
        )

    fun currentState(): SecurityShieldState = state

    fun observeWindow(
        packageName: CharSequence?,
        className: CharSequence?,
        secureWindowDetected: Boolean = false,
    ): SecurityShieldState =
        observeWindow(
            packageName = packageName,
            className = className,
            secureWindowDetected = secureWindowDetected,
            authoritativeTransition = false,
        )

    private fun observeWindow(
        packageName: CharSequence?,
        className: CharSequence?,
        secureWindowDetected: Boolean,
        authoritativeTransition: Boolean,
    ): SecurityShieldState {
        val incoming =
            WindowObservation(
                packageName = packageName?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                className = className?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                secureWindowDetected = secureWindowDetected,
            )
        return updateState {
            val current = state
            val sameBiometricOwnerContent =
                current.reason == SecurityShieldReason.BIOMETRIC_ACTIVE &&
                    !authoritativeTransition &&
                    incoming.packageName != null &&
                    incoming.packageName == lastObservation.packageName &&
                    !isSecurityEscalation(
                        incoming.packageName,
                        incoming.className,
                        incoming.secureWindowDetected,
                    )
            if (sameBiometricOwnerContent) {
                // A generic content/class update from SystemUI is not proof that the
                // biometric prompt was dismissed. Only a confirmed window/root transition
                // may clear BIOMETRIC_ACTIVE for the same package.
                return@updateState current
            }

            val isProtectedForeground =
                current.reason == SecurityShieldReason.PACKAGE_BLOCKED ||
                    current.reason == SecurityShieldReason.SECURE_WINDOW ||
                    (
                        current.reason == SecurityShieldReason.BIOMETRIC_ACTIVE &&
                            incoming.packageName != lastObservation.packageName
                    )
            if (isProtectedForeground &&
                incoming.packageName != null &&
                !isSecurityEscalation(incoming.packageName, incoming.className, incoming.secureWindowDetected) &&
                isInputMethodOrOverlaySurface(
                    incoming.packageName,
                    incoming.className,
                    includeSystemUiOverlay = lastObservation.packageName != SYSTEM_UI_PACKAGE,
                )
            ) {
                return@updateState current
            }

            // Once a secure/password surface is observed, keep the package blocked until
            // foreground ownership changes. A later content event must not clear the shield.
            val observation =
                incoming.copy(
                    secureWindowDetected =
                        incoming.secureWindowDetected ||
                            (
                                lastObservation.secureWindowDetected &&
                                    lastObservation.packageName == incoming.packageName
                            ),
                )
            lastObservation = observation
            evaluate(observation)
        }
    }

    /**
     * Evaluates a window transition (`TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOWS_CHANGED`).
     *
     * When a banking or protected package is currently blocked, neither an IME/overlay window
     * nor a transition with `rootInActiveWindow == null` is allowed to replace the blocked
     * foreground owner until a real foreground application window is confirmed.
     */
    fun observeWindowTransition(
        eventPackageName: CharSequence?,
        eventClassName: CharSequence?,
        activeRootPackageName: CharSequence? = null,
        activeRootClassName: CharSequence? = null,
        secureWindowDetected: Boolean = false,
    ): SecurityShieldState {
        val eventPkg = eventPackageName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val eventCls = eventClassName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val rootPkg = activeRootPackageName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val rootCls = activeRootClassName?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        // 1. Any explicit security escalation from the event or root takes effect immediately.
        if (isSecurityEscalation(eventPkg, eventCls, secureWindowDetected)) {
            return observeWindow(
                packageName = eventPkg ?: rootPkg,
                className = eventCls ?: rootCls,
                secureWindowDetected = secureWindowDetected,
            )
        }
        if (isSecurityEscalation(rootPkg, rootCls, secureWindowDetected = false)) {
            return observeWindow(
                packageName = rootPkg,
                className = rootCls ?: eventCls,
                secureWindowDetected = secureWindowDetected,
            )
        }

        val current = state
        val hasProtectedOwner =
            current.reason == SecurityShieldReason.PACKAGE_BLOCKED ||
                current.reason == SecurityShieldReason.SECURE_WINDOW ||
                current.reason == SecurityShieldReason.BIOMETRIC_ACTIVE

        // 2. A missing active root preserves known protection, but never preserves SAFE.
        // Event packages (including window-state events) are not fresh foreground proof.
        if (rootPkg == null) {
            if (hasProtectedOwner) {
                return observePotentialSecuritySignal(eventPkg, eventCls, secureWindowDetected)
            }
            return observeWindow(null, null)
        }

        // 3. Even when rootInActiveWindow is non-null, an IME or transient overlay must not
        // replace the foreground application owner.
        if (isInputMethodOrOverlaySurface(
                rootPkg,
                rootCls,
                includeSystemUiOverlay = current.reason == SecurityShieldReason.PACKAGE_BLOCKED,
            ) ||
            (rootPkg == eventPkg && isInputMethodOrOverlaySurface(eventPkg, eventCls, includeSystemUiOverlay = false))
        ) {
            return observePotentialSecuritySignal(rootPkg, eventCls ?: rootCls, secureWindowDetected)
        }

        val effectiveClass =
            if (eventPkg == rootPkg && !isInputMethodOrOverlaySurface(eventPkg, eventCls, includeSystemUiOverlay = false)) {
                eventCls ?: rootCls
            } else {
                rootCls
            }
        return observeWindow(
            packageName = rootPkg,
            className = effectiveClass,
            secureWindowDetected = secureWindowDetected,
            authoritativeTransition = true,
        )
    }

    fun markSecureWindow(
        packageName: String?,
        className: String? = null,
    ): SecurityShieldState = observeWindow(packageName, className, secureWindowDetected = true)

    /**
     * Secondary Accessibility events (IME, overlays, content updates) may escalate
     * protection but cannot clear an existing block or replace the foreground owner.
     */
    fun observePotentialSecuritySignal(
        packageName: CharSequence?,
        className: CharSequence?,
        secureWindowDetected: Boolean = false,
    ): SecurityShieldState {
        val normalizedPackage = packageName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedClass = className?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return if (isSecurityEscalation(normalizedPackage, normalizedClass, secureWindowDetected)) {
            observeWindow(normalizedPackage, normalizedClass, secureWindowDetected)
        } else {
            state
        }
    }

    fun pauseAutomation(): SecurityShieldState =
        updateState {
            manuallyPaused = true
            evaluate(lastObservation)
        }

    fun resumeAutomation(): SecurityShieldState =
        updateState {
            manuallyPaused = false
            evaluate(lastObservation)
        }

    /** Checks both the current window state and the package targeted by an action. */
    fun validateAction(targetPackage: String?): Result<Unit> {
        val snapshot = state
        if (snapshot.blocked) {
            return Result.failure(SecurityException(snapshot.reason.errorCode))
        }
        return PackageShieldFilter.validateAction(targetPackage)
    }

    fun addListener(listener: (SecurityShieldState) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (SecurityShieldState) -> Unit) {
        listeners.remove(listener)
    }

    internal fun isSecurityEscalation(
        packageName: CharSequence?,
        className: CharSequence?,
        secureWindowDetected: Boolean = false,
    ): Boolean {
        val normalizedPackage = packageName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedClass = className?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val blockedPackage =
            PackageShieldFilter.classify(normalizedPackage).classification ==
                PackageClassification.BLOCKED
        return secureWindowDetected ||
            blockedPackage ||
            isBiometricSurface(normalizedPackage, normalizedClass)
    }

    internal fun isInputMethodOrOverlaySurface(
        packageName: CharSequence?,
        className: CharSequence?,
        includeSystemUiOverlay: Boolean = false,
    ): Boolean {
        val pkg = packageName?.toString()?.trim()?.lowercase().orEmpty()
        val cls = className?.toString()?.trim()?.lowercase().orEmpty()
        if (isBiometricSurface(pkg, cls)) return false
        if (pkg.isNotEmpty() && (pkg in knownInputMethodPackages || imePackageSegmentPattern.containsMatchIn(pkg))) {
            return true
        }
        if (includeSystemUiOverlay && pkg == SYSTEM_UI_PACKAGE) {
            return true
        }
        return cls.isNotEmpty() && imeOrOverlayClassMarkers.any(cls::contains)
    }

    private fun evaluate(observation: WindowObservation): SecurityShieldState {
        val assessment = PackageShieldFilter.classify(observation.packageName)
        val reason =
            when {
                manuallyPaused -> SecurityShieldReason.MANUALLY_PAUSED
                assessment.classification == PackageClassification.UNKNOWN ->
                    SecurityShieldReason.FOREGROUND_UNKNOWN
                assessment.classification == PackageClassification.BLOCKED ->
                    SecurityShieldReason.PACKAGE_BLOCKED
                observation.secureWindowDetected -> SecurityShieldReason.SECURE_WINDOW
                isBiometricSurface(observation.packageName, observation.className) ->
                    SecurityShieldReason.BIOMETRIC_ACTIVE
                else -> SecurityShieldReason.SAFE
            }
        return SecurityShieldState(
            reason = reason,
            packageName = assessment.packageName,
            windowClassName = observation.className,
            trustedPackage = assessment.classification == PackageClassification.TRUSTED,
            observedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private fun isBiometricSurface(
        packageName: String?,
        className: String?,
    ): Boolean {
        val signature = "${packageName.orEmpty()}/${className.orEmpty()}".lowercase()
        return biometricMarkers.any(signature::contains)
    }

    private fun updateState(change: () -> SecurityShieldState): SecurityShieldState {
        val (previous, next) =
            synchronized(lock) {
                val previousState = state
                val candidate = change()
                val nextState = if (sameSecurityState(previousState, candidate)) previousState else candidate
                state = nextState
                previousState to nextState
            }
        if (next !== previous) {
            listeners.forEach { listener -> runCatching { listener(next) } }
        }
        return next
    }

    private fun sameSecurityState(
        a: SecurityShieldState,
        b: SecurityShieldState,
    ): Boolean =
        a.reason == b.reason &&
            a.packageName == b.packageName &&
            a.windowClassName == b.windowClassName &&
            a.trustedPackage == b.trustedPackage

    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    private val knownInputMethodPackages =
        setOf(
            "com.google.android.inputmethod.latin",
            "com.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.sec.android.inputmethod",
            "com.touchtype.swiftkey",
            "com.microsoft.emmx.keyboard",
            "dev.patrickgold.florisboard",
        )

    private val imePackageSegmentPattern =
        Regex(
            pattern = "(^|\\.)(inputmethod|keyboard|ime|latinime|honeyboard|swiftkey|gboard|florisboard)(\\.|$)",
            option = RegexOption.IGNORE_CASE,
        )

    private val imeOrOverlayClassMarkers =
        listOf(
            "inputmethod",
            "softinput",
            "popupwindow",
            "toast",
            "floatingtoolbar",
            "magnifier",
            "autofill",
            "clipboardoverlay",
            "volumedialog",
            "navigationbar",
            "statusbar",
            "headsup",
            "notificationpanel",
            "systemuitoast",
        )

    private val biometricMarkers =
        listOf(
            "biometricprompt",
            "biometric",
            "fingerprint",
            "faceauth",
            "faceunlock",
            "authcredential",
            "confirmdevicecredential",
            "keyguard",
        )
}
