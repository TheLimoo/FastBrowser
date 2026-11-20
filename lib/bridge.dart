// Local proxy bridge — Dart port of LocalProxyBridge.kt.
//
// Bound to 127.0.0.1. The WebView talks to this bridge (via the Android
// proxy override); the bridge routes to the selected upstream server and
// hot-swaps it atomically. Handles HTTP CONNECT / plain HTTP from the
// WebView, SOCKS5 (RFC 1928 + RFC 1929 user/pass, remote DNS) and HTTP
// CONNECT upstream, and WebSocket upstreams (TLS + WS handshake, then the
// proxy protocol inside WS frames — the Cloudflare route).
//
// Rules kept from the Kotlin version:
// - Kill-switch: no server + kill-switch on → 503, never a leak. Direct
//   mode only when the kill-switch is explicitly off.
// - relayBothDirections: when EITHER direction ends, tear the tunnel down
//   at once. Joining both pumps deadlocked (v1.0.1): the server closes its
//   write side after a response while the WebView never half-closes first,
//   so the upload pump stayed blocked in read() and every HTTPS page hung
//   until the socket timeout.
// - Semaphore(64): at most 64 client handlers at once (v1.0.7).
// - Timeouts: 30s read, 10s connect.
// - Upstream failures answer the client with a real 502 (never a silent
//   black page) and are recorded in lastError for Diagnostics.

import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:io';

import 'models.dart';
import 'ws_stream.dart';

class BridgeError {
  final String message;
  final String? serverName;
  final int atMillis;
  BridgeError(this.message, this.serverName)
      : atMillis = DateTime.now().millisecondsSinceEpoch;
}

class _Semaphore {
  int _permits;
  final Queue<Completer<void>> _waiters = Queue<Completer<void>>();

  _Semaphore(this._permits);

  Future<void> acquire() {
    if (_permits > 0) {
      _permits--;
      return Future.value();
    }
    final c = Completer<void>();
    _waiters.add(c);
    return c.future;
  }

  void release() {
    if (_waiters.isNotEmpty) {
      _waiters.removeFirst().complete();
    } else {
      _permits++;
    }
  }
}

class LocalProxyBridge {
  static const int _maxTunnels = 64;
  static const Duration _readTimeout = Duration(seconds: 30);
  static const Duration _connectTimeout = Duration(seconds: 10);

  ServerSocket? _serverSocket;
  bool _running = false;
  int localPort = 0;

  ServerNode? _activeServer;
  bool _killSwitch = true;

  final _Semaphore _permits = _Semaphore(_maxTunnels);

  final StreamController<BridgeError?> _lastErrorController =
      StreamController<BridgeError?>.broadcast();
  BridgeError? _lastError;
  Stream<BridgeError?> get lastErrorStream => _lastErrorController.stream;
  BridgeError? get lastError => _lastError;

  void _recordError(String message, String? serverName) {
    _lastError = BridgeError(message, serverName);
    _lastErrorController.add(_lastError);
  }

  Future<int> start() async {
    if (_running) return localPort;
    final socket = await ServerSocket.bind(
      InternetAddress.loopbackIPv4,
      0,
      backlog: 256,
    );
    _serverSocket = socket;
    localPort = socket.port;
    _running = true;
    unawaited(_acceptLoop(socket));
    return localPort;
  }

  Future<void> _acceptLoop(ServerSocket socket) async {
    try {
      await for (final client in socket) {
        client.setOption(SocketOption.tcpNoDelay, true);
        try {
          await _permits.acquire();
        } catch (_) {
          try {
            client.destroy();
          } catch (_) {}
          continue;
        }
        unawaited(_handleClient(client));
      }
    } catch (_) {
      // Server socket closed by stop().
    }
  }

  void updateUpstreamServer(ServerNode? server, bool killSwitch) {
    _activeServer = server;
    _killSwitch = killSwitch;
  }

  Future<void> stop() async {
    _running = false;
    try {
      await _serverSocket?.close();
    } catch (_) {}
    _serverSocket = null;
  }

  void dispose() {
    _lastErrorController.close();
  }

