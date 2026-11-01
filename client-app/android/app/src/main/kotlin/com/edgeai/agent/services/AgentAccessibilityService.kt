package com.edgeai.agent.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.edgeai.agent.security.SecurityShield
import com.edgeai.agent.security.SecurityShieldReason
import com.edgeai.agent.security.SecurityShieldState

class AgentAccessibilityService : AccessibilityService() {
    data class WindowNodeSnapshot(
        val packageName: CharSequence?,
        val className: CharSequence?,
    )

    companion object {
        private const val TELEGRAM_PACKAGE = "org.telegram.messenger"
        private const val TELEGRAM_INPUT_ID = "$TELEGRAM_PACKAGE:id/chat_text_input"
        private const val TELEGRAM_SEND_ID = "$TELEGRAM_PACKAGE:id/chat_send_button"

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set
    }

    internal var activeWindowRootProvider: () -> WindowNodeSnapshot? = {
        rootInActiveWindow?.let { WindowNodeSnapshot(it.packageName, it.className) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (AgentForegroundService.shouldStartAfterBoot(this) && !AgentForegroundService.isRunning) {
            AgentForegroundService.start(this, rememberUserChoice = false)
        }
        refreshForegroundSecurityState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            SecurityShield.observeWindow(null, null)
            return
        }
        handleAccessibilityEvent(
            eventType = event.eventType,
            packageName = event.packageName,
            className = event.className,
            isPassword = event.isPassword,
        )
    }

    internal fun handleAccessibilityEvent(
        eventType: Int,
        packageName: CharSequence?,
        className: CharSequence?,
        isPassword: Boolean = false,
    ): SecurityShieldState {
        val windowTransition =
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (windowTransition) {
            val root = activeWindowRootProvider()
            return SecurityShield.observeWindowTransition(
                eventPackageName = packageName,
                eventClassName = className,
                activeRootPackageName = root?.packageName,
                activeRootClassName = root?.className,
                secureWindowDetected = isPassword,
            )
        }

        val eventPackage = packageName?.toString()
        return when {
            isPassword ->
                SecurityShield.markSecureWindow(
                    SecurityShield.currentState().packageName ?: eventPackage,
                    className?.toString(),
                )
            else -> SecurityShield.observePotentialSecuritySignal(packageName, className)
        }
    }

    override fun onInterrupt() {
        // The service remains bound after an interrupt; invalidate only foreground state.
        SecurityShield.observeWindow(null, null)
    }

    override fun onDestroy() {
        instance = null
        SecurityShield.observeWindow(null, null)
        super.onDestroy()
    }

    /** Returns only a freshly confirmed active-root owner, never a cached SAFE package. */
    fun foregroundPackageName(): String? {
        val current = SecurityShield.currentState()
        if (current.blocked && current.reason != SecurityShieldReason.FOREGROUND_UNKNOWN) return null

        val observed = refreshForegroundSecurityState()
        return if (observed.blocked) null else observed.packageName
    }

    /** Messenger method retained from main, with every UI action routed through shielded helpers. */
    fun sendTelegramMessage(
        recipientName: String,
        messageText: String,
    ): Boolean {
        if (recipientName.isBlank() || messageText.isBlank()) return false

        val root = safeRoot() ?: return false
        if (root.packageName?.toString() != TELEGRAM_PACKAGE) return false
        if (SecurityShield.validateAction(TELEGRAM_PACKAGE).isFailure) return false

        val chatNode = root.findAccessibilityNodeInfosByText(recipientName).firstOrNull()
        if (!isTelegramNode(chatNode) || !clickNode(chatNode)) return false
        Thread.sleep(300)

        val input = findNodeById(TELEGRAM_INPUT_ID)
        if (!isTelegramNode(input) || !setText(input, messageText)) return false
        Thread.sleep(150)

        val sendButton = findNodeById(TELEGRAM_SEND_ID)
        return isTelegramNode(sendButton) && clickNode(sendButton)
    }

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        if (text.isBlank()) return null
        val root = safeRoot() ?: return null
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull()
    }

    fun findNodeById(viewId: String): AccessibilityNodeInfo? {
        if (viewId.isBlank()) return null
        val root = safeRoot() ?: return null
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        return nodes.firstOrNull()
    }

    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (!guardNode(node)) return false
        node ?: return false
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var parent = node.parent
        while (parent != null) {
            if (!guardNode(parent)) return false
            if (parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
        }
        return false
    }

    fun setText(
        node: AccessibilityNodeInfo?,
        text: String,
    ): Boolean {
        if (!guardNode(node) || node?.isPassword == true) return false
        node ?: return false
        val args =
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun tapCoordinates(
        x: Float,
        y: Float,
        durationMs: Long = 50,
    ): Boolean {
        if (!x.isFinite() || !y.isFinite() || x < 0f || y < 0f || durationMs !in 1L..5_000L) {
            return false
        }
        if (SecurityShield.currentState().blocked) return false
        val packageName = foregroundPackageName()
        if (SecurityShield.validateAction(packageName).isFailure) return false

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun refreshForegroundSecurityState(): SecurityShieldState {
        val root = activeWindowRootProvider()
        return SecurityShield.observeWindowTransition(
            eventPackageName = null,
            eventClassName = null,
            activeRootPackageName = root?.packageName,
            activeRootClassName = root?.className,
        )
    }

    /** Reads only the active root's package/class before allowing tree traversal. */
    private fun safeRoot(): AccessibilityNodeInfo? {
        val current = SecurityShield.currentState()
        if (current.blocked && current.reason != SecurityShieldReason.FOREGROUND_UNKNOWN) return null

        val root =
            rootInActiveWindow ?: run {
                SecurityShield.observeWindow(null, null)
                return null
            }
        val evaluated = SecurityShield.observeWindow(root.packageName, root.className)
        return if (evaluated.blocked) null else root
    }

    private fun guardNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null || SecurityShield.currentState().blocked) return false
        val evaluated = SecurityShield.observeWindow(node.packageName, node.className)
        return !evaluated.blocked && SecurityShield.validateAction(evaluated.packageName).isSuccess
    }

    private fun isTelegramNode(node: AccessibilityNodeInfo?): Boolean =
        node?.packageName?.toString() == TELEGRAM_PACKAGE
}
