// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.input

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object UhidBridge {
    private const val UHID_DESTROY = 1
    private const val UHID_CREATE2 = 11
    private const val UHID_INPUT2 = 12
    private const val UHID_EVENT_SIZE = 4380
    private const val BUS_USB = 0x03

    const val MOUSE_NAME = "NectarLink Virtual Mouse"

    private val GAMEPAD_RD = byteArrayOf(
        0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x10, 0x81.toByte(), 0x02,
        0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(), 0x42,
        0x65, 0x00, 0x45, 0x00, 0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(), 0x03,
        0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
        0x16, 0x01, 0x80.toByte(), 0x26, 0xFF.toByte(), 0x7F, 0x75, 0x10, 0x95.toByte(), 0x04, 0x81.toByte(), 0x02,
        0x05, 0x02, 0x09, 0xC5.toByte(), 0x09, 0xC4.toByte(), 0x15, 0x00, 0x26, 0xFF.toByte(), 0x00, 0x75, 0x08, 0x95.toByte(), 0x02, 0x81.toByte(), 0x02,
        0xC0.toByte(),
    )

    private val MOUSE_RD = byteArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01,
        0x09, 0x01, 0xA1.toByte(), 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03, 0x81.toByte(), 0x06,
        0xC0.toByte(), 0xC0.toByte(),
    )

    @Volatile private var gamepadFd: RandomAccessFile? = null
    @Volatile private var gamepadAttempted = false
    @Volatile private var mouseFd: RandomAccessFile? = null

    fun isAvailable(): Boolean = runCatching {
        val f = File("/dev/uhid")
        f.exists() && f.canWrite()
    }.getOrDefault(false)

    private fun openDevice(name: String, product: Int, rd: ByteArray): RandomAccessFile? = runCatching {
        val raf = RandomAccessFile("/dev/uhid", "rw")
        val buf = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(UHID_CREATE2)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        buf.position(4)
        buf.put(nameBytes, 0, minOf(nameBytes.size, 127))
        val physBytes = "nectarlink/uhid".toByteArray(Charsets.UTF_8)
        buf.position(4 + 128)
        buf.put(physBytes, 0, minOf(physBytes.size, 63))
        val uniqBytes = "nectarlink-$product".toByteArray(Charsets.UTF_8)
        buf.position(4 + 128 + 64)
        buf.put(uniqBytes, 0, minOf(uniqBytes.size, 63))
        buf.position(4 + 128 + 64 + 64)
        buf.putShort(rd.size.toShort())
        buf.putShort(BUS_USB.toShort())
        buf.putInt(0x18D1)
        buf.putInt(product)
        buf.putInt(1)
        buf.putInt(0)
        buf.put(rd)
        raf.write(buf.array())
        raf
    }.getOrNull()

    private fun writeInputReport(raf: RandomAccessFile, report: ByteArray): Boolean = runCatching {
        val buf = ByteBuffer.allocate(4 + 2 + report.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(UHID_INPUT2)
        buf.putShort(report.size.toShort())
        buf.put(report)
        raf.write(buf.array())
        true
    }.getOrDefault(false)

    private fun destroy(fd: RandomAccessFile) {
        runCatching {
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(UHID_DESTROY)
            fd.write(buf.array())
            fd.close()
        }
    }

    @Synchronized
    fun sendGamepadReport(buttons: Int, lx: Int, ly: Int, rx: Int, ry: Int, lt: Int, rt: Int): Boolean {
        if (!gamepadAttempted) {
            gamepadAttempted = true
            gamepadFd = openDevice("NectarLink Virtual Gamepad", 0x4EE8, GAMEPAD_RD)
        }
        val fd = gamepadFd ?: return false
        val report = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(GamepadMapping.hidButtons(buttons).toShort())
            put(GamepadMapping.hat(buttons).toByte())
            putShort(lx.coerceIn(-32767, 32767).toShort())
            putShort(GamepadMapping.stickY(ly).toShort())
            putShort(rx.coerceIn(-32767, 32767).toShort())
            putShort(GamepadMapping.stickY(ry).toShort())
            put(lt.coerceIn(0, 255).toByte())
            put(rt.coerceIn(0, 255).toByte())
        }.array()
        return writeInputReport(fd, report)
    }

    @Synchronized
    fun openMouse(): Boolean {
        if (mouseFd == null) mouseFd = openDevice(MOUSE_NAME, 0x4EE9, MOUSE_RD)
        return mouseFd != null
    }

    @Synchronized
    fun mouse(dx: Int, dy: Int, buttons: Int, wheel: Int): Boolean {
        val fd = mouseFd ?: return false
        var x = dx
        var y = dy
        var w = wheel
        do {
            val sx = x.coerceIn(-127, 127)
            val sy = y.coerceIn(-127, 127)
            val sw = w.coerceIn(-127, 127)
            val report = byteArrayOf((buttons and 0x07).toByte(), sx.toByte(), sy.toByte(), sw.toByte())
            if (!writeInputReport(fd, report)) return false
            x -= sx
            y -= sy
            w -= sw
        } while (x != 0 || y != 0 || w != 0)
        return true
    }

    @Synchronized
    fun closeMouse() {
        mouseFd?.let(::destroy)
        mouseFd = null
    }

    @Synchronized
    fun closeAll() {
        gamepadFd?.let(::destroy)
        gamepadFd = null
        gamepadAttempted = false
        closeMouse()
    }
}
