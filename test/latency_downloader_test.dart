import 'package:fastbrowser/downloader.dart';
import 'package:fastbrowser/latency.dart';
import 'package:fastbrowser/models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('guessFileName', () {
    test('prefers content-disposition filename', () {
      expect(
        guessFileName(
          'https://example.com/download?id=1',
          'attachment; filename="report.pdf"',
          'application/pdf',
        ),
        'report.pdf',
      );
    });

    test('falls back to the URL segment', () {
      expect(
        guessFileName('https://example.com/files/app.apk', null, null),
        'app.apk',
      );
    });

    test('falls back to a mime-based name', () {
      expect(
        guessFileName('https://example.com/dl', null, 'application/pdf'),
        'dl.pdf',
      );
      expect(guessFileName('https://example.com/', null, null), 'download');
    });
  });

  group('LatencyMonitor', () {
    test('pingAll with no servers returns an empty map', () async {
      expect(await LatencyMonitor.pingAll([]), isEmpty);
    });

    test('a closed local port reports offline, not a crash', () async {
      // Port 1 on loopback refuses immediately — no 8 s wait.
      const server = ServerNode(
        id: 'dead',
        name: 'Dead',
        host: '127.0.0.1',
        port: 1,
      );
      final result = await LatencyMonitor.pingServer(server);
      expect(result.isOnline, isFalse);
      expect(result.rttMs, isNull);
    });
  });
}
