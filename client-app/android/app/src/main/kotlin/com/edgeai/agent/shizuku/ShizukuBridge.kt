package com.edgeai.agent.shizuku

import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcel
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class ShizukuState { UNAVAILABLE, PERMISSION_DENIED, READY }

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

// ShizukuBridge: low-level access to the Shizuku binder (shell UID 2000 or root)
object ShizukuBridge {
    private const val TAG = "ShizukuBridge"
    private const val REQUEST_CODE = 4202

    @Volatile private var pendingPermissionCallback: ((Boolean) -> Unit)? = null
    private var permissionListenerAdded = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
        if (code == REQUEST_CODE) {
            pendingPermissionCallback?.invoke(grantResult == PackageManager.PERMISSION_GRANTED)
            pendingPermissionCallback = null
        }
    }

    // Hidden API exemption is needed to read IInputManager$Stub.TRANSACTION_* fields
    private val hiddenApiUnlocked: Boolean by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            true
        } else {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }.getOrDefault(false)
        }
    }

    // newProcess() is private since Shizuku API 13, so call it by reflection
    private val newProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    fun state(): ShizukuState = try {
        when {
            !Shizuku.pingBinder() || Shizuku.isPreV11() -> ShizukuState.UNAVAILABLE
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
            else -> ShizukuState.PERMISSION_DENIED
        }
    } catch (e: Throwable) {
        ShizukuState.UNAVAILABLE
    }

    // 2000 = ADB shell, 0 = root, -1 = unknown
    fun uid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    @Synchronized
    fun requestPermission(callback: (Boolean) -> Unit) {
        when (state()) {
            ShizukuState.UNAVAILABLE -> callback(false)
            ShizukuState.READY -> callback(true)
            ShizukuState.PERMISSION_DENIED -> {
                if (!permissionListenerAdded) {
                    Shizuku.addRequestPermissionResultListener(permissionListener)
                    permissionListenerAdded = true
                }
                pendingPermissionCallback = callback
                Shizuku.requestPermission(REQUEST_CODE)
            }
        }
    }

    // Runs a command as shell UID. Args go straight to exec (no 'sh -c'), so no injection
    fun exec(vararg args: String, timeoutMs: Long = 5000): ShellResult {
        val process = try {
            newProcessMethod.invoke(null, arrayOf(*args), null, null) as Process
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException ?: e
        }

        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = drain(process.inputStream, out)
        val errThread = drain(process.errorStream, err)

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroy()
        outThread.join(500)
        errThread.join(500)

        val code = if (finished) process.exitValue() else -1
        return ShellResult(code, out.toString().trim(), err.toString().trim(), !finished)
    }

    private fun drain(stream: InputStream, sink: StringBuilder): Thread =
        thread(isDaemon = true) {
            runCatching { stream.bufferedReader().forEachLine { sink.appendLine(it) } }
        }

    // Resolves a hidden AIDL transaction code, e.g. TRANSACTION_injectInputEvent
    fun transactionCode(stubClass: String, field: String): Int? {
        if (!hiddenApiUnlocked) return null
        return runCatching {
            val f = Class.forName(stubClass).getDeclaredField(field)
            f.isAccessible = true
            f.getInt(null)
        }.onFailure { Log.w(TAG, "No transaction code $stubClass#$field: ${it.message}") }
            .getOrNull()
    }

    // Raw Binder call into a system service with shell privileges
    fun <T> transact(
        service: String,
        descriptor: String,
        code: Int,
        write: (Parcel) -> Unit,
        read: (Parcel) -> T
    ): T {
        val raw = SystemServiceHelper.getSystemService(service)
            ?: throw IllegalStateException("System service '$service' not found")
        val binder = ShizukuBinderWrapper(raw)

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            write(data)
            if (!binder.transact(code, data, reply, 0)) {
                throw IllegalStateException("transact($code) rejected by '$service'")
            }
            reply.readException()
            return read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
