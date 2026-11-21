// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "MissingPermission")
object CallAudioStreamer {
    private const val TAG = "CallAudioStreamer"
    private const val RATE = 16_000
    private const val CHUNK_BYTES = RATE / 50 * 2
    private const val MODE_IN_CALL = 2
    private const val CALL_REDIRECT_PSTN = 1
    private const val VOICE_DOWNLINK = 3
    private const val USAGE_CALL_ASSISTANT = 17
    private const val MODE_CHECK_CHUNKS = 50

    fun isSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching { interceptable(audioService()) }.getOrDefault(false)
    }

    fun openCallAudio(): ParcelFileDescriptor {
        val pair = ParcelFileDescriptor.createSocketPair()
        val clientSide = pair[0]
        val serviceSide = pair[1]
        thread(isDaemon = true, name = "nl-call-audio") {
            serviceSide.use { sock ->
                val out = ParcelFileDescriptor.AutoCloseOutputStream(sock)
                val input = ParcelFileDescriptor.AutoCloseInputStream(sock)
                serveStreams(input, out)
            }
        }
        return clientSide
    }

    fun serveStreams(input: InputStream, out: OutputStream) {
        var record: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            val audio = audioService()
            val mode = { audio.javaClass.getMethod("getMode").invoke(audio) as Int }
            if (mode() != MODE_IN_CALL) return refuse(out, "no call in progress")
            if (!interceptable(audio)) return refuse(out, "this phone doesn't let apps reach a call's sound")
            track = runCatching(::uplinkTrack).getOrElse { return refuse(out, "can't play into the call: $it") }
            record = runCatching(::downlinkRecord).getOrElse { return refuse(out, "can't hear the call: $it") }
            out.write("OK\n".toByteArray())
            out.flush()

            val playing = track
            playing.play()
            thread(isDaemon = true, name = "nl-call-uplink") {
                val chunk = ByteArray(CHUNK_BYTES)
                runCatching {
                    while (true) {
                        val n = input.read(chunk)
                        if (n <= 0) break
                        if (playing.write(chunk, 0, n - n % 2, AudioTrack.WRITE_NON_BLOCKING) < 0) break
                    }
                }
                runCatching { out.close() }
            }

            record.startRecording()
            val chunk = ByteArray(CHUNK_BYTES)
            var chunks = 0
            while (true) {
                val n = record.read(chunk, 0, chunk.size)
                if (n < 0) break
                out.write(chunk, 0, n)
                out.flush()
                if (++chunks % MODE_CHECK_CHUNKS == 0 && mode() != MODE_IN_CALL) break
            }
        } catch (_: IOException) {
        } catch (e: Exception) {
            Log.w(TAG, "call sound failed", e)
        } finally {
            record?.let { runCatching { it.stop() }; it.release() }
            track?.let { runCatching { it.stop() }; it.release() }
        }
    }

    private fun refuse(out: OutputStream, why: String) {
        runCatching {
            out.write("ERR $why\n".toByteArray())
            out.flush()
        }
    }

    private fun audioService(): Any {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "audio") as IBinder
        return Class.forName("android.media.IAudioService\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    private fun interceptable(audio: Any): Boolean =
        runCatching { audio.javaClass.getMethod("isPstnCallAudioInterceptable").invoke(audio) as Boolean }
            .getOrDefault(false)

    private fun uplinkTrack(): AudioTrack {
        val attrs = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        attrs.javaClass.getMethod("setSystemUsage", Int::class.javaPrimitiveType).invoke(attrs, USAGE_CALL_ASSISTANT)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attrs.build())
            .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
            .setBufferSizeInBytes(CHUNK_BYTES * 4)
        builder.javaClass.getMethod("setCallRedirectionMode", Int::class.javaPrimitiveType)
            .invoke(builder, CALL_REDIRECT_PSTN)
        return builder.build().also { check(it.state == AudioTrack.STATE_INITIALIZED) { "not initialized" } }
    }

    private fun downlinkRecord(): AudioRecord {
        val attrs = AudioAttributes.Builder()
        attrs.javaClass.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType).invoke(attrs, VOICE_DOWNLINK)
        val builder = AudioRecord.Builder()
            .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
            .setBufferSizeInBytes(CHUNK_BYTES * 4)
        builder.javaClass.getMethod("setAudioAttributes", AudioAttributes::class.java).invoke(builder, attrs.build())
        builder.javaClass.getMethod("setCallRedirectionMode", Int::class.javaPrimitiveType)
            .invoke(builder, CALL_REDIRECT_PSTN)
        return builder.build().also { check(it.state == AudioRecord.STATE_INITIALIZED) { "not initialized" } }
    }

    private fun format(channels: Int): AudioFormat =
        AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(channels).build()
}
