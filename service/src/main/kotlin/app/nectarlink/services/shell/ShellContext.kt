// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.shell

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.Looper
import android.os.Process
import androidx.annotation.RequiresApi

/**
 * System context wrapped to speak as `com.android.shell` when running inside an
 * `app_process` worker launched at `uid 2000`.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
internal class ShellContext private constructor(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = SHELL_PACKAGE

    override fun getOpPackageName(): String = SHELL_PACKAGE

    override fun getApplicationContext(): Context = this

    @RequiresApi(Build.VERSION_CODES.S)
    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(Process.SHELL_UID).setPackageName(SHELL_PACKAGE).build()

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"

        @Volatile
        private var instance: ShellContext? = null

        @Synchronized
        fun get(): ShellContext = instance ?: ShellContext(createSystemContext()).also { instance = it }

        private fun createSystemContext(): Context {
            if (Looper.getMainLooper() == null) {
                @Suppress("DEPRECATION")
                Looper.prepareMainLooper()
            }
            val type = Class.forName("android.app.ActivityThread")
            val thread = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            type.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
            runCatching {
                type.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
            }
            fillConfigurationController(type, thread)
            return type.getDeclaredMethod("getSystemContext").invoke(thread) as Context
        }

        private fun fillConfigurationController(type: Class<*>, thread: Any) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            runCatching {
                val controllerType = Class.forName("android.app.ConfigurationController")
                val internal = Class.forName("android.app.ActivityThreadInternal")
                val controller = controllerType.getDeclaredConstructor(internal)
                    .apply { isAccessible = true }
                    .newInstance(thread)
                runCatching {
                    controllerType.getDeclaredField("mConfiguration").apply { isAccessible = true }
                        .set(controller, Configuration().apply { setToDefaults() })
                }
                type.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(thread, controller)
            }.onFailure { System.err.println("no configuration controller: $it") }
        }
    }
}
