// Onboarding — first-run guided setup, three short steps, plain words.

import 'package:flutter/material.dart';

import '../app_state.dart';

class OnboardingScreen extends StatefulWidget {
  final AppState state;
  const OnboardingScreen({super.key, required this.state});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  final _controller = PageController();
  int _page = 0;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final server = widget.state.activeServer;
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: PageView(
                controller: _controller,
                onPageChanged: (i) => setState(() => _page = i),
                children: const [
                  _Step(
                    icon: Icons.public,
                    title: 'Browse privately',
                    body:
                        'FastBrowser sends your traffic through your own servers, so sites see the server — not you. Two servers are already set up for you.',
                  ),
                  _Step(
                    icon: Icons.group,
                    title: 'Profiles keep things separate',
                    body:
                        'Each profile has its own cookies, logins and history. Work, personal and incognito stay apart — switch profiles from the Profiles tab.',
                  ),
                  _Step(
                    icon: Icons.shield,
                    title: 'The kill-switch protects you',
                    body:
                        'If no server is active, FastBrowser blocks traffic instead of leaking it. You can turn the kill-switch off in Settings if you ever need to browse directly.',
                  ),
                ],
              ),
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: List.generate(
                3,
                (i) => Container(
                  margin: const EdgeInsets.symmetric(horizontal: 4),
                  width: 8,
                  height: 8,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: i == _page
                        ? Theme.of(context).colorScheme.primary
                        : Colors.grey,
                  ),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (server != null)
                    Text(
                      'Ready to connect through ${server.name}.',
                      textAlign: TextAlign.center,
                      style: const TextStyle(color: Colors.grey),
                    ),
                  const SizedBox(height: 12),
                  FilledButton(
                    onPressed: _page < 2
                        ? () => _controller.nextPage(
                              duration: const Duration(milliseconds: 250),
                              curve: Curves.easeInOut,
                            )
                        : () => widget.state.completeOnboarding(),
                    child: Text(_page < 2 ? 'Next' : 'Get started'),
                  ),
                  TextButton(
                    onPressed: () => widget.state.completeOnboarding(),
                    child: const Text('Skip'),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Step extends StatelessWidget {
  final IconData icon;
  final String title;
  final String body;
  const _Step({required this.icon, required this.title, required this.body});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(32),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            icon,
            size: 72,
            color: Theme.of(context).colorScheme.primary,
          ),
          const SizedBox(height: 24),
          Text(
            title,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 12),
          Text(
            body,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 16),
          ),
        ],
      ),
    );
  }
}
