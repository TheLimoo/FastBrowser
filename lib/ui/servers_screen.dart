// Servers screen — list, ping, add/edit/delete, auto-select fastest.

import 'package:flutter/material.dart';

import '../app_state.dart';
import '../models.dart';
import '../utils.dart';
import 'widgets/dialogs.dart';

class ServersScreen extends StatelessWidget {
  final AppState state;
  const ServersScreen({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Servers'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Ping all servers',
            onPressed: state.isPinging ? null : state.refreshLatency,
          ),
          IconButton(
            icon: const Icon(Icons.speed),
            tooltip: 'Auto-select fastest',
            onPressed: state.autoSelectFastestServer,
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        icon: const Icon(Icons.add),
        label: const Text('Add server'),
        onPressed: () async {
          final created = await showServerEditor(context);
          if (created != null) await state.saveServer(created);
        },
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (state.servers.isEmpty)
            const Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: Text('No servers. Add one to get started.'),
              ),
            )
          else
            ...state.servers.map((server) {
              final latency = state.latencyResults[server.id];
              final active = state.settings.activeServerId == server.id;
              return Card(
                color: active
                    ? Theme.of(context)
                        .colorScheme
                        .primaryContainer
                        .withValues(alpha: 0.25)
                    : null,
                child: ListTile(
                  leading: Text(
                    flagFor(server.countryCode),
                    style: const TextStyle(fontSize: 24),
                  ),
                  title: Text(server.name),
                  subtitle: Text(
                    '${server.host}:${server.port} · '
                    '${server.protocol.name} · '
                    '${server.transport == ProxyTransport.WEBSOCKET ? 'WebSocket ${server.wsPath}' : 'Direct'}'
                    '${server.username.isNotEmpty ? ' · has login' : ' · no login'}',
                  ),
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (latency != null)
                        Text(
                          latency.rttMs == null
                              ? 'no reply'
                              : '${latency.rttMs} ms${latency.stale ? ' (stale)' : ''}',
                          style: TextStyle(
                            color: latencyColor(latency.rttMs),
                            fontSize: 12,
                          ),
                        ),
                      const SizedBox(width: 4),
                      PopupMenuButton<String>(
                        onSelected: (value) async {
                          if (value == 'edit') {
                            final edited = await showServerEditor(
                              context,
                              existing: server,
                            );
                            if (edited != null) {
                              await state.saveServer(edited);
                            }
                          } else if (value == 'delete') {
                            final ok = await showDialog<bool>(
                              context: context,
                              builder: (context) => AlertDialog(
                                title: Text('Delete ${server.name}?'),
                                content: const Text(
                                  'This server will be removed from the list.',
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
                                    child: const Text('Delete'),
                                  ),
                                ],
                              ),
                            );
                            if (ok == true) {
                              await state.deleteServer(server.id);
                            }
                          }
                        },
                        itemBuilder: (context) => const [
                          PopupMenuItem(
                            value: 'edit',
                            child: Text('Edit'),
                          ),
                          PopupMenuItem(
                            value: 'delete',
                            child: Text('Delete'),
                          ),
                        ],
                      ),
                    ],
                  ),
                  onTap: () => state.setActiveServer(server),
                ),
              );
            }),
          const SizedBox(height: 80),
        ],
      ),
    );
  }
}
