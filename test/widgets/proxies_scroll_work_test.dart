import 'dart:convert';
import 'dart:io';

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart' hide ListHeader;
import 'package:fl_clash/views/proxies/card.dart';
import 'package:fl_clash/views/proxies/list.dart';
import 'package:fl_clash/views/proxies/tab.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

Group _group(int index, int nodes) => Group(
  type: GroupType.Selector,
  name: 'Group $index',
  all: [for (var i = 0; i < nodes; i++) Proxy(name: 'Node $index-$i', type: 'ss')],
);

final _layoutLine = RegExp(r'^Laying out \([^)]*\) (\S+)#([a-z0-9]+)');
final _identity = RegExp(r'^(\S+)#([a-z0-9]+)');

String? _renderIdentity(RenderObject render) =>
    _identity.firstMatch(render.toStringShort())?.group(0);

class _WorkCounter {
  final builds = <String, int>{};
  final layouts = <String, int>{};
  final groupLayouts = <String, int>{};
  final paints = <String, int>{};
  final paintedNodes = <String>{};
  final groupRenderIds = <String>{};
  int nodePaintCalls = 0;

  void clear() {
    builds.clear();
    layouts.clear();
    groupLayouts.clear();
    paints.clear();
    paintedNodes.clear();
    nodePaintCalls = 0;
  }

  void build(Element element, bool builtOnce) {
    final type = element.widget.runtimeType.toString();
    builds.update(type, (count) => count + 1, ifAbsent: () => 1);
  }

  void layout(String message) {
    final match = _layoutLine.firstMatch(message);
    if (match == null) return;
    final type = match.group(1)!;
    layouts.update(type, (count) => count + 1, ifAbsent: () => 1);
    final id = '$type#${match.group(2)}';
    if (groupRenderIds.contains(id)) {
      groupLayouts.update(id, (count) => count + 1, ifAbsent: () => 1);
    }
  }

  void paint(RenderObject render) {
    final type = render.runtimeType.toString();
    paints.update(type, (count) => count + 1, ifAbsent: () => 1);
    final creator = render.debugCreator;
    if (creator is! DebugCreator) return;
    creator.element.visitAncestorElements((element) {
      final widget = element.widget;
      if (widget is ProxyCard) {
        paintedNodes.add('${widget.groupName}/${widget.proxy.name}');
        nodePaintCalls++;
        return false;
      }
      return true;
    });
  }

  Map<String, Object?> toJson() => {
    'widgetBuildCallsByType': Map.of(builds),
    'actualLayoutCallsByRenderType': Map.of(layouts),
    'groupRootActualLayoutCalls': groupLayouts.values.fold<int>(0, (a, b) => a + b),
    'groupRootsActuallyLaidOut': groupLayouts.length,
    'renderPaintCallsByType': Map.of(paints),
    'nodeRenderPaintCalls': nodePaintCalls,
    'distinctNodesPainted': paintedNodes.length,
  };
}

