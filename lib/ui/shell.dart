// Shell — bottom navigation (Home / Browser / Profiles / Servers /
// Settings) over an IndexedStack so the browser's WebViews stay alive
// across tab switches. Back: browser handles overlays/omnibox/WebView
// back first; otherwise back goes to Home; back on Home exits the app.
// App detach triggers the best-effort exit wipe (incognito + profiles
// with "clear on exit").

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app_state.dart';
import 'browser_screen.dart';
import 'home_screen.dart';
import 'profiles_screen.dart';
import 'servers_screen.dart';
import 'settings_screen.dart';

class Shell extends StatefulWidget {
  final AppState state;
  const Shell({super.key, required this.state});

  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> with WidgetsBindingObserver {
  int _index = 0;
  final _browserController = BrowserScreenController();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    widget.state.events.listen((message) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(message)),
      );
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.detached) {
      widget.state.onAppDetached();
    }
  }

  Future<void> _onPopInvoked(bool didPop) async {
    if (didPop) return;
    if (_index == 1) {
      final handled = await _browserController.handleBack?.call() ?? false;
      if (!handled && mounted) setState(() => _index = 0);
    } else if (_index != 0 && mounted) {
      setState(() => _index = 0);
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.state;
    return PopScope(
      canPop: _index == 0,
      onPopInvokedWithResult: (didPop, _) => _onPopInvoked(didPop),
      child: Scaffold(
        body: IndexedStack(
          index: _index,
          children: [
            HomeScreen(
              state: state,
              onOpenBrowser: () => setState(() => _index = 1),
              onOpenServers: () => setState(() => _index = 3),
              onOpenSettings: () => setState(() => _index = 4),
            ),
            BrowserScreen(
              state: state,
              controller: _browserController,
              onOpenSettings: () => setState(() => _index = 4),
              onOpenServers: () => setState(() => _index = 3),
              onGoHome: () => setState(() => _index = 0),
            ),
            ProfilesScreen(
              state: state,
              onOpenBrowser: () => setState(() => _index = 1),
            ),
            ServersScreen(state: state),
            SettingsScreen(state: state),
          ],
        ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: _index,
          onDestinationSelected: (i) => setState(() => _index = i),
          destinations: const [
            NavigationDestination(
              icon: Icon(Icons.home_outlined),
              selectedIcon: Icon(Icons.home),
              label: 'Home',
            ),
            NavigationDestination(
              icon: Icon(Icons.public_outlined),
              selectedIcon: Icon(Icons.public),
              label: 'Browser',
            ),
            NavigationDestination(
              icon: Icon(Icons.group_outlined),
              selectedIcon: Icon(Icons.group),
              label: 'Profiles',
            ),
            NavigationDestination(
              icon: Icon(Icons.dns_outlined),
              selectedIcon: Icon(Icons.dns),
              label: 'Servers',
            ),
            NavigationDestination(
              icon: Icon(Icons.settings_outlined),
              selectedIcon: Icon(Icons.settings),
              label: 'Settings',
            ),
          ],
        ),
      ),
    );
  }
}

/// Exits the app (used by the Home tab's back handling via PopScope).
void exitApp() => SystemNavigator.pop();
