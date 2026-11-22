// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.util.Log
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
import app.nectarlink.services.shell.ShellBridgeClient
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
    private val shellStateListener: (Boolean) -> Unit = { notifyStatusChanged() }
    private val shellReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            ShellBridgeReceiver.handleIntent(context, intent)
        }
    }

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
            this,
            verifier,
            inputController,
            pointerFlow,
            displayPower,
            clipboardController,
            systemControl,
        )
        pointerFlow.restoreAcceleration()
        VirtualDeviceOwner.forget(this)
        registerShellReadyReceiver()
        ShellBridgeClient.onClipboardChanged = { text, html, isSensitive ->
            if (ServicesPrefs.isServiceEnabled(this) &&
                ServicesPrefs.isClipboardEnabled(this) &&
                !hasPerm("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
            ) {
                notifyClipboardChanged(text, html, isSensitive)
                protocolAdapter.dispatchClipboard(text, html, isSensitive)
            }
        }
        ShellBridgeClient.onInputBlocked = { notifyInputBlocked() }
        ShellBridgeClient.addStateListener(shellStateListener)
        ShellBridgeClient.restoreAndConnect(this)
        Log.i(
            "NectarlinkPrivService",
            "onCreate: priv=${isPrivilegedSystemApp()} inject=${hasPerm("android.permission.INJECT_EVENTS")} shellConnected=${ShellBridgeClient.isConnected}",
        )
        if (ServicesPrefs.isServiceEnabled(this) &&
            ServicesPrefs.isClipboardEnabled(this) &&
            hasPerm("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
        ) {
            clipboardController.start()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerShellReadyReceiver() {
        val filter = IntentFilter(ShellBridgeReceiver.ACTION_SHELL_READY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                shellReadyReceiver,
                filter,
                "android.permission.MANAGE_APP_OPS_MODES",
                null,
                RECEIVER_EXPORTED,
            )
        } else {
            registerReceiver(
                shellReadyReceiver,
                filter,
                "android.permission.MANAGE_APP_OPS_MODES",
                null,
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { unregisterReceiver(shellReadyReceiver) }
        ShellBridgeClient.onClipboardChanged = null
        ShellBridgeClient.onInputBlocked = null
        ShellBridgeClient.removeStateListener(shellStateListener)
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

    private fun notifyStatusChanged() {
        val status = buildStatus()
        val count = callbacks.beginBroadcast()
        try {
            for (i in 0 until count) {
                runCatching { callbacks.getBroadcastItem(i).onStatusChanged(status) }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun hasPerm(perm: String): Boolean =
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun useShellInput(): Boolean =
        !hasPerm("android.permission.INJECT_EVENTS") && ShellBridgeClient.isConnected

    private fun useShellDisplay(): Boolean =
        !hasPerm("android.permission.CAPTURE_VIDEO_OUTPUT") && ShellBridgeClient.isConnected

    private fun useShellPower(): Boolean =
        !hasPerm("android.permission.DEVICE_POWER") && ShellBridgeClient.isConnected

    private fun useShellClipboard(): Boolean =
        !hasPerm("android.permission.READ_CLIPBOARD_IN_BACKGROUND") && ShellBridgeClient.isConnected

    private fun isPrivilegedSystemApp(): Boolean {
        val flags = applicationInfo.flags
        return (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    private fun buildStatus(): ServiceStatus {
        val enabled = ServicesPrefs.isServiceEnabled(this)
        val authorized = verifier.isCallingUidAuthorized()
        val bridged = ShellBridgeClient.isConnected
        var caps = 0L
        if (enabled && authorized) {
            if (ServicesPrefs.isInputEnabled(this) && (hasPerm("android.permission.INJECT_EVENTS") || bridged)) {
                caps = caps or NectarlinkServiceContract.CAP_INPUT_INJECTION
                caps = caps or NectarlinkServiceContract.CAP_POINTER_FLOW
            }
            if (ServicesPrefs.isDisplayEnabled(this) && (hasPerm("android.permission.CAPTURE_VIDEO_OUTPUT") || bridged)) {
                caps = caps or NectarlinkServiceContract.CAP_SCREEN_MIRROR
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    caps = caps or NectarlinkServiceContract.CAP_APP_VIRTUAL_DISPLAY
                }
            }
            if (ServicesPrefs.isDisplayEnabled(this) &&
                (hasPerm("android.permission.DEVICE_POWER") || hasPerm(Manifest.permission.WRITE_SETTINGS) || bridged)
            ) {
                caps = caps or NectarlinkServiceContract.CAP_DISPLAY_POWER
            }
            if (ServicesPrefs.isAudioEnabled(this) && hasPerm(Manifest.permission.CAPTURE_AUDIO_OUTPUT)) {
                caps = caps or NectarlinkServiceContract.CAP_DEVICE_AUDIO
                if (CallAudioStreamer.isSupported() && hasPerm("android.permission.CALL_AUDIO_INTERCEPTION")) {
                    caps = caps or NectarlinkServiceContract.CAP_CALL_AUDIO
                }
            }
            if (ServicesPrefs.isClipboardEnabled(this) && (hasPerm("android.permission.READ_CLIPBOARD_IN_BACKGROUND") || bridged)) {
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
            uhidAvailable = UhidBridge.isAvailable() || bridged,
            virtualDeviceSupported = VirtualDeviceOwner.isSupported(this) || bridged,
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
            if (!NectarlinkServiceContract.isAllowedProvisionPackage(pkg, verifier.allowedPackages) ||
                !verifier.isCallingPackage(pkg)
            ) {
                return false
            }
            return systemControl.provisionAppPermissions(pkg)
        }

        override fun injectTouch(displayId: Int, action: Int, x: Float, y: Float): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            if (!NectarlinkServiceContract.isAllowedTouch(displayId, action, x, y)) return false
            if (useShellInput()) return ShellBridgeClient.injectTouch(displayId, action, x, y)
            return inputController.touch(displayId, action, x, y)
        }

        override fun injectScroll(displayId: Int, x: Float, y: Float, dx: Float, dy: Float): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            if (!NectarlinkServiceContract.isAllowedScroll(displayId, x, y, dx, dy)) return false
            if (useShellInput()) return ShellBridgeClient.injectScroll(displayId, x, y, dx, dy)
            return inputController.scroll(displayId, x, y, dx, dy)
        }

        override fun injectKey(displayId: Int, keyCode: Int, metaState: Int): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            if (!NectarlinkServiceContract.isAllowedKey(displayId, keyCode)) return false
            if (useShellInput()) return ShellBridgeClient.injectKey(displayId, keyCode, metaState)
            return inputController.key(displayId, keyCode, metaState)
        }

        override fun injectText(displayId: Int, text: String?): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            if (text == null || !NectarlinkServiceContract.isAllowedText(displayId, text)) return false
            if (useShellInput()) return ShellBridgeClient.injectText(displayId, text)
            return inputController.text(displayId, text)
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
            if (!NectarlinkServiceContract.isAllowedGamepad(displayId, lx, ly, rx, ry, lt, rt)) return false
            if (useShellInput()) return ShellBridgeClient.injectGamepad(displayId, buttons, lx, ly, rx, ry, lt, rt)
            return inputController.gamepad(displayId, buttons, lx, ly, rx, ry, lt, rt)
        }

        override fun pointerStart(edge: String?, y: Float): PointerScreenInfo? {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            val cleanEdge = edge ?: return null
            if (!NectarlinkServiceContract.isAllowedPointerStart(cleanEdge, y)) return null
            if (useShellInput()) return ShellBridgeClient.pointerStart(cleanEdge, y)
            return pointerFlow.start(cleanEdge, y)
        }

        override fun pointerMove(dx: Int, dy: Int, buttons: Int, wheel: Int): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            if (useShellInput()) return ShellBridgeClient.pointerMove(dx, dy, buttons, wheel)
            return pointerFlow.move(dx, dy, buttons, wheel)
        }

        override fun pointerWhere(): FloatArray {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            if (useShellInput()) return ShellBridgeClient.pointerWhere()
            return pointerFlow.where()
        }

        override fun pointerStop() {
            verifier.requireAuthorized(ServicesPrefs.isInputEnabled(this@NectarlinkPrivilegedService))
            if (useShellInput()) {
                ShellBridgeClient.pointerStop()
                return
            }
            pointerFlow.stop()
        }

        override fun openScreenMirror(
            width: Int,
            height: Int,
            dpi: Int,
            bitrate: Int,
            fps: Int,
            codec: String?,
        ): IDisplaySession? {
            verifier.requireAuthorized(ServicesPrefs.isDisplayEnabled(this@NectarlinkPrivilegedService))
            val c = codec ?: "h264"
            if (!NectarlinkServiceContract.isAllowedDisplayRequest(width, height, dpi, bitrate, fps, c)) {
                return null
            }
            if (useShellDisplay()) {
                return ShellBridgeClient.openDisplaySession("-", width, height, dpi, bitrate, fps, c)
            }
            val session = AppDisplaySession(
                context = this@NectarlinkPrivilegedService,
                pkg = "-",
                initialWidth = width,
                initialHeight = height,
                initialDpi = dpi,
                bitrate = bitrate,
                fps = fps,
                codecName = c,
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
            val targetPkg = pkg ?: return null
            val c = codec ?: "h264"
            if (!NectarlinkServiceContract.isValidPackageName(targetPkg) ||
                !NectarlinkServiceContract.isAllowedDisplayRequest(width, height, dpi, bitrate, fps, c)
            ) {
                return null
            }
            if (useShellDisplay()) {
                return ShellBridgeClient.openDisplaySession(targetPkg, width, height, dpi, bitrate, fps, c)
            }
            val session = AppDisplaySession(
                context = this@NectarlinkPrivilegedService,
                pkg = targetPkg,
                initialWidth = width,
                initialHeight = height,
                initialDpi = dpi,
                bitrate = bitrate,
                fps = fps,
                codecName = c,
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
            if (useShellPower()) return ShellBridgeClient.setScreenOff(off)
            return displayPower.setScreenOff(off)
        }

        override fun wakeScreen(): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isDisplayEnabled(this@NectarlinkPrivilegedService))
            if (useShellPower()) return ShellBridgeClient.wakeScreen()
            return displayPower.wakeScreen()
        }

        override fun setClipboard(text: String?, html: String?, sensitive: Boolean): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isClipboardEnabled(this@NectarlinkPrivilegedService))
            if (text == null) return false
            if (useShellClipboard()) return ShellBridgeClient.setClipboard(text, html, sensitive)
            return clipboardController.setClipboard(text, html, sensitive)
        }

        override fun clearClipboard(text: String?): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isClipboardEnabled(this@NectarlinkPrivilegedService))
            if (text == null) return false
            if (useShellClipboard()) return ShellBridgeClient.clearClipboard(text)
            return clipboardController.clearClipboard(text)
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
            if (!NectarlinkServiceContract.isAllowedBrightness(brightness)) return false
            return systemControl.setScreenBrightness(brightness)
        }

        override fun setMusicVolume(index: Int): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isSystemEnabled(this@NectarlinkPrivilegedService))
            if (index < 0) return false
            return systemControl.setMusicVolume(index)
        }

        override fun uninstallPackage(pkg: String?): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isSystemEnabled(this@NectarlinkPrivilegedService))
            if (pkg == null) return false
            if (!NectarlinkServiceContract.isAllowedUninstallPackage(pkg, packageName) ||
                pkg in verifier.allowedPackages
            ) {
                return false
            }
            return systemControl.uninstallPackage(pkg)
        }

        override fun launchActivityFromBackground(pkg: String?, activityClass: String?): Boolean {
            verifier.requireAuthorized(ServicesPrefs.isSystemEnabled(this@NectarlinkPrivilegedService))
            if (pkg == null || activityClass == null) return false
            if (!NectarlinkServiceContract.isAllowedBackgroundLaunch(pkg, activityClass, verifier.allowedPackages) ||
                !verifier.isCallingPackage(pkg)
            ) {
                return false
            }
            return systemControl.launchActivityFromBackground(pkg, activityClass)
        }

        override fun openInputServerChannel(): ParcelFileDescriptor {
            verifier.requireAuthorized()
            return protocolAdapter.openChannel(verifier.packagesForCallingUid())
        }
    }
}
