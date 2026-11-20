// FastBrowser 2.0.0 — entry point.
// Crash capture is installed before anything else runs; the app state
// (store, bridge, proxy bind) initialises behind a loading screen; the
// onboarding gate shows first-run setup until it is completed.

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
      child: Consumer<AppState>(
        builder: (context, state, _) => MaterialApp(
          title: 'FastBrowser',
          debugShowCheckedModeBanner: false,
          theme: buildTheme(amoledBlack: state.settings.amoledBlackTheme),
          home: FutureBuilder<void>(
            future: _init,
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
              if (!state.onboardingCompleted) {
                return OnboardingScreen(state: state);
              }
              return Shell(state: state);
            },
          ),
        ),
      ),
    );
  }
}
