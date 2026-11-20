// Home screen — beginner first: one big Connect button, plain words,
// status, active server, IP check, recent pages for the active profile.

import 'package:flutter/material.dart';

import '../app_state.dart';
import '../models.dart';
import '../utils.dart';
import 'widgets/sheets.dart';

class HomeScreen extends StatelessWidget {
  final AppState state;
  final VoidCallback onOpenBrowser;
  final VoidCallback onOpenServers;
  final VoidCallback onOpenSettings;

  const HomeScreen({
    super.key,
    required this.state,
    required this.onOpenBrowser,
    required this.onOpenServers,
    required this.onOpenSettings,
  });

  @override
  Widget build(BuildContext context) {
    final server = state.activeServer;
    final profile = state.activeProfile;
    final recent = profile == null ? <String>[] : state.historyFor(profile.id);
    final connected = state.isConnected;
    return Scaffold(
      appBar: AppBar(
        title: const Text('FastBrowser'),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            tooltip: 'Settings',
            onPressed: onOpenSettings,
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (state.proxyBlocked)
            Card(
              color: Theme.of(context).colorScheme.errorContainer,
              child: ListTile(
                leading: const Icon(Icons.block),
                title: const Text('Browsing is blocked'),
                subtitle: const Text(
                  'The proxy override is not available on this device, so the kill-switch is blocking all traffic to keep you safe.',
                ),
                trailing: TextButton(
                  onPressed: () async {
                    await state.updateSettings(
                      state.settings.copyWith(killSwitchEnabled: false),
                    );
                  },
                  child: const Text('Turn off kill-switch'),
                ),
              ),
            ),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(
                        connected ? Icons.shield : Icons.shield_outlined,
                        color: connected
                            ? Theme.of(context).colorScheme.primary
                            : null,
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          connected
                              ? 'Connected — traffic goes through ${server?.name ?? 'a server'}'
                              : 'Not connected',
                          style: const TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ],
                  ),
                  if (profile != null) ...[
                    const SizedBox(height: 8),
                    Text('Profile: ${profile.name}'),
                  ],
                  if (state.bridgeTestResult.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    Text(state.bridgeTestResult),
                  ],
                  const SizedBox(height: 16),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      icon: const Icon(Icons.public),
                      label: const Text('Connect & browse'),
                      onPressed: onOpenBrowser,
                    ),
                  ),
                  const SizedBox(height: 8),
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      icon: const Icon(Icons.speed),
                      label: const Text('Check my IP'),
                      onPressed: state.testBridgeConnection,
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          if (server != null)
            Card(
              child: ListTile(
                leading: Text(
                  flagFor(server.countryCode),
                  style: const TextStyle(fontSize: 24),
                ),
                title: Text(server.name),
                subtitle: Text(
                  '${server.host}:${server.port}'
                  '${server.transport == ProxyTransport.WEBSOCKET ? ' · WebSocket' : ''}',
                ),
                trailing: _LatencyText(state: state, serverId: server.id),
                onTap: onOpenServers,
              ),
            ),
          const SizedBox(height: 16),
          Row(
            children: [
              const Expanded(
                child: Text(
                  'Recent pages',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
                ),
              ),
              if (recent.isNotEmpty)
                TextButton(
                  onPressed: () => showHistorySheet(
                    context,
                    state,
                    profile!.id,
                  ),
                  child: const Text('See all'),
                ),
            ],
          ),
          if (recent.isEmpty)
            const Text(
              'Nothing yet — open the browser and visit a page.',
              style: TextStyle(color: Colors.grey),
            )
          else
            ...recent.take(5).map(
              (url) => ListTile(
                leading: const Icon(Icons.history),
                title: Text(
                  cleanDomain(url),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                onTap: () {
                  state.navigateActiveTab(url);
                  onOpenBrowser();
                },
              ),
            ),
        ],
      ),
    );
  }
}

class _LatencyText extends StatelessWidget {
  final AppState state;
  final String serverId;
  const _LatencyText({required this.state, required this.serverId});

  @override
  Widget build(BuildContext context) {
    final latency = state.latencyResults[serverId];
    if (latency == null) return const Text('—');
    if (latency.rttMs == null) {
      return const Text('no reply', style: TextStyle(color: Colors.red));
    }
    return Text(
      '${latency.rttMs} ms${latency.stale ? ' (stale)' : ''}',
      style: TextStyle(color: latencyColor(latency.rttMs)),
    );
  }
}
