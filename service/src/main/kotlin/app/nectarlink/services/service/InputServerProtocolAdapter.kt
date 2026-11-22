// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import app.nectarlink.services.client.NectarlinkServiceContract
import app.nectarlink.services.clipboard.ClipboardController
import app.nectarlink.services.display.DisplayPowerController
import app.nectarlink.services.input.InputController
import app.nectarlink.services.input.PointerFlowController
import app.nectarlink.services.security.CallerVerifier
import app.nectarlink.services.storage.ServicesPrefs
import app.nectarlink.services.system.SystemControlManager
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.Writer
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

class InputServerProtocolAdapter(
    context: Context,
    private val verifier: CallerVerifier,
    private val inputController: InputController,
    private val pointerFlow: PointerFlowController,
    private val displayPower: DisplayPowerController,
    private val clipboardController: ClipboardController,
    private val systemControl: SystemControlManager,
) {
    private val appContext = context.applicationContext
    private val activeWriters = CopyOnWriteArraySet<Writer>()

    fun openChannel(callerPackages: Set<String> = verifier.allowedPackages): ParcelFileDescriptor {
        val scopedPackages = verifier.allowedPackages.intersect(callerPackages)
        val pair = ParcelFileDescriptor.createSocketPair()
        val clientSide = pair[0]
        val serviceSide = pair[1]
        thread(isDaemon = true, name = "nl-input-adapter") {
            serve(serviceSide, scopedPackages)
        }
        return clientSide
    }

    fun dispatchClipboard(text: String, html: String?, isSensitive: Boolean) {
        if (activeWriters.isEmpty()) return
        val b64Text = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val b64Html = if (!html.isNullOrEmpty()) {
            Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } else {
            ""
        }
        val line = "CLIP\t$b64Text\t$b64Html\t${if (isSensitive) "1" else "0"}\n"
        broadcast(line)
    }

    fun dispatchInputBlocked() {
        broadcast("BLOCKED\n")
    }

    private fun broadcast(line: String) {
        for (writer in activeWriters) {
            runCatching {
                synchronized(writer) {
                    writer.write(line)
                    writer.flush()
                }
            }
        }
    }

    private fun serve(socket: ParcelFileDescriptor, callerPackages: Set<String>) {
        val writer = OutputStreamWriter(ParcelFileDescriptor.AutoCloseOutputStream(socket), Charsets.UTF_8)
        val reader = ParcelFileDescriptor.AutoCloseInputStream(socket).bufferedReader(Charsets.UTF_8)
        activeWriters.add(writer)
        inputController.resetBlockedState()
        try {
            while (true) {
                val raw = reader.nextLine(MAX_LINE_CHARS) ?: break
                if (raw == "H") continue
                runCatching { handleLine(raw, writer, callerPackages) }.onFailure {
                    Log.w(TAG, "adapter line failed: $raw", it)
                }
            }
        } catch (_: IOException) {
        } finally {
            activeWriters.remove(writer)
            runCatching { writer.close() }
            runCatching { reader.close() }
            runCatching { socket.close() }
        }
    }

    private fun handleLine(full: String, writer: Writer, callerPackages: Set<String>) {
        if (!ServicesPrefs.isServiceEnabled(appContext)) return
        var line = full
        var displayId = -1
        if (line.startsWith("@")) {
            val space = line.indexOf(' ')
            if (space > 1) {
                displayId = line.substring(1, space).toIntOrNull() ?: return
                if (displayId < 0) return
                line = line.substring(space + 1)
            }
        }
        val parts = line.split(' ', limit = 2)
        val rest = parts.getOrElse(1) { "" }
        when (parts[0]) {
            "D", "M", "U" -> {
                if (!ServicesPrefs.isInputEnabled(appContext)) return
                val coords = rest.split(' ')
                if (coords.size < 2) return
                val x = coords[0].toFloatOrNull() ?: return
                val y = coords[1].toFloatOrNull() ?: return
                val action = when (parts[0]) {
                    "D" -> NectarlinkServiceContract.TOUCH_DOWN
                    "M" -> NectarlinkServiceContract.TOUCH_MOVE
                    else -> NectarlinkServiceContract.TOUCH_UP
                }
                if (NectarlinkServiceContract.isAllowedTouch(displayId, action, x, y)) {
                    inputController.touch(displayId, action, x, y)
                }
            }
            "S" -> {
                if (!ServicesPrefs.isInputEnabled(appContext)) return
                val vals = rest.split(' ')
                if (vals.size < 4) return
                val x = vals[0].toFloatOrNull() ?: return
                val y = vals[1].toFloatOrNull() ?: return
                val dx = vals[2].toFloatOrNull() ?: return
                val dy = vals[3].toFloatOrNull() ?: return
                if (NectarlinkServiceContract.isAllowedScroll(displayId, x, y, dx, dy)) {
                    inputController.scroll(displayId, x, y, dx, dy)
                }
            }
            "K" -> {
                if (!ServicesPrefs.isInputEnabled(appContext)) return
                val code = rest.toIntOrNull() ?: return
                if (NectarlinkServiceContract.isAllowedKey(displayId, code)) inputController.key(displayId, code)
            }
            "V" -> if (displayId < 0 && ServicesPrefs.isSystemEnabled(appContext)) {
                rest.toIntOrNull()?.takeIf { it >= 0 }?.let { systemControl.setMusicVolume(it) }
            }
            "Y" -> {
                if (!ServicesPrefs.isInputEnabled(appContext)) return
                val vals = rest.split(' ')
                if (vals.size < 2) return
                val keyCode = vals[0].toIntOrNull() ?: return
                val metaState = vals[1].toIntOrNull() ?: return
                if (NectarlinkServiceContract.isAllowedKey(displayId, keyCode)) {
                    inputController.key(displayId, keyCode, metaState)
                }
            }
            "FS" -> if (displayId < 0 && ServicesPrefs.isInputEnabled(appContext)) {
                val vals = rest.split(' ')
                if (vals.size < 2) return
                val edge = vals[0]
                val y = vals[1].toFloatOrNull() ?: return
                if (!NectarlinkServiceContract.isAllowedPointerStart(edge, y)) return
                val info = pointerFlow.start(edge, y)
                val reply = if (info != null) "FI ${info.width} ${info.height} ${info.scale}\n" else "FI 0 0 0\n"
                synchronized(writer) {
                    writer.write(reply)
                    writer.flush()
                }
            }
            "FM" -> {
                if (!ServicesPrefs.isInputEnabled(appContext)) return
                val vals = rest.split(' ')
                if (vals.size < 4) return
                pointerFlow.move(
                    vals[0].toIntOrNull() ?: return,
                    vals[1].toIntOrNull() ?: return,
                    vals[2].toIntOrNull() ?: return,
                    vals[3].toIntOrNull() ?: return,
                )
            }
            "FW" -> {
                if (!ServicesPrefs.isInputEnabled(appContext)) return
                val at = pointerFlow.where()
                synchronized(writer) {
                    writer.write("FP ${at[0]} ${at[1]}\n")
                    writer.flush()
                }
            }
            "FX" -> if (ServicesPrefs.isInputEnabled(appContext)) pointerFlow.stop()
            "T" -> if (ServicesPrefs.isInputEnabled(appContext) && NectarlinkServiceContract.isAllowedText(displayId, rest)) {
                inputController.text(displayId, rest)
            }
            "G" -> {
                if (!ServicesPrefs.isInputEnabled(appContext)) return
                val tokens = rest.trim().split(' ')
                if (tokens.size < 7) return
                val buttons = tokens[0].toIntOrNull() ?: return
                val lx = tokens[1].toIntOrNull() ?: return
                val ly = tokens[2].toIntOrNull() ?: return
                val rx = tokens[3].toIntOrNull() ?: return
                val ry = tokens[4].toIntOrNull() ?: return
                val lt = tokens[5].toIntOrNull() ?: return
                val rt = tokens[6].toIntOrNull() ?: return
                if (NectarlinkServiceContract.isAllowedGamepad(displayId, lx, ly, rx, ry, lt, rt)) {
                    inputController.gamepad(displayId, buttons, lx, ly, rx, ry, lt, rt)
                }
            }
            "C" -> if (displayId < 0 && ServicesPrefs.isSystemEnabled(appContext)) {
                handleAllowedCommand(rest, callerPackages)
            }
            "P" -> if (displayId < 0 && ServicesPrefs.isDisplayEnabled(appContext)) {
                val trimmed = rest.trim()
                if (trimmed == "0" || trimmed == "1") {
                    displayPower.setScreenOff(trimmed == "0")
                }
            }
            "B" -> if (displayId < 0 && ServicesPrefs.isClipboardEnabled(appContext)) setClipboardLine(rest)
            "X" -> if (displayId < 0 && ServicesPrefs.isClipboardEnabled(appContext)) {
                val text = String(Base64.decode(rest, Base64.NO_WRAP), Charsets.UTF_8)
                clipboardController.clearClipboard(text)
            }
        }
    }

    private fun setClipboardLine(rest: String) {
        val parts = rest.split(' ')
        if (parts.isEmpty() || parts[0].isEmpty()) return
        val decode = { b64: String -> String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8) }
        val text = decode(parts[0])
        val html = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(decode)
        val sensitive = parts.getOrNull(2) == "1"
        clipboardController.setClipboard(text, html, sensitive)
    }

    private fun handleAllowedCommand(cmd: String, callerPackages: Set<String>) {
        if (!NectarlinkServiceContract.isAllowedShellCommand(
                cmd,
                callerPackages,
                appContext.packageName,
            )
        ) {
            return
        }
        when (cmd) {
            "cmd statusbar expand-notifications" -> systemControl.expandNotifications()
            "svc wifi enable" -> systemControl.setWifiEnabled(true)
            "svc wifi disable" -> systemControl.setWifiEnabled(false)
            "svc bluetooth enable" -> systemControl.setBluetoothEnabled(true)
            "svc bluetooth disable" -> systemControl.setBluetoothEnabled(false)
            "hotspot on", "cmd wifi start-softap" -> systemControl.setHotspotEnabled(true)
            "hotspot off", "cmd wifi stop-softap" -> systemControl.setHotspotEnabled(false)
            else -> when {
                cmd.startsWith(BRIGHTNESS_PREFIX) ->
                    cmd.removePrefix(BRIGHTNESS_PREFIX).toIntOrNull()?.let { systemControl.setScreenBrightness(it) }
                cmd.startsWith(UNINSTALL_PREFIX) -> {
                    val pkg = cmd.removePrefix(UNINSTALL_PREFIX)
                    if (pkg !in verifier.allowedPackages) {
                        systemControl.uninstallPackage(pkg)
                    }
                }
                cmd.startsWith("appops set ") -> {
                    val pkg = cmd.removePrefix("appops set ").substringBefore(' ')
                    if (pkg in callerPackages) {
                        systemControl.provisionAppPermissions(pkg)
                    }
                }
                cmd.startsWith("am start -n ") -> {
                    val component = cmd.removePrefix("am start -n ")
                    val pkg = component.substringBefore('/', "")
                    val cls = component.substringAfter('/', "")
                    if (NectarlinkServiceContract.isAllowedBackgroundLaunch(pkg, cls, callerPackages)) {
                        systemControl.launchActivityFromBackground(pkg, cls)
                    }
                }
            }
        }
    }

    private fun Reader.nextLine(maxChars: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val ch = read()
            if (ch < 0) return if (sb.isEmpty()) null else sb.toString()
            if (ch == '\n'.code) return sb.toString()
            if (ch != '\r'.code) {
                if (sb.length >= maxChars) throw IOException("line too long")
                sb.append(ch.toChar())
            }
        }
    }

    companion object {
        private const val TAG = "InputServerAdapter"
        private const val MAX_LINE_CHARS = 256 * 1024
        private const val BRIGHTNESS_PREFIX = "settings put system screen_brightness "
        private const val UNINSTALL_PREFIX = "pm uninstall "
    }
}
