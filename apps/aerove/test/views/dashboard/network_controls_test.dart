import 'dart:ui' show Tristate;

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/widgets/card.dart';
import 'package:fl_clash/views/dashboard/widgets/fused_controls.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/cjk_test_fonts.dart';
import '../../helpers/system_test_font.dart';
import '../../helpers/test_app.dart';
import '../../helpers/test_profiles.dart';

const _font = 'ControlsRoboto';

var _fallback = const <String>[];

const _all = {
  DashboardWidget.outboundMode,
  DashboardWidget.vpnButton,
  DashboardWidget.intranetIp,
};

const _modeNames = {Mode.rule: '规则', Mode.global: '全局', Mode.direct: '直连'};

final _profile = Profile.normal(
  label: 'p',
).copyWith(id: 1, currentGroupName: 'Manual');

/// The card's width on a phone [device] dp wide.
double _cardOn(double device) => device - 2 * dashboardMobileSideOf(device);

Future<ProviderContainer> _pump(
  WidgetTester tester, {
  Set<DashboardWidget> members = _all,
  double device = 392,
  double scale = 1,
  Locale locale = const Locale('zh', 'CN'),
  Widget Function(Widget child)? wrap,
}) async {
  tester.view.physicalSize = const Size(1000, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final container = ProviderContainer(
    overrides: [
      profilesProvider.overrideWith(() => TestProfiles([_profile])),
      currentProfileIdProvider.overrideWithBuild((_, _) => _profile.id),
    ],
  );
  globalState.container = container;
  addTearDown(() async {
    await tester.pumpWidget(const SizedBox.shrink());
    container.dispose();
    await tester.pump();
  });
  // Held for the whole test, so reading them never schedules a disposal.
  container
    ..listen(patchClashConfigProvider, (_, _) {})
    ..listen(vpnSettingProvider, (_, _) {})
    ..listen(localIpProvider, (_, _) {})
    ..listen(currentProfileProvider, (_, _) {});
  container
      .read(patchClashConfigProvider.notifier)
      .update((state) => state.copyWith(mode: Mode.rule, allowLan: false));
  container
      .read(vpnSettingProvider.notifier)
      .update((state) => state.copyWith(enable: false));
  final card = FusedNetworkControls(members: members);
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: TestApp(
        locale: locale,
        theme: ThemeData(fontFamily: _font, fontFamilyFallback: _fallback),
        child: Scaffold(
          body: Align(
            alignment: Alignment.topLeft,
            child: SizedBox(
              width: _cardOn(device),
              child: MediaQuery.withClampedTextScaling(
                minScaleFactor: scale,
                maxScaleFactor: scale,
                child: DashboardDensityScope(
                  density: DashboardDensity.mobile,
                  scale: scale,
                  child: wrap == null ? card : wrap(card),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pump();
  return container;
}

Finder _button(String name) => find.byKey(ValueKey('dashboard-$name'));

Finder get _buttons => find.byType(DashboardConnectedButton);

List<RenderParagraph> _paragraphsIn(WidgetTester tester, Finder scope) => [
  for (final element
      in find.descendant(of: scope, matching: find.byType(RichText)).evaluate())
    element.renderObject! as RenderParagraph,
];

/// No label is cut short: none runs past its line limit, its box or its
/// button, it breaks only where it was set to and nowhere inside a word, it
/// takes two lines at most and draws at 11 logical pixels at least.
void _expectWhole(WidgetTester tester, [String why = '']) {
  for (final name in ['mode', 'vpn', 'lan']) {
    final button = _button(name);
    if (button.evaluate().isEmpty) continue;
    final buttonRect = tester.getRect(button);
    for (final paragraph in _paragraphsIn(tester, button)) {
      final text = '$why ${paragraph.text.toPlainText()}';
      expect(paragraph.didExceedMaxLines, isFalse, reason: text);
      expect(
        paragraph.size.height,
        lessThanOrEqualTo(
          paragraph.getMaxIntrinsicHeight(double.infinity) + 0.5,
        ),
        reason: '$text wraps where it was not set to',
      );
      final style = paragraph.text.style!;
      final drawn =
          paragraph.textScaler.scale(style.fontSize!) * _shareOf(paragraph);
      expect(drawn, greaterThanOrEqualTo(10.99), reason: '$text at $drawn');
      expect(
        paragraph.overflow,
        isNot(anyOf(TextOverflow.ellipsis, TextOverflow.fade)),
        reason: text,
      );
      expect(
        paragraph.getMinIntrinsicWidth(double.infinity),
        lessThanOrEqualTo(paragraph.size.width + 0.5),
        reason: '$text breaks a word',
      );
      expect(
        paragraph.getMaxIntrinsicHeight(paragraph.size.width),
        lessThanOrEqualTo(paragraph.size.height + 0.5),
        reason: '$text loses a line',
      );
      final shown = MatrixUtils.transformRect(
        paragraph.getTransformTo(null),
        Offset.zero & paragraph.size,
      );
      expect(buttonRect.intersect(shown), shown, reason: '$text leaves $name');
      final lines =
          paragraph.getMaxIntrinsicHeight(paragraph.size.width) /
          paragraph.getMaxIntrinsicHeight(double.infinity);
      expect(
        lines,
        lessThanOrEqualTo(2.01),
        reason: '$text takes $lines lines',
      );
    }
  }
}

/// The icon and the label of [button] centred on it as one group.
void _expectCentred(WidgetTester tester, Finder button) {
  final rect = tester.getRect(button);
  final icon = tester.getRect(
    find.descendant(of: button, matching: find.byType(GlyphIcon)),
  );
  final label = tester.getRect(
    find.descendant(of: button, matching: find.byType(RichText)),
  );
  final group = icon.expandToInclude(label);
  expect(group.center.dx, closeTo(rect.center.dx, 0.5));
  expect(group.center.dy, closeTo(rect.center.dy, 0.5));
}

String _modeShown(WidgetTester tester) => [
  for (final paragraph in _paragraphsIn(tester, _button('mode')))
    paragraph.text.toPlainText(),
].join();

/// The share of its laid out size [paragraph] is drawn at.
double _shareOf(RenderParagraph paragraph) =>
    MatrixUtils.transformRect(
      paragraph.getTransformTo(null),
      Offset.zero & paragraph.size,
    ).width /
    paragraph.size.width;

double _shrinkOf(WidgetTester tester, String button) =>
    _shareOf(_paragraphsIn(tester, _button(button)).single);

String _labelOf(WidgetTester tester, String button) =>
    _paragraphsIn(tester, _button(button)).single.text.toPlainText();

void main() {
  setUpAll(() async {
    final font = FontLoader(_font)..addFont(loadSdkTestFont());
    await font.load();
    _fallback = await loadCjkTestFonts();
  });

  for (final (device, scale) in const [
    (392.0, 1.0),
    (393.0, 1.0),
    (360.0, 1.0),
    (392.0, 1.3),
  ]) {
    testWidgets('network controls are one row of three buttons on a '
        '$device dp device at $scale', (tester) async {
      final container = await _pump(tester, device: device, scale: scale);
      container.read(localIpProvider.notifier).value = '192.0.2.8';
      container
          .read(patchClashConfigProvider.notifier)
          .update((state) => state.copyWith(allowLan: true, mixedPort: 7890));
      await tester.pump();

      expect(_buttons, findsNWidgets(3));
      final rects = [
        for (final name in ['mode', 'vpn', 'lan'])
          tester.getRect(_button(name)),
      ];
      for (final rect in rects) {
        expect(rect.top, rects.first.top);
        expect(rect.width, closeTo(rects.first.width, 0.01));
        expect(rect.height, rects.first.height);
        expect(rect.height, greaterThanOrEqualTo(48));
      }
      expect(rects[1].left - rects[0].right, closeTo(3, 0.01));
      expect(rects[2].left - rects[1].right, closeTo(3, 0.01));
      for (final name in ['mode', 'vpn', 'lan']) {
        _expectCentred(tester, _button(name));
      }
      _expectWhole(tester);
      expect(find.byType(DashboardSwitch), findsNothing);
      expect(find.textContaining('192.0.2.8'), findsNothing);
      expect(find.byKey(const ValueKey('dashboard-lan-copy')), findsNothing);
      if (device == 392 && scale == 1) {
        expect(tester.getSize(find.byType(DashboardCard)).height, 126);
      }
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('the mode button steps rule, global, direct and back', (
    tester,
  ) async {
    final container = await _pump(tester);
    final height = tester.getSize(find.byType(DashboardCard)).height;
    final seen = <(Mode, String)>[];
    for (var tap = 0; tap < 4; tap++) {
      await tester.tap(_button('mode'));
      await tester.pumpAndSettle();
      final mode = container.read(patchClashConfigProvider).mode;
      seen.add((mode, _modeShown(tester)));
      if (mode == Mode.global) {
        expect(
          container.read(currentProfileProvider)?.currentGroupName,
          GroupName.GLOBAL.name,
        );
      }
    }
    expect(seen, [
      (Mode.global, _modeNames[Mode.global]),
      (Mode.direct, _modeNames[Mode.direct]),
      (Mode.rule, _modeNames[Mode.rule]),
      (Mode.global, _modeNames[Mode.global]),
    ]);
    expect(container.read(vpnSettingProvider).enable, isFalse);
    expect(container.read(patchClashConfigProvider).allowLan, isFalse);
    expect(tester.getSize(find.byType(DashboardCard)).height, height);
    expect(tester.takeException(), isNull);
  });

  testWidgets('taps faster than a frame each move one step', (tester) async {
    final container = await _pump(tester);
    await tester.tap(_button('mode'));
    await tester.tap(_button('mode'));
    expect(container.read(patchClashConfigProvider).mode, Mode.direct);
    await tester.tap(_button('vpn'));
    await tester.tap(_button('vpn'));
    await tester.tap(_button('vpn'));
    expect(container.read(vpnSettingProvider).enable, isTrue);
    await tester.tap(_button('lan'));
    await tester.tap(_button('lan'));
    expect(container.read(patchClashConfigProvider).allowLan, isFalse);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('VPN and LAN proxy each switch only their own setting', (
    tester,
  ) async {
    final container = await _pump(tester);
    final height = tester.getSize(find.byType(DashboardCard)).height;
    final rect = tester.getRect(_button('vpn'));

    await tester.tap(_button('vpn'));
    await tester.pumpAndSettle();
    expect(container.read(vpnSettingProvider).enable, isTrue);
    expect(container.read(patchClashConfigProvider).allowLan, isFalse);
    expect(container.read(patchClashConfigProvider).mode, Mode.rule);
    expect(
      tester.widget<DashboardConnectedButton>(_button('vpn')).selected,
      isTrue,
    );
    expect(
      tester.widget<DashboardConnectedButton>(_button('lan')).selected,
      isFalse,
    );

    await tester.tap(_button('lan'));
    await tester.pumpAndSettle();
    expect(container.read(patchClashConfigProvider).allowLan, isTrue);
    expect(container.read(vpnSettingProvider).enable, isTrue);
    expect(container.read(patchClashConfigProvider).mode, Mode.rule);

    await tester.tap(_button('vpn'));
    await tester.pumpAndSettle();
    expect(container.read(vpnSettingProvider).enable, isFalse);
    expect(container.read(patchClashConfigProvider).allowLan, isTrue);
    // A selected button's bolder label leaves the row where it was.
    expect(tester.getRect(_button('vpn')), rect);
    expect(tester.getSize(find.byType(DashboardCard)).height, height);
    expect(
      tester.widget<DashboardConnectedButton>(_button('mode')).selected,
      isFalse,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('a screen reader hears the mode, what a tap does and two '
      'switches, in order', (tester) async {
    final semantics = tester.ensureSemantics();
    try {
      final container = await _pump(tester);
      container
          .read(vpnSettingProvider.notifier)
          .update((state) => state.copyWith(enable: true));
      await tester.pump();
      final mode = tester.getSemantics(_button('mode')).getSemanticsData();
      expect(mode.label, '出站模式：规则');
      expect(mode.flagsCollection.isButton, isTrue);
      expect(mode.flagsCollection.isLiveRegion, isTrue);
      expect(mode.flagsCollection.isToggled, Tristate.none);
      expect(mode.flagsCollection.isInMutuallyExclusiveGroup, isFalse);
      expect(
        tester.getSemantics(_button('mode')).hintOverrides?.onTapHint,
        '切换到全局',
      );
      final vpn = tester.getSemantics(_button('vpn')).getSemanticsData();
      expect(vpn.label, 'VPN');
      expect(vpn.flagsCollection.isToggled, Tristate.isTrue);
      final lan = tester.getSemantics(_button('lan')).getSemanticsData();
      expect(lan.label, '局域网代理');
      expect(lan.flagsCollection.isToggled, Tristate.isFalse);

      final card = tester.getSemantics(find.byType(DashboardCard));
      bool inCard(SemanticsNode? node) =>
          node != null && (node == card || inCard(node.parent));
      final order = [
        for (final node in tester.semantics.simulatedAccessibilityTraversal())
          if (inCard(node) && node.label.isNotEmpty) node.label,
      ];
      expect(order, ['网络控制', '出站模式：规则', 'VPN', '局域网代理']);

      tester.semantics.tap(find.semantics.byLabel('出站模式：规则'));
      await tester.pumpAndSettle();
      expect(container.read(patchClashConfigProvider).mode, Mode.global);
      final next = tester.getSemantics(_button('mode')).getSemanticsData();
      expect(next.label, '出站模式：全局');
      expect(
        tester.getSemantics(_button('mode')).hintOverrides?.onTapHint,
        '切换到直连',
      );
    } finally {
      semantics.dispose();
    }
  });

  // A label wraps inside its pill, two lines at most, between words or CJK
  // characters, never inside a word or a kana run; only a word too long for
  // the pill narrows the insets and then shrinks the labels together.
  for (final locale in const [
    Locale('zh', 'CN'),
    Locale('ru'),
    Locale('en'),
    Locale('ja'),
    Locale('ko'),
    Locale('es'),
    Locale('fr'),
  ]) {
    for (final device in const [320.0, 360.0, 393.0, 412.0]) {
      testWidgets('three buttons keep one row at every text size '
          '${locale.toLanguageTag()} on a $device dp device', (tester) async {
        for (final scale in const [1.0, 1.3, 1.5, 2.0]) {
          final why = '${locale.toLanguageTag()} $device dp x$scale';
          final container = await _pump(
            tester,
            device: device,
            scale: scale,
            locale: locale,
          );
          final card = tester.getRect(find.byType(DashboardCard));
          for (var tap = 0; tap < 3; tap++) {
            final rects = [
              for (final name in ['mode', 'vpn', 'lan'])
                tester.getRect(_button(name)),
            ];
            for (var index = 0; index < rects.length; index++) {
              expect(rects[index].top, rects.first.top, reason: why);
              expect(rects[index].height, rects.first.height, reason: why);
              expect(
                rects[index].width,
                closeTo(rects.first.width, 0.01),
                reason: why,
              );
              if (index > 0) {
                expect(
                  rects[index].left - rects[index - 1].right,
                  closeTo(3, 0.01),
                  reason: why,
                );
              }
            }
            for (final name in ['mode', 'vpn', 'lan']) {
              expect(
                find.descendant(
                  of: _button(name),
                  matching: find.byType(GlyphIcon),
                ),
                findsWidgets,
                reason: '$why $name keeps its icon',
              );
              _expectCentred(tester, _button(name));
            }
            _expectWhole(tester, why);
            final lan = _labelOf(tester, 'lan');
            switch (locale.languageCode) {
              case 'zh':
                expect(lan, anyOf('局域网代理', '局域网\n代理'), reason: why);
              case 'ja':
                expect(lan, anyOf('LANプロキシ', 'LAN\nプロキシ'), reason: why);
                expect(_labelOf(tester, 'mode'), isNot(contains('\n')));
              case 'ru':
                expect(lan, anyOf('Разрешить LAN', 'Разрешить\nLAN'));
            }
            expect(tester.getRect(find.byType(DashboardCard)), card);
            await tester.tap(_button('mode'));
            await tester.pumpAndSettle();
          }
          expect(
            container.read(patchClashConfigProvider).mode,
            Mode.rule,
            reason: why,
          );
          expect(tester.takeException(), isNull, reason: why);
          await tester.pumpWidget(const SizedBox.shrink());
        }
      });
    }
  }

  for (final (device, scale) in const [
    (320.0, 1.3),
    (360.0, 1.3),
    (360.0, 1.5),
    (412.0, 2.0),
  ]) {
    testWidgets('局域网代理 wraps as 局域网/代理 at full size on a $device dp '
        'device at $scale', (tester) async {
      await _pump(tester, device: device, scale: scale);
      expect(_labelOf(tester, 'lan'), '局域网\n代理');
      for (final name in ['mode', 'vpn', 'lan']) {
        expect(_shrinkOf(tester, name), closeTo(1, 1e-9), reason: name);
      }
      _expectWhole(tester);
    });
  }

  testWidgets('on a 320 dp device at twice the text size the labels keep '
      'their breaks and shrink only as far as the longest piece needs', (
    tester,
  ) async {
    for (final (locale, lan) in const [
      (Locale('zh', 'CN'), '局域网\n代理'),
      (Locale('ja'), 'LAN\nプロキシ'),
      (Locale('ru'), 'Разрешить\nLAN'),
    ]) {
      await _pump(tester, device: 320, scale: 2, locale: locale);
      expect(_labelOf(tester, 'lan'), lan, reason: '$locale');
      final shrink = _shrinkOf(tester, 'lan');
      expect(shrink, lessThan(1), reason: '$locale');
      _expectWhole(tester, '$locale');
      await tester.pumpWidget(const SizedBox.shrink());
    }
  });

  for (final (members, names) in [
    ({DashboardWidget.outboundMode}, ['mode']),
    ({DashboardWidget.vpnButton, DashboardWidget.intranetIp}, ['vpn', 'lan']),
    (
      {DashboardWidget.intranetIp, DashboardWidget.outboundMode},
      ['mode', 'lan'],
    ),
    ({DashboardWidget.intranetIp}, ['lan']),
  ]) {
    testWidgets('a layout keeping ${names.join(', ')} shows those buttons '
        'in their order, sharing the row', (tester) async {
      await _pump(tester, members: members);
      expect(_buttons, findsNWidgets(names.length));
      final content = tester.getSize(find.byType(DashboardCard)).width - 40;
      expect(content, _cardOn(392) - 40);
      final rects = [for (final name in names) tester.getRect(_button(name))];
      for (var index = 0; index < rects.length; index++) {
        expect(
          rects[index].width,
          closeTo((content - 3 * (names.length - 1)) / names.length, 0.01),
        );
        if (index > 0) {
          expect(rects[index].left, greaterThan(rects[index - 1].right));
        }
      }
      expect(tester.takeException(), isNull);
    });
  }

  for (final material in LiquidGlassMode.values) {
    for (final aurora in [false, true]) {
      for (final brightness in Brightness.values) {
        testWidgets('the buttons stay full centred pills '
            '$material/$aurora/$brightness', (tester) async {
          await _pump(
            tester,
            wrap: (child) => Theme(
              data: ThemeData(
                fontFamily: _font,
                colorScheme: ColorScheme.fromSeed(
                  seedColor: Colors.blue,
                  brightness: brightness,
                ),
              ),
              child: AuroraAppearance(
                enabled: aurora,
                child: LiquidGlassScope(
                  enabled: material != LiquidGlassMode.none,
                  quality: material == LiquidGlassMode.ultra
                      ? LiquidGlassQuality.ultra
                      : LiquidGlassQuality.standard,
                  child: LiquidGlassHost(
                    enabled: material != LiquidGlassMode.none,
                    child: child,
                  ),
                ),
              ),
            ),
          );
          expect(_buttons, findsNWidgets(3));
          final widths = <double>[];
          for (final name in ['mode', 'vpn', 'lan']) {
            final button = _button(name);
            final rect = tester.getRect(button);
            _expectCentred(tester, button);
            final fill = tester.widget<Container>(
              find
                  .descendant(
                    of: button,
                    matching: find.byWidgetPredicate(
                      (widget) =>
                          widget is Container &&
                          widget.decoration is ShapeDecoration,
                    ),
                  )
                  .first,
            );
            final shape = (fill.decoration! as ShapeDecoration).shape;
            expect(shape, AppShape.full);
            final path = shape.getOuterPath(Offset.zero & rect.size);
            expect(path.contains(rect.size.center(Offset.zero)), isTrue);
            expect(path.contains(const Offset(0.1, 0.1)), isFalse);
            widths.add(rect.width);
          }
          for (final width in widths) {
            expect(width, closeTo(widths.first, 0.01));
          }
          expect(tester.takeException(), isNull);
        });
      }
    }
  }
}
