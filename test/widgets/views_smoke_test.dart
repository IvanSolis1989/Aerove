import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:fl_clash/features/form/form.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/action.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:fl_clash/providers/state.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/paged_sheet.dart';
import 'package:fl_clash/ui/popup.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/open_container.dart';
import 'package:fl_clash/ui/card.dart';
import 'package:fl_clash/views/config/advanced.dart';
import 'package:fl_clash/views/config/custom_resources.dart';
import 'package:fl_clash/views/config/dns.dart';
import 'package:fl_clash/views/config/ntp.dart';
import 'package:fl_clash/views/config/sniffer.dart';
import 'package:fl_clash/views/config/tun.dart';
import 'package:fl_clash/views/config/general.dart';
import 'package:fl_clash/views/config/network.dart';
import 'package:fl_clash/views/config/on_demand.dart';
import 'package:fl_clash/views/config/filters.dart';
import 'package:fl_clash/views/config/rules.dart';
import 'package:fl_clash/views/config/scripts.dart';
import 'package:fl_clash/views/config/user_agents.dart';
import 'package:fl_clash/views/hotkey.dart';
import 'package:fl_clash/views/profiles/custom/custom.dart';
import 'package:fl_clash/views/profiles/custom/custom_proxies.dart';
import 'package:fl_clash/views/profiles/custom/rules.dart';
import 'package:fl_clash/views/proxies/list.dart';
import 'package:fl_clash/views/proxies/tab.dart';
import 'package:fl_clash/views/theme.dart';
import 'package:fl_clash/views/views.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/rendering.dart';

import '../helpers/test_app.dart';
import '../helpers/test_database_providers.dart';
import '../helpers/test_profiles.dart';

Finder _portField(String label) => find.descendant(
  of: find.widgetWithText(FormRow, label),
  matching: find.byType(TextFormField),
);

