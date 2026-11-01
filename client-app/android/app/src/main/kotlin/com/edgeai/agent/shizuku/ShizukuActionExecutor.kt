package com.edgeai.agent.shizuku

import android.util.Log
import com.edgeai.agent.security.SecurityShield
import com.edgeai.agent.security.SecurityShieldReason
import com.edgeai.agent.security.SecurityShieldState
import com.edgeai.agent.services.AgentAccessibilityService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// High-level actions the agent can request (mirrors the JSON action schema)
sealed class ShizukuAction {
    data class Tap(
        val x: Float,
        val y: Float,
    ) : ShizukuAction()

    data class Swipe(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val durationMs: Long = 300,
    ) : ShizukuAction()

    data class Key(
        val keyCode: Int,
    ) : ShizukuAction()

    data class Text(
        val text: String,
    ) : ShizukuAction()

    data class Launch(
        val packageName: String,
    ) : ShizukuAction()

    data class ForceStop(
        val packageName: String,
    ) : ShizukuAction()

    companion object {
        // Parses a Flutter/JSON map, e.g. {"action":"tap","x":540,"y":1200}
        fun fromMap(m: Map<*, *>): Result<ShizukuAction> =
            runCatching {
                fun num(key: String) =
                    (m[key] as? Number)?.toFloat()
                        ?: throw IllegalArgumentException("Missing number '$key'")

                fun str(key: String) =
                    m[key] as? String
                        ?: throw IllegalArgumentException("Missing string '$key'")

                when (val name = m["action"]) {
                    "tap" -> Tap(num("x"), num("y"))
                    "swipe" ->
                        Swipe(
                            num("x1"),
                            num("y1"),
                            num("x2"),
                            num("y2"),
                            (m["durationMs"] as? Number)?.toLong() ?: 300L,
                        )
                    "key" -> Key(num("keyCode").toInt())
                    "text" -> Text(str("text"))
                    "launch" -> Launch(str("packageName"))
                    "force_stop" -> ForceStop(str("packageName"))
                    else -> throw IllegalArgumentException("Unknown action '$name'")
                }
            }
    }
}

data class ActionResult(
    val success: Boolean,
    val channel: String,
    val detail: String,
) {
    fun toMap(): Map<String, Any> = mapOf("success" to success, "channel" to channel, "detail" to detail)
}

// ShizukuActionExecutor: shield check -> Binder transact -> shell cmd fallback
object ShizukuActionExecutor {
    private const val TAG = "ShizukuActionExecutor"

    const val ERR_NOT_READY = "ERR_SHIZUKU_NOT_READY"
    const val ERR_INVALID_PACKAGE = "ERR_INVALID_PACKAGE"
    const val ERR_FOREGROUND_UNKNOWN = "ERR_FOREGROUND_UNKNOWN"

    private val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val FOCUS_REGEX = Regex("mCurrentFocus=Window\\{[0-9a-f]+ u\\d+ ([\\w.]+)[/}]")

    private class QueuedAction(
        private val action: ShizukuAction,
        private val callback: (ActionResult) -> Unit,
    ) : Runnable {
        private val completed = AtomicBoolean(false)

        override fun run() {
            complete(execute(action))
        }

        fun cancel(state: SecurityShieldState) {
            complete(ActionResult(false, "shield", state.reason.errorCode))
        }

        private fun complete(result: ActionResult) {
            if (completed.compareAndSet(false, true)) {
                runCatching { callback(result) }
            }
        }
    }

