import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter_test/flutter_test.dart';

String _androidAttribute(String source, String element, String attribute) {
  final elementTag = RegExp('<$element\\b[^>]*>').firstMatch(source)!.group(0)!;
  return RegExp(
    'android:$attribute="([^"]+)"',
  ).firstMatch(elementTag)!.group(1)!;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('TV launcher icons meet density-specific minimum sizes', () async {
    const expectedSizes = {
      'mdpi': 80,
      'hdpi': 120,
      'xhdpi': 160,
      'xxhdpi': 240,
      'xxxhdpi': 320,
    };

    for (final MapEntry(key: density, value: size) in expectedSizes.entries) {
      final file = File(
        'android/app/src/main/res/'
        'mipmap-television-$density/ic_launcher.png',
      );
      expect(file.existsSync(), isTrue, reason: 'missing ${file.path}');

      final codec = await ui.instantiateImageCodec(await file.readAsBytes());
      final frame = await codec.getNextFrame();
      expect(
        (frame.image.width, frame.image.height),
        (size, size),
        reason: file.path,
      );
      frame.image.dispose();
      codec.dispose();
    }
  });

  test('TV adaptive launcher icon stays centered in the safe zone', () async {
    final adaptiveIcon = File(
      'android/app/src/main/res/'
      'mipmap-television-anydpi-v26/ic_launcher.xml',
    ).readAsStringSync();
    expect(
      _androidAttribute(adaptiveIcon, 'foreground', 'drawable'),
      '@drawable/ic_launcher_foreground_tv',
    );
    expect(
      _androidAttribute(adaptiveIcon, 'background', 'drawable'),
      '@color/ic_launcher_background',
    );

    final foreground = File(
      'android/app/src/main/res/drawable/'
      'ic_launcher_foreground_tv.xml',
    ).readAsStringSync();
    final insets = [
      for (final side in ['Left', 'Top', 'Right', 'Bottom'])
        double.parse(
          _androidAttribute(
            foreground,
            'inset',
            'inset$side',
          ).replaceAll('%', ''),
        ) / 100,
    ];
    final bitmap = _androidAttribute(foreground, 'bitmap', 'src');
    expect(bitmap, startsWith('@drawable/'));
    final codec = await ui.instantiateImageCodec(
      await File(
        'android/app/src/main/res/drawable-nodpi/'
        '${bitmap.substring('@drawable/'.length)}.png',
      ).readAsBytes(),
    );
    final frame = await codec.getNextFrame();
    final image = frame.image;
    final pixels = (await image.toByteData())!;
    final contentWidth = 108 * (1 - insets[0] - insets[2]);
    final contentHeight = 108 * (1 - insets[1] - insets[3]);
    var maxRadiusSquared = 0.0;
    var left = image.width;
    var top = image.height;
    var right = 0;
    var bottom = 0;
    for (var y = 0; y < image.height; y++) {
      for (var x = 0; x < image.width; x++) {
        if (pixels.getUint8((y * image.width + x) * 4 + 3) < 16) {
          continue;
        }
        final dx =
            108 * insets[0] + (x + 0.5) / image.width * contentWidth - 54;
        final dy =
            108 * insets[1] + (y + 0.5) / image.height * contentHeight - 54;
        final radiusSquared = dx * dx + dy * dy;
        if (radiusSquared > maxRadiusSquared) {
          maxRadiusSquared = radiusSquared;
        }
        if (x < left) left = x;
        if (y < top) top = y;
        if (x + 1 > right) right = x + 1;
        if (y + 1 > bottom) bottom = y + 1;
      }
    }
    expect(right, greaterThan(left), reason: 'foreground must be visible');
    expect(
      maxRadiusSquared,
      lessThanOrEqualTo(33 * 33),
      reason: 'visible foreground must fit the 66dp adaptive icon safe circle',
    );
    final transformedBounds = ui.Rect.fromLTRB(
      108 * insets[0] + left / image.width * contentWidth,
      108 * insets[1] + top / image.height * contentHeight,
      108 * insets[0] + right / image.width * contentWidth,
      108 * insets[1] + bottom / image.height * contentHeight,
    );
    const safeZone = ui.Rect.fromLTWH(18, 18, 72, 72);

    expect(transformedBounds.left, greaterThanOrEqualTo(safeZone.left));
    expect(transformedBounds.top, greaterThanOrEqualTo(safeZone.top));
    expect(transformedBounds.right, lessThanOrEqualTo(safeZone.right));
    expect(transformedBounds.bottom, lessThanOrEqualTo(safeZone.bottom));
    expect(transformedBounds.center.dx, closeTo(safeZone.center.dx, 2));
    expect(transformedBounds.center.dy, closeTo(safeZone.center.dy, 2));
    image.dispose();
    codec.dispose();
  });
}
