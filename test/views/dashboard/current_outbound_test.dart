import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/exit_snapshot.dart';
import 'package:fl_clash/common/ip_quality.dart';
import 'package:fl_clash/common/service_probe.dart'
    show directOutbound, routedOutbound;
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/ip_quality.dart';
import 'package:fl_clash/providers/last_exit.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/widgets/card.dart';
import 'package:fl_clash/views/dashboard/widgets/current_outbound.dart';
import 'package:fl_clash/views/dashboard/widgets/outbound_motion.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'dart:async';

import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:mocktail/mocktail.dart';

import '../../helpers/glyph_finders.dart';
import '../../helpers/test_app.dart';

const _ip = '203.0.113.9';

const _profile = Profile(id: 1, autoUpdateDuration: Duration.zero);

const _residential = IpQuality(
  ip: _ip,
  source: IpQualitySource.ipLocate,
  type: IpType.residential,
  organization: 'Example Broadband',
  asn: 64500,
);

class _MockCore extends Mock implements CoreHandlerInterface {}

class _RecordingSetupAction extends SetupAction {
  final requests = <bool>[];

  @override
  Future<bool> setRunning(bool running, {bool initialize = false}) {
    requests.add(running);
    ref.read(runTimeProvider.notifier).value = running ? 1 : null;
    return Future.value(true);
  }
}

class _Ips extends OutboundIpProbe {
  @override
  RoutedProbeState<String, IpInfo> build() => const RoutedProbeState({
    routedOutbound: ProbeEntry(
      phase: ProbePhase.fresh,
      value: IpInfo(ip: _ip, countryCode: 'GB'),
    ),
  });

  @override
  void watch(String target) {}

  @override
  void unwatch(String target) {}
}

OutboundIpResult _address({int coreEpoch = 1}) => OutboundIpResult(
  url: 'https://www.cloudflare.com/cdn-cgi/trace',
  body: 'ip=$_ip\nloc=GB\n',
  coreEpoch: coreEpoch,
  picksVersion: 1,
);

Widget _card({
  DashboardDensity density = DashboardDensity.standard,
  bool motion = true,
}) => Scaffold(
  body: Align(
    alignment: Alignment.topLeft,
    child: SizedBox(
      width: density.isDesktop ? 600 : 380,
      height: density.isDesktop ? 208 : null,
      child: DashboardDensityScope(
        density: density,
        scale: 1,
        child: PageActivityScope(
          isActive: motion,
          child: const CurrentOutboundCard(),
        ),
      ),
    ),
  ),
);

Widget _app(
  Future<IpQuality> Function(Ref ref, String ip) quality, {
  DashboardDensity density = DashboardDensity.standard,
  String countryCode = 'GB',
}) {
  return TestApp(
    locale: const Locale('en'),
    overrides: [
      profilesProvider.overrideWithValue(const [_profile]),
      suspendProvider.overrideWithValue(false),
      outboundIpProbeProvider.overrideWithBuild(
        (_, _) => RoutedProbeState({
          routedOutbound: ProbeEntry(
            phase: ProbePhase.fresh,
            value: IpInfo(ip: _ip, countryCode: countryCode),
          ),
        }),
      ),
      ipQualityProvider.overrideWith(quality),
    ],
    child: _card(density: density),
  );
}

Widget _probingApp(
  CoreHandlerInterface core, {
  Future<IpQuality> Function(Ref ref, String ip)? quality,
}) {
  return TestApp(
    locale: const Locale('en'),
    overrides: [
      profilesProvider.overrideWithValue(const [_profile]),
      routeTrackerProvider.overrideWithBuild(
        (ref, _) => RouteState(
          coreEpoch: 1,
          picksVersion: 1,
          hostEpoch: 1,
          proxied: ref.watch(isStartProvider),
          synced: true,
          live: true,
        ),
      ),
      coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
      ipQualityProvider.overrideWith(quality ?? (_, _) async => _residential),
    ],
    child: _card(motion: false),
  );
}

