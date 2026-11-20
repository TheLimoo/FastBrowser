// Downloads screen — the in-app download list the old app never had
// (its menu item just opened the system Downloads app). List, open,
// delete, and retry failed downloads. Downloads always go through the
// local proxy bridge with the profile's cookies — never the system
// downloader, which leaked the real IP.

import 'package:flutter/material.dart';

import '../app_state.dart';
import '../models.dart';
import '../native.dart';

class DownloadsScreen extends StatelessWidget {
  final AppState state;
  const DownloadsScreen({super.key, required this.state});

  String _sizeText(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Downloads')),
      body: state.downloads.isEmpty
          ? const Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: Text(
                  'No downloads yet.\nDownloads from the browser land here — they go through the proxy, so your real IP stays hidden.',
                  textAlign: TextAlign.center,
                ),
              ),
            )
          : ListView.builder(
              padding: const EdgeInsets.all(16),
              itemCount: state.downloads.length,
              itemBuilder: (context, i) {
                final d = state.downloads[i];
                final statusText = switch (d.status) {
                  DownloadStatus.downloading =>
                    'Downloading… ${_sizeText(d.sizeBytes)}',
                  DownloadStatus.done => 'Done · ${_sizeText(d.sizeBytes)}',
                  DownloadStatus.failed =>
                    'Failed${d.error != null ? ': ${d.error}' : ''}',
                };
                return Card(
                  child: ListTile(
                    leading: Icon(
                      d.status == DownloadStatus.done
                          ? Icons.check_circle
                          : d.status == DownloadStatus.failed
                              ? Icons.error_outline
                              : Icons.download,
                      color: d.status == DownloadStatus.done
                          ? Theme.of(context).colorScheme.primary
                          : d.status == DownloadStatus.failed
                              ? Theme.of(context).colorScheme.error
                              : null,
                    ),
                    title: Text(
                      d.fileName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    subtitle: Text(
                      statusText,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (d.status == DownloadStatus.done)
                          IconButton(
                            icon: const Icon(Icons.open_in_new),
                            tooltip: 'Open',
                            onPressed: () async {
                              final ok = await NativeBridge.openDownload(
                                d.fileName,
                              );
                              if (!ok && context.mounted) {
                                ScaffoldMessenger.of(context).showSnackBar(
                                  const SnackBar(
                                    content: Text(
                                      'Could not open this file — no app handles it.',
                                    ),
                                  ),
                                );
                              }
                            },
                          ),
                        if (d.status == DownloadStatus.failed)
                          IconButton(
                            icon: const Icon(Icons.refresh),
                            tooltip: 'Retry',
                            onPressed: () {
                              final profile = state.activeProfile;
                              final tab = state.activeTab;
                              final ua = profile == null
                                  ? ''
                                  : profile.customUserAgent.isNotEmpty
                                      ? profile.customUserAgent
                                      : profile.userAgentPreset.uaString;
                              state.startDownload(
                                url: d.url,
                                userAgent: ua,
                                contentDisposition: '',
                                mimeType: d.mimeType,
                                profileId: tab?.profileId ??
                                    state.settings.activeProfileId,
                              );
                              state.deleteDownload(d.id);
                            },
                          ),
                        IconButton(
                          icon: const Icon(Icons.delete_outline),
                          tooltip: 'Delete',
                          onPressed: () => state.deleteDownload(d.id),
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
    );
  }
}
