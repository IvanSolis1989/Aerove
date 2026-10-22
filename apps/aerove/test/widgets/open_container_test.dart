import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/ui/open_container.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/surface.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter/rendering.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';

const _viewSize = Size(800, 600);

void main() {
  testWidgets('glass fade updates semantics when visibility crosses zero', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    final opacity = AnimationController(
      vsync: tester,
      duration: const Duration(milliseconds: 300),
    );
    try {
      await tester.pumpWidget(
        MaterialApp(
          home: LiquidGlassFade(
            opacity: opacity,
            child: Semantics(
              container: true,
              label: 'Fading settings',
              child: const SizedBox(width: 100, height: 100),
            ),
          ),
        ),
      );
      for (final value in [
        0.0,
        0.001,
        0.003,
        0.1,
        0.5,
        1.0,
        0.5,
        0.001,
        0.0,
        0.5,
        0.0,
      ]) {
        opacity.value = value;
        await tester.pump();
        bool includesSettings(SemanticsNode node) {
          if (node.getSemanticsData().label == 'Fading settings') return true;
          var found = false;
          node.visitChildren((child) {
            found = includesSettings(child);
            return !found;
          });
          return found;
        }

        final root =
            tester.binding.pipelineOwner.semanticsOwner!.rootSemanticsNode!;
        expect(includesSettings(root), Color.getAlphaFromOpacity(value) != 0);
        expect(tester.takeException(), isNull);
      }
    } finally {
      try {
        await tester.pumpWidget(const SizedBox.shrink());
      } finally {
        opacity.dispose();
        semantics.dispose();
      }
    }
  });

  for (final quality in LiquidGlassQuality.values) {
    testWidgets('glass $quality keeps its route and transition transparent', (
      tester,
    ) async {
      final capture = GlobalKey();
      await tester.pumpWidget(
        LiquidGlassScope(
          enabled: true,
          quality: quality,
          child: MaterialApp(
            builder: (_, child) => RepaintBoundary(key: capture, child: child),
            home: Scaffold(
              backgroundColor: Colors.transparent,
              body: Center(
                child: SizedBox(
                  width: 160,
                  height: 80,
                  child: OpenContainer<void>(
                    closedColor: Colors.red,
                    closedShape: const RoundedSuperellipseBorder(
                      borderRadius: BorderRadius.all(Radius.circular(24)),
                    ),
                    closedBuilder: (_, open) => TextButton(
                      onPressed: open,
                      child: const Text('Open glass'),
                    ),
                    openBuilder: (_, close) => TextButton(
                      onPressed: close,
                      child: const Text('Close glass'),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      final closedRect = tester.getRect(find.byType(OpenContainer<void>));
      await tester.tap(find.text('Open glass'));
      await tester.pump();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 150));
      final navigator = find.byType(Navigator);
      expect(navigator, paints..path(color: Colors.transparent));
      await tester.pumpAndSettle();
      final route = ModalRoute.of(tester.element(find.text('Close glass')))!;
      expect(route.opaque, isTrue);
      expect(route.barrierColor, isNull);
      expect(navigator, paints..rect(color: Colors.transparent));
      await tester.tap(find.text('Close glass'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 150));
      expect(route.animation!.value, closeTo(0.5, 0.00001));
      final closing = Rect.lerp(
        closedRect,
        Offset.zero & _viewSize,
        Curves.fastOutSlowIn.flipped.transform(route.animation!.value),
      )!;
      await _expectSolid(
        tester,
        capture,
        closing.topLeft + Offset(closing.width * 0.2, closing.height * 0.2),
        Colors.red,
      );
      await tester.pumpAndSettle();
      expect(find.text('Open glass'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('the tile a transition carries keeps Standard body glass', (
    tester,
  ) async {
    await tester.pumpWidget(
      LiquidGlassScope(
        enabled: true,
        quality: LiquidGlassQuality.standard,
        child: MaterialApp(
          home: Scaffold(
            body: Center(
              child: SizedBox(
                width: 160,
                height: 80,
                child: OpenContainer<void>(
                  closedBuilder: (_, open) => TextButton(
                    onPressed: open,
                    child: const Text('Open tile'),
                  ),
                  openBuilder: (_, close) => TextButton(
                    onPressed: close,
                    child: const Text('Close tile'),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    Finder bodies() => find.ancestor(
      of: find.text('Open tile', skipOffstage: false),
      matching: find.byType(LiquidGlassBody),
    );
    expect(bodies(), findsNothing);
    await tester.tap(find.text('Open tile'));
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 150));
    expect(bodies(), findsWidgets);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Close tile'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 150));
    expect(bodies(), findsWidgets);
    await tester.pumpAndSettle();
    expect(bodies(), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('OpenContainer opens, closes, and returns a value', (
    tester,
  ) async {
    String? result;

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(
              width: 160,
              height: 80,
              child: OpenContainer<String>(
                closedBuilder: (_, open) {
                  return FilledButton(
                    onPressed: open,
                    child: const Text('Closed'),
                  );
                },
                openBuilder: (_, close) {
                  return Center(
                    child: FilledButton(
                      onPressed: () => close(returnValue: 'done'),
                      child: const Text('Close'),
                    ),
                  );
                },
                onClosed: (value) {
                  result = value;
                },
              ),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('Closed'));
    await tester.pump(const Duration(milliseconds: 80));
    expect(find.text('Close'), findsOneWidget);
    await tester.pumpAndSettle();

    await tester.tap(find.text('Close'));
    await tester.pump(const Duration(milliseconds: 80));
    await tester.pumpAndSettle();

    expect(result, 'done');
    expect(find.text('Closed'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('morphs the closed shape and color into the open page', (
    tester,
  ) async {
    const closedShape = RoundedSuperellipseBorder(
      borderRadius: BorderRadius.all(Radius.circular(24)),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(
              width: 160,
              height: 80,
              child: OpenContainer<void>(
                closedShape: closedShape,
                closedColor: Colors.red,
                closedBuilder: (_, open) {
                  return GestureDetector(
                    onTap: open,
                    child: const Text('Card'),
                  );
                },
                openBuilder: (_, close) {
                  return GestureDetector(
                    onTap: () => close(),
                    child: const Text('Page'),
                  );
                },
              ),
            ),
          ),
        ),
      ),
    );

    final closedMaterial = tester.widget<Material>(
      find
          .ancestor(of: find.text('Card'), matching: find.byType(Material))
          .first,
    );
    expect(closedMaterial.shape, closedShape);
    final closedRect = tester.getRect(find.byType(OpenContainer<void>));
    final navigator = find.byType(Navigator);

    await tester.tap(find.text('Card'));
    await tester.pump();
    expect(
      navigator,
      paints..path(
        color: Colors.red,
        includes: [closedRect.center],
        excludes: [closedRect.topLeft + const Offset(2, 2)],
      ),
    );

    final page = SurfaceLevel.page.colorOf(
      Theme.of(tester.element(find.text('Card'))).colorScheme,
    );
    Rect at(double progress) =>
        Rect.lerp(closedRect, Offset.zero & _viewSize, progress)!;
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 150));
    final opening = at(Curves.fastOutSlowIn.transform(0.5));
    expect(
      navigator,
      paints..path(
        color: page,
        includes: [opening.centerLeft + const Offset(2, 0)],
        excludes: [opening.centerLeft - const Offset(2, 0)],
      ),
    );

    await tester.pumpAndSettle();
    expect(navigator, isNot(paints..path(color: page)));

    await tester.tap(find.text('Page'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 150));
    final closing = at(Curves.fastOutSlowIn.flipped.transform(0.5));
    expect(
      navigator,
      paints..path(
        color: Colors.red,
        includes: [closing.centerLeft + const Offset(2, 0)],
        excludes: [closing.centerLeft - const Offset(2, 0)],
      ),
    );

    await tester.pumpAndSettle();
    expect(find.text('Page'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('opens by callback and supports interrupted reverse', (
    tester,
  ) async {
    String? result = 'unchanged';

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Align(
            alignment: Alignment.topLeft,
            child: OpenContainer<String>(
              closedBuilder: (_, open) {
                return TextButton(
                  onPressed: open,
                  child: const Text('Open manually'),
                );
              },
              openBuilder: (_, close) {
                return TextButton(
                  onPressed: () => close(),
                  child: const Text('Reverse now'),
                );
              },
              onClosed: (value) {
                result = value;
              },
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('Open manually'));
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    await tester.tap(find.text('Reverse now'));
    await tester.pump(const Duration(milliseconds: 50));
    await tester.pumpAndSettle();
    await tester.pump();

    expect(result, isNull);
    expect(find.text('Reverse now'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  Widget containerApp({
    required CloseContainerBuilder closedBuilder,
    required OpenContainerBuilder<void> openBuilder,
  }) {
    return MaterialApp(
      home: Scaffold(
        body: Center(
          child: SizedBox(
            width: 160,
            height: 80,
            child: OpenContainer<void>(
              closedBuilder: closedBuilder,
              openBuilder: openBuilder,
            ),
          ),
        ),
      ),
    );
  }

  testWidgets('a transition builds the page once and the tile twice', (
    tester,
  ) async {
    var pageBuilds = 0;
    var tileBuilds = 0;
    final page = Builder(
      builder: (context) {
        pageBuilds++;
        return Text('${Theme.of(context).brightness}');
      },
    );

    await tester.pumpWidget(
      containerApp(
        closedBuilder: (_, open) {
          tileBuilds++;
          return TextButton(onPressed: open, child: const Text('Tile'));
        },
        openBuilder: (_, _) => page,
      ),
    );
    tileBuilds = 0;

    await tester.tap(find.text('Tile'));
    await tester.pumpAndSettle();
    expect(pageBuilds, 1);

    Navigator.of(tester.element(find.byWidget(page))).pop();
    await tester.pumpAndSettle();
    expect(pageBuilds, 1);
    expect(tileBuilds, 2);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a transition leaves no animation undisposed', (tester) async {
    var undisposed = 0;
    void track(ObjectEvent event) {
      if (event.object is! CurvedAnimation) {
        return;
      }
      undisposed += event is ObjectCreated ? 1 : -1;
    }

    await tester.pumpWidget(
      containerApp(
        closedBuilder: (_, open) {
          return GestureDetector(onTap: open, child: const Text('Tile'));
        },
        openBuilder: (_, close) {
          return GestureDetector(onTap: close, child: const Text('Page'));
        },
      ),
    );
    FlutterMemoryAllocations.instance.addListener(track);
    addTearDown(() => FlutterMemoryAllocations.instance.removeListener(track));

    await tester.tap(find.text('Tile'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Page'));
    await tester.pumpAndSettle();
    expect(undisposed, 0);
  });

  testWidgets('the page takes no taps until it starts to show', (tester) async {
    var taps = 0;

    await tester.pumpWidget(
      containerApp(
        closedBuilder: (_, open) {
          return TextButton(onPressed: open, child: const Text('Tile'));
        },
        openBuilder: (_, _) {
          return Align(
            alignment: Alignment.topLeft,
            child: TextButton(
              onPressed: () => taps++,
              child: const Text('Page'),
            ),
          );
        },
      ),
    );

    await tester.tap(find.text('Tile'));
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 30));
    await tester.tap(find.text('Page'), warnIfMissed: false);
    expect(taps, 0);

    await tester.pumpAndSettle();
    await tester.tap(find.text('Page'));
    expect(taps, 1);
  });
}

Future<void> _expectSolid(
  WidgetTester tester,
  GlobalKey capture,
  Offset point,
  Color color,
) async {
  final boundary =
      capture.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  late ByteData bytes;
  late int width;
  await tester.runAsync(() async {
    final image = boundary.toImageSync();
    width = image.width;
    bytes = (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!;
    image.dispose();
  });
  final expected = color.toARGB32();
  for (var y = point.dy.floor(); y < point.dy.floor() + 4; y++) {
    for (var x = point.dx.floor(); x < point.dx.floor() + 4; x++) {
      final index = (y * width + x) * 4;
      expect(
        [
          bytes.getUint8(index),
          bytes.getUint8(index + 1),
          bytes.getUint8(index + 2),
          bytes.getUint8(index + 3),
        ],
        [
          (expected >> 16) & 255,
          (expected >> 8) & 255,
          expected & 255,
          (expected >> 24) & 255,
        ],
      );
    }
  }
}
