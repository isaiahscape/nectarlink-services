// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import app.nectarlink.services.IDisplaySession
import app.nectarlink.services.INectarlinkService
import app.nectarlink.services.INectarlinkServiceCallback
import app.nectarlink.services.PointerScreenInfo
import app.nectarlink.services.ServiceStatus
import app.nectarlink.services.audio.CallAudioStreamer
import app.nectarlink.services.audio.DeviceAudioStreamer
import app.nectarlink.services.client.NectarlinkServiceContract
import app.nectarlink.services.clipboard.ClipboardController
import app.nectarlink.services.display.AppDisplaySession
import app.nectarlink.services.display.DisplayPowerController
import app.nectarlink.services.display.VirtualDeviceOwner
import app.nectarlink.services.input.InputController
import app.nectarlink.services.input.PointerFlowController
import app.nectarlink.services.input.UhidBridge
import app.nectarlink.services.security.CallerVerifier
import app.nectarlink.services.storage.ServicesPrefs
import app.nectarlink.services.system.SystemControlManager
import java.util.Collections

class NectarlinkPrivilegedService : Service() {
    private lateinit var verifier: CallerVerifier
    private lateinit var inputController: InputController
    private lateinit var pointerFlow: PointerFlowController
    private lateinit var displayPower: DisplayPowerController
    private lateinit var deviceAudio: DeviceAudioStreamer
    private lateinit var clipboardController: ClipboardController
    private lateinit var systemControl: SystemControlManager
    private lateinit var protocolAdapter: InputServerProtocolAdapter

    private val callbacks = RemoteCallbackList<INectarlinkServiceCallback>()
    private val displaySessions = Collections.synchronizedSet(LinkedHashSet<AppDisplaySession>())

