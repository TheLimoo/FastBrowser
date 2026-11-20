// Latency monitor — Dart port of LatencyMonitor.kt.
//
// 8 s timeout (was 3 s in v1.0.1 — too short for a ~1130 ms link with
// jitter), one retry after 400 ms. WebSocket servers get a real TLS + WS
// handshake check (a successful `WebSocket.connect` proves the whole
// Cloudflare → Gost path answers, not just the port) instead of a bare
// TCP connect. A failed check returns isOnline=false with rttMs null;
// the app state keeps the last-known latency and shows it as "stale" in
// amber instead of flipping the server to "offline" on one failed attempt.

import 'dart:async';
import 'dart:io';

import 'models.dart';

class ServerLatencyResult {
  final String serverId;
  final int? rttMs;
  final bool isOnline;
  final bool stale;

  const ServerLatencyResult(
    this.serverId,
    this.rttMs,
    this.isOnline, {
    this.stale = false,
  });

  ServerLatencyResult copyWith({int? rttMs, bool? isOnline, bool? stale}) =>
      ServerLatencyResult(
        serverId,
        rttMs ?? this.rttMs,
        isOnline ?? this.isOnline,
        stale: stale ?? this.stale,
      );
}

class LatencyMonitor {
  static const Duration _timeout = Duration(seconds: 8);

  static Future<ServerLatencyResult> pingServer(ServerNode server) async {
    for (var attempt = 0; attempt < 2; attempt++) {
      try {
        final rttMs = server.transport == ProxyTransport.WEBSOCKET
            ? await _pingWebSocket(server)
            : await _pingTcp(server);
        return ServerLatencyResult(server.id, rttMs, true);
      } catch (_) {
        if (attempt == 0) {
          await Future.delayed(const Duration(milliseconds: 400));
        }
      }
    }
    return ServerLatencyResult(server.id, null, false);
  }

  static Future<Map<String, ServerLatencyResult>> pingAll(
    List<ServerNode> servers,
  ) async {
    final results = await Future.wait(servers.map(pingServer));
    return {for (final r in results) r.serverId: r};
  }

  static Future<int> _pingTcp(ServerNode server) async {
    final stopwatch = Stopwatch()..start();
    final socket = await Socket.connect(
      server.host.trim(),
      server.port,
      timeout: _timeout,
    );
    try {
      socket.setOption(SocketOption.tcpNoDelay, true);
    } catch (_) {}
    stopwatch.stop();
    await socket.close();
    return stopwatch.elapsedMilliseconds.clamp(1, 1 << 62);
  }

  /// A successful `WebSocket.connect` IS the check: TLS handshake +
  /// WS Upgrade returning 101. Only the handshake is measured — no SOCKS
  /// traffic is sent, so this is a lightweight check, not a browse.
  static Future<int> _pingWebSocket(ServerNode server) async {
    final host = server.host.trim();
    final path = server.wsPath.isEmpty ? '/ws' : server.wsPath;
    final stopwatch = Stopwatch()..start();
    final ws = await WebSocket.connect('wss://$host:${server.port}$path')
        .timeout(_timeout);
    stopwatch.stop();
    try {
      await ws.close();
    } catch (_) {}
    return stopwatch.elapsedMilliseconds.clamp(1, 1 << 62);
  }
}
