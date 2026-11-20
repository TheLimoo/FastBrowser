// Browser screen — one-row toolbar, one always-composed omnibox,
// one native WebView per tab (kept alive in an Offstage Stack — removing
// a PlatformView from the tree destroys it), ⋮ menu with all 17 items,
// find-in-page, tab switcher, new-tab page, blocked/error overlays.
//
// The omnibox is ONE TextField, never swapped for a view/edit pair
// (the v1.0.6 instant-close bug came from swapping two fields). On focus
// it shows the full URL with all text selected; unfocused it shows the
// clean domain.
//
// Back order (same as v1.0.6): find bar → omnibox editor → WebView back
// → (shell: switch to Home). Exposed through [BrowserScreenController].

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:share_plus/share_plus.dart';

import '../app_state.dart';
import '../models.dart';
import '../utils.dart';
import '../webview/webview_host.dart';
import 'downloads_screen.dart';
import 'widgets/sheets.dart';

class BrowserScreenController {
  Future<bool> Function()? handleBack;
}

class BrowserScreen extends StatefulWidget {
  final AppState state;
  final BrowserScreenController controller;
  final VoidCallback onOpenSettings;
  final VoidCallback onOpenServers;
  final VoidCallback onGoHome;

  const BrowserScreen({
    super.key,
    required this.state,
    required this.controller,
    required this.onOpenSettings,
    required this.onOpenServers,
    required this.onGoHome,
  });

  @override
  State<BrowserScreen> createState() => BrowserScreenState();
}

class BrowserScreenState extends State<BrowserScreen> {
  final Map<String, WebViewHandle> _handles = {};
  final Map<String, String> _loadedUrl = {};
  final Map<String, int> _reloadNonce = {};
  final Map<String, bool> _desktopMode = {};
  String _settingsSignature = '';

  final _omniboxController = TextEditingController();
  final _omniboxFocus = FocusNode();
  final _findController = TextEditingController();
  final _findFocus = FocusNode();
  bool _findVisible = false;
  String? _lastOmniboxTabId;

  AppState get _state => widget.state;

