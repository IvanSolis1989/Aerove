import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/glyph_finders.dart';
import '../helpers/test_app.dart';

void main() {
  setUpAll(() async {
    final font = FontLoader('SheetSearchProbe')
      ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf'));
    await font.load();
  });

  late List<String> queries;

  setUp(() => queries = []);

  Widget scaffold({required SheetType? type, Widget? body}) {
    final scaffold = CommonScaffold(
      title: 'title',
      searchState: AppBarSearchState(onSearch: queries.add),
      body: body ?? const SizedBox(),
    );
    return TestApp(
      wrapInProviderScope: true,
      child: type == null
          ? scaffold
          : SheetProvider(type: type, child: scaffold),
    );
  }

  testWidgets('a bottom sheet searches from a docked field, not a bar button', (
    tester,
  ) async {
    await tester.pumpWidget(scaffold(type: SheetType.bottomSheet));
    await tester.pumpAndSettle();

    expect(find.byType(SearchField), findsOneWidget);
    expect(
      find.descendant(
        of: find.byType(AppBar),
        matching: find.byGlyph(AppGlyphs.search),
      ),
      findsNothing,
    );
    expect(find.text('title'), findsOneWidget);

    await tester.enterText(find.byType(TextField), 'wiki');
    await tester.pumpAndSettle();

    expect(queries, ['wiki']);
    // The title survives searching, because nothing took the bar over.
    expect(find.text('title'), findsOneWidget);
  });

  testWidgets('a docked field leaves the close button at the trailing end', (
    tester,
  ) async {
    await tester.pumpWidget(
      scaffold(
        type: SheetType.bottomSheet,
        body: const SizedBox(width: 600, height: 200),
      ),
    );
    await tester.pumpAndSettle();

    final bar = find.byType(AppBar);
    final close = find.descendant(
      of: bar,
      matching: find.byGlyph(AppGlyphs.close),
    );
    expect(close, findsOneWidget);
    final rect = tester.getRect(bar);
    expect(rect.width, 600);
    expect(tester.getCenter(close).dx, greaterThan(rect.center.dx));
  });

  testWidgets('a page and a side sheet keep the bar button', (tester) async {
    for (final type in [null, SheetType.page, SheetType.sideSheet]) {
      await tester.pumpWidget(scaffold(type: type));
      await tester.pumpAndSettle();

      expect(find.byType(SearchField), findsNothing, reason: '$type');
      expect(
        find.descendant(
          of: find.byType(AppBar),
          matching: find.byGlyph(AppGlyphs.search),
        ),
        findsOneWidget,
        reason: '$type',
      );
    }
  });

  Future<void> openModalSheet(
    WidgetTester tester, {
    Widget? body,
    bool glass = false,
  }) async {
    await tester.pumpWidget(
      LiquidGlassScope(
        enabled: glass,
        child: TestApp(
          wrapInProviderScope: true,
          child: Builder(
            builder: (context) => TextButton(
              onPressed: () => showModalBottomSheet<void>(
                context: context,
                isScrollControlled: true,
                builder: (_) => SheetProvider(
                  type: SheetType.bottomSheet,
                  child: CommonScaffold(
                    title: 'title',
                    searchState: AppBarSearchState(onSearch: queries.add),
                    body: body ?? const SizedBox(height: 200),
                  ),
                ),
              ),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  testWidgets('a docked field leaves the list room to scroll clear of it', (
    tester,
  ) async {
    late double inset;

    await openModalSheet(
      tester,
      body: Builder(
        builder: (context) {
          inset = BottomInsetScope.of(context);
          return const SizedBox(height: 200);
        },
      ),
    );

    expect(inset, BottomInsetScope.dockedSearchInset);
    expect(
      tester.getSize(find.byType(SearchField)).height,
      BottomInsetScope.dockedSearchHeight,
    );
  });

  testWidgets('a docked field carries its own opaque surface', (tester) async {
    await openModalSheet(tester);

    final decoration = tester
        .widget<TextField>(find.byType(TextField))
        .decoration!;
    final field = tester.element(find.byType(TextField));
    final theme = Theme.of(field);
    final inputTheme = InputDecorationTheme.of(field);
    final fill = decoration.fillColor ?? inputTheme.fillColor;

    expect(decoration.filled ?? inputTheme.filled, isTrue);
    expect(
      fill,
      SurfaceLevel.floatingOf(theme.colorScheme).colorOf(theme.colorScheme),
    );
    expect(fill!.a, 1);
    // The dock itself paints nothing, so no band spans the sheet behind it.
    final dock = tester.widget<Material>(
      find
          .ancestor(
            of: find.byType(SearchField),
            matching: find.byType(Material),
          )
          .first,
    );
    expect(dock.type, MaterialType.transparency);
  });

  testWidgets('a docked field stands on glass in place of its fill', (
    tester,
  ) async {
    await openModalSheet(tester, glass: true);

    expect(
      find.ancestor(
        of: find.byType(SearchField),
        matching: find.byType(LiquidGlass),
      ),
      findsOneWidget,
    );
    expect(
      InputDecorationTheme.of(tester.element(find.byType(TextField))).filled,
      isFalse,
    );
  });

  testWidgets('high contrast search retains its opaque focus treatment', (
    tester,
  ) async {
    tester.platformDispatcher.accessibilityFeaturesTestValue =
        const FakeAccessibilityFeatures(highContrast: true);
    addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
    await openModalSheet(tester, glass: true);
    final field = tester.element(find.byType(TextField));
    final theme = InputDecorationTheme.of(field);
    expect(theme.filled, isTrue);
    expect(theme.fillColor!.a, 1);
    expect(theme.focusedBorder!.borderSide.width, 2);
    expect(
      find.ancestor(
        of: find.byType(SearchField),
        matching: find.byType(LiquidGlass),
      ),
      findsNothing,
    );
  });

  for (final brightness in Brightness.values) {
    for (final quality in LiquidGlassQuality.values) {
      testWidgets('snap search has one frosted outline across focus and IME $brightness $quality', (
        tester,
      ) async {
        const size = Size(400, 900);
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        tester.view.padding = const FakeViewPadding(bottom: 34);
        tester.view.viewPadding = const FakeViewPadding(bottom: 34);
        addTearDown(tester.view.reset);
        final captureKey = GlobalKey();
        final scheme = ColorScheme.fromSeed(
          seedColor: Colors.blue,
          brightness: brightness,
        ).copyWith(primary: Colors.black);
        final inheritedBorder = AppShape.input.copyWith(
          borderSide: const BorderSide(color: Colors.black, width: 4),
        );
        final theme = ThemeData(colorScheme: scheme, fontFamily: 'SheetSearchProbe');
        await tester.pumpWidget(
          RepaintBoundary(
            key: captureKey,
            child: LiquidGlassScope(
              enabled: true,
              quality: quality,
              child: LiquidGlassHost(
                enabled: true,
                wallpaperOnly: true,
                child: Stack(
                  children: [
                    const Positioned.fill(
                      child: LiquidGlassColorField(
                        child: LiquidGlassColorSource(
                          child: ColoredBox(color: Color(0xFF88BBAA)),
                        ),
                      ),
                    ),
                    TestApp(
                      includeNavigatorKey: false,
                      overrides: [viewSizeProvider.overrideWithBuild((_, _) => size)],
                      theme: theme.copyWith(
                        inputDecorationTheme: theme.inputDecorationTheme.copyWith(
                          enabledBorder: inheritedBorder,
                          disabledBorder: inheritedBorder,
                          errorBorder: inheritedBorder,
                          focusedErrorBorder: inheritedBorder,
                        ),
                      ),
                      child: Scaffold(
                        backgroundColor: Colors.transparent,
                        body: Stack(
                          children: [
                            const Positioned(
                              left: 20,
                              right: 20,
                              bottom: 34,
                              child: Text('HOME DOCK  HOME DOCK  HOME DOCK'),
                            ),
                            Builder(
                              builder: (context) => TextButton(
                                onPressed: () => Navigator.of(context).push<void>(
                                  SnapSheetRoute<void>(
                                    barrierLabel: 'Close sheet',
                                    detents: const [0.7, 1],
                                    builder: (_, controller) => CommonScaffold(
                                      title: 'title',
                                      searchState: AppBarSearchState(onSearch: queries.add),
                                      body: Builder(
                                        builder: (bodyContext) => ListView.builder(
                                          controller: controller,
                                          padding: EdgeInsets.only(
                                            top: sheetAppBarHeight,
                                            bottom: BottomInsetScope.of(bodyContext),
                                          ),
                                          itemCount: 60,
                                          itemExtent: 40,
                                          itemBuilder: (_, index) => Text('Sheet row $index'),
                                        ),
                                      ),
                                    ),
                                  ),
                                ),
                                child: const Text('open'),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
        await tester.tap(find.text('open'));
        await tester.pumpAndSettle();
        if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
          expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
        }
        if (ui.ImageFilter.isShaderFilterSupported) {
          await tester.runAsync(() => ui.FragmentProgram.fromAsset(
            'shaders/liquid_glass.frag',
          ).timeout(const Duration(seconds: 30)));
          await tester.pump();
          expect(LiquidGlass.debugShaderReady, isTrue);
        }
        final search = find.byType(SearchField);
        final panes = find.ancestor(of: search, matching: find.byType(LiquidGlass));
        expect(panes, findsOneWidget);
        final pane = tester.widget<LiquidGlass>(panes);
        expect(pane.contentBackdrop, isFalse);
        expect(pane.style.blur, 16);
        expect(pane.style.haze, 0);
        expect(pane.style.refraction, 0);
        expect(pane.style.bevel, 0);
        expect(pane.style.rim, 0);
        expect(pane.style.hairline, 0);
        expect(pane.style.tint.a, lessThanOrEqualTo(0.24));
        final decoration = InputDecorationTheme.of(tester.element(find.byType(TextField)));
        for (final border in [
          decoration.border,
          decoration.enabledBorder,
          decoration.disabledBorder,
          decoration.errorBorder,
          decoration.focusedBorder,
          decoration.focusedErrorBorder,
        ]) {
          expect(border, isA<AppInputBorder>());
          expect(border!.borderSide, BorderSide.none);
          expect((border as AppInputBorder).borderRadius, AppRadius.full);
        }
        final name = 'sheet-search-${brightness.name}-${quality.name}';
        final bounds = tester.getRect(search);
        expect(bounds.bottom, size.height - 34 - BottomInsetScope.dockedSearchMargin);
        final before = await _searchFrame(tester, captureKey, '$name-unfocused');
        final repeat = await _searchFrame(tester, captureKey, '$name-repeat');
        await tester.tap(find.byType(TextField));
        await tester.pumpAndSettle();
        expect(tester.widget<TextField>(find.byType(TextField)).focusNode!.hasFocus, isTrue);
        expect(tester.getRect(search), bounds);
        final focused = await _searchFrame(tester, captureKey, '$name-focused');
        final noise = _edgeDelta(before, repeat, bounds);
        final focusDelta = _edgeDelta(before, focused, bounds);
        expect(focusDelta, lessThanOrEqualTo(noise));

        await tester.enterText(find.byType(TextField), 'wiki');
        await tester.pumpAndSettle();
        expect(queries.last, 'wiki');
        await tester.tap(find.byTooltip(UiLocalizations.of(tester.element(search)).clearSearch));
        await tester.pumpAndSettle();
        expect(queries.last, '');
        expect(tester.widget<TextField>(find.byType(TextField)).controller!.text, isEmpty);
        for (final inset in [100.0, 200.0, 300.0]) {
          tester.view.viewInsets = FakeViewPadding(bottom: inset);
          await tester.pump();
          await tester.pumpAndSettle();
          expect(tester.getRect(search).bottom, lessThanOrEqualTo(size.height - inset));
          expect(tester.getSize(search).height, BottomInsetScope.dockedSearchHeight);
          expect(find.text('title'), findsOneWidget);
          expect(find.byType(SheetDragHandle), findsOneWidget);
        }
        final shaderReadyAfterIme = LiquidGlass.debugShaderReady;
        await _searchFrame(tester, captureKey, '$name-ime');
        final scrolling = tester.state<ScrollableState>(find.byType(Scrollable).first).position;
        scrolling.jumpTo(scrolling.maxScrollExtent);
        await tester.pumpAndSettle();
        expect(find.text('Sheet row 59'), findsOneWidget);
        expect(tester.getRect(find.text('Sheet row 59')).bottom, lessThanOrEqualTo(tester.getRect(search).top));
        final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
        if (output != null && output.isNotEmpty) {
          await tester.runAsync(() async {
            File('$output/$name.json').writeAsStringSync(
              '{"shaderSupported":${ui.ImageFilter.isShaderFilterSupported},'
              '"shaderReadyAfterIme":$shaderReadyAfterIme,'
              '"focusEdgeDelta":$focusDelta,"repeatEdgeNoise":$noise}',
            );
          });
        }
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump(const Duration(seconds: 2));
        expect(tester.takeException(), isNull);
      });
    }
  }

  testWidgets('a docked field is a pill that strokes only while focused', (
    tester,
  ) async {
    await openModalSheet(tester);

    final field = tester.element(find.byType(TextField));
    final inputTheme = InputDecorationTheme.of(field);
    final border = inputTheme.border;
    final focused = inputTheme.focusedBorder;

    expect(border, isA<AppInputBorder>());
    expect((border! as AppInputBorder).borderRadius, AppRadius.full);
    expect(border.borderSide, BorderSide.none);
    expect(focused, isA<AppInputBorder>());
    expect((focused! as AppInputBorder).borderRadius, AppRadius.full);
    expect(focused.borderSide.color, Theme.of(field).colorScheme.primary);
    final decorator = tester.widget<InputDecorator>(
      find.byType(InputDecorator),
    );
    expect(
      decorator.decoration.floatingLabelBehavior,
      FloatingLabelBehavior.never,
    );
  });

  testWidgets('a docked field clears the bottom safe area once', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(400, 900);
    tester.view.devicePixelRatio = 1;
    tester.view.padding = const FakeViewPadding(bottom: 34);
    tester.view.viewPadding = const FakeViewPadding(bottom: 34);
    addTearDown(tester.view.reset);

    await openModalSheet(tester);

    expect(
      tester.getBottomLeft(find.byType(SearchField)).dy,
      900 - 34 - BottomInsetScope.dockedSearchMargin,
    );
  });

  testWidgets('a fit sheet keeps its docked field above the keyboard', (
    tester,
  ) async {
    const size = Size(400, 900);
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      TestApp(
        overrides: [viewSizeProvider.overrideWithBuild((_, _) => size)],
        child: Builder(
          builder: (context) => TextButton(
            onPressed: () => showSheet<void>(
              context: context,
              builder: (_) => CommonScaffold(
                title: 'title',
                searchState: AppBarSearchState(onSearch: queries.add),
                body: const SizedBox(height: 200),
              ),
            ),
            child: const Text('open'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(tester.getBottomLeft(find.byType(SearchField)).dy, lessThan(900));

    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    await tester.pumpAndSettle();

    expect(tester.getBottomLeft(find.byType(SearchField)).dy, lessThan(600));

    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 2));
  });

  testWidgets('a snap sheet keeps its docked field above the keyboard', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(400, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      TestApp(
        wrapInProviderScope: true,
        child: Builder(
          builder: (context) => TextButton(
            onPressed: () => showSnapSheet(
              context,
              builder: (_, controller) => CommonScaffold(
                title: 'title',
                searchState: AppBarSearchState(onSearch: queries.add),
                body: ListView.builder(
                  controller: controller,
                  itemCount: 60,
                  itemBuilder: (_, index) =>
                      SizedBox(height: 40, child: Text('$index')),
                ),
              ),
            ),
            child: const Text('open'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    expect(find.byType(SearchField), findsOneWidget);
    expect(tester.getBottomLeft(find.byType(SearchField)).dy, lessThan(900));

    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    addTearDown(tester.view.resetViewInsets);
    await tester.pumpAndSettle();

    expect(tester.getBottomLeft(find.byType(SearchField)).dy, lessThan(600));

    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 2));
  });
}

Future<({List<int> rgba, int width})> _searchFrame(
  WidgetTester tester,
  GlobalKey key,
  String name,
) async {
  final boundary = key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  final image = boundary.toImageSync();
  try {
    return (await tester.runAsync(() async {
      final bytes = (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!;
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null && output.isNotEmpty) {
        Directory(output).createSync(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        File('$output/$name.png').writeAsBytesSync(png.buffer.asUint8List());
      }
      return (rgba: bytes.buffer.asUint8List().toList(), width: image.width);
    }))!;
  } finally {
    image.dispose();
  }
}

int _edgeDelta(
  ({List<int> rgba, int width}) a,
  ({List<int> rgba, int width}) b,
  Rect bounds,
) {
  var maxDelta = 0;
  for (final y in [bounds.top.ceil() + 1, bounds.bottom.floor() - 2]) {
    for (var x = (bounds.left + bounds.height).ceil();
        x < bounds.right - bounds.height;
        x++) {
      for (var channel = 0; channel < 4; channel++) {
        final index = (y * a.width + x) * 4 + channel;
        final delta = (a.rgba[index] - b.rgba[index]).abs();
        if (delta > maxDelta) maxDelta = delta;
      }
    }
  }
  return maxDelta;
}