    override fun onCreate() {
        super.onCreate()
        verifier = CallerVerifier(this)
        inputController = InputController(onInputBlocked = { notifyInputBlocked() })
        pointerFlow = PointerFlowController(this, inputController)
        displayPower = DisplayPowerController(this, inputController)
        deviceAudio = DeviceAudioStreamer(inputController)
        systemControl = SystemControlManager(this, inputController)
        clipboardController = ClipboardController(this) { text, html, isSensitive ->
            if (ServicesPrefs.isServiceEnabled(this) && ServicesPrefs.isClipboardEnabled(this)) {
                notifyClipboardChanged(text, html, isSensitive)
                protocolAdapter.dispatchClipboard(text, html, isSensitive)
            }
        }
        protocolAdapter = InputServerProtocolAdapter(
            inputController,
            pointerFlow,
            displayPower,
            clipboardController,
            systemControl,
        )
        pointerFlow.restoreAcceleration()
        VirtualDeviceOwner.forget(this)
        if (ServicesPrefs.isServiceEnabled(this) && ServicesPrefs.isClipboardEnabled(this)) {
            clipboardController.start()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        clipboardController.stop()
        pointerFlow.stop()
        displayPower.restoreIfNeeded()
        UhidBridge.closeAll()
        synchronized(displaySessions) {
            displaySessions.toList().forEach { runCatching { it.close() } }
            displaySessions.clear()
        }
        callbacks.kill()
        super.onDestroy()
    }

    private fun notifyClipboardChanged(text: String, html: String?, isSensitive: Boolean) {
        val count = callbacks.beginBroadcast()
        try {
            for (i in 0 until count) {
                runCatching { callbacks.getBroadcastItem(i).onClipboardChanged(text, html, isSensitive) }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun notifyInputBlocked() {
        protocolAdapter.dispatchInputBlocked()
        val count = callbacks.beginBroadcast()
        try {
            for (i in 0 until count) {
                runCatching { callbacks.getBroadcastItem(i).onInputBlocked() }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun hasPerm(perm: String): Boolean =
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun isPrivilegedSystemApp(): Boolean {
        val flags = applicationInfo.flags
        return (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    private fun buildStatus(): ServiceStatus {
        val enabled = ServicesPrefs.isServiceEnabled(this)
        val authorized = verifier.isCallingUidAuthorized()
        var caps = 0L
        if (enabled && authorized) {
            if (ServicesPrefs.isInputEnabled(this) && hasPerm("android.permission.INJECT_EVENTS")) {
                caps = caps or NectarlinkServiceContract.CAP_INPUT_INJECTION
                caps = caps or NectarlinkServiceContract.CAP_POINTER_FLOW
            }
            if (ServicesPrefs.isDisplayEnabled(this) && hasPerm("android.permission.CAPTURE_VIDEO_OUTPUT")) {
                caps = caps or NectarlinkServiceContract.CAP_SCREEN_MIRROR
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    caps = caps or NectarlinkServiceContract.CAP_APP_VIRTUAL_DISPLAY
                }
            }
            if (ServicesPrefs.isDisplayEnabled(this) &&
                (hasPerm("android.permission.DEVICE_POWER") || hasPerm(Manifest.permission.WRITE_SETTINGS))
            ) {
                caps = caps or NectarlinkServiceContract.CAP_DISPLAY_POWER
            }
            if (ServicesPrefs.isAudioEnabled(this) && hasPerm(Manifest.permission.CAPTURE_AUDIO_OUTPUT)) {
                caps = caps or NectarlinkServiceContract.CAP_DEVICE_AUDIO
                if (CallAudioStreamer.isSupported() && hasPerm("android.permission.CALL_AUDIO_INTERCEPTION")) {
                    caps = caps or NectarlinkServiceContract.CAP_CALL_AUDIO
                }
            }
            if (ServicesPrefs.isClipboardEnabled(this) && hasPerm("android.permission.READ_CLIPBOARD_IN_BACKGROUND")) {
                caps = caps or NectarlinkServiceContract.CAP_CLIPBOARD_SYNC
            }
            if (ServicesPrefs.isSystemEnabled(this)) {
                caps = caps or NectarlinkServiceContract.CAP_SYSTEM_TOGGLES
                if (hasPerm("android.permission.DELETE_PACKAGES")) {
                    caps = caps or NectarlinkServiceContract.CAP_UNINSTALL_PACKAGES
                }
                if (hasPerm("android.permission.START_ACTIVITIES_FROM_BACKGROUND")) {
                    caps = caps or NectarlinkServiceContract.CAP_BACKGROUND_LAUNCH
                }
            }
        }
        return ServiceStatus(
            apiVersion = NectarlinkServiceContract.API_VERSION,
            serviceEnabled = enabled,
            callerAuthorized = authorized,
            privilegedApp = isPrivilegedSystemApp(),
            capabilities = caps,
            callAudioSupported = CallAudioStreamer.isSupported(),
            uhidAvailable = UhidBridge.isAvailable(),
            virtualDeviceSupported = VirtualDeviceOwner.isSupported(this),
        )
    }

    private val binder = object : INectarlinkService.Stub() {
        override fun getApiVersion(): Int = NectarlinkServiceContract.API_VERSION

        override fun getStatus(): ServiceStatus = buildStatus()

        override fun registerCallback(callback: INectarlinkServiceCallback?) {
            verifier.requireAuthorized()
            if (callback != null) callbacks.register(callback)
        }

        override fun unregisterCallback(callback: INectarlinkServiceCallback?) {
            if (callback != null) callbacks.unregister(callback)
        }

        override fun provisionAppPermissions(targetPackage: String?): Boolean {
            verifier.requireAuthorized()
            val pkg = targetPackage ?: return false
            if (pkg !in verifier.allowedPackages) return false
            return systemControl.provisionAppPermissions(pkg)
        }

        override fun injectTouch(displayId: Int, action: Int, x: Float, y: Float): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            return inputController.touch(displayId, action, x, y)
        }

        override fun injectScroll(displayId: Int, x: Float, y: Float, dx: Float, dy: Float): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            return inputController.scroll(displayId, x, y, dx, dy)
        }

        override fun injectKey(displayId: Int, keyCode: Int, metaState: Int): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            return inputController.key(displayId, keyCode, metaState)
        }

        override fun injectText(displayId: Int, text: String?): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            return if (text != null) inputController.text(displayId, text) else false
        }

        override fun injectGamepad(
            displayId: Int,
            buttons: Int,
            lx: Int,
            ly: Int,
            rx: Int,
            ry: Int,
            lt: Int,
            rt: Int,
        ): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            return inputController.gamepad(displayId, buttons, lx, ly, rx, ry, lt, rt)
        }

        override fun pointerStart(edge: String?, y: Float): PointerScreenInfo? {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            return pointerFlow.start(edge ?: return null, y)
        }

        override fun pointerMove(dx: Int, dy: Int, buttons: Int, wheel: Int): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            return pointerFlow.move(dx, dy, buttons, wheel)
        }

        override fun pointerWhere(): FloatArray {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            return pointerFlow.where()
        }

        override fun pointerStop() {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            pointerFlow.stop()
        }

        override fun openScreenMirror(
            width: Int,
            height: Int,
            dpi: Int,
            bitrate: Int,
            fps: Int,
            codec: String?,
        ): IDisplaySession {
            verifier.requireAuthorized(ServicesPrefs.isDisplayEnabled(this@NectarlinkPrivilegedService))
            val session = AppDisplaySession(
                context = this@NectarlinkPrivilegedService,
                pkg = "-",
                initialWidth = width.coerceIn(16, 4096),
                initialHeight = height.coerceIn(16, 4096),
                initialDpi = dpi.coerceIn(72, 1000),
                bitrate = bitrate,
                fps = fps,
                codecName = codec ?: "h264",
                wakePhone = { displayPower.wakeScreen() },
                onClosed = { displaySessions.remove(it) },
            )
            displaySessions.add(session)
            return session
        }

        override fun openAppDisplay(
            pkg: String?,
            width: Int,
            height: Int,
            dpi: Int,
            bitrate: Int,
            fps: Int,
            codec: String?,
        ): IDisplaySession? {
            verifier.requireAuthorized(ServicesPrefs.isDisplayEnabled(this@NectarlinkPrivilegedService))
            val targetPkg = pkg?.takeIf { it.isNotBlank() && it != "-" } ?: return null
            val session = AppDisplaySession(
                context = this@NectarlinkPrivilegedService,
                pkg = targetPkg,
                initialWidth = width.coerceIn(16, 4096),
                initialHeight = height.coerceIn(16, 4096),
                initialDpi = dpi.coerceIn(72, 1000),
                bitrate = bitrate,
                fps = fps,
                codecName = codec ?: "h264",
                wakePhone = { displayPower.wakeScreen() },
                onClosed = { displaySessions.remove(it) },
            )
            displaySessions.add(session)
            return session
        }

        override fun openDeviceAudio(): ParcelFileDescriptor? {
            verifier.requireAuthorized(ServicesPrefs.isAudioEnabled(this@NectarlinkPrivilegedService))
            return deviceAudio.openStream()
        }

        override fun isCallAudioSupported(): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isAudioEnabled(this@NectarlinkPrivilegedService))
            return CallAudioStreamer.isSupported()
        }

        override fun openCallAudio(): ParcelFileDescriptor {
            verifier.requireAuthorized(ServicesPrefs.isAudioEnabled(this@NectarlinkPrivilegedService))
            return CallAudioStreamer.openCallAudio()
        }

        override fun setScreenOff(off: Boolean): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isDisplayEnabled(this@NectarlinkPrivilegedService))
            return displayPower.setScreenOff(off)
        }

        override fun wakeScreen(): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isDisplayEnabled(this@NectarlinkPrivilegedService))
            return displayPower.wakeScreen()
        }

        override fun setClipboard(text: String?, html: String?, sensitive: Boolean): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isClipboardEnabled(this@NectarlinkPrivilegedService))
            return if (text != null) clipboardController.setClipboard(text, html, sensitive) else false
        }

        override fun clearClipboard(text: String?): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isClipboardEnabled(this@NectarlinkPrivilegedService))
            return if (text != null) clipboardController.clearClipboard(text) else false
        }

        override fun expandNotifications(): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isSystemEnabled(this@NectarlinkPrivilegedService))
            return systemControl.expandNotifications()
        }

        override fun setWifiEnabled(enabled: Boolean): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isSystemEnabled(this@NectarlinkPrivilegedService))
            return systemControl.setWifiEnabled(enabled)
        }

        override fun setBluetoothEnabled(enabled: Boolean): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isSystemEnabled(this@NectarlinkPrivilegedService))
            return systemControl.setBluetoothEnabled(enabled)
        }

        override fun setHotspotEnabled(enabled: Boolean): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isSystemEnabled(this@NectarlinkPrivilegedService))
            return systemControl.setHotspotEnabled(enabled)
        }

        override fun setScreenBrightness(brightness: Int): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isSystemEnabled(this@NectarlinkPrivilegedService))
            return systemControl.setScreenBrightness(brightness)
        }

        override fun setMusicVolume(index: Int): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isSystemEnabled(this@NectarlinkPrivilegedService))
            return systemControl.setMusicVolume(index)
        }

        override fun uninstallPackage(pkg: String?): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isSystemEnabled(this@NectarlinkPrivilegedService))
            return if (pkg != null) systemControl.uninstallPackage(pkg) else false
        }

        override fun launchActivityFromBackground(pkg: String?, activityClass: String?): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isSystemEnabled(this@NectarlinkPrivilegedService))
            if (pkg == null || activityClass == null || pkg !in verifier.allowedPackages) return false
            return systemControl.launchActivityFromBackground(pkg, activityClass)
        }

        override fun openInputServerChannel(): ParcelFileDescriptor {
            verifier.requireAuthorized()
            return protocolAdapter.openChannel()
        }
    }
}
