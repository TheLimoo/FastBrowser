// Bottom sheets: server switcher, history (per profile), bookmarks.
// The server switcher fixes the v1.0.7 gap where NTP server cards said
// "Tap a card to switch" but only opened this sheet — here (and on the
// new-tab page) tapping a server SELECTS it. The history sheet is
// per-profile and has the per-entry delete + "Clear history" buttons the
// old app lacked.

import 'package:flutter/material.dart';

import '../../app_state.dart';
import '../../latency.dart';
import '../../models.dart';
import '../../utils.dart';

void showServerSwitcherSheet(
  BuildContext context,
  AppState state, {
  required VoidCallback onAddServer,
}) {
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (context) => _ServerSwitcherSheet(
      state: state,
      onAddServer: onAddServer,
    ),
  );
}

class _ServerSwitcherSheet extends StatelessWidget {
  final AppState state;
  final VoidCallback onAddServer;
  const _ServerSwitcherSheet({required this.state, required this.onAddServer});

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Servers',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 8),
            ...state.servers.map((server) {
              final latency = state.latencyResults[server.id];
              final active = state.settings.activeServerId == server.id;
              return ListTile(
                leading: Text(
                  flagFor(server.countryCode),
                  style: const TextStyle(fontSize: 22),
                ),
                title: Text(server.name),
                subtitle: Text(
                  '${server.host}:${server.port}'
                  '${server.transport == ProxyTransport.WEBSOCKET ? ' · WebSocket' : ''}',
                ),
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    _LatencyChip(latency: latency),
                    const SizedBox(width: 8),
                    Icon(
                      active
                          ? Icons.radio_button_checked
                          : Icons.radio_button_unchecked,
                      color: active
                          ? Theme.of(context).colorScheme.primary
                          : null,
                    ),
                  ],
                ),
                onTap: () {
                  Navigator.of(context).pop();
                  state.setActiveServer(server);
                },
              );
            }),
            ListTile(
              leading: const Icon(Icons.public),
              title: const Text('Direct Connection'),
              subtitle: Text(
                state.settings.killSwitchEnabled
                    ? 'Blocked — turn off the kill-switch in Settings to allow it'
                    : 'Browse without a server (your real IP is visible)',
              ),
              onTap: () {
                Navigator.of(context).pop();
                if (state.settings.killSwitchEnabled) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      content: Text(
                        'Direct connection is blocked while the kill-switch is on. Turn it off in Settings if you really want to browse directly.',
                      ),
                    ),
                  );
                } else {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      content: Text(
                        'Direct connection is not a server — pick a server to route through the proxy.',
                      ),
                    ),
                  );
                }
              },
            ),
            const Divider(),
            ListTile(
              leading: const Icon(Icons.add),
              title: const Text('Add server'),
              onTap: () {
                Navigator.of(context).pop();
                onAddServer();
              },
            ),
            ListTile(
              leading: const Icon(Icons.speed),
              title: const Text('Auto-select fastest'),
              onTap: () {
                Navigator.of(context).pop();
                state.autoSelectFastestServer();
              },
            ),
          ],
        ),
      ),
    );
  }
}

class _LatencyChip extends StatelessWidget {
  final ServerLatencyResult? latency;
  const _LatencyChip({this.latency});

  @override
  Widget build(BuildContext context) {
    if (latency == null) return const SizedBox.shrink();
    if (latency!.rttMs == null) {
      return const Text(
        'no reply',
        style: TextStyle(color: Colors.red, fontSize: 12),
      );
    }
    final color = latencyColor(latency!.rttMs);
    final stale = latency!.stale ? ' (stale)' : '';
    return Text(
      '${latency!.rttMs} ms$stale',
      style: TextStyle(color: color, fontSize: 12),
    );
  }
}

void showHistorySheet(BuildContext context, AppState state, String profileId) {
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (context) => _HistorySheet(state: state, profileId: profileId),
  );
}

class _HistorySheet extends StatelessWidget {
  final AppState state;
  final String profileId;
  const _HistorySheet({required this.state, required this.profileId});

