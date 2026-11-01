import 'dart:convert';
import 'dart:io';
import 'package:crypto/crypto.dart' show sha256;
import 'package:drift/native.dart';
import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/common/string.dart';
import 'package:fl_clash/common/native_lite_migration.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:fl_clash/common/javascript.dart';
import 'package:fl_clash/common/task.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:yaml/yaml.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';

String get _nodeExecutable =>
    Platform.environment['AEROVE_TEST_NODE'] ??
    (Platform.isWindows ? 'C:/Program Files/nodejs/node.exe' : 'node');

Future<Map> _compile(
  Map<String, dynamic> source, {
  bool safe = false,
  String core = 'meow-rs',
  PatchClashConfig? patch,
}) async {
  final built = await _compileBuilt(
    source,
    safe: safe,
    core: core,
    patch: patch,
  );
  return jsonDecode(jsonEncode(loadYaml(built.yaml))) as Map;
}

Future<RealProfile> _compileBuilt(
  Map<String, dynamic> source, {
  bool safe = false,
  String core = 'meow-rs',
  PatchClashConfig? patch,
  ProfileSelectionBinding? selectionBinding,
}) async {
  final home = await Directory.systemTemp.createTemp('resource89-task-');
  try {
    final actual =
        patch ??
        (core == 'meow-rs'
            ? const PatchClashConfig().copyWith.tun(
                stack: TunStack.lwip,
                congestionController: 'builtin',
                device: '',
              )
            : const PatchClashConfig());
    final built = await makeRealProfileTask(
      MakeRealProfileState(
        profilesPath: home.path,
        profileId: 1,
        rawConfig: source,
        realPatchConfig: actual,
        appendSystemDns: false,
        proxyGroups: [],
        rules: [],
        addedRules: [],
        defaultUA: 'fixture',
        safeMode: safe,
      ),
      coreId: core,
      selectionBinding: selectionBinding,
    );
    return built;
  } finally {
    await home.delete(recursive: true);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Standard compiles all three source selection states to its existing SQL-managed product policy',
    () async {
      final compiledStates = <Map>[];
      for (final requested in [true, false, null]) {
        final built = await _compileBuilt({
          if (requested != null) 'profile': {'store-selected': requested},
        }, core: 'mihomo');
        final compiled = jsonDecode(jsonEncode(loadYaml(built.yaml))) as Map;
        expect(compiled['profile']['store-selected'], false);
        expect(built.selectionPolicy.sourceStoreSelected, requested);
        expect(built.selectionPolicy.requestedStoreSelected, requested);
        expect(built.md5, built.yaml.toMd5());
        compiledStates.add(compiled);
      }
      for (final compiled in compiledStates.skip(1))
        expect(compiled, compiledStates.first);
    },
  );

  test(
    'Lite source selection states consume the same reopened per-profile SQL raw map and original source proof',
    () async {
      final home = await Directory.systemTemp.createTemp(
        'selection-policy-sql-',
      );
      final file = File('${home.path}/database.sqlite');
      var database = db.Database(NativeDatabase(file));
      const raw = {
        'selector': 'REMOVED',
        'url-test': 'B',
        'fallback': 'C',
        'unknown-group': 'untouched',
      };
      const other = {'selector': 'OTHER', 'url-test': '', 'fallback': 'A'};
      try {
        await database.profiles.put(
          const Profile(
            id: 1,
            autoUpdateDuration: Duration.zero,
            autoUpdate: false,
            selectedMap: raw,
          ).toCompanion(),
        );
        await database.profiles.put(
          const Profile(
            id: 2,
            autoUpdateDuration: Duration.zero,
            autoUpdate: false,
            selectedMap: other,
          ).toCompanion(),
        );
        await database.close();
        database = db.Database(NativeDatabase(file));
        final config = Config.realFromJson(
          null,
          coreId: 'meow-rs',
        ).copyWith(currentProfileId: 1);
        SharedPreferences.setMockInitialValues({configKey: jsonEncode(config)});
        final storage = await SharedPreferences.getInstance();
        await Directory('${home.path}/profiles').create();
        await File(
          '${home.path}/profiles/2.yaml',
        ).writeAsString('rules: ["MATCH,DIRECT"]\n');
        final sourceHashes = <String>{};
        final sqlHashes = <String>{};
        final compiledStates = <Map>[];
        for (final requested in [true, false, null]) {
          final source =
              '${requested == null ? '' : 'profile: {store-selected: $requested}\n'}rules: ["MATCH,DIRECT"]\n';
          final sourceFile = File('${home.path}/profiles/1.yaml');
          await sourceFile.writeAsString(source, flush: true);
          await database.close();
          database = db.Database(NativeDatabase(file));
          final rows = await database.profilesDao.query().get();
          final restored = rows
              .singleWhere((profile) => profile.id == 1)
              .selectedMap;
          expect(restored, raw);
          expect(
            rows.singleWhere((profile) => profile.id == 2).selectedMap,
            other,
          );
          final proof = await nativeLiteBusinessSource(
            database,
            home,
            storage,
            config,
          );
          sqlHashes.add(
            ((proof['tables'] as Map)['profiles'] as Map)['sha256'] as String,
          );
          final rawSource =
              jsonDecode(jsonEncode(loadYaml(source))) as Map<String, dynamic>;
          final built = await _compileBuilt(
            rawSource,
            selectionBinding: ProfileSelectionBinding(1, restored),
          );
          final params = built.selectionPolicy.consumeSql(
            SetupParams(
              selectedMap: restored,
              testUrl: 'https://example.test/probe',
              dataSourceProof: proof,
            ),
            profileId: 1,
          );
          expect(params.selectedMap, raw);
          expect(params.toJson().containsKey('selection-policy'), false);
          expect(built.selectionPolicy.sourceStoreSelected, requested);
          expect(built.selectionPolicy.requestedStoreSelected, requested);
          final compiled = jsonDecode(jsonEncode(loadYaml(built.yaml))) as Map;
          expect(compiled.containsKey('profile'), false);
          expect(built.md5, built.yaml.toMd5());
          compiledStates.add(compiled);
          final receipt = (proof['files'] as List).cast<Map>().singleWhere(
            (row) => row['path'] == 'profiles/1.yaml',
          );
          final hash = sha256
              .convert(await sourceFile.readAsBytes())
              .toString();
          expect(receipt['sha256'], hash);
          sourceHashes.add(hash);
          expect(
            () => built.selectionPolicy.consumeSql(
              params.copyWith(selectedMap: {'selector': 'newer'}),
              profileId: 1,
            ),
            throwsStateError,
          );
          expect(
            () => built.selectionPolicy.consumeSql(params, profileId: 2),
            throwsStateError,
          );
          expect(
            () => built.selectionPolicy.consumeSql(
              params.copyWith(dataSourceProof: null),
              profileId: 1,
            ),
            throwsStateError,
          );
        }
        expect(sourceHashes, hasLength(3));
        expect(sqlHashes, hasLength(1));
        for (final compiled in compiledStates.skip(1))
          expect(compiled, compiledStates.first);
      } finally {
        await database.close();
        await home.delete(recursive: true);
      }
    },
  );

  test(
    'SQL-bound compilation still rejects unknown source profile fields and non-Boolean declarations',
    () async {
      for (final entry in <String, Object?>{
        'unexpected': true,
        'store-selected': 'true',
      }.entries) {
        await expectLater(
          _compileBuilt({
            'profile': {entry.key: entry.value},
          }, selectionBinding: ProfileSelectionBinding(1, {})),
          throwsA(
            isA<FormatException>().having(
              (error) => error.message,
              'exact source path',
              contains('\$.profile.${entry.key}'),
            ),
          ),
        );
      }
      await expectLater(
        _compileBuilt({
          'profile': {'store-selected': null},
        }, selectionBinding: ProfileSelectionBinding(1, {})),
        throwsA(
          isA<FormatException>().having(
            (error) => error.message,
            'exact source path',
            contains('\$.profile.store-selected'),
          ),
        ),
      );
    },
  );

  test(
    'native edit intent preserves fresh source and actual single Node script output',
    () async {
      final original = <String, dynamic>{
        'type': 'http',
        'url': 'https://old.invalid',
        'filter': 'old',
        'health-check': {
          'enable': false,
          'url': 'https://probe.invalid',
          'unknown': 7,
        },
        'unknown': {'raw': true},
      };
      final initial = ProxyProviderOptions.fromJson(
        original,
      ).copyWith(sourceDefinition: original, editedFields: {});
      final fresh = <String, dynamic>{
        ...original,
        'url': 'https://new.invalid',
        'filter': 'subscription',
        'unknown': {'new': true},
      };
      final base = ProfileOverrides(proxyProviders: {'p': initial});
      final input = jsonEncode({
        'proxy-providers': {'p': fresh},
      });
      expect(
        jsonDecode(
          nativeProxyProviderScriptInput(input, base),
        )['proxy-providers']['p'],
        fresh,
      );
      final edited = initial
          .copyWith(filter: null)
          .withNativeEditsSince(initial);
      expect(edited.editedFields, {'filter'});
      final cleared = jsonDecode(
        nativeProxyProviderScriptInput(
          input,
          base.copyWith(proxyProviders: {'p': edited}),
        ),
      )['proxy-providers']['p'];
      expect(cleared.containsKey('filter'), false);
      expect(cleared['url'], 'https://new.invalid');
      expect(cleared['unknown'], {'new': true});
      final back = edited.copyWith(filter: 'old').withNativeEditsSince(edited);
      expect(back.editedFields, {'filter'});
      expect(back.applyNativeEdits(fresh)['filter'], 'old');
      expect(initial.withNativeEditsSince(initial).editedFields, isEmpty);
      final ordinary = const ProxyProviderOptions(filter: 'normal');
      expect(ordinary.withNativeEditsSince(null).editedFields, isNull);
      expect(
        ProxyProviderOptions.fromJson(
          jsonDecode(jsonEncode(edited)),
        ).editedFields,
        {'filter'},
      );
      final previous = scriptEvaluator;
      var evaluations = 0;
      scriptEvaluator = ({required String script, required String config}) async {
        evaluations++;
        final result = await Process.run(_nodeExecutable, [
          '-e',
          'const config=JSON.parse(process.argv[1]); const main=eval(process.argv[2]); process.stdout.write(JSON.stringify(main(config)));',
          config,
          script,
        ]);
        if (result.exitCode != 0) throw StateError(result.stderr.toString());
        return result.stdout.toString();
      };
      try {
        final result = await handleEvaluate(
          '(config)=>{config["proxy-providers"].p.filter="script";return config;}',
          nativeProxyProviderScriptInput(
            input,
            base.copyWith(proxyProviders: {'p': edited}),
          ),
        );
        expect(jsonDecode(result)['proxy-providers']['p']['filter'], 'script');
        expect(evaluations, 1);
      } finally {
        scriptEvaluator = previous;
      }
    },
  );

  test(
    'Meow emits no generated inactive Mihomo host policy and preserves active values for strict consumer checks',
    () async {
      final result = await _compile({}, safe: true);
      for (final key in [
        'external-controller-tls',
        'external-controller-unix',
        'external-controller-pipe',
        'external-ui',
        'external-ui-url',
        'interface-name',
        'routing-mark',
        'redir-port',
        'tproxy-port',
        'skip-auth-prefixes',
        'profile',
        'disable-keep-alive',
        'tunnels',
        'ss-config',
        'vmess-config',
        'tuic-server',
        'iptables',
      ]) {
        expect(result.containsKey(key), isFalse, reason: key);
      }
      expect(result.containsKey('keep-alive-interval'), false);
      expect(result.containsKey('keep-alive-idle'), false);
      expect(result.containsKey('geodata-loader'), false);
      final standard = await _compile({}, safe: true, core: 'mihomo');
      expect(standard['external-controller-tls'], '');
      expect(standard['tproxy-port'], 0);
      expect(standard['profile']['store-selected'], false);
      expect(standard['tuic-server'], {'enable': false});
      final unknown = await _compile({
        'unrecognized': {'retain': true},
      });
      expect(unknown['unrecognized'], {'retain': true});
    },
  );
  test(
    'unsupported client TCP tuning and loader selection fail by exact field',
    () async {
      final base = const PatchClashConfig().copyWith.tun(
        stack: TunStack.lwip,
        congestionController: 'builtin',
        device: '',
      );
      for (final item in <String, PatchClashConfig>{
        'keep-alive-interval': base.copyWith(keepAliveInterval: 31),
        'keep-alive-idle': base.copyWith(keepAliveIdle: 16),
        'disable-keep-alive': base.copyWith(disableKeepAlive: true),
        'geodata-loader': base.copyWith(geodataLoader: GeodataLoader.standard),
      }.entries) {
        await expectLater(
          _compile({}, patch: item.value),
          throwsA(
            isA<FormatException>().having(
              (e) => e.message,
              'path',
              contains('\$.${item.key}'),
            ),
          ),
        );
      }
      for (final key in [
        'keep-alive-interval',
        'keep-alive-idle',
        'disable-keep-alive',
        'geodata-loader',
      ]) {
        await expectLater(
          _compile({
            key: key == 'disable-keep-alive'
                ? false
                : key == 'geodata-loader'
                ? 'memconservative'
                : 30,
          }),
          throwsA(isA<FormatException>()),
        );
      }
      final standard = await _compile(
        {},
        core: 'mihomo',
        patch: const PatchClashConfig().copyWith(
          keepAliveInterval: 31,
          keepAliveIdle: 16,
          disableKeepAlive: true,
          geodataLoader: GeodataLoader.standard,
        ),
      );
      expect(standard['keep-alive-interval'], 31);
      expect(standard['keep-alive-idle'], 16);
      expect(standard['disable-keep-alive'], true);
      expect(standard['geodata-loader'], 'standard');
    },
  );
  test(
    'Meow rejects effective source fields before Task could overwrite them with inactive client defaults',
    () async {
      for (final entry in <String, Object>{
        'external-controller-tls': 'fixture',
        'external-ui': 'fixture',
        'interface-name': 'fixture',
        'routing-mark': 42,
        'redir-port': 12345,
        'tproxy-port': 12345,
        'skip-auth-prefixes': ['127.0.0.1/8'],
        'profile': {'store-selected': true},
        'keep-alive-interval': 30,
        'geodata-loader': 'standard',
        'ss-config': 'fixture',
        'iptables': {'enable': false, 'unknown': 'retained'},
      }.entries) {
        final original = <String, dynamic>{entry.key: entry.value};
        final before = jsonEncode(original);
        await expectLater(
          _compile(original, safe: true),
          throwsA(
            isA<FormatException>().having(
              (error) => error.message,
              'old field',
              contains('\$.${entry.key}'),
            ),
          ),
        );
        expect(jsonEncode(original), before);
      }
    },
  );

  test(
    'imported provider editable fields override and clear originals while unknown transport fields survive',
    () {
      final raw = <String, Object?>{
        'type': 'http',
        'url': 'https://example.invalid/source',
        'path': 'private.yaml',
        'interval': 321,
        'header': {
          'X-Original': ['fixture-only'],
        },
        'vendor': {'retain': true},
        'filter': 'before',
        'exclude-filter': 'excluded',
        'health-check': {
          'enable': false,
          'url': 'https://example.invalid/health',
          'interval': 300,
          'timeout': 2000,
          'lazy': false,
          'expected-status': '200-299',
          'vendor-health': {'retain': true},
        },
        'override': {
          'additional-prefix': 'before',
          'additional-suffix': 'suffix',
          'udp': true,
          'skip-cert-verify': false,
          'ip-version': 'ipv4',
          'vendor-override': {'retain': true},
        },
      };
      final imported = ProxyProviderOptions.fromJson(
        raw,
      ).copyWith(sourceDefinition: Map<String, dynamic>.from(raw));
      expect(imported.definition, raw);
      final edited = imported
          .copyWith(filter: null, excludeFilter: 'changed')
          .copyWith
          .healthCheck(
            url: null,
            interval: 600,
            timeout: null,
            lazy: true,
            expectedStatus: null,
          )
          .copyWith
          .proxyOverride(
            additionalPrefix: 'after',
            additionalSuffix: null,
            udp: false,
            skipCertVerify: null,
            ipVersion: null,
          );
      final definition = edited.definition;
      expect(definition['url'], raw['url']);
      expect(definition['interval'], 321);
      expect(definition['header'], raw['header']);
      expect(definition['vendor'], raw['vendor']);
      expect(definition.containsKey('filter'), isFalse);
      expect(definition['exclude-filter'], 'changed');
      expect(definition['health-check'], {
        'enable': false,
        'interval': 600,
        'lazy': true,
        'vendor-health': {'retain': true},
      });
      expect(definition['override'], {
        'additional-prefix': 'after',
        'udp': false,
        'vendor-override': {'retain': true},
      });
      expect(
        ProxyProviderOptions.fromJson(
          Map<String, Object?>.from(jsonDecode(jsonEncode(edited)) as Map),
        ).definition,
        definition,
      );
    },
  );
}
