import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/dashboard/dashboard.dart';
import 'package:fl_clash/views/dashboard/edit.dart';
import 'package:fl_clash/views/dashboard/widgets/fused_controls.dart';
import 'package:fl_clash/views/dashboard/widgets/widgets.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/preferences.dart';
import '../../helpers/test_app.dart';
import '../../helpers/test_profiles.dart';

void main() {
  late ProviderContainer container;
  late GlobalKey sceneKey;

  setUp(() async {
    await mockPreferences();
    container = ProviderContainer(
      overrides: [profilesProvider.overrideWith(TestProfiles.new)],
    );
    globalState.container = container;
    sceneKey = GlobalKey();
  });

  tearDown(() => container.dispose());

  void save(List<DashboardWidget> widgets) {
    container
        .read(appSettingProvider.notifier)
        .update((state) => state.copyWith(dashboardWidgets: widgets));
  }

  Future<void> pumpDashboard(WidgetTester tester) async {
    tester.view.physicalSize = const Size(412, 700);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    container.read(viewSizeProvider.notifier).value = const Size(412, 700);
    save(DashboardWidget.values);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: RepaintBoundary(
          key: sceneKey,
          child: const TestApp(
            child: DashboardView(platform: SupportPlatform.Android),
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
  }

  Map<Key, RepaintBoundary> cards(WidgetTester tester) => {
    for (final boundary in tester.widgetList<RepaintBoundary>(
      find.descendant(
        of: find.byType(DashboardView),
        matching: find.byType(RepaintBoundary),
        skipOffstage: false,
      ),
    ))
      if (boundary.key is GlobalKey) boundary.key!: boundary,
  };

  Future<void> capture(WidgetTester tester, String name) async {
    final directory = Platform.environment['XCLASH_SCREENSHOT_DIR'];
    if (directory == null) return;
    await tester.runAsync(() async {
      final boundary =
          sceneKey.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      final image = await boundary.toImage(pixelRatio: 1);
      try {
        final png = await image.toByteData(format: ui.ImageByteFormat.png);
        final rgba = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
        final output = Directory('$directory/dashboard_scroll_build');
        await output.create(recursive: true);
        await File('${output.path}/$name.png').writeAsBytes(
          png!.buffer.asUint8List(png.offsetInBytes, png.lengthInBytes),
        );
        await File('${output.path}/$name.rgba').writeAsBytes(
          rgba!.buffer.asUint8List(rgba.offsetInBytes, rgba.lengthInBytes),
        );
      } finally {
        image.dispose();
      }
    });
  }

  Future<void> teardown(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 5));
  }

  testWidgets('pure phone scrolling retains every card configuration', (
    tester,
  ) async {
    await pumpDashboard(tester);
    final position = tester
        .state<ScrollableState>(find.byType(Scrollable).first)
        .position;
    final initial = cards(tester);
    expect(initial.length, greaterThan(3));
    expect(find.byType(FusedNetworkControls), findsOneWidget);
    expect(position.maxScrollExtent, greaterThan(0));
    var replacedWrappers = 0;
    var replacedFusionCards = 0;
    final controls = tester.widget(find.byType(FusedNetworkControls));
    final services = tester.widget(find.byType(ServiceStatusCard));
    final offsets = <double>[];
    await capture(tester, 'scroll_top');

    final gesture = await tester.startGesture(const Offset(200, 500));
    for (var frame = 0; frame < 8; frame++) {
      await gesture.moveBy(const Offset(0, -32));
      await tester.pump(const Duration(milliseconds: 16));
      offsets.add(position.pixels);
      final current = cards(tester);
      for (final entry in initial.entries) {
        if (!identical(current[entry.key], entry.value)) replacedWrappers++;
      }
      if (!identical(
        tester.widget(find.byType(FusedNetworkControls)),
        controls,
      )) {
        replacedFusionCards++;
      }
      if (!identical(tester.widget(find.byType(ServiceStatusCard)), services)) {
        replacedFusionCards++;
      }
    }
    await capture(tester, 'scroll_drag');
    await gesture.up();
    await tester.pump(const Duration(seconds: 1));
    await capture(tester, 'scroll_rest');
    final directory = Platform.environment['XCLASH_SCREENSHOT_DIR'];
    if (directory != null) {
      await tester.runAsync(
        () => File('$directory/dashboard_scroll_build/work.json').writeAsString(
          jsonEncode({
            'scene': 'Flutter widget test; not an Android device render',
            'size': [412, 700],
            'frames': offsets.length,
            'offsets': offsets,
            'cardCount': initial.length,
            'replacedWrappers': replacedWrappers,
            'replacedFusionCards': replacedFusionCards,
          }),
        ),
      );
    }
    await teardown(tester);

    expect(offsets.last, greaterThan(offsets.first));
    expect(
      replacedWrappers,
      0,
      reason: 'Scroll-only constraints must not recreate card wrappers',
    );
    expect(
      replacedFusionCards,
      0,
      reason: 'Scroll-only constraints must not recreate fusion cards',
    );
  });

  testWidgets('phone width and saved edits still refresh card layout', (
    tester,
  ) async {
    await pumpDashboard(tester);
    final initial = cards(tester);
    final firstKey = initial.keys.first;
    final firstWidth = tester.getSize(find.byKey(firstKey)).width;
    final controls = tester.widget<FusedNetworkControls>(
      find.byType(FusedNetworkControls),
    );

    tester.view.physicalSize = const Size(620, 700);
    container.read(viewSizeProvider.notifier).value = const Size(620, 700);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    final resized = cards(tester);
    expect(resized.keys, unorderedEquals(initial.keys));
    expect(tester.getSize(find.byKey(firstKey)).width, greaterThan(firstWidth));
    expect(tester.getSize(find.byKey(firstKey)).width, lessThanOrEqualTo(560));
    await capture(tester, 'width_620');

    await tester.tap(find.byTooltip('Edit dashboard'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byType(DashboardEditor), findsOneWidget);
    final editing = cards(tester);
    expect(editing, isNotEmpty);
    expect(editing.keys, contains(firstKey));
    expect(initial.keys, containsAll(editing.keys));
    await capture(tester, 'editing');
    save([
      for (final item in DashboardWidget.values)
        if (item != DashboardWidget.intranetIp) item,
    ]);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.tap(find.byTooltip('Done'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    final changed = tester.widget<FusedNetworkControls>(
      find.byType(FusedNetworkControls),
    );
    expect(find.byType(DashboardEditor), findsNothing);
    expect(controls.members, contains(DashboardWidget.intranetIp));
    expect(changed.members, isNot(contains(DashboardWidget.intranetIp)));
    expect(cards(tester).keys, unorderedEquals(initial.keys));
    await capture(tester, 'saved_edit');
    await teardown(tester);
  });
}
