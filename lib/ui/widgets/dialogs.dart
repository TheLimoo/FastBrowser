// Server + profile editor dialogs (shared by the Servers and Profiles
// screens). The server editor includes the password field (editable for
// when auth returns) and the language-override field — the field the old
// app stored but nothing read; in v2 it is wired into the anti-leak
// script (navigator.language / languages).

import 'package:flutter/material.dart';

import '../../models.dart';

Future<ServerNode?> showServerEditor(
  BuildContext context, {
  ServerNode? existing,
}) {
  return showDialog<ServerNode>(
    context: context,
    builder: (context) => _ServerEditorDialog(existing: existing),
  );
}

class _ServerEditorDialog extends StatefulWidget {
  final ServerNode? existing;
  const _ServerEditorDialog({this.existing});

  @override
  State<_ServerEditorDialog> createState() => _ServerEditorDialogState();
}

class _ServerEditorDialogState extends State<_ServerEditorDialog> {
  late final TextEditingController _name;
  late final TextEditingController _host;
  late final TextEditingController _port;
  late final TextEditingController _wsPath;
  late final TextEditingController _country;
  late final TextEditingController _timezone;
  late final TextEditingController _language;
  late final TextEditingController _username;
  late final TextEditingController _password;
  ProxyProtocol _protocol = ProxyProtocol.SOCKS5;
  ProxyTransport _transport = ProxyTransport.DIRECT;
  bool _showPassword = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    _name = TextEditingController(text: e?.name ?? '');
    _host = TextEditingController(text: e?.host ?? '');
    _port = TextEditingController(text: (e?.port ?? 10800).toString());
    _wsPath = TextEditingController(text: e?.wsPath ?? '/ws');
    _country = TextEditingController(text: e?.countryCode ?? '');
    _timezone = TextEditingController(text: e?.timezoneOverride ?? '');
    _language = TextEditingController(text: e?.languageOverride ?? '');
    _username = TextEditingController(text: e?.username ?? '');
    _password = TextEditingController(text: e?.password ?? '');
    _protocol = e?.protocol ?? ProxyProtocol.SOCKS5;
    _transport = e?.transport ?? ProxyTransport.DIRECT;
  }

  @override
  void dispose() {
    for (final c in [
      _name,
      _host,
      _port,
      _wsPath,
      _country,
      _timezone,
      _language,
      _username,
      _password,
    ]) {
      c.dispose();
    }
    super.dispose();
  }

  void _save() {
    final host = _host.text.trim();
    final port = int.tryParse(_port.text.trim());
    if (host.isEmpty) {
      setState(() => _error = 'Enter the server address.');
      return;
    }
    if (port == null || port < 1 || port > 65535) {
      setState(() => _error = 'Enter a port between 1 and 65535.');
      return;
    }
    final id = widget.existing?.id ??
        'server_${DateTime.now().millisecondsSinceEpoch}';
    Navigator.of(context).pop(ServerNode(
      id: id,
      name: _name.text.trim().isEmpty ? host : _name.text.trim(),
      host: host,
      port: port,
      protocol: _protocol,
      username: _username.text.trim(),
      password: _password.text,
      countryCode:
          _country.text.trim().isEmpty ? 'AUTO' : _country.text.trim(),
      timezoneOverride: _timezone.text.trim(),
      languageOverride: _language.text.trim(),
      transport: _transport,
      wsPath: _wsPath.text.trim().isEmpty ? '/ws' : _wsPath.text.trim(),
    ));
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.existing == null ? 'Add server' : 'Edit server'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _name,
              decoration: const InputDecoration(labelText: 'Name'),
            ),
            TextField(
              controller: _host,
              decoration: const InputDecoration(
                labelText: 'Host',
                hintText: 'example.com or 1.2.3.4',
              ),
            ),
            TextField(
              controller: _port,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Port'),
            ),
            DropdownButtonFormField<ProxyProtocol>(
              initialValue: _protocol,
              decoration: const InputDecoration(labelText: 'Protocol'),
              items: ProxyProtocol.values
                  .map((p) => DropdownMenuItem(
                        value: p,
                        child: Text(p.name),
                      ))
                  .toList(),
              onChanged: (v) => setState(() => _protocol = v!),
            ),
            DropdownButtonFormField<ProxyTransport>(
              initialValue: _transport,
              decoration: const InputDecoration(labelText: 'Connection'),
              items: const [
                DropdownMenuItem(
                  value: ProxyTransport.DIRECT,
                  child: Text('Direct (SOCKS5 / HTTP)'),
                ),
                DropdownMenuItem(
                  value: ProxyTransport.WEBSOCKET,
                  child: Text('WebSocket (through Cloudflare)'),
                ),
              ],
              onChanged: (v) => setState(() => _transport = v!),
            ),
            if (_transport == ProxyTransport.WEBSOCKET)
              TextField(
                controller: _wsPath,
                decoration: const InputDecoration(
                  labelText: 'WebSocket path',
                  hintText: '/ws',
                ),
              ),
            TextField(
              controller: _country,
              decoration: const InputDecoration(
                labelText: 'Country code',
                hintText: 'SE, US, FR… (AUTO if blank)',
              ),
            ),
            TextField(
              controller: _timezone,
              decoration: const InputDecoration(
                labelText: 'Timezone',
                hintText: 'Europe/Stockholm (blank = device)',
              ),
            ),
            TextField(
              controller: _language,
              decoration: const InputDecoration(
                labelText: 'Language',
                hintText: 'en-US (blank = device)',
              ),
            ),
            TextField(
              controller: _username,
              decoration: const InputDecoration(
                labelText: 'Username',
                hintText: 'Leave blank — servers are no-auth now',
              ),
            ),
            TextField(
              controller: _password,
              obscureText: !_showPassword,
              decoration: InputDecoration(
                labelText: 'Password',
                hintText: 'Leave blank — servers are no-auth now',
                suffixIcon: IconButton(
                  icon: Icon(
                    _showPassword ? Icons.visibility_off : Icons.visibility,
                  ),
                  onPressed: () =>
                      setState(() => _showPassword = !_showPassword),
                ),
              ),
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  _error!,
                  style: TextStyle(
                    color: Theme.of(context).colorScheme.error,
                  ),
                ),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(onPressed: _save, child: const Text('Save')),
      ],
    );
  }
}