Map<String, Object?> _snapshot(WidgetTester tester) {
  final cards = find.byType(ProxyCard, skipOffstage: false).evaluate().toList();
  final viewport = Offset.zero & const Size(390, 600);
  var offscreenCards = 0;
  for (final card in cards) {
    final render = card.findRenderObject()! as RenderBox;
    final rect = MatrixUtils.transformRect(render.getTransformTo(null), render.paintBounds);
    if (!rect.overlaps(viewport)) offscreenCards++;
  }
  var paneLayers = 0;
  var inkLayers = 0;
  void visit(ContainerLayer parent) {
    for (Layer? layer = parent.firstChild; layer != null; layer = layer.nextSibling) {
      if (layer.runtimeType.toString() == '_LiquidGlassLayer') paneLayers++;
      if (layer is LiquidGlassInkLayer) inkLayers++;
      if (layer is ContainerLayer) visit(layer);
    }
  }
  visit(tester.binding.renderViews.first.debugLayer!);
  final capture = LiquidGlassHost.debugLastCapture;
  final inks = LiquidGlassHost.debugLastInkCapture;
  return {
    'mountedCardsIncludingOffstage': cards.length,
    'mountedOffscreenCards': offscreenCards,
    'mountedGroupSkeletons': find.byWidgetPredicate(
      (widget) => widget.runtimeType.toString() == '_GroupSliver',
      skipOffstage: false,
    ).evaluate().length,
    'mountedHeaderContents': find.byType(ListHeader, skipOffstage: false).evaluate().length,
    'retainedPaneLayers': paneLayers,
    'retainedInkLayers': inkLayers,
    'sampledPanes': capture?.panes.length ?? 0,
    'sampledPanesFullyOutsideViewport': capture?.panes.where(
      (pane) => !pane.pane.overlaps(viewport),
    ).length ?? 0,
    'atlasPixels': capture?.atlasPixels ?? 0,
    'snapshots': capture?.snapshots ?? 0,
    'inkMasks': inks?.masks ?? 0,
    'inkFilterUpdatesCumulative': inks?.filterUpdates ?? 0,
    'inkTexturePixels': inks?.texturePixels ?? 0,
  };
}

