import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:drift/native.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/xclash_defaults.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/desktop/model.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/core/method.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/product.dart';
import 'package:fl_clash/providers/action.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/core.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:fl_clash/providers/state.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:path/path.dart' as p;
import 'package:shared_preferences/shared_preferences.dart';

import '../../../aerove-lite/lib/lite_product.dart';
import '../helpers/app_paths.dart';
import '../helpers/test_profiles.dart';

/// Transport receipt only. Real native acceptance is the downstream OwnedRuntime test.
class _FreshWireCore extends CoreHandlerInterface {
  final calls = <Map<String, Object?>>[];

  Future<CoreLifecycleResult> _unexpectedLifecycle(String operation) =>
      Future<CoreLifecycleResult>.error(
        StateError('Fresh wire export must not invoke native $operation'),
      );
  @override
  Future<CoreLifecycleResult> start() => _unexpectedLifecycle('start');
  @override
  Future<CoreLifecycleResult> restart() => _unexpectedLifecycle('restart');
  @override
  Future<CoreLifecycleResult> stop() => _unexpectedLifecycle('stop');
  @override
  Future<CoreLifecycleResult> close() => _unexpectedLifecycle('close');
  @override
  Future<T?> invokeMethod<T>({
    required CoreMethod method,
    Object? arguments,
    Duration? timeout,
  }) async {
    calls.add({
      'method': method.name,
      'arguments': jsonDecode(jsonEncode(arguments)),
    });
    switch (method) {
      case CoreMethod.setupConfig:
      case CoreMethod.updateConfig:
        return '' as T;
      case CoreMethod.getIsInit:
      case CoreMethod.initClash:
        return true as T;
      case CoreMethod.getProxies:
      case CoreMethod.getAppliedSetup:
        return null;
      case CoreMethod.getExternalProviders:
        return <dynamic>[] as T;
      default:
        throw StateError(
          'Fresh producer attempted an unqualified transport operation: ${method.name}',
        );
    }
  }
}

