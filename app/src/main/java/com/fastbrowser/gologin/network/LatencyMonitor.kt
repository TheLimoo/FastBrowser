package com.fastbrowser.gologin.network

import com.fastbrowser.gologin.model.ServerNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureNanoTime

data class ServerLatencyResult(
    val serverId: String,
    val rttMs: Long?,
    val isOnline: Boolean
)

object LatencyMonitor {

    /**
     * Measures real TCP SYN-ACK round-trip latency (in milliseconds) to the server.
     * Much more accurate for proxy browsing speed than ICMP ping.
     */
    suspend fun pingServer(server: ServerNode, timeoutMs: Int = 3000): ServerLatencyResult =
        withContext(Dispatchers.IO) {
            try {
                var rttMs = -1L
                val sock = Socket()
                sock.tcpNoDelay = true
                val nanos = measureNanoTime {
                    sock.connect(InetSocketAddress(server.host.trim(), server.port), timeoutMs)
                }
                sock.close()
                rttMs = (nanos / 1_000_000L).coerceAtLeast(1L)
                ServerLatencyResult(server.id, rttMs, isOnline = true)
            } catch (_: Exception) {
                ServerLatencyResult(server.id, rttMs = null, isOnline = false)
            }
        }

    suspend fun pingAll(servers: List<ServerNode>): Map<String, ServerLatencyResult> =
        withContext(Dispatchers.IO) {
            servers.map { server ->
                async { pingServer(server) }
            }.awaitAll().associateBy { it.serverId }
        }
}