void main() {
  final cases = <String, Widget>{
    'dashboard': const DashboardView(),
    'proxies': const ProxiesView(),
    'profiles': const ProfilesView(),
    'requests': const RequestsView(),
    'dns queries': const DnsQueriesView(),
    'resources': const ResourcesView(),
    'logs': const LogsView(),
    'tools': const ToolsView(),
    'general settings': const GeneralView(),
    'dns config': const DnsView(),
    'ntp config': NtpView(1),
    'sniffer config': SnifferView(1),
    'tun config': TunView(1),
    'network config': const Scaffold(body: NetworkListView()),
    'advanced config': const AdvancedConfigView(),
    'custom profile resources': const CustomResourcesView(),
    'on demand config': const OnDemandView(),
    'theme': const ThemeView(),
    'backup and restore': const BackupAndRestore(),
    'hotkeys': const HotKeyView(),
    'access control': const AccessView(),
    'added rules': const AddedRulesView(),
    'scripts': const ScriptsView(),
    'user agents': const UserAgentsView(),
    'filters': const FiltersView(),
  };

  for (final entry in cases.entries) {
    testWidgets('${entry.key} renders its default state', (tester) async {
      tester.view.physicalSize = const Size(1400, 1000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      final container = ProviderContainer(
        overrides: [
          profilesProvider.overrideWith(TestProfiles.new),
          scriptsProvider.overrideWith(TestScripts.new),
          globalRulesProvider.overrideWith(TestGlobalRules.new),
          iconSetsProvider.overrideWith(TestIconSets.new),
          clashProvidersProvider.overrideWith(TestClashProviders.new),
        ],
      );
      addTearDown(container.dispose);
      globalState.container = container;

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: TestApp(child: entry.value),
        ),
      );
      await tester.pump();
      if (entry.key == 'access control') {
        await tester.pump(const Duration(milliseconds: 301));
      }
      final scrollables = find.byType(Scrollable);
      if (scrollables.evaluate().isNotEmpty) {
        for (var index = 0; index < 8; index++) {
          await tester.drag(scrollables.first, const Offset(0, -700));
          await tester.pump();
        }
      }

      expect(find.byWidget(entry.value), findsOneWidget);
      expect(tester.takeException(), null);

      await tester.pumpWidget(const SizedBox.shrink());
    });
  }

  final toolDestinations = <String, Type>{
    'Theme': ThemeView,
    'Backup and restore': BackupAndRestore,
    'General': GeneralView,
    'Advanced configuration': AdvancedConfigView,
    'Custom profile resources': CustomResourcesView,
  };

  for (final brightness in Brightness.values) {
    for (final quality in [null, ...LiquidGlassQuality.values]) {
      for (final label in ['Theme', 'General', 'Advanced configuration']) {
        testWidgets('mobile $label route wallpaper $brightness $quality', (
          tester,
        ) async {
          tester.view.physicalSize = const Size(390, 800);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          final container = ProviderContainer(
            overrides: [profilesProvider.overrideWith(TestProfiles.new)],
          );
          addTearDown(container.dispose);
          globalState.container = container;
          container.read(viewSizeProvider.notifier).update(
            (_) => const Size(390, 800),
          );
          final key = GlobalKey();
          final theme = ThemeData(
            brightness: brightness,
            platform: TargetPlatform.android,
          );
          await tester.pumpWidget(UncontrolledProviderScope(
            container: container,
            child: RepaintBoundary(key: key, child: Stack(
              fit: StackFit.expand,
              children: [
                const CustomPaint(painter: _RouteWallpaper()),
                LiquidGlassScope(
                  enabled: quality != null,
                  quality: quality ?? LiquidGlassQuality.standard,
                  child: TestApp(
                    includeNavigatorKey: false,
                    theme: theme,
                    child: const ToolsView(),
                  ),
                ),
              ],
            )),
          ));
          await tester.pumpAndSettle();
          final prefix = 'settings-${brightness.name}-${quality?.name ?? 'none'}-$label';
          final before = await _routePixels(tester, key, '$prefix-before');
          final target = find.text(label);
          await tester.scrollUntilVisible(target, 300,
            scrollable: find.byType(Scrollable).first);
          expect(find.ancestor(of: target, matching: find.byType(OpenContainer<dynamic>)),
            findsOneWidget);
          await tester.tap(target);
          await tester.pumpAndSettle();
          expect(find.byType(toolDestinations[label]!), findsOneWidget);
          final after = await _routePixels(tester, key, '$prefix-open');
          final pageContext = tester.element(find.byType(toolDestinations[label]!));
          expect(LiquidGlassScope.of(pageContext), quality != null);
          if (quality != null) {
            expect(find.byType(LiquidGlass), findsWidgets);
            expect(find.byType(CardGroup), findsWidgets);
          }
          for (var y = 200; y < 700; y++) {
            final at = (y * 390 + 2) * 4;
            if (quality != null) {
              expect(after.sublist(at, at + 4), before.sublist(at, at + 4),
                reason: 'Settled page must retain the external wallpaper at y=$y.');
            } else {
              final color = theme.canvasColor;
              expect(after.sublist(at, at + 4), [
                (color.r * 255).round(), (color.g * 255).round(),
                (color.b * 255).round(), 255,
              ]);
            }
          }
          Navigator.of(pageContext).pop();
          await tester.pumpAndSettle();
          final returned = await _routePixels(tester, key, '$prefix-return');
          for (var y = 200; y < 700; y++) {
            final at = (y * 390 + 2) * 4;
            expect(returned.sublist(at, at + 4), before.sublist(at, at + 4));
          }
          expect(find.byType(ToolsView), findsOneWidget);
          expect(tester.takeException(), isNull);
          await tester.pumpWidget(const SizedBox.shrink());
        });
      }
    }
  }

  for (final entry in toolDestinations.entries) {
    testWidgets('tools opens ${entry.key}', (tester) async {
      tester.view.physicalSize = const Size(1400, 1000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      final container = ProviderContainer(
        overrides: [profilesProvider.overrideWith(TestProfiles.new)],
      );
      addTearDown(container.dispose);
      globalState.container = container;
      container
          .read(viewSizeProvider.notifier)
          .update((_) => const Size(1400, 1000));

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const TestApp(child: ToolsView()),
        ),
      );
      await tester.pump();

      final target = find.text(entry.key);
      await tester.scrollUntilVisible(
        target,
        500,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(target);
      await tester.pumpAndSettle();

      expect(find.byType(entry.value), findsOneWidget);
      expect(tester.takeException(), null);
    });
  }

  testWidgets('user agent item opens the page that applies a preset', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1000, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final container = ProviderContainer(
      overrides: [profilesProvider.overrideWith(TestProfiles.new)],
    );
    addTearDown(container.dispose);
    globalState.container = container;
    container
        .read(viewSizeProvider.notifier)
        .update((_) => const Size(1000, 800));

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          child: Scaffold(body: ListView(children: const [UaItem()])),
        ),
      ),
    );
    await tester.pump();

    await tester.tap(find.text('User-Agent'));
    await tester.pumpAndSettle();
    expect(find.byType(UserAgentsView), findsOneWidget);
    expect(find.text('clash-verge/v2.4.2'), findsOneWidget);

    await tester.tap(find.text('clash-verge/v2.4.2'));
    await tester.pumpAndSettle();

    expect(
      container.read(patchClashConfigProvider).globalUa,
      'clash-verge/v2.4.2',
    );
    expect(tester.takeException(), null);
  });

  testWidgets('the port form refuses an emptied port', (tester) async {
    tester.view.physicalSize = const Size(1000, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final container = ProviderContainer(
      overrides: [profilesProvider.overrideWith(TestProfiles.new)],
    );
    addTearDown(container.dispose);
    globalState.container = container;
    container
        .read(viewSizeProvider.notifier)
        .update((_) => const Size(1000, 800));
    final before = container.read(patchClashConfigProvider);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(child: Scaffold(body: PortItem())),
      ),
    );
    await tester.pump();

    await tester.tap(find.text('Port').first);
    await tester.pumpAndSettle();

    await tester.enterText(_portField('SOCKS port'), '');
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(TextButton, 'Save'));
    await tester.pumpAndSettle();

    expect(tester.takeException(), null);
    expect(container.read(patchClashConfigProvider), before);
    expect(_portField('SOCKS port'), findsOneWidget);
    expect(find.text('SOCKS port cannot be empty'), findsOneWidget);
  });

  testWidgets('proxies renders populated tab and list layouts', (tester) async {
    tester.view.physicalSize = const Size(1400, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final profile = Profile.normal().copyWith(
      currentGroupName: 'Selector',
      selectedMap: {'Selector': 'Proxy 1'},
      unfoldSet: {'Selector'},
    );
    final proxies = List.generate(
      24,
      (index) => Proxy(name: 'Proxy $index', type: 'Direct'),
    );
    final group = Group(
      name: 'Selector',
      type: GroupType.Selector,
      hidden: false,
      now: 'Proxy 1',
      all: proxies,
    );
    final container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(() => TestProfiles([profile])),
        currentProfileIdProvider.overrideWithBuild((_, _) => profile.id),
        currentGroupsStateProvider.overrideWithValue(
          GroupsState(value: [group]),
        ),
        groupsProvider.overrideWithValue([group]),
      ],
    );
    addTearDown(container.dispose);
    globalState.container = container;
    container
        .read(viewSizeProvider.notifier)
        .update((_) => const Size(1400, 1000));
    container.listen(proxiesStyleSettingProvider, (_, _) {});
    container
        .read(proxiesStyleSettingProvider.notifier)
        .update((state) => state.copyWith(type: ProxiesType.tab));

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(child: ProxiesView()),
      ),
    );
    await tester.pump();

    expect(container.read(proxiesTabStateProvider).groups, [group]);
    expect(find.byType(ProxiesTabView), findsOneWidget);
    expect(find.byType(ProxyGroupView), findsOneWidget);

    container
        .read(proxiesStyleSettingProvider.notifier)
        .update((state) => state.copyWith(type: ProxiesType.list));
    await tester.pump();
    expect(find.byType(ProxiesListView), findsOneWidget);

    final scrollables = find.byType(Scrollable);
    for (var index = 0; index < 8; index++) {
      await tester.drag(
        scrollables.last,
        const Offset(0, -700),
        warnIfMissed: false,
      );
      await tester.pump();
    }
    expect(tester.takeException(), null);
  });

  testWidgets('custom profile editors render populated data', (tester) async {
    tester.view.physicalSize = const Size(1400, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final profile = Profile.custom();
    final proxyGroups = List.generate(
      8,
      (index) => ProxyGroup(
        id: 100 + index,
        profileId: profile.id,
        name: 'Group $index',
        type: GroupType.Selector,
        proxies: const ['DIRECT'],
      ),
    );
    final rules = List.generate(
      12,
      (index) => Rule(
        id: 200 + index,
        content: 'example$index.com',
        ruleTarget: 'DIRECT',
        order: index.toString(),
      ),
    );
    final customProxies = List.generate(
      6,
      (index) => CustomProxy(
        id: 300 + index,
        definition: {
          'name': 'Node $index',
          'type': 'socks5',
          'server': '127.0.0.1',
          'port': 1080 + index,
        },
      ),
    );
    final container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(() => TestProfiles([profile])),
        currentProfileIdProvider.overrideWithBuild((_, _) => profile.id),
        profileRulesProvider.overrideWith2((_) => _TestProfileRules(rules)),
        proxyGroupsProvider.overrideWith2((_) => _TestProxyGroups(proxyGroups)),
        customProxiesProvider.overrideWith(
          () => _TestCustomProxies(customProxies),
        ),
        customProxyCoreErrorsProvider.overrideWith(
          (_) async => {customProxies.first.id: 'unsupport proxy type: nope'},
        ),
        proxyGroupProvider.overrideWithBuild((_, _) => proxyGroups.first),
        setupActionProvider.overrideWith(_IdleSetupAction.new),
        proxyDialersProvider.overrideWith2((_) => _TestProxyDialers()),
        for (final kind in ProviderKind.values)
          appProviderNamesProvider(kind).overrideWithValue(const {}),
        customProfileDataProvider(profile.id).overrideWithValue(
          CustomProfileData(
            proxyGroups: proxyGroups,
            proxyProviders: const {'provider'},
            ruleTargets: {
              ...RuleTarget.baseTargets,
              ...proxyGroups.map((group) => group.name),
            },
          ),
        ),
      ],
    );
    addTearDown(container.dispose);
    globalState.container = container;
    container
        .read(viewSizeProvider.notifier)
        .update((_) => const Size(1400, 1000));

    final views = <Widget>[
      const CustomProxiesView(),
      CustomRulesView(profile.id),
      CustomProfileView(profileId: profile.id),
    ];

    for (final view in views) {
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: TestApp(child: view),
        ),
      );
      await tester.pump();
      expect(find.byWidget(view), findsOneWidget);
      expect(tester.takeException(), null);

      if (view is CustomProxiesView) {
        await tester.pump();
        expect(find.text('Node 5'), findsOneWidget);
        expect(find.text('socks5'), findsWidgets);
        expect(find.byType(CustomIssueButton), findsOneWidget);
      }

      if (view is CustomRulesView) {
        final list = tester.widget<ReorderableListView>(
          find.byType(ReorderableListView),
        );
        list.onReorderItem!(0, 1);
        await tester.pump();

        await tester.tap(find.byType(Checkbox).first);
        await tester.pump();
        for (var i = 0; i < 2; i++) {
          await tester.tap(find.byTooltip('Select all'));
          await tester.pumpAndSettle();
        }
        expect(find.byTooltip('Add'), findsOneWidget);

        container
            .read(viewSizeProvider.notifier)
            .update((_) => const Size(500, 1000));
        await tester.tap(find.byTooltip('Add'));
        await tester.pumpAndSettle();
        await tester.tap(
          find.descendant(
            of: find.byType(CommonPopupMenu),
            matching: find.text('Add'),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.byType(PagedSheet), findsOneWidget);
        globalState.navigatorKey.currentState!.pop();
        await tester.pumpAndSettle();
        container
            .read(viewSizeProvider.notifier)
            .update((_) => const Size(1400, 1000));
      }

      if (view is CustomProfileView) {
        await tester.pumpAndSettle();
        final list = tester.widget<SliverReorderableList>(
          find.byType(SliverReorderableList),
        );
        list.onReorderItem!(0, 1);
        await tester.pump();

        container
            .read(viewSizeProvider.notifier)
            .update((_) => const Size(500, 1000));
        await tester.tap(find.byTooltip('Add'));
        await tester.pumpAndSettle();
        expect(find.byType(PagedSheet), findsOneWidget);
        globalState.navigatorKey.currentState!.pop();
        await tester.pumpAndSettle();
        container
            .read(viewSizeProvider.notifier)
            .update((_) => const Size(1400, 1000));
      }
    }

    await tester.pumpWidget(const SizedBox.shrink());
  });
}

