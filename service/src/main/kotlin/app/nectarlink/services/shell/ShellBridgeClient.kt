// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.shell

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import app.nectarlink.services.IDisplaySession
import app.nectarlink.services.PointerScreenInfo
import app.nectarlink.services.storage.ServicesPrefs
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Connects from `NectarlinkPrivilegedService` (`priv_app`) to the `uid 2000`
 * [ShellBridgeServer] over authenticated loopback TCP when pure-`signature`
 * permissions are not directly granted to the APK.
 */
object ShellBridgeClient {
    private const val TAG = "ShellBridgeClient"
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val RPC_TIMEOUT_MS = 3_000L
    private const val RECONNECT_DELAY_MS = 3_000L
    private const val KIND_CONFIG = 0

    private val lock = Any()
    private val nextReqId = AtomicLong(1L)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<String>>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "nl-shell-reconnect").apply { isDaemon = true }
    }

    @Volatile
    private var port: Int = 0

    @Volatile
    private var token: String? = null

    @Volatile
    private var controlSocket: Socket? = null

    @Volatile
    private var controlWriter: Writer? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var onClipboardChanged: ((text: String, html: String?, isSensitive: Boolean) -> Unit)? = null

    @Volatile
    var onInputBlocked: (() -> Unit)? = null

    private val stateListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    fun addStateListener(listener: (Boolean) -> Unit) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: (Boolean) -> Unit) {
        stateListeners.remove(listener)
    }

    private fun notifyState(connected: Boolean) {
        stateListeners.forEach { runCatching { it(connected) } }
    }

    fun restoreAndConnect(context: Context) {
        val savedPort = ServicesPrefs.getShellBridgePort(context)
        val savedToken = ServicesPrefs.getShellBridgeToken(context)
        if (savedPort in 1..65535 && !savedToken.isNullOrBlank()) {
            Log.i(TAG, "Restoring shell bridge config for port $savedPort")
            configure(savedPort, savedToken)
        } else {
            Log.i(TAG, "No saved shell bridge config yet")
        }
    }

    fun configure(newPort: Int, newToken: String) {
        if (newPort !in 1..65535 || newToken.isBlank()) return
        synchronized(lock) {
            if (port == newPort && token == newToken && isConnected) return
            port = newPort
            token = newToken
            disconnectLocked(notify = false)
        }
        scheduler.execute { connectNow() }
    }

    private fun connectNow() {
        val targetPort: Int
        val targetToken: String
        synchronized(lock) {
            if (isConnected) return
            targetPort = port
            targetToken = token ?: return
        }
        if (targetPort !in 1..65535) return

        val sock = Socket()
        try {
            sock.tcpNoDelay = true
            sock.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort), CONNECT_TIMEOUT_MS)
            sock.soTimeout = CONNECT_TIMEOUT_MS
            val writer = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
            val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
            writer.write("$targetToken\nCTL\n")
            writer.flush()
            val ack = reader.readLine()
            if (ack != "OK") {
                Log.w(TAG, "Shell bridge handshake failed on port $targetPort: $ack")
                sock.close()
                scheduleReconnect()
                return
            }
            sock.soTimeout = 0
            synchronized(lock) {
                controlSocket = sock
                controlWriter = writer
                isConnected = true
            }
            Log.i(TAG, "Connected to uid-2000 shell bridge on port $targetPort")
            notifyState(true)

            thread(isDaemon = true, name = "nl-shell-ctl-read") {
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        handleIncomingLine(line)
                    }
                } catch (_: IOException) {
                } finally {
                    synchronized(lock) {
                        if (controlSocket === sock) {
                            disconnectLocked(notify = true)
                        }
                    }
                    scheduleReconnect()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shell bridge unavailable on port $targetPort: ${e.message}")
            runCatching { sock.close() }
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (port !in 1..65535 || token.isNullOrBlank()) return
        scheduler.schedule({ connectNow() }, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun disconnectLocked(notify: Boolean) {
        val wasConnected = isConnected
        isConnected = false
        controlWriter = null
        runCatching { controlSocket?.close() }
        controlSocket = null
        pending.values.forEach { it.cancel(false) }
        pending.clear()
        if (notify && wasConnected) {
            notifyState(false)
        }
    }

    private fun handleIncomingLine(line: String) {
        when {
            line.startsWith("RSP ") -> {
                val body = line.removePrefix("RSP ")
                val space = body.indexOf(' ')
                if (space > 0) {
                    val id = body.substring(0, space).toLongOrNull() ?: return
                    val result = body.substring(space + 1)
                    pending.remove(id)?.complete(result)
                }
            }
            line.startsWith("CLIP\t") -> {
                val parts = line.split('\t')
                if (parts.size >= 4) {
                    val text = runCatching {
                        String(Base64.decode(parts[1], Base64.NO_WRAP), Charsets.UTF_8)
                    }.getOrNull() ?: return
                    val html = parts[2].takeIf { it.isNotEmpty() }?.let {
                        runCatching { String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull()
                    }
                    val sensitive = parts[3] == "1"
                    onClipboardChanged?.invoke(text, html, sensitive)
                }
            }
            line == "BLOCKED" -> onInputBlocked?.invoke()
        }
    }

    private fun rpc(command: String): String? {
        val writer = controlWriter ?: return null
        val id = nextReqId.getAndIncrement()
        val future = CompletableFuture<String>()
        pending[id] = future
        return try {
            synchronized(writer) {
                writer.write("REQ $id $command\n")
                writer.flush()
            }
            future.get(RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            pending.remove(id)
            null
        }
    }

    private fun rpcBool(command: String): Boolean = rpc(command) == "1"

    fun injectTouch(displayId: Int, action: Int, x: Float, y: Float): Boolean =
        rpcBool("TOUCH $displayId $action $x $y")

    fun injectScroll(displayId: Int, x: Float, y: Float, dx: Float, dy: Float): Boolean =
        rpcBool("SCROLL $displayId $x $y $dx $dy")

    fun injectKey(displayId: Int, keyCode: Int, metaState: Int): Boolean =
        rpcBool("KEY $displayId $keyCode $metaState")

    fun injectText(displayId: Int, text: String): Boolean =
        rpcBool("TEXT $displayId ${Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}")

    fun injectGamepad(
        displayId: Int,
        buttons: Int,
        lx: Int,
        ly: Int,
        rx: Int,
        ry: Int,
        lt: Int,
        rt: Int,
    ): Boolean = rpcBool("GAMEPAD $displayId $buttons $lx $ly $rx $ry $lt $rt")

    fun pointerStart(edge: String, y: Float): PointerScreenInfo? {
        val raw = rpc("PSTART $edge $y") ?: return null
        if (raw == "ERR") return null
        val parts = raw.split(' ')
        if (parts.size < 3) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        val s = parts[2].toFloatOrNull() ?: return null
        return PointerScreenInfo(w, h, s)
    }

    fun pointerMove(dx: Int, dy: Int, buttons: Int, wheel: Int): Boolean =
        rpcBool("PMOVE $dx $dy $buttons $wheel")

    fun pointerWhere(): FloatArray {
        val raw = rpc("PWHERE") ?: return floatArrayOf(-1f, -1f)
        val parts = raw.split(' ')
        val x = parts.getOrNull(0)?.toFloatOrNull() ?: -1f
        val y = parts.getOrNull(1)?.toFloatOrNull() ?: -1f
        return floatArrayOf(x, y)
    }

    fun pointerStop() {
        rpcBool("PSTOP")
    }

    fun setScreenOff(off: Boolean): Boolean =
        rpcBool("SCREENOFF ${if (off) "1" else "0"}")

    fun wakeScreen(): Boolean = rpcBool("WAKE")

    fun setClipboard(text: String, html: String?, sensitive: Boolean): Boolean {
        val b64Text = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val b64Html = if (!html.isNullOrEmpty()) {
            Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } else {
            ""
        }
        return rpcBool("SETCLIP $b64Text\t$b64Html\t${if (sensitive) "1" else "0"}")
    }

    fun clearClipboard(expectedText: String): Boolean {
        val b64Text = Base64.encodeToString(expectedText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return rpcBool("CLEARCLIP $b64Text")
    }

    fun openDisplaySession(
        pkg: String,
        width: Int,
        height: Int,
        dpi: Int,
        bitrate: Int,
        fps: Int,
        codec: String,
    ): IDisplaySession? {
        val targetPort = port
        val targetToken = token ?: return null
        if (targetPort !in 1..65535) return null
        val sock = Socket()
        return try {
            sock.tcpNoDelay = true
            sock.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort), CONNECT_TIMEOUT_MS)
            val writer = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
            writer.write("$targetToken\nV $width $height $dpi $bitrate $fps $pkg $codec\n")
            writer.flush()
            ShellDisplaySession(sock, writer)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open shell display session ($pkg)", e)
            runCatching { sock.close() }
            null
        }
    }

    private class ShellDisplaySession(
        private val sock: Socket,
        private val writer: Writer,
    ) : IDisplaySession.Stub() {
        @Volatile
        private var currentDisplayId: Int = -1

        @Volatile
        private var readFd: ParcelFileDescriptor?

        private val writeFd: ParcelFileDescriptor

        init {
            val pipe = ParcelFileDescriptor.createPipe()
            readFd = pipe[0]
            writeFd = pipe[1]
            thread(isDaemon = true, name = "nl-shell-disp-pump") {
                try {
                    val dataIn = DataInputStream(sock.getInputStream().buffered(1 shl 16))
                    val dataOut = DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(writeFd))
                    while (true) {
                        val len = dataIn.readInt()
                        if (len < 9 || len > 16 * 1024 * 1024) break
                        val kind = dataIn.readUnsignedByte()
                        val timeUs = dataIn.readLong()
                        val payload = ByteArray(len - 9)
                        dataIn.readFully(payload)
                        if (kind == KIND_CONFIG && payload.size >= 12) {
                            currentDisplayId = ByteBuffer.wrap(payload).getInt(8)
                        }
                        dataOut.writeInt(len)
                        dataOut.writeByte(kind)
                        dataOut.writeLong(timeUs)
                        dataOut.write(payload)
                        dataOut.flush()
                    }
                } catch (_: IOException) {
                } finally {
                    close()
                }
            }
        }

        override fun getVideoReadFd(): ParcelFileDescriptor? = readFd.also { readFd = null }

        override fun getDisplayId(): Int = currentDisplayId

        override fun requestKeyframe() {
            runCatching {
                synchronized(writer) {
                    writer.write("K\n")
                    writer.flush()
                }
            }
        }

        override fun resize(width: Int, height: Int, dpi: Int) {
            runCatching {
                synchronized(writer) {
                    writer.write("R $width $height $dpi\n")
                    writer.flush()
                }
            }
        }

        override fun close() {
            runCatching { readFd?.close() }
            readFd = null
            runCatching { writeFd.close() }
            runCatching { sock.close() }
        }
    }
}
