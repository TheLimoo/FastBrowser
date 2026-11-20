import 'package:fastbrowser/adblock.dart';
import 'package:fastbrowser/anti_leak.dart';
import 'package:fastbrowser/models.dart';
import 'package:fastbrowser/utils.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('cleanDomain', () {
    test('strips scheme, www and path', () {
      expect(cleanDomain('https://www.example.com/foo?x=1'), 'example.com');
      expect(cleanDomain('http://sub.site.org/'), 'sub.site.org');
      expect(cleanDomain(''), '');
    });
  });

  group('resolveInputToUrl', () {
    test('blank input goes to the homepage', () {
      expect(
        resolveInputToUrl('  ', SearchEngine.GOOGLE, 'https://home.example'),
        'https://home.example',
      );
    });
    test('bare domain gets https://', () {
      expect(
        resolveInputToUrl('example.com', SearchEngine.GOOGLE, 'https://home'),
        'https://example.com',
      );
    });
    test('full URL passes through', () {
      expect(
        resolveInputToUrl(
          'https://example.com/a?b=1',
          SearchEngine.GOOGLE,
          'https://home',
        ),
        'https://example.com/a?b=1',
      );
    });
    test('plain words become a search', () {
      expect(
        resolveInputToUrl(
          'how to make tea',
          SearchEngine.DUCKDUCKGO,
          'https://home',
        ),
        'https://duckduckgo.com/?q=how%20to%20make%20tea',
      );
    });
  });

  group('AdBlockFilter', () {
    test('exact and subdomain matches block', () {
      expect(AdBlockFilter.isBlockedDomain('doubleclick.net'), isTrue);
      expect(AdBlockFilter.isBlockedDomain('ads.doubleclick.net'), isTrue);
      expect(
        AdBlockFilter.isBlockedDomain('connect.facebook.net'),
        isTrue,
      );
    });
    test('unrelated domains pass', () {
      expect(AdBlockFilter.isBlockedDomain('example.com'), isFalse);
      expect(AdBlockFilter.isBlockedDomain('notdoubleclick.net'), isFalse);
    });
  });

  group('buildAntiLeakScript', () {
    test('hides webdriver and sets platform per preset', () {
      final script = buildAntiLeakScript(
        profileId: 'p1',
        uaPresetName: 'DESKTOP_WINDOWS',
      );
      expect(script, contains("defineProp(Navigator.prototype, 'webdriver', false)"));
      expect(script, contains("'Win32'"));
      expect(script, contains('WebRTC disabled'));
      expect(script, contains('enumerateDevices'));
    });

    test('timezone and language overrides are injected', () {
      final script = buildAntiLeakScript(
        profileId: 'p1',
        uaPresetName: 'ANDROID_CHROME',
        timezone: 'Europe/Stockholm',
        language: 'en-US',
      );
      expect(script, contains("res.timeZone = 'Europe/Stockholm'"));
      expect(script, contains("defineProp(Navigator.prototype, 'language', 'en-US')"));
      expect(script, contains("'Linux armv8l'"));
    });

    test('canvas noise can be turned off', () {
      final withNoise = buildAntiLeakScript(
        profileId: 'p1',
        uaPresetName: 'ANDROID_CHROME',
      );
      final withoutNoise = buildAntiLeakScript(
        profileId: 'p1',
        uaPresetName: 'ANDROID_CHROME',
        canvasNoiseEnabled: false,
      );
      expect(withNoise, contains('toDataURL'));
      expect(withoutNoise, isNot(contains('toDataURL')));
    });
  });
}
