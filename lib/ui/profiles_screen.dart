// Profiles screen — create/edit/delete profiles, per-profile wipe,
// per-profile history (with delete), launch. The Delete button here is
// the one v1.0.7 was missing entirely (deleteProfile had no UI caller),
// and the per-profile history delete is the gap the user named.

import 'package:flutter/material.dart';

import '../app_state.dart';
import 'widgets/dialogs.dart';
import 'widgets/sheets.dart';

class ProfilesScreen extends StatelessWidget {
  final AppState state;
  final VoidCallback onOpenBrowser;

  const ProfilesScreen({
    super.key,
    required this.state,
    required this.onOpenBrowser,
  });

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Profiles')),
      floatingActionButton: FloatingActionButton.extended(
        icon: const Icon(Icons.add),
        label: const Text('New profile'),
        onPressed: () async {
          final created = await showProfileEditor(
            context,
            servers: state.servers,
          );
          if (created != null) await state.saveProfile(created);
        },
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const Text(
            'Each profile keeps its own cookies, logins and history — separate from the others.',
            style: TextStyle(color: Colors.grey),
          ),
          const SizedBox(height: 12),
          ...state.profiles.map((profile) {
            final active = state.settings.activeProfileId == profile.id;
            final tabCount = state.tabsForProfile(profile.id).length;
            final historyCount = state.historyFor(profile.id).length;
            final serverName = profile.assignedServerId == null
                ? 'Uses the active server'
                : state.servers
                        .where((s) => s.id == profile.assignedServerId)
                        .map((s) => s.name)
                        .firstOrNull ??
                    'Uses the active server';
            return Card(
              color: active
                  ? Theme.of(context)
                      .colorScheme
                      .primaryContainer
                      .withValues(alpha: 0.25)
                  : null,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(
                        active
                            ? Icons.radio_button_checked
                            : Icons.radio_button_unchecked,
                        color: active
                            ? Theme.of(context).colorScheme.primary
                            : null,
                      ),
                      title: Text(profile.name),
                      subtitle: Text(
                        '$serverName · ${profile.userAgentPreset.label} · $tabCount open tab${tabCount == 1 ? '' : 's'}',
                      ),
                      onTap: () async {
                        await state.launchProfile(profile);
                        onOpenBrowser();
                      },
                    ),
                    Wrap(
                      spacing: 8,
                      children: [
                        OutlinedButton.icon(
                          icon: const Icon(Icons.play_arrow, size: 18),
                          label: const Text('Use'),
                          onPressed: () async {
                            await state.launchProfile(profile);
                            onOpenBrowser();
                          },
                        ),
                        OutlinedButton.icon(
                          icon: const Icon(Icons.edit_outlined, size: 18),
                          label: const Text('Edit'),
                          onPressed: () async {
                            final edited = await showProfileEditor(
                              context,
                              existing: profile,
                              servers: state.servers,
                            );
                            if (edited != null) {
                              await state.saveProfile(edited);
                            }
                          },
                        ),
                        OutlinedButton.icon(
                          icon: const Icon(Icons.history, size: 18),
                          label: Text('History ($historyCount)'),
                          onPressed: () => showHistorySheet(
                            context,
                            state,
                            profile.id,
                          ),
                        ),
                        OutlinedButton.icon(
                          icon:
                              const Icon(Icons.cleaning_services, size: 18),
                          label: const Text('Wipe data'),
                          onPressed: () async {
                            final ok = await showDialog<bool>(
                              context: context,
                              builder: (context) => AlertDialog(
                                title: Text('Wipe ${profile.name}?'),
                                content: const Text(
                                  'This clears this profile\u2019s cookies, storage and cache. Other profiles are not affected.',
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
                                    child: const Text('Wipe'),
                                  ),
                                ],
                              ),
                            );
                            if (ok == true) {
                              await state.wipeProfileData(profile);
                            }
                          },
                        ),
                        OutlinedButton.icon(
                          icon: const Icon(Icons.delete_outline, size: 18),
                          label: const Text('Delete'),
                          onPressed: state.profiles.length <= 1
                              ? null
                              : () async {
                                  final ok = await showDialog<bool>(
                                    context: context,
                                    builder: (context) => AlertDialog(
                                      title:
                                          Text('Delete ${profile.name}?'),
                                      content: const Text(
                                        'This deletes the profile, its history and its cookies, and closes its tabs. This cannot be undone.',
                                      ),
                                      actions: [
                                        TextButton(
                                          onPressed: () => Navigator.of(
                                            context,
                                          ).pop(false),
                                          child: const Text('Cancel'),
                                        ),
                                        FilledButton(
                                          onPressed: () => Navigator.of(
                                            context,
                                          ).pop(true),
                                          child: const Text('Delete'),
                                        ),
                                      ],
                                    ),
                                  );
                                  if (ok == true) {
                                    await state.deleteProfile(profile.id);
                                  }
                                },
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            );
          }),
          const SizedBox(height: 12),
          Card(
            child: ListTile(
              leading: const Icon(Icons.visibility_off),
              title: const Text('Incognito'),
              subtitle: const Text(
                'Temporary profile — its cookies and data are wiped when the app closes.',
              ),
              trailing: OutlinedButton(
                onPressed: () {
                  state.newIncognitoTab();
                  onOpenBrowser();
                },
                child: const Text('Open'),
              ),
            ),
          ),
          const SizedBox(height: 80),
        ],
      ),
    );
  }
}

extension _FirstOrNull<T> on Iterable<T> {
  T? get firstOrNull => isEmpty ? null : first;
}
