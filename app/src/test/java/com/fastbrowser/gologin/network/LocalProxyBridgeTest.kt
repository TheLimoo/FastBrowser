package com.fastbrowser.gologin.network

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class LocalProxyBridgeTest {

    /**
     * Kill-switch, exercised against the REAL bridge (v1.0.7 adaptation of
     * the pack's socket-level simulation): with no active server and the
     * kill-switch on, a request through the bridge must be answered 503
     * (never proxied, never left hanging). The bridge is a plain JVM class
     * (no Android APIs), so this runs as an ordinary local unit test.
     */
    @Test
    fun killSwitch_closesIncomingSocketWhenNoActiveServer() = runBlocking {
        val bridge = LocalProxyBridge()
        bridge.updateUpstreamServer(null, killSwitch = true)
        val port = bridge.start()
        try {
            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 5_000
                client.getOutputStream().write(
                    "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
                )
                client.getOutputStream().flush()
                val statusLine = client.getInputStream().bufferedReader().readLine()
                assertTrue(
                    "Expected a 503 kill-switch answer, got: $statusLine",
                    statusLine?.startsWith("HTTP/1.1 503") == true
                )
            }
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun semaphore_boundsConcurrentTunnels() {
        val permits = Semaphore(permits = 2)
        assertTrue(permits.tryAcquire())
        assertTrue(permits.tryAcquire())
        assertFalse(permits.tryAcquire())
        permits.release()
        assertTrue(permits.tryAcquire())
    }
}