void main() {
  final reports = <Map<String, Object?>>[];
  setUpAll(GlassInk.prepare);
  tearDownAll(() {
    final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
    if (output == null) return;
    final directory = Directory(output)..createSync(recursive: true);
    File('${directory.path}/proxies-scroll-work.json').writeAsStringSync(
      const JsonEncoder.withIndent('  ').convert({
        'viewportLogical': [390, 600],
        'dpr': 1,
        'layoutCounter': 'Flutter debugPrintLayouts, actual performLayout only; not early-return layout invocations',
        'timing': 'No FPS or duration claim; instrumentation has debug overhead',
        'dataBoundary': 'Synthetic local models and currentGroupsState fixture; real list/grid/card/Host rendering',
        'paneBoundary': 'Retained layer count and sampled viewport rectangles are separate; no private offscreen registration counter is invented',
        'cases': reports,
      }),
    );
  });

  final cases = [
    for (final groups in [10, 50, 200])
      (grid: false, groups: groups, nodes: 50, mode: LiquidGlassMode.standard),
    for (final nodes in [50, 1000])
      (grid: true, groups: 1, nodes: nodes, mode: LiquidGlassMode.standard),
    for (final mode in [LiquidGlassMode.none, LiquidGlassMode.ultra]) ...[
      (grid: false, groups: 50, nodes: 50, mode: mode),
      (grid: true, groups: 1, nodes: 1000, mode: mode),
    ],
  ];
  for (final scenario in cases) {
    testWidgets('real proxy scroll work ${scenario.grid ? 'grid' : 'collapsed'} '
        'G${scenario.groups} N${scenario.nodes} ${scenario.mode.name}', (tester) async {
      tester.view.physicalSize = const Size(390, 600);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final groups = [for (var i = 0; i < scenario.groups; i++) _group(i, scenario.nodes)];
      final profile = Profile.normal().copyWith(unfoldSet: const {});
      final container = ProviderContainer(overrides: [
        viewModeProvider.overrideWithValue(ViewMode.mobile),
        currentProfileIdProvider.overrideWithBuild((_, _) => profile.id),
        profilesProvider.overrideWith(() => TestProfiles([profile])),
        currentGroupsStateProvider.overrideWithValue(GroupsState(value: groups)),
      ]);
      globalState.container = container;
      container.read(groupsProvider.notifier).value = groups;
      final controller = ScrollController();
      final work = _WorkCounter();
      final oldPrint = debugPrint;
      final oldLayouts = debugPrintLayouts;
      final oldBuild = debugOnRebuildDirtyWidget;
      final oldPaint = debugOnProfilePaint;
      final oldProfilePaints = debugProfilePaintsEnabled;
      final frames = <Map<String, Object?>>[];
      LiquidGlassHost.debugLastCapture = null;
      LiquidGlassHost.debugLastInkCapture = null;
      try {
        await tester.pumpWidget(UncontrolledProviderScope(
          container: container,
          child: TestApp(
            homeBuilder: (child) => Scaffold(body: LiquidGlassScope(
              enabled: scenario.mode != LiquidGlassMode.none,
              quality: scenario.mode == LiquidGlassMode.ultra
                  ? LiquidGlassQuality.ultra : LiquidGlassQuality.standard,
              child: LiquidGlassHost(
                enabled: scenario.mode != LiquidGlassMode.none,
                wallpaperOnly: true,
                child: Stack(children: [
                  const Positioned.fill(child: LiquidGlassColorField(
                    child: ColoredBox(color: Color(0xFF284858)),
                  )),
                  child,
                ]),
              ),
            )),
            child: scenario.grid ? ProxyGroupView(
              group: groups.first, controller: controller, columns: 2,
              cardType: ProxyCardType.standard,
            ) : const ProxiesListView(),
          ),
        ));
        await tester.pumpAndSettle();
        final skeletons = find.byWidgetPredicate(
          (widget) => widget.runtimeType.toString() == '_GroupSliver',
          skipOffstage: false,
        );
        for (final element in skeletons.evaluate()) {
          final id = _renderIdentity(element.findRenderObject()!);
          if (id != null) work.groupRenderIds.add(id);
        }
        debugPrintLayouts = true;
        debugPrint = (message, {wrapWidth}) {
          if (message?.startsWith('Laying out ') == true) {
            work.layout(message!);
          } else {
            oldPrint(message, wrapWidth: wrapWidth);
          }
        };
        debugOnRebuildDirtyWidget = work.build;
        debugOnProfilePaint = work.paint;
        debugProfilePaintsEnabled = true;
        frames.add({'step': 0, ..._snapshot(tester)});
        final scrollable = find.descendant(
          of: find.byType(scenario.grid ? GridView : CustomScrollView),
          matching: find.byType(Scrollable),
        ).first;
        final position = tester.state<ScrollableState>(scrollable).position;
        expect(position.maxScrollExtent, greaterThan(0));
        for (var step = 1; step <= 3; step++) {
          work.clear();
          position.jumpTo(position.maxScrollExtent * step / 4);
          await tester.pump(const Duration(milliseconds: 16));
          final snapshot = _snapshot(tester);
          frames.add({'step': step, 'scrollPixels': position.pixels,
            ...snapshot, ...work.toJson()});
          if (!scenario.grid) {
            expect(snapshot['mountedCardsIncludingOffstage'], 0);
            expect(work.nodePaintCalls, 0);
            expect(work.builds['ProxyCard'] ?? 0, 0);
            expect(snapshot['mountedGroupSkeletons'], scenario.groups);
            expect(work.groupLayouts, isNotEmpty);
          } else {
            expect(snapshot['mountedCardsIncludingOffstage'] as int,
              inInclusiveRange(1, 49));
            expect(work.groupRenderIds, isEmpty);
            expect(work.nodePaintCalls, greaterThan(0));
          }
          expect(snapshot['sampledPanesFullyOutsideViewport'], 0);
        }
        reports.add({'grid': scenario.grid, 'groups': scenario.groups,
          'nodesPerGroup': scenario.nodes, 'mode': scenario.mode.name, 'frames': frames});
        expect(tester.takeException(), isNull);
      } finally {
        debugPrint = oldPrint;
        debugPrintLayouts = oldLayouts;
        debugOnRebuildDirtyWidget = oldBuild;
        debugOnProfilePaint = oldPaint;
        debugProfilePaintsEnabled = oldProfilePaints;
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
        controller.dispose();
        container.dispose();
      }
    });
  }
}