    // A single worker keeps gestures ordered; pending actions are drained on a shield event.
    private val queue =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue<Runnable>(),
            { r -> Thread(r, "shizuku-actions").apply { isDaemon = true } },
        )

    init {
        SecurityShield.addListener { state ->
            if (state.blocked) cancelPendingActions(state)
        }
    }

    fun executeAsync(
        action: ShizukuAction,
        callback: (ActionResult) -> Unit,
    ) {
        queue.execute(QueuedAction(action, callback))
    }

    fun execute(action: ShizukuAction): ActionResult {
        shieldCheck(action)?.let { return it }
        if (ShizukuBridge.state() != ShizukuState.READY) {
            return ActionResult(false, "none", ERR_NOT_READY)
        }

        return try {
            when (action) {
                is ShizukuAction.Tap ->
                    viaBinderOrShell(
                        action,
                        { ShizukuInputInjector.tap(action.x, action.y) },
                        arrayOf("input", "tap", action.x.toInt().toString(), action.y.toInt().toString()),
                    )
                is ShizukuAction.Swipe ->
                    viaBinderOrShell(
                        action,
                        { ShizukuInputInjector.swipe(action.x1, action.y1, action.x2, action.y2, action.durationMs) },
                        arrayOf(
                            "input",
                            "swipe",
                            action.x1.toInt().toString(),
                            action.y1.toInt().toString(),
                            action.x2.toInt().toString(),
                            action.y2.toInt().toString(),
                            action.durationMs.toString(),
                        ),
                    )
                is ShizukuAction.Key ->
                    viaBinderOrShell(
                        action,
                        { ShizukuInputInjector.key(action.keyCode) },
                        arrayOf("input", "keyevent", action.keyCode.toString()),
                    )
                is ShizukuAction.Text ->
                    viaBinderOrShell(
                        action,
                        { ShizukuInputInjector.text(action.text) },
                        arrayOf("input", "text", action.text.replace(" ", "%s")),
                    )
                is ShizukuAction.Launch ->
                    shellGuarded(
                        action,
                        "monkey",
                        "-p",
                        action.packageName,
                        "-c",
                        "android.intent.category.LAUNCHER",
                        "1",
                    ) { !it.stdout.contains("No activities found") }
                is ShizukuAction.ForceStop -> shellGuarded(action, "am", "force-stop", action.packageName)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Action failed: $action", e)
            ActionResult(false, "none", e.message ?: e.javaClass.simpleName)
        }
    }

    // Fail closed: unknown foreground app blocks coordinate/text/key actions
    private fun shieldCheck(action: ShizukuAction): ActionResult? {
        val current = SecurityShield.currentState()
        if (current.blocked && current.reason != SecurityShieldReason.FOREGROUND_UNKNOWN) {
            return ActionResult(false, "shield", current.reason.errorCode)
        }

        val foreground =
            foregroundPackage()
                ?: return ActionResult(false, "shield", ERR_FOREGROUND_UNKNOWN)
        val observed = SecurityShield.observeWindow(foreground, null)
        if (observed.blocked) {
            return ActionResult(false, "shield", observed.reason.errorCode)
        }

        val pkg =
            when (action) {
                is ShizukuAction.Launch -> action.packageName
                is ShizukuAction.ForceStop -> action.packageName
                else -> foreground
            }
        if (action is ShizukuAction.Launch || action is ShizukuAction.ForceStop) {
            if (!PACKAGE_REGEX.matches(pkg)) return ActionResult(false, "shield", ERR_INVALID_PACKAGE)
        }
        return SecurityShield
            .validateAction(pkg)
            .exceptionOrNull()
            ?.let { ActionResult(false, "shield", it.message ?: "ERR_SECURITY_SHIELD_BLOCKED") }
    }

    // Accessibility is the fast path, dumpsys through Shizuku is the fallback
    private fun foregroundPackage(): String? {
        AgentAccessibilityService.instance
            ?.foregroundPackageName()
            ?.let { return it }
        val dump =
            runCatching { ShizukuBridge.exec("dumpsys", "window", timeoutMs = 3000) }
                .getOrNull() ?: return null
        return FOCUS_REGEX.find(dump.stdout)?.groupValues?.get(1)
    }

    private fun viaBinderOrShell(
        action: ShizukuAction,
        binder: () -> Boolean,
        shellArgs: Array<String>,
    ): ActionResult {
        shieldCheck(action)?.let { return it }
        val binderError =
            try {
                if (ShizukuInputInjector.isSupported() && binder()) {
                    return ActionResult(true, "binder", "ok")
                }
                "binder returned false"
            } catch (e: IllegalArgumentException) {
                return ActionResult(false, "binder", e.message ?: "invalid input")
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
        Log.w(TAG, "Binder path failed ($binderError), falling back to shell")
        shieldCheck(action)?.let { return it }
        return shell(*shellArgs)
    }

    private fun shellGuarded(
        action: ShizukuAction,
        vararg args: String,
        accept: (ShellResult) -> Boolean = { true },
    ): ActionResult {
        shieldCheck(action)?.let { return it }
        return shell(*args, accept = accept)
    }

    private fun shell(
        vararg args: String,
        accept: (ShellResult) -> Boolean = { true },
    ): ActionResult {
        val r = ShizukuBridge.exec(*args)
        val ok = r.ok && accept(r)
        return ActionResult(ok, "shell", if (ok) "ok" else r.stderr.ifBlank { r.stdout }.ifBlank { "exit ${r.exitCode}" })
    }

    private fun cancelPendingActions(state: SecurityShieldState) {
        val cancelled = mutableListOf<Runnable>()
        queue.queue.drainTo(cancelled)
        cancelled.filterIsInstance<QueuedAction>().forEach { it.cancel(state) }
    }
}
