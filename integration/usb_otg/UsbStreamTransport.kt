// SCAFFOLD (not compiled here). Android USB-OTG client transport for HELIX.
//
// The smartphone connects to the PC as a USB accessory (AOA) or usb-serial. This implements
// the HELIX Transport contract natively over that byte stream, using the SAME length-prefixed
// framing as the Python side (helix/transport/framing.py): `uint32 big-endian length || frame`,
// with the length checked BEFORE allocation. The decoded frame bytes go up to the HELIX codec
// (Python via Chaquopy, or a native port). Point-to-point: one remote (the PC).

package com.example.helix.usb

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

class UsbStreamTransport(
    private val input: InputStream,     // AOA/usb-serial read side (e.g. accessory FD)
    private val output: OutputStream,   // write side
    private val nodeId: String,
    private val maxFrame: Int = 16 * 1024 * 1024,
) {
    private var onFrame: ((ByteArray) -> Unit)? = null
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    fun onFrame(handler: (ByteArray) -> Unit) { onFrame = handler }

    fun start() {
        running = true
        // Handshake: send our node id first (transport-level addressing), then read frames.
        writeFrame(nodeId.toByteArray(Charsets.UTF_8))
        io.execute { readLoop() }
    }

    fun send(frame: ByteArray) {          // point-to-point: one remote (PC)
        if (frame.size <= maxFrame) writeFrame(frame)
    }

    private fun writeFrame(frame: ByteArray) {
        synchronized(output) {
            val len = frame.size
            output.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(),
                                     (len ushr 8).toByte(), len.toByte()))
            output.write(frame)
            output.flush()
        }
    }

    private fun readLoop() {
        val din = DataInputStream(input)
        try {
            // first framed chunk = remote (PC) node id — read and ignore beyond addressing
            skipFrame(din)
            while (running) {
                val len = din.readInt()                 // uint32 BE length
                if (len <= 0 || len > maxFrame) break    // reject BEFORE allocating
                val buf = ByteArray(len)
                din.readFully(buf)
                onFrame?.invoke(buf)                     // identity is authenticated inside
            }
        } catch (_: Exception) { /* stream closed */ }
    }

    private fun skipFrame(din: DataInputStream) {
        val len = din.readInt()
        if (len in 1..1024) din.readFully(ByteArray(len))
    }

    fun stop() { running = false; io.shutdownNow() }
}