/// Host has no Android permission UI; no config field or setup operation is replaced.
class _FreshHostSetup extends SetupAction {
  @override
  Future<bool> requestAdmin(bool enableTun) async => true;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('fresh default no-selection runtime export', () async {
    const output = String.fromEnvironment('FRESH_RUNTIME_OUTPUT');
    const source = String.fromEnvironment('FRESH_RUNTIME_SOURCE');
    expect(const bool.fromEnvironment('AEROVE_TEST_SUITE_PRODUCT'), isTrue);
    expect(
      output,
      isNotEmpty,
      reason: 'Explicit fixture export requires its owned output directory',
    );
    expect(RegExp(r'^[0-9a-f]{40}$').hasMatch(source), isTrue);
    SharedPreferences.setMockInitialValues({});
    try {
      ClientProduct.current;
    } on StateError {
      ClientProduct.configure(
        product: liteProduct,
        backendCoreId: 'meow-rs',
        backendFactory: () => throw StateError(
          'Host export must not launch a native application',
        ),
      );
    }
    expect(ClientProduct.current.expectedCoreId, 'meow-rs');
    final requestedRoot = Directory(output).absolute;
    await requestedRoot.create(recursive: true);
    final root = Directory(await requestedRoot.resolveSymbolicLinks());
    final home = Directory(p.join(root.path, 'producer-home'));
    expect(
      await home.exists(),
      isFalse,
      reason: 'Refuse to overwrite a prior fixture or user home',
    );
    await home.create();
    final database = db.Database(
      NativeDatabase(File(p.join(home.path, 'database.sqlite'))),
    );
    final previousDatabase = db.database;
    final previousMd5 = globalState.lastConfigMd5;
    db.database = database;
    ProviderContainer? container;
    var exported = false;
    try {
      await useAppPaths(home);
      await AppLocalizations.load(const Locale('en'));
      globalState.packageInfo = PackageInfo(
        appName: 'AEROVE Lite',
        packageName: 'dev.aerove.fixture.fresh',
        version: '0.0.0',
        buildNumber: '0',
      );
      final storage = await SharedPreferences.getInstance();
      await storage.clear();
      final defaults = XClashDefaults(
        storage: storage,
        database: database,
        directory: home,
        loadScript: () async {
          final data = await rootBundle.load(xclashDefaultScriptAsset);
          return data.buffer.asUint8List(
            data.offsetInBytes,
            data.lengthInBytes,
          );
        },
        createId: () => 1,
      );
      await defaults.prepare(isAndroid: true);
      final config = await defaults.initialize(
        Config(
          currentProfileId: null,
          themeProps: defaultThemeProps,
          patchClashConfig: newPatchClashConfig('meow-rs'),
        ),
      );
      expect(
        jsonDecode(
          await File(p.join(home.path, 'xclash-defaults.json')).readAsString(),
        )['freshAndroid'],
        isTrue,
      );
      expect(config.currentProfileId, isNull);
      expect(config.appSettingProps.acceptedDisclaimerVersion, 0);
      expect(await database.profilesDao.query().get(), isEmpty);
      final core = _FreshWireCore();
      container = ProviderContainer(
        overrides: [
          ...buildConfigOverrides(config),
          profilesProvider.overrideWith(() => TestProfiles([])),
          coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
          setupActionProvider.overrideWith(_FreshHostSetup.new),
        ],
      );
      globalState.container = container;
      globalState.lastConfigMd5 = null;
      await container.read(vpnOptionsProvider.future);
      final shared = container.read(sharedStateProvider).toJson();
      final applied = await container
          .read(setupActionProvider.notifier)
          .applyProfile(force: true, silence: true);
      expect(applied, isTrue);
      final setups = core.calls
          .where((row) => row['method'] == CoreMethod.setupConfig.name)
          .toList();
      expect(setups, hasLength(1));
      expect(
        core.calls.where(
          (row) => {
            CoreMethod.getConfig.name,
            CoreMethod.getProfileBase.name,
          }.contains(row['method']),
        ),
        isEmpty,
      );
      final wire = Map<String, dynamic>.from(setups.single['arguments'] as Map);
      expect(wire['base'], isNull);
      expect(wire['applied'], isA<Map>());
      expect(wire['data-source-proof'], isA<Map>());
      final sourceFile = File(await appPath.configFilePath);
      final sourceBytes = await sourceFile.readAsBytes();
      expect(sourceBytes, isNotEmpty);
      final files = <Map<String, Object?>>[];
      Future<void> save(String name, List<int> bytes) async {
        await File(p.join(root.path, name)).writeAsBytes(bytes, flush: true);
        files.add({
          'path': name,
          'bytes': bytes.length,
          'sha256': sha256.convert(bytes).toString(),
        });
      }

      await save('producer.yaml', sourceBytes);
      await save('setup-wire.json', utf8.encode(jsonEncode(wire)));
      await save('shared-state.json', utf8.encode(jsonEncode(shared)));
      await save(
        'effective-config.json',
        utf8.encode(jsonEncode(config.toJson())),
      );
      await save(
        'source-proof.json',
        utf8.encode(jsonEncode(wire['data-source-proof'])),
      );
      await save('transport-calls.json', utf8.encode(jsonEncode(core.calls)));
      final manifest = {
        'schema': 1,
        'kind': 'fresh-default-host-producer',
        'synthetic': true,
        'source': source,
        'original312Input': false,
        'nativeAccepted': false,
        'home': home.resolveSymbolicLinksSync(),
        'configFile': sourceFile.resolveSymbolicLinksSync(),
        'acceptedDisclaimerVersion': 0,
        'selectedProfile': null,
        'androidDefaultsPrepared': true,
        'hostAuthorizationOnly': true,
        'producer': 'SetupAction.applyProfile/CoreHandlerInterface.setupConfig',
        'packageInfo': {
          'packageName': globalState.packageInfo.packageName,
          'version': globalState.packageInfo.version,
          'buildNumber': globalState.packageInfo.buildNumber,
        },
        'userConfigIdentity': {
          'effectiveConfigFile': 'effective-config.json',
          'sourceProofFile': 'source-proof.json',
        },
        'files': files,
      };
      await File(
        p.join(root.path, 'producer-manifest.json'),
      ).writeAsString(jsonEncode(manifest), flush: true);
      exported = true;
    } finally {
      container?.dispose();
      db.database = previousDatabase;
      globalState.lastConfigMd5 = previousMd5;
      await database.close();
      // A failed explicit export remains owned evidence; it is not a usable manifest.
      if (!exported)
        await File(
          p.join(root.path, 'PRODUCER-FAILED'),
        ).writeAsString('producer failed; no native acceptance');
    }
  });
}
