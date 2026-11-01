package com.edgeai.agent

import com.edgeai.agent.security.RestrictedSettingsHelper
import com.edgeai.agent.security.SecurityShield
import com.edgeai.agent.services.AgentAccessibilityService
import com.edgeai.agent.services.AgentForegroundService
import com.edgeai.agent.services.AgentNotificationListener
import com.edgeai.agent.shizuku.ShizukuAction
import com.edgeai.agent.shizuku.ShizukuActionExecutor
import com.edgeai.agent.shizuku.ShizukuBridge
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "com.edgeai.agent/native"

    override fun onPostResume() {
        super.onPostResume()
        if (AgentForegroundService.isBootRestartPending(this) && !AgentForegroundService.isRunning) {
            AgentForegroundService.start(this, rememberUserChoice = false)
        }
        RestrictedSettingsHelper.maybeShowOnboardingOnce(this)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "startBackgroundService" -> {
                    result.success(AgentForegroundService.start(this))
                }
                "isAccessibilityEnabled" -> {
                    result.success(RestrictedSettingsHelper.isAccessibilityEnabled(this))
                }
                "getAccessibilityOnboardingStatus" -> {
                    result.success(RestrictedSettingsHelper.getState(this).toMap())
                }
                "showAccessibilityOnboarding" -> {
                    RestrictedSettingsHelper.showOnboardingDialog(this)
                    result.success(true)
                }
                "dismissAccessibilityOnboarding" -> {
                    RestrictedSettingsHelper.markOnboardingDismissed(this)
                    result.success(true)
                }
                "openAppDetails" -> {
                    result.success(RestrictedSettingsHelper.openAppDetails(this))
                }
                "openAccessibilitySettings" -> {
                    result.success(RestrictedSettingsHelper.openAccessibilitySettings(this))
                }
                "securityShieldStatus" -> {
                    result.success(SecurityShield.currentState().toMap())
                }
                "pauseAutomation" -> {
                    result.success(SecurityShield.pauseAutomation().toMap())
                }
                "resumeAutomation" -> {
                    result.success(SecurityShield.resumeAutomation().toMap())
                }
                "sendTelegramMessage" -> {
                    val args = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
                    val recipient = args["recipient"] as? String ?: ""
                    val message = args["message"] as? String ?: ""
                    val success =
                        AgentAccessibilityService.instance
                            ?.sendTelegramMessage(recipient, message)
                            ?: false
                    result.success(success)
                }
                "readRecentMessages" -> {
                    val messages =
                        AgentNotificationListener
                            .readRecentMessages(20)
                            .map { message ->
                                mapOf(
                                    "packageName" to message.packageName,
                                    "title" to message.title,
                                    "text" to message.text,
                                )
                            }
                    result.success(messages)
                }
                "shizukuStatus" -> {
                    result.success(mapOf("state" to ShizukuBridge.state().name, "uid" to ShizukuBridge.uid()))
                }
                "shizukuRequestPermission" -> {
                    ShizukuBridge.requestPermission { granted -> runOnUiThread { result.success(granted) } }
                }
                "shizukuExecute" -> {
                    val action = ShizukuAction.fromMap(call.arguments as? Map<*, *> ?: emptyMap<Any, Any>())
                    action
                        .onSuccess { a ->
                            ShizukuActionExecutor.executeAsync(a) { r -> runOnUiThread { result.success(r.toMap()) } }
                        }.onFailure { result.error("INVALID_ACTION", it.message, null) }
                }
                else -> result.notImplemented()
            }
        }
    }
}
