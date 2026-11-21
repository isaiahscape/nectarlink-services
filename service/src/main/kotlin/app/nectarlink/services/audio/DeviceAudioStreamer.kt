// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.KeyEvent
import app.nectarlink.services.input.InputController
import kotlin.concurrent.thread

@SuppressLint("MissingPermission", "PrivateApi", "DiscouragedPrivateApi")
class DeviceAudioStreamer(private val inputController: InputController) {
    fun openStream(): ParcelFileDescriptor? {
        val recorder = openRecorder() ?: return null
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]
        thread(isDaemon = true, name = "nl-device-audio") {
            ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { out ->
                try {
                    recorder.startRecording()
                    thread(isDaemon = true, name = "nl-submix-vol") {
                        raiseSubmixVolume { recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING }
                    }
                    val buf = ByteArray(FRAME_BYTES)
                    while (true) {
                        val n = recorder.read(buf, 0, buf.size)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        out.flush()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "device audio stream closed: ${e.message}")
                } finally {
                    runCatching { recorder.stop() }
                    runCatching { recorder.release() }
                }
            }
        }
        return readFd
    }

    private fun openRecorder(): AudioRecord? {
        val minBuf = maxOf(
            AudioRecord.getMinBufferSize(RATE, CHANNEL_MASK, ENCODING),
            FRAME_BYTES * 4,
        )
        val format = AudioFormat.Builder()
            .setSampleRate(RATE)
            .setChannelMask(CHANNEL_MASK)
            .setEncoding(ENCODING)
            .build()

        runCatching<Unit> {
            val attrBuilder = AudioAttributes.Builder()
            attrBuilder.javaClass.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
                .invoke(attrBuilder, MediaRecorder.AudioSource.REMOTE_SUBMIX)
            val recBuilder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf)
            recBuilder.javaClass.getMethod("setAudioAttributes", AudioAttributes::class.java)
                .invoke(recBuilder, attrBuilder.build())
            val rec = recBuilder.build()
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
            rec.release()
        }

        runCatching {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.REMOTE_SUBMIX,
                RATE,
                CHANNEL_MASK,
                ENCODING,
                minBuf,
            )
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
            rec.release()
        }
        return null
    }

    private fun raiseSubmixVolume(recording: () -> Boolean) {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "audio") as IBinder
            val audio = Class.forName("android.media.IAudioService\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val read = { method: String ->
                audio.javaClass.getMethod(method, Int::class.javaPrimitiveType).invoke(audio, STREAM_MUSIC) as Int
            }
            while (recording() && read("getDeviceMaskForStream") and DEVICE_OUT_REMOTE_SUBMIX == 0) {
                Thread.sleep(SUBMIX_POLL_MS)
            }
            val max = read("getStreamMaxVolume")
            var presses = 0
            while (recording() && read("getDeviceMaskForStream") and DEVICE_OUT_REMOTE_SUBMIX != 0 &&
                read("getStreamVolume") < max && presses++ <= max
            ) {
                inputController.key(-1, KeyEvent.KEYCODE_VOLUME_UP)
                Thread.sleep(SUBMIX_PRESS_MS)
            }
        }
    }

    companion object {
        private const val TAG = "DeviceAudioStreamer"
        private const val RATE = 48_000
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_BYTES = (RATE / 100) * 2 * 2
        private const val STREAM_MUSIC = 3
        private const val DEVICE_OUT_REMOTE_SUBMIX = 0x8000
        private const val SUBMIX_POLL_MS = 500L
        private const val SUBMIX_PRESS_MS = 40L
    }
}
