// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nectarlink.services.display.VirtualDeviceOwner
import app.nectarlink.services.input.InputController
import app.nectarlink.services.input.PointerFlowController

class BootCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        VirtualDeviceOwner.forget(context)
        PointerFlowController(context, InputController()).restoreAcceleration()
    }
}
