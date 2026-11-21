// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.input

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import app.nectarlink.services.PointerScreenInfo

class PointerFlowController(
    context: Context,
    private val inputController: InputController,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("pointer_flow", Context.MODE_PRIVATE)

    @Volatile private var active = false
    @Volatile private var useUhid = false
    @Volatile private var screenW = 0
    @Volatile private var screenH = 0
    @Volatile private var cursorX = 0f
    @Volatile private var cursorY = 0f
    @Volatile private var lastButtons = 0
    @Volatile private var downTime = 0L

    @Synchronized
    fun start(edge: String, y: Float): PointerScreenInfo? {
        val (w, h) = readScreenSize() ?: return null
        screenW = w
        screenH = h
        if (tryStartUhid(edge, y, w, h) != null) {
            return uhidInfo
        }
        useUhid = false
        active = true
        cursorX = if (edge == "left") 0f else (w - 1).toFloat()
        cursorY = (y.coerceIn(0f, 1f) * (h - 1)).coerceIn(0f, (h - 1).toFloat())
        lastButtons = 0
        injectMouseEvent(MotionEvent.ACTION_HOVER_ENTER, 0, 0)
        injectMouseEvent(MotionEvent.ACTION_HOVER_MOVE, 0, 0)
        return PointerScreenInfo(w, h, 1.0f)
    }

    @Synchronized
    fun move(dx: Int, dy: Int, buttons: Int, wheel: Int): Boolean {
        if (!active) return false
        if (useUhid) {
            return UhidBridge.mouse(dx, dy, buttons, wheel)
        }
        val maxX = (screenW - 1).coerceAtLeast(1).toFloat()
        val maxY = (screenH - 1).coerceAtLeast(1).toFloat()
        cursorX = (cursorX + dx).coerceIn(0f, maxX)
        cursorY = (cursorY + dy).coerceIn(0f, maxY)

        val wasPressed = lastButtons != 0
        val isPressed = buttons != 0
        val now = SystemClock.uptimeMillis()

        if (!wasPressed && isPressed) {
            downTime = now
            injectMouseEvent(MotionEvent.ACTION_DOWN, buttons, 0)
            injectMouseEvent(MotionEvent.ACTION_BUTTON_PRESS, buttons, 0)
        } else if (wasPressed && isPressed) {
            injectMouseEvent(MotionEvent.ACTION_MOVE, buttons, 0)
        } else if (wasPressed && !isPressed) {
            injectMouseEvent(MotionEvent.ACTION_BUTTON_RELEASE, 0, 0)
            injectMouseEvent(MotionEvent.ACTION_UP, 0, 0)
        } else {
            injectMouseEvent(MotionEvent.ACTION_HOVER_MOVE, 0, 0)
        }
        if (wheel != 0) {
            injectMouseEvent(MotionEvent.ACTION_SCROLL, buttons, wheel)
        }
        lastButtons = buttons
        return true
    }

    @Synchronized
    fun where(): FloatArray {
        if (!active) return floatArrayOf(-1f, -1f)
        if (useUhid) {
            val pos = uhidPosition() ?: return floatArrayOf(-1f, -1f)
            return floatArrayOf(pos.first, pos.second)
        }
        return floatArrayOf(cursorX, cursorY)
    }

    @Synchronized
    fun stop() {
        if (useUhid) {
            UhidBridge.closeMouse()
            restoreAcceleration()
        } else if (active) {
            if (lastButtons != 0) {
                injectMouseEvent(MotionEvent.ACTION_UP, 0, 0)
                lastButtons = 0
            }
            injectMouseEvent(MotionEvent.ACTION_HOVER_EXIT, 0, 0)
        }
        active = false
        useUhid = false
    }

    fun restoreAcceleration() {
        if (!prefs.contains(KEY_SAVED_ACCEL)) return
        val was = prefs.getString(KEY_SAVED_ACCEL, null)
        runCatching {
            if (was == null || was == "null") {
                Settings.System.putString(appContext.contentResolver, ACCELERATION, null)
            } else {
                Settings.System.putString(appContext.contentResolver, ACCELERATION, was)
            }
        }
        prefs.edit().remove(KEY_SAVED_ACCEL).apply()
    }

    @Volatile private var uhidInfo: PointerScreenInfo? = null

    private fun tryStartUhid(edge: String, y: Float, w: Int, h: Int): PointerScreenInfo? {
        if (!UhidBridge.isAvailable()) return null
        saveAcceleration()
        runCatching { Settings.System.putInt(appContext.contentResolver, ACCELERATION, 0) }
        if (!UhidBridge.openMouse()) {
            restoreAcceleration()
            return null
        }
        Thread.sleep(400)
        UhidBridge.mouse(-127 * 30, -127 * 60, 0, 0)
        Thread.sleep(80)
        UhidBridge.mouse(100, 100, 0, 0)
        Thread.sleep(150)
        val at = uhidPosition()
        if (at == null || at.first <= 0f) {
            UhidBridge.closeMouse()
            restoreAcceleration()
            return null
        }
        val scale = (at.first + at.second) / 200f
        UhidBridge.mouse(if (edge == "left") -127 * 30 else 127 * 30, -127 * 60, 0, 0)
        UhidBridge.mouse(0, (y.coerceIn(0f, 1f) * (h - 1) / scale).toInt(), 0, 0)
        useUhid = true
        active = true
        return PointerScreenInfo(w, h, scale).also { uhidInfo = it }
    }

    private fun saveAcceleration() {
        if (prefs.contains(KEY_SAVED_ACCEL)) return
        val current = runCatching {
            Settings.System.getString(appContext.contentResolver, ACCELERATION)
        }.getOrNull() ?: "null"
        prefs.edit().putString(KEY_SAVED_ACCEL, current).apply()
    }

    private fun injectMouseEvent(action: Int, buttons: Int, wheel: Int) {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = cursorX
                y = cursorY
                if (action == MotionEvent.ACTION_SCROLL) {
                    setAxisValue(MotionEvent.AXIS_VSCROLL, wheel.toFloat())
                }
            },
        )
        val event = MotionEvent.obtain(
            downTime.takeIf { it > 0 } ?: now,
            now,
            action,
            1,
            properties,
            coords,
            0,
            buttons,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0,
        )
        try {
            inputController.sendEvent(-1, event)
        } finally {
            event.recycle()
        }
    }

    private fun readScreenSize(): Pair<Int, Int>? = runCatching {
        val windows = appContext.getSystemService(WindowManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windows.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windows.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }.getOrNull()?.takeIf { it.first > 0 && it.second > 0 }

    private fun uhidPosition(): Pair<Float, Float>? = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("dumpsys", "input"))
        val dump = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        val id = Regex("""Device (\d+): ${Regex.escape(UhidBridge.MOUSE_NAME)}""").find(dump)?.groupValues?.get(1)
            ?: return null
        val at = Regex("""DeviceId\($id\):[^\n]*?Pointer\(id=0, MOUSE\) at \(([\d.]+), ([\d.]+)\)""").find(dump)
            ?: return null
        at.groupValues[1].toFloat() to at.groupValues[2].toFloat()
    }.getOrNull()

    companion object {
        private const val ACCELERATION = "mouse_pointer_acceleration_enabled"
        private const val KEY_SAVED_ACCEL = "saved_accel"
    }
}
