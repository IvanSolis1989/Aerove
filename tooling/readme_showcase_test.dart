import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:crypto/crypto.dart';
import 'package:aerove_standard/standard_product.dart';
import 'package:fl_clash/application.dart';
import 'package:fl_clash/common/app_ports.dart';
import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/core/service.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/manager/theme_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/pages/home.dart';
import 'package:fl_clash/product.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:fl_clash/providers/service_status.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/dashboard.dart';
import 'package:fl_clash/views/navigation.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:yaml/yaml.dart';

import '../apps/aerove/test/helpers/test_profiles.dart';

const _viewport = Size(430, 932);
const _pixelRatio = 2.0;
const _harnessPath = 'tooling/readme_showcase_test.dart';
const _materials = {
  'solid': LiquidGlassMode.none,
  'frost': LiquidGlassMode.standard,
  'clear': LiquidGlassMode.ultra,
};

// No startup, Core, VPN or network managers run in this documentation fixture.
class _OfflineIps extends OutboundIpProbe {
  @override
  RoutedProbeState<String, IpInfo> build() => const RoutedProbeState({
    routedOutbound: ProbeEntry(phase: ProbePhase.failed),
  });

  @override
  void watch(String target) {}

  @override
  void unwatch(String target) {}
}

class _OfflineServices extends ServiceStatus {
  @override
  RoutedProbeState<ServiceTarget, ServiceCheck> build() =>
      const RoutedProbeState();

  @override
  void watch(ServiceTarget target) {}

  @override
  void unwatch(ServiceTarget target) {}
}

Widget _themeOnly(Widget child) => ThemeManager(child: child);

String _gitOutput(List<String> args, {String? workingDirectory}) {
  final result = Process.runSync(
    'git',
    args,
    workingDirectory: workingDirectory,
  );
  if (result.exitCode != 0) {
    throw StateError('git ${args.join(' ')} failed: ${result.stderr}');
  }
  return result.stdout as String;
}

