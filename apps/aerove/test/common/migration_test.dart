import 'dart:convert';

import 'package:fl_clash/common/migration.dart';
import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/ui/color.dart';
import 'package:material_ui/material_ui.dart' show ThemeMode;
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('Migration', () {
    test('returns current config without rewriting storage', () async {
      final configMap = _createConfigMap(
        davProps: const DAVProps(
          uri: 'https://example.com/dav',
          user: 'user',
          password: 'secret',
        ),
      );
      final store = _FakeMigrationStore(
        configMap: configMap,
        version: Migration.currentVersion,
      );

      final config = await Migration(store: store).run();

      expect(config, Config.realFromJson(configMap));
      expect(config.davProps?.password, 'secret');
      expect(store.events, ['getConfigMap', 'getVersion']);
    });

    test(
      'obfuscates a compatible DAV password without a version migration',
      () async {
        final configMap = _createConfigMap(
          davProps: const DAVProps(
            uri: 'https://example.com/dav',
            user: 'user',
          ),
        );
        final davProps = configMap['davProps']! as Map<String, Object?>;
        davProps['password'] = 'secret';
        final store = _FakeMigrationStore(configMap: configMap, version: 1);

        final config = await Migration(store: store).run();

        expect(config.davProps?.user, 'user');
        expect(config.davProps?.password, 'secret');
        expect(store.savedConfig, config);
        expect(store.version, Migration.currentVersion);
        expect(store.events, ['getConfigMap', 'getVersion', 'saveConfig']);
        final savedConfigMap =
            jsonDecode(jsonEncode(store.savedConfig)) as Map<String, Object?>;
        final savedDavProps =
            savedConfigMap['davProps']! as Map<String, Object?>;
        expect(savedDavProps['password'], startsWith('v1.'));
        expect(savedDavProps['password'], isNot(contains('secret')));
      },
    );

    test(
      'commits v0 cleanup and version only after migrated data is saved',
      () async {
        final configMap = <String, Object?>{
          'proxiesStyle': <String, Object?>{},
          'dav': <String, Object?>{
            'uri': 'https://example.com/dav',
            'user': 'user',
            'password': 'secret',
          },
        };
        final store = _FakeMigrationStore(
          configMap: configMap,
          version: 0,
          clashConfigMap: <String, Object?>{'mixed-port': 7890},
        );
        final migration = Migration(
          store: store,
          migrateV0: (configMap) async {
            store.events.add('migrateV0');
            expect(configMap['patchClashConfig'], store.clashConfigMap);
            return MigrationData(
              configMap: _createConfigMap(
                davProps: const DAVProps(
                  uri: 'https://example.com/dav',
                  user: 'user',
                  password: 'secret',
                ),
              ),
            );
          },
        );

        await migration.run();

        expect(store.events, [
          'getConfigMap',
          'getVersion',
          'getClashConfigMap',
          'migrateV0',
          'restore',
          'saveConfig',
          'clearClashConfig',
          'setVersion',
        ]);
        expect(store.savedConfig?.davProps?.password, 'secret');
        expect(store.didClearClashConfig, isTrue);
        expect(store.version, Migration.currentVersion);
      },
    );

    test(
      'preserves legacy clash config when current-shaped data has version zero',
      () async {
        final configMap = _createConfigMap()..remove('patchClashConfig');
        final clashConfigMap = _createClashConfigMap(mixedPort: 1234);
        final store = _FakeMigrationStore(
          configMap: configMap,
          version: 0,
          clashConfigMap: clashConfigMap,
        );

        final config = await Migration(store: store).run();

        expect(config.patchClashConfig.mixedPort, 1234);
        expect(store.savedConfig?.patchClashConfig.mixedPort, 1234);
        expect(store.didClearClashConfig, isTrue);
        expect(store.events, [
          'getConfigMap',
          'getVersion',
          'getClashConfigMap',
          'restore',
          'saveConfig',
          'clearClashConfig',
          'setVersion',
        ]);
      },
    );

    test('does not clear legacy clash config when saving fails', () async {
      final configMap = _createConfigMap()..remove('patchClashConfig');
      final store = _FakeMigrationStore(
        configMap: configMap,
        version: 0,
        clashConfigMap: _createClashConfigMap(mixedPort: 1234),
        configSaveResult: false,
      );

      await expectLater(
        Migration(store: store).run(),
        throwsA(isA<StateError>()),
      );

      expect(store.didClearClashConfig, isFalse);
      expect(store.version, 0);
      expect(store.events, [
        'getConfigMap',
        'getVersion',
        'getClashConfigMap',
        'restore',
        'saveConfig',
        'isAvailable',
      ]);
    });

    test('starts with defaults when the store cannot be opened', () async {
      final store = _FakeMigrationStore(
        configMap: null,
        version: 0,
        configSaveResult: false,
        available: false,
      );

      final config = await Migration(store: store).run();

      expect(config, isNotNull);
      expect(store.didClearClashConfig, isFalse);
      expect(store.version, 0);
      expect(store.events, [
        'getConfigMap',
        'getVersion',
        'getClashConfigMap',
        'restore',
        'saveConfig',
        'isAvailable',
      ]);
    });

    group('initial theme', () {
      test('saves once and preserves later user choices', () async {
        final store = _FakeMigrationStore(
          configMap: null,
          version: 0,
          upgradeVersion: 0,
          firstInstall: true,
        );
        final selected = defaultThemeProps.copyWith(
          auroraEnabled: false,
          liquidGlass: LiquidGlassMode.standard,
        );
        var calls = 0;
        Future<ThemeProps> initialTheme() async {
          calls++;
          store.events.add('initialTheme');
          return selected;
        }

        final migration = Migration(store: store);
        final first = await migration.run(initialTheme: initialTheme);

        expect(first.themeProps, selected);
        expect(store.savedConfig?.themeProps, selected);
        expect(Config.realFromJson(store.configMap).themeProps, selected);
        expect(calls, 1);
        expect(store.events, [
          'getConfigMap',
          'getVersion',
          'getClashConfigMap',
          'restore',
          'initialTheme',
          'saveConfig',
          'setVersion',
          'setUpgradeVersion',
        ]);
        expect(store.version, Migration.currentVersion);
        expect(store.upgradeVersion, Migration.upgradeVersion);
        expect(
          (await migration.run(initialTheme: initialTheme)).themeProps,
          selected,
        );
        expect(calls, 1);

        for (final theme in [
          defaultThemeProps.copyWith(
            auroraEnabled: true,
            liquidGlass: LiquidGlassMode.none,
          ),
          defaultThemeProps,
        ]) {
          expect(
            await store.saveConfig(first.copyWith(themeProps: theme)),
            isTrue,
          );
          expect(
            (await migration.run(initialTheme: initialTheme)).themeProps,
            theme,
          );
          expect(calls, 1);
        }
      });

      test('preserves existing defaults and legacy clash config', () async {
        final existing = _FakeMigrationStore(
          configMap: _createConfigMap(),
          version: Migration.currentVersion,
        );
        final legacy = _FakeMigrationStore(
          configMap: null,
          version: 0,
          clashConfigMap: _createClashConfigMap(mixedPort: 1234),
        );
        Future<ThemeProps> unexpected() async {
          fail('An existing configuration must not select an initial theme');
        }

        expect(
          (await Migration(
            store: existing,
          ).run(initialTheme: unexpected)).themeProps,
          defaultThemeProps,
        );
        final migrated = await Migration(
          store: legacy,
        ).run(initialTheme: unexpected);
        expect(migrated.patchClashConfig.mixedPort, 1234);
        expect(legacy.didClearClashConfig, isTrue);
      });

      test('does not select when storage is unavailable', () async {
        final store = _FakeMigrationStore(
          configMap: null,
          version: 0,
          firstInstall: true,
          available: false,
          configSaveResult: false,
        );
        final config = await Migration(store: store).run(
          initialTheme: () async {
            fail('Unavailable storage cannot prove a first install');
          },
        );

        expect(config.themeProps, defaultThemeProps);
        expect(store.version, 0);
        expect(store.didClearClashConfig, isFalse);
      });

      test('failed first save can retry without marking a version', () async {
        final store = _FakeMigrationStore(
          configMap: null,
          version: 0,
          upgradeVersion: 0,
          firstInstall: true,
          configSaveResult: false,
        );
        var calls = 0;
        for (var attempt = 0; attempt < 2; attempt++) {
          await expectLater(
            Migration(store: store).run(
              initialTheme: () async {
                calls++;
                return defaultThemeProps.copyWith(auroraEnabled: false);
              },
            ),
            throwsA(isA<StateError>()),
          );
          expect(store.configMap, isNull);
          expect(await store.isFirstInstall, isTrue);
          expect(store.version, 0);
          expect(store.upgradeVersion, 0);
          expect(store.didClearClashConfig, isFalse);
        }
        expect(calls, 2);
        expect(store.events, isNot(contains('setVersion')));
        expect(store.events, isNot(contains('setUpgradeVersion')));
      });

      test('propagates a decision failure without saving', () async {
        final store = _FakeMigrationStore(
          configMap: null,
          version: 0,
          upgradeVersion: 0,
          firstInstall: true,
        );
        final failure = StateError('initial theme decision failed');

        await expectLater(
          Migration(store: store).run(initialTheme: () async => throw failure),
          throwsA(same(failure)),
        );

        expect(store.savedConfig, isNull);
        expect(store.events, isNot(contains('saveConfig')));
        expect(store.version, 0);
        expect(store.upgradeVersion, 0);
      });
    });

    group('upgrade', () {
      Map<String, Object?> customized() =>
          _createConfigMap()
            ..['themeProps'] = {
              'primaryColor': 0xFF123456,
              'primaryColors': [0xFF795548, 0xFF123456],
              'schemeVariant': 'tonalSpot',
              'themeMode': 'dark',
              'pureBlack': true,
              'liquidGlass': true,
              'systemFontFamily': 'Beta Serif',
            };

      test('starts theme colors over once and keeps the rest', () async {
        final store = _FakeMigrationStore(
          configMap: customized(),
          version: Migration.currentVersion,
          upgradeVersion: 0,
        );

        final config = await Migration(store: store).run();

        final theme = config.themeProps;
        expect(theme.primaryColor, defaultPrimaryColor);
        expect(theme.primaryColors, defaultPrimaryColors);
        expect(theme.colorStyle, ColorStyle.vivid);
        expect(theme.themeMode, ThemeMode.dark);
        expect(theme.pureBlack, isTrue);
        expect(theme.liquidGlass, LiquidGlassMode.ultra);
        expect(theme.fontFamily, 'Beta Serif');
        expect(store.savedConfig, config);
        expect(store.version, Migration.currentVersion);
        expect(store.upgradeVersion, Migration.upgradeVersion);
        expect(store.events, [
          'getConfigMap',
          'getVersion',
          'saveConfig',
          'setUpgradeVersion',
        ]);

        final later = _FakeMigrationStore(
          configMap: jsonDecode(
            jsonEncode(config.copyWith.themeProps(primaryColor: 0xFF123456)),
          ),
          version: store.version,
          upgradeVersion: store.upgradeVersion,
        );
        final kept = await Migration(store: later).run();

        expect(kept.themeProps.primaryColor, 0xFF123456);
        expect(later.events, ['getConfigMap', 'getVersion']);
      });

      test('keeps standard glass on the ultra tier it became, once', () async {
        Future<LiquidGlassMode> upgrade(String mode, int from) async {
          final configMap = _createConfigMap();
          (configMap['themeProps']! as Map<String, Object?>)['liquidGlass'] =
              mode;
          final store = _FakeMigrationStore(
            configMap: configMap,
            version: Migration.currentVersion,
            upgradeVersion: from,
          );
          return (await Migration(store: store).run()).themeProps.liquidGlass;
        }

        expect(await upgrade('standard', 1), LiquidGlassMode.ultra);
        expect(await upgrade('none', 1), LiquidGlassMode.none);
        expect(await upgrade('standard', 2), LiquidGlassMode.standard);
      });

      test('moves presets picked before to the current system colors, '
          'once', () async {
        Future<ThemeProps> upgrade(int from) async {
          final configMap = _createConfigMap();
          (configMap['themeProps']! as Map<String, Object?>)
            ..['primaryColor'] = 0xFFFF9500
            ..['primaryColors'] = [
              0xFF007AFF,
              0xFFAF52DE,
              0xFFCB30E0,
              0xFFFF9500,
              0xFF123456,
            ];
          final store = _FakeMigrationStore(
            configMap: configMap,
            version: Migration.currentVersion,
            upgradeVersion: from,
          );
          return (await Migration(store: store).run()).themeProps;
        }

        final moved = await upgrade(2);
        expect(moved.primaryColor, 0xFFFF8D28);
        expect(moved.primaryColors, [
          0xFF007AFF,
          0xFFCB30E0,
          0xFFFF8D28,
          0xFF123456,
        ]);
        expect((await upgrade(3)).primaryColor, 0xFFFF9500);
      });

      test(
        'keeps Aerove sidebar blur choices across reference upgrades',
        () async {
          for (final enabled in [false, true]) {
            for (final from in [2, 3, 4]) {
              final configMap = _createConfigMap();
              (configMap['themeProps']!
                      as Map<String, Object?>)['sidebarBlur'] =
                  enabled;
              final store = _FakeMigrationStore(
                configMap: configMap,
                version: Migration.currentVersion,
                upgradeVersion: from,
              );
              expect(
                (await Migration(store: store).run()).themeProps.sidebarBlur,
                enabled,
              );
            }
          }
        },
      );

      test('a new install only records it', () async {
        final store = _FakeMigrationStore(
          configMap: null,
          version: 0,
          upgradeVersion: 0,
        );

        final config = await Migration(store: store).run();

        expect(config.themeProps, defaultThemeProps);
        expect(store.upgradeVersion, Migration.upgradeVersion);
        expect(
          store.events.where((event) => event == 'saveConfig'),
          hasLength(1),
        );
      });

      test('waits for the next launch when saving fails', () async {
        final store = _FakeMigrationStore(
          configMap: customized(),
          version: Migration.currentVersion,
          upgradeVersion: 0,
          configSaveResult: false,
        );

        final config = await Migration(store: store).run();

        expect(config.themeProps.primaryColor, 0xFF123456);
        expect(store.upgradeVersion, 0);
      });
    });

    test('keeps what it can read of a damaged current config', () async {
      final configMap = _createConfigMap()
        ..['currentProfileId'] = 7
        ..['excludeSSIDs'] = 'home';
      final store = _FakeMigrationStore(
        configMap: configMap,
        version: Migration.currentVersion,
      );

      final config = await Migration(store: store).run();

      expect(config.currentProfileId, 7);
      expect(config.excludeSSIDs, isEmpty);
      expect(store.events, ['getConfigMap', 'getVersion']);
    });

    test('keeps the current version when password obfuscation fails', () async {
      final configMap = _createConfigMap(
        davProps: const DAVProps(uri: 'https://example.com/dav', user: 'user'),
      );
      final davProps = configMap['davProps']! as Map<String, Object?>;
      davProps['password'] = 'secret';
      final store = _FakeMigrationStore(
        configMap: configMap,
        version: Migration.currentVersion,
        configSaveResult: false,
      );

      await expectLater(
        Migration(store: store).run(),
        throwsA(isA<StateError>()),
      );

      expect(store.events, ['getConfigMap', 'getVersion', 'saveConfig']);
      expect(store.version, Migration.currentVersion);
    });
  });
}

