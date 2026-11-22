// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.shell

import android.annotation.SuppressLint
import android.content.ClipData
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.PersistableBundle
import android.text.Html
import android.text.Spanned
import android.text.style.CharacterStyle
import android.util.Base64
import app.nectarlink.services.client.NectarlinkServiceContract
import app.nectarlink.services.display.AppDisplaySession
import app.nectarlink.services.display.DisplayPowerController
import app.nectarlink.services.display.VirtualDeviceOwner
import app.nectarlink.services.input.InputController
import app.nectarlink.services.input.PointerFlowController
import app.nectarlink.services.input.UhidBridge
import java.io.BufferedReader
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Shell-UID (`uid 2000`) helper launched via `app_process` by the root module's `service.sh`.
 *
 * Holds the ROM's platform-level `com.android.shell` capabilities (`INJECT_EVENTS`,
 * `CAPTURE_VIDEO_OUTPUT`, `ADD_TRUSTED_DISPLAY`, `DEVICE_POWER`, `READ_CLIPBOARD_IN_BACKGROUND`)
 * when `NectarlinkServices.apk` itself is signed with a non-platform key inside a root module.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object ShellBridgeServer {
    private const val AUTH_TIMEOUT_MS = 3_000
    private const val MAX_TOKEN_CHARS = 128
    private const val MAX_LINE_CHARS = 256 * 1024
    private const val MAX_CLIENTS = 24
    private const val LABEL_NECTARLINK = "Nectarlink"
    private const val EXTRA_SUPPRESS_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"
    private const val EXTRA_IS_REMOTE_DEVICE = "android.content.extra.IS_REMOTE_DEVICE"
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val clientPool = ThreadPoolExecutor(
        0,
        MAX_CLIENTS,
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { r -> Thread(r, "nl-shell-client").apply { isDaemon = true } }

    private val clipExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nl-shell-clip").apply { isDaemon = true }
    }

    private lateinit var shellContext: ShellContext
    private lateinit var inputController: InputController
    private lateinit var pointerFlow: PointerFlowController
    private lateinit var displayPower: DisplayPowerController

    private val displaySessions = Collections.synchronizedSet(LinkedHashSet<AppDisplaySession>())

    @Volatile
    private var controlOut: Writer? = null

    @Volatile
    private var clipService: Pair<Any, Method>? = null

    @Volatile
    private var clipGet: Method? = null

    @Volatile
    private var clipListenerProxy: Any? = null

    @Volatile
    private var lastSentClipKey: String? = null

    @Volatile
    private var blockedReported = false

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args.getOrNull(0)?.takeIf {
            it.length in 32..MAX_TOKEN_CHARS && it.all { c -> c in '0'..'9' || c in 'a'..'f' }
        } ?: run {
            System.err.println("usage: ShellBridgeServer <hex-token>")
            return
        }

        shellContext = ShellContext.get()
        inputController = InputController(onInputBlocked = { reportInputBlocked() })
        pointerFlow = PointerFlowController(shellContext, inputController, usePrefs = false)
        displayPower = DisplayPowerController(shellContext, inputController)

        pointerFlow.restoreAcceleration()
        VirtualDeviceOwner.forget(shellContext)
        startClipboardWatcher()

        val server = runCatching {
            ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        }.getOrElse {
            System.err.println("ShellBridgeServer bind failed: $it")
            return
        }

        println("ready ${server.localPort}")
        System.out.flush()

        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { server.close() }
                cleanupActiveState()
            },
        )

        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            try {
                clientPool.execute { serveClient(client, token) }
            } catch (_: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun cleanupActiveState() {
        runCatching { pointerFlow.stop() }
        runCatching { displayPower.restoreIfNeeded() }
        runCatching { UhidBridge.closeAll() }
        val open = synchronized(displaySessions) { displaySessions.toList().also { displaySessions.clear() } }
        open.forEach { runCatching { it.close() } }
    }

    private fun serveClient(client: Socket, token: String) {
        client.use { sock ->
            sock.tcpNoDelay = true
            sock.soTimeout = AUTH_TIMEOUT_MS
            val reader = sock.inputStream.bufferedReader(Charsets.UTF_8)
            val given = runCatching { reader.nextLine(MAX_TOKEN_CHARS) }.getOrNull() ?: return
            if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) return
            val first = runCatching { reader.nextLine(MAX_LINE_CHARS) }.getOrNull() ?: return
            sock.soTimeout = 0
            if (first == "CTL") {
                serveControl(sock, reader)
            } else if (first.startsWith("V ")) {
                serveDisplay(sock, reader, first)
            }
        }
    }

    private fun serveControl(sock: Socket, reader: BufferedReader) {
        val writer = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
        synchronized(writer) {
            writer.write("OK\n")
            writer.flush()
        }
        controlOut = writer
        blockedReported = false
        inputController.resetBlockedState()
        try {
            while (true) {
                val line = runCatching { reader.nextLine(MAX_LINE_CHARS) }.getOrNull() ?: break
                if (line.startsWith("REQ ")) {
                    val reply = handleRequest(line.removePrefix("REQ "))
                    synchronized(writer) {
                        writer.write("RSP $reply\n")
                        writer.flush()
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            if (controlOut === writer) {
                controlOut = null
            }
            cleanupActiveState()
            runCatching { writer.close() }
        }
    }

    private fun handleRequest(line: String): String {
        val firstSpace = line.indexOf(' ')
        if (firstSpace <= 0) return "0 0"
        val reqId = line.substring(0, firstSpace)
        val body = line.substring(firstSpace + 1)
        val parts = body.split(' ', '\t')
        return when (parts.getOrNull(0)) {
            "PING" -> "$reqId 1"
            "TOUCH" -> {
                val ok = if (parts.size >= 5) {
                    val d = parts[1].toIntOrNull()
                    val a = parts[2].toIntOrNull()
                    val x = parts[3].toFloatOrNull()
                    val y = parts[4].toFloatOrNull()
                    if (d != null && a != null && x != null && y != null &&
                        NectarlinkServiceContract.isAllowedTouch(d, a, x, y)
                    ) {
                        inputController.touch(d, a, x, y)
                    } else {
                        false
                    }
                } else {
                    false
                }
                "$reqId ${if (ok) "1" else "0"}"
            }
            "SCROLL" -> {
                val ok = if (parts.size >= 6) {
                    val d = parts[1].toIntOrNull()
                    val x = parts[2].toFloatOrNull()
                    val y = parts[3].toFloatOrNull()
                    val dx = parts[4].toFloatOrNull()
                    val dy = parts[5].toFloatOrNull()
                    if (d != null && x != null && y != null && dx != null && dy != null &&
                        NectarlinkServiceContract.isAllowedScroll(d, x, y, dx, dy)
                    ) {
                        inputController.scroll(d, x, y, dx, dy)
                    } else {
                        false
                    }
                } else {
                    false
                }
                "$reqId ${if (ok) "1" else "0"}"
            }
            "KEY" -> {
                val ok = if (parts.size >= 4) {
                    val d = parts[1].toIntOrNull()
                    val code = parts[2].toIntOrNull()
                    val meta = parts[3].toIntOrNull()
                    if (d != null && code != null && meta != null && NectarlinkServiceContract.isAllowedKey(d, code)) {
                        inputController.key(d, code, meta)
                    } else {
                        false
                    }
                } else {
                    false
                }
                "$reqId ${if (ok) "1" else "0"}"
            }
            "TEXT" -> {
                val ok = if (parts.size >= 3) {
                    val d = parts[1].toIntOrNull()
                    val decoded = runCatching {
                        String(Base64.decode(parts[2], Base64.NO_WRAP), Charsets.UTF_8)
                    }.getOrNull()
                    if (d != null && decoded != null && NectarlinkServiceContract.isAllowedText(d, decoded)) {
                        inputController.text(d, decoded)
                    } else {
                        false
                    }
                } else {
                    false
                }
                "$reqId ${if (ok) "1" else "0"}"
            }
            "GAMEPAD" -> {
                val ok = if (parts.size >= 9) {
                    val nums = parts.subList(1, 9).mapNotNull { it.toIntOrNull() }
                    if (nums.size == 8 &&
                        NectarlinkServiceContract.isAllowedGamepad(
                            nums[0], nums[2], nums[3], nums[4], nums[5], nums[6], nums[7],
                        )
                    ) {
                        inputController.gamepad(
                            nums[0], nums[1], nums[2], nums[3],
                            nums[4], nums[5], nums[6], nums[7],
                        )
                    } else {
                        false
                    }
                } else {
                    false
                }
                "$reqId ${if (ok) "1" else "0"}"
            }
            "PSTART" -> {
                val edge = parts.getOrNull(1)
                val y = parts.getOrNull(2)?.toFloatOrNull()
                val info = if (edge != null && y != null && NectarlinkServiceContract.isAllowedPointerStart(edge, y)) {
                    pointerFlow.start(edge, y)
                } else {
                    null
                }
                if (info != null) "$reqId ${info.width} ${info.height} ${info.scale}" else "$reqId ERR"
            }
            "PMOVE" -> {
                val ok = if (parts.size >= 5) {
                    val dx = parts[1].toIntOrNull()
                    val dy = parts[2].toIntOrNull()
                    val buttons = parts[3].toIntOrNull()
                    val wheel = parts[4].toIntOrNull()
                    if (dx != null && dy != null && buttons != null && wheel != null) {
                        pointerFlow.move(dx, dy, buttons, wheel)
                    } else {
                        false
                    }
                } else {
                    false
                }
                "$reqId ${if (ok) "1" else "0"}"
            }
            "PWHERE" -> {
                val pos = pointerFlow.where()
                if (pos.size >= 2) "$reqId ${pos[0]} ${pos[1]}" else "$reqId -1.0 -1.0"
            }
            "PSTOP" -> {
                pointerFlow.stop()
                "$reqId 1"
            }
            "SCREENOFF" -> {
                val off = parts.getOrNull(1) == "1"
                val ok = displayPower.setScreenOff(off)
                "$reqId ${if (ok) "1" else "0"}"
            }
            "WAKE" -> {
                val ok = displayPower.wakeScreen()
                "$reqId ${if (ok) "1" else "0"}"
            }
            "SETCLIP" -> {
                val tabParts = body.split('\t')
                val ok = if (tabParts.size >= 4) {
                    val text = decodeB64(tabParts[1])
                    val html = decodeB64(tabParts[2])?.takeIf { it.isNotEmpty() }
                    val sensitive = tabParts[3] == "1"
                    if (text != null) setClipboard(text, html, sensitive) else false
                } else {
                    false
                }
                "$reqId ${if (ok) "1" else "0"}"
            }
            "CLEARCLIP" -> {
                val tabParts = body.split('\t')
                val text = tabParts.getOrNull(1)?.let(::decodeB64)
                val ok = if (text != null) clearClipboard(text) else false
                "$reqId ${if (ok) "1" else "0"}"
            }
            else -> "$reqId 0"
        }
    }

    private fun serveDisplay(sock: Socket, reader: BufferedReader, request: String) {
        val parts = request.split(' ')
        if (parts.size < 7) return
        val w = parts[1].toIntOrNull() ?: return
        val h = parts[2].toIntOrNull() ?: return
        val dpi = parts[3].toIntOrNull() ?: return
        val bitrate = parts[4].toIntOrNull() ?: return
        val fps = parts[5].toIntOrNull() ?: return
        val pkg = parts[6].takeIf { it == "-" || NectarlinkServiceContract.isValidPackageName(it) } ?: return
        val codec = parts.getOrNull(7) ?: "h264"
        if (!NectarlinkServiceContract.isAllowedDisplayRequest(w, h, dpi, bitrate, fps, codec)) return

        lateinit var session: AppDisplaySession
        session = AppDisplaySession(
            context = shellContext,
            pkg = pkg,
            initialWidth = w,
            initialHeight = h,
            initialDpi = dpi,
            bitrate = bitrate,
            fps = fps,
            codecName = codec,
            wakePhone = { displayPower.wakeScreen() },
            useShellContext = true,
            onClosed = { displaySessions.remove(it) },
        )
        displaySessions.add(session)

        thread(isDaemon = true, name = "nl-shell-disp-ctl") {
            try {
                while (true) {
                    val cmd = reader.nextLine(MAX_TOKEN_CHARS) ?: break
                    when {
                        cmd == "K" -> session.requestKeyframe()
                        cmd.startsWith("R ") -> {
                            val r = cmd.split(' ')
                            val rw = r.getOrNull(1)?.toIntOrNull()
                            val rh = r.getOrNull(2)?.toIntOrNull()
                            val rdpi = r.getOrNull(3)?.toIntOrNull()
                            if (rw != null && rh != null && rdpi != null) {
                                session.resize(rw, rh, rdpi)
                            }
                        }
                    }
                }
            } catch (_: IOException) {
            }
            session.close()
        }

        try {
            val readFd = session.videoReadFd ?: return
            android.os.ParcelFileDescriptor.AutoCloseInputStream(readFd).use { pipeIn ->
                val out = sock.getOutputStream()
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val read = pipeIn.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    out.flush()
                }
            }
        } catch (_: IOException) {
        } finally {
            session.close()
            displaySessions.remove(session)
        }
    }

    private fun startClipboardWatcher() {
        runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "clipboard") as? IBinder ?: return
            val stub = Class.forName("android.content.IClipboard\$Stub")
            val clipboard = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return
            val getMethod = clipboard.javaClass.methods.firstOrNull { it.name == "getPrimaryClip" } ?: return
            clipGet = getMethod
            clipboard.javaClass.methods.firstOrNull { it.name == "setPrimaryClip" }?.let {
                clipService = clipboard to it
            }
            val addMethod = clipboard.javaClass.methods.firstOrNull { it.name == "addPrimaryClipChangedListener" } ?: return
            val listenerClass = Class.forName("android.content.IOnPrimaryClipChangedListener")
            val listenerBinder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == FIRST_CALL_TRANSACTION) {
                        runCatching { data.enforceInterface("android.content.IOnPrimaryClipChangedListener") }
                        readAndSendPrimaryClip(clipboard, getMethod)
                        return true
                    }
                    return super.onTransact(code, data, reply, flags)
                }
            }
            var proxyRef: Any? = null
            val proxy = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { _, method, args ->
                when (method.name) {
                    "asBinder" -> listenerBinder
                    "dispatchPrimaryClipChanged" -> {
                        readAndSendPrimaryClip(clipboard, getMethod)
                        null
                    }
                    "hashCode" -> System.identityHashCode(listenerBinder)
                    "equals" -> args?.getOrNull(0) === proxyRef
                    "toString" -> "NectarlinkShellClipListener"
                    else -> null
                }
            }
            proxyRef = proxy
            clipListenerProxy = proxy
            addMethod.invoke(clipboard, *buildClipboardArgs(addMethod.parameterTypes, proxy, null))
        }.onFailure {
            System.err.println("clipboard watcher unavailable: $it")
        }
    }

    private fun setClipboard(text: String, html: String?, sensitive: Boolean): Boolean {
        val (service, setMethod) = clipService ?: return false
        return runCatching {
            val clip = if (!html.isNullOrBlank()) {
                ClipData.newHtmlText(LABEL_NECTARLINK, text, html)
            } else {
                ClipData.newPlainText(LABEL_NECTARLINK, text)
            }
            clip.description.extras = PersistableBundle().apply {
                putBoolean(EXTRA_SUPPRESS_OVERLAY, true)
                putBoolean(EXTRA_IS_REMOTE_DEVICE, true)
                if (sensitive) putBoolean(EXTRA_IS_SENSITIVE, true)
            }
            setMethod.invoke(service, *buildClipboardArgs(setMethod.parameterTypes, null, clip))
            true
        }.getOrDefault(false)
    }

    private fun clearClipboard(expectedText: String): Boolean {
        val (service, setMethod) = clipService ?: return false
        val getMethod = clipGet ?: return false
        return runCatching {
            val clip = getMethod.invoke(service, *buildClipboardArgs(getMethod.parameterTypes, null, null))
                as? ClipData ?: return false
            if (clip.itemCount == 0 || clip.getItemAt(0).text?.toString() != expectedText) return false
            val empty = ClipData.newPlainText(LABEL_NECTARLINK, "")
            setMethod.invoke(service, *buildClipboardArgs(setMethod.parameterTypes, null, empty))
            true
        }.getOrDefault(false)
    }

    private fun readAndSendPrimaryClip(clipboard: Any, getMethod: Method) {
        val out = controlOut ?: return
        clipExecutor.execute {
            runCatching {
                val clip = getMethod.invoke(clipboard, *buildClipboardArgs(getMethod.parameterTypes, null, null))
                    as? ClipData ?: return@runCatching
                val desc = clip.description
                val label = desc?.label?.toString()
                val extras = desc?.extras
                val isSuppressed = label == "host clipboard" ||
                    label == LABEL_NECTARLINK ||
                    extras?.getBoolean(EXTRA_SUPPRESS_OVERLAY) == true ||
                    extras?.getBoolean(EXTRA_IS_REMOTE_DEVICE) == true
                if (isSuppressed) {
                    lastSentClipKey = null
                    return@runCatching
                }
                val isSensitive = extras?.getBoolean(EXTRA_IS_SENSITIVE) == true
                for (i in 0 until clip.itemCount) {
                    val item = clip.getItemAt(i)
                    val cs = item.text ?: continue
                    val text = cs.toString().takeIf { it.isNotBlank() } ?: continue
                    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
                    val html = item.htmlText?.takeIf { it.isNotBlank() } ?: run {
                        if (cs is Spanned && cs.getSpans(0, cs.length, CharacterStyle::class.java).isNotEmpty()) {
                            Html.toHtml(cs, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE).takeIf { it.isNotBlank() }
                        } else {
                            null
                        }
                    }
                    val dedupKey = "$normalized\u0000${html.orEmpty()}"
                    if (dedupKey == lastSentClipKey) return@runCatching
                    lastSentClipKey = dedupKey
                    val b64Text = encodeB64(text)
                    val b64Html = if (html != null) encodeB64(html) else ""
                    synchronized(out) {
                        out.write("CLIP\t$b64Text\t$b64Html\t${if (isSensitive) "1" else "0"}\n")
                        out.flush()
                    }
                    return@runCatching
                }
            }
        }
    }

    private fun buildClipboardArgs(
        paramTypes: Array<Class<*>>,
        listener: Any?,
        clipData: ClipData?,
    ): Array<Any?> {
        var seenPkg = false
        return Array(paramTypes.size) { i ->
            val t = paramTypes[i]
            when {
                t.name == "android.content.IOnPrimaryClipChangedListener" -> listener
                t == ClipData::class.java -> clipData
                t == String::class.java -> if (!seenPkg) {
                    seenPkg = true
                    ShellContext.SHELL_PACKAGE
                } else {
                    null
                }
                t == Int::class.javaPrimitiveType || t == Int::class.javaObjectType -> 0
                t == Boolean::class.javaPrimitiveType || t == Boolean::class.javaObjectType -> false
                else -> null
            }
        }
    }

    private fun reportInputBlocked() {
        if (blockedReported) return
        val out = controlOut ?: return
        blockedReported = true
        runCatching {
            synchronized(out) {
                out.write("BLOCKED\n")
                out.flush()
            }
        }
    }

    private fun encodeB64(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decodeB64(b64: String): String? =
        runCatching { String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull()

    private fun BufferedReader.nextLine(maxChars: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val ch = read()
            if (ch < 0) return if (sb.isEmpty()) null else sb.toString()
            if (ch == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') {
                    sb.setLength(sb.length - 1)
                }
                return sb.toString()
            }
            if (sb.length < maxChars) {
                sb.append(ch.toChar())
            } else {
                throw IOException("Line exceeded $maxChars chars")
            }
        }
    }
}
