package com.edgeai.agent.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.edgeai.agent.R
import com.edgeai.agent.services.AgentAccessibilityService

enum class AccessibilityOnboardingStatus {
    READY,
    ACCESSIBILITY_DISABLED,
    RESTRICTED_SETTINGS_MAY_BE_REQUIRED,
}

data class AccessibilityOnboardingState(
    val status: AccessibilityOnboardingStatus,
    val accessibilityEnabled: Boolean,
    val restrictedSettingsMayBeRequired: Boolean,
    val installerPackage: String?,
    val androidApi: Int,
    val onboardingOffered: Boolean = false,
    val onboardingDismissed: Boolean = false,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "status" to status.name,
            "accessibilityEnabled" to accessibilityEnabled,
            "restrictedSettingsMayBeRequired" to restrictedSettingsMayBeRequired,
            "installerPackage" to installerPackage,
            "androidApi" to androidApi,
            "onboardingOffered" to onboardingOffered,
            "onboardingDismissed" to onboardingDismissed,
            "requiresUserAction" to (status != AccessibilityOnboardingStatus.READY),
        )
}

/** User-guided Android 13+ onboarding. This helper never grants settings itself. */
object RestrictedSettingsHelper {
    private const val ACCESSIBILITY_DETAILS_ACTION =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    private const val PREFERENCES = "edge_agent_onboarding"
    private const val PREF_ONBOARDING_OFFERED = "accessibility_onboarding_offered"
    private const val PREF_ONBOARDING_DISMISSED = "accessibility_onboarding_dismissed"

    @Volatile
    private var onboardingOfferedInSession: Boolean = false

    @Volatile
    private var onboardingDismissedInSession: Boolean = false

    private val trustedInstallers =
        setOf(
            "com.android.vending",
            "com.sec.android.app.samsungapps",
            "com.amazon.venezia",
            "com.huawei.appmarket",
        )

    fun getState(context: Context): AccessibilityOnboardingState {
        val enabled = isAccessibilityEnabled(context)
        if (enabled) {
            resetOnboardingPromptState(context)
        }
        val installer = getInstallerPackage(context)
        val mayBeRestricted =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !enabled && (installer == null || installer !in trustedInstallers)
        val status =
            when {
                enabled -> AccessibilityOnboardingStatus.READY
                mayBeRestricted -> AccessibilityOnboardingStatus.RESTRICTED_SETTINGS_MAY_BE_REQUIRED
                else -> AccessibilityOnboardingStatus.ACCESSIBILITY_DISABLED
            }
        return AccessibilityOnboardingState(
            status = status,
            accessibilityEnabled = enabled,
            restrictedSettingsMayBeRequired = mayBeRestricted,
            installerPackage = installer,
            androidApi = Build.VERSION.SDK_INT,
            onboardingOffered = isOnboardingOffered(context),
            onboardingDismissed = isOnboardingDismissed(context),
        )
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val manager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { enabledService ->
                val serviceInfo = enabledService.resolveInfo.serviceInfo
                serviceInfo.packageName == context.packageName &&
                    normalizeClassName(serviceInfo.packageName, serviceInfo.name) ==
                    AgentAccessibilityService::class.java.name
            }
    }

    fun isOnboardingOffered(context: Context): Boolean =
        onboardingOfferedInSession ||
            (
                context
                    .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    ?.getBoolean(PREF_ONBOARDING_OFFERED, false) ?: false
            )

    fun isOnboardingDismissed(context: Context): Boolean =
        onboardingDismissedInSession ||
            (
                context
                    .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    ?.getBoolean(PREF_ONBOARDING_DISMISSED, false) ?: false
            )

    fun markOnboardingOffered(context: Context) {
        onboardingOfferedInSession = true
        runCatching {
            context
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                ?.edit()
                ?.putBoolean(PREF_ONBOARDING_OFFERED, true)
                ?.apply()
        }
    }

    fun markOnboardingDismissed(context: Context) {
        onboardingOfferedInSession = true
        onboardingDismissedInSession = true
        runCatching {
            context
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                ?.edit()
                ?.putBoolean(PREF_ONBOARDING_OFFERED, true)
                ?.putBoolean(PREF_ONBOARDING_DISMISSED, true)
                ?.apply()
        }
    }

    fun resetOnboardingPromptState(context: Context) {
        onboardingOfferedInSession = false
        onboardingDismissedInSession = false
        runCatching {
            context
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                ?.edit()
                ?.putBoolean(PREF_ONBOARDING_OFFERED, false)
                ?.putBoolean(PREF_ONBOARDING_DISMISSED, false)
                ?.apply()
        }
    }

    /**
     * Returns true only when Accessibility is disabled and onboarding has not yet been
     * offered or dismissed (persisted across Activity recreations).
     */
    fun shouldAutoShowOnboarding(context: Context): Boolean {
        val state = getState(context)
        if (state.accessibilityEnabled) return false
        return !state.onboardingOffered && !state.onboardingDismissed
    }

    /**
     * One-shot onboarding flow for `MainActivity.onPostResume`: shows the dialog at most
     * once across Activity recreations unless explicitly requested by the user via UI.
     */
    fun maybeShowOnboardingOnce(
        activity: Activity,
        dialogPresenter: (Activity) -> Unit = ::showOnboardingDialog,
    ): Boolean {
        if (!shouldAutoShowOnboarding(activity)) return false
        markOnboardingOffered(activity)
        dialogPresenter(activity)
        return true
    }

    fun openAppDetails(context: Context): Boolean =
        startSafely(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )

    fun openAccessibilitySettings(context: Context): Boolean {
        val details =
            Intent(ACCESSIBILITY_DETAILS_ACTION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        if (startSafely(context, details)) return true
        return startSafely(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun showOnboardingDialog(activity: Activity) {
        markOnboardingOffered(activity)
        val state = getState(activity)
        val message =
            if (state.restrictedSettingsMayBeRequired) {
                R.string.restricted_settings_instructions
            } else {
                R.string.accessibility_enable_instructions
            }
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.accessibility_onboarding_title)
            .setMessage(message)
            .setPositiveButton(R.string.open_app_info) { _, _ -> openAppDetails(activity) }
            .setNeutralButton(R.string.open_accessibility_settings) { _, _ ->
                openAccessibilitySettings(activity)
            }.setNegativeButton(android.R.string.cancel) { _, _ ->
                markOnboardingDismissed(activity)
            }.setOnCancelListener {
                markOnboardingDismissed(activity)
            }.show()
    }

    @Suppress("DEPRECATION")
    private fun getInstallerPackage(context: Context): String? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val source = context.packageManager.getInstallSourceInfo(context.packageName)
                source.installingPackageName ?: source.initiatingPackageName ?: source.originatingPackageName
            } else {
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()

    private fun normalizeClassName(
        packageName: String,
        className: String,
    ): String =
        when {
            className.startsWith(".") -> packageName + className
            '.' !in className -> "$packageName.$className"
            else -> className
        }

    private fun startSafely(
        context: Context,
        intent: Intent,
    ): Boolean =
        runCatching {
            if (intent.resolveActivity(context.packageManager) == null) return@runCatching false
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
}
