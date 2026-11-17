package com.fastbrowser.gologin.network

import com.fastbrowser.gologin.model.ProxyProtocol
import com.fastbrowser.gologin.model.ProxyTransport
import com.fastbrowser.gologin.model.ServerNode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Embedded high-speed local HTTP CONNECT / HTTP Proxy bridge bound to 127.0.0.1.
 *
 * Why this exists:
 * 1. Android WebView ProxyController connects to 127.0.0.1:<localPort> with zero auth popups.
 * 2. Switching between Server 1 and Server 2 updates [activeServerRef] atomically in <1ms
 *    without restarting WebView or re-applying system proxy overrides.
 * 3. Handles upstream SOCKS5 (RFC 1928 + RFC 1929 User/Pass) and HTTP CONNECT with remote DNS
 *    resolution (zero local DNS leaks) and TCP_NODELAY enabled on both sockets. Servers with
 *    transport=WEBSOCKET first open a TLS + WebSocket connection (WebSocketUpstreamSocket)
 *    and run the same proxy protocol inside masked WS frames — the Cloudflare route.
 * 4. Enforces a strict Kill-Switch: if [killSwitchEnabled] is true and no server is active or
 *    the upstream server is unreachable, connections are immediately closed (never leaked).
 */
/** Last bridge failure, surfaced to the Diagnostics screen instead of a silent black page. */
data class BridgeError(
    val message: String,
    val serverName: String?,
    val atMillis: Long = System.currentTimeMillis()
)

class LocalProxyBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)

    private val activeServerRef = AtomicReference<ServerNode?>(null)
    private val killSwitchRef = AtomicBoolean(true)

    private val _lastError = MutableStateFlow<BridgeError?>(null)
    val lastError: StateFlow<BridgeError?> = _lastError.asStateFlow()

    private val _proxyOverrideApplied = MutableStateFlow(false)
    val proxyOverrideApplied: StateFlow<Boolean> = _proxyOverrideApplied.asStateFlow()

    fun markProxyOverrideApplied() {
        _proxyOverrideApplied.value = true
    }

    @Volatile
    var localPort: Int = 0
        private set

    fun start(): Int {
        if (isRunning.get()) return localPort
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", 0), 256)
        localPort = socket.localPort
        serverSocket = socket
        isRunning.set(true)

        scope.launch {
            while (isRunning.get() && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    client.tcpNoDelay = true
                    client.keepAlive = true
                    launch { handleClient(client) }
                } catch (_: Exception) {
                    if (!isRunning.get()) break
                }
            }
        }
        return localPort
    }

    fun updateUpstreamServer(server: ServerNode?, killSwitch: Boolean) {
        activeServerRef.set(server)
        killSwitchRef.set(killSwitch)
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        scope.cancel()
    }

    private suspend fun handleClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
        var upstreamSocket: Socket? = null
        try {
            clientSocket.soTimeout = 30_000
            val clientIn = BufferedInputStream(clientSocket.getInputStream(), 32768)
            val clientOut = clientSocket.getOutputStream()

            val requestLine = readLineAscii(clientIn) ?: return@withContext
            val parts = requestLine.split(" ")
            if (parts.size < 3) return@withContext

            val method = parts[0].uppercase()
            val target = parts[1]
            val httpVersion = parts[2]

            // Read remaining HTTP request headers from WebView
            val headers = mutableListOf<String>()
            while (true) {
                val line = readLineAscii(clientIn) ?: break
                if (line.isEmpty()) break
                headers.add(line)
            }

            val server = activeServerRef.get()
            if (server == null) {
                if (killSwitchRef.get()) {
                    sendHttpError(clientOut, 503, "Kill-Switch Active: No Proxy Server Selected")
                    return@withContext
                } else {
                    // Direct mode (only when Kill-Switch is explicitly disabled)
                    handleDirectConnection(method, target, httpVersion, headers, clientSocket, clientIn, clientOut)
                    return@withContext
                }
            }

            val (destHost, destPort) = parseHostPort(method, target, headers)
                ?: return@withContext

            upstreamSocket = try {
                connectToProxyServer(server, destHost, destPort)
            } catch (e: Exception) {
                // Auth/connect failure used to close the socket silently, leaving
                // WebView on a black page. Record it and answer the client with
                // a real 502 so the failure is visible instead of silent.
                val msg = e.message ?: "Proxy connection failed"
                _lastError.value = BridgeError(msg, server.name)
                try {
                    sendHttpError(clientOut, 502, "Proxy error (${server.name}): $msg")
                } catch (_: Exception) {
                }
                return@withContext
            }
            upstreamSocket.soTimeout = 60_000
            val upstreamIn = upstreamSocket.getInputStream()
            val upstreamOut = upstreamSocket.getOutputStream()

            if (method == "CONNECT") {
                // Acknowledge HTTPS tunnel to WebView
                clientOut.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: FastNode/1.0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                clientOut.flush()
            } else {
                // Plain HTTP request: rewrite absolute URI to relative path and forward
                val relativePath = extractRelativePath(target)
                val sb = StringBuilder()
                sb.append("$method $relativePath $httpVersion\r\n")
                for (h in headers) {
                    if (!h.startsWith("Proxy-Connection:", ignoreCase = true) &&
                        !h.startsWith("Proxy-Authorization:", ignoreCase = true)
                    ) {
                        sb.append(h).append("\r\n")
                    }
                }
                sb.append("\r\n")
                upstreamOut.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
                upstreamOut.flush()
            }

            // Bidirectional relay: when EITHER direction ends, tear the tunnel
            // down at once. Joining both pumps deadlocked: the server closes
            // its write side after a response while WebView never half-closes
            // the tunnel first, so the upload pump stayed blocked in read()
            // and every HTTPS page hung until the socket timeout.
            relayBothDirections(
                clientSocket, upstreamSocket,
                clientIn, clientOut, upstreamIn, upstreamOut
            )
        } catch (e: Exception) {
            // Connection reset / aborted by page navigation — routine, not an error.
            val msg = e.message.orEmpty()
            if (msg.isNotBlank() &&
                !msg.contains("reset", ignoreCase = true) &&
                !msg.contains("Broken pipe", ignoreCase = true)
            ) {
                _lastError.value = BridgeError("Bridge error: $msg", activeServerRef.get()?.name)
            }
        } finally {
            try { upstreamSocket?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Relays bytes in both directions until EITHER side ends, then closes
     * both sockets immediately so the other pump unblocks at once. Never
     * relies on the socket timeout for normal completion.
     */
    private suspend fun relayBothDirections(
        clientSocket: Socket,
        upstreamSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        upstreamIn: InputStream,
        upstreamOut: OutputStream
    ) {
        coroutineScope {
            val upJob = launch { pumpStream(clientIn, upstreamOut) }
            val downJob = launch { pumpStream(upstreamIn, clientOut) }
            // Wait for the FIRST direction to finish — not both.
            select<Unit> {
                upJob.onJoin {}
                downJob.onJoin {}
            }
            // Unblock the pump still stuck in read() before cancelling it:
            // blocking InputStream.read() does not react to cancellation.
            try { clientSocket.shutdownInput() } catch (_: Exception) {}
            try { clientSocket.shutdownOutput() } catch (_: Exception) {}
            try { upstreamSocket.shutdownInput() } catch (_: Exception) {}
            try { upstreamSocket.shutdownOutput() } catch (_: Exception) {}
            upJob.cancel()
            downJob.cancel()
            upJob.join()
            downJob.join()
        }
    }

    private fun connectToProxyServer(server: ServerNode, destHost: String, destPort: Int): Socket {
        val sock: Socket = if (server.transport == ProxyTransport.WEBSOCKET) {
            // WSS first (TLS + WS handshake), then the proxy protocol runs
            // inside WS frames — see connectWebSocketUpstream.
            connectWebSocketUpstream(server)
        } else {
            val plain = Socket()
            plain.tcpNoDelay = true
            plain.keepAlive = true
            plain.receiveBufferSize = 65536
            plain.sendBufferSize = 65536
            plain.connect(InetSocketAddress(server.host.trim(), server.port), 8_000)
            plain
        }

        when (server.protocol) {
            ProxyProtocol.SOCKS5 -> performSocks5Handshake(sock, server, destHost, destPort)
            ProxyProtocol.HTTP -> performHttpConnectHandshake(sock, server, destHost, destPort)
        }
        return sock
    }

    /**
     * WebSocket upstream (v1.0.3): TLS to host:port (standard verification,
     * SNI = host — the Cloudflare edge cert is a normal public cert, no
     * pinning), then the WS handshake on [ServerNode.wsPath]. The returned
     * socket's streams are WS frames, so the SOCKS5/HTTP handshake above
     * runs on top of it unchanged. Verified against Gost auto+wss behind
     * Cloudflare: 101 → SOCKS5 greeting → CONNECT → Sweden exit IP.
     */
    private fun connectWebSocketUpstream(server: ServerNode): Socket {
        val host = server.host.trim()
        val plain = Socket()
        plain.tcpNoDelay = true
        plain.keepAlive = true
        plain.receiveBufferSize = 65536
        plain.sendBufferSize = 65536
        try {
            plain.connect(InetSocketAddress(host, server.port), 8_000)
            plain.soTimeout = 10_000
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(plain, host, server.port, true) as SSLSocket
            tls.tcpNoDelay = true
            tls.startHandshake()

            // One BufferedInputStream for the handshake read AND the frame
            // reader: a second buffer would swallow bytes read ahead past
            // the header block (the first WS frame can already be in it).
            val rawIn = BufferedInputStream(tls.getInputStream(), 8192)
            val rawOut = tls.getOutputStream()

            val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val key = Base64.getEncoder().encodeToString(keyBytes)
            val path = server.wsPath.ifBlank { "/ws" }
            val req = StringBuilder()
            req.append("GET $path HTTP/1.1\r\n")
            req.append("Host: $host:${server.port}\r\n")
            req.append("Upgrade: websocket\r\n")
            req.append("Connection: Upgrade\r\n")
            req.append("Sec-WebSocket-Key: $key\r\n")
            req.append("Sec-WebSocket-Version: 13\r\n")
            req.append("\r\n")
            rawOut.write(req.toString().toByteArray(StandardCharsets.US_ASCII))
            rawOut.flush()

            val statusLine = readLineAscii(rawIn)
                ?: throw IllegalStateException("WS handshake failed: empty response")
            if (!statusLine.contains(" 101 ")) {
                throw IllegalStateException("WS handshake failed: $statusLine")
            }
            var accept: String? = null
            while (true) {
                val line = readLineAscii(rawIn) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Sec-WebSocket-Accept:", ignoreCase = true)) {
                    accept = line.substringAfter(":").trim()
                }
            }
            val expected = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest("$key$WS_GUID".toByteArray(StandardCharsets.US_ASCII))
            )
            if (accept == null || accept != expected) {
                throw IllegalStateException("WS handshake failed: bad Sec-WebSocket-Accept")
            }
            return WebSocketUpstreamSocket(tls, rawIn, rawOut)
        } catch (e: Exception) {
            try {
                plain.close()
            } catch (_: Exception) {
            }
            if (e is IllegalStateException && e.message?.startsWith("WS handshake failed") == true) throw e
            throw IllegalStateException(
                "WS handshake failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}", e
            )
        }
    }

    private companion object {
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }

    /**
     * RFC 1928 + RFC 1929 SOCKS5 Handshake with ATYP=0x03 (Remote DNS resolution on server).
     */
    private fun performSocks5Handshake(sock: Socket, server: ServerNode, destHost: String, destPort: Int) {
        val out = sock.getOutputStream()
        val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
        // No-auth when BOTH fields are blank (v1.0.2 test build: the servers run
        // without credentials) — the greeting then offers only method 0x00 and no
        // sub-negotiation happens. Nothing here forces auth on empty credentials.
        val hasAuth = server.username.isNotEmpty() || server.password.isNotEmpty()

        // Greeting
        if (hasAuth) {
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            out.write(byteArrayOf(0x05, 0x01, 0x00))
        }
        out.flush()

        val ver = input.readUnsignedByte()
        val method = input.readUnsignedByte()
        if (ver != 0x05 || method == 0xFF) {
            throw IllegalStateException("SOCKS5 greeting rejected")
        }

        // Username/Password sub-negotiation (RFC 1929)
        if (method == 0x02) {
            val userBytes = server.username.toByteArray(StandardCharsets.UTF_8)
            val passBytes = server.password.toByteArray(StandardCharsets.UTF_8)
            val authPacket = ByteArray(3 + userBytes.size + passBytes.size)
            authPacket[0] = 0x01
            authPacket[1] = userBytes.size.toByte()
            System.arraycopy(userBytes, 0, authPacket, 2, userBytes.size)
            authPacket[2 + userBytes.size] = passBytes.size.toByte()
            System.arraycopy(passBytes, 0, authPacket, 3 + userBytes.size, passBytes.size)
            out.write(authPacket)
            out.flush()

            val authVer = input.readUnsignedByte()
            val authStatus = input.readUnsignedByte()
            if (authVer != 0x01 || authStatus != 0x00) {
                throw IllegalStateException("SOCKS5 authentication failed")
            }
        }

        // CONNECT command with Domain Name (0x03) so Server resolves DNS
        val hostBytes = destHost.toByteArray(StandardCharsets.US_ASCII)
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05 // VER
        req[1] = 0x01 // CMD: CONNECT
        req[2] = 0x00 // RSV
        req[3] = 0x03 // ATYP: DOMAINNAME
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = ((destPort shr 8) and 0xFF).toByte()
        req[6 + hostBytes.size] = (destPort and 0xFF).toByte()
        out.write(req)
        out.flush()

        val repVer = input.readUnsignedByte()
        val repStatus = input.readUnsignedByte()
        input.readUnsignedByte() // RSV
        val atyp = input.readUnsignedByte()
        if (repVer != 0x05 || repStatus != 0x00) {
            throw IllegalStateException("SOCKS5 CONNECT failed with code: $repStatus")
        }

        // Consume bound address + port
        when (atyp) {
            0x01 -> input.readFully(ByteArray(4 + 2))
            0x03 -> {
                val len = input.readUnsignedByte()
                input.readFully(ByteArray(len + 2))
            }
            0x04 -> input.readFully(ByteArray(16 + 2))
        }
    }

    private fun performHttpConnectHandshake(sock: Socket, server: ServerNode, destHost: String, destPort: Int) {
        val out = sock.getOutputStream()
        val input = BufferedInputStream(sock.getInputStream())
        val sb = StringBuilder()
        sb.append("CONNECT $destHost:$destPort HTTP/1.1\r\n")
        sb.append("Host: $destHost:$destPort\r\n")
        if (server.username.isNotEmpty() || server.password.isNotEmpty()) {
            val creds = "${server.username}:${server.password}".toByteArray(StandardCharsets.UTF_8)
            val b64 = Base64.getEncoder().encodeToString(creds)
            sb.append("Proxy-Authorization: Basic $b64\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
        out.flush()

        val statusLine = readLineAscii(input) ?: throw IllegalStateException("Empty HTTP proxy response")
        if (!statusLine.contains(" 200 ")) {
            throw IllegalStateException("HTTP proxy rejected CONNECT: $statusLine")
        }
        while (true) {
            val line = readLineAscii(input) ?: break
            if (line.isEmpty()) break
        }
    }

    private suspend fun handleDirectConnection(
        method: String,
        target: String,
        httpVersion: String,
        headers: List<String>,
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream
    ) {
        val (destHost, destPort) = parseHostPort(method, target, headers) ?: return
        val directSock = Socket()
        directSock.tcpNoDelay = true
        directSock.connect(InetSocketAddress(destHost, destPort), 8_000)
        try {
            val upIn = directSock.getInputStream()
            val upOut = directSock.getOutputStream()
            if (method == "CONNECT") {
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                clientOut.flush()
            } else {
                val rel = extractRelativePath(target)
                val sb = StringBuilder("$method $rel $httpVersion\r\n")
                headers.forEach { sb.append(it).append("\r\n") }
                sb.append("\r\n")
                upOut.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
                upOut.flush()
            }
            // Same first-direction-ends teardown as the proxied path: joining
            // both pumps deadlocks when the peer never half-closes first.
            relayBothDirections(
                clientSocket, directSock,
                clientIn, clientOut, upIn, upOut
            )
        } finally {
            directSock.close()
        }
    }

    private fun pumpStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(32768)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun readLineAscii(input: InputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (true) {
            val b = input.read()
            if (b == -1) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
            if (b == '\n'.code) {
                if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.removeAt(bytes.size - 1)
                }
                return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
            }
            bytes.add(b.toByte())
        }
    }

    private fun parseHostPort(method: String, target: String, headers: List<String>): Pair<String, Int>? {
        if (method == "CONNECT") {
            val idx = target.lastIndexOf(':')
            if (idx <= 0) return null
            val host = target.substring(0, idx).removePrefix("[").removeSuffix("]")
            val port = target.substring(idx + 1).toIntOrNull() ?: 443
            return host to port
        }
        val hostHeader = headers.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
        val raw = hostHeader ?: target.substringAfter("://").substringBefore("/")
        val idx = raw.lastIndexOf(':')
        return if (idx > 0) {
            raw.substring(0, idx) to (raw.substring(idx + 1).toIntOrNull() ?: 80)
        } else {
            raw to 80
        }
    }

    private fun extractRelativePath(target: String): String {
        if (!target.startsWith("http://", ignoreCase = true) && !target.startsWith("https://", ignoreCase = true)) {
            return target
        }
        val withoutScheme = target.substringAfter("://")
        val slashIdx = withoutScheme.indexOf('/')
        return if (slashIdx == -1) "/" else withoutScheme.substring(slashIdx)
    }

    private fun sendHttpError(out: OutputStream, code: Int, message: String) {
        val body = "<html><body style='background:#0a0a0a;color:#ff5555;font-family:sans-serif;padding:24px;'><h2>$code - $message</h2></body></html>"
        val resp = "HTTP/1.1 $code $message\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        try {
            out.write(resp.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        } catch (_: Exception) {
        }
    }
}
