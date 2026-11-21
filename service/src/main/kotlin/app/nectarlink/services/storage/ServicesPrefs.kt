// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.storage

import android.content.Context
import android.content.SharedPreferences

object ServicesPrefs {
    private const val PREFS_NAME = "nectarlink_services_prefs"
    private const val KEY_MASTER_ENABLED = "master_enabled"
    private const val KEY_INPUT_ENABLED = "input_enabled"
    private const val KEY_DISPLAY_ENABLED = "display_enabled"
    private const val KEY_AUDIO_ENABLED = "audio_enabled"
    private const val KEY_CLIPBOARD_ENABLED = "clipboard_enabled"
    private const val KEY_SYSTEM_ENABLED = "system_enabled"
    private const val KEY_APPROVED_CLIENTS = "approved_clients"
    private const val KEY_SHELL_PORT = "shell_bridge_port"
    private const val KEY_SHELL_TOKEN = "shell_bridge_token"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun devicePrefs(context: Context): SharedPreferences =
        context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isServiceEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MASTER_ENABLED, true)
    fun setServiceEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_MASTER_ENABLED, on).apply()

    fun isInputEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_INPUT_ENABLED, true)
    fun setInputEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_INPUT_ENABLED, on).apply()

    fun isDisplayEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_DISPLAY_ENABLED, true)
    fun setDisplayEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_DISPLAY_ENABLED, on).apply()

    fun isAudioEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUDIO_ENABLED, true)
    fun setAudioEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_AUDIO_ENABLED, on).apply()

    fun isClipboardEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CLIPBOARD_ENABLED, true)
    fun setClipboardEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_CLIPBOARD_ENABLED, on).apply()

    fun isSystemEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SYSTEM_ENABLED, true)
    fun setSystemEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_SYSTEM_ENABLED, on).apply()

    fun approvedClients(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_APPROVED_CLIENTS, emptySet()).orEmpty().toSet()

    fun isClientApproved(context: Context, packageName: String, certSha256: String): Boolean =
        "${packageName}:${certSha256.lowercase()}" in approvedClients(context)

    fun approveClient(context: Context, packageName: String, certSha256: String) {
        val next = approvedClients(context) + "${packageName}:${certSha256.lowercase()}"
        prefs(context).edit().putStringSet(KEY_APPROVED_CLIENTS, next).apply()
    }

    fun revokeClient(context: Context, packageName: String, certSha256: String) {
        val next = approvedClients(context) - "${packageName}:${certSha256.lowercase()}"
        prefs(context).edit().putStringSet(KEY_APPROVED_CLIENTS, next).apply()
    }

    fun getShellBridgePort(context: Context): Int {
        val port = devicePrefs(context).getInt(KEY_SHELL_PORT, 0)
        if (port in 1..65535) return port
        return runCatching { prefs(context).getInt(KEY_SHELL_PORT, 0) }.getOrDefault(0)
    }

    fun getShellBridgeToken(context: Context): String? {
        val token = devicePrefs(context).getString(KEY_SHELL_TOKEN, null)
        if (!token.isNullOrBlank()) return token
        return runCatching { prefs(context).getString(KEY_SHELL_TOKEN, null) }.getOrNull()
    }

    fun setShellBridge(context: Context, port: Int, token: String) {
        devicePrefs(context).edit()
            .putInt(KEY_SHELL_PORT, port)
            .putString(KEY_SHELL_TOKEN, token)
            .apply()
        runCatching {
            prefs(context).edit()
                .putInt(KEY_SHELL_PORT, port)
                .putString(KEY_SHELL_TOKEN, token)
                .apply()
        }
    }
}
