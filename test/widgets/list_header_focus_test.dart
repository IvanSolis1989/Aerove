import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart'
    show LiquidGlassHost, LiquidGlassScope, RemoteFocusAdapter;
import 'package:fl_clash/views/proxies/list.dart';
import 'package:fl_clash/views/proxies/proxies.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

void main() {
  Future<ProviderContainer> pumpListLayout(
    WidgetTester tester, {
    Size size = const Size(1400, 1000),
    int proxyCount = 12,
    bool expanded = true,
    bool glass = false,
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final profile = Profile.normal().copyWith(
      currentGroupName: 'Selector',
      selectedMap: {'Selector': 'Proxy 1'},
      unfoldSet: expanded ? {'Selector'} : {},
    );
    final proxies = List.generate(
      proxyCount,
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
    container.read(viewSizeProvider.notifier).update((_) => size);
    container
        .read(proxiesStyleSettingProvider.notifier)
        .update((state) => state.copyWith(type: ProxiesType.list));

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          localizationsDelegates: const [
            AppLocalizations.delegate,
            AppUiLocalizations.delegate,
            ...GlobalMaterialLocalizations.delegates,
          ],
          supportedLocales: AppLocalizations.delegate.supportedLocales,
          builder: (context, child) {
            return wrapAppScopes(
              context,
              glass
                  ? RemoteFocusAdapter(
                      enabled: true,
                      child: LiquidGlassScope(
                        enabled: true,
                        child: LiquidGlassHost(enabled: true, child: child!),
                      ),
                    )
                  : child!,
            );
          },
          home: const ProxiesView(),
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
    return container;
  }

  bool focusInHeaderCard() {
    final ctx = FocusManager.instance.primaryFocus?.context;
    if (ctx == null) return false;
    return ctx.findAncestorWidgetOfExactType<ListHeader>() != null &&
        ctx.findAncestorWidgetOfExactType<FilledButton>() != null &&
        ctx.findAncestorWidgetOfExactType<IconButton>() == null;
  }

  bool focusInHeaderAction() {
    final ctx = FocusManager.instance.primaryFocus?.context;
    if (ctx == null) return false;
    return ctx.findAncestorWidgetOfExactType<ListHeader>() != null &&
        ctx.findAncestorWidgetOfExactType<IconButton>() != null;
  }

  int? focusedHeaderActionIndex() {
    final context = FocusManager.instance.primaryFocus?.context;
    final header = context?.findAncestorWidgetOfExactType<ListHeader>();
    final button = context?.findAncestorWidgetOfExactType<IconButton>();
    if (header == null || button == null) {
      return null;
    }
    final buttons = find
        .descendant(
          of: find.byWidget(header),
          matching: find.byType(IconButton),
        )
        .evaluate()
        .map((element) => element.widget)
        .toList();
    return buttons.indexWhere((candidate) => identical(candidate, button));
  }

  testWidgets('arrow right from the ListHeader card enters the actions', (
    tester,
  ) async {
    await pumpListLayout(tester);

    for (var i = 0; i < 10 && !focusInHeaderCard(); i++) {
      await tester.sendKeyEvent(LogicalKeyboardKey.tab);
      await tester.pump();
    }
    expect(focusInHeaderCard(), isTrue, reason: 'ListHeader card is reachable');

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await tester.pump();
    expect(
      focusInHeaderAction(),
      isTrue,
      reason:
          'arrow right from the card should enter the actions row, '
          'actual: ${FocusManager.instance.primaryFocus}',
    );

    await tester.pump(const Duration(seconds: 1));
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });

  testWidgets('arrow right traverses every ListHeader action', (tester) async {
    await pumpListLayout(tester);

    for (var i = 0; i < 10 && !focusInHeaderCard(); i++) {
      await tester.sendKeyEvent(LogicalKeyboardKey.tab);
      await tester.pump();
    }
    expect(focusInHeaderCard(), isTrue);

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await tester.pump();
    expect(focusedHeaderActionIndex(), 0);

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await tester.pump();
    expect(focusedHeaderActionIndex(), 1);

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await tester.pump();
    expect(focusedHeaderActionIndex(), 2);

    await tester.pump(const Duration(seconds: 1));
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });

  testWidgets('the expand action retains focus while toggling', (tester) async {
    await pumpListLayout(tester, expanded: false);

    for (var i = 0; i < 10 && !focusInHeaderCard(); i++) {
      await tester.sendKeyEvent(LogicalKeyboardKey.tab);
      await tester.pump();
    }
    expect(focusInHeaderCard(), isTrue);

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await tester.pump();
    expect(focusedHeaderActionIndex(), 0);
    final expandFocus = FocusManager.instance.primaryFocus;

    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pump();
    expect(focusedHeaderActionIndex(), 2);
    expect(FocusManager.instance.primaryFocus, same(expandFocus));

    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pump();
    expect(focusedHeaderActionIndex(), 0);
    expect(FocusManager.instance.primaryFocus, same(expandFocus));
    await tester.pumpAndSettle();
    expect(focusedHeaderActionIndex(), 0);
    expect(FocusManager.instance.primaryFocus, same(expandFocus));

    await tester.pump(const Duration(seconds: 1));
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });

  testWidgets('a glass header keeps its keyboard focus ring across folding', (
    tester,
  ) async {
    await pumpListLayout(tester, expanded: false, glass: true);
    for (var i = 0; i < 10 && !focusInHeaderCard(); i++) {
      await tester.sendKeyEvent(LogicalKeyboardKey.tab);
      await tester.pump();
    }
    expect(focusInHeaderCard(), isTrue);
    final cardFocus = FocusManager.instance.primaryFocus;
    void expectRing() {
      final card = tester.widget<FilledButton>(
        find.descendant(
          of: find.byType(ListHeader),
          matching: find.byType(FilledButton),
        ),
      );
      final side = card.style!.side!.resolve({WidgetState.focused})!;
      expect(side.width, 2);
      expect(
        side.color,
        Theme.of(tester.element(find.byType(ListHeader))).colorScheme.primary,
      );
    }

    expectRing();
    for (var i = 0; i < 2; i++) {
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await tester.pumpAndSettle();
      expect(focusInHeaderCard(), isTrue);
      expect(FocusManager.instance.primaryFocus, same(cardFocus));
      expectRing();
    }
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await tester.pump();
    expect(focusedHeaderActionIndex(), 0);
    final actionFocus = FocusManager.instance.primaryFocus;
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pumpAndSettle();
    expect(focusedHeaderActionIndex(), 2);
    expect(FocusManager.instance.primaryFocus, same(actionFocus));
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pumpAndSettle();
    expect(focusedHeaderActionIndex(), 0);
    expect(FocusManager.instance.primaryFocus, same(actionFocus));
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });

  testWidgets('revealed proxy stays below the pinned header', (tester) async {
    await pumpListLayout(tester, size: const Size(600, 400), proxyCount: 30);

    final proxyFinder = find.byKey(const ValueKey('Selector.Proxy 3')).first;
    final targetContext = tester.element(proxyFinder);
    final scrollable = Scrollable.of(targetContext);
    final viewportTop = tester.getTopLeft(find.byWidget(scrollable.widget)).dy;
    final targetTop = tester.getTopLeft(proxyFinder).dy;
    scrollable.position.jumpTo(
      scrollable.position.pixels + targetTop - viewportTop + 20,
    );
    await tester.pump();
    expect(tester.getTopLeft(proxyFinder).dy, lessThan(viewportTop));

    await Scrollable.ensureVisible(
      targetContext,
      alignmentPolicy: ScrollPositionAlignmentPolicy.keepVisibleAtStart,
    );
    await tester.pump();

    final headerBottom = tester.getBottomLeft(
      find.byKey(const ValueKey('Selector')).first,
    );
    expect(
      tester.getTopLeft(proxyFinder).dy,
      greaterThanOrEqualTo(headerBottom.dy),
    );

    await tester.pump(const Duration(seconds: 1));
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });
}
