// Crash capture — Dart equivalent of CrashReporter.kt.
//
// Flutter errors + platform errors are recorded to a private on-device
// file (`last_crash.txt`, nothing is uploaded) and exposed in
// Settings → Diagnostics as "Last crash", so a crash reports its exact
// cause instead of a blank screen. A crash from a previous run is read
// back on start. The native Kotlin side writes the same file for
// platform-side crashes (same line format).

import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

class CrashInfo {
  final int atMillis;
  final String exceptionName;
  final String? message;
  final List<String> stackLines;

  const CrashInfo({
    required this.atMillis,
    required this.exceptionName,
    this.message,
    this.stackLines = const [],
  });
}

class CrashReporter {
  static const _fileName = 'last_crash.txt';
  static const _maxStackLines = 25;

  static CrashInfo? lastCrash;
  static final StreamController<CrashInfo?> _controller =
      StreamController<CrashInfo?>.broadcast();
  static Stream<CrashInfo?> get stream => _controller.stream;

  static Future<void> install() async {
    lastCrash = await _readCrashFile();
    _controller.add(lastCrash);
    FlutterError.onError = (details) {
      unawaited(_record(
        details.exception.runtimeType.toString(),
        details.exception.toString(),
        details.stack?.toString() ?? '',
      ));
    };
    PlatformDispatcher.instance.onError = (error, stack) {
      unawaited(_record(
        error.runtimeType.toString(),
        error.toString(),
        stack.toString(),
      ));
      return true;
    };
  }

  static Future<void> _record(
    String name,
    String message,
    String stack,
  ) async {
    final info = CrashInfo(
      atMillis: DateTime.now().millisecondsSinceEpoch,
      exceptionName: name,
      message: message.isEmpty ? null : message,
      stackLines: stack.split('\n').take(_maxStackLines).toList(),
    );
    lastCrash = info;
    _controller.add(info);
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/$_fileName');
      final text = StringBuffer()
        ..writeln(info.atMillis)
        ..writeln(info.exceptionName)
        ..writeln(info.message ?? '')
        ..writeAll(info.stackLines, '\n');
      await file.writeAsString(text.toString());
    } catch (_) {}
  }

  static Future<CrashInfo?> _readCrashFile() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/$_fileName');
      if (!await file.exists()) return null;
      final lines = await file.readAsLines();
      if (lines.length < 3) return null;
      return CrashInfo(
        atMillis: int.tryParse(lines[0]) ?? 0,
        exceptionName: lines[1],
        message: lines[2].isEmpty ? null : lines[2],
        stackLines: lines.skip(3).toList(),
      );
    } catch (_) {
      return null;
    }
  }

  static Future<void> clear() async {
    lastCrash = null;
    _controller.add(null);
    try {
      final dir = await getApplicationDocumentsDirectory();
      await File('${dir.path}/$_fileName').delete();
    } catch (_) {}
  }
}
