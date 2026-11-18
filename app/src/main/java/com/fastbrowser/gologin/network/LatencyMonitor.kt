package com.fastbrowser.gologin.network

import com.fastbrowser.gologin.model.ProxyTransport
import com.fastbrowser.gologin.model.ServerNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.system.measureNanoTime

data class ServerLatencyResult(
    val serverId: String,
    val rttMs: Long?,
    val isOnline: Boolean,
    /**
     * v1.0.5: true when the last fresh ping failed but an older latency is
     * still shown. The UI keeps the old number in amber instead of flipping
     * the server to "offline" on one failed attempt.
     */
    val stale: Boolean = false
)

object LatencyMonitor {

    /**
     * Measures real round-trip latency (in milliseconds) to the server.
     * v1.0.5: 8 s timeout (was 3 s — too short for a ~1130 ms link with
     * jitter: DNS + TCP connect alone can exceed 3 s on a slow mobile
     * link, which is why all three servers read "offline" at 21:28 while
     * every route was demonstrably healthy), one retry on failure.
     * WebSocket servers get a real TLS + WS-handshake check (expect 101)
     * instead of a bare TCP connect — that proves the whole
     * Cloudflare → Gost path answers, not just the port.
     */
    suspend fun pingServer(server: ServerNode, timeoutMs: Int = 8000): ServerLatencyResult =
        withContext(Dispatchers.IO) {
            repeat(2) { attempt ->
                try {
                    val rttMs = if (server.transport == ProxyTransport.WEBSOCKET) {
                        pingWebSocket(server, timeoutMs)
                    } else {
                        pingTcp(server, timeoutMs)
                    }
                    return@withContext ServerLatencyResult(server.id, rttMs, isOnline = true)
                } catch (_: Exception) {
                    if (attempt == 0) delay(400)
                }
            }
            ServerLatencyResult(server.id, rttMs = null, isOnline = false)
        }

    suspend fun pingAll(servers: List<ServerNode>): Map<String, ServerLatencyResult> =
        withContext(Dispatchers.IO) {
            servers.map { server ->
                async { pingServer(server) }
            }.awaitAll().associateBy { it.serverId }
        }

    private fun pingTcp(server: ServerNode, timeoutMs: Int): Long {
        val sock = Socket()
        sock.tcpNoDelay = true
        try {
            val nanos = measureNanoTime {
                sock.connect(InetSocketAddress(server.host.trim(), server.port), timeoutMs)
            }
            return (nanos / 1_000_000L).coerceAtLeast(1L)
        } finally {
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    /** TLS + WS Upgrade handshake to the server's wsPath; a 101 proves the
     *  full proxy path answers. Only the handshake is measured — no SOCKS
     *  traffic is sent, so this is a lightweight check, not a browse. */
    private fun pingWebSocket(server: ServerNode, timeoutMs: Int): Long {
        val host = server.host.trim()
        val plain = Socket()
        plain.tcpNoDelay = true
        try {
            val rttNanos = measureNanoTime {
                plain.connect(InetSocketAddress(host, server.port), timeoutMs)
                plain.soTimeout = timeoutMs
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(plain, host, server.port, true) as SSLSocket
                tls.soTimeout = timeoutMs
                try {
                    tls.startHandshake()
                    val key = Base64.getEncoder().encodeToString(
                        ByteArray(16).also { SecureRandom().nextBytes(it) }
                    )
                    val path = server.wsPath.ifBlank { "/ws" }
                    val req = "GET $path HTTP/1.1\r\n" +
                        "Host: $host:${server.port}\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\n" +
                        "Sec-WebSocket-Version: 13\r\n\r\n"
                    tls.getOutputStream().write(req.toByteArray(StandardCharsets.US_ASCII))
                    tls.getOutputStream().flush()
                    val rawIn = BufferedInputStream(tls.getInputStream(), 4096)
                    val status = readLineAscii(rawIn)
                        ?: throw IOException("empty WS handshake response")
                    if (!status.contains(" 101 ")) {
                        throw IOException("WS handshake failed: $status")
                    }
                } finally {
                    try {
                        tls.close()
                    } catch (_: Exception) {
                    }
                }
            }
            return (rttNanos / 1_000_000L).coerceAtLeast(1L)
        } finally {
            try {
                plain.close()
            } catch (_: Exception) {
            }
        }
    }

    /** One ASCII line, CRLF-terminated (local copy — the bridge's is private). */
    private fun readLineAscii(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (true) {
            val b = input.read()
            if (b == -1) return if (bytes.isEmpty()) null
                else String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
            if (b == '\n'.code) {
                if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.removeAt(bytes.size - 1)
                }
                return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
            }
            bytes.add(b.toByte())
        }
    }
}
