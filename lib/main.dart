// FastBrowser 2.0.1 — entry point.
// Crash capture is installed before anything else runs; the app state
// (store, bridge, proxy bind) initialises behind a loading screen; the
// onboarding gate shows first-run setup until it is completed.
//
// Performance note (v2.0.1 jank fix): v2.0.0 wrapped the whole MaterialApp
// in a Consumer<AppState>, so EVERY state change — including every 1% of
// WebView load progress and every download chunk — rebuilt the entire
// app: all five shell screens plus the Offstage stack of native WebViews.
// Now the root only rebuilds for the two things it actually needs: the
// theme flag (amoled on/off) and the onboarding gate. Each screen refreshes
// itself on state changes (ListenableBuilder in the shell, the browser
// screen's own coalesced listener).

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'app_state.dart';
import 'crash.dart';
import 'theme.dart';
import 'ui/onboarding_screen.dart';
import 'ui/shell.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await CrashReporter.install();
  runApp(const FastBrowserApp());
}

class FastBrowserApp extends StatefulWidget {
  const FastBrowserApp({super.key});

  @override
  State<FastBrowserApp> createState() => _FastBrowserAppState();
}

class _FastBrowserAppState extends State<FastBrowserApp> {
  late final AppState _state = AppState();
  late final Future<void> _init = _state.init();

  @override
  void dispose() {
    _state.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider<AppState>.value(
      value: _state,
      // Only the theme flag rebuilds MaterialApp — everything else the
      // app state notifies about is handled by the screens themselves.
      child: Selector<AppState, bool>(
        selector: (_, state) => state.settings.amoledBlackTheme,
        builder: (_, amoledBlack, __) => MaterialApp(
          title: 'FastBrowser',
          debugShowCheckedModeBanner: false,
          theme: buildTheme(amoledBlack: amoledBlack),
          home: _HomeGate(state: _state, init: _init),
        ),
      ),
    );
  }
}

/// Init loading screen + onboarding gate. Rebuilds only when
/// onboardingCompleted flips; the shell and onboarding screens refresh
/// themselves from AppState after that.
class _HomeGate extends StatelessWidget {
  final AppState state;
  final Future<void> init;

  const _HomeGate({required this.state, required this.init});

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<void>(
      future: init,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Scaffold(
            body: Center(child: CircularProgressIndicator()),
          );
        }
        if (snapshot.hasError) {
          return Scaffold(
            body: Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Text(
                  'FastBrowser could not start: ${snapshot.error}',
                  textAlign: TextAlign.center,
                ),
              ),
            ),
          );
        }
        return Selector<AppState, bool>(
          selector: (_, s) => s.onboardingCompleted,
          builder: (_, done, __) => done
              ? Shell(state: state)
              : OnboardingScreen(state: state),
        );
      },
    );
  }
}