  @override
  void initState() {
    super.initState();
    widget.controller.handleBack = _handleBack;
    _state.addListener(_onStateChanged);
    _omniboxFocus.addListener(_onOmniboxFocusChanged);
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncWebViews());
  }

  @override
  void dispose() {
    widget.controller.handleBack = null;
    _state.removeListener(_onStateChanged);
    _omniboxFocus.removeListener(_onOmniboxFocusChanged);
    _omniboxController.dispose();
    _omniboxFocus.dispose();
    _findController.dispose();
    _findFocus.dispose();
    super.dispose();
  }

  void _onStateChanged() {
    if (!mounted) return;
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncWebViews());
    _syncOmnibox();
  }

  // ----- WebView sync -----------------------------------------------------

  BrowserProfile _profileForTab(BrowserTab tab) {
    if (tab.profileId == 'incognito') {
      return const BrowserProfile(
        id: 'incognito',
        name: 'Incognito',
        clearCookiesOnExit: true,
      );
    }
    for (final p in _state.profiles) {
      if (p.id == tab.profileId) return p;
    }
    return _state.activeProfile ??
        const BrowserProfile(id: 'default_profile', name: 'Default Profile');
  }

  WebViewConfig _configForTab(BrowserTab tab) {
    final server = _state.activeServer;
    return WebViewConfig(
      profile: _profileForTab(tab),
      settings: _state.settings,
      timezoneOverride: server?.timezoneOverride ?? '',
      languageOverride: server?.languageOverride ?? '',
      desktopMode: tab.desktopMode,
      initialUrl: tab.url,
    );
  }

  String _settingsSignatureOf(AppSettings s) =>
      '${s.textScale}|${s.blockImagesDataSaver}|${s.hardwareAcceleration}|${s.forceDarkWebPages}|${s.blockThirdPartyCookies}';

  Future<void> _syncWebViews() async {
    if (!mounted) return;
    final sig = _settingsSignatureOf(_state.settings);
    final settingsChanged = sig != _settingsSignature;
    _settingsSignature = sig;
    for (final tab in _state.tabs) {
      final handle = _handles[tab.id];
      if (handle == null) continue;
      try {
        if (tab.desktopMode != _desktopMode[tab.id] || settingsChanged) {
          _desktopMode[tab.id] = tab.desktopMode;
          await handle.applySettings(_configForTab(tab).toApplySettings());
        }
        if (tab.reloadNonce != (_reloadNonce[tab.id] ?? 0)) {
          _reloadNonce[tab.id] = tab.reloadNonce;
          await handle.reload();
        } else if (tab.url.isNotEmpty && tab.url != _loadedUrl[tab.id]) {
          _loadedUrl[tab.id] = tab.url;
          await handle.loadUrl(tab.url);
        }
      } catch (_) {}
    }
    final active = _state.activeTab;
    if (active != null) {
      final handle = _handles[active.id];
      _state.cookieProvider =
          handle == null ? null : (url) => handle.getCookie(url);
    } else {
      _state.cookieProvider = null;
    }
  }

  void _onWebViewCreated(BrowserTab tab, WebViewHandle handle) {
    final old = _handles[tab.id];
    if (old != null && old != handle) unawaited(old.dispose());
    _handles[tab.id] = handle;
    _loadedUrl[tab.id] = tab.url;
    _reloadNonce[tab.id] = tab.reloadNonce;
    _desktopMode[tab.id] = tab.desktopMode;
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncWebViews());
  }

  void _onWebViewEvent(BrowserTab tab, WebViewEvent event) {
    switch (event.type) {
      case 'pageStarted':
        final url = event.data['url'] as String? ?? '';
        _loadedUrl[tab.id] = url;
        _state.onTabPageStarted(url, tab.id);
      case 'pageFinished':
        final url = event.data['url'] as String? ?? '';
        final title = event.data['title'] as String? ?? '';
        _loadedUrl[tab.id] = url;
        _state.onTabPageFinished(url, title, tab.id);
      case 'progress':
        final value = event.data['value'] as int? ?? 0;
        _state.onTabProgress(value, tab.id);
      case 'state':
        _state.onTabNavState(
          event.data['canGoBack'] as bool? ?? false,
          event.data['canGoForward'] as bool? ?? false,
          tab.id,
        );
      case 'error':
        _state.onTabError(
          event.data['message'] as String? ?? 'Failed to load this page.',
          tab.id,
        );
      case 'download':
        final url = event.data['url'] as String? ?? '';
        if (url.isEmpty) break;
        final handle = _handles[tab.id];
        final profile = _profileForTab(tab);
        final ua = profile.customUserAgent.isNotEmpty
            ? profile.customUserAgent
            : (tab.desktopMode
                ? UserAgentPreset.DESKTOP_WINDOWS.uaString
                : profile.userAgentPreset.uaString);
        unawaited(_state.startDownload(
          url: url,
          userAgent: ua,
          contentDisposition:
              event.data['contentDisposition'] as String? ?? '',
          mimeType: event.data['mimeType'] as String? ??
              'application/octet-stream',
          profileId: tab.profileId,
          getCookie: handle == null ? null : handle.getCookie,
          fileNameOverride: event.data['fileName'] as String?,
        ));
    }
  }

  // ----- omnibox ----------------------------------------------------------

  void _onOmniboxFocusChanged() {
    final tab = _state.activeTab;
    if (tab == null) return;
    if (_omniboxFocus.hasFocus) {
      _omniboxController.text = tab.url;
      _omniboxController.selection = TextSelection(
        baseOffset: 0,
        extentOffset: _omniboxController.text.length,
      );
    } else {
      _omniboxController.text = cleanDomain(tab.url);
    }
  }

  void _syncOmnibox() {
    final tab = _state.activeTab;
    if (tab == null) return;
    if (_lastOmniboxTabId != tab.id) {
      _lastOmniboxTabId = tab.id;
      if (!_omniboxFocus.hasFocus) {
        _omniboxController.text = cleanDomain(tab.url);
      }
      return;
    }
    if (!_omniboxFocus.hasFocus &&
        _omniboxController.text != cleanDomain(tab.url)) {
      _omniboxController.text = cleanDomain(tab.url);
    }
  }

  void _submitOmnibox() {
    final tab = _state.activeTab;
    if (tab == null) return;
    final url = resolveInputToUrl(
      _omniboxController.text,
      _state.settings.searchEngine,
      _state.settings.homePageUrl,
    );
    _omniboxFocus.unfocus();
    _state.navigateActiveTab(url);
  }

  Future<bool> _handleBack() async {
    if (_findVisible) {
      setState(() {
        _findVisible = false;
        _findController.clear();
      });
      final handle = _handles[_state.activeTabId];
      await handle?.clearMatches();
      return true;
    }
    if (_omniboxFocus.hasFocus) {
      _omniboxFocus.unfocus();
      return true;
    }
    final tab = _state.activeTab;
    if (tab != null && tab.canGoBack) {
      await _handles[tab.id]?.goBack();
      return true;
    }
    return false;
  }

  // ----- menu actions -------------------------------------------------------

  void _openTabSwitcher() {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) => _TabSwitcherSheet(
        state: _state,
        onCloseAll: () {
          Navigator.of(context).pop();
          _state.closeAllTabs();
        },
        onNewTab: () {
          Navigator.of(context).pop();
          _state.newTab();
        },
      ),
    );
  }

  Future<void> _menuAction(String value) async {
    final tab = _state.activeTab;
    final handle = tab == null ? null : _handles[tab.id];
    switch (value) {
      case 'server':
        showServerSwitcherSheet(
          context,
          _state,
          onAddServer: widget.onOpenServers,
        );
      case 'new_tab':
        _state.newTab();
      case 'new_incognito':
        _state.newIncognitoTab();
      case 'history':
        if (tab != null) {
          showHistorySheet(context, _state, tab.profileId);
        }
      case 'downloads':
        await Navigator.of(context).push(
          MaterialPageRoute<void>(
            builder: (context) => DownloadsScreen(state: _state),
          ),
        );
      case 'bookmarks':
        showBookmarksSheet(context, _state);
      case 'recent_tabs':
        _openTabSwitcher();
      case 'share':
        if (tab != null && tab.url.isNotEmpty) {
          await Share.share(tab.url);
        }
      case 'copy_link':
        if (tab != null && tab.url.isNotEmpty) {
          await Clipboard.setData(ClipboardData(text: tab.url));
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('Link copied')),
            );
          }
        }
      case 'find':
        setState(() => _findVisible = true);
        _findFocus.requestFocus();
      case 'translate':
        if (tab != null && tab.url.isNotEmpty) {
          _state.navigateActiveTab(
            'https://translate.google.com/translate?sl=auto&tl=en&u=${Uri.encodeComponent(tab.url)}',
          );
        }
      case 'desktop':
        _state.toggleDesktopMode();
      case 'reload':
        _state.reloadActiveTab();
      case 'forward':
        await handle?.goForward();
      case 'ip_check':
        _state.navigateActiveTab('https://browserleaks.com/ip');
      case 'home':
        _state.navigateActiveTab(_state.settings.homePageUrl);
      case 'settings':
        widget.onOpenSettings();
    }
  }

  // ----- build --------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final tab = _state.activeTab;
    final toolbar = _Toolbar(
      state: _state,
      omniboxController: _omniboxController,
      omniboxFocus: _omniboxFocus,
      onSubmitted: _submitOmnibox,
      onTabSwitcher: _openTabSwitcher,
      onMenuAction: _menuAction,
      onBack: () async {
        final t = _state.activeTab;
        if (t != null) await _handles[t.id]?.goBack();
      },
    );
    final body = Stack(
      children: [
        Stack(
          children: _state.tabs.map((t) {
            final version = _state.profileConfigVersions[t.profileId] ?? 0;
            return Offstage(
              offstage: t.id != tab?.id,
              child: FastBrowserWebView(
                key: ValueKey('tabview_${t.id}'),
                config: _configForTab(t),
                configVersion: version,
                onCreated: (handle) => _onWebViewCreated(t, handle),
                onEvent: (event) => _onWebViewEvent(t, event),
              ),
            );
          }).toList(),
        ),
        if (tab != null && tab.url.isEmpty && !_state.proxyBlocked)
          _NewTabPage(state: _state, onOpenBrowser: () {}),
        if (_state.proxyBlocked) _BlockedOverlay(state: _state),
        if (tab != null && tab.loadError != null)
          _ErrorOverlay(tab: tab, state: _state),
      ],
    );
    final progress = tab != null && tab.isLoading
        ? LinearProgressIndicator(
            value: tab.progress > 0 ? tab.progress / 100 : null,
          )
        : const SizedBox(height: 4);
    final findBar = _findVisible
        ? _FindBar(
            controller: _findController,
            focusNode: _findFocus,
            onFind: (query) {
              final h = _handles[_state.activeTabId];
              if (query.isNotEmpty) h?.findAll(query);
            },
            onNext: (forward) =>
                _handles[_state.activeTabId]?.findNext(forward),
            onClose: () async {
              setState(() {
                _findVisible = false;
                _findController.clear();
              });
              await _handles[_state.activeTabId]?.clearMatches();
            },
          )
        : const SizedBox.shrink();
    final columnChildren = _state.settings.bottomAddressBar
        ? <Widget>[
            Expanded(child: body),
            progress,
            findBar,
            toolbar,
          ]
        : <Widget>[
            toolbar,
            progress,
            Expanded(child: body),
            findBar,
          ];
    return Scaffold(
      body: SafeArea(child: Column(children: columnChildren)),
    );
  }
}