Future<BrowserProfile?> showProfileEditor(
  BuildContext context, {
  BrowserProfile? existing,
  required List<ServerNode> servers,
}) {
  return showDialog<BrowserProfile>(
    context: context,
    builder: (context) => _ProfileEditorDialog(
      existing: existing,
      servers: servers,
    ),
  );
}

class _ProfileEditorDialog extends StatefulWidget {
  final BrowserProfile? existing;
  final List<ServerNode> servers;
  const _ProfileEditorDialog({this.existing, required this.servers});

  @override
  State<_ProfileEditorDialog> createState() => _ProfileEditorDialogState();
}

class _ProfileEditorDialogState extends State<_ProfileEditorDialog> {
  late final TextEditingController _name;
  late final TextEditingController _customUa;
  UserAgentPreset _preset = UserAgentPreset.ANDROID_CHROME;
  String? _assignedServerId;
  bool _blockWebRtc = true;
  bool _canvasNoise = false;
  bool _clearOnExit = false;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    _name = TextEditingController(text: e?.name ?? '');
    _customUa = TextEditingController(text: e?.customUserAgent ?? '');
    _preset = e?.userAgentPreset ?? UserAgentPreset.ANDROID_CHROME;
    _assignedServerId = e?.assignedServerId;
    _blockWebRtc = e?.blockWebRtcLeaks ?? true;
    _canvasNoise = e?.canvasNoiseEnabled ?? false;
    _clearOnExit = e?.clearCookiesOnExit ?? false;
  }

  @override
  void dispose() {
    _name.dispose();
    _customUa.dispose();
    super.dispose();
  }

  void _save() {
    final name = _name.text.trim();
    if (name.isEmpty) return;
    final id = widget.existing?.id ??
        'profile_${DateTime.now().millisecondsSinceEpoch}';
    Navigator.of(context).pop(BrowserProfile(
      id: id,
      name: name,
      assignedServerId: _assignedServerId,
      userAgentPreset: _preset,
      customUserAgent: _customUa.text.trim(),
      blockWebRtcLeaks: _blockWebRtc,
      canvasNoiseEnabled: _canvasNoise,
      clearCookiesOnExit: _clearOnExit,
    ));
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.existing == null ? 'New profile' : 'Edit profile'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _name,
              decoration: const InputDecoration(labelText: 'Profile name'),
            ),
            DropdownButtonFormField<UserAgentPreset>(
              initialValue: _preset,
              decoration: const InputDecoration(labelText: 'Device'),
              items: UserAgentPreset.values
                  .map((p) => DropdownMenuItem(
                        value: p,
                        child: Text(p.label),
                      ))
                  .toList(),
              onChanged: (v) => setState(() => _preset = v!),
            ),
            TextField(
              controller: _customUa,
              decoration: const InputDecoration(
                labelText: 'Custom user agent (optional)',
                hintText: 'Leave blank to use the device above',
              ),
            ),
            DropdownButtonFormField<String?>(
              initialValue: _assignedServerId,
              decoration: const InputDecoration(
                labelText: 'Always use this server',
              ),
              items: [
                const DropdownMenuItem<String?>(
                  value: null,
                  child: Text('Use the active server'),
                ),
                ...widget.servers.map((s) => DropdownMenuItem<String?>(
                      value: s.id,
                      child: Text(s.name),
                    )),
              ],
              onChanged: (v) => setState(() => _assignedServerId = v),
            ),
            SwitchListTile(
              title: const Text('Block WebRTC leaks'),
              subtitle: const Text('Stops sites seeing your real IP'),
              value: _blockWebRtc,
              onChanged: (v) => setState(() => _blockWebRtc = v),
            ),
            SwitchListTile(
              title: const Text('Canvas noise'),
              subtitle: const Text('Slightly changes canvas fingerprints'),
              value: _canvasNoise,
              onChanged: (v) => setState(() => _canvasNoise = v),
            ),
            SwitchListTile(
              title: const Text('Clear cookies when the app closes'),
              value: _clearOnExit,
              onChanged: (v) => setState(() => _clearOnExit = v),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(onPressed: _save, child: const Text('Save')),
      ],
    );
  }
}
