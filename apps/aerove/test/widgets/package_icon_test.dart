import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/plugins/app.dart';
import 'package:fl_clash/widgets/icon.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('$packageName/app');
  late Directory icons;

  setUp(() async {
    icons = await Directory.systemTemp.createTemp('package_icon_test');
    App().clearPackageIconCache();
    packageIconApp = App();
  });

  tearDown(() async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    App().clearPackageIconCache();
    packageIconApp = null;
    imageCache.clear();
    await icons.delete(recursive: true);
  });

  testWidgets('a package icon decodes at its display size, never above the '
      '128 physical pixels the platform exports', (tester) async {
    final iconPath = '${icons.path}/app.png';
    await tester.runAsync(
      () async => File(iconPath).writeAsBytes(await _squarePng(128)),
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (_) async => iconPath);

    await tester.pumpWidget(
      const MediaQuery(
        data: MediaQueryData(devicePixelRatio: 3),
        child: Directionality(
          textDirection: TextDirection.ltr,
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              PackageIcon(packageName: 'app', size: 24),
              PackageIcon(packageName: 'app', size: 48),
            ],
          ),
        ),
      ),
    );
    await tester.pump();
    expect(find.byType(RawImage), findsNWidgets(2));

    List<int?> widths() => [
      for (final raw in tester.widgetList<RawImage>(find.byType(RawImage)))
        raw.image?.width,
    ];
    for (var attempt = 0; attempt < 100 && widths().contains(null); attempt++) {
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 20)),
      );
      await tester.pump();
    }

    expect(widths(), [72, 128]);
  });
}

Future<Uint8List> _squarePng(int size) async {
  final recorder = ui.PictureRecorder();
  Canvas(recorder).drawRect(
    Rect.fromLTWH(0, 0, size.toDouble(), size.toDouble()),
    Paint()..color = const Color(0xFF2196F3),
  );
  final image = await recorder.endRecording().toImage(size, size);
  final data = await image.toByteData(format: ui.ImageByteFormat.png);
  return data!.buffer.asUint8List();
}
