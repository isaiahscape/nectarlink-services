// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.display

import android.annotation.SuppressLint
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.Context
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi")
internal class VirtualDeviceOwner private constructor(
    private val context: Context,
    private val device: Any,
) {
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): VirtualDisplay {
        val config = VirtualDisplayConfig.Builder(name, width, height, dpi)
            .setFlags(flags)
            .setSurface(surface)
            .build()
        return device.javaClass
            .getMethod(
                "createVirtualDisplay",
                VirtualDisplayConfig::class.java,
                Executor::class.java,
                VirtualDisplay.Callback::class.java,
            )
            .invoke(device, config, null, null) as VirtualDisplay
    }

    fun close() {
        runCatching { device.javaClass.getMethod("close").invoke(device) }
            .onFailure { Log.w(TAG, "can't close virtual device", it) }
        synchronized(lock) {
            open--
            if (open <= 0) {
                open = 0
                forget(context)
            }
        }
    }

    companion object {
        private const val TAG = "VirtualDeviceOwner"
        private const val APP_STREAMING = AssociationRequest.DEVICE_PROFILE_APP_STREAMING
        private const val ADDRESS = "02:00:00:4e:4c:02"
        private const val LOCK_STATE_ALWAYS_UNLOCKED = 1
        private const val ASSOCIATION_POLLS = 20
        private const val ASSOCIATION_POLL_MS = 100L

        private val lock = Any()
        private var open = 0

        fun isSupported(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
            return runCatching {
                Class.forName("android.companion.virtual.VirtualDeviceManager")
                context.getSystemService(CompanionDeviceManager::class.java) != null
            }.getOrDefault(false)
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        fun create(context: Context): VirtualDeviceOwner? = synchronized(lock) {
            make(context.applicationContext)?.also { open++ } ?: null.also {
                if (open == 0) forget(context.applicationContext)
            }
        }

        fun forget(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            runCatching {
                val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return@runCatching
                for (assoc in cdm.myAssociations) {
                    if (assoc.deviceProfile == APP_STREAMING &&
                        assoc.deviceMacAddress?.toString().equals(ADDRESS, ignoreCase = true)
                    ) {
                        cdm.disassociate(assoc.id)
                    }
                }
            }.onFailure {
                runCatching {
                    Runtime.getRuntime()
                        .exec(arrayOf("cmd", "companiondevice", "disassociate", "0", context.packageName, ADDRESS))
                        .waitFor()
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun make(context: Context): VirtualDeviceOwner? = runCatching {
            val association = association(context) ?: return null
            val managerType = Class.forName("android.companion.virtual.VirtualDeviceManager")
            val serviceType = Class.forName("android.companion.virtual.IVirtualDeviceManager")
            val manager = managerType.getConstructor(serviceType, Context::class.java)
                .newInstance(service("virtualdevice", serviceType), context)
            val paramsType = Class.forName("android.companion.virtual.VirtualDeviceParams")
            val builderType = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
            val builder = builderType.getConstructor().newInstance()
            builderType.getMethod("setName", String::class.java).invoke(builder, "Nectarlink")
            builderType.getMethod("setLockState", Int::class.javaPrimitiveType)
                .invoke(builder, LOCK_STATE_ALWAYS_UNLOCKED)
            val params = builderType.getMethod("build").invoke(builder)
            val device = managerType.getMethod("createVirtualDevice", Int::class.javaPrimitiveType, paramsType)
                .invoke(manager, association, params)!!
            VirtualDeviceOwner(context, device)
        }.onFailure { Log.i(TAG, "no virtual device: $it") }.getOrNull()

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun association(context: Context): Int? = findAssociation(context) ?: run {
            val proc = Runtime.getRuntime().exec(
                arrayOf("cmd", "companiondevice", "associate", "0", context.packageName, ADDRESS, APP_STREAMING),
            )
            proc.waitFor()
            repeat(ASSOCIATION_POLLS) {
                findAssociation(context)?.let { return it }
                Thread.sleep(ASSOCIATION_POLL_MS)
            }
            null
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun findAssociation(context: Context): Int? {
            val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return null
            return cdm.myAssociations.firstOrNull {
                it.deviceProfile == APP_STREAMING &&
                    it.deviceMacAddress?.toString().equals(ADDRESS, ignoreCase = true)
            }?.id
        }

        private fun service(name: String, type: Class<*>): Any {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, name) as IBinder
            return Class.forName("${type.name}\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!
        }
    }
}
