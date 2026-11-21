// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.display

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import app.nectarlink.services.input.InputController

@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi")
class DisplayPowerController(
    context: Context,
    private val inputController: InputController,
) {
    private val appContext = context.applicationContext

    @Volatile
    var isScreenOffRequested: Boolean = false
        private set

    @Volatile
    private var savedBrightness: Int? = null

    fun wakeScreen(): Boolean {
        val power = appContext.getSystemService(PowerManager::class.java)
        if (power?.isInteractive != false) return true
        return inputController.key(-1, KeyEvent.KEYCODE_WAKEUP)
    }

    @Synchronized
    fun setScreenOff(off: Boolean): Boolean {
        isScreenOffRequested = off
        val on = !off
        val how = when {
            requestDisplayPower(on) -> "requestDisplayPower"
            setPanelPowerMode(on) -> "setDisplayPowerMode"
            else -> null
        }
        Log.i(TAG, if (how != null) "screen ${if (on) "on" else "off"} via $how" else "panel power API unavailable")
        if (!on) {
            if (how == null) dimScreen()
        } else {
            restoreBrightness()
        }
        return how != null || !on || savedBrightness == null
    }

    fun restoreIfNeeded() {
        if (isScreenOffRequested) {
            setScreenOff(false)
        }
    }

    private fun dimScreen() {
        if (savedBrightness == null) {
            savedBrightness = runCatching {
                Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            }.getOrNull()
        }
        runCatching {
            Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 0)
        }
    }

    private fun restoreBrightness() {
        val saved = savedBrightness ?: return
        savedBrightness = null
        runCatching {
            Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, saved)
        }
    }

    private fun requestDisplayPower(on: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 35) return false
        return runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "display") as IBinder
            val manager = Class.forName("android.hardware.display.IDisplayManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val method = manager.javaClass.methods.first {
                it.name == "requestDisplayPower" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            val power: Any = if (method.parameterTypes[1] == Boolean::class.javaPrimitiveType) on else if (on) 2 else 1
            (method.invoke(manager, Display.DEFAULT_DISPLAY, power) as? Boolean) != false
        }.onFailure { Log.d(TAG, "requestDisplayPower unavailable: $it") }.getOrDefault(false)
    }

    private fun setPanelPowerMode(on: Boolean): Boolean = runCatching {
        val sc = Class.forName("android.view.SurfaceControl")
        val tokens = runCatching {
            listOfNotNull(sc.getMethod("getInternalDisplayToken").invoke(null) as? IBinder)
        }.getOrNull().orEmpty().ifEmpty {
            val dc = displayControl()
            val ids = dc.getMethod("getPhysicalDisplayIds").invoke(null) as? LongArray ?: LongArray(0)
            val token = dc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            buildList { for (id in ids) (token.invoke(null, id) as? IBinder)?.let(::add) }
        }
        check(tokens.isNotEmpty()) { "no display token" }
        val setMode = sc.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
        tokens.forEach { setMode.invoke(null, it, if (on) POWER_MODE_NORMAL else POWER_MODE_OFF) }
        true
    }.onFailure { Log.d(TAG, "setDisplayPowerMode unavailable: $it") }.getOrDefault(false)

    private fun displayControl(): Class<*> {
        val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val create = factory.getDeclaredMethod(
            "createClassLoader",
            String::class.java, String::class.java, String::class.java, ClassLoader::class.java,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java,
        )
        val loader = create.invoke(
            null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null,
        ) as ClassLoader
        val dc = loader.loadClass("com.android.server.display.DisplayControl")
        Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(Runtime.getRuntime(), dc, "android_servers")
        return dc
    }

    companion object {
        private const val TAG = "DisplayPowerController"
        private const val POWER_MODE_OFF = 0
        private const val POWER_MODE_NORMAL = 2
    }
}
