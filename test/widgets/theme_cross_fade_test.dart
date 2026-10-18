import 'dart:ui' as ui show Image;

import 'package:fl_clash/ui/theme_cross_fade.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _red = Color(0xFFFF0000);
const _blue = Color(0xFF0000FF);
const _green = Color(0xFF00FF00);

void main() {
  final shotKey = GlobalKey();
  late ValueNotifier<Color> background;

  setUp(() => background = ValueNotifier(_red));
  tearDown(() => background.dispose());

  Future<void> pumpApp(
    WidgetTester tester, {
    double opacity = 1,
    ui.Image? Function(ui.Image? Function() capture)? captureSnapshot,
  }) async {
    tester.view.physicalSize = const Size(40, 40);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      RepaintBoundary(
        key: shotKey,
        child: ValueListenableBuilder(
          valueListenable: background,
          builder: (_, color, _) => MaterialApp(
            debugShowCheckedModeBanner: false,
            theme: ThemeData(scaffoldBackgroundColor: color),
            themeAnimationStyle: AnimationStyle.noAnimation,
            builder: (_, child) =>
                ThemeCrossFade(captureSnapshot: captureSnapshot, child: child!),
            home: Builder(
              builder: (context) => ColoredBox(
                color: Theme.of(
                  context,
                ).scaffoldBackgroundColor.withValues(alpha: opacity),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<Color> pixel(WidgetTester tester) async {
    final boundary =
        shotKey.currentContext!.findRenderObject()! as RenderRepaintBoundary;
    late Color color;
    await tester.runAsync(() async {
      final image = await boundary.toImage();
      final data = (await image.toByteData())!;
      image.dispose();
      color = Color.fromARGB(
        data.getUint8(3),
        data.getUint8(0),
        data.getUint8(1),
        data.getUint8(2),
      );
    });
    return color;
  }

  List<double> channels(Color color) => [color.a, color.r, color.g, color.b];

  Matcher isColor(Color expected) => isA<Color>().having(
    channels,
    'channels',
    pairwiseCompare<double, double>(
      channels(expected),
      (expected, actual) => (expected - actual).abs() <= 2 / 255,
      'within 2/255 of',
    ),
  );

  testWidgets('shows the last frame over a new theme until the fade ends', (
    tester,
  ) async {
    await pumpApp(tester);

    background.value = _blue;
    await tester.pump();
    expect(await pixel(tester), isColor(_red));

    await tester.pump(const Duration(milliseconds: 100));
    expect(await pixel(tester), isColor(Color.lerp(_red, _blue, 0.5)!));

    await tester.pumpAndSettle();
    expect(await pixel(tester), isColor(_blue));
  });

  testWidgets('keeps a translucent pixel as opaque as it was', (tester) async {
    await pumpApp(tester, opacity: 0.5);
    final alpha = (await pixel(tester)).a;

    background.value = _blue;
    for (var frame = 0; frame < 14; frame++) {
      await tester.pump(const Duration(milliseconds: 16));
      expect(
        (await pixel(tester)).a,
        moreOrLessEquals(alpha, epsilon: 1 / 255),
      );
    }
  });

  testWidgets('a second change mid-fade starts from what is on screen', (
    tester,
  ) async {
    await pumpApp(tester);
    background.value = _blue;
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    final onScreen = await pixel(tester);

    background.value = _green;
    await tester.pump();
    expect(await pixel(tester), isColor(onScreen));

    await tester.pumpAndSettle();
    expect(await pixel(tester), isColor(_green));
  });

  testWidgets('switches at once when animations are disabled', (tester) async {
    tester.platformDispatcher.accessibilityFeaturesTestValue =
        const FakeAccessibilityFeatures(disableAnimations: true);
    addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
    await pumpApp(tester);

    background.value = _blue;
    await tester.pump();

    expect(await pixel(tester), isColor(_blue));
    expect(tester.hasRunningAnimations, isFalse);
  });

  testWidgets('inactive freezes the visible fade and resumes without recapture', (
    tester,
  ) async {
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    addTearDown(() => tester.binding.handleAppLifecycleStateChanged(
      AppLifecycleState.resumed,
    ));
    var captures = 0;
    await pumpApp(tester, captureSnapshot: (capture) {
      captures++;
      return capture();
    });
    final mountedState = tester.state(find.byType(ThemeCrossFade));
    background.value = _blue;
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 60));
    final visibleFrame = await pixel(tester);
    expect(captures, 1);
    expect(tester.hasRunningAnimations, isTrue);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    await tester.pump();
    expect(await pixel(tester), visibleFrame);
    await tester.pump(const Duration(minutes: 3));
    expect(await pixel(tester), visibleFrame);
    expect(tester.hasRunningAnimations, isFalse);
    expect(tester.binding.hasScheduledFrame, isFalse);
    expect(captures, 1);
    expect(tester.state(find.byType(ThemeCrossFade)), same(mountedState));
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(await pixel(tester), visibleFrame);
    expect(tester.hasRunningAnimations, isTrue);
    expect(captures, 1);
    await tester.pumpAndSettle();
    expect(await pixel(tester), isColor(_blue));
    background.value = _green;
    await tester.pump();
    expect(captures, 2);
    expect(await pixel(tester), isColor(_blue));
    await tester.pumpAndSettle();
    expect(await pixel(tester), isColor(_green));
    await tester.pumpWidget(const SizedBox.shrink());
    expect(tester.takeException(), isNull);
  });

  for (final lifecycle in [
    AppLifecycleState.hidden,
    AppLifecycleState.paused,
    AppLifecycleState.detached,
  ]) {
    testWidgets(
      'drops the fade and refuses background snapshots ($lifecycle)',
      (tester) async {
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.resumed,
        );
        addTearDown(() {
          tester.binding.handleAppLifecycleStateChanged(
            AppLifecycleState.resumed,
          );
        });
        var captures = 0;
        await pumpApp(
          tester,
          captureSnapshot: (capture) {
            captures++;
            return capture();
          },
        );
        final mountedState = tester.state(find.byType(ThemeCrossFade));
        background.value = _blue;
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 60));
        expect(captures, 1);
        expect(tester.hasRunningAnimations, isTrue);

        tester.binding.handleAppLifecycleStateChanged(lifecycle);
        expect(tester.hasRunningAnimations, isFalse);
        await tester.pump();
        expect(await pixel(tester), isColor(_blue));
        background.value = _green;
        await tester.pump();
        await tester.pump(const Duration(minutes: 3));
        expect(captures, 1);
        expect(tester.hasRunningAnimations, isFalse);
        expect(tester.binding.hasScheduledFrame, isFalse);
        expect(
          await pixel(tester),
          isColor(_blue),
          reason: 'Suspended framework rendering retains its last frame.',
        );
        expect(tester.state(find.byType(ThemeCrossFade)), same(mountedState));

        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.resumed,
        );
        await tester.pump();
        expect(captures, 1);
        expect(await pixel(tester), isColor(_green));
        expect(tester.hasRunningAnimations, isFalse);
        background.value = _red;
        await tester.pump();
        expect(captures, 2);
        expect(await pixel(tester), isColor(_green));
        await tester.pumpAndSettle();
        expect(await pixel(tester), isColor(_red));
        await tester.pumpWidget(const SizedBox.shrink());
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'a failed readback drops an active fade and paints the new theme',
    (tester) async {
      var failCapture = false;
      final failure = StateError('snapshot readback failed');
      await pumpApp(
        tester,
        captureSnapshot: (capture) {
          if (failCapture) throw failure;
          return capture();
        },
      );
      background.value = _blue;
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 60));
      expect(tester.hasRunningAnimations, isTrue);

      failCapture = true;
      background.value = _green;
      await tester.pump();
      expect(tester.takeException(), same(failure));
      expect(await pixel(tester), isColor(_green));
      expect(tester.hasRunningAnimations, isFalse);
      await tester.pumpAndSettle();
      expect(await pixel(tester), isColor(_green));

      failCapture = false;
      background.value = _red;
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(await pixel(tester), isColor(_red));
    },
  );
}