class _IdleSetupAction extends SetupAction {
  @override
  void autoApplyProfile() {}
}

class _TestProxyDialers extends ProxyDialers {
  @override
  Stream<Map<int, String>> build(int profileId) => Stream.value(const {});
}

class _TestProfileRules extends ProfileRules {
  final List<Rule> initial;

  _TestProfileRules(this.initial);

  @override
  Stream<List<Rule>> build(int profileId) => Stream.value(initial);

  @override
  void order(int oldIndex, int newIndex) {}
}

class _TestCustomProxies extends CustomProxies {
  final List<CustomProxy> initial;

  _TestCustomProxies(this.initial);

  @override
  Stream<List<CustomProxy>> build() => Stream.value(initial);

  @override
  void order(int oldIndex, int newIndex) {}
}

class _TestProxyGroups extends ProxyGroups {
  final List<ProxyGroup> initial;

  _TestProxyGroups(this.initial);

  @override
  Stream<List<ProxyGroup>> build(int profileId) => Stream.value(initial);

  @override
  void order(int oldIndex, int newIndex) {}
}


Future<Uint8List> _routePixels(
  WidgetTester tester,
  GlobalKey key,
  String name,
) async {
  expect(tester.takeException(), isNull);
  final boundary = key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  expect(boundary.size, const Size(390, 800));
  return (await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      expect(image.width, 390);
      expect(image.height, 800);
      final data = (await image.toByteData())!;
      final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
      if (output != null) {
        await Directory(output).create(recursive: true);
        final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
        await File('$output/$name.png').writeAsBytes(
          png.buffer.asUint8List(png.offsetInBytes, png.lengthInBytes),
        );
      }
      return Uint8List.fromList(data.buffer.asUint8List(
        data.offsetInBytes, data.lengthInBytes,
      ));
    } finally {
      image.dispose();
    }
  }))!;
}

class _RouteWallpaper extends CustomPainter {
  const _RouteWallpaper();

  @override
  void paint(Canvas canvas, Size size) {
    for (var y = 0.0; y < size.height; y += 20) {
      canvas.drawRect(Rect.fromLTWH(0, y, size.width, 20),
        Paint()..color = (y ~/ 20).isEven
            ? const Color(0xFF4679B2) : const Color(0xFFB5824A));
    }
  }

  @override
  bool shouldRepaint(_RouteWallpaper oldDelegate) => false;
}