  @override
  Widget build(BuildContext context) {
    final history = state.historyFor(profileId);
    final profile = state.profiles.firstWhere(
      (p) => p.id == profileId,
      orElse: () => const BrowserProfile(id: '', name: 'Profile'),
    );
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    'History — ${profile.name}',
                    style: const TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                if (history.isNotEmpty)
                  TextButton.icon(
                    icon: const Icon(Icons.delete_sweep),
                    label: const Text('Clear history'),
                    onPressed: () async {
                      final ok = await showDialog<bool>(
                        context: context,
                        builder: (context) => AlertDialog(
                          title: const Text('Clear history?'),
                          content: Text(
                            'Delete all history for ${profile.name}?',
                          ),
                          actions: [
                            TextButton(
                              onPressed: () =>
                                  Navigator.of(context).pop(false),
                              child: const Text('Cancel'),
                            ),
                            FilledButton(
                              onPressed: () =>
                                  Navigator.of(context).pop(true),
                              child: const Text('Clear'),
                            ),
                          ],
                        ),
                      );
                      if (ok == true) {
                        await state.clearHistoryForProfile(profileId);
                        if (context.mounted) Navigator.of(context).pop();
                      }
                    },
                  ),
              ],
            ),
            const SizedBox(height: 8),
            if (history.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 24),
                child: Center(child: Text('No history yet for this profile.')),
              )
            else
              Flexible(
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: history.length,
                  itemBuilder: (context, i) {
                    final url = history[i];
                    return ListTile(
                      leading: const Icon(Icons.history),
                      title: Text(
                        cleanDomain(url),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      subtitle: Text(
                        url,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      trailing: IconButton(
                        icon: const Icon(Icons.delete_outline),
                        tooltip: 'Delete this entry',
                        onPressed: () =>
                            state.deleteHistoryEntry(profileId, url),
                      ),
                      onTap: () {
                        Navigator.of(context).pop();
                        state.navigateActiveTab(url);
                      },
                    );
                  },
                ),
              ),
          ],
        ),
      ),
    );
  }
}

void showBookmarksSheet(BuildContext context, AppState state) {
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (context) => _BookmarksSheet(state: state),
  );
}

class _BookmarksSheet extends StatelessWidget {
  final AppState state;
  const _BookmarksSheet({required this.state});

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Bookmarks',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 8),
            if (state.bookmarks.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 24),
                child: Center(child: Text('No bookmarks yet.')),
              )
            else
              Flexible(
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: state.bookmarks.length,
                  itemBuilder: (context, i) {
                    final b = state.bookmarks[i];
                    return ListTile(
                      leading: const Icon(Icons.bookmark),
                      title: Text(
                        b.title.isEmpty ? cleanDomain(b.url) : b.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      subtitle: Text(
                        b.url,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          IconButton(
                            icon: const Icon(Icons.edit_outlined),
                            tooltip: 'Rename',
                            onPressed: () async {
                              final controller =
                                  TextEditingController(text: b.title);
                              final newTitle = await showDialog<String>(
                                context: context,
                                builder: (context) => AlertDialog(
                                  title: const Text('Rename bookmark'),
                                  content: TextField(
                                    controller: controller,
                                    autofocus: true,
                                    decoration: const InputDecoration(
                                      labelText: 'Title',
                                    ),
                                  ),
                                  actions: [
                                    TextButton(
                                      onPressed: () =>
                                          Navigator.of(context).pop(),
                                      child: const Text('Cancel'),
                                    ),
                                    FilledButton(
                                      onPressed: () => Navigator.of(context)
                                          .pop(controller.text.trim()),
                                      child: const Text('Save'),
                                    ),
                                  ],
                                ),
                              );
                              if (newTitle != null && newTitle.isNotEmpty) {
                                await state.renameBookmark(b, newTitle);
                              }
                            },
                          ),
                          IconButton(
                            icon: const Icon(Icons.delete_outline),
                            tooltip: 'Delete',
                            onPressed: () => state.removeBookmark(b.url),
                          ),
                        ],
                      ),
                      onTap: () {
                        Navigator.of(context).pop();
                        state.navigateActiveTab(b.url);
                      },
                    );
                  },
                ),
              ),
          ],
        ),
      ),
    );
  }
}
