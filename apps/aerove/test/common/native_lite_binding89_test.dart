import 'dart:convert';
import 'dart:io';
import 'package:fl_clash/common/javascript.dart';
import 'package:fl_clash/common/task.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:yaml/yaml.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';

Future<Map> _compile(
  Map<String, dynamic> source, {
  bool safe = false,
  String core = 'meow-rs',
  PatchClashConfig? patch,
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
    );
    return jsonDecode(jsonEncode(loadYaml(built.yaml))) as Map;
  } finally {
    await home.delete(recursive: true);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
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
        final result = await Process.run('C:/Program Files/nodejs/node.exe', [
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
