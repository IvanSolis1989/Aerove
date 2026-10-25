import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter_test/flutter_test.dart';

String _read(String path) => File(path).readAsStringSync();

String _color(String file, String name) => RegExp(
  '<color name="$name">(#[0-9A-F]{6})</color>',
).firstMatch(_read('android/app/src/main/res/$file'))!.group(1)!;

String _attribute(String source, String element, String attribute) {
  final tag = RegExp('<$element\\b[^>]*>').firstMatch(source)!.group(0)!;
  return RegExp('$attribute="([^"]+)"').firstMatch(tag)!.group(1)!;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('both application variants declare standard and round icons', () {
    for (final variant in ['main', 'debug']) {
      final manifest = _read('android/app/src/$variant/AndroidManifest.xml');
      expect(
        _attribute(manifest, 'application', 'android:icon'),
        '@mipmap/ic_launcher',
      );
      expect(
        _attribute(manifest, 'application', 'android:roundIcon'),
        '@mipmap/ic_launcher_round',
      );
    }
  });

  test('system vector icons share the canonical white ribbon', () {
    final glyph = _read('assets_source/images/icon/glyph.svg');
    final path = _attribute(glyph, 'path', 'd');
    for (final file in [
      'android/service/src/main/res/drawable/ic.xml',
      'android/service/src/main/res/drawable/ic_service.xml',
      'android/app/src/main/res/drawable/ic_launcher_monochrome.xml',
    ]) {
      final vector = _read(file);
      expect(RegExp(r'<path\b').allMatches(vector), hasLength(1));
      expect(
        _attribute(vector, 'path', 'android:pathData'),
        path,
        reason: file,
      );
      expect(
        _attribute(vector, 'path', 'android:fillColor'),
        '#FFFFFF',
        reason: file,
      );
    }
  });

  test('full launch background and system bars share the dark brand tile', () {
    final brand = _color(
      'values/ic_launcher_background.xml',
      'ic_launcher_background',
    );
    expect(brand, '#0E1115');
    expect(_color('values/colors.xml', 'launch_background'), brand);
    expect(_color('values-night/colors.xml', 'launch_background'), brand);
    for (final variant in [
      'values',
      'values-night',
      'values-v27',
      'values-night-v27',
    ]) {
      expect(
        _read('android/app/src/main/res/$variant/styles.xml'),
        contains(
          '<item name="android:windowBackground">@color/launch_background',
        ),
        reason: variant,
      );
      final style = _read('android/app/src/main/res/$variant/styles.xml');
      for (final bar in ['statusBarColor', 'navigationBarColor']) {
        expect(
          style,
          contains('<item name="android:$bar">@color/launch_background</item>'),
          reason: variant,
        );
      }
      expect(
        style,
        contains('<item name="android:windowLightStatusBar">false</item>'),
        reason: variant,
      );
    }
    for (final variant in ['values-v27', 'values-night-v27']) {
      expect(
        _read('android/app/src/main/res/$variant/styles.xml'),
        contains('tools:targetApi="s">@color/launch_background</item>'),
        reason: variant,
      );
    }
  });

  test(
    'Lite launches on a full white window with dark system bar icons',
    () async {
      final style = _read('../aerove-lite/app/src/main/res/values/styles.xml');
      expect(
        style,
        contains('<color name="launcher_background">#FFFFFF</color>'),
      );
      for (final region in [
        'windowBackground',
        'statusBarColor',
        'navigationBarColor',
        'windowSplashScreenBackground',
        'windowSplashScreenIconBackgroundColor',
      ]) {
        expect(
          style,
          contains('<item name="android:$region">@color/launcher_background'),
        );
      }
      expect(
        style,
        contains('<item name="android:windowLightStatusBar">true</item>'),
      );
      expect(
        style,
        contains('<item name="android:windowLightNavigationBar">true</item>'),
      );
      final codec = await ui.instantiateImageCodec(
        await File(
          '../aerove-lite/app/src/main/res/drawable-nodpi/aerove_lite_splash_art.png',
        ).readAsBytes(),
      );
      final frame = await codec.getNextFrame();
      final pixels = (await frame.image.toByteData())!;
      expect(pixels.getUint8(3), 0);
      frame.image.dispose();
      codec.dispose();
    },
  );

  test('standard and round adaptive icons resolve current artwork', () {
    for (final name in ['ic_launcher', 'ic_launcher_round']) {
      final adaptive = _read(
        'android/app/src/main/res/mipmap-anydpi-v26/$name.xml',
      );
      expect(
        _attribute(adaptive, 'foreground', 'android:drawable'),
        '@drawable/ic_launcher_foreground',
      );
      expect(
        _attribute(adaptive, 'monochrome', 'android:drawable'),
        '@drawable/ic_launcher_monochrome',
      );
    }
  });

  test('standard and round raster icons retain all density variants', () async {
    const sizes = {
      'mdpi': 48,
      'hdpi': 72,
      'xhdpi': 96,
      'xxhdpi': 144,
      'xxxhdpi': 192,
    };
    for (final MapEntry(key: density, value: size) in sizes.entries) {
      for (final name in ['ic_launcher', 'ic_launcher_round']) {
        final file = File('android/app/src/main/res/mipmap-$density/$name.png');
        final codec = await ui.instantiateImageCodec(await file.readAsBytes());
        final frame = await codec.getNextFrame();
        expect((frame.image.width, frame.image.height), (size, size));
        final pixels = (await frame.image.toByteData())!;
        for (final offset in [
          0,
          size - 1,
          size * (size - 1),
          size * size - 1,
        ]) {
          expect(pixels.getUint8(offset * 4 + 3), 0, reason: file.path);
        }
        frame.image.dispose();
        codec.dispose();
      }
    }
  });

  test('bundled fallback icon matches the Windows application icon', () {
    expect(
      File('assets/images/icon.ico').readAsBytesSync(),
      orderedEquals(
        File('windows/runner/resources/app_icon.ico').readAsBytesSync(),
      ),
    );
  });

  test('published website marks use canonical packaged assets', () {
    final site = _read('../../website/index.html');
    expect(
      RegExp(r'src="\{\{up\}\}icon\.png"').allMatches(site),
      hasLength(2),
    );
    final build = _read('../../tooling/website/build_site.sh');
    expect(
      build,
      contains(
        r'cp "$source_root/apps/aerove/assets/images/icon.png" "$work/icon.png"',
      ),
    );
    expect(
      build,
      contains(
        r'cp "$source_root/apps/aerove/assets_source/images/icon/glyph.svg" "$work/favicon.svg"',
      ),
    );
  });

  test('store icon and TV banner retain their required dimensions', () async {
    const sizes = {
      'android/app/src/main/ic_launcher-playstore.png': (512, 512),
      'android/app/src/main/res/mipmap-xhdpi/ic_banner.png': (320, 180),
    };
    for (final MapEntry(key: path, value: size) in sizes.entries) {
      final codec = await ui.instantiateImageCodec(
        await File(path).readAsBytes(),
      );
      final frame = await codec.getNextFrame();
      expect((frame.image.width, frame.image.height), size, reason: path);
      frame.image.dispose();
      codec.dispose();
    }
  });
}