class _Toolbar extends StatelessWidget {
  final AppState state;
  final TextEditingController omniboxController;
  final FocusNode omniboxFocus;
  final VoidCallback onSubmitted;
  final VoidCallback onTabSwitcher;
  final Future<void> Function(String value) onMenuAction;
  final Future<void> Function() onBack;

  const _Toolbar({
    required this.state,
    required this.omniboxController,
    required this.omniboxFocus,
    required this.onSubmitted,
    required this.onTabSwitcher,
    required this.onMenuAction,
    required this.onBack,
  });

  @override
  Widget build(BuildContext context) {
    final tab = state.activeTab;
    final handleLoading = tab?.isLoading ?? false;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        border: Border(
          bottom: BorderSide(color: Theme.of(context).colorScheme.outline),
        ),
      ),
      child: Row(
        children: [
          IconButton(
            icon: const Icon(Icons.arrow_back),
            tooltip: 'Back',
            onPressed: (tab?.canGoBack ?? false) ? onBack : null,
          ),
          Expanded(
            child: TextField(
              controller: omniboxController,
              focusNode: omniboxFocus,
              textInputAction: TextInputAction.go,
              onSubmitted: (_) => onSubmitted(),
              decoration: InputDecoration(
                hintText: 'Search or type a web address',
                prefixIcon: Icon(
                  (tab?.profileId == 'incognito')
                      ? Icons.visibility_off
                      : Icons.lock_outline,
                  size: 18,
                ),
                suffixIcon: handleLoading
                    ? IconButton(
                        icon: const Icon(Icons.close),
                        tooltip: 'Stop',
                        onPressed: state.stopLoadingActiveTab,
                      )
                    : IconButton(
                        icon: const Icon(Icons.refresh),
                        tooltip: 'Reload',
                        onPressed: state.reloadActiveTab,
                      ),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(24),
                ),
                contentPadding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 8,
                ),
                isDense: true,
              ),
            ),
          ),
          IconButton(
            icon: Stack(
              alignment: Alignment.center,
              children: [
                const Icon(Icons.tab),
                Text(
                  '${state.tabs.length}',
                  style: const TextStyle(fontSize: 10),
                ),
              ],
            ),
            tooltip: 'Tabs',
            onPressed: onTabSwitcher,
          ),
          PopupMenuButton<String>(
            icon: const Icon(Icons.more_vert),
            onSelected: onMenuAction,
            itemBuilder: (context) {
              final t = state.activeTab;
              return [
                const PopupMenuItem(value: 'server', child: Text('Server')),
                const PopupMenuItem(value: 'new_tab', child: Text('New tab')),
                const PopupMenuItem(
                  value: 'new_incognito',
                  child: Text('New Incognito tab'),
                ),
                const PopupMenuItem(value: 'history', child: Text('History')),
                const PopupMenuItem(
                  value: 'downloads',
                  child: Text('Downloads'),
                ),
                const PopupMenuItem(
                  value: 'bookmarks',
                  child: Text('Bookmarks'),
                ),
                const PopupMenuItem(
                  value: 'recent_tabs',
                  child: Text('Recent tabs'),
                ),
                const PopupMenuItem(value: 'share', child: Text('Share…')),
                const PopupMenuItem(
                  value: 'copy_link',
                  child: Text('Copy link'),
                ),
                const PopupMenuItem(
                  value: 'find',
                  child: Text('Find in page'),
                ),
                const PopupMenuItem(
                  value: 'translate',
                  child: Text('Translate…'),
                ),
                PopupMenuItem(
                  value: 'desktop',
                  child: Text(
                    'Desktop site${(t?.desktopMode ?? false) ? ' ✓' : ''}',
                  ),
                ),
                const PopupMenuItem(value: 'reload', child: Text('Reload')),
                PopupMenuItem(
                  value: 'forward',
                  enabled: t?.canGoForward ?? false,
                  child: const Text('Forward'),
                ),
                const PopupMenuItem(
                  value: 'ip_check',
                  child: Text('Quick IP check'),
                ),
                const PopupMenuItem(value: 'home', child: Text('Home')),
                const PopupMenuItem(
                  value: 'settings',
                  child: Text('Settings'),
                ),
              ];
            },
          ),
        ],
      ),
    );
  }
}

