package com.thecaticorn01.ircontroller.blaster

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.thecaticorn01.ircontroller.core.elksmart.BulkTransport
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * The dongle's bulk OUT/IN endpoint pair, opened with Android's USB host API.
 * ELK Smart dongles report a communications-class interface; claiming it with
 * `force = true` detaches any kernel driver so the app can talk to it directly.
 */
class UsbBulkTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    val outEndpoint: UsbEndpoint,
    val inEndpoint: UsbEndpoint,
) : BulkTransport {

    private val readBuffer = ByteArray(max(64, inEndpoint.maxPacketSize))
    @Volatile private var closed = false

    val endpointDescription: String
        get() = "Interface %d · bulk OUT 0x%02X · IN 0x%02X".format(usbInterface.id, outEndpoint.address, inEndpoint.address)

    override fun write(bytes: List<Int>, timeoutMillis: Int) {
        if (closed) throw IOException("The IR blaster connection is closed.")
        val data = ByteArray(bytes.size) { bytes[it].toByte() }
        val written = connection.bulkTransfer(outEndpoint, data, data.size, max(1, timeoutMillis))
        if (written < data.size) throw IOException("Writing to the IR blaster failed ($written of ${data.size} bytes).")
    }

    override fun read(maxLength: Int, timeoutMillis: Int): List<Int>? {
        if (closed) throw IOException("The IR blaster connection is closed.")
        val length = min(maxLength, readBuffer.size)
        // bulkTransfer returns a negative value both on timeout and on error.
        val count = connection.bulkTransfer(inEndpoint, readBuffer, length, max(1, timeoutMillis))
        if (count <= 0) return null
        return List(count) { readBuffer[it].toInt() and 0xFF }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    companion object {
        /** Finds the interface with a bulk OUT and a bulk IN endpoint and claims it. */
        fun open(manager: UsbManager, device: UsbDevice): UsbBulkTransport {
            val candidate = (0 until device.interfaceCount).asSequence()
                .map { device.getInterface(it) }
                .mapNotNull { iface ->
                    val endpoints = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                        .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                    val out = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
                    val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
                    if (out != null && input != null) Triple(iface, out, input) else null
                }
                .firstOrNull()
                ?: throw IOException("The IR blaster does not expose the expected USB endpoints.")
            val connection = manager.openDevice(device)
                ?: throw IOException("The IR blaster couldn’t be opened. Unplug it, plug it back in and allow access when asked.")
            val (iface, out, input) = candidate
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                throw IOException("The IR blaster is in use by another app. Close it, then reconnect the blaster.")
            }
            return UsbBulkTransport(connection, iface, out, input)
        }

        /** Lists interfaces and endpoints for the diagnostic report. */
        fun describe(device: UsbDevice): String = buildString {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                append("  - interface ${iface.id} class 0x%02X/0x%02X/0x%02X".format(iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol))
                append(", ${iface.endpointCount} endpoint(s)\n")
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
                        else -> "control"
                    }
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    append("    · 0x%02X %s %s, max packet %d\n".format(ep.address, type, dir, ep.maxPacketSize))
                }
            }
        }
    }
}
