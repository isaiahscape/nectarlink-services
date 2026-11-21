// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.display

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import app.nectarlink.services.IDisplaySession
import app.nectarlink.services.client.NectarlinkServiceContract
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.concurrent.thread

@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi")
class AppDisplaySession(
    context: Context,
    private val pkg: String,
    private val initialWidth: Int,
    private val initialHeight: Int,
    private val initialDpi: Int,
    bitrate: Int,
    fps: Int,
    private val codecName: String = "h264",
    private val wakePhone: () -> Unit = {},
    private val useShellContext: Boolean = false,
    private val onClosed: (AppDisplaySession) -> Unit = {},
) : IDisplaySession.Stub() {
    private data class EncodedSurface(
        val codecName: String,
        val codec: MediaCodec,
        val surface: Surface,
    )

    private val appContext = context.applicationContext
    private val bitrate = bitrate.coerceIn(500_000, 40_000_000)
    private val fps = fps.coerceIn(1, 120)

    @Volatile private var running = true
    @Volatile private var nextSize: Triple<Int, Int, Int>? = null
    @Volatile private var size = Triple(initialWidth, initialHeight, initialDpi)
    @Volatile private var codecForKeyframes: MediaCodec? = null
    @Volatile private var currentDisplayId: Int = -1

    private var lastRotationCheck = 0L
    private var display: VirtualDisplay? = null
    private var device: VirtualDeviceOwner? = null
    @Volatile private var readFd: ParcelFileDescriptor?
    private val writeFd: ParcelFileDescriptor

    init {
        val pipe = ParcelFileDescriptor.createPipe()
        readFd = pipe[0]
        writeFd = pipe[1]
        thread(isDaemon = true, name = "nl-display-$pkg") {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { out ->
                    stream(out)
                }
            }.onFailure {
                Log.w(TAG, "display session ($pkg) ended with error", it)
            }
            onClosed(this)
        }
    }

    override fun getVideoReadFd(): ParcelFileDescriptor? = readFd.also { readFd = null }

    override fun getDisplayId(): Int = currentDisplayId

    override fun requestKeyframe() {
        val codec = codecForKeyframes ?: return
        runCatching {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    override fun resize(width: Int, height: Int, dpi: Int) {
        if (width !in 16..4096 || height !in 16..4096 || dpi !in 72..1000) return
        nextSize = Triple(width, height, dpi)
    }

    override fun close() {
        running = false
        runCatching { readFd?.close() }
        readFd = null
        runCatching { writeFd.close() }
    }

    private fun stream(out: OutputStream) {
        val fullScreen = pkg == "-"
        var curW = initialWidth
        var curH = initialHeight
        var curDpi = initialDpi
        var codec: MediaCodec? = null
        var surface: Surface? = null
        val data = DataOutputStream(out.buffered(1 shl 16))
        try {
            val first = createEncoder(curW, curH)
            codec = first.codec
            surface = first.surface
            val made = if (fullScreen) {
                createMirrorDisplay(curW, curH, curDpi, first.surface)
            } else {
                createAppDisplay(curW, curH, curDpi, first.surface)
            }
            display = made
            currentDisplayId = if (fullScreen) 0 else made.display.displayId
            if (!fullScreen) {
                hideIme(currentDisplayId)
            }
            codecForKeyframes = first.codec
            writeConfig(data, curW, curH, currentDisplayId, first.codecName)
            if (!fullScreen) {
                launchApp(pkg, currentDisplayId)
                watchActivities(currentDisplayId)
                keepActive(currentDisplayId)
            }
            while (running) {
                val activeCodec = codec ?: break
                drain(activeCodec, data)
                if (!running) break
                val (newW, newH, newDpi) = nextSize ?: continue
                nextSize = null
                if (newW == curW && newH == curH && newDpi == curDpi) continue
                if (newW == curW && newH == curH) {
                    made.resize(curW, curH, newDpi)
                    forceDensity(currentDisplayId, newDpi)
                    curDpi = newDpi
                    size = Triple(curW, curH, curDpi)
                    continue
                }
                codecForKeyframes = null
                runCatching { activeCodec.stop() }
                runCatching { activeCodec.release() }
                codec = null
                runCatching { surface?.release() }
                surface = null
                val next = createEncoder(newW, newH)
                codec = next.codec
                surface = next.surface
                codecForKeyframes = next.codec
                made.resize(newW, newH, newDpi)
                made.surface = next.surface
                curW = newW
                curH = newH
                curDpi = newDpi
                size = Triple(curW, curH, curDpi)
                writeConfig(data, curW, curH, currentDisplayId, next.codecName)
            }
        } finally {
            if (!fullScreen && currentDisplayId > 0) forceDensity(currentDisplayId, null)
            running = false
            codecForKeyframes = null
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { surface?.release() }
            runCatching { display?.release() }
            display = null
            device?.close()
            device = null
        }
    }

    private fun followAppOrientation() {
        if (currentDisplayId <= 0) return
        val now = SystemClock.uptimeMillis()
        if (now - lastRotationCheck < ROTATION_CHECK_MS) return
        lastRotationCheck = now
        val rotation = runCatching { display?.display?.rotation }.getOrNull() ?: return
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val (w, h, d) = size
            nextSize = Triple(h, w, d)
        }
    }

    private fun mimeForCodec(name: String): String = when (name.lowercase()) {
        "h265", "hevc" -> MediaFormat.MIMETYPE_VIDEO_HEVC
        "av1" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "video/av01"
        } else {
            throw UnsupportedOperationException("AV1 requires Android 10+")
        }
        else -> MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private fun createEncoderForCodec(name: String, w: Int, h: Int): EncodedSurface {
        val mime = mimeForCodec(name)
        val format = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }
        val codec = MediaCodec.createEncoderByType(mime)
        var surface: Surface? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            return EncodedSurface(name, codec, surface)
        } catch (error: Exception) {
            runCatching { surface?.release() }
            runCatching { codec.release() }
            throw error
        }
    }

    private fun createEncoder(w: Int, h: Int): EncodedSurface {
        if (codecName != "h264") {
            try {
                return createEncoderForCodec(codecName, w, h)
            } catch (error: Exception) {
                Log.i(TAG, "$codecName encoder unavailable; falling back to H.264", error)
            }
        }
        return createEncoderForCodec("h264", w, h)
    }

    private fun writeConfig(
        out: DataOutputStream,
        width: Int,
        height: Int,
        displayId: Int,
        codec: String,
    ) {
        val codecId = when (codec) {
            "h264" -> NectarlinkServiceContract.CODEC_ID_H264
            "h265" -> NectarlinkServiceContract.CODEC_ID_H265
            "av1" -> NectarlinkServiceContract.CODEC_ID_AV1
            else -> error("Unsupported screen codec: $codec")
        }
        write(
            out,
            NectarlinkServiceContract.PACKET_KIND_CONFIG,
            0,
            ByteArray(16).also {
                ByteBuffer.wrap(it).putInt(width).putInt(height).putInt(displayId).putInt(codecId)
            },
        )
        out.flush()
    }

    private fun drain(codec: MediaCodec, out: DataOutputStream) {
        val info = MediaCodec.BufferInfo()
        var parameters = ByteArray(0)
        while (running && nextSize == null) {
            followAppOrientation()
            val index = codec.dequeueOutputBuffer(info, 50_000)
            if (index < 0) continue
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                when {
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> parameters = bytes
                    info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> {
                        write(out, NectarlinkServiceContract.PACKET_KIND_KEYFRAME, info.presentationTimeUs, parameters + bytes)
                        out.flush()
                    }
                    else -> {
                        write(out, NectarlinkServiceContract.PACKET_KIND_FRAME, info.presentationTimeUs, bytes)
                        out.flush()
                    }
                }
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    private fun write(out: DataOutputStream, kind: Int, timeUs: Long, bytes: ByteArray) {
        out.writeInt(1 + 8 + bytes.size)
        out.writeByte(kind)
        out.writeLong(timeUs)
        out.write(bytes)
    }

    private fun displayManager(): DisplayManager {
        if (useShellContext) {
            runCatching {
                val constructor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
                constructor.isAccessible = true
                return constructor.newInstance(appContext)
            }
        }
        return appContext.getSystemService(DisplayManager::class.java)
            ?: error("DisplayManager unavailable")
    }

    private fun createMirrorDisplay(w: Int, h: Int, d: Int, surface: Surface): VirtualDisplay {
        runCatching {
            val m = DisplayManager::class.java.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java,
            )
            val vd = m.invoke(null, "NectarlinkScreen", w, h, 0, surface) as? VirtualDisplay
            if (vd != null) return vd
        }
        val manager = displayManager()
        @SuppressLint("WrongConstant")
        return runCatching {
            manager.createVirtualDisplay(
                "NectarlinkScreen",
                w,
                h,
                d,
                surface,
                PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            )
        }.getOrElse {
            manager.createVirtualDisplay(
                "NectarlinkScreen",
                w,
                h,
                d,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            )
        }
    }

    private fun createAppDisplay(w: Int, h: Int, d: Int, surface: Surface): VirtualDisplay =
        try {
            createAppDisplay(w, h, d, surface, public = true)
        } catch (e: SecurityException) {
            if (e.message?.contains("CAPTURE_VIDEO_OUTPUT") != true) throw e
            createAppDisplay(w, h, d, surface, public = false)
        }

    private fun createAppDisplay(w: Int, h: Int, d: Int, surface: Surface, public: Boolean): VirtualDisplay {
        var flags = OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or DESTROY_CONTENT_ON_REMOVAL
        if (public) flags = flags or PUBLIC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flags or OWN_FOCUS or DEVICE_DISPLAY_GROUP
            ownedDisplay(w, h, d, surface, flags)?.let { return it }
        }
        wakePhone()
        val manager = displayManager()
        @SuppressLint("WrongConstant")
        return manager.createVirtualDisplay("Nectarlink", w, h, d, surface, flags)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun ownedDisplay(w: Int, h: Int, d: Int, surface: Surface, flags: Int): VirtualDisplay? {
        val owner = VirtualDeviceOwner.create(appContext) ?: return null
        return runCatching {
            owner.createVirtualDisplay("Nectarlink", w, h, d, surface, flags and OWN_DISPLAY_GROUP.inv())
        }.onSuccess { device = owner }.onFailure {
            Log.i(TAG, "no display on virtual device: $it")
            owner.close()
        }.getOrNull()
    }

    private fun forceDensity(display: Int, dpi: Int?) {
        val applied = runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val wm = wmg.getMethod("getWindowManagerService").invoke(null) ?: return@runCatching false
            val userId = android.os.Process.myUserHandle().hashCode()
            if (dpi != null) {
                wm.javaClass.getMethod(
                    "setForcedDisplayDensityForUser",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).invoke(wm, display, dpi, userId)
            } else {
                wm.javaClass.getMethod(
                    "clearForcedDisplayDensityForUser",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).invoke(wm, display, userId)
            }
            true
        }.getOrDefault(false)
        if (!applied) {
            runCatching {
                Runtime.getRuntime()
                    .exec(arrayOf("wm", "density", dpi?.toString() ?: "reset", "-d", display.toString()))
                    .waitFor()
            }
        }
    }

    private fun hideIme(display: Int) {
        val hidden = runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val wm = wmg.getMethod("getWindowManagerService").invoke(null) ?: return@runCatching false
            wm.javaClass
                .getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, display, DISPLAY_IME_POLICY_HIDE)
            true
        }.getOrDefault(false)
        if (!hidden) {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("wm", "set-display-ime-policy", display.toString(), "hide")).waitFor()
            }
        }
    }

    private fun launchApp(pkg: String, displayId: Int) {
        if (!useShellContext) {
            val launchIntent = appContext.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            } ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            val options = ActivityOptions.makeBasic().apply {
                setLaunchDisplayId(displayId)
            }
            val launched = runCatching {
                appContext.startActivity(launchIntent, options.toBundle())
                true
            }.getOrElse { e ->
                Log.w(TAG, "direct startActivity failed for $pkg on display $displayId, trying am start", e)
                false
            }
            if (launched) return
        }
        val target = launcherActivity(pkg)?.let { arrayOf("-n", it) }
            ?: arrayOf("-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER, "-p", pkg)
        val cmd = arrayOf("am", "start", "--display", displayId.toString(), "--windowingMode", "1") +
            target + arrayOf("-f", (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK).toString())
        runCatching { Runtime.getRuntime().exec(cmd).waitFor() }
    }

    private fun launcherActivity(pkg: String): String? = runCatching {
        val process = Runtime.getRuntime().exec(
            arrayOf(
                "cmd", "package", "resolve-activity", "--brief", "--user", "0",
                "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER, pkg,
            ),
        )
        val last = process.inputStream.bufferedReader().readLines().lastOrNull { it.isNotBlank() }?.trim()
        process.waitFor()
        last?.takeIf { it.startsWith("$pkg/") }
    }.getOrNull()

    private fun keepActive(display: Int) {
        val power = runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "power") as IBinder
            Class.forName("android.os.IPowerManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.getOrNull() ?: return

        val perDisplay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val method = runCatching {
            if (perDisplay) {
                power.javaClass.getMethod(
                    "userActivity",
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            } else {
                power.javaClass.getMethod(
                    "userActivity",
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            }
        }.getOrNull() ?: return

        thread(isDaemon = true, name = "nl-display-active") {
            try {
                while (running) {
                    val now = SystemClock.uptimeMillis()
                    val ok = runCatching {
                        if (perDisplay) method.invoke(power, display, now, EVENT_OTHER, NO_CHANGE_LIGHTS)
                        else method.invoke(power, now, EVENT_OTHER, NO_CHANGE_LIGHTS)
                    }.isSuccess
                    if (!ok) break
                    Thread.sleep(KEEP_ACTIVE_MS)
                }
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun watchActivities(display: Int) {
        thread(isDaemon = true, name = "nl-display-watch") {
            try {
                Thread.sleep(1_500)
                var seen = false
                var emptyCount = 0
                var ticks = 0
                while (running) {
                    if (hasActivityOnDisplay(display)) {
                        seen = true
                        emptyCount = 0
                    } else if (seen || ticks >= 15) {
                        emptyCount++
                        if (emptyCount >= 2) {
                            running = false
                            break
                        }
                    }
                    ticks++
                    Thread.sleep(1_000)
                }
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun hasActivityOnDisplay(display: Int): Boolean = runCatching {
        val service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            ?: return@runCatching true
        val tasks = service.javaClass
            .getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType)
            .invoke(service, display) as List<*>
        tasks.any { task ->
            if (task == null) return@any false
            val visible = runCatching { task.javaClass.getField("visible").getBoolean(task) }.getOrDefault(true)
            val children = runCatching { (task.javaClass.getField("childTaskIds").get(task) as IntArray).size }.getOrDefault(1)
            visible && children > 0
        }
    }.getOrDefault(true)

    companion object {
        private const val TAG = "AppDisplaySession"
        private const val KEEP_ACTIVE_MS = 4_000L
        private const val ROTATION_CHECK_MS = 250L
        private const val DISPLAY_IME_POLICY_HIDE = 2
        private const val EVENT_OTHER = 0
        private const val NO_CHANGE_LIGHTS = 1

        private const val PUBLIC = 1
        private const val OWN_CONTENT_ONLY = 1 shl 3
        private const val SUPPORTS_TOUCH = 1 shl 6
        private const val ROTATES_WITH_CONTENT = 1 shl 7
        private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val TRUSTED = 1 shl 10
        private const val OWN_DISPLAY_GROUP = 1 shl 11
        private const val ALWAYS_UNLOCKED = 1 shl 12
        private const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val OWN_FOCUS = 1 shl 14
        private const val DEVICE_DISPLAY_GROUP = 1 shl 15
    }
}