class _FindBar extends StatelessWidget {
  final TextEditingController controller;
  final FocusNode focusNode;
  final void Function(String query) onFind;
  final void Function(bool forward) onNext;
  final VoidCallback onClose;

  const _FindBar({
    required this.controller,
    required this.focusNode,
    required this.onFind,
    required this.onNext,
    required this.onClose,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
      color: Theme.of(context).colorScheme.surface,
      child: Row(
        children: [
          Expanded(
            child: TextField(
              controller: controller,
              focusNode: focusNode,
              decoration: const InputDecoration(
                hintText: 'Find in page',
                isDense: true,
              ),
              onSubmitted: onFind,
              onChanged: onFind,
            ),
          ),
          IconButton(
            icon: const Icon(Icons.keyboard_arrow_up),
            onPressed: () => onNext(false),
          ),
          IconButton(
            icon: const Icon(Icons.keyboard_arrow_down),
            onPressed: () => onNext(true),
          ),
          IconButton(
            icon: const Icon(Icons.close),
            onPressed: onClose,
          ),
        ],
      ),
    );
  }
}

class _NewTabPage extends StatelessWidget {
  final AppState state;
  final VoidCallback onOpenBrowser;
  const _NewTabPage({required this.state, required this.onOpenBrowser});

  @override
  Widget build(BuildContext context) {
    final profile = state.activeProfile;
    final recent =
        profile == null ? <String>[] : state.historyFor(profile.id);
    return Container(
      color: Theme.of(context).scaffoldBackgroundColor,
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const SizedBox(height: 24),
          const Text(
            'FastBrowser',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 28, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 8),
          const Text(
            'Type an address above, or pick a server below.',
            textAlign: TextAlign.center,
            style: TextStyle(color: Colors.grey),
          ),
          const SizedBox(height: 24),
          const Text(
            'Servers — tap one to switch',
            style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 8),
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
                  latency?.rttMs == null
                      ? 'Tap to switch'
                      : '${latency!.rttMs} ms${latency.stale ? ' (stale)' : ''} · Tap to switch',
                ),
                trailing: active
                    ? Icon(
                        Icons.check_circle,
                        color: Theme.of(context).colorScheme.primary,
                      )
                    : null,
                onTap: () => state.setActiveServer(server),
              ),
            );
          }),
          const SizedBox(height: 24),
          if (recent.isNotEmpty) ...[
            const Text(
              'Recent pages',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 8),
            ...recent.take(8).map(
              (url) => ListTile(
                leading: const Icon(Icons.history),
                title: Text(
                  cleanDomain(url),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                onTap: () => state.navigateActiveTab(url),
              ),
            ),
          ],
          const SizedBox(height: 16),
          ListTile(
            leading: const Icon(Icons.speed),
            title: const Text('Check my IP'),
            onTap: () =>
                state.navigateActiveTab('https://browserleaks.com/ip'),
          ),
        ],
      ),
    );
  }
}

