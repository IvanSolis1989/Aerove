import 'package:fl_clash/common/task.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const customState = SetupState(
    profileId: 7,
    profileLastUpdateDate: 1700000000000,
    profileType: ProfileType.custom,
    extendType: ExtendType.standard,
    rules: [
      Rule(id: 1, content: 'example.com', ruleTarget: 'Proxy'),
      Rule(id: 2, ruleAction: RuleAction.MATCH, ruleTarget: 'DIRECT'),
    ],
    proxyGroups: [
      ProxyGroup(
        id: 3,
        name: 'Proxy',
        type: GroupType.Selector,
        proxies: ['node'],
        use: ['subscription'],
      ),
    ],
    addedRules: [],
    script: null,
    overrides: ProfileOverrides(),
    clashProviders: [ClashProvider(id: 4, label: 'ads', url: 'https://x')],
    profileProviders: {'subscription': 5},
    appProxies: [
      CustomProxy(id: 6, definition: {'name': 'node', 'type': 'ss'}),
    ],
    proxyDialers: {6: 'Proxy'},
    matchTarget: null,
  );

  SetupInputsState inputs({
    SetupState setupState = customState,
    PatchClashConfig patchConfig = const PatchClashConfig(),
    String testUrl = 'https://example.com/generate_204',
    bool safeMode = false,
    String build = '1',
  }) => (
    setupState: setupState,
    setupPatch: patchConfig.setupOnly,
    appendSystemDns: false,
    bypassPrivateRoute: true,
    testUrl: testUrl,
    skipCertVerify: false,
    safeMode: safeMode,
    build: build,
  );

  final baseline = setupInputsDigest(inputs());

  test('equal inputs give the same digest', () {
    expect(setupInputsDigest(inputs()), baseline);
    expect(baseline, hasLength(64));
  });

  test('a setting patched live leaves it alone', () {
    expect(
      setupInputsDigest(
        inputs(
          patchConfig: const PatchClashConfig(
            mode: Mode.global,
            logLevel: LogLevel.debug,
          ),
        ),
      ),
      baseline,
    );
  });

  test('what only a rebuild hands the Core moves it', () {
    final moved = {
      'hosts': inputs(
        patchConfig: const PatchClashConfig(hosts: {'a.test': '1.1.1.1'}),
      ),
      'rules': inputs(
        setupState: customState.copyWith(rules: customState.rules.sublist(1)),
      ),
      'profile file': inputs(
        setupState: customState.copyWith(profileLastUpdateDate: 1),
      ),
      'match target': inputs(
        setupState: customState.copyWith(matchTarget: 'Proxy'),
      ),
      'rule provider': inputs(
        setupState: customState.copyWith(
          clashProviders: [
            const ClashProvider(id: 4, label: 'ads', url: 'https://y'),
          ],
        ),
      ),
      'dialer': inputs(
        setupState: customState.copyWith(proxyDialers: {6: 'DIRECT'}),
      ),
      'test url': inputs(testUrl: 'https://example.org'),
      'safe mode': inputs(safeMode: true),
      'build': inputs(build: '2'),
    };

    for (final MapEntry(key: name, value: state) in moved.entries) {
      expect(setupInputsDigest(state), isNot(baseline), reason: name);
    }
  });
}
