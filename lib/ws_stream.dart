// Byte-stream abstraction shared by raw TCP sockets and WebSocket
// upstreams, so the SOCKS5/HTTP handshake and relay code runs on both
// unchanged (the same idea as the Kotlin WebSocketUpstreamSocket façade).
//
// A Dart `dart:io` WebSocket is message-oriented; this adapter turns it
// into a plain byte stream: outgoing writes become one binary message each,
// incoming messages are concatenated into a continuous stream. The
// WebSocket layer itself reassembles fragmented messages and answers
// ping frames — the bridge never sees control frames.

import 'dart:async';
import 'dart:convert';
import 'dart:io';

/// Minimal byte-stream interface: a single-subscription byte stream plus
/// write/flush/close/destroy.
abstract class ByteStream {
  Stream<List<int>> get stream;
  void add(List<int> data);
  Future<void> flush();
  Future<void> close();
  void destroy();
}

class SocketByteStream implements ByteStream {
  final Socket _socket;
  SocketByteStream(this._socket);

  @override
  Stream<List<int>> get stream => _socket;

  @override
  void add(List<int> data) => _socket.add(data);

  @override
  Future<void> flush() => _socket.flush();

  @override
  Future<void> close() => _socket.close();

  @override
  void destroy() => _socket.destroy();
}

class WebSocketByteStream implements ByteStream {
  final WebSocket _ws;
  final StreamController<List<int>> _controller =
      StreamController<List<int>>();
  bool _closed = false;

  WebSocketByteStream._(this._ws) {
    _ws.listen(
      (dynamic data) {
        if (_closed) return;
        if (data is List<int>) _controller.add(data);
      },
      onError: (_) => _finish(),
      onDone: _finish,
      cancelOnError: true,
    );
  }

  /// TLS + WS handshake (Dart does both). Throws on handshake failure.
  static Future<WebSocketByteStream> connect(
    String host,
    int port,
    String path, {
    Duration timeout = const Duration(seconds: 10),
  }) async {
    final url = 'wss://$host:$port$path';
    final ws = await WebSocket.connect(url).timeout(timeout);
    return WebSocketByteStream._(ws);
  }

  void _finish() {
    if (_closed) return;
    _closed = true;
    _controller.close();
  }

  @override
  Stream<List<int>> get stream => _controller.stream;

  @override
  void add(List<int> data) {
    if (!_closed) _ws.add(data);
  }

  @override
  Future<void> flush() async {}

  @override
  Future<void> close() async {
    _closed = true;
    try {
      await _ws.close();
    } catch (_) {}
    await _controller.close();
  }

  @override
  void destroy() {
    _closed = true;
    try {
      _ws.close();
    } catch (_) {}
    _controller.close();
  }
}

/// Buffered exact reader over a byte stream. Used by the SOCKS5 handshake
/// (readUnsignedByte / readFully) and by the bridge's HTTP request-line
/// parsing. Returns null on EOF.
class ByteReader {
  final StreamIterator<List<int>> _it;
  final List<int> _buf = <int>[];
  int _pos = 0;
  bool _eof = false;

  ByteReader(Stream<List<int>> stream) : _it = StreamIterator(stream);

  Future<bool> _fill() async {
    if (_pos < _buf.length) return true;
    if (_eof) return false;
    if (await _it.moveNext()) {
      final chunk = _it.current;
      if (chunk.isNotEmpty) {
        _buf.addAll(chunk);
        return true;
      }
      return _fill();
    }
    _eof = true;
    return false;
  }

  Future<int?> readByte() async {
    if (!await _fill()) return null;
    return _buf[_pos++];
  }

  Future<List<int>?> readExactly(int n) async {
    final out = <int>[];
    while (out.length < n) {
      if (!await _fill()) return null;
      final take = (n - out.length).clamp(0, _buf.length - _pos);
      out.addAll(_buf.sublist(_pos, _pos + take));
      _pos += take;
    }
    return out;
  }

  /// One CRLF-terminated ASCII line (without the CRLF). Null on EOF with
  /// nothing read.
  Future<String?> readLineAscii() async {
    final bytes = <int>[];
    while (true) {
      final b = await readByte();
      if (b == null) {
        return bytes.isEmpty ? null : ascii.decode(bytes);
      }
      if (b == 10) {
        if (bytes.isNotEmpty && bytes.last == 13) bytes.removeLast();
        return ascii.decode(bytes);
      }
      bytes.add(b);
    }
  }

  /// Yields bytes already buffered (read ahead by the handshake), then the
  /// rest of the stream. Lets the relay pump keep reading from the same
  /// stream the handshake reader already consumed from — buffered
  /// leftovers are not lost.
  Stream<List<int>> drain() async* {
    if (_pos < _buf.length) {
      yield _buf.sublist(_pos);
      _pos = _buf.length;
    }
    while (await _it.moveNext()) {
      yield _it.current;
    }
  }
}
