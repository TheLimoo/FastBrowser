// Downloads through the local proxy bridge — Dart port of
// ProxyDownloader.kt. NEVER the system downloader: the old Android
// DownloadManager downloaded DIRECTLY (bypassing the proxy, leaking the
// real IP) and without the profile's cookies.
//
// Flow: HttpClient via `PROXY 127.0.0.1:<bridgePort>` (so the download
// exits through the selected server), with the profile's cookies (fetched
// from the native per-profile cookie store over the channel), streamed to
// a temp file with progress, then handed to the native side for the
// MediaStore insert into public Downloads (Dart has no MediaStore API).

import 'dart:async';
import 'dart:io';

typedef CookieProvider = Future<String?> Function(String url);
typedef SaveToDownloads = Future<String> Function({
  required String tempPath,
  required String fileName,
  required String mimeType,
});

class DownloadResult {
  final String savedName;
  final int sizeBytes;
  const DownloadResult(this.savedName, this.sizeBytes);
}

String guessFileName(String url, String? contentDisposition, String? mimeType) {
  if (contentDisposition != null) {
    final m = RegExp(r'''filename\*?=(?:UTF-8''?)?"?([^";]+)"?''')
        .firstMatch(contentDisposition);
    if (m != null) {
      final name = Uri.decodeComponent(m.group(1)!).trim();
      if (name.isNotEmpty) return name;
    }
  }
  try {
    final uri = Uri.parse(url);
    final last = uri.pathSegments.isNotEmpty ? uri.pathSegments.last : '';
    if (last.isNotEmpty) {
      // Android's URLUtil.guessFileName: an extension-less last segment
      // keeps its name and gains the mime type's extension.
      if (last.contains('.')) return last;
      final ext = _extensionForMime(mimeType);
      return ext.isNotEmpty ? '$last$ext' : last;
    }
  } catch (_) {}
  final ext = _extensionForMime(mimeType);
  return ext.isNotEmpty ? 'download$ext' : 'download';
}

String _extensionForMime(String? mimeType) => switch (mimeType) {
      'application/pdf' => '.pdf',
      'image/jpeg' => '.jpg',
      'image/png' => '.png',
      'image/webp' => '.webp',
      'video/mp4' => '.mp4',
      'audio/mpeg' => '.mp3',
      'application/zip' => '.zip',
      'application/vnd.android.package-archive' => '.apk',
      'text/plain' => '.txt',
      'text/html' => '.html',
      _ => '',
    };

Future<DownloadResult> downloadThroughProxy({
  required String url,
  required String userAgent,
  required String fileName,
  required String mimeType,
  required int localProxyPort,
  required CookieProvider getCookie,
  required Directory tempDir,
  required SaveToDownloads saveToDownloads,
  void Function(int received, int? total)? onProgress,
}) async {
  final client = HttpClient();
  client.findProxy = (uri) => 'PROXY 127.0.0.1:$localProxyPort';
  client.connectionTimeout = const Duration(seconds: 15);
  try {
    final request = await client.getUrl(Uri.parse(url));
    request.headers.set(HttpHeaders.userAgentHeader, userAgent);
    final cookie = await getCookie(url);
    if (cookie != null && cookie.isNotEmpty) {
      request.headers.set(HttpHeaders.cookieHeader, cookie);
    }
    final response = await request.close().timeout(const Duration(seconds: 30));
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw HttpException(
        'HTTP ${response.statusCode} ${response.reasonPhrase}',
        uri: Uri.parse(url),
      );
    }
    final total = response.contentLength >= 0 ? response.contentLength : null;
    final tempFile = File(
      '${tempDir.path}/dl_${DateTime.now().millisecondsSinceEpoch}_$fileName',
    );
    var received = 0;
    final sink = tempFile.openWrite();
    try {
      await for (final chunk in response.timeout(const Duration(seconds: 30))) {
        received += chunk.length;
        sink.add(chunk);
        onProgress?.call(received, total);
      }
    } finally {
      await sink.close();
    }
    final savedName = await saveToDownloads(
      tempPath: tempFile.path,
      fileName: fileName,
      mimeType: mimeType,
    );
    try {
      await tempFile.delete();
    } catch (_) {}
    return DownloadResult(savedName, received);
  } finally {
    client.close(force: true);
  }
}