Map<String, Object?> _createConfigMap({DAVProps? davProps}) {
  return jsonDecode(
        jsonEncode(Config(themeProps: defaultThemeProps, davProps: davProps)),
      )
      as Map<String, Object?>;
}

Map<String, Object?> _createClashConfigMap({required int mixedPort}) {
  return jsonDecode(jsonEncode(PatchClashConfig(mixedPort: mixedPort)))
      as Map<String, Object?>;
}

class _FakeMigrationStore implements MigrationStore {
  Map<String, Object?>? configMap;
  final Map<String, Object?>? clashConfigMap;
  final bool configSaveResult;
  final bool available;
  bool _firstInstall;
  final List<String> events = [];

  int version;
  int upgradeVersion;
  Config? savedConfig;
  MigrationData? restoredData;
  bool didClearClashConfig = false;

  _FakeMigrationStore({
    required this.configMap,
    required this.version,
    this.upgradeVersion = Migration.upgradeVersion,
    this.clashConfigMap,
    this.configSaveResult = true,
    this.available = true,
    bool firstInstall = false,
  }) : _firstInstall = firstInstall;

  @override
  Future<bool> get isAvailable async {
    events.add('isAvailable');
    return available;
  }

  @override
  Future<bool> get isFirstInstall async => available && _firstInstall;

  @override
  Future<void> clearClashConfig() async {
    events.add('clearClashConfig');
    didClearClashConfig = true;
  }

  @override
  Future<Map<String, Object?>?> getClashConfigMap() async {
    events.add('getClashConfigMap');
    return clashConfigMap;
  }

  @override
  Future<Map<String, Object?>?> getConfigMap() async {
    events.add('getConfigMap');
    return configMap;
  }

  @override
  Future<int> getVersion() async {
    events.add('getVersion');
    return version;
  }

  @override
  Future<void> restore(MigrationData data) async {
    events.add('restore');
    restoredData = data;
  }

  @override
  Future<bool> saveConfig(Config config) async {
    events.add('saveConfig');
    savedConfig = config;
    if (configSaveResult) {
      configMap = jsonDecode(jsonEncode(config)) as Map<String, Object?>;
      _firstInstall = false;
    }
    return configSaveResult;
  }

  @override
  Future<void> setVersion(int version) async {
    events.add('setVersion');
    this.version = version;
  }

  @override
  Future<int> getUpgradeVersion() async => upgradeVersion;

  @override
  Future<void> setUpgradeVersion(int version) async {
    events.add('setUpgradeVersion');
    upgradeVersion = version;
  }
}