String _git(List<String> args) => _gitOutput(args).trim();

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final output = Platform.environment['AEROVE_README_RENDER_DIR'];
  if (output == null) {
    test(
      'README showcase is opt-in',
      () {},
      skip: 'Set AEROVE_README_RENDER_DIR to render documentation images.',
    );
    return;
  }
  ClientProduct.configure(
    product: standardProduct,
    backendCoreId: 'mihomo',
    backendFactory: CoreService.new,
  );
  final locale = Platform.environment['AEROVE_README_LOCALE'] ?? 'en';
  final localeParts = locale.split('_');
  final systemLocale = Locale(
    localeParts.first,
    localeParts.length > 1 ? localeParts.last : null,
  );
  final cases = <Map<String, Object?>>[];
  late Map<String, Object?> source;
  late Map<String, Object?> fontSource;
  late List<String> omittedAssets;

  setUpAll(() async {
    Directory(output).createSync(recursive: true);
    final manifest = File('$output/render-info.json');
    if (manifest.existsSync()) manifest.deleteSync();
    final pubspec = loadYaml(File('pubspec.yaml').readAsStringSync());
    const certificateAsset = 'core/Clash.Meta/component/ca/ca-certificates.crt';
    omittedAssets = pubspec['flutter']['assets'].contains(certificateAsset)
        ? []
        : [certificateAsset];
    for (final hook in ['setup', 'rust_api']) {
      expect(
        pubspec['hooks']['user_defines'][hook]['build_assets'],
        isFalse,
        reason: 'Use the documented Dart-only hook settings in a disposable checkout.',
      );
    }
    expect(
      ui.ImageFilter.isShaderFilterSupported,
      isTrue,
      reason: 'Use --enable-impeller; clear glass requires shader filters.',
    );
    final root = _git(['rev-parse', '--show-toplevel']);
    expect(
      _git([
        'diff',
        '--name-only',
        'HEAD',
        '--',
        '$root/packages/aerove_client',
        '$root/apps/aerove/lib',
        '$root/apps/aerove/assets',
      ]),
      isEmpty,
      reason: 'The rendered production sources must match the recorded tree.',
    );
    source = {
      'commit': _git(['rev-parse', 'HEAD']),
      'tree': _git(['rev-parse', 'HEAD^{tree}']),
      'uiTreeSha256': sha256
          .convert(
            utf8.encode(
              _gitOutput([
                'ls-tree',
                '-r',
                'HEAD',
                'packages/aerove_client/lib',
                'packages/aerove_client/assets',
                'packages/aerove_client/shaders',
                'apps/aerove/lib',
                'apps/aerove/assets',
              ], workingDirectory: root),
            ),
          )
          .toString(),
      'ref':
          Platform.environment['GITHUB_REF_NAME'] ??
          _git(['rev-parse', '--abbrev-ref', 'HEAD']),
      'workflowRun': Platform.environment['GITHUB_RUN_ID'],
      'producerDirty': _git([
        'status',
        '--porcelain',
        '--',
        '$root/$_harnessPath',
      ]).isNotEmpty,
      'harness': {
        'file': _harnessPath,
        'sha256': sha256
            .convert(File('$root/$_harnessPath').readAsBytesSync())
            .toString(),
      },
    };
    final fontPath =
        Platform.environment['AEROVE_README_FONT'] ??
        'test/fixtures/fonts/Roboto-Regular.ttf';
    final fontBytes = await File(fontPath).readAsBytes();
    fontSource = {
      'file': fontPath,
      'sha256': sha256.convert(fontBytes).toString(),
    };
    await (FontLoader(
      'ReadmeShowcase',
    )..addFont(Future.value(ByteData.sublistView(fontBytes)))).load();
    await GlassInk.prepare();
  });

  tearDownAll(() {
    if (cases.length != 12) return;
    File('$output/render-info.json').writeAsStringSync(
      const JsonEncoder.withIndent('  ').convert({
        'schema': 1,
        'product': 'standard',
        'method': 'flutter-widget-test',
        'source': source,
        'viewport': {
          'width': _viewport.width,
          'height': _viewport.height,
          'devicePixelRatio': _pixelRatio,
        },
        'fixture': 'offline',
        'androidDevice': false,
        'nativeAssets': false,
        'omittedAssets': omittedAssets,
        'locale': locale,
        'testOverrides': {
          'managers': 'ThemeManager only; no startup/Core/VPN managers',
          'platform': 'Android widget theme/layout only',
          'profiles': 'empty',
          'isStart': false,
          'outboundProbe': 'offline failed; no IP',
          'serviceProbes': 'offline pending; no requests',
          'disableAnimations': true,
          'fontFamily': 'ReadmeShowcase',
        },
        'font': fontSource,
        'pureBlack': false,
        'networkAttempts': 0,
        'cases': cases,
        'complete': true,
      }),
    );
  });

  for (final material in _materials.entries) {
    for (final colorMode in ['classic', 'aurora']) {
      for (final brightness in [Brightness.light, Brightness.dark]) {
        final id = '${material.key}-$colorMode-${brightness.name}';
        testWidgets('README complete HomePage $id', (tester) async {
          final previousPlatform = debugDefaultTargetPlatformOverride;
          final previousNavigation = navigationPort;
          debugDefaultTargetPlatformOverride = TargetPlatform.android;
          navigationPort = navigation;
          tester.view.physicalSize = _viewport * _pixelRatio;
          tester.view.devicePixelRatio = _pixelRatio;
          tester.platformDispatcher.localesTestValue = [systemLocale];
          tester.platformDispatcher.accessibilityFeaturesTestValue =
              const FakeAccessibilityFeatures(disableAnimations: true);
          final container = ProviderContainer(
            overrides: [
              profilesProvider.overrideWith(TestProfiles.new),
              isStartProvider.overrideWithValue(false),
              outboundIpProbeProvider.overrideWith(_OfflineIps.new),
              serviceStatusProvider.overrideWith(_OfflineServices.new),
              appSettingProvider.overrideWithBuild(
                (_, _) => AppSettingProps(
                  locale: locale,
                  autoCheckUpdate: false,
                  autoRun: false,
                ),
              ),
              themeSettingProvider.overrideWithBuild(
                (_, _) => ThemeProps(
                  themeMode: brightness == Brightness.light
                      ? ThemeMode.light
                      : ThemeMode.dark,
                  auroraEnabled: colorMode == 'aurora',
                  liquidGlass: material.value,
                  pureBlack: false,
                  fontFamily: 'ReadmeShowcase',
                ),
              ),
              navigationItemsStateProvider.overrideWithValue(
                NavigationItemsState(
                  value: navigation
                      .getItems()
                      .map(
                        (item) => item.label == PageLabel.dashboard
                            ? item.copyWith(
                                builder: (_) => const DashboardView(
                                  key: GlobalObjectKey(PageLabel.dashboard),
                                  platform: SupportPlatform.Android,
                                ),
                              )
                            : item,
                      )
                      .toList(),
                ),
              ),
            ],
          );
          globalState.container = container;
          container.read(viewSizeProvider.notifier).value = _viewport;
          final capture = GlobalKey();
          var networkAttempts = 0;
          late Map<String, Object?> rendered;
          try {
            await HttpOverrides.runZoned(
              () async {
                await tester.pumpWidget(
                  UncontrolledProviderScope(
                    container: container,
                    child: RepaintBoundary(
                      key: capture,
                      child: const Application.forTesting(
                        home: HomePage(),
                        wrapManagers: _themeOnly,
                      ),
                    ),
                  ),
                );
                await tester.pumpAndSettle();
                expect(tester.takeException(), isNull);
                expect(find.byType(HomePage), findsOneWidget);
                expect(find.byType(DashboardView), findsOneWidget);
                expect(find.byType(ThemeManager), findsOneWidget);
                expect(find.byType(AuroraAppSurface), findsOneWidget);
                expect(find.byType(NavigationDock), findsOneWidget);
                expect(
                  find.byType(SeededAuroraWallpaper),
                  colorMode == 'aurora' ? findsOneWidget : findsNothing,
                );
                final context = tester.element(find.byType(HomePage));
                expect(Theme.of(context).brightness, brightness);
                expect(
                  LiquidGlassScope.of(context),
                  material.value != LiquidGlassMode.none,
                );
                if (material.value != LiquidGlassMode.none) {
                  expect(
                    LiquidGlassScope.qualityOf(context),
                    material.value == LiquidGlassMode.ultra
                        ? LiquidGlassQuality.ultra
                        : LiquidGlassQuality.standard,
                  );
                }
                expect(networkAttempts, 0);
                final boundary =
                    capture.currentContext!.findRenderObject()!
                        as RenderRepaintBoundary;
                expect(boundary.size, _viewport);
                final result = await tester.runAsync(() async {
                  final image = await boundary.toImage(pixelRatio: _pixelRatio);
                  try {
                    final data = (await image.toByteData(
                      format: ui.ImageByteFormat.png,
                    ))!;
                    final png = data.buffer.asUint8List(
                      data.offsetInBytes,
                      data.lengthInBytes,
                    );
                    await File('$output/$id.png').writeAsBytes(png);
                    return <String, Object?>{
                      'id': id,
                      'material': material.key,
                      'colorMode': colorMode,
                      'brightness': brightness.name,
                      'file': '$id.png',
                      'sha256': sha256.convert(png).toString(),
                      'width': image.width,
                      'height': image.height,
                    };
                  } finally {
                    image.dispose();
                  }
                });
                expect(result!['width'], 860);
                expect(result['height'], 1864);
                expect(networkAttempts, 0);
                rendered = result;
              },
              createHttpClient: (_) {
                networkAttempts++;
                throw StateError(
                  'Network requests are forbidden in README rendering.',
                );
              },
            );
          } finally {
            await tester.pumpWidget(const SizedBox.shrink());
            await tester.pumpAndSettle();
            container.dispose();
            navigationPort = previousNavigation;
            debugDefaultTargetPlatformOverride = previousPlatform;
            tester.view.reset();
            tester.platformDispatcher.clearAccessibilityFeaturesTestValue();
            tester.platformDispatcher.clearLocalesTestValue();
          }
          expect(tester.takeException(), isNull);
          cases.add(rendered);
        });
      }
    }
  }
}