  Future<void> _handleClient(Socket clientSocket) async {
    ByteStream? upstream;
    try {
      final client = SocketByteStream(clientSocket);
      final reader = ByteReader(client.stream);
      final requestLine = await reader.readLineAscii();
      if (requestLine == null) return;
      final parts = requestLine.split(' ');
      if (parts.length < 3) return;
      final method = parts[0].toUpperCase();
      final target = parts[1];
      final httpVersion = parts[2];

      final headers = <String>[];
      while (true) {
        final line = await reader.readLineAscii();
        if (line == null || line.isEmpty) break;
        headers.add(line);
      }

      final server = _activeServer;
      if (server == null) {
        if (_killSwitch) {
          await _sendHttpError(
            client,
            503,
            'Kill-Switch Active: No Proxy Server Selected',
          );
          return;
        }
        await _handleDirect(method, target, httpVersion, headers, client, reader);
        return;
      }

      final dest = _parseHostPort(method, target, headers);
      if (dest == null) return;

      ByteReader upstreamReader;
      try {
        final conn = await _connectToProxyServer(server, dest.$1, dest.$2);
        upstream = conn.$1;
        upstreamReader = conn.$2;
      } catch (e) {
        final msg = e.toString();
        _recordError(msg, server.name);
        try {
          await _sendHttpError(client, 502, 'Proxy error (${server.name}): $msg');
        } catch (_) {}
        return;
      }

      if (method == 'CONNECT') {
        client.add(ascii.encode(
          'HTTP/1.1 200 Connection Established\r\nProxy-Agent: FastNode/1.0\r\n\r\n',
        ));
        await client.flush();
      } else {
        final relativePath = _extractRelativePath(target);
        final sb = StringBuffer('$method $relativePath $httpVersion\r\n');
        for (final h in headers) {
          final lower = h.toLowerCase();
          if (!lower.startsWith('proxy-connection:') &&
              !lower.startsWith('proxy-authorization:')) {
            sb.write('$h\r\n');
          }
        }
        sb.write('\r\n');
        upstream.add(latin1.encode(sb.toString()));
        await upstream.flush();
      }

      await _relay(client, upstream, reader, upstreamReader);
    } catch (e) {
      final msg = e.toString();
      if (msg.isNotEmpty &&
          !msg.toLowerCase().contains('reset') &&
          !msg.contains('Broken pipe') &&
          !msg.contains('Connection closed') &&
          !_isTimeout(msg)) {
        _recordError('Bridge error: $msg', _activeServer?.name);
      }
    } finally {
      _permits.release();
      try {
        upstream?.destroy();
      } catch (_) {}
      try {
        clientSocket.destroy();
      } catch (_) {}
    }
  }

  bool _isTimeout(String msg) =>
      msg.contains('TimeoutException') || msg.contains('timed out');

  Future<void> _handleDirect(
    String method,
    String target,
    String httpVersion,
    List<String> headers,
    ByteStream client,
    ByteReader reader,
  ) async {
    final dest = _parseHostPort(method, target, headers);
    if (dest == null) return;
    final direct = await Socket.connect(
      dest.$1,
      dest.$2,
      timeout: _connectTimeout,
    );
    direct.setOption(SocketOption.tcpNoDelay, true);
    final upstream = SocketByteStream(direct);
    try {
      if (method == 'CONNECT') {
        client.add(ascii.encode('HTTP/1.1 200 Connection Established\r\n\r\n'));
        await client.flush();
      } else {
        final rel = _extractRelativePath(target);
        final sb = StringBuffer('$method $rel $httpVersion\r\n');
        for (final h in headers) {
          sb.write('$h\r\n');
        }
        sb.write('\r\n');
        upstream.add(latin1.encode(sb.toString()));
        await upstream.flush();
      }
      await _relay(client, upstream, reader, ByteReader(upstream.stream));
    } finally {
      upstream.destroy();
    }
  }

  /// Relay both directions until EITHER side ends, then destroy both
  /// sockets at once so the pump still stuck in read() unblocks.
  Future<void> _relay(
    ByteStream client,
    ByteStream upstream,
    ByteReader clientReader,
    ByteReader upstreamReader,
  ) async {
    Future<void> pump(Stream<List<int>> src, ByteStream dst) async {
      try {
        await for (final chunk in src.timeout(_readTimeout)) {
          dst.add(chunk);
        }
        await dst.flush();
      } catch (_) {
        // EOF, reset, timeout or a write to a dead socket — all end the
        // tunnel the same way.
      }
    }

    final up = pump(clientReader.drain(), upstream);
    final down = pump(upstreamReader.drain(), client);
    await Future.any([up, down]);
    client.destroy();
    upstream.destroy();
    await Future.wait([up, down]);
  }

  /// Connects to the upstream proxy and runs the proxy-protocol handshake.
  /// Returns the stream AND its reader — the handshake reader is the same
  /// one the relay must keep using (a second listener on a
  /// single-subscription stream would throw, and buffered leftovers would
  /// be lost).
  Future<(ByteStream, ByteReader)> _connectToProxyServer(
    ServerNode server,
    String destHost,
    int destPort,
  ) async {
    final ByteStream sock;
    if (server.transport == ProxyTransport.WEBSOCKET) {
      sock = await WebSocketByteStream.connect(
        server.host.trim(),
        server.port,
        server.wsPath.isEmpty ? '/ws' : server.wsPath,
        timeout: _connectTimeout,
      );
    } else {
      final plain = await Socket.connect(
        server.host.trim(),
        server.port,
        timeout: _connectTimeout,
      );
      plain.setOption(SocketOption.tcpNoDelay, true);
      sock = SocketByteStream(plain);
    }

    final reader = ByteReader(sock.stream);
    if (server.protocol == ProxyProtocol.SOCKS5) {
      await _socks5Handshake(sock, reader, server, destHost, destPort);
    } else {
      await _httpConnectHandshake(sock, reader, server, destHost, destPort);
    }
    return (sock, reader);
  }

