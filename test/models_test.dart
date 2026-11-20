import 'package:fastbrowser/models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('ServerNode JSON round-trips all fields', () {
    const server = ServerNode(
      id: 'server-stockholm-cf',
      name: 'Stockholm (Cloudflare)',
      host: 'cf-gost.limoo.indevs.in',
      port: 2083,
      protocol: ProxyProtocol.SOCKS5,
      countryCode: 'SE',
      timezoneOverride: 'Europe/Stockholm',
      languageOverride: 'en-US',
      transport: ProxyTransport.WEBSOCKET,
      wsPath: '/ws',
    );
    final back = ServerNode.fromJson(server.toJson());
    expect(back.id, server.id);
    expect(back.host, server.host);
    expect(back.port, server.port);
    expect(back.languageOverride, 'en-US');
    expect(back.transport, ProxyTransport.WEBSOCKET);
    expect(back.wsPath, '/ws');
  });

  test('AppSettings JSON round-trips', () {
    const settings = AppSettings(
      activeServerId: 'server-ohio',
      activeProfileId: 'default_profile',
      killSwitchEnabled: true,
      textScale: 1.25,
      searchEngine: SearchEngine.DUCKDUCKGO,
    );
    final back = AppSettings.fromJson(settings.toJson());
    expect(back.activeServerId, 'server-ohio');
    expect(back.textScale, 1.25);
    expect(back.searchEngine, SearchEngine.DUCKDUCKGO);
  });

  test('BackupBundle round-trips (v1.0.7 import shape)', () {
    const bundle = BackupBundle(
      servers: [
        ServerNode(id: 's1', name: 'Ohio', host: '18.190.239.234', port: 10800),
      ],
      profiles: [
        BrowserProfile(id: 'p1', name: 'Default Profile'),
      ],
      settings: AppSettings(activeServerId: 's1'),
    );
    final back = BackupBundle.fromJsonString(bundle.toJsonString());
    expect(back.servers.length, 1);
    expect(back.servers.first.host, '18.190.239.234');
    expect(back.profiles.first.name, 'Default Profile');
    expect(back.settings.activeServerId, 's1');
  });

  test('BackupBundle rejects garbage', () {
    expect(() => BackupBundle.fromJsonString('not json'), throwsA(anything));
    expect(
      () => BackupBundle.fromJsonString('{"servers": []}'),
      throwsA(anything),
    );
  });

  test('BrowserTab copyWith keeps identity, updates flags', () {
    final tab = BrowserTab(profileId: 'default_profile', url: 'https://example.com');
    final reloaded = tab.copyWith(reloadNonce: tab.reloadNonce + 1);
    expect(reloaded.id, tab.id);
    expect(reloaded.reloadNonce, 1);
    final errored = tab.copyWith(loadError: 'boom');
    expect(errored.loadError, 'boom');
    expect(errored.copyWith(clearLoadError: true).loadError, isNull);
    final desktop = tab.copyWith(desktopMode: true);
    expect(desktop.desktopMode, isTrue);
  });

  test('DownloadRecord copyWith updates file name and status', () {
    final d = DownloadRecord(
      url: 'https://example.com/f.pdf',
      fileName: 'f.pdf',
      mimeType: 'application/pdf',
      profileId: 'default_profile',
    );
    final done = d.copyWith(
      fileName: 'f (1).pdf',
      status: DownloadStatus.done,
      sizeBytes: 1234,
    );
    expect(done.fileName, 'f (1).pdf');
    expect(done.status, DownloadStatus.done);
    expect(done.sizeBytes, 1234);
    expect(done.id, d.id);
  });
}
