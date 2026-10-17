import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/style.dart';
import 'package:fl_clash/views/dashboard/widgets/card.dart';
import 'package:fl_clash/views/dashboard/widgets/traffic.dart';
import 'package:fl_clash/views/proxies/group_tabs.dart';
import 'package:fl_clash/widgets/aurora.dart' show SeededAuroraWallpaper;
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

class _Groups extends StatefulWidget {
  const _Groups();

  @override
  State<_Groups> createState() => _GroupsState();
}

class _GroupsState extends State<_Groups> with SingleTickerProviderStateMixin {
  late final _controller = TabController(length: 2, vsync: this);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => GroupTabs(
    controller: _controller,
    groups: const [
      Group(type: GroupType.Selector, name: 'Route A'),
      Group(type: GroupType.Selector, name: 'Route B'),
    ],
  );
}

void main() {
  for (final brightness in Brightness.values) {
    for (final quality in LiquidGlassQuality.values) {
      testWidgets('dashboard inner tiles transmit the backdrop on '
          '${brightness.name} ${quality.name}', (tester) async {
        tester.view.physicalSize = const Size(400, 600);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final key = GlobalKey();
        Future<List<int>> render(
          bool aurora,
          Color backdrop, {
          bool wallpaper = false,
          bool glass = true,
        }) async {
          await tester.pumpWidget(
            TestApp(
              wrapInProviderScope: true,
              locale: const Locale('en'),
              theme: ThemeData(colorScheme: auroraColorScheme(brightness)),
              child: RepaintBoundary(
                key: key,
                child: AuroraAppearance(
                  enabled: aurora,
                  child: LiquidGlassScope(
                    enabled: glass,
                    quality: quality,
                    child: LiquidGlassHost(
                      enabled: true,
                      child: Stack(
                        fit: StackFit.expand,
                        children: [
                          if (wallpaper)
                            SeededAuroraWallpaper(
                              colors: const [Color(0xFF65BDD2), Color(0xFFC589CA)],
                              seed: 271828,
                              brightness: brightness,
                            )
                          else
                            ColoredBox(color: backdrop),
                          Padding(
                            padding: const EdgeInsets.all(16),
                            child: DashboardDensityScope(
                              density: DashboardDensity.mobile,
                              scale: 1,
                              child: Column(
                                children: [
                                  const NetworkSpeedCard(),
                                  const SizedBox(height: 16),
                                  Builder(
                                    builder: (context) => DashboardConnectedButton(
                                      selected: true,
                                      first: true,
                                      last: true,
                                      outer: DashboardCorner.mobileBlock,
                                      inner: DashboardCorner.tab,
                                      background: DashboardColors.of(context).tone,
                                      onTap: () {},
                                      semanticLabel: 'Selected route',
                                      builder: (foreground) => Padding(
                                        padding: const EdgeInsets.all(16),
                                        child: Text(
                                          'Selected route',
                                          style: TextStyle(color: foreground),
                                        ),
                                      ),
                                    ),
                                  ),
                                  const SizedBox(height: 16),
                                  const _Groups(),
                                ],
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          );
          await tester.pumpAndSettle();
          final context = tester.element(find.byType(NetworkSpeedCard));
          final colors = DashboardColors.of(context);
          expect(colors.tone.a, glass ? lessThan(0.2) : 1.0);
          expect(colors.tone2.a, glass ? lessThan(0.2) : 1.0);
          expect(colors.track.a, glass ? lessThan(0.2) : 1.0);
          final selectedText = tester.widget<Text>(find.text('Selected route'));
          final scheme = Theme.of(context).colorScheme;
          expect(selectedText.style!.color,
              glass ? scheme.onSurface : scheme.onPrimary);
          final tabStyle = tester.widget<AnimatedDefaultTextStyle>(
            find.ancestor(
              of: find.text('Route A'),
              matching: find.byType(AnimatedDefaultTextStyle),
            ).first,
          );
          expect(tabStyle.style.color,
              glass ? scheme.onSurface : scheme.onPrimary);
          if (glass) {
            expect(find.descendant(
              of: find.byType(NetworkSpeedCard),
              matching: find.byType(LiquidGlass),
            ), findsNWidgets(3));
          }
          final card = tester.getRect(find.byType(NetworkSpeedCard));
          final boundary = key.currentContext!.findRenderObject()!
              as RenderRepaintBoundary;
          final origin = boundary.localToGlobal(Offset.zero);
          final sample = Offset(card.right - 40, card.bottom - 45) - origin;
          final gap = Offset(card.center.dx, card.top + 50) - origin;
          return (await tester.runAsync(() async {
            final image = await boundary.toImage();
            try {
              final rgba = (await image.toByteData(
                format: ui.ImageByteFormat.rawRgba,
              ))!;
              final offset = (sample.dy.floor() * image.width +
                  sample.dx.floor()) * 4;
              final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
              if (output != null) {
                final png = (await image.toByteData(
                  format: ui.ImageByteFormat.png,
                ))!;
                final directory = Directory(output)..createSync(recursive: true);
                final mode = aurora ? 'aurora' : 'ordinary';
                final effect = glass
                    ? quality.name
                    : '${quality.name}-disabled';
                final tint = wallpaper
                    ? 'wallpaper'
                    : backdrop.toARGB32().toRadixString(16);
                File('${directory.path}/dashboard-$mode-${brightness.name}-'
                    '$effect-$tint.png')
                    .writeAsBytesSync(png.buffer.asUint8List());
              }
              final gapOffset = (gap.dy.floor() * image.width +
                  gap.dx.floor()) * 4;
              return [
                for (var i = 0; i < 3; i++) rgba.getUint8(offset + i),
                for (var i = 0; i < 3; i++) rgba.getUint8(gapOffset + i),
              ];
            } finally {
              image.dispose();
            }
          }))!;
        }

        const first = Color(0xFF194F78);
        const second = Color(0xFF854652);
        final ordinaryFirst = await render(false, first, glass: false);
        final ordinarySecond = await render(false, second, glass: false);
        final clearFirst = await render(true, first);
        final clearSecond = await render(true, second);
        int difference(List<int> a, List<int> b) =>
            List.generate(3, (i) => (a[i] - b[i]).abs())
                .reduce((a, b) => a + b);
        expect(difference(clearFirst, clearSecond),
            greaterThan(difference(ordinaryFirst, ordinarySecond) + 20));
        final plainGlassFirst = await render(false, first);
        final plainGlassSecond = await render(false, second);
        expect(difference(plainGlassFirst, plainGlassSecond),
            greaterThan(difference(ordinaryFirst, ordinarySecond) + 20));
        final backgroundDifference = [
          (first.r - second.r).abs(),
          (first.g - second.g).abs(),
          (first.b - second.b).abs(),
        ].reduce((a, b) => a + b) * 255;
        expect(difference(clearFirst.sublist(3), clearSecond.sublist(3)),
            greaterThan(backgroundDifference * 0.3));
        expect(difference(plainGlassFirst.sublist(3), plainGlassSecond.sublist(3)),
            greaterThan(backgroundDifference * 0.3));
        await render(true, first, wallpaper: true);
        await tester.tap(find.text('Route B'));
        await tester.pumpAndSettle();
        final groups = tester.state<_GroupsState>(find.byType(_Groups));
        expect(groups._controller.index, 1);
        expect(tester.takeException(), isNull);
      });
    }
  }
}
