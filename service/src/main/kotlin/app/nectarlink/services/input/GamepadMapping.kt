// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.input

import android.view.KeyEvent

object GamepadMapping {
    const val DPAD_UP = 0x0001
    const val DPAD_DOWN = 0x0002
    const val DPAD_LEFT = 0x0004
    const val DPAD_RIGHT = 0x0008
    const val START = 0x0010
    const val BACK = 0x0020
    const val LEFT_THUMB = 0x0040
    const val RIGHT_THUMB = 0x0080
    const val LEFT_SHOULDER = 0x0100
    const val RIGHT_SHOULDER = 0x0200
    const val A = 0x1000
    const val B = 0x2000
    const val X = 0x4000
    const val Y = 0x8000

    private val HID_BITS = listOf(
        A to 0,
        B to 1,
        X to 3,
        Y to 4,
        LEFT_SHOULDER to 6,
        RIGHT_SHOULDER to 7,
        BACK to 10,
        START to 11,
        LEFT_THUMB to 13,
        RIGHT_THUMB to 14,
    )

    fun hidButtons(xinput: Int): Int =
        HID_BITS.fold(0) { acc, (mask, bit) -> if (xinput and mask != 0) acc or (1 shl bit) else acc }

    fun hat(xinput: Int): Int {
        val (x, y) = hatAxes(xinput)
        return when {
            x == 0 && y == -1 -> 0
            x == 1 && y == -1 -> 1
            x == 1 && y == 0 -> 2
            x == 1 && y == 1 -> 3
            x == 0 && y == 1 -> 4
            x == -1 && y == 1 -> 5
            x == -1 && y == 0 -> 6
            x == -1 && y == -1 -> 7
            else -> HAT_NONE
        }
    }

    fun hatAxes(xinput: Int): Pair<Int, Int> {
        val x = (if (xinput and DPAD_RIGHT != 0) 1 else 0) - (if (xinput and DPAD_LEFT != 0) 1 else 0)
        val y = (if (xinput and DPAD_DOWN != 0) 1 else 0) - (if (xinput and DPAD_UP != 0) 1 else 0)
        return x to y
    }

    fun stickY(xinput: Int): Int = -xinput.coerceIn(-32767, 32767)

    const val HAT_NONE = 8

    val KEYCODES = listOf(
        A to KeyEvent.KEYCODE_BUTTON_A,
        B to KeyEvent.KEYCODE_BUTTON_B,
        X to KeyEvent.KEYCODE_BUTTON_X,
        Y to KeyEvent.KEYCODE_BUTTON_Y,
        LEFT_SHOULDER to KeyEvent.KEYCODE_BUTTON_L1,
        RIGHT_SHOULDER to KeyEvent.KEYCODE_BUTTON_R1,
        BACK to KeyEvent.KEYCODE_BUTTON_SELECT,
        START to KeyEvent.KEYCODE_BUTTON_START,
        LEFT_THUMB to KeyEvent.KEYCODE_BUTTON_THUMBL,
        RIGHT_THUMB to KeyEvent.KEYCODE_BUTTON_THUMBR,
    )
}