  Future<void> _socks5Handshake(
    ByteStream sock,
    ByteReader reader,
    ServerNode server,
    String destHost,
    int destPort,
  ) async {
    final hasAuth =
        server.username.isNotEmpty || server.password.isNotEmpty;
    if (hasAuth) {
      sock.add([0x05, 0x02, 0x00, 0x02]);
    } else {
      sock.add([0x05, 0x01, 0x00]);
    }
    await sock.flush();

    final ver = await reader.readByte();
    final method = await reader.readByte();
    if (ver != 0x05 || method == null || method == 0xFF) {
      throw StateError('SOCKS5 greeting rejected');
    }
    if (method == 0x02) {
      final userBytes = utf8.encode(server.username);
      final passBytes = utf8.encode(server.password);
      final auth = <int>[
        0x01,
        userBytes.length,
        ...userBytes,
        passBytes.length,
        ...passBytes,
      ];
      sock.add(auth);
      await sock.flush();
      final authVer = await reader.readByte();
      final authStatus = await reader.readByte();
      if (authVer != 0x01 || authStatus != 0x00) {
        throw StateError('SOCKS5 authentication failed');
      }
    }

    final hostBytes = ascii.encode(destHost);
    final req = <int>[
      0x05, 0x01, 0x00, 0x03,
      hostBytes.length,
      ...hostBytes,
      (destPort >> 8) & 0xFF,
      destPort & 0xFF,
    ];
    sock.add(req);
    await sock.flush();

    final repVer = await reader.readByte();
    final repStatus = await reader.readByte();
    await reader.readByte(); // RSV
    final atyp = await reader.readByte();
    if (repVer != 0x05 || repStatus != 0x00) {
      throw StateError('SOCKS5 CONNECT failed with code: $repStatus');
    }
    switch (atyp) {
      case 0x01:
        await reader.readExactly(6);
      case 0x03:
        final len = await reader.readByte() ?? 0;
        await reader.readExactly(len + 2);
      case 0x04:
        await reader.readExactly(18);
    }
  }

  Future<void> _httpConnectHandshake(
    ByteStream sock,
    ByteReader reader,
    ServerNode server,
    String destHost,
    int destPort,
  ) async {
    final sb = StringBuffer('CONNECT $destHost:$destPort HTTP/1.1\r\n'
        'Host: $destHost:$destPort\r\n');
    if (server.username.isNotEmpty || server.password.isNotEmpty) {
      final creds = base64Encode(
        utf8.encode('${server.username}:${server.password}'),
      );
      sb.write('Proxy-Authorization: Basic $creds\r\n');
    }
    sb.write('\r\n');
    sock.add(ascii.encode(sb.toString()));
    await sock.flush();

    final statusLine = await reader.readLineAscii();
    if (statusLine == null) {
      throw StateError('Empty HTTP proxy response');
    }
    if (!statusLine.contains(' 200 ')) {
      throw StateError('HTTP proxy rejected CONNECT: $statusLine');
    }
    while (true) {
      final line = await reader.readLineAscii();
      if (line == null || line.isEmpty) break;
    }
  }

  (String, int)? _parseHostPort(
    String method,
    String target,
    List<String> headers,
  ) {
    if (method == 'CONNECT') {
      final idx = target.lastIndexOf(':');
      if (idx <= 0) return null;
      final host = target
          .substring(0, idx)
          .replaceAll('[', '')
          .replaceAll(']', '');
      final port = int.tryParse(target.substring(idx + 1)) ?? 443;
      return (host, port);
    }
    final hostHeader = headers
        .firstWhere(
          (h) => h.toLowerCase().startsWith('host:'),
          orElse: () => '',
        )
        .split(':')
        .skip(1)
        .join(':')
        .trim();
    final raw = hostHeader.isNotEmpty
        ? hostHeader
        : target.replaceFirst(RegExp(r'^[a-zA-Z]+://'), '').split('/').first;
    final idx = raw.lastIndexOf(':');
    if (idx > 0) {
      return (
        raw.substring(0, idx),
        int.tryParse(raw.substring(idx + 1)) ?? 80,
      );
    }
    return (raw, 80);
  }

  String _extractRelativePath(String target) {
    final lower = target.toLowerCase();
    if (!lower.startsWith('http://') && !lower.startsWith('https://')) {
      return target;
    }
    final withoutScheme = target.split('://').skip(1).join('://');
    final slashIdx = withoutScheme.indexOf('/');
    return slashIdx == -1 ? '/' : withoutScheme.substring(slashIdx);
  }

  Future<void> _sendHttpError(ByteStream out, int code, String message) async {
    final body =
        "<html><body style='background:#0a0a0a;color:#ff5555;font-family:sans-serif;padding:24px;'><h2>$code - $message</h2></body></html>";
    final bodyBytes = utf8.encode(body);
    final resp = 'HTTP/1.1 $code $message\r\n'
        'Content-Type: text/html\r\n'
        'Content-Length: ${bodyBytes.length}\r\n'
        'Connection: close\r\n\r\n';
    try {
      out.add(utf8.encode(resp));
      out.add(bodyBytes);
      await out.flush();
    } catch (_) {}
  }
}
