import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/sheet_surface.dart';
import 'package:fl_clash/views/connection/connections.dart';
import 'package:fl_clash/views/connection/requests.dart';
import 'package:fl_clash/views/dashboard/widgets/connections_table.dart';
import 'package:fl_clash/views/dns_queries.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:mocktail/mocktail.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

class _Core extends Mock implements CoreHandlerInterface {}

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    if (ui.ImageFilter.isShaderFilterSupported) {
      await ui.FragmentProgram.fromAsset(
        'packages/fl_clash/shaders/liquid_glass.frag',
      );
    }
  });

  testWidgets('a table adds no glass pane per row', (tester) async {
    tester.view.physicalSize = const Size(1100, 700);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    Future<(int, int)> pump({required bool glass, required int rows}) async {
      await tester.pumpWidget(
        ProviderScope(
          child: TestApp(
            homeBuilder: (child) => LiquidGlassScope(
              enabled: glass,
              child: Material(type: MaterialType.transparency, child: child),
            ),
            child: RecordTableFrame<int>(
              tableId: 'glass-rows',
              items: [for (var index = 0; index < rows; index++) index],
              columnsOf: (_) => [
                RecordColumn(
                  const TableColumn('name', 'Name', flex: 1),
                  cell: (_, item) => RecordCellText('row $item'),
                ),
                RecordColumn(
                  const TableColumn('exit', 'Exit', width: 120),
                  cell: (_, item) => TonalChip(
                    label: 'exit $item',
                    color: Colors.blue,
                    foregroundColor: Colors.white,
                    onPressed: () {},
                  ),
                ),
              ],
              builder: (_, table, items) => ListView.builder(
                itemCount: items.length,
                itemBuilder: (context, index) => RecordTableRow(
                  table: table!,
                  striped: index.isOdd,
                  cells: table.cellsOf(context, items[index]),
                  onTap: () {},
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      return (
        find.byType(RecordTableRow).evaluate().length,
        find.byType(LiquidGlass).evaluate().length,
      );
    }

    final (fewRows, fewPanes) = await pump(glass: true, rows: 3);
    final (manyRows, manyPanes) = await pump(glass: true, rows: 200);
    expect(manyRows, greaterThan(fewRows * 4));
    expect(fewPanes, 2);
    expect(manyPanes, fewPanes);
    final firstRow = find.byType(RecordTableRow).first;
    final row = tester.getRect(
      find.descendant(of: firstRow, matching: find.byType(TableCells)),
    );
    final header = tester.getRect(find.byType(TableCells).first);
    final envelope = tester.getRect(
      find.ancestor(of: firstRow, matching: find.byType(CardGroup)),
    );
    expect(row.left - envelope.left, 8);
    expect(envelope.right - row.right, 8);
    expect((row.left, row.right), (header.left, header.right));
    final (_, plainPanes) = await pump(glass: false, rows: 200);
    expect(plainPanes, 0);
    expect(find.byType(CardGroup), findsNWidgets(2));
    expect(tester.takeException(), isNull);
  });

  for (final feed in DashboardFeed.values) {
    for (final entry in ['page', 'sheet', 'table']) {
      testWidgets('${feed.name} $entry renders glass and opaque fallbacks', (
        tester,
      ) async {
        final size = Size(entry == 'table' ? 1100 : 420, 900);
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.resumed,
        );
        final rendered = <(LiquidGlassMode, bool), List<Color>>{};
        for (final (mode, highContrast, pureBlack, backdrop) in [
          (LiquidGlassMode.standard, false, false, const Color(0xFF2040C0)),
          (LiquidGlassMode.standard, false, false, const Color(0xFFC04020)),
          (LiquidGlassMode.ultra, false, false, const Color(0xFF2040C0)),
          (LiquidGlassMode.ultra, false, false, const Color(0xFFC04020)),
          (LiquidGlassMode.none, false, false, const Color(0xFF2040C0)),
          (LiquidGlassMode.standard, true, false, const Color(0xFF2040C0)),
          (LiquidGlassMode.ultra, false, true, const Color(0xFF2040C0)),
          (LiquidGlassMode.ultra, false, true, const Color(0xFFC04020)),
        ]) {
          final tracker = TrackerInfo(
            id: 'record',
            start: DateTime.utc(2026),
            metadata: const Metadata(
              network: 'tcp',
              host: 'glass-record.test',
              destinationPort: '443',
            ),
            chains: const ['Proxy'],
            rule: 'MATCH',
            rulePayload: '',
          );
          final core = _Core();
          when(() => core.getConnections()).thenAnswer((_) async => [tracker]);
          final container = ProviderContainer(
            overrides: [
              profilesProvider.overrideWith(TestProfiles.new),
              coreHandlerProvider.overrideWithValue(
                CoreController.scoped(core),
              ),
              coreStatusProvider.overrideWithBuild(
                (_, _) => CoreStatus.connected,
              ),
              themeSettingProvider.overrideWithBuild(
                (_, _) => ThemeProps(
                  auroraEnabled: false,
                  liquidGlass: mode,
                  pureBlack: pureBlack,
                ),
              ),
            ],
          );
          try {
            globalState.container = container;
            container.read(viewSizeProvider.notifier).value = size;
            container.read(requestsProvider.notifier).addRequest(tracker);
            container
                .read(dnsQueriesProvider.notifier)
                .addQuery(
                  DnsQuery(
                    domain: 'glass-record.test',
                    type: 'A',
                    time: DateTime.utc(2026),
                    answers: const ['192.0.2.1'],
                  ),
                );
            final captureKey = GlobalKey();
            await tester.pumpWidget(
              UncontrolledProviderScope(
                container: container,
                child: MaterialApp(
                  navigatorKey: globalState.navigatorKey,
                  theme:
                      ThemeData(
                        platform: TargetPlatform.android,
                        brightness: pureBlack
                            ? Brightness.dark
                            : Brightness.light,
                      ).withAppShapes.withAppSurfaces.copyWith(
                        scaffoldBackgroundColor: backdrop,
                      ),
                  localizationsDelegates: const [
                    AppLocalizations.delegate,
                    AppUiLocalizations.delegate,
                    ...GlobalMaterialLocalizations.delegates,
                  ],
                  supportedLocales: AppLocalizations.delegate.supportedLocales,
                  builder: (context, child) => MediaQuery(
                    data: MediaQuery.of(
                      context,
                    ).copyWith(highContrast: highContrast),
                    child: wrapAppScopes(
                      context,
                      RepaintBoundary(
                        key: captureKey,
                        child: AuroraAppSurface(motion: false, child: child!),
                      ),
                    ),
                  ),
                  home: Scaffold(
                    body: Builder(
                      builder: (context) => TextButton(
                        onPressed: () {
                          if (entry == 'sheet') {
                            openDashboardFeed(context, feed);
                          } else {
                            BaseNavigator.push<void>(context, switch (feed) {
                              DashboardFeed.connections =>
                                const ConnectionsView(),
                              DashboardFeed.requests => const RequestsView(),
                              DashboardFeed.dns => const DnsQueriesView(),
                            });
                          }
                        },
                        child: const Text('Open feed'),
                      ),
                    ),
                  ),
                ),
              ),
            );
            await tester.tap(find.text('Open feed'));
            await tester.pump(const Duration(milliseconds: 500));
            await tester.pump(const Duration(milliseconds: 500));
            await tester.pumpAndSettle();
            final glass = mode != LiquidGlassMode.none && !highContrast;
            final table = entry == 'table';
            final record = table
                ? find.byType(RecordTableRow).first
                : find.byType(RecordListItem).first;
            expect(record, findsOneWidget);
            final group = table
                ? find.ancestor(of: record, matching: find.byType(CardGroup))
                : find
                      .descendant(of: record, matching: find.byType(CardGroup))
                      .first;
            if (table) {
              expect(group, findsOneWidget);
              expect(tester.widget<CardGroup>(group).paintSurface, glass);
              expect(
                find.descendant(of: record, matching: find.byType(CardGroup)),
                findsNothing,
              );
            }
            final panes = find.descendant(
              of: group,
              matching: find.byType(LiquidGlass),
            );
            expect(
              panes,
              glass && entry != 'sheet' ? findsWidgets : findsNothing,
            );
            final chips = tester
                .widgetList<TonalChip>(
                  find.descendant(of: record, matching: find.byType(TonalChip)),
                )
                .where((chip) => chip.onPressed != null);
            for (final chip in chips) {
              expect(
                find.descendant(
                  of: find.byWidget(chip),
                  matching: find.byType(LiquidGlass),
                ),
                findsNothing,
              );
            }
            expect(find.byType(LiquidGlassHost), findsOneWidget);
            final fills = tester
                .widgetList<DecoratedBox>(
                  find.descendant(
                    of: record,
                    matching: find.byType(DecoratedBox),
                  ),
                )
                .map((box) => box.decoration)
                .whereType<ShapeDecoration>()
                .map((box) => box.color)
                .whereType<Color>();
            if (glass) {
              expect(fills.every((color) => color.a < 1), isTrue);
              final rect = tester.getRect(group);
              final capture = LiquidGlassHost.debugLastCapture!;
              expect(
                capture.panes.where(
                  (pane) =>
                      (pane.pane.width - rect.width).abs() < 0.5 &&
                      (pane.pane.height - rect.height).abs() < 0.5,
                ),
                hasLength(entry == 'sheet' ? 0 : 1),
              );
              if (entry == 'sheet') {
                expect(find.byType(SheetSurface), findsWidgets);
                final fill = tester.widget<DecoratedBox>(
                  find
                      .descendant(
                        of: group,
                        matching: find.byType(DecoratedBox),
                      )
                      .first,
                );
                expect(
                  (fill.decoration as ShapeDecoration).color,
                  glassRaisedFillOf(tester.element(group)),
                );
              }
              final boundary = tester.renderObject<RenderRepaintBoundary>(
                find.byKey(captureKey),
              );
              final point = boundary.globalToLocal(
                Offset(rect.left + 6, rect.center.dy),
              );
              final image = boundary.toImageSync();
              try {
                final color = (await tester.runAsync(() async {
                  final bytes = (await image.toByteData())!;
                  final at =
                      (point.dy.floor() * image.width + point.dx.floor()) * 4;
                  final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
                  if (output != null) {
                    final png = (await image.toByteData(
                      format: ui.ImageByteFormat.png,
                    ))!;
                    await Directory(output).create(recursive: true);
                    await File(
                      '$output/xclash-record-${feed.name}-$entry-${mode.name}-${backdrop.toARGB32()}.png',
                    ).writeAsBytes(png.buffer.asUint8List());
                  }
                  return Color.fromARGB(
                    255,
                    bytes.getUint8(at),
                    bytes.getUint8(at + 1),
                    bytes.getUint8(at + 2),
                  );
                }))!;
                (rendered[(mode, pureBlack)] ??= []).add(color);
              } finally {
                image.dispose();
              }
            } else {
              expect(LiquidGlassScope.of(tester.element(record)), isFalse);
              if (entry != 'table')
                expect(fills.any((color) => color.a == 1), isTrue);
            }
            expect(tester.takeException(), isNull);
          } finally {
            try {
              await tester.pumpWidget(const SizedBox.shrink());
              await tester.pump(Duration.zero);
            } finally {
              container.dispose();
            }
          }
        }
        for (final colors in rendered.values) {
          expect(colors, hasLength(2));
          expect((colors[0].r - colors[1].r).abs(), greaterThan(0.1));
          expect((colors[0].b - colors[1].b).abs(), greaterThan(0.1));
        }
      });
    }
  }
}
