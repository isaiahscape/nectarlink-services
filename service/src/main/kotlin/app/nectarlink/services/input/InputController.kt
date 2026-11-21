// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.input

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import app.nectarlink.services.client.NectarlinkServiceContract
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class InputController(private val onInputBlocked: () -> Unit = {}) {
    private val manager: Any?
    private val inject: Method?
    private val setDisplayId: Method? = runCatching {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }.getOrNull()

    private var downTime = 0L
    private var lastGamepadButtons = 0
    @Volatile
    private var blockedReported = false

    init {
        var resolvedManager: Any? = null
        var resolvedInject: Method? = null
        runCatching {
            val global = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }.getOrNull()
            val owner = global ?: Class.forName("android.hardware.input.InputManager")
            resolvedManager = owner.getDeclaredMethod("getInstance").invoke(null)
            resolvedInject = owner.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        }.onFailure {
            Log.e(TAG, "InputManager reflection init failed", it)
        }
        manager = resolvedManager
        inject = resolvedInject
    }

    val isReady: Boolean get() = manager != null && inject != null

    fun resetBlockedState() {
        blockedReported = false
    }

    fun sendEvent(displayId: Int, event: InputEvent): Boolean {
        val mgr = manager ?: return false
        val method = inject ?: return false
        if (displayId >= 0) {
            runCatching { setDisplayId?.invoke(event, displayId) }
        }
        return try {
            method.invoke(mgr, event, 0)
            true
        } catch (e: InvocationTargetException) {
            if (e.cause is SecurityException) {
                if (!blockedReported) {
                    blockedReported = true
                    onInputBlocked()
                }
            }
            Log.w(TAG, "injectInputEvent failed", e.cause ?: e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "injectInputEvent failed", e)
            false
        }
    }

    fun touch(displayId: Int, action: Int, x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val kind = when (action) {
            NectarlinkServiceContract.TOUCH_DOWN -> {
                downTime = now
                MotionEvent.ACTION_DOWN
            }
            NectarlinkServiceContract.TOUCH_MOVE -> MotionEvent.ACTION_MOVE
            else -> MotionEvent.ACTION_UP
        }
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = if (kind == MotionEvent.ACTION_UP) 0f else 1f
                size = 1f
            },
        )
        val event = MotionEvent.obtain(
            downTime, now, kind, 1, properties, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        return try {
            sendEvent(displayId, event)
        } finally {
            event.recycle()
        }
    }

    fun scroll(displayId: Int, x: Float, y: Float, dx: Float, dy: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                setAxisValue(MotionEvent.AXIS_VSCROLL, -dy)
                setAxisValue(MotionEvent.AXIS_HSCROLL, dx)
            },
        )
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, properties, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0,
        )
        return try {
            sendEvent(displayId, event)
        } finally {
            event.recycle()
        }
    }

    fun key(displayId: Int, code: Int, metaState: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()
        var ok = true
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            ok = sendEvent(
                displayId,
                KeyEvent(now, now, action, code, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
            ) && ok
        }
        return ok
    }

    fun text(displayId: Int, text: String): Boolean {
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        var ok = true
        for (c in text) {
            val events = map.getEvents(charArrayOf(c)) ?: continue
            for (ev in events) {
                ok = sendEvent(displayId, ev) && ok
            }
        }
        return ok
    }

    fun gamepad(displayId: Int, buttons: Int, lx: Int, ly: Int, rx: Int, ry: Int, lt: Int, rt: Int): Boolean {
        if (displayId < 0 && UhidBridge.sendGamepadReport(buttons, lx, ly, rx, ry, lt, rt)) {
            lastGamepadButtons = buttons
            return true
        }
        val now = SystemClock.uptimeMillis()
        val changed = buttons xor lastGamepadButtons
        lastGamepadButtons = buttons
        var ok = true
        for ((mask, keyCode) in GamepadMapping.KEYCODES) {
            if ((changed and mask) != 0) {
                val action = if ((buttons and mask) != 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
                ok = sendEvent(
                    displayId,
                    KeyEvent(
                        now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                        InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD,
                    ),
                ) && ok
            }
        }

        fun normAxis(v: Int): Float = v.coerceIn(-32767, 32767) / 32767f
        fun normTrigger(v: Int): Float = v.coerceIn(0, 255) / 255f
        val (hatX, hatY) = GamepadMapping.hatAxes(buttons)

        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_UNKNOWN
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                setAxisValue(MotionEvent.AXIS_X, normAxis(lx))
                setAxisValue(MotionEvent.AXIS_Y, normAxis(GamepadMapping.stickY(ly)))
                setAxisValue(MotionEvent.AXIS_Z, normAxis(rx))
                setAxisValue(MotionEvent.AXIS_RZ, normAxis(GamepadMapping.stickY(ry)))
                setAxisValue(MotionEvent.AXIS_LTRIGGER, normTrigger(lt))
                setAxisValue(MotionEvent.AXIS_RTRIGGER, normTrigger(rt))
                setAxisValue(MotionEvent.AXIS_HAT_X, hatX.toFloat())
                setAxisValue(MotionEvent.AXIS_HAT_Y, hatY.toFloat())
            },
        )
        val motion = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_MOVE, 1, properties, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD, 0,
        )
        return try {
            sendEvent(displayId, motion) && ok
        } finally {
            motion.recycle()
        }
    }

    companion object {
        private const val TAG = "InputController"
    }
}
