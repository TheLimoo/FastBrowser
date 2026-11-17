package com.fastbrowser.gologin.network

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom

/**
 * WebSocket (RFC 6455) upstream transport for the local proxy bridge.
 *
 * Why: direct TCP to the Sweden proxy (10800/10801) is DPI-dropped from
 * Iran — the TCP connection opens but the SOCKS5 handshake bytes never get
 * through. The same server behind Cloudflare (WSS on 2083) works, because
 * it looks like ordinary HTTPS. This file makes a WebSocket connection
 * behave like a plain Socket so the existing SOCKS5/HTTP handshake and
 * relay code runs on top of it unchanged.
 *
 * Rules honoured here:
 * - Client frames are ALWAYS masked (RFC 6455 §5.3), fresh random mask per frame.
 * - Server frames are unmasked; a masked server frame is still unmasked correctly.
 * - Fragmented messages are re-assembled transparently (the reader is a
 *   continuous byte stream; continuation frames just keep feeding it).
 * - Ping frames are answered with a pong carrying the same payload; pong
 *   frames are ignored; a close frame is echoed and then reads return EOF.
 * - The handshake HTTP bytes are read from the SAME BufferedInputStream
 *   the frame reader later uses — a second buffer would swallow bytes
 *   already read ahead past the header block.
 */

private val wsRandom = SecureRandom()

/** Builds one WS frame header (FIN set) for [opcode] and [payloadLen]. */
private fun wsFrameHeader(opcode: Int, payloadLen: Long, masked: Boolean): ByteArray {
    val maskBit = if (masked) 0x80 else 0x00
    return when {
        payloadLen <= 125 -> byteArrayOf(
            (0x80 or opcode).toByte(),
            (maskBit or payloadLen.toInt()).toByte()
        )
        payloadLen <= 65535 -> byteArrayOf(
            (0x80 or opcode).toByte(),
            (maskBit or 126).toByte(),
            (payloadLen shr 8).toByte(),
            payloadLen.toByte()
        )
        else -> byteArrayOf(
            (0x80 or opcode).toByte(),
            (maskBit or 127).toByte(),
            0, 0, 0, 0,
            (payloadLen shr 24).toByte(),
            (payloadLen shr 16).toByte(),
            (payloadLen shr 8).toByte(),
            payloadLen.toByte()
        )
    }
}

/**
 * Writes one masked client frame. [writeLock] serialises all writes to
 * [rawOut]: the relay pump and the ping/pong replies from the reader thread
 * share the same underlying stream.
 */
private fun writeWsFrame(
    rawOut: OutputStream,
    writeLock: Any,
    opcode: Int,
    payload: ByteArray,
    off: Int = 0,
    len: Int = payload.size
) {
    val mask = ByteArray(4).also { wsRandom.nextBytes(it) }
    val header = wsFrameHeader(opcode, len.toLong(), masked = true)
    val masked = ByteArray(len)
    for (i in 0 until len) {
        masked[i] = (payload[off + i].toInt() xor mask[i and 3].toInt()).toByte()
    }
    synchronized(writeLock) {
        rawOut.write(header)
        rawOut.write(mask)
        rawOut.write(masked)
        rawOut.flush()
    }
}

