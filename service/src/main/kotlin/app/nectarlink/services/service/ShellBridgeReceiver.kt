// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.nectarlink.services.shell.ShellBridgeClient
import app.nectarlink.services.storage.ServicesPrefs

/**
 * Receives the loopback port and auth token from the module's boot script once the
 * `uid 2000` [app.nectarlink.services.shell.ShellBridgeServer] is listening.
 */
class ShellBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        handleIntent(context, intent)
    }

    companion object {
        private const val TAG = "ShellBridgeReceiver"
        const val ACTION_SHELL_READY = "app.nectarlink.services.ACTION_SHELL_READY"
        const val EXTRA_PORT = "shell_port"
        const val EXTRA_TOKEN = "shell_token"

        fun handleIntent(context: Context, intent: Intent?) {
            if (intent?.action != ACTION_SHELL_READY) return
            val port = intent.getIntExtra(EXTRA_PORT, -1)
            val token = intent.getStringExtra(EXTRA_TOKEN)?.takeIf { it.isNotBlank() } ?: return
            if (port !in 1..65535) return
            Log.i(TAG, "Received shell bridge announcement for port $port")
            runCatching { ServicesPrefs.setShellBridge(context, port, token) }
            ShellBridgeClient.configure(port, token)
        }
    }
}