class _BlockedOverlay extends StatelessWidget {
  final AppState state;
  const _BlockedOverlay({required this.state});

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Theme.of(context).scaffoldBackgroundColor,
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.block, size: 64),
              const SizedBox(height: 16),
              const Text(
                'Browsing blocked',
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 8),
              const Text(
                'The proxy override is not available on this device, so the kill-switch is blocking all traffic to keep you safe.',
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: () async {
                  await state.updateSettings(
                    state.settings.copyWith(killSwitchEnabled: false),
                  );
                },
                child: const Text('Turn off kill-switch to browse directly'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ErrorOverlay extends StatelessWidget {
  final BrowserTab tab;
  final AppState state;
  const _ErrorOverlay({required this.tab, required this.state});

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Theme.of(context).scaffoldBackgroundColor,
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.cloud_off, size: 64),
              const SizedBox(height: 16),
              const Text(
                'Couldn\u2019t load this page',
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 8),
              Text(
                tab.loadError ?? 'Unknown error.',
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 16),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  FilledButton(
                    onPressed: state.reloadActiveTab,
                    child: const Text('Retry'),
                  ),
                  const SizedBox(width: 12),
                  OutlinedButton(
                    onPressed: () => state.clearTabError(tab.id),
                    child: const Text('Dismiss'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _TabSwitcherSheet extends StatelessWidget {
  final AppState state;
  final VoidCallback onCloseAll;
  final VoidCallback onNewTab;
  const _TabSwitcherSheet({
    required this.state,
    required this.onCloseAll,
    required this.onNewTab,
  });

  @override
  Widget build(BuildContext context) {
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
                    '${state.tabs.length} tab${state.tabs.length == 1 ? '' : 's'}',
                    style: const TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                TextButton.icon(
                  icon: const Icon(Icons.add),
                  label: const Text('New tab'),
                  onPressed: onNewTab,
                ),
                TextButton.icon(
                  icon: const Icon(Icons.close),
                  label: const Text('Close all'),
                  onPressed: onCloseAll,
                ),
              ],
            ),
            const SizedBox(height: 8),
            Flexible(
              child: ListView.builder(
                shrinkWrap: true,
                itemCount: state.tabs.length,
                itemBuilder: (context, i) {
                  final tab = state.tabs[i];
                  final active = tab.id == state.activeTabId;
                  return ListTile(
                    leading: Icon(
                      state.isIncognitoTab(tab)
                          ? Icons.visibility_off
                          : Icons.tab,
                    ),
                    title: Text(
                      (state.isIncognitoTab(tab) ? '🔒 Incognito · ' : '') +
                          (tab.title.isEmpty ? 'New Tab' : tab.title),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    subtitle: Text(
                      cleanDomain(tab.url),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    trailing: IconButton(
                      icon: const Icon(Icons.close),
                      tooltip: 'Close tab',
                      onPressed: () {
                        state.closeTab(tab.id);
                        if (state.tabs.isEmpty) {
                          Navigator.of(context).pop();
                        }
                      },
                    ),
                    selected: active,
                    onTap: () {
                      state.selectTab(tab.id);
                      Navigator.of(context).pop();
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
