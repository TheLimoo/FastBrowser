// Settings — all nine sections from the v1.0.7 inventory, plus the
// Diagnostics section (bridge error, last crash, bridge test) and
// import/export/reset. Advanced items live under one "Advanced" section.

import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';

import '../app_state.dart';
import '../crash.dart';
import '../models.dart';
import '../utils.dart';

class SettingsScreen extends StatelessWidget {
  final AppState state;
  const SettingsScreen({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    final s = state.settings;
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _section('Connection'),
          SwitchListTile(
            title: const Text('Kill-switch'),
            subtitle: const Text(
              'Block all traffic when no server is active. Fail closed — nothing leaks.',
            ),
            value: s.killSwitchEnabled,
            onChanged: (v) =>
                state.updateSettings(s.copyWith(killSwitchEnabled: v)),
          ),
          SwitchListTile(
            title: const Text('Auto-select fastest server'),
            subtitle: const Text(
              'Pick the lowest-latency server when the app starts.',
            ),
            value: s.autoSelectFastestServer,
            onChanged: (v) => state.updateSettings(
              s.copyWith(autoSelectFastestServer: v),
            ),
          ),
          _section('Privacy & security'),
          SwitchListTile(
            title: const Text('Block WebRTC leaks'),
            subtitle: const Text('Stops sites seeing your real IP.'),
            value: s.blockWebRtcGlobal,
            onChanged: (v) =>
                state.updateSettings(s.copyWith(blockWebRtcGlobal: v)),
          ),
          SwitchListTile(
            title: const Text('Block ads & trackers'),
            subtitle: const Text('Blocks known ad and tracker domains.'),
            value: s.adAndTrackerBlocker,
            onChanged: (v) =>
                state.updateSettings(s.copyWith(adAndTrackerBlocker: v)),
          ),
          SwitchListTile(
            title: const Text('Block third-party cookies'),
            value: s.blockThirdPartyCookies,
            onChanged: (v) => state.updateSettings(
              s.copyWith(blockThirdPartyCookies: v),
            ),
          ),
          ListTile(
            title: const Text('Clear cookies & cache (all profiles)'),
            subtitle: const Text(
              'Wipes every profile\u2019s cookies, storage and cache.',
            ),
            trailing: OutlinedButton(
              onPressed: () async {
                final ok = await showDialog<bool>(
                  context: context,
                  builder: (context) => AlertDialog(
                    title: const Text('Clear everything?'),
                    content: const Text(
                      'This signs you out of every site in every profile.',
                    ),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.of(context).pop(false),
                        child: const Text('Cancel'),
                      ),
                      FilledButton(
                        onPressed: () => Navigator.of(context).pop(true),
                        child: const Text('Clear'),
                      ),
                    ],
                  ),
                );
                if (ok == true) await state.clearAllCookiesAndCache();
              },
              child: const Text('Clear'),
            ),
          ),
          ListTile(
            title: const Text('Clear history (all profiles)'),
            trailing: OutlinedButton(
              onPressed: () async {
                final ok = await showDialog<bool>(
                  context: context,
                  builder: (context) => AlertDialog(
                    title: const Text('Clear all history?'),
                    content: const Text(
                      'Deletes history for every profile.',
                    ),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.of(context).pop(false),
                        child: const Text('Cancel'),
                      ),
                      FilledButton(
                        onPressed: () => Navigator.of(context).pop(true),
                        child: const Text('Clear'),
                      ),
                    ],
                  ),
                );
                if (ok == true) await state.clearAllHistory();
              },
              child: const Text('Clear'),
            ),
          ),
          _section('Browser'),
          ListTile(
            title: const Text('Search engine'),
            trailing: DropdownButton<SearchEngine>(
              value: s.searchEngine,
              items: SearchEngine.values
                  .map((e) => DropdownMenuItem(
                        value: e,
                        child: Text(e.label),
                      ))
                  .toList(),
              onChanged: (v) {
                if (v != null) {
                  state.updateSettings(s.copyWith(searchEngine: v));
                }
              },
            ),
          ),
          ListTile(
            title: const Text('Homepage'),
            subtitle: Text(s.homePageUrl),
            onTap: () async {
              final controller =
                  TextEditingController(text: s.homePageUrl);
              final next = await showDialog<String>(
                context: context,
                builder: (context) => AlertDialog(
                  title: const Text('Homepage'),
                  content: TextField(
                    controller: controller,
                    autofocus: true,
                    decoration: const InputDecoration(
                      labelText: 'URL',
                    ),
                  ),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.of(context).pop(),
                      child: const Text('Cancel'),
                    ),
                    FilledButton(
                      onPressed: () =>
                          Navigator.of(context).pop(controller.text.trim()),
                      child: const Text('Save'),
                    ),
                  ],
                ),
              );
              if (next != null && next.isNotEmpty) {
                await state.updateSettings(s.copyWith(homePageUrl: next));
              }
            },
          ),
          SwitchListTile(
            title: const Text('Desktop site by default'),
            value: s.desktopSiteDefault,
            onChanged: (v) =>
                state.updateSettings(s.copyWith(desktopSiteDefault: v)),
          ),
          ListTile(
            title: const Text('Text size'),
            subtitle: Slider(
              value: s.textScale,
              min: 0.5,
              max: 2.0,
              divisions: 15,
              label: '${(s.textScale * 100).round()}%',
              onChanged: (v) =>
                  state.updateSettings(s.copyWith(textScale: v)),
            ),
          ),
          SwitchListTile(
            title: const Text('Show the new-tab page'),
            subtitle: const Text('Otherwise new tabs open your homepage.'),
            value: s.showNewTabPage,
            onChanged: (v) =>
                state.updateSettings(s.copyWith(showNewTabPage: v)),
          ),
          _section('Appearance'),
          SwitchListTile(
            title: const Text('AMOLED black'),
            subtitle: const Text('True-black background.'),
            value: s.amoledBlackTheme,
            onChanged: (v) =>
                state.updateSettings(s.copyWith(amoledBlackTheme: v)),
          ),
          SwitchListTile(
            title: const Text('Address bar at the bottom'),
            value: s.bottomAddressBar,
            onChanged: (v) =>
                state.updateSettings(s.copyWith(bottomAddressBar: v)),
          ),
          _section('Advanced'),
          SwitchListTile(
            title: const Text('Data saver (block images)'),
            value: s.blockImagesDataSaver,
            onChanged: (v) => state.updateSettings(
              s.copyWith(blockImagesDataSaver: v),
            ),
          ),
          SwitchListTile(
            title: const Text('Force dark web pages'),
            value: s.forceDarkWebPages,
            onChanged: (v) =>
                state.updateSettings(s.copyWith(forceDarkWebPages: v)),
          ),
          SwitchListTile(
            title: const Text('Hardware acceleration'),
            value: s.hardwareAcceleration,
            onChanged: (v) => state.updateSettings(
              s.copyWith(hardwareAcceleration: v),
            ),
          ),
          _section('Diagnostics'),
          ListTile(
            title: const Text('Local bridge'),
            subtitle: Text(
              state.bridgePort == 0
                  ? 'Not running'
                  : 'Running on 127.0.0.1:${state.bridgePort}',
            ),
          ),
          if (state.bridgeError.isNotEmpty)
            ListTile(
              title: const Text('Bridge error'),
              subtitle: Text(state.bridgeError),
              trailing: IconButton(
                icon: const Icon(Icons.clear),
                onPressed: state.clearBridgeError,
              ),
            ),
          ListTile(
            title: const Text('Last crash'),
            subtitle: Text(
              CrashReporter.lastCrash == null
                  ? 'None recorded'
                  : '${CrashReporter.lastCrash!.exceptionName}'
                      '${CrashReporter.lastCrash!.message != null ? ': ${CrashReporter.lastCrash!.message}' : ''}'
                      ' — ${formatDateTime(CrashReporter.lastCrash!.atMillis)}',
            ),
            trailing: CrashReporter.lastCrash == null
                ? null
                : IconButton(
                    icon: const Icon(Icons.delete_outline),
                    onPressed: state.clearCrash,
                  ),
          ),
          ListTile(
            title: const Text('Test the bridge'),
            subtitle: Text(
              state.bridgeTestResult.isEmpty
                  ? 'Fetches your public IP through the proxy.'
                  : state.bridgeTestResult,
            ),
            trailing: OutlinedButton(
              onPressed: state.testBridgeConnection,
              child: const Text('Test'),
            ),
          ),
          _section('Data'),
          ListTile(
            title: const Text('Export backup'),
            subtitle: const Text(
              'Servers, profiles and settings as a file (passwords in plain text, by design).',
            ),
            trailing: OutlinedButton.icon(
              icon: const Icon(Icons.share),
              label: const Text('Share'),
              onPressed: () {
                Share.share(
                  state.exportBackup(),
                  subject: 'FastBrowser backup',
                );
              },
            ),
          ),
          ListTile(
            title: const Text('Import backup'),
            subtitle: const Text('Paste a backup you exported earlier.'),
            trailing: OutlinedButton(
              onPressed: () async {
                final controller = TextEditingController();
                final pasted = await showDialog<String>(
                  context: context,
                  builder: (context) => AlertDialog(
                    title: const Text('Import backup'),
                    content: TextField(
                      controller: controller,
                      maxLines: 6,
                      decoration: const InputDecoration(
                        labelText: 'Paste backup JSON here',
                      ),
                    ),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.of(context).pop(),
                        child: const Text('Cancel'),
                      ),
                      FilledButton(
                        onPressed: () => Navigator.of(context)
                            .pop(controller.text.trim()),
                        child: const Text('Import'),
                      ),
                    ],
                  ),
                );
                if (pasted != null && pasted.isNotEmpty) {
                  await state.importBackup(pasted);
                }
              },
              child: const Text('Import'),
            ),
          ),
          ListTile(
            title: const Text('Reset everything'),
            subtitle: const Text(
              'Back to the pre-seeded servers and one default profile.',
            ),
            trailing: OutlinedButton(
              onPressed: () async {
                final ok = await showDialog<bool>(
                  context: context,
                  builder: (context) => AlertDialog(
                    title: const Text('Reset everything?'),
                    content: const Text(
                      'This deletes all servers, profiles, history, bookmarks and downloads you added.',
                    ),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.of(context).pop(false),
                        child: const Text('Cancel'),
                      ),
                      FilledButton(
                        onPressed: () => Navigator.of(context).pop(true),
                        child: const Text('Reset'),
                      ),
                    ],
                  ),
                );
                if (ok == true) await state.resetAll();
              },
              child: const Text('Reset'),
            ),
          ),
          _section('About'),
          const ListTile(
            title: Text('FastBrowser'),
            subtitle: Text('Version 2.0.0 — full Flutter rewrite.\nPackage com.fastbrowser.gologin'),
          ),
          const SizedBox(height: 32),
        ],
      ),
    );
  }

  Widget _section(String title) => Padding(
        padding: const EdgeInsets.only(top: 16, bottom: 4),
        child: Text(
          title,
          style: const TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.w600,
            color: Colors.grey,
          ),
        ),
      );
}
