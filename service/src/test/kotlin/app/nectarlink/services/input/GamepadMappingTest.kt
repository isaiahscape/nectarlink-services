// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class GamepadMappingTest {
    @Test
    fun maps_xinput_buttons_to_hid_bits() {
        assertEquals(0, GamepadMapping.hidButtons(0))
        assertEquals(1 shl 0, GamepadMapping.hidButtons(GamepadMapping.A))
        assertEquals((1 shl 0) or (1 shl 4) or (1 shl 11), GamepadMapping.hidButtons(GamepadMapping.A or GamepadMapping.Y or GamepadMapping.START))
    }

    @Test
    fun maps_dpad_to_hat_and_cancels_opposites() {
        assertEquals(GamepadMapping.HAT_NONE, GamepadMapping.hat(0))
        assertEquals(0, GamepadMapping.hat(GamepadMapping.DPAD_UP))
        assertEquals(1, GamepadMapping.hat(GamepadMapping.DPAD_UP or GamepadMapping.DPAD_RIGHT))
        assertEquals(4, GamepadMapping.hat(GamepadMapping.DPAD_DOWN))
        assertEquals(GamepadMapping.HAT_NONE, GamepadMapping.hat(GamepadMapping.DPAD_UP or GamepadMapping.DPAD_DOWN))
    }

    @Test
    fun flips_and_clamps_stick_y() {
        assertEquals(-32767, GamepadMapping.stickY(32767))
        assertEquals(32767, GamepadMapping.stickY(-32767))
        assertEquals(32767, GamepadMapping.stickY(-40000))
    }

    @Test
    fun keycode_mapping_contains_face_and_shoulder_buttons() {
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, GamepadMapping.KEYCODES.first { it.first == GamepadMapping.A }.second)
        assertEquals(KeyEvent.KEYCODE_BUTTON_THUMBR, GamepadMapping.KEYCODES.first { it.first == GamepadMapping.RIGHT_THUMB }.second)
    }
}
