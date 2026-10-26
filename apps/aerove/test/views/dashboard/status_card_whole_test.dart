import 'dart:io';

import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/ip_quality.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/dashboard/dashboard.dart';
import 'package:fl_clash/views/dashboard/widgets/current_outbound.dart';
import 'package:fl_clash/views/dashboard/widgets/status_layout.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/cjk_test_fonts.dart';
import '../../helpers/system_test_font.dart';
import '../../helpers/test_app.dart';
import '../../helpers/test_profiles.dart';

const _font = 'WholeRoboto';

var _fallback = const <String>[];

const _v4 = '142.91.102.22';
const _v6 = '2001:0db8:85a3:0000:0000:8a2e:0370:7334';

class _Ips extends OutboundIpProbe {
  _Ips(this.ip);

  final String ip;

  @override
  RoutedProbeState<String, IpInfo> build() => RoutedProbeState({
    routedOutbound: ProbeEntry(
      phase: ProbePhase.fresh,
      value: IpInfo(ip: ip, countryCode: 'DE'),
    ),
  });

  @override
  void watch(String target) {}

  @override
  void unwatch(String target) {}
}

final _profile = Profile.normal(label: 'Example').copyWith(id: 1);

/// The whole dashboard in a window [width] by [height] dp, its status card
/// laid out for [check].
Future<void> _pumpDashboard(
  WidgetTester tester, {
  required double width,
  required double height,
  required double scale,
  required Locale locale,
  required String ip,
  required bool running,
  required SupportPlatform platform,
  required void Function(RenderStatusCardLayout layout) check,
}) async {
  tester.view.physicalSize = Size(width, height);
  tester.view.devicePixelRatio = 1;
  final container = ProviderContainer(
    overrides: [
      profilesProvider.overrideWith(() => TestProfiles([_profile])),
      currentProfileIdProvider.overrideWithBuild((_, _) => _profile.id),
      isStartProvider.overrideWith((ref) => running),
      suspendProvider.overrideWith((ref) => false),
      outboundIpProbeProvider.overrideWith(() => _Ips(ip)),
      ipQualityProvider.overrideWith(
        (_, _) async => IpQuality(
          ip: ip,
          source: IpQualitySource.ipLocate,
          type: IpType.residential,
          organization: 'Example',
        ),
      ),
    ],
  );
  globalState.container = container;
  container
    ..listen(patchClashConfigProvider, (_, _) {})
    ..listen(vpnSettingProvider, (_, _) {});
  container.read(viewSizeProvider.notifier).value = Size(width, height);
  if (running) container.read(runTimeProvider.notifier).value = 754000;
  try {
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          locale: locale,
          theme: ThemeData(fontFamily: _font, fontFamilyFallback: _fallback),
          child: Builder(
            builder: (context) => MediaQuery(
              data: MediaQuery.of(
                context,
              ).copyWith(textScaler: TextScaler.linear(scale)),
              child: DashboardView(platform: platform),
            ),
          ),
        ),
      ),
    );
    await tester.pump(const Duration(milliseconds: 500));
    check(
      tester.renderObject<RenderStatusCardLayout>(
        find.byType(StatusCardLayout),
      ),
    );
  } finally {
    await tester.pumpWidget(const SizedBox.shrink());
    container.dispose();
  }
}

Rect _rectIn(RenderBox box, RenderBox layout) => MatrixUtils.transformRect(
  box.getTransformTo(layout),
  Offset.zero & box.size,
);

