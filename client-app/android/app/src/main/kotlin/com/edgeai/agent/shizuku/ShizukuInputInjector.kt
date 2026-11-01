package com.edgeai.agent.shizuku

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.edgeai.agent.security.SecurityShield

// ShizukuInputInjector: input events via IInputManager.injectInputEvent Binder transaction
object ShizukuInputInjector {
    private const val DESCRIPTOR = "android.hardware.input.IInputManager"
    private const val MODE_ASYNC = 0
    private const val FRAME_MS = 16L

    private val injectCode: Int? by lazy {
        ShizukuBridge.transactionCode("$DESCRIPTOR\$Stub", "TRANSACTION_injectInputEvent")
    }

    fun isSupported(): Boolean = injectCode != null

    fun tap(
        x: Float,
        y: Float,
    ): Boolean {
        val downTime = SystemClock.uptimeMillis()
        return motion(downTime, MotionEvent.ACTION_DOWN, x, y) &&
            motion(downTime, MotionEvent.ACTION_UP, x, y)
    }

    fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val steps = (durationMs / FRAME_MS).coerceAtLeast(1)
        if (!motion(downTime, MotionEvent.ACTION_DOWN, x1, y1)) return false
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            Thread.sleep(FRAME_MS)
            if (!motion(downTime, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t)) return false
        }
        return motion(downTime, MotionEvent.ACTION_UP, x2, y2)
    }

    fun key(keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        return inject(keyEvent(now, KeyEvent.ACTION_DOWN, keyCode)) &&
            inject(keyEvent(now, KeyEvent.ACTION_UP, keyCode))
    }

    // Only chars present in the virtual keyboard map (ASCII) can be typed this way
    fun text(text: String): Boolean {
        val events =
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
                ?: throw IllegalArgumentException("Text has chars unsupported by key injection")
        val now = SystemClock.uptimeMillis()
        return events.all { ev ->
            val fresh = KeyEvent.changeTimeRepeat(ev, now, 0)
            fresh.source = InputDevice.SOURCE_KEYBOARD
            inject(fresh)
        }
    }

    private fun keyEvent(
        downTime: Long,
        action: Int,
        keyCode: Int,
    ) = KeyEvent(
        downTime,
        SystemClock.uptimeMillis(),
        action,
        keyCode,
        0,
        0,
        KeyCharacterMap.VIRTUAL_KEYBOARD,
        0,
        0,
        InputDevice.SOURCE_KEYBOARD,
    )

    private fun motion(
        downTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): Boolean {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            return inject(event)
        } finally {
            event.recycle()
        }
    }

    // Parcel layout: non-null marker, InputEvent (with type token), injection mode
    private fun inject(event: InputEvent): Boolean {
        // Re-check for every gesture frame so a newly opened protected window stops a swipe.
        if (SecurityShield.currentState().blocked) return false
        val code = injectCode ?: throw IllegalStateException("injectInputEvent code not found")
        return ShizukuBridge.transact(
            service = "input",
            descriptor = DESCRIPTOR,
            code = code,
            write = { data ->
                data.writeInt(1)
                event.writeToParcel(data, 0)
                data.writeInt(MODE_ASYNC)
            },
            read = { reply -> reply.readInt() != 0 },
        )
    }
}
