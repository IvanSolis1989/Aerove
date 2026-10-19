import 'dart:convert';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/sliver_glass_group.dart';
import 'package:fl_clash/views/proxies/card.dart';
import 'package:fl_clash/views/proxies/list.dart';
import 'package:fl_clash/views/proxies/tab.dart';
import 'package:fl_clash/widgets/widgets.dart' hide ListHeader;
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_svg/svg.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';
import '../helpers/scrollbar.dart';

class _RecordingProxiesAction extends ProxiesAction {
  static final List<String> tested = [];

  @override
  Future<void> delayTestPageGroup(
    String groupName, {
    bool matchSearch = true,
  }) async {
    tested.add(groupName);
  }
}

Group _group(String name, int count) => Group(
  type: GroupType.Selector,
  name: name,
  all: [for (var i = 0; i < count; i++) Proxy(name: '$name-$i', type: 'ss')],
);

void main() {
  late ProviderContainer container;

  Future<void> pumpList(
    WidgetTester tester, {
    Map<String, String> selectedMap = const {},
    ViewMode viewMode = ViewMode.mobile,
    List<Group>? groups,
    Set<String> unfoldSet = const {'G0', 'G1', 'G2'},
    ValueListenable<Brightness>? brightness,
    bool aurora = false,
    bool? glass,
    LiquidGlassQuality quality = LiquidGlassQuality.standard,
    Widget? view,
  }) async {
    tester.view.physicalSize = const Size(900, 700);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    groups ??= [_group('G0', 40), _group('G1', 30), _group('G2', 60)];
    final profile = Profile.normal().copyWith(
      selectedMap: selectedMap,
      unfoldSet: unfoldSet,
    );
    _RecordingProxiesAction.tested.clear();
    container = ProviderContainer(
      overrides: [
        viewModeProvider.overrideWithValue(viewMode),
        currentProfileIdProvider.overrideWithBuild((_, _) => profile.id),
        profilesProvider.overrideWith(() => TestProfiles([profile])),
        currentGroupsStateProvider.overrideWithValue(
          GroupsState(value: groups),
        ),
        proxiesActionProvider.overrideWith(_RecordingProxiesAction.new),
      ],
    );
    addTearDown(container.dispose);
    globalState.container = container;
    container.read(groupsProvider.notifier).value = groups;
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          homeBuilder: (child) => Scaffold(
            body: Builder(
              builder: (context) => MediaQuery(
                data: MediaQuery.of(
                  context,
                ).copyWith(padding: const EdgeInsets.only(top: 64)),
                child: (glass ?? aurora)
                    ? AuroraAppearance(
                        enabled: aurora,
                        child: LiquidGlassScope(
                          enabled: true,
                          quality: quality,
                          child: LiquidGlassHost(enabled: true, child: child),
                        ),
                      )
                    : child,
              ),
            ),
          ),
          child: brightness == null
              ? view ?? const ProxiesListView()
              : ValueListenableBuilder(
                  valueListenable: brightness,
                  builder: (_, brightness, child) => Theme(
                    data: ThemeData(brightness: brightness),
                    child: child!,
                  ),
                  child: view ?? const ProxiesListView(),
                ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  Finder headerOf(String groupName) => find.byWidgetPredicate(
    (widget) => widget is ListHeader && widget.group.name == groupName,
  );

  Finder actionOf(String groupName, String tooltip) => find.descendant(
    of: headerOf(groupName),
    matching: find.byTooltip(tooltip),
  );

  Finder scrollable() => find
      .descendant(
        of: find.byType(CustomScrollView),
        matching: find.byType(Scrollable),
      )
      .first;

  Future<void> revealHeaderOf(WidgetTester tester, String groupName) async {
    await tester.scrollUntilVisible(
      actionOf(groupName, 'Scroll to selected'),
      200,
      scrollable: scrollable(),
    );
    await tester.pumpAndSettle();
  }

  Finder groupScroll(String groupName) => find.descendant(
    of: find.ancestor(
      of: find.byWidgetPredicate(
        (widget) => widget is ListHeader && widget.group.name == groupName,
        skipOffstage: false,
      ),
      matching: find.byWidgetPredicate(
        (widget) => widget.runtimeType.toString() == '_GroupSliver',
      ),
      skipOffstage: false,
    ),
    matching: find.byType(Scrollable),
    skipOffstage: false,
  );

  ShapeDecoration surfaceOf(WidgetTester tester, String groupName) =>
      tester
              .widget<DecoratedBox>(
                find
                    .descendant(
                      of: headerOf(groupName),
                      matching: find.byType(DecoratedBox),
                    )
                    .first,
              )
              .decoration
          as ShapeDecoration;

  Rect headerInHost(WidgetTester tester, String groupName) {
    final header = tester.renderObject<RenderBox>(headerOf(groupName));
    final host = tester.renderObject<RenderBox>(find.byType(LiquidGlassHost));
    return MatrixUtils.transformRect(
      header.getTransformTo(host),
      Offset.zero & header.size,
    );
  }

  Rect glassGroupRect(WidgetTester tester, String groupName) {
    final header = headerInHost(tester, groupName);
    return LiquidGlassHost.debugLastCapture!.panes
        .singleWhere(
          (pane) =>
              (pane.pane.left - header.left).abs() < 0.01 &&
              (pane.pane.width - header.width).abs() < 0.01 &&
              (pane.pane.top - header.top).abs() < 0.01,
        )
        .pane;
  }

  Future<void> unmountList(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(Duration.zero);
  }

  for (final tab in [false, true]) {
    for (final quality in [null, ...LiquidGlassQuality.values]) {
      testWidgets(
        'proxy ${tab ? 'tab' : 'list'} scrollbar fades and remains '
        'scrubbable (${quality?.name ?? 'none'})',
        (tester) async {
          final group = _group('G0', 160);
          final controller = ScrollController();
          try {
            await pumpList(
              tester,
              groups: [group],
              glass: quality != null,
              quality: quality ?? LiquidGlassQuality.standard,
              view: tab
                  ? ProxyGroupView(
                      group: group,
                      controller: controller,
                      columns: 3,
                      cardType: ProxyCardType.standard,
                    )
                  : null,
            );
            final viewport = find
                .byType(tab ? GridView : CustomScrollView)
                .first;
            final scrollable = find
                .descendant(of: viewport, matching: find.byType(Scrollable))
                .first;
            final position = tester.state<ScrollableState>(scrollable).position;
            double opacity() =>
                scrollbarPainter(tester).fadeoutOpacityAnimation.value;
            expect(position.maxScrollExtent, greaterThan(100));
            expect(
              tester
                  .widget<CommonScrollBar>(find.byType(CommonScrollBar).first)
                  .thumbVisibility,
              isFalse,
            );
            await tester.pump(const Duration(seconds: 2));
            await tester.pump(const Duration(milliseconds: 300));
            expect(opacity(), 0);

            final initialOffset = position.pixels;
            await tester.drag(viewport, const Offset(0, -160));
            await tester.pump();
            expect(position.pixels, greaterThan(initialOffset));
            await tester.pump(const Duration(milliseconds: 300));
            expect(opacity(), 1);
            await tester.pump(const Duration(seconds: 2));
            await tester.pump(const Duration(milliseconds: 300));
            expect(opacity(), 0);

            await tester.drag(viewport, const Offset(0, -80));
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 300));
            expect(opacity(), 1);
            final thumb = await revealScrollbarThumb(
              tester,
              scrollable: scrollable,
            );
            if (Theme.of(tester.element(viewport)).platform ==
                TargetPlatform.macOS) {
              final mouse = await tester.createGesture(
                kind: PointerDeviceKind.mouse,
              );
              await mouse.addPointer(location: Offset.zero);
              await mouse.moveTo(thumb);
              await tester.pump();
              await tester.pump(const Duration(milliseconds: 150));
              expect(scrollbarPainter(tester).thickness, 10);
              await mouse.removePointer();
              await tester.pump(const Duration(milliseconds: 150));
            }
            final before = position.pixels;
            final gesture = await tester.startGesture(thumb);
            await gesture.moveBy(const Offset(0, 40));
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 150));
            expect(position.pixels, greaterThan(before));
            await tester.pump(const Duration(seconds: 2));
            expect(opacity(), 1);
            await gesture.up();
            await tester.pumpAndSettle(const Duration(seconds: 2));
            expect(opacity(), 0);
            expect(tester.takeException(), isNull);
          } finally {
            await unmountList(tester);
            controller.dispose();
          }
        },
        variant: const TargetPlatformVariant({
          TargetPlatform.android,
          TargetPlatform.macOS,
        }),
      );
    }
  }

  testWidgets('glass leaves the filled group icon well transparent', (
    tester,
  ) async {
    await pumpList(tester, groups: [_group('全球 🌐 节点', 0)], glass: true);
    container.read(proxiesStyleSettingProvider.notifier).value = container
        .read(proxiesStyleSettingProvider)
        .copyWith(iconStyle: ProxiesIconStyle.filled);
    await tester.pumpAndSettle();

    final iconContainers = tester.widgetList<Container>(
      find.ancestor(
        of: find.byType(GroupMonogram),
        matching: find.byType(Container),
      ),
    );
    final well = iconContainers.singleWhere(
      (widget) => widget.decoration is ShapeDecoration,
    );
    expect((well.decoration! as ShapeDecoration).color, Colors.transparent);
    expect(find.text('🌐'), findsOneWidget);
    expect(find.text('全球 节点'), findsOneWidget);
    expect(find.text('全球 🌐 节点'), findsNothing);
  });

  testWidgets('group headings retain full semantics without emoji', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    try {
      for (final (name, title) in [
        ('普通 A1 名称', '普通 A1 名称'),
        ('🤖 AI 🌐 服务', 'AI 服务'),
        ('家庭 👨‍👩‍👧‍👦 👍🏽 🇭🇰 节点', '家庭 节点'),
        ('🌐🇭🇰👨‍👩‍👧‍👦', ''),
      ]) {
        await pumpList(tester, groups: [_group(name, 0)]);
        container.read(proxiesStyleSettingProvider.notifier).value = container
            .read(proxiesStyleSettingProvider)
            .copyWith(iconStyle: ProxiesIconStyle.filled);
        await tester.pumpAndSettle();

        expect(find.text(title), findsOneWidget, reason: name);
        final titleSemantics = find.byWidgetPredicate(
          (widget) => widget is Semantics && widget.properties.label == name,
        );
        expect(titleSemantics, findsOneWidget);
        expect(tester.getSemantics(titleSemantics).label, contains(name));
        expect(container.read(groupsProvider).single.name, name);
      }
    } finally {
      semantics.dispose();
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(Duration.zero);
    }
  });

  testWidgets('explicit images replace emoji decorations in titles', (
    tester,
  ) async {
    const name = 'AI 🤖 服务';
    final svg = base64Encode(
      utf8.encode(
        '<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1">'
        '<rect width="1" height="1" fill="red"/></svg>',
      ),
    );
    final group = _group(
      name,
      0,
    ).copyWith(icon: 'data:image/svg+xml;base64,$svg');
    await pumpList(tester, groups: [group]);
    container.read(proxiesStyleSettingProvider.notifier).value = container
        .read(proxiesStyleSettingProvider)
        .copyWith(iconStyle: ProxiesIconStyle.plain);
    await tester.pumpAndSettle();

    expect(find.text('AI 服务'), findsOneWidget);
    expect(find.text(name), findsNothing);
    final avatar = find.byType(CommonTargetIcon);
    expect(
      find.descendant(of: avatar, matching: find.byType(SvgPicture)),
      findsOneWidget,
    );
    expect(
      find.descendant(of: avatar, matching: find.byType(GroupMonogram)),
      findsNothing,
    );
    expect(
      tester.widget<CommonTargetIcon>(find.byType(CommonTargetIcon)).src,
      group.icon,
    );

    container.read(proxiesStyleSettingProvider.notifier).value = container
        .read(proxiesStyleSettingProvider)
        .copyWith(iconStyle: ProxiesIconStyle.hidden);
    await tester.pumpAndSettle();

    expect(find.text(name), findsOneWidget);
    expect(find.byType(CommonTargetIcon), findsNothing);
  });

  testWidgets('display titles preserve identity, selection and node flags', (
    tester,
  ) async {
    const name = '🌐 全球节点';
    const node = '🇭🇰 Hong Kong';
    final group = _group(name, 0).copyWith(
      all: [const Proxy(name: node, type: 'ss')],
    );
    await pumpList(
      tester,
      groups: [group],
      selectedMap: {name: node},
      unfoldSet: {},
    );

    expect(find.text('全球节点'), findsOneWidget);
    expect(find.text(node), findsOneWidget);
    expect(container.read(selectedProxyNameProvider(name)), node);
    expect(container.read(currentProfileProvider)?.selectedMap, {name: node});
    expect(tester.widget<ListHeader>(headerOf(name)).group.name, name);

    await tester.tap(actionOf(name, 'Expand'));
    await tester.pumpAndSettle();
    expect(container.read(unfoldSetProvider), contains(name));
    expect(find.byKey(const ValueKey('$name.$node')), findsWidgets);

    await tester.tap(actionOf(name, 'Delay test'));
    await tester.pump();
    expect(_RecordingProxiesAction.tested, [name]);

    await tester.tap(
      find.descendant(of: headerOf(name), matching: find.text('全球节点')),
    );
    await tester.pumpAndSettle();
    expect(container.read(unfoldSetProvider), isNot(contains(name)));

    await tester.tap(actionOf(name, 'Expand'));
    await tester.pumpAndSettle();
    expect(container.read(unfoldSetProvider), contains(name));
    expect(find.byKey(const ValueKey('$name.$node')), findsWidgets);
    expect(container.read(selectedProxyNameProvider(name)), node);
  });

  testWidgets('scroll to selected lands the row under the pinned header', (
    tester,
  ) async {
    await pumpList(tester, selectedMap: {'G1': 'G1-10'});
    await revealHeaderOf(tester, 'G1');

    await tester.tap(actionOf('G1', 'Scroll to selected'));
    await tester.pumpAndSettle();

    final header = tester.getRect(headerOf('G1'));
    final card = tester.getRect(find.byKey(const ValueKey('G1.G1-10')).first);
    expect(card.top, header.bottom);
  });

  for (final viewMode in [ViewMode.mobile, ViewMode.desktop]) {
    testWidgets('a pinned ${viewMode.name} group consumes drags before the page', (
      tester,
    ) async {
      await pumpList(
        tester,
        viewMode: viewMode,
        groups: [
          _group('G0', 0),
          _group('G1', 0),
          _group('G2', 120),
          _group('G3', 120),
          for (var i = 4; i < 10; i++) _group('G$i', 0),
        ],
        unfoldSet: {'G2', 'G3'},
      );
      final outer = tester.state<ScrollableState>(scrollable()).position;
      final inner = tester.state<ScrollableState>(groupScroll('G2')).position;
      final pinnedTop = tester.getRect(headerOf('G0')).top;
      final anchor = tester.getRect(headerOf('G2')).top - pinnedTop;
      Offset bodyPoint() {
        final header = tester.getRect(headerOf('G2'));
        return Offset(header.center.dx, header.bottom + 36);
      }

      final gesture = await tester.startGesture(bodyPoint());
      await gesture.moveBy(const Offset(0, -24));
      await tester.pump();
      await gesture.moveBy(const Offset(0, -20));
      await tester.pump();
      expect(outer.pixels, greaterThan(0));
      expect(outer.pixels, lessThan(anchor));
      expect(inner.pixels, 0);
      await gesture.moveBy(Offset(0, -anchor - 80));
      await tester.pump();
      expect(outer.pixels, closeTo(anchor, 0.01));
      expect(inner.pixels, greaterThan(0));
      final before = inner.pixels;
      await gesture.moveBy(const Offset(0, -70));
      await tester.pump();
      expect(outer.pixels, closeTo(anchor, 0.01));
      expect(inner.pixels, greaterThan(before));
      expect(tester.getRect(headerOf('G2')).top, closeTo(pinnedTop, 0.01));
      if (viewMode == ViewMode.desktop) {
        final other = tester.state<ScrollableState>(groupScroll('G3')).position;
        expect(other.pixels, 0);
      }
      await gesture.cancel();
      await tester.pumpAndSettle();
      final beforeWheel = inner.pixels;
      await tester.sendEventToBinding(
        PointerScrollEvent(
          position: bodyPoint(),
          scrollDelta: const Offset(0, 80),
        ),
      );
      await tester.pumpAndSettle();
      expect(inner.pixels, greaterThan(beforeWheel));
      expect(outer.pixels, closeTo(anchor, 0.01));

      inner.jumpTo(inner.maxScrollExtent - 30);
      await tester.pump();
      final tail = await tester.startGesture(bodyPoint());
      await tail.moveBy(const Offset(0, -24));
      await tester.pump();
      await tail.moveBy(const Offset(0, -100));
      await tester.pump();
      expect(inner.pixels, inner.maxScrollExtent);
      expect(outer.pixels, greaterThan(anchor));
      await tail.cancel();
      await tester.pumpAndSettle();

      final reverse = await tester.startGesture(bodyPoint());
      await reverse.moveBy(const Offset(0, 24));
      await tester.pump();
      await reverse.moveBy(const Offset(0, 130));
      await tester.pump();
      expect(outer.pixels, closeTo(anchor, 0.01));
      expect(inner.pixels, lessThan(inner.maxScrollExtent));
      await reverse.cancel();
      await tester.pumpAndSettle();

      inner.jumpTo(20);
      await tester.pump();
      final head = await tester.startGesture(bodyPoint());
      await head.moveBy(const Offset(0, 24));
      await tester.pump();
      await head.moveBy(const Offset(0, 100));
      await tester.pump();
      expect(inner.pixels, 0);
      expect(outer.pixels, lessThan(anchor));
      await head.cancel();
      await tester.pumpAndSettle();
      outer.jumpTo(anchor);
      await tester.pump();
      final tailPosition = tester.state<ScrollableState>(groupScroll('G2')).position;
      tailPosition.jumpTo(tailPosition.maxScrollExtent - 10);
      await tester.pump();
      await tester.fling(groupScroll('G2'), const Offset(0, -200), 1000);
      await tester.pumpAndSettle();
      expect(outer.pixels, greaterThan(anchor));
      expect(tester.takeException(), isNull);
      await unmountList(tester);
    });
  }

  testWidgets('a short expanded group hands its drag to the page', (
    tester,
  ) async {
    await pumpList(
      tester,
      groups: [_group('G0', 2), _group('G1', 120)],
      unfoldSet: {'G0', 'G1'},
    );
    final outer = tester.state<ScrollableState>(scrollable()).position;
    final inner = tester.state<ScrollableState>(groupScroll('G0')).position;
    expect(inner.maxScrollExtent, 0);
    final header = tester.getRect(headerOf('G0'));
    final gesture = await tester.startGesture(
      Offset(header.center.dx, header.bottom + 30),
    );
    await gesture.moveBy(const Offset(0, -24));
    await tester.pump();
    await gesture.moveBy(const Offset(0, -100));
    await tester.pump();
    expect(inner.pixels, 0);
    expect(outer.pixels, greaterThan(0));
    await gesture.cancel();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await unmountList(tester);
  });

  testWidgets('a group drag grows its sheet before scrolling nodes', (
    tester,
  ) async {
    await pumpList(
      tester,
      groups: [_group('G0', 120)],
      unfoldSet: {'G0'},
      view: Builder(
        builder: (context) => TextButton(
          onPressed: () {
            showSheet<void>(
              context: context,
              builder: (_) => const CommonScaffold(
                title: 'proxies',
                body: ProxiesListView(),
              ),
            );
          },
          child: const Text('open proxies'),
        ),
      ),
    );
    tester.view.physicalSize = const Size(390, 700);
    await tester.pumpAndSettle();
    await tester.tap(find.text('open proxies'));
    await tester.pumpAndSettle();
    final inner = tester.state<ScrollableState>(groupScroll('G0')).position;
    final before = tester.getRect(headerOf('G0'));
    final gesture = await tester.startGesture(
      Offset(before.center.dx, before.bottom + 30),
    );
    await gesture.moveBy(const Offset(0, -24));
    await tester.pump();
    await gesture.moveBy(const Offset(0, -80));
    await tester.pump();
    expect(tester.getRect(headerOf('G0')).top, lessThan(before.top));
    expect(inner.pixels, 0);
    await gesture.cancel();
    await tester.pumpAndSettle();
    tester.state<NavigatorState>(find.byType(Navigator).first).pop();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await unmountList(tester);
  });

  testWidgets('a group caches its offset offscreen and resets it when folded', (
    tester,
  ) async {
    await pumpList(
      tester,
      groups: [for (var i = 0; i < 3; i++) _group('G$i', 120)],
    );
    final outer = tester.state<ScrollableState>(scrollable()).position;
    var inner = tester.state<ScrollableState>(groupScroll('G0')).position;
    inner.jumpTo(400);
    await tester.pump();
    outer.jumpTo(outer.maxScrollExtent);
    await tester.pump();
    expect(
      find.byWidgetPredicate(
        (widget) => widget is ProxyCard && widget.groupName == 'G0',
        skipOffstage: false,
      ),
      findsNothing,
    );
    outer.jumpTo(0);
    await tester.pump();
    inner = tester.state<ScrollableState>(groupScroll('G0')).position;
    expect(inner.pixels, 400);
    final action = container.read(proxiesActionProvider.notifier);
    action.updateCurrentUnfoldSet({'G1', 'G2'});
    await tester.pumpAndSettle();
    action.updateCurrentUnfoldSet({'G0', 'G1', 'G2'});
    await tester.pumpAndSettle();
    inner = tester.state<ScrollableState>(groupScroll('G0')).position;
    expect(inner.pixels, 0);
    expect(tester.takeException(), isNull);
    await unmountList(tester);
  });

  testWidgets('group wheel scrolling preserves sheet detents and settling', (
    tester,
  ) async {
    await pumpList(
      tester,
      groups: [_group('G0', 120)],
      unfoldSet: {'G0'},
      view: Builder(
        builder: (context) => TextButton(
          onPressed: () {
            showSheet<void>(
              context: context,
              builder: (_) => const CommonScaffold(
                title: 'proxies',
                body: ProxiesListView(),
              ),
            );
          },
          child: const Text('open proxies'),
        ),
      ),
    );
    tester.view.physicalSize = const Size(390, 700);
    await tester.pumpAndSettle();
    await tester.tap(find.text('open proxies'));
    await tester.pumpAndSettle();
    final outer = tester.state<ScrollableState>(scrollable()).position;
    final pointer = TestPointer(11, PointerDeviceKind.mouse);
    Future<void> wheel(Offset delta, int milliseconds) async {
      final header = tester.getRect(headerOf('G0'));
      await tester.sendEventToBinding(
        pointer.hover(Offset(header.center.dx, header.bottom + 30)),
      );
      await tester.sendEventToBinding(
        pointer.scroll(delta, timeStamp: Duration(milliseconds: milliseconds)),
      );
    }

    ScrollPosition inner() =>
        tester.state<ScrollableState>(groupScroll('G0')).position;
    final collapsedTop = tester.getRect(headerOf('G0')).top;
    await wheel(const Offset(0, 60), 0);
    await tester.pump(const Duration(milliseconds: 30));
    expect(inner().pixels, 0);
    await wheel(const Offset(60, 0), 30);
    await tester.pumpAndSettle();
    final expandedTop = tester.getRect(headerOf('G0')).top;
    expect(expandedTop, lessThan(collapsedTop));
    expect(inner().pixels, 0);
    expect(outer.pixels, 0);

    await wheel(const Offset(0, 60), 1000);
    await tester.pumpAndSettle();
    expect(inner().pixels, 60);
    expect(outer.pixels, 0);
    await wheel(const Offset(0, -60), 2000);
    await tester.pumpAndSettle();
    expect(inner().pixels, 0);
    expect(tester.getRect(headerOf('G0')).top, closeTo(expandedTop, 1));

    await wheel(const Offset(0, 20), 3000);
    await tester.pumpAndSettle();
    expect(inner().pixels, 20);
    await wheel(const Offset(0, -100), 4000);
    await tester.pump(const Duration(milliseconds: 30));
    expect(inner().pixels, 0);
    await wheel(const Offset(0, 60), 4030);
    await wheel(const Offset(60, 0), 4040);
    await tester.pumpAndSettle();
    expect(tester.getRect(headerOf('G0')).top, closeTo(collapsedTop, 1));
    expect(inner().pixels, 0);
    expect(outer.pixels, 0);
    tester.state<NavigatorState>(find.byType(Navigator).first).pop();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await unmountList(tester);
  });

  testWidgets('folding a group leaves the cards of the others as they were', (
    tester,
  ) async {
    await pumpList(tester);
    final card = find.byKey(const ValueKey('G0.G0-0')).first;
    final before = tester.widget(card);

    container.read(proxiesActionProvider.notifier).updateCurrentUnfoldSet({
      'G0',
      'G1',
    });
    await tester.pump();

    expect(tester.widget(card), same(before));
  });

  for (final viewMode in [ViewMode.mobile, ViewMode.desktop]) {
    for (final glass in [false, true]) {
      testWidgets(
        'collapsed runs preserve ${viewMode.name} geometry and reverse '
        'an external unfold with glass $glass',
        (tester) async {
          await pumpList(
            tester,
            groups: [for (var i = 0; i < 8; i++) _group('G$i', 4)],
            unfoldSet: {},
            viewMode: viewMode,
            glass: glass,
          );
          final skeletons = find.byWidgetPredicate(
            (widget) => widget.runtimeType.toString() == '_GroupSliver',
          );
          final runs = find.byWidgetPredicate(
            (widget) => widget.runtimeType.toString() == '_CollapsedGroupRun',
          );
          final initialRuns = viewMode == ViewMode.mobile ? 1 : 2;
          expect(skeletons, findsNothing);
          expect(runs, findsNWidgets(initialRuns));
          final before = tester.getRect(headerOf('G3'));
          final position = tester.state<ScrollableState>(scrollable()).position;
          final initialExtent = position.maxScrollExtent;
          final action = container.read(proxiesActionProvider.notifier);
          final controllers = <AnimationController>[];
          void captureController(ObjectEvent event) {
            if (event is ObjectCreated &&
                event.object is AnimationController &&
                StackTrace.current.toString().contains(
                  '_GroupSliverState.initState',
                )) {
              controllers.add(event.object as AnimationController);
            }
          }

          FlutterMemoryAllocations.instance.addListener(captureController);
          try {
            action.updateCurrentUnfoldSet({'G2'});
            await tester.pump();
          } finally {
            FlutterMemoryAllocations.instance.removeListener(captureController);
          }
          expect(skeletons, findsOneWidget);
          expect(controllers, hasLength(1));
          final controller = controllers.single;
          expect(
            controller.value,
            0,
            reason: 'new group starts at zero; status ${controller.status}',
          );
          expect(controller.status, AnimationStatus.forward);
          await tester.pump(const Duration(milliseconds: 70));
          expect(controller.value, inExclusiveRange(0, 1));
          final entries = find.descendant(
            of: skeletons,
            matching: find.byType(UnfoldEntryTransition),
            skipOffstage: false,
          );
          expect(entries, findsWidgets);
          final entry = tester.widget<UnfoldEntryTransition>(entries.first);
          expect((entry.animation as CurvedAnimation).parent, same(controller));
          action.updateCurrentUnfoldSet({});
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 20));
          expect(skeletons, findsOneWidget);
          action.updateCurrentUnfoldSet({'G2'});
          await tester.pumpAndSettle();
          expect(skeletons, findsOneWidget);
          expect(controller.value, 1);
          expect(controller.status, AnimationStatus.completed);
          expect(find.byType(UnfoldEntryTransition), findsNothing);
          expect(runs, findsNWidgets(initialRuns + 1));
          expect(find.byType(ProxyCard), findsWidgets);
          if (glass) {
            expect(glassGroupRect(tester, 'G0'), headerInHost(tester, 'G0'));
          }
          action.updateCurrentUnfoldSet({});
          await tester.pumpAndSettle();
          expect(skeletons, findsNothing);
          expect(runs, findsNWidgets(initialRuns));
          expect(find.byType(ProxyCard), findsNothing);
          expect(tester.getRect(headerOf('G3')), before);
          expect(position.maxScrollExtent, initialExtent);
          expect(tester.takeException(), isNull);
          await unmountList(tester);
        },
      );
    }
  }

  testWidgets('splitting a collapsed run preserves another expanded group', (
    tester,
  ) async {
    await pumpList(
      tester,
      groups: [for (var i = 0; i < 5; i++) _group('G$i', 2)],
      unfoldSet: {'G0'},
    );
    final card = find.byKey(const ValueKey('G0.G0-0')).first;
    final before = tester.widget(card);
    final action = container.read(proxiesActionProvider.notifier);
    action.updateCurrentUnfoldSet({'G0', 'G2'});
    await tester.pumpAndSettle();
    expect(tester.widget(card), same(before));
    action.updateCurrentUnfoldSet({'G0'});
    await tester.pumpAndSettle();
    expect(tester.widget(card), same(before));
    expect(tester.takeException(), isNull);
    await unmountList(tester);
  });

  testWidgets('a newly opened group locates its selection after a folded run', (
    tester,
  ) async {
    await pumpList(
      tester,
      groups: [
        _group('G0', 4),
        _group('G1', 4),
        _group('G2', 60),
        for (var i = 3; i < 13; i++) _group('G$i', 0),
      ],
      unfoldSet: {},
      selectedMap: {'G2': 'G2-15'},
    );
    await tester.tap(actionOf('G2', 'Expand'));
    await tester.pumpAndSettle();
    await tester.tap(actionOf('G2', 'Scroll to selected'));
    await tester.pumpAndSettle();
    final position = tester.state<ScrollableState>(scrollable()).position;
    expect(position.pixels, lessThan(position.maxScrollExtent));
    expect(
      tester.getRect(find.byKey(const ValueKey('G2.G2-15')).first).top,
      tester.getRect(headerOf('G2')).bottom,
    );
    expect(tester.takeException(), isNull);
    await unmountList(tester);
  });

  testWidgets('external fold changes before mounting leave no group skeleton', (
    tester,
  ) async {
    await pumpList(tester, unfoldSet: {});
    final action = container.read(proxiesActionProvider.notifier);
    action.updateCurrentUnfoldSet({'G0'});
    expect(container.read(unfoldSetProvider), {'G0'});
    action.updateCurrentUnfoldSet({});
    expect(container.read(unfoldSetProvider), isEmpty);
    await tester.pumpAndSettle();
    expect(
      find.byWidgetPredicate(
        (widget) => widget.runtimeType.toString() == '_GroupSliver',
      ),
      findsNothing,
    );
    expect(find.byType(ProxyCard), findsNothing);
    expect(tester.takeException(), isNull);
    await unmountList(tester);
  });

  testWidgets('a group unfolds its rows from under its header and folds them '
      'back', (tester) async {
    await pumpList(tester);
    container.read(proxiesActionProvider.notifier).updateCurrentUnfoldSet({
      'G1',
      'G2',
    });
    await tester.pumpAndSettle();
    final radius = AppCorner.xl.ap(tester.element(find.text('G0')));
    final unfolding = find.byType(UnfoldEntryTransition);
    final cards = find.byWidgetPredicate(
      (widget) => widget is ProxyCard && widget.groupName == 'G0',
    );
    BorderRadiusGeometry headerCorners() =>
        (surfaceOf(tester, 'G0').shape as RoundedSuperellipseBorder)
            .borderRadius;

    await tester.tap(find.text('G0'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 120));
    expect(unfolding, findsWidgets);
    expect(headerCorners(), AppRadius.all(radius));

    await tester.pumpAndSettle();
    expect(unfolding, findsNothing);
    expect(cards, findsWidgets);
    expect(headerCorners(), AppRadius.vertical(top: radius));

    await tester.tap(find.text('G0'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 120));
    expect(unfolding, findsWidgets);
    expect(headerCorners(), AppRadius.all(radius));

    await tester.pumpAndSettle();
    expect(cards, findsNothing);
  });

  testWidgets('the pinned header floats over its rows and leaves with the '
      'bottom of its group card', (tester) async {
    await pumpList(tester);
    expect(surfaceOf(tester, 'G0').shadows, isNull);

    final position = tester.state<ScrollableState>(scrollable()).position;
    position.jumpTo(200);
    await tester.pump();
    final floating = surfaceOf(tester, 'G0');
    expect(floating.shadows, isNotEmpty);
    expect(
      (floating.shape as RoundedSuperellipseBorder).borderRadius,
      AppRadius.all(AppCorner.xl.ap(tester.element(headerOf('G0')))),
    );

    final pinnedTop = tester.getRect(headerOf('G0')).top;
    while (headerOf('G1').evaluate().isEmpty) {
      position.jumpTo(position.pixels + 100);
      await tester.pump();
    }
    final nextTop = tester.getRect(headerOf('G1')).top;
    position.jumpTo(position.pixels + nextTop - pinnedTop - 20);
    await tester.pump();

    expect(
      tester.getRect(headerOf('G0')).bottom,
      tester.getRect(headerOf('G1')).top -
          cardSpacingOf(tester.element(headerOf('G1')), ViewMode.mobile),
    );
    expect(surfaceOf(tester, 'G0').shadows, isNull);
  });

  testWidgets('a folded header takes the surface color of a new theme', (
    tester,
  ) async {
    final brightness = ValueNotifier(Brightness.light);
    addTearDown(brightness.dispose);
    await pumpList(tester, unfoldSet: const {}, brightness: brightness);

    brightness.value = Brightness.dark;
    await tester.pumpAndSettle();

    final context = tester.element(headerOf('G0'));
    expect(context.colorScheme.brightness, Brightness.dark);
    expect(
      surfaceOf(tester, 'G0').color,
      context.raisedSurfaceLevel.colorOf(context.colorScheme),
    );
  });

  testWidgets('glass without wallpaper also removes opaque proxy masks', (
    tester,
  ) async {
    await pumpList(tester, glass: true, groups: [_group('G0', 4)]);
    final header = headerOf('G0');
    expect(surfaceOf(tester, 'G0').color, Colors.transparent);
    final pinned = find.ancestor(
      of: header,
      matching: find.byType(PinnedHeaderSliver),
    );
    expect(
      find.descendant(of: pinned, matching: find.byType(ColoredBox)),
      findsNothing,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('Aurora proxy groups share one envelope without a header pane', (
    tester,
  ) async {
    await pumpList(
      tester,
      aurora: true,
      groups: [_group('G0', 40)],
      unfoldSet: const {},
    );
    final header = headerOf('G0');
    final shapedPanes = find.byWidgetPredicate(
      (widget) => widget is LiquidGlass && widget.clipper is ShapeBorderClipper,
    );
    final shell = find.descendant(of: header, matching: shapedPanes);
    final buttonPanes = find.descendant(
      of: header,
      matching: find.byWidgetPredicate(
        (widget) => widget is LiquidGlass && widget.clipper == null,
      ),
    );
    expect(surfaceOf(tester, 'G0').color, Colors.transparent);
    expect(shell, findsNothing);
    final group = tester.widget<CardGroup>(
      find.descendant(of: header, matching: find.byType(CardGroup)),
    );
    expect(group.paintSurface, isFalse);
    expect(glassGroupRect(tester, 'G0'), headerInHost(tester, 'G0'));
    expect(
      find.descendant(
        of: find.descendant(of: header, matching: find.byType(CommonCard)),
        matching: shapedPanes,
      ),
      findsNothing,
    );
    expect(buttonPanes, findsOneWidget);
    expect(find.byType(LiquidGlassHost), findsOneWidget);
    final sharedHost = tester.element(find.byType(LiquidGlassHost));
    for (final pane in tester.widgetList<LiquidGlass>(buttonPanes)) {
      expect(pane.style.tint.a, lessThan(1));
      expect(
        tester.element(
          find.ancestor(
            of: find.byWidget(pane),
            matching: find.byType(LiquidGlassHost),
          ),
        ),
        same(sharedHost),
      );
    }
    final pinned = find.ancestor(
      of: header,
      matching: find.byType(PinnedHeaderSliver),
    );
    expect(
      find.descendant(of: pinned, matching: find.byType(ColoredBox)),
      findsNothing,
    );

    await tester.tap(find.text('G0'));
    await tester.pumpAndSettle();
    final before = tester.getRect(header);
    final beforeEnvelope = glassGroupRect(tester, 'G0');
    final position = tester.state<ScrollableState>(groupScroll('G0')).position;
    position.jumpTo(200);
    await tester.pumpAndSettle();
    final after = tester.getRect(header);
    expect(after.top, before.top);
    expect(glassGroupRect(tester, 'G0').top, headerInHost(tester, 'G0').top);
    expect(glassGroupRect(tester, 'G0').bottom, beforeEnvelope.bottom);
    expect(glassGroupRect(tester, 'G0').bottom, lessThanOrEqualTo(700));
    expect(
      tester.layers.whereType<ClipRectLayer>().map((layer) => layer.clipRect),
      contains(
        predicate<Rect?>(
          (rect) => rect != null && (rect.top - after.bottom).abs() < 0.01,
          'a row paint clip below the pinned header',
        ),
      ),
    );
    expect(tester.takeException(), isNull);
    await unmountList(tester);
  });

  testWidgets('glass group envelopes include their last row and rounded tail', (
    tester,
  ) async {
    await pumpList(tester, glass: true, groups: [_group('G0', 4)]);
    try {
      final header = headerInHost(tester, 'G0');
      final last = tester.getRect(
        find.byWidgetPredicate(
          (widget) => widget is ProxyCard && widget.proxy.name == 'G0-3',
        ),
      );
      final host = tester.renderObject<RenderBox>(find.byType(LiquidGlassHost));
      final lastInHost = last.shift(-host.localToGlobal(Offset.zero));
      final envelope = glassGroupRect(tester, 'G0');
      final first = tester.getRect(
        find
            .byWidgetPredicate(
              (widget) => widget is ProxyCard && widget.groupName == 'G0',
            )
            .first,
      );
      final inset =
          first.left - host.localToGlobal(Offset.zero).dx - header.left;
      expect(envelope.top, header.top);
      expect(envelope.bottom, moreOrLessEquals(lastInHost.bottom + inset));
      expect(envelope.height, greaterThan(header.height));
      final glassGroup = tester.widget<SliverGlassGroup>(
        find.ancestor(
          of: headerOf('G0'),
          matching: find.byType(SliverGlassGroup),
        ),
      );
      expect(
        glassGroup.shape.borderRadius,
        AppRadius.all(AppCorner.xl.ap(tester.element(headerOf('G0')))),
      );
    } finally {
      await unmountList(tester);
    }
  });

  testWidgets(
    'glass group tails stay fixed while the pinned group scrolls internally',
    (tester) async {
      await pumpList(
        tester,
        glass: true,
        groups: [_group('G0', 40), _group('G1', 0)],
      );
      try {
        final before = glassGroupRect(tester, 'G0');
        final position = tester.state<ScrollableState>(groupScroll('G0')).position;
        position.jumpTo(200);
        await tester.pumpAndSettle();
        final pinned = glassGroupRect(tester, 'G0');
        expect(pinned.top, before.top);
        expect(pinned.top, headerInHost(tester, 'G0').top);
        expect(pinned.bottom, moreOrLessEquals(before.bottom));
        expect(pinned.bottom, lessThanOrEqualTo(700));

        final lastCard = find.byWidgetPredicate(
          (widget) => widget is ProxyCard && widget.proxy.name == 'G0-39',
        );
        await tester.scrollUntilVisible(
          lastCard,
          200,
          scrollable: groupScroll('G0'),
        );
        await tester.pumpAndSettle();
        position.jumpTo(position.maxScrollExtent);
        await tester.pumpAndSettle();
        final last = tester.getRect(lastCard);
        final header = tester.getRect(headerOf('G0'));
        final host = tester.renderObject<RenderBox>(
          find.byType(LiquidGlassHost),
        );
        final first = tester.getRect(
          find
              .byWidgetPredicate(
                (widget) => widget is ProxyCard && widget.groupName == 'G0',
              )
              .first,
        );
        final expectedTail =
            last.bottom +
            first.left -
            header.left -
            host.localToGlobal(Offset.zero).dy;
        expect(
          glassGroupRect(tester, 'G0').bottom,
          moreOrLessEquals(expectedTail),
        );
        expect(
          glassGroupRect(tester, 'G0').top,
          headerInHost(tester, 'G0').top,
        );
      } finally {
        await unmountList(tester);
      }
    },
  );

  testWidgets(
    'glass envelopes follow unfolding frames and keep empty groups whole',
    (tester) async {
      await pumpList(
        tester,
        glass: true,
        groups: [_group('G0', 4), _group('Empty', 0)],
        unfoldSet: {'Empty'},
      );
      try {
        expect(glassGroupRect(tester, 'Empty'), headerInHost(tester, 'Empty'));
        final folded = glassGroupRect(tester, 'G0');
        await tester.tap(find.text('G0'));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 120));
        final opening = glassGroupRect(tester, 'G0');
        expect(opening.top, headerInHost(tester, 'G0').top);
        expect(opening.height, greaterThan(folded.height));
        await tester.pumpAndSettle();
        final unfolded = glassGroupRect(tester, 'G0');
        expect(unfolded.height, greaterThan(opening.height));
        await tester.tap(find.text('G0'));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 120));
        final closing = glassGroupRect(tester, 'G0');
        expect(closing.height, greaterThan(folded.height));
        expect(closing.height, lessThan(unfolded.height));
        await tester.pumpAndSettle();
        expect(glassGroupRect(tester, 'G0'), folded);
        expect(glassGroupRect(tester, 'Empty'), headerInHost(tester, 'Empty'));
      } finally {
        await unmountList(tester);
      }
    },
  );

  testWidgets('the last row sits as far from the bottom of its group card as '
      'from its sides', (tester) async {
    await pumpList(tester);
    final lastCard = find.byWidgetPredicate(
      (widget) => widget is ProxyCard && widget.proxy.name == 'G0-39',
    );
    await tester.scrollUntilVisible(lastCard, 200, scrollable: groupScroll('G0'));
    final position = tester.state<ScrollableState>(groupScroll('G0')).position;
    position.jumpTo(position.maxScrollExtent);
    await tester.pumpAndSettle();

    final header = tester.getRect(headerOf('G0'));
    final card = tester.getRect(lastCard);
    final nextHeader = tester.getRect(headerOf('G1'));
    final sideInset = header.right - card.right;
    expect(
      nextHeader.top -
          cardSpacingOf(tester.element(headerOf('G1')), ViewMode.mobile) -
          card.bottom,
      sideInset,
    );
  });

  testWidgets('a desktop window lays the groups out in columns and pins the '
      'header of an unfolded one', (tester) async {
    await pumpList(tester, viewMode: ViewMode.desktop);
    container.read(proxiesActionProvider.notifier).updateCurrentUnfoldSet({
      'G2',
    });
    await tester.pumpAndSettle();
    final first = tester.getRect(headerOf('G0'));
    final second = tester.getRect(headerOf('G1'));
    final third = tester.getRect(headerOf('G2'));

    expect(second.top, first.top);
    expect(
      second.left - first.right,
      cardSpacingOf(tester.element(headerOf('G1')), ViewMode.desktop),
    );
    expect(third.left, first.left);
    expect(third.top, greaterThan(first.bottom));

    final position = tester.state<ScrollableState>(scrollable()).position;
    position.jumpTo(40);
    await tester.pump();

    expect(tester.getRect(headerOf('G0')).top, first.top - 40);
    expect(tester.getRect(headerOf('G1')).top, second.top - 40);

    position.jumpTo(third.top - first.top + 200);
    await tester.pump();

    expect(tester.getRect(headerOf('G2')).top, first.top);
  });

  testWidgets('scroll to selected stays put without a selection', (
    tester,
  ) async {
    await pumpList(tester);
    await revealHeaderOf(tester, 'G1');
    final before = tester.getRect(headerOf('G1'));

    await tester.tap(actionOf('G1', 'Scroll to selected'));
    await tester.pumpAndSettle();

    expect(tester.getRect(headerOf('G1')), before);
  });

  testWidgets('a group name too long for its header stays on one line', (
    tester,
  ) async {
    await pumpList(
      tester,
      groups: [_group('Streaming Media Very Long Group Name 流媒体服务分组 ' * 3, 4)],
    );

    expect(tester.takeException(), isNull);
  });

  testWidgets('the delay test button tests its own group only', (tester) async {
    await pumpList(tester);

    await tester.tap(actionOf('G0', 'Delay test'));
    await tester.pump();

    expect(_RecordingProxiesAction.tested, ['G0']);
  });

  testWidgets('a group under test spins its button and ignores taps', (
    tester,
  ) async {
    await pumpList(tester);

    container.read(delayTestingGroupsProvider.notifier).start('G0');
    await tester.pump();

    expect(
      find.descendant(
        of: headerOf('G0'),
        matching: find.byType(CommonCircleLoading),
      ),
      findsOneWidget,
    );
    await tester.tap(actionOf('G0', 'Delay test'), warnIfMissed: false);
    await tester.pump();
    expect(_RecordingProxiesAction.tested, isEmpty);

    container.read(delayTestingGroupsProvider.notifier).stop('G0');
    await tester.pump();

    expect(find.byType(CommonCircleLoading), findsNothing);
  });
}