/// Nothing in the card is cut, squeezed away or crossed by its zero line,
/// and the band the stroke takes under it stays above the cells.
void _expectWhole(
  WidgetTester tester,
  RenderStatusCardLayout layout,
  String why, {
  required bool running,
}) {
  expect(tester.takeException(), isNull, reason: why);
  final l10n = AppLocalizations.of(
    tester.element(find.byType(CurrentOutboundCard)),
  );
  final baseline = layout.baseline!;
  final shown = <String>{};
  double? cells;
  void visit(RenderObject node) {
    if (node is RenderParagraph) {
      final text = node.text.toPlainText();
      final emoji = text.runes.any((rune) => rune >= 0x1F1E6);
      if (text.trim().isNotEmpty && !emoji) {
        shown.add(text);
        final rect = _rectIn(node, layout);
        final what = '$why "$text" $rect';
        expect(node.didExceedMaxLines, isFalse, reason: what);
        expect(rect.width, greaterThan(1), reason: '$what is squeezed away');
        if (node.overflow == TextOverflow.ellipsis) {
          expect(
            node.getMaxIntrinsicWidth(double.infinity),
            lessThanOrEqualTo(node.size.width + 0.5),
            reason: '$what is cut short',
          );
        }
        expect(
          node.getMaxIntrinsicHeight(node.size.width),
          lessThanOrEqualTo(node.size.height + 0.5),
          reason: '$what loses a line',
        );
        expect(
          (Offset.zero & layout.size).inflate(0.5).intersect(rect),
          rect,
          reason: '$what leaves the card',
        );
        expect(
          rect.top >= baseline + 1 || rect.bottom <= baseline - 1,
          isTrue,
          reason: '$what is crossed by the zero line at $baseline',
        );
        if (text == l10n.runTime || text == l10n.ipOrganization) {
          cells = cells == null || rect.top < cells! ? rect.top : cells;
        }
      }
    }
    node.visitChildren(visit);
  }

  layout.visitChildren(visit);
  for (final head in [l10n.status, running ? l10n.running : l10n.notRunning]) {
    expect(shown, contains(head), reason: '$why loses "$head"');
  }
  expect(cells, isNotNull, reason: why);
  expect(baseline, closeTo(layout.size.height - 1, 0.01), reason: why);
}

void main() {
  setUpAll(() async {
    await (FontLoader(_font)..addFont(loadSdkTestFont())).load();
    _fallback = await loadCjkTestFonts();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('plugins.flutter.io/path_provider'),
          (_) async => Directory.systemTemp.path,
        );
  });

  const locales = [
    Locale('zh', 'CN'),
    Locale('en'),
    Locale('ja'),
    Locale('ko'),
    Locale('es'),
    Locale('fr'),
    Locale('ru'),
  ];

  // The head keeps its title and status whole, the cells wrap rather than
  // squeeze, and at normal size on a 360 dp phone or wider Chinese and
  // English keep the figures in the corner.
  for (final locale in locales) {
    for (final device in const [320.0, 360.0, 393.0, 412.0]) {
      testWidgets('the status card stays whole '
          '${locale.toLanguageTag()} on a $device dp phone', (tester) async {
        for (final scale in const [1.0, 1.3, 2.0]) {
          for (final ip in const [_v4, _v6]) {
            for (final running in const [false, true]) {
              final why =
                  '${locale.toLanguageTag()} $device dp x$scale $ip '
                  '${running ? 'running' : 'stopped'}';
              await _pumpDashboard(
                tester,
                width: device,
                height: 1400,
                scale: scale,
                locale: locale,
                ip: ip,
                running: running,
                platform: SupportPlatform.Android,
                check: (layout) {
                  _expectWhole(tester, layout, why, running: running);
                  if (scale == 1 &&
                      device >= 360 &&
                      const {'zh', 'en'}.contains(locale.languageCode)) {
                    expect(layout.overlaid, isTrue, reason: why);
                  }
                },
              );
            }
          }
        }
        tester.view.reset();
      });
    }
  }

  // A desktop card has a fixed height: the figures always find their corner,
  // nothing overflows and the zero line keeps off the text.
  for (final width in const [680.0, 720.0, 760.0, 800.0, 840.0, 1280.0]) {
    testWidgets('the desktop status card stays whole $width dp wide', (
      tester,
    ) async {
      for (final height in const [600.0, 760.0]) {
        for (final scale in const [1.0, 1.05, 1.1, 1.15, 1.2, 1.25, 1.3]) {
          for (final ip in const [_v4, _v6]) {
            for (final locale in const [
              Locale('zh', 'CN'),
              Locale('en'),
              Locale('ru'),
            ]) {
              final why =
                  '$width x $height x$scale $ip ${locale.toLanguageTag()}';
              await _pumpDashboard(
                tester,
                width: width,
                height: height,
                scale: scale,
                locale: locale,
                ip: ip,
                running: true,
                platform: SupportPlatform.Windows,
                check: (layout) {
                  _expectWhole(tester, layout, why, running: true);
                  expect(layout.overlaid, isTrue, reason: why);
                },
              );
            }
          }
        }
      }
      tester.view.reset();
    });
  }
}