void _useTallView(WidgetTester tester) {
  tester.view.physicalSize = const Size(1000, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

Finder _bubble() => find.ancestor(
  of: find.byType(OutboundBubble),
  matching: find.byWidgetPredicate(
    (widget) => widget is InkResponse && widget.runtimeType == InkResponse,
  ),
);

T _painterIn<T extends CustomPainter>(WidgetTester tester, Finder scope) {
  final paint = tester.widget<CustomPaint>(
    find.descendant(
      of: scope,
      matching: find.byWidgetPredicate(
        (widget) => widget is CustomPaint && widget.painter is T,
      ),
    ),
  );
  return paint.painter! as T;
}

CustomPaint _badge(WidgetTester tester) => tester.widget<CustomPaint>(
  find.descendant(
    of: _bubble(),
    matching: find.byWidgetPredicate(
      (widget) =>
          widget is CustomPaint && widget.painter is OutboundDropletPainter,
    ),
  ),
);

OutboundDropletPainter _bubbles(WidgetTester tester) =>
    _badge(tester).painter! as OutboundDropletPainter;

DashboardChip _chip(WidgetTester tester, String label) =>
    tester.widget<DashboardChip>(
      find.ancestor(of: find.text(label), matching: find.byType(DashboardChip)),
    );

int Function() _countFrames(WidgetTester tester) {
  var frames = 0;
  var counting = true;
  void count(Duration _) {
    if (!counting) return;
    frames++;
    tester.binding.addPostFrameCallback(count);
  }

  tester.binding.addPostFrameCallback(count);
  return () {
    counting = false;
    return frames;
  };
}

void main() {
  setUpAll(() => registerFallbackValue(const OutboundIpParams(timeout: 0)));

  group('the exit address', () {
    for (final density in DashboardDensity.values) {
      testWidgets('the flag fills the ${density.name} badge bubble', (
        tester,
      ) async {
        await tester.pumpWidget(
          _app(
            (_, _) async => _residential,
            countryCode: 'JP',
            density: density,
          ),
        );
        await tester.pump();
        final flag = find.byWidgetPredicate(
          (widget) => widget is EmojiText && widget.text == '🇯🇵',
        );
        expect(flag, findsOneWidget);
        final cover = find.descendant(
          of: _bubble(),
          matching: find.byType(EmojiCover),
        );
        expect(find.descendant(of: cover, matching: flag), findsOneWidget);
        final badge = find.descendant(
          of: _bubble(),
          matching: find.byType(OutboundBubble),
        );
        final badgeSize = tester.getSize(badge);
        expect(badgeSize.width, density == DashboardDensity.compact ? 40 : 48);
        expect(tester.getSize(cover), badgeSize);
        expect(tester.getSize(flag).width, greaterThan(badgeSize.width));
        expect(
          find.ancestor(
            of: cover,
            matching: find.byWidgetPredicate(
              (widget) =>
                  widget is ClipPath &&
                  widget.clipper is OutboundDropletClipper,
            ),
          ),
          findsOneWidget,
        );
        // Nepal's pennants leave part of the bubble to its own fill.
        expect(_bubbles(tester).color, isNotNull);
        expect(find.byTooltip('Retry'), findsOneWidget);
        expect(tester.widget<InkResponse>(_bubble()).onTap, isNotNull);
        expect(tester.takeException(), isNull);
      });
    }

    testWidgets('normalizes the exit country to an untinted flag', (
      tester,
    ) async {
      final semantics = tester.ensureSemantics();
      try {
        await tester.pumpWidget(
          _app((_, _) async => _residential, countryCode: ' hk '),
        );
        await tester.pump();
        final flag = find.byWidgetPredicate(
          (widget) => widget is EmojiText && widget.text == '🇭🇰',
        );
        expect(flag, findsOneWidget);
        expect(find.text('HK'), findsNothing);
        expect(
          find.descendant(of: flag, matching: find.byType(GlassInk)),
          findsNothing,
        );
        expect(find.bySemanticsLabel(RegExp('HK')), findsOneWidget);
        expect(find.byTooltip('Retry'), findsOneWidget);
        expect(tester.widget<InkResponse>(_bubble()).onTap, isNotNull);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        semantics.dispose();
      }
    });

    for (final country in ['', ' ', 'USA', '??', 'XX', 'ZZ']) {
      testWidgets('keeps the country placeholder for "$country"', (
        tester,
      ) async {
        await tester.pumpWidget(
          _app((_, _) async => _residential, countryCode: country),
        );
        await tester.pump();
        expect(
          find.descendant(
            of: _bubble(),
            matching: find.byGlyph(AppGlyphs.proxies),
          ),
          findsOneWidget,
        );
        expect(
          find.descendant(of: _bubble(), matching: find.byType(EmojiText)),
          findsNothing,
        );
        expect(find.byTooltip('Retry'), findsOneWidget);
        expect(tester.widget<InkResponse>(_bubble()).onTap, isNotNull);
      });
    }

    testWidgets('shows with its country, type, quality and organization', (
      tester,
    ) async {
      await tester.pumpWidget(_app((_, _) async => _residential));
      await tester.pump();

      expect(find.text(_ip), findsOneWidget);
      expect(find.text('🇬🇧'), findsOneWidget);
      expect(find.text('Residential'), findsOneWidget);
      expect(_chip(tester, 'Quality Good').tone, DashboardChipTone.ok);
      expect(find.text('Example Broadband'), findsOneWidget);
      expect(find.text('Status'), findsOneWidget);
      expect(find.byGlyph(AppGlyphs.refresh), findsNothing);
      expect(find.byType(ShapeBadge), findsNothing);
      expect(find.byType(OutboundBubble), findsOneWidget);
      expect(find.byType(OutboundRibbon), findsOneWidget);
    });

    testWidgets('a little wider than its card shrinks to fit', (tester) async {
      await tester.pumpWidget(_app((_, _) async => _residential));
      await tester.pump();

      expect(tester.widget<Text>(find.text(_ip)).overflow, isNull);
      expect(
        find.ancestor(of: find.text(_ip), matching: find.byType(FittedBox)),
        findsOneWidget,
      );
    });

    testWidgets('a risky one shows its quality in the warning tone', (
      tester,
    ) async {
      await tester.pumpWidget(
        _app((_, _) async => _residential.copyWith(isTor: true)),
      );
      await tester.pump();

      expect(_chip(tester, 'Quality Risky').tone, DashboardChipTone.warn);
    });

    testWidgets('says its type is unknown when no type was found', (
      tester,
    ) async {
      await tester.pumpWidget(
        _app((_, _) async => throw const IpQualityLookupException([])),
      );
      await tester.pump();

      expect(find.text('🇬🇧'), findsOneWidget);
      expect(find.text('Not running'), findsOneWidget);
      expect(_chip(tester, 'Unknown type').tone, DashboardChipTone.idle);
      expect(find.byType(DashboardChip), findsNWidgets(2));
      expect(find.text('Example Broadband'), findsNothing);
    });

    testWidgets('holds the place of its quality while it loads', (
      tester,
    ) async {
      final quality = Completer<IpQuality>();
      await tester.pumpWidget(
        TestApp(
          locale: const Locale('en'),
          overrides: [
            profilesProvider.overrideWithValue(const [_profile]),
            suspendProvider.overrideWithValue(false),
            outboundIpProbeProvider.overrideWith(_Ips.new),
            ipQualityProvider.overrideWith((_, _) => quality.future),
          ],
          child: _card(),
        ),
      );
      await tester.pump();

      expect(find.byType(SkeletonPill), findsNWidgets(3));
      expect(find.byType(SkeletonText), findsOneWidget);
      expect(tester.widget<InkResponse>(_bubble()).onTap, isNull);
      final checking = _bubbles(tester);
      expect(checking.wobble, 1);
      expect(checking.irregularity, 1);
      await tester.pump(const Duration(milliseconds: 300));
      expect(_bubbles(tester).time, greaterThan(checking.time));

      quality.complete(_residential);
      await tester.pumpAndSettle();

      expect(find.byType(SkeletonPill), findsNothing);
      expect(find.text('Residential'), findsOneWidget);
      expect(tester.binding.hasScheduledFrame, isFalse);
      final settled = _bubbles(tester);
      expect(settled.wobble, 0);
      expect(settled.irregularity, 0);
      expect(
        OutboundDropletClipper(
          time: settled.time,
          wobble: settled.wobble,
          irregularity: settled.irregularity,
        ).getClip(const Size.square(48)).getBounds(),
        const OutboundDropletClipper(
          irregularity: 0,
        ).getClip(const Size.square(48)).getBounds(),
      );
      await tester.pump(const Duration(seconds: 1));
      expect(_bubbles(tester).time, settled.time);
      expect(tester.widget<InkResponse>(_bubble()).onTap, isNotNull);
    });

    testWidgets('shows the last exit and its quality while both are checked', (
      tester,
    ) async {
      final quality = Completer<IpQuality>();
      await tester.pumpWidget(
        TestApp(
          locale: const Locale('en'),
          overrides: [
            profilesProvider.overrideWithValue(const [_profile]),
            suspendProvider.overrideWithValue(false),
            currentProfileIdProvider.overrideWithBuild((_, _) => _profile.id),
            lastExitProvider.overrideWithBuild(
              (_, _) => const ExitSnapshot(
                profileId: 1,
                proxied: false,
                ip: IpInfo(ip: _ip, countryCode: 'GB'),
                quality: _residential,
              ),
            ),
            routeTrackerProvider.overrideWithBuild(
              (_, _) => const RouteState(),
            ),
            ipQualityProvider.overrideWith((_, _) => quality.future),
          ],
          child: _card(),
        ),
      );
      await tester.pump();

      expect(find.text(_ip), findsOneWidget);
      expect(find.text('Residential'), findsOneWidget);
      expect(find.text('Example Broadband'), findsOneWidget);
      expect(find.byType(SkeletonPill), findsNothing);
      expect(find.byType(SkeletonText), findsNothing);
      expect(_bubbles(tester).wobble, 1);

      quality.complete(
        _residential.copyWith(
          type: IpType.hosting,
          organization: 'Example Hosting',
        ),
      );
      await tester.pump();
      await tester.pump();

      expect(find.text('Data center'), findsOneWidget);
      expect(find.text('Example Hosting'), findsOneWidget);
    });

    testWidgets('checks a failed quality again from its bubble', (
      tester,
    ) async {
      final core = _MockCore();
      when(() => core.outboundIp(any())).thenAnswer((_) async => _address());
      var calls = 0;
      await tester.pumpWidget(
        _probingApp(
          core,
          quality: (_, _) async {
            calls++;
            throw const IpQualityLookupException([]);
          },
        ),
      );
      await tester.pump();
      await tester.pump(commonDuration);
      expect(find.text('Unknown type'), findsOneWidget);

      await tester.tap(find.byTooltip('Retry'));
      await tester.pumpAndSettle();

      expect(calls, 2);
      verify(() => core.outboundIp(any())).called(2);
    });

    testWidgets('names its network by ASN when the organization is unknown', (
      tester,
    ) async {
      await tester.pumpWidget(
        _app((_, _) async => _residential.copyWith(organization: null)),
      );
      await tester.pump();

      expect(find.text('AS64500'), findsOneWidget);
    });

    testWidgets('fits a phone', (tester) async {
      await tester.pumpWidget(
        _app((_, _) async => _residential, density: DashboardDensity.mobile),
      );
      await tester.pump();

      expect(find.text(_ip), findsOneWidget);
      expect(find.text('Residential'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('opens its details from the address, not the card', (
      tester,
    ) async {
      _useTallView(tester);
      await tester.pumpWidget(_app((_, _) async => _residential));
      await tester.pump();

      await tester.tap(find.text('Example Broadband'));
      await tester.pumpAndSettle();
      expect(find.text('iplocate.io'), findsNothing);

      await tester.tap(find.text(_ip));
      await tester.pumpAndSettle();

      expect(find.text('Good'), findsOneWidget);
      expect(find.text('Residential'), findsNWidgets(2));
      final organization = tester.widget<RichText>(
        find.descendant(
          of: find.text('Example Broadband').last,
          matching: find.byType(RichText),
        ),
      );
      expect(organization.textAlign, TextAlign.end);
      expect(find.text('AS64500'), findsOneWidget);
      expect(find.text('iplocate.io'), findsOneWidget);
    });

    testWidgets('lists every source when none found a type and checks again', (
      tester,
    ) async {
      _useTallView(tester);
      var calls = 0;
      await tester.pumpWidget(
        _app((_, _) async {
          calls++;
          throw const IpQualityLookupException([
            (
              source: IpQualitySource.identMe,
              status: IpQualitySourceStatus.ipMismatch,
            ),
            (
              source: IpQualitySource.ipLocate,
              status: IpQualitySourceStatus.rateLimited,
            ),
          ]);
        }),
      );
      await tester.pump();

      await tester.tap(find.byTooltip('Outbound IP details'));
      await tester.pumpAndSettle();

      expect(find.text("Couldn't determine the IP type"), findsOneWidget);
      expect(find.text('Different outbound IP'), findsOneWidget);
      expect(find.text('Rate limited'), findsOneWidget);

      await tester.tap(find.byTooltip('Check again'));
      await tester.pumpAndSettle();

      expect(calls, 2);
    });

    testWidgets('is masked everywhere once hidden', (tester) async {
      _useTallView(tester);
      await tester.pumpWidget(_app((_, _) async => _residential));
      await tester.pump();

      await tester.tap(find.byTooltip('Outbound IP details'));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('Hide IP'),
        100,
        scrollable: find.byType(Scrollable).last,
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Hide IP'));
      await tester.pumpAndSettle();

      expect(find.text(_ip), findsNothing);
      expect(find.text('*****'), findsNWidgets(2));
      final container = ProviderScope.containerOf(
        tester.element(find.byType(CurrentOutboundCard)),
      );
      expect(container.read(appSettingProvider).hideIp, isTrue);
    });

    ProviderContainer containerOf(WidgetTester tester) =>
        ProviderScope.containerOf(
          tester.element(find.byType(CurrentOutboundCard)),
        );

    void start(WidgetTester tester) {
      containerOf(tester).read(runTimeProvider.notifier).value = 1;
    }

    ProbePhase phaseOf(WidgetTester tester) => containerOf(
      tester,
    ).read(outboundIpProbeProvider).entryOf(routedOutbound).phase;

    testWidgets('that fails as the proxy starts shows as checking', (
      tester,
    ) async {
      final core = _MockCore();
      var epoch = 1;
      when(() => core.outboundIp(any())).thenAnswer((invocation) async {
        final params =
            invocation.positionalArguments.single as OutboundIpParams;
        final failing = params.proxyName != directOutbound && epoch == 1;
        return failing ? null : _address(coreEpoch: epoch);
      });
      await tester.pumpWidget(_probingApp(core));
      await tester.pump();
      await tester.pump(commonDuration);
      expect(find.text(_ip), findsOneWidget);

      start(tester);
      await tester.pump();
      await tester.pump(commonDuration);
      expect(phaseOf(tester), ProbePhase.failed);
      expect(find.text('Check failed'), findsNothing);
      expect(find.byType(SkeletonText), findsNWidgets(2));

      epoch = 2;
      containerOf(tester)
          .read(routeTrackerProvider.notifier)
          .applySnapshot(const RouteSnapshot(coreEpoch: 2, picksVersion: 1));
      await tester.pumpAndSettle();
      await tester.pump(const Duration(milliseconds: 700));
      expect(find.text(_ip), findsOneWidget);
      expect(find.byType(SkeletonText), findsNothing);
    });

    testWidgets('that fails past the start is shown, then checked again', (
      tester,
    ) async {
      final core = _MockCore();
      final asked = <String>[];
      when(() => core.outboundIp(any())).thenAnswer((invocation) async {
        final params =
            invocation.positionalArguments.single as OutboundIpParams;
        asked.add(params.proxyName);
        return params.proxyName == directOutbound ? _address() : null;
      });
      await tester.pumpWidget(_probingApp(core));
      await tester.pump();
      await tester.pump(commonDuration);

      start(tester);
      await tester.pump();
      await tester.pump(commonDuration);
      await tester.tap(find.byTooltip('Retry'));
      await tester.pump();
      expect(phaseOf(tester), ProbePhase.failed);
      expect(find.text('Check failed'), findsNothing);
      expect(asked, [directOutbound, routedOutbound]);

      await tester.pump(const Duration(seconds: 5));
      expect(find.text('Check failed'), findsOneWidget);
      expect(find.text('Tap the icon to retry'), findsOneWidget);
      await tester.tap(find.byTooltip('Retry'));
      await tester.pump();
      expect(asked, [directOutbound, routedOutbound, routedOutbound]);
      await tester.pumpAndSettle();
      await tester.pump(const Duration(milliseconds: 700));
      expect(find.text('Check failed'), findsOneWidget);
      expect(find.byType(SkeletonText), findsNothing);
    });
  });

  group('the start button', () {
    late ProviderContainer container;

    ProviderContainer containerWith({
      List<Profile> profiles = const [_profile],
      bool suspended = false,
    }) => ProviderContainer(
      overrides: [
        initProvider.overrideWithBuild((_, _) => true),
        profilesProvider.overrideWithValue(profiles),
        outboundIpProbeProvider.overrideWith(_Ips.new),
        ipQualityProvider.overrideWith((_, _) async => _residential),
        setupActionProvider.overrideWith(_RecordingSetupAction.new),
        suspendProvider.overrideWithValue(suspended),
      ],
    );

    tearDown(() => container.dispose());

    Future<void> pumpCard(
      WidgetTester tester, {
      DashboardDensity density = DashboardDensity.standard,
      bool glass = false,
      bool aurora = false,
      LiquidGlassQuality quality = LiquidGlassQuality.ultra,
      bool reduced = false,
      bool highContrast = false,
      bool motion = false,
    }) async {
      globalState.container = container;
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: TestApp(
            child: AuroraAppearance(
              enabled: aurora,
              child: LiquidGlassScope(
                enabled: glass,
                quality: quality,
                child: Builder(
                  builder: (context) => MediaQuery(
                    data: MediaQuery.of(context).copyWith(
                      disableAnimations: reduced,
                      highContrast: highContrast,
                    ),
                    child: _card(density: density, motion: motion),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pump();
    }

    Finder startButton() => find
        .byWidgetPredicate((widget) => widget.runtimeType == InkResponse)
        .last;

    OutboundDropletPainter droplet(WidgetTester tester) =>
        _painterIn<OutboundDropletPainter>(tester, startButton());

    double gauze(WidgetTester tester) {
      final ribbon = find.byType(OutboundRibbon);
      return _painterIn<OutboundRibbonPainter>(tester, ribbon).phase;
    }

    testWidgets('dispatches each toggle through the shared running state', (
      tester,
    ) async {
      container = containerWith();
      container.read(runTimeProvider.notifier).value = 1;
      await pumpCard(tester);
      final action =
          container.read(setupActionProvider.notifier) as _RecordingSetupAction;

      await tester.tap(startButton());
      expect(action.requests, [false]);
      expect(container.read(isStartProvider), isFalse);
      await tester.pumpAndSettle();
      expect(find.byTooltip('Start'), findsOneWidget);

      await tester.tap(startButton());
      expect(action.requests, [false, true]);
      expect(container.read(isStartProvider), isTrue);
      await tester.pumpAndSettle();
      expect(find.byTooltip('Stop'), findsOneWidget);
    });

    testWidgets('a suspended tunnel swaps in the Wi-Fi off glyph and says so', (
      tester,
    ) async {
      container = containerWith(suspended: true);
      container.read(runTimeProvider.notifier).value = 1;
      await pumpCard(tester);
      await tester.pumpAndSettle();

      final context = tester.element(find.byType(CurrentOutboundCard));
      expect(
        find.byTooltip(context.appLocalizations.suspended),
        findsOneWidget,
      );
      expect(find.text(context.appLocalizations.suspended), findsOneWidget);
      expect(find.byGlyph(AppGlyphs.wifiOff), findsOneWidget);
    });

    testWidgets('swells under a press and on glass bends within its shape', (
      tester,
    ) async {
      container = containerWith();
      container.read(runTimeProvider.notifier).value = 1;
      await pumpCard(tester, glass: true);
      await tester.pumpAndSettle();
      final glass = tester.widget<LiquidGlass>(
        find.descendant(of: startButton(), matching: find.byType(LiquidGlass)),
      );
      expect(glass.clipper, isNotNull);

      final press = find.descendant(
        of: startButton(),
        matching: find.byType(ElasticPress),
      );
      final gesture = await tester.startGesture(
        tester.getCenter(startButton()),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expect(
        tester.renderObject<RenderProxyBox>(press).child!.debugLayer,
        isA<TransformLayer>(),
      );
      await tester.pump(const Duration(milliseconds: 60));
      expect(droplet(tester).squeeze, greaterThan(0.5));
      await gesture.cancel();
      await tester.pumpAndSettle();
      expect(droplet(tester).squeeze, closeTo(0, 1e-9));
    });

    testWidgets('active glass stays clear without Aurora while toggling', (
      tester,
    ) async {
      container = containerWith();
      await pumpCard(tester, glass: true);
      await tester.pumpAndSettle();
      LiquidGlass pane() => tester.widget<LiquidGlass>(
        find.descendant(of: startButton(), matching: find.byType(LiquidGlass)),
      );
      expect(pane().style.tint, Colors.transparent);
      await tester.tap(startButton());
      await tester.pumpAndSettle();
      expect(container.read(isStartProvider), isTrue);
      expect(pane().style.tint, Colors.transparent);
      expect(tester.takeException(), isNull);
    });

    for (final quality in LiquidGlassQuality.values) {
      testWidgets('${quality.name} uses the shared body glass renderer', (
        tester,
      ) async {
        container = containerWith();
        await pumpCard(tester, glass: true, quality: quality);
        await tester.pumpAndSettle();
        final pane = find.descendant(
          of: startButton(),
          matching: find.byType(LiquidGlass),
        );
        final glass = tester.widget<LiquidGlass>(pane);
        expect(glass.clipper, isA<OutboundDropletClipper>());
        expect(
          find.ancestor(of: pane, matching: find.byType(GlassSurfaceScope)),
          findsOneWidget,
        );
        expect(
          tester.widget<DashboardCard>(find.byType(DashboardCard)).pane,
          isTrue,
        );
        expect(
          find.ancestor(of: pane, matching: find.byType(LiquidGlassBody)),
          findsOneWidget,
        );
        expect(LiquidGlassScope.qualityOf(tester.element(pane)), quality);
        expect(
          glass.style.tint.a,
          quality == LiquidGlassQuality.ultra ? 0 : greaterThan(0),
        );
      });
    }

    testWidgets('none keeps the droplet filled without a glass pane', (
      tester,
    ) async {
      container = containerWith();
      await pumpCard(tester);
      await tester.pumpAndSettle();
      expect(
        find.descendant(of: startButton(), matching: find.byType(LiquidGlass)),
        findsNothing,
      );
      expect(droplet(tester).color, isNotNull);
    });

    for (final (glass, quality, highContrast, finish) in [
      (false, LiquidGlassQuality.ultra, false, OutboundFinish.solid),
      (true, LiquidGlassQuality.standard, false, OutboundFinish.frost),
      (true, LiquidGlassQuality.ultra, false, OutboundFinish.clear),
      (true, LiquidGlassQuality.ultra, true, OutboundFinish.solid),
    ]) {
      final mode = glass ? '${quality.name} glass' : 'no glass';
      final contrast = highContrast ? ' in high contrast' : '';
      testWidgets('${finish.name} bubbles under $mode$contrast', (
        tester,
      ) async {
        container = containerWith();
        await pumpCard(
          tester,
          glass: glass,
          quality: quality,
          highContrast: highContrast,
        );
        await tester.pumpAndSettle();
        final gloss =
            _badge(tester).foregroundPainter! as OutboundDropletPainter;
        expect(gloss.rim != null, finish == OutboundFinish.clear);
        expect(
          gloss.highlight,
          !highContrast && finish != OutboundFinish.frost,
        );
        expect(
          _bubbles(tester).shadow.a > 0,
          finish != OutboundFinish.clear && !highContrast,
        );
        final start = droplet(tester);
        expect(start.color == null, finish != OutboundFinish.solid);
        expect(start.shadow.a > 0, finish != OutboundFinish.clear);
      });
    }

    testWidgets('the gauze drifts only while the tunnel runs', (tester) async {
      container = containerWith();
      container.read(runTimeProvider.notifier).value = 1;
      await pumpCard(tester, motion: true);
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pump(const Duration(milliseconds: 200));
      expect(gauze(tester), greaterThan(0));

      await tester.pumpAndSettle();
      final drifting = gauze(tester);
      final frames = _countFrames(tester);
      for (var vsync = 0; vsync < 120; vsync++) {
        await tester.pump(const Duration(microseconds: 8333));
      }
      expect(frames(), inInclusiveRange(14, 16));
      expect(gauze(tester), greaterThan(drifting));

      container.read(runTimeProvider.notifier).value = null;
      await tester.pump();
      final stopped = gauze(tester);
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pump(const Duration(milliseconds: 200));
      expect(gauze(tester), stopped);
      await tester.pumpAndSettle();
      expect(tester.binding.hasScheduledFrame, isFalse);
      final idle = _countFrames(tester);
      await tester.pump(const Duration(seconds: 1));
      expect(idle(), 0);
      expect(tester.binding.transientCallbackCount, 0);
    });

    testWidgets('a suspended tunnel holds the gauze still', (tester) async {
      container = containerWith(suspended: true);
      container.read(runTimeProvider.notifier).value = 1;
      await pumpCard(tester, motion: true);
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pump(const Duration(milliseconds: 200));
      expect(gauze(tester), 0);
      await tester.pumpAndSettle();
      expect(tester.binding.hasScheduledFrame, isFalse);
    });

    testWidgets('a hidden app holds the gauze still', (tester) async {
      container = containerWith();
      container.read(runTimeProvider.notifier).value = 1;
      await pumpCard(tester, motion: true);
      await tester.pump(const Duration(seconds: 1));
      container.read(appVisibleProvider.notifier).value = false;
      await tester.pump();
      final hidden = gauze(tester);
      final idle = _countFrames(tester);
      await tester.pump(const Duration(seconds: 1));
      expect(idle(), 0);
      expect(gauze(tester), hidden);
      expect(tester.binding.hasScheduledFrame, isFalse);
      container.read(appVisibleProvider.notifier).value = true;
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pump(const Duration(milliseconds: 200));
      expect(gauze(tester), greaterThan(hidden));
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      final disposed = _countFrames(tester);
      await tester.pump(const Duration(seconds: 1));
      expect(disposed(), 0);
      expect(tester.binding.transientCallbackCount, 0);
    });

    for (final highContrast in [false, true]) {
      testWidgets(
        '${highContrast ? 'high contrast' : 'reduced motion'} keeps taps without press animation',
        (tester) async {
          container = containerWith();
          await pumpCard(
            tester,
            glass: true,
            reduced: !highContrast,
            highContrast: highContrast,
          );
          await tester.pumpAndSettle();
          expect(tester.widget<InkResponse>(startButton()).onTap, isNotNull);
          final press = tester.widget<ElasticPress>(
            find.descendant(
              of: startButton(),
              matching: find.byType(ElasticPress),
            ),
          );
          expect(press.enabled, isFalse);
          if (highContrast) {
            expect(
              find.descendant(
                of: startButton(),
                matching: find.byType(LiquidGlass),
              ),
              findsNothing,
            );
          }
          await tester.tap(startButton());
          await tester.pumpAndSettle();
          expect(container.read(isStartProvider), isTrue);
          expect(tester.takeException(), isNull);
        },
      );
    }

    testWidgets('is off without a profile', (tester) async {
      container = containerWith(profiles: const []);
      await pumpCard(tester, density: DashboardDensity.mobile);

      final toggle = tester.widget<InkResponse>(startButton());
      expect(toggle.onTap, isNull);
      expect(tester.takeException(), isNull);
    });
  });
}