/** InputStream that decodes WS frames into a plain byte stream. */
class WebSocketInputStream(
    private val rawIn: BufferedInputStream,
    private val rawOut: OutputStream,
    private val writeLock: Any
) : InputStream() {

    private val frameBuf = ByteArray(65536)
    private var frameBufPos = 0
    private var frameBufLen = 0
    /** Bytes of the current data frame's payload not yet pulled into frameBuf. */
    private var frameRemaining = 0L
    private var frameMask: ByteArray? = null
    private var frameMaskPos = 0
    private var eof = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (eof) return -1
        if (len == 0) return 0
        if (frameBufPos < frameBufLen) {
            val n = minOf(len, frameBufLen - frameBufPos)
            System.arraycopy(frameBuf, frameBufPos, b, off, n)
            frameBufPos += n
            return n
        }
        if (!fillNextChunk()) return -1
        return read(b, off, len)
    }

    override fun available(): Int = frameBufLen - frameBufPos

    /**
     * Pulls the next chunk of data-frame payload into frameBuf, processing
     * control frames (ping/pong/close) on the way. Returns false on EOF.
     */
    private fun fillNextChunk(): Boolean {
        while (true) {
            if (eof) return false
            if (frameRemaining > 0) {
                val want = minOf(frameRemaining, frameBuf.size.toLong()).toInt()
                readFully(frameBuf, 0, want)
                val mask = frameMask
                if (mask != null) {
                    for (i in 0 until want) {
                        frameBuf[i] = (frameBuf[i].toInt() xor mask[frameMaskPos].toInt()).toByte()
                        frameMaskPos = (frameMaskPos + 1) and 3
                    }
                }
                frameBufPos = 0
                frameBufLen = want
                frameRemaining -= want
                return true
            }
            // Parse the next frame header.
            val b0 = rawIn.read()
            if (b0 == -1) {
                eof = true
                return false
            }
            val b1 = rawIn.read()
            if (b1 == -1) {
                eof = true
                return false
            }
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var payloadLen = (b1 and 0x7F).toLong()
            if (payloadLen == 126L) {
                payloadLen = readUInt16().toLong()
            } else if (payloadLen == 127L) {
                payloadLen = readUInt64()
                if (payloadLen < 0 || payloadLen > Int.MAX_VALUE) {
                    throw IOException("WS frame too large")
                }
            }
            var mask: ByteArray? = null
            if (masked) {
                mask = ByteArray(4)
                readFully(mask, 0, 4)
            }
            when (opcode) {
                0x8 -> { // Close: echo it, then EOF.
                    skipFully(payloadLen)
                    try {
                        writeWsFrame(rawOut, writeLock, 0x8, ByteArray(0))
                    } catch (_: Exception) {
                    }
                    eof = true
                    return false
                }
                0x9 -> { // Ping: reply pong with the same payload.
                    val payload = ByteArray(payloadLen.toInt())
                    if (payload.isNotEmpty()) readFully(payload, 0, payload.size)
                    if (mask != null) {
                        for (i in payload.indices) {
                            payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                        }
                    }
                    try {
                        writeWsFrame(rawOut, writeLock, 0xA, payload)
                    } catch (_: Exception) {
                    }
                }
                0xA -> skipFully(payloadLen) // Pong: ignore.
                0x0, 0x1, 0x2 -> { // Data / continuation: stream the payload.
                    frameRemaining = payloadLen
                    frameMask = mask
                    frameMaskPos = 0
                }
                else -> skipFully(payloadLen) // Unknown opcode: ignore.
            }
        }
    }

    private fun readFully(buf: ByteArray, off: Int, len: Int) {
        var read = 0
        while (read < len) {
            val n = rawIn.read(buf, off + read, len - read)
            if (n == -1) throw EOFException("WS stream ended mid-frame")
            read += n
        }
    }

    private fun skipFully(n: Long) {
        var remaining = n
        val scratch = ByteArray(4096)
        while (remaining > 0) {
            val want = minOf(remaining, scratch.size.toLong()).toInt()
            val got = rawIn.read(scratch, 0, want)
            if (got == -1) throw EOFException("WS stream ended mid-frame")
            remaining -= got
        }
    }

    private fun readUInt16(): Int {
        val hi = rawIn.read()
        val lo = rawIn.read()
        if (hi == -1 || lo == -1) throw EOFException("WS stream ended mid-header")
        return (hi shl 8) or lo
    }

    private fun readUInt64(): Long {
        var v = 0L
        repeat(8) {
            val b = rawIn.read()
            if (b == -1) throw EOFException("WS stream ended mid-header")
            v = (v shl 8) or b.toLong()
        }
        return v
    }
}

/** OutputStream that sends every write as one masked binary WS frame. */
class WebSocketOutputStream(
    private val rawOut: OutputStream,
    private val writeLock: Any
) : OutputStream() {

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        writeWsFrame(rawOut, writeLock, 0x2, b, off, len)
    }

    override fun flush() {
        synchronized(writeLock) { rawOut.flush() }
    }

    /** Best-effort WS close frame (empty payload, masked). */
    fun sendCloseFrame() {
        writeWsFrame(rawOut, writeLock, 0x8, ByteArray(0))
    }
}

/**
 * A java.net.Socket façade over an established WebSocket connection, so the
 * bridge's SOCKS5/HTTP handshake and relay code runs on it unchanged.
 * All socket operations delegate to the inner (TLS) socket; the streams
 * are the WS frame encoder/decoder above, cached so repeated
 * getInputStream()/getOutputStream() calls return the same instances
 * (the SOCKS5 handshake wraps getInputStream() in its own buffer — a
 * fresh decoder each time would lose buffered frame state).
 */
class WebSocketUpstreamSocket(
    private val inner: Socket,
    rawIn: BufferedInputStream,
    rawOut: OutputStream
) : Socket() {

    private val writeLock = Any()
    private val wsIn = WebSocketInputStream(rawIn, rawOut, writeLock)
    private val wsOut = WebSocketOutputStream(rawOut, writeLock)

    @Volatile
    private var closed = false

    override fun getInputStream(): InputStream = wsIn
    override fun getOutputStream(): OutputStream = wsOut

    override fun setSoTimeout(timeout: Int) {
        inner.soTimeout = timeout
    }

    override fun getSoTimeout(): Int = inner.soTimeout

    override fun shutdownInput() {
        try {
            inner.shutdownInput()
        } catch (_: Exception) {
        }
    }

    override fun shutdownOutput() {
        try {
            inner.shutdownOutput()
        } catch (_: Exception) {
        }
    }

    override fun isClosed(): Boolean = closed || inner.isClosed
    override fun isConnected(): Boolean = inner.isConnected

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            wsOut.sendCloseFrame()
        } catch (_: Exception) {
        }
        try {
            inner.close()
        } catch (_: Exception) {
        }
    }
}
