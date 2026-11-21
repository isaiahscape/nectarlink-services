// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.service

import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import app.nectarlink.services.client.NectarlinkServiceContract
import app.nectarlink.services.clipboard.ClipboardController
import app.nectarlink.services.display.DisplayPowerController
import app.nectarlink.services.input.InputController
import app.nectarlink.services.input.PointerFlowController
import app.nectarlink.services.system.SystemControlManager
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.Writer
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

class InputServerProtocolAdapter(
    private val inputController: InputController,
    private val pointerFlow: PointerFlowController,
    private val displayPower: DisplayPowerController,
    private val clipboardController: ClipboardController,
    private val systemControl: SystemControlManager,
) {
    private val activeWriters = CopyOnWriteArraySet<Writer>()

    fun openChannel(): ParcelFileDescriptor {
        val pair = ParcelFileDescriptor.createSocketPair()
        val clientSide = pair[0]
        val serviceSide = pair[1]
        thread(isDaemon = true, name = "nl-input-adapter") {
            serve(serviceSide)
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

    private fun serve(socket: ParcelFileDescriptor) {
        val writer = OutputStreamWriter(ParcelFileDescriptor.AutoCloseOutputStream(socket), Charsets.UTF_8)
        val reader = ParcelFileDescriptor.AutoCloseInputStream(socket).bufferedReader(Charsets.UTF_8)
        activeWriters.add(writer)
        inputController.resetBlockedState()
        try {
            while (true) {
                val raw = reader.nextLine(MAX_LINE_CHARS) ?: break
                if (raw == "H") continue
                runCatching { handleLine(raw, writer) }.onFailure {
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

    private fun handleLine(full: String, writer: Writer) {
        var line = full
        var displayId = -1
        if (line.startsWith("@")) {
            val space = line.indexOf(' ')
            if (space > 1) {
                displayId = line.substring(1, space).toIntOrNull() ?: return
                line = line.substring(space + 1)
            }
        }
        val parts = line.split(' ', limit = 2)
        val rest = parts.getOrElse(1) { "" }
        when (parts[0]) {
            "D", "M", "U" -> {
                val coords = rest.split(' ')
                if (coords.size < 2) return
                val x = coords[0].toFloatOrNull() ?: return
                val y = coords[1].toFloatOrNull() ?: return
                val action = when (parts[0]) {
                    "D" -> NectarlinkServiceContract.TOUCH_DOWN
                    "M" -> NectarlinkServiceContract.TOUCH_MOVE
                    else -> NectarlinkServiceContract.TOUCH_UP
                }
                inputController.touch(displayId, action, x, y)
            }
            "S" -> {
                val vals = rest.split(' ')
                if (vals.size < 4) return
                inputController.scroll(
                    displayId,
                    vals[0].toFloatOrNull() ?: return,
                    vals[1].toFloatOrNull() ?: return,
                    vals[2].toFloatOrNull() ?: return,
                    vals[3].toFloatOrNull() ?: return,
                )
            }
            "K" -> rest.toIntOrNull()?.let { inputController.key(displayId, it) }
            "V" -> if (displayId < 0) rest.toIntOrNull()?.let { systemControl.setMusicVolume(it) }
            "Y" -> {
                val vals = rest.split(' ')
                if (vals.size < 2) return
                inputController.key(displayId, vals[0].toIntOrNull() ?: return, vals[1].toIntOrNull() ?: return)
            }
            "FS" -> if (displayId < 0) {
                val vals = rest.split(' ')
                if (vals.size < 2) return
                val info = pointerFlow.start(vals[0], vals[1].toFloatOrNull() ?: return)
                val reply = if (info != null) "FI ${info.width} ${info.height} ${info.scale}\n" else "FI 0 0 0\n"
                synchronized(writer) {
                    writer.write(reply)
                    writer.flush()
                }
            }
            "FM" -> {
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
                val at = pointerFlow.where()
                synchronized(writer) {
                    writer.write("FP ${at[0]} ${at[1]}\n")
                    writer.flush()
                }
            }
            "FX" -> pointerFlow.stop()
            "T" -> inputController.text(displayId, rest)
            "G" -> {
                val tokens = rest.trim().split(' ')
                if (tokens.size < 7) return
                inputController.gamepad(
                    displayId,
                    tokens[0].toIntOrNull() ?: return,
                    tokens[1].toIntOrNull() ?: return,
                    tokens[2].toIntOrNull() ?: return,
                    tokens[3].toIntOrNull() ?: return,
                    tokens[4].toIntOrNull() ?: return,
                    tokens[5].toIntOrNull() ?: return,
                    tokens[6].toIntOrNull() ?: return,
                )
            }
            "C" -> if (displayId < 0) handleAllowedCommand(rest)
            "P" -> if (displayId < 0) displayPower.setScreenOff(rest.trim() == "0")
            "B" -> if (displayId < 0) setClipboardLine(rest)
            "X" -> if (displayId < 0) {
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

    private fun handleAllowedCommand(cmd: String) {
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
                cmd.startsWith(UNINSTALL_PREFIX) ->
                    systemControl.uninstallPackage(cmd.removePrefix(UNINSTALL_PREFIX))
                cmd.startsWith("am start -n ") -> {
                    val component = cmd.removePrefix("am start -n ").trim()
                    val pkg = component.substringBefore('/', "")
                    val cls = component.substringAfter('/', "")
                    if (pkg.isNotEmpty() && cls.isNotEmpty()) {
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
