// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.system

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import app.nectarlink.services.client.NectarlinkServiceContract
import app.nectarlink.services.input.InputController
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "MissingPermission")
class SystemControlManager(
    context: Context,
    private val inputController: InputController,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var volumeTarget = -1
    private val steppingVolume = AtomicBoolean(false)

    fun expandNotifications(): Boolean = runCatching {
        val sb = appContext.getSystemService("statusbar") ?: return false
        sb.javaClass.getMethod("expandNotificationsPanel").invoke(sb)
        true
    }.onFailure {
        Log.w(TAG, "expandNotifications failed", it)
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun setWifiEnabled(enabled: Boolean): Boolean = runCatching {
        val wifi = appContext.getSystemService(WifiManager::class.java) ?: return false
        wifi.setWifiEnabled(enabled)
    }.onFailure {
        Log.w(TAG, "setWifiEnabled($enabled) failed", it)
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun setBluetoothEnabled(enabled: Boolean): Boolean = runCatching {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (enabled) adapter.enable() else adapter.disable()
    }.onFailure {
        Log.w(TAG, "setBluetoothEnabled($enabled) failed", it)
    }.getOrDefault(false)

    fun setHotspotEnabled(enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ok = runCatching {
                val tm = appContext.getSystemService("tethering") ?: return@runCatching false
                if (enabled) {
                    val reqClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
                    val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
                    val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(0)
                    val request = builderClass.getMethod("build").invoke(builder)
                    val cbClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
                    val proxy = java.lang.reflect.Proxy.newProxyInstance(
                        cbClass.classLoader,
                        arrayOf(cbClass),
                    ) { _, _, _ -> null }
                    val executor = Executor { r -> mainHandler.post(r) }
                    tm.javaClass.getMethod("startTethering", reqClass, Executor::class.java, cbClass)
                        .invoke(tm, request, executor, proxy)
                } else {
                    tm.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, 0)
                }
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return runCatching {
            val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
            if (enabled) {
                val cbClass = Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
                val proxy = java.lang.reflect.Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { _, _, _ -> null }
                cm.javaClass.getMethod(
                    "startTethering",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    cbClass,
                    Handler::class.java,
                ).invoke(cm, 0, false, proxy, mainHandler)
            } else {
                cm.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(cm, 0)
            }
            true
        }.onFailure {
            Log.w(TAG, "setHotspotEnabled($enabled) failed", it)
        }.getOrDefault(false)
    }

    fun setScreenBrightness(brightness: Int): Boolean {
        if (!NectarlinkServiceContract.isAllowedBrightness(brightness)) return false
        return runCatching {
            Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
        }.onFailure {
            Log.w(TAG, "setScreenBrightness($brightness) failed", it)
        }.getOrDefault(false)
    }

    fun setMusicVolume(index: Int): Boolean {
        if (index < 0) return false
        val am = appContext.getSystemService(AudioManager::class.java) ?: return false
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val clamped = index.coerceIn(0, max)
        runCatching {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        }
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == clamped) return true
        stepVolumeTo(clamped, am)
        return true
    }

    private fun stepVolumeTo(target: Int, am: AudioManager) {
        volumeTarget = target
        if (!steppingVolume.compareAndSet(false, true)) return
        thread(isDaemon = true, name = "nl-volume-keys") {
            try {
                var presses = 0
                var unchanged = 0
                while (presses++ < MAX_VOLUME_PRESSES) {
                    val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val wanted = volumeTarget
                    if (now == wanted) break
                    inputController.key(
                        -1,
                        if (now < wanted) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN,
                    )
                    var moved = false
                    var waited = 0L
                    while (!moved && waited < VOLUME_SETTLE_MS) {
                        Thread.sleep(VOLUME_POLL_MS)
                        waited += VOLUME_POLL_MS
                        moved = am.getStreamVolume(AudioManager.STREAM_MUSIC) != now
                    }
                    unchanged = if (moved) 0 else unchanged + 1
                    if (unchanged >= 3) break
                }
            } finally {
                steppingVolume.set(false)
            }
        }
    }

    fun uninstallPackage(pkg: String): Boolean {
        if (!NectarlinkServiceContract.isAllowedUninstallPackage(pkg, appContext.packageName)) {
            return false
        }
        return runCatching {
            val installer = appContext.packageManager.packageInstaller
            val intent = Intent("sn.nectarlink.services.UNINSTALL_STATUS").setPackage(appContext.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val sender = PendingIntent.getBroadcast(appContext, pkg.hashCode(), intent, flags).intentSender
            @Suppress("DEPRECATION")
            installer.uninstall(pkg, sender)
            true
        }.onFailure {
            Log.w(TAG, "uninstallPackage($pkg) failed", it)
        }.getOrDefault(false)
    }

    fun launchActivityFromBackground(pkg: String, activityClass: String): Boolean {
        if (!NectarlinkServiceContract.isAllowedBackgroundLaunch(pkg, activityClass)) return false
        return runCatching {
            val intent = Intent().apply {
                component = ComponentName(pkg, activityClass)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            true
        }.onFailure {
            Log.w(TAG, "launchActivityFromBackground($pkg/$activityClass) failed", it)
        }.getOrDefault(false)
    }

    fun provisionAppPermissions(targetPackage: String): Boolean {
        if (!NectarlinkServiceContract.isValidPackageName(targetPackage)) return false
        val info = runCatching { appContext.packageManager.getApplicationInfo(targetPackage, 0) }.getOrNull()
            ?: return false
        val uid = info.uid
        var anyApplied = false

        val appOps = appContext.getSystemService(AppOpsManager::class.java)
        if (appOps != null) {
            val setMode = runCatching {
                appOps.javaClass.getMethod(
                    "setMode",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull()
            if (setMode != null) {
                val ops = buildList {
                    add("android:write_settings")
                    add("android:get_usage_stats")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        add("android:manage_ongoing_calls")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        add("android:receive_sensitive_notifications")
                    }
                }
                for (op in ops) {
                    runCatching {
                        setMode.invoke(appOps, op, uid, targetPackage, AppOpsManager.MODE_ALLOWED)
                        anyApplied = true
                    }
                }
            }
        }

        val nm = appContext.getSystemService(NotificationManager::class.java)
        if (nm != null) {
            runCatching {
                nm.javaClass.getMethod(
                    "setNotificationPolicyAccessGranted",
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                ).invoke(nm, targetPackage, true)
                anyApplied = true
            }
            runCatching {
                val listener = ComponentName(targetPackage, "app.nectarlink.android.notifications.NotificationListener")
                nm.javaClass.getMethod(
                    "setNotificationListenerAccessGranted",
                    ComponentName::class.java,
                    Boolean::class.javaPrimitiveType,
                ).invoke(nm, listener, true)
                anyApplied = true
            }
        }
        return anyApplied
    }

    companion object {
        private const val TAG = "SystemControlManager"
        private const val VOLUME_POLL_MS = 30L
        private const val VOLUME_SETTLE_MS = 600L
        private const val MAX_VOLUME_PRESSES = 200
    }
}
