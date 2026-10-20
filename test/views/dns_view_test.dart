import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/features/form/form.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/pages/editor.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/config/dns.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

const _profileId = 1;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late ProviderContainer container;

  Future<void> pumpView(WidgetTester tester, {bool profile = false}) async {
    tester.view.physicalSize = const Size(1200, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(
          () => TestProfiles([
            const Profile(
              id: _profileId,
              type: ProfileType.custom,
              autoUpdateDuration: Duration.zero,
            ),
          ]),
        ),
      ],
    );
    container
        .read(patchClashConfigProvider.notifier)
        .update(
          (state) => state.copyWith(
            dns: state.dns.copyWith(ipv6: false),
            dnsOverrideKeys: {},
          ),
        );
    container
        .read(profilesProvider.notifier)
        .updateProfile(
          _profileId,
          (profile) => profile.copyWith.overrides(
            dns: profile.overrides.dns.copyWith(ipv6: false),
            dnsOverrideKeys: {},
          ),
        );
    addTearDown(container.dispose);
    globalState.container = container;
    container
        .read(viewSizeProvider.notifier)
        .update((_) => const Size(1200, 1000));

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          child: profile ? DnsView.profile(_profileId) : const DnsView(),
        ),
      ),
    );
    await tester.pump();
  }

  PatchClashConfig config() => container.read(patchClashConfigProvider);

  ProfileOverrides overrides() =>
      container.read(profilesProvider).getProfile(_profileId)!.overrides;

  void setKeys(Set<DnsOverrideKey> keys) {
    container
        .read(patchClashConfigProvider.notifier)
        .update((state) => state.copyWith(dnsOverrideKeys: keys));
  }

  void setProfileKeys(Set<DnsOverrideKey> keys) {
    container
        .read(profilesProvider.notifier)
        .updateProfile(
          _profileId,
          (profile) => profile.copyWith.overrides(dnsOverrideKeys: keys),
        );
  }

  String emptyLabel() =>
      currentAppLocalizations.nullTip(currentAppLocalizations.overrideEntries);

  Finder inSheet(String title) => find.descendant(
    of: find.byType(SelectionSheet<DnsOverrideKey>),
    matching: find.text(title),
  );

  Future<void> openAddSheet(WidgetTester tester, {bool profile = false}) async {
    final l = currentAppLocalizations;
    await tester.tap(
      find.text(profile ? l.addSettingEntry : l.addOverrideEntry),
    );
    await tester.pumpAndSettle();
  }

  IconButton barButton(WidgetTester tester, String tooltip) =>
      tester.widget<IconButton>(
        find.ancestor(
          of: find.byTooltip(tooltip),
          matching: find.byType(IconButton),
        ),
      );

  testWidgets('a key picked from the sheet starts at its default', (
    tester,
  ) async {
    await pumpView(tester);
    expect(find.text(emptyLabel()), findsOneWidget);

    await openAddSheet(tester);
    expect(inSheet(currentAppLocalizations.addOverrideEntry), findsOneWidget);
    await tester.tap(find.text(currentAppLocalizations.listen));
    await tester.pumpAndSettle();

    expect(config().dnsOverrideKeys, {DnsOverrideKey.listen});
    expect(config().dns.listen, isEmpty);
    expect(find.text(emptyLabel()), findsNothing);
    expect(find.text(currentAppLocalizations.listen), findsOneWidget);
    expect(tester.takeException(), null);
  });

  testWidgets('a key added again starts over from its default', (tester) async {
    await pumpView(tester);
    container
        .read(patchClashConfigProvider.notifier)
        .update((state) => state.copyWith.dns(ipv6: true));

    await openAddSheet(tester);
    await tester.tap(find.text('IPv6'));
    await tester.pumpAndSettle();

    expect(config().dnsOverrideKeys, {DnsOverrideKey.ipv6});
    expect(config().dns.ipv6, xclashDefaultDns.ipv6);
  });

  testWidgets('the empty page says where the entries apply and adds from it', (
    tester,
  ) async {
    final l = currentAppLocalizations;
    await pumpView(tester);

    expect(find.text(l.dnsOverrideDesc), findsOneWidget);
    expect(find.byTooltip(l.addOverrideEntry), findsNothing);

    setKeys({DnsOverrideKey.ipv6});
    await tester.pumpAndSettle();

    expect(find.text(emptyLabel()), findsNothing);
    expect(find.text(l.dnsOverrideDesc), findsOneWidget);
    await tester.tap(find.byTooltip(l.addOverrideEntry));
    await tester.pumpAndSettle();
    expect(inSheet(l.addOverrideEntry), findsOneWidget);
    expect(inSheet('IPv6'), findsNothing);
  });

  testWidgets('the app-wide page offers only the normal profile keys', (
    tester,
  ) async {
    await pumpView(tester);

    await openAddSheet(tester);

    expect(find.text('Direct Nameserver'), findsOneWidget);
    expect(find.text(currentAppLocalizations.respectRules), findsNothing);
    expect(find.text('Nameserver Policy'), findsNothing);
    expect(find.text(currentAppLocalizations.fallbackFilter), findsNothing);
  });

  testWidgets('the app-wide page offers no quick edit', (tester) async {
    await pumpView(tester);
    setKeys({DnsOverrideKey.ipv6});
    await tester.pump();

    expect(find.byTooltip(currentAppLocalizations.quickEdit), findsNothing);
  });

  testWidgets('an options key is added without asking for its value', (
    tester,
  ) async {
    await pumpView(tester);

    await openAddSheet(tester);
    await tester.tap(find.text(currentAppLocalizations.dnsMode));
    await tester.pumpAndSettle();

    expect(find.byType(CommonPopupMenu), findsNothing);
    expect(config().dnsOverrideKeys, {DnsOverrideKey.enhancedMode});
    expect(config().dns.enhancedMode, xclashDefaultDns.enhancedMode);
    expect(xclashDefaultDns.enhancedMode, DnsMode.fakeIp);
    expect(find.text('fake-ip'), findsOneWidget);
  });

  testWidgets('a toggle entry writes its value and can be removed', (
    tester,
  ) async {
    await pumpView(tester);
    setKeys({DnsOverrideKey.ipv6});
    await tester.pump();

    expect(find.text('IPv6'), findsOneWidget);
    await tester.tap(find.byType(Switch).last);
    await tester.pump();
    expect(config().dns.ipv6, isTrue);

    await tester.tap(find.byTooltip(currentAppLocalizations.remove));
    await tester.pumpAndSettle();
    expect(config().dnsOverrideKeys, isEmpty);
    expect(config().dns.ipv6, xclashDefaultDns.ipv6);
    expect(find.text('IPv6'), findsNothing);
    expect(find.text(emptyLabel()), findsOneWidget);
  });

  testWidgets(
    'resetting and re-adding bootstrap DNS restores only four DoH resolvers',
    (tester) async {
      await pumpView(tester);
      setKeys({DnsOverrideKey.defaultNameserver});
      container
          .read(patchClashConfigProvider.notifier)
          .update(
            (state) =>
                state.copyWith.dns(defaultNameserver: ['114.114.114.114']),
          );
      await tester.pump();

      await tester.tap(find.byTooltip(currentAppLocalizations.remove));
      await tester.pumpAndSettle();
      expect(config().dns.defaultNameserver, xclashBootstrapNameservers);

      await openAddSheet(tester);
      await tester.tap(find.text('Default Nameserver'));
      await tester.pumpAndSettle();
      expect(config().dnsOverrideKeys, {DnsOverrideKey.defaultNameserver});
      expect(config().dns.defaultNameserver, xclashBootstrapNameservers);
      expect(
        config().dns.defaultNameserver.every(
          (value) => value.startsWith('https://'),
        ),
        true,
      );
      expect(find.text(currentAppLocalizations.itemsCount(4)), findsOneWidget);
    },
  );

  testWidgets('a list entry without a description summarises its items', (
    tester,
  ) async {
    await pumpView(tester);
    setKeys({DnsOverrideKey.nameserver, DnsOverrideKey.defaultNameserver});
    container
        .read(patchClashConfigProvider.notifier)
        .update(
          (state) => state.copyWith.dns(
            nameserver: ['1.1.1.1', '8.8.8.8'],
            defaultNameserver: [],
          ),
        );
    await tester.pump();

    expect(find.text(currentAppLocalizations.itemsCount(2)), findsOneWidget);
    expect(find.text(currentAppLocalizations.none), findsOneWidget);
  });

  testWidgets('an entry emptied on the app-wide page overrides with nothing', (
    tester,
  ) async {
    await pumpView(tester);
    setKeys({
      DnsOverrideKey.listen,
      DnsOverrideKey.nameserver,
      DnsOverrideKey.defaultNameserver,
    });
    container
        .read(patchClashConfigProvider.notifier)
        .update(
          (state) => state.copyWith.dns(
            listen: ':1053',
            nameserver: ['1.1.1.1'],
            defaultNameserver: ['223.5.5.5'],
          ),
        );
    await tester.pump();

    await tester.tap(find.text(currentAppLocalizations.listen));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '');
    await tester.tap(find.text(currentAppLocalizations.submit));
    await tester.pumpAndSettle();
    final defaultNameserver = find.ancestor(
      of: find.text('Default Nameserver'),
      matching: find.byType(ConfigListEditItem),
    );
    tester
        .widget<ConfigListEditItem>(defaultNameserver)
        .onChanged(tester.element(defaultNameserver) as WidgetRef, const []);
    await tester.pumpAndSettle();

    expect(config().dnsOverrideKeys, hasLength(3));
    expect(
      config().dns.overrideJson(config().dnsOverrideKeys, keepEmpty: true),
      {
        'listen': '',
        'default-nameserver': <String>[],
        'nameserver': ['1.1.1.1'],
      },
    );
  });

  testWidgets('the DNS mode options read as mihomo writes them and leave '
      'out hosts', (tester) async {
    await pumpView(tester);
    setKeys({DnsOverrideKey.enhancedMode});
    await tester.pump();

    await tester.tap(find.text(currentAppLocalizations.dnsMode));
    await tester.pumpAndSettle();

    final options = find.byType(CommonPopupMenu);
    expect(options, findsOneWidget);
    final items = tester.widget<CommonPopupMenu>(options).items;
    expect(items.map((item) => item.label), [
      'normal',
      'fake-ip',
      'redir-host',
    ]);
    expect(items.map((item) => item.checked), [false, true, false]);
    Finder option(String label) =>
        find.descendant(of: options, matching: find.text(label));
    expect(option('normal'), findsOneWidget);
    expect(option('fake-ip'), findsOneWidget);
    expect(option('redir-host'), findsOneWidget);
    expect(option(DnsMode.redirHost.name), findsNothing);
    expect(option(DnsMode.hosts.name), findsNothing);
    await tester.tap(option('normal'));
    await tester.pumpAndSettle();
    expect(config().dns.enhancedMode, DnsMode.normal);
    expect(config().dnsOverrideKeys, {DnsOverrideKey.enhancedMode});
    expect(find.byType(CommonPopupMenu), findsNothing);
  });

  testWidgets('the add button disables while every key is listed', (
    tester,
  ) async {
    await pumpView(tester);
    setKeys(DnsOverrideKey.normalProfileKeys);
    await tester.pump();

    final add = barButton(tester, currentAppLocalizations.addOverrideEntry);
    expect(add.onPressed, isNull);
  });

  Future<EditorPage> openQuickEdit(WidgetTester tester) async {
    await tester.tap(find.byTooltip(currentAppLocalizations.quickEdit));
    await tester.pumpAndSettle();
    return tester.widget<EditorPage>(find.byType(EditorPage));
  }

  group('a custom profile', () {
    testWidgets('keeps its keys apart from the app-wide ones', (tester) async {
      await pumpView(tester, profile: true);

      await openAddSheet(tester, profile: true);
      await tester.tap(find.text(currentAppLocalizations.respectRules));
      await tester.pumpAndSettle();

      expect(overrides().dnsOverrideKeys, {DnsOverrideKey.respectRules});
      expect(overrides().dns.respectRules, xclashDefaultDns.respectRules);
      expect(config().dnsOverrideKeys, isEmpty);
    });

    testWidgets('describes its own settings rather than overrides', (
      tester,
    ) async {
      final l = currentAppLocalizations;
      await pumpView(tester, profile: true);

      expect(find.text(l.nullTip(l.settingEntries)), findsOneWidget);
      expect(find.text(l.profileSettingsDesc), findsOneWidget);
      expect(find.text(l.dnsOverrideDesc), findsNothing);

      await openAddSheet(tester, profile: true);
      expect(inSheet(l.addSettingEntry), findsOneWidget);
    });

    testWidgets('groups entries by what they configure', (tester) async {
      await pumpView(tester, profile: true);
      setProfileKeys({
        DnsOverrideKey.fallbackFilterDomain,
        DnsOverrideKey.nameserver,
        DnsOverrideKey.fakeIpTtl,
        DnsOverrideKey.enable,
      });
      await tester.pump();

      final l = currentAppLocalizations;
      double header(String title) =>
          tester.getTopLeft(find.widgetWithText(ListHeader, title)).dy;
      final headers = [
        l.options,
        'Fake-IP',
        'Nameserver',
        l.fallbackFilter,
      ].map(header).toList();
      for (var i = 1; i < headers.length; i++) {
        expect(headers[i - 1], lessThan(headers[i]));
      }
      final ttl = tester.getTopLeft(find.text(l.fakeipTtl)).dy;
      expect(ttl, inExclusiveRange(headers[1], headers[2]));
      expect(
        tester.getTopLeft(find.text(l.domain)).dy,
        greaterThan(headers[3]),
      );
    });

    testWidgets('a list entry closed unchanged stays and one emptied is '
        'taken off', (tester) async {
      await pumpView(tester, profile: true);
      setProfileKeys({DnsOverrideKey.nameserver, DnsOverrideKey.fallback});
      container
          .read(profilesProvider.notifier)
          .updateProfile(
            _profileId,
            (profile) => profile.copyWith.overrides.dns(
              nameserver: ['1.1.1.1'],
              fallback: [],
            ),
          );
      await tester.pump();

      await tester.tap(find.text('Fallback'));
      await tester.pumpAndSettle();
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(overrides().dnsOverrideKeys, contains(DnsOverrideKey.fallback));

      final nameserver = find.ancestor(
        of: find.text('Nameserver'),
        matching: find.byType(ConfigListEditItem),
      );
      tester
          .widget<ConfigListEditItem>(nameserver)
          .onChanged(tester.element(nameserver) as WidgetRef, const []);
      await tester.pumpAndSettle();
      expect(overrides().dnsOverrideKeys, {DnsOverrideKey.fallback});
      expect(overrides().dns.nameserver, xclashDefaultDns.nameserver);
    });

    testWidgets('a policy entry summarises its rules', (tester) async {
      await pumpView(tester, profile: true);
      setProfileKeys({
        DnsOverrideKey.nameserverPolicy,
        DnsOverrideKey.proxyServerNameserverPolicy,
      });
      container
          .read(profilesProvider.notifier)
          .updateProfile(
            _profileId,
            (profile) => profile.copyWith.overrides.dns(
              nameserverPolicy: {'+.example.com': '1.1.1.1'},
            ),
          );
      await tester.pump();

      expect(find.text(currentAppLocalizations.itemsCount(1)), findsOneWidget);
      expect(find.text(currentAppLocalizations.none), findsOneWidget);
    });

    testWidgets('a number entry writes the integer it is given', (
      tester,
    ) async {
      await pumpView(tester, profile: true);
      setProfileKeys({DnsOverrideKey.ipv6Timeout});
      await tester.pump();

      await tester.tap(find.text(currentAppLocalizations.ipv6Timeout));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '300');
      await tester.tap(find.text(currentAppLocalizations.submit));
      await tester.pumpAndSettle();

      expect(overrides().dns.ipv6Timeout, 300);
      expect(find.text('300'), findsOneWidget);
    });

    testWidgets('quick edit opens its section and applies it on exit', (
      tester,
    ) async {
      await pumpView(tester, profile: true);
      setProfileKeys({DnsOverrideKey.ipv6});
      await tester.pump();

      final editor = await openQuickEdit(tester);
      expect(editor.content, contains('ipv6: false'));
      expect(editor.readOnly, isFalse);
      expect(editor.onSave, isNull);

      final context = tester.element(find.byType(EditorPage));
      final popped = await editor.onPop!(context, 'DNS', 'listen: :53');
      expect(popped, isTrue);
      expect(overrides().dnsOverrideKeys, {DnsOverrideKey.listen});
      expect(overrides().dns.listen, ':53');
    });

    testWidgets('a quick edit naming an unknown key is refused', (
      tester,
    ) async {
      await pumpView(tester, profile: true);
      final editor = await openQuickEdit(tester);
      final context = tester.element(find.byType(EditorPage));

      final popped = editor.onPop!(context, 'DNS', 'bogus: 1');
      await tester.pump();
      expect(
        find.textContaining(currentAppLocalizations.discardChanges),
        findsOneWidget,
      );
      await tester.tap(find.text(currentAppLocalizations.cancel));
      await tester.pump();
      expect(await popped, isFalse);
      expect(overrides().dnsOverrideKeys, isEmpty);
    });

    testWidgets(
      'a policy picked from the sheet preserves the editable baseline',
      (tester) async {
        await pumpView(tester, profile: true);
        final appConfig = config();
        final original = overrides();
        final baseline = xclashDefaultDns.nameserverPolicy;
        expect(baseline, hasLength(7));

        await openAddSheet(tester, profile: true);
        await tester.drag(
          inSheet(currentAppLocalizations.addSettingEntry),
          const Offset(0, -400),
        );
        await tester.pumpAndSettle();
        await tester.dragUntilVisible(
          find.text('Nameserver Policy'),
          find.byType(SelectionSheet<DnsOverrideKey>),
          const Offset(0, -200),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.text('Nameserver Policy'));
        await tester.pumpAndSettle();

        expect(overrides().dnsOverrideKeys, {DnsOverrideKey.nameserverPolicy});
        expect(overrides().dns.nameserverPolicy, baseline);
        final selected = original.copyWith(
          dnsOverrideKeys: {DnsOverrideKey.nameserverPolicy},
        );
        expect(overrides(), selected);
        expect(config(), appConfig);

        await tester.binding.handlePopRoute();
        await tester.pumpAndSettle();
        expect(find.byType(SelectionSheet<DnsOverrideKey>), findsNothing);
        await tester.tap(find.text('Nameserver Policy'));
        await tester.pumpAndSettle();
        expect(
          tester.widget<MapEditView>(find.byType(MapEditView)).entries,
          baseline,
        );
        await tester.tap(find.text('+.jsdelivr.net'));
        await tester.pumpAndSettle();
        expect(find.byType(FormRowTextField), findsNWidgets(2));
        final valueField = find.descendant(
          of: find.widgetWithText(FormRow, currentAppLocalizations.value),
          matching: find.byType(TextField),
        );
        expect(valueField, findsOneWidget);
        expect(
          tester.widget<TextField>(valueField).controller!.text,
          baseline['+.jsdelivr.net'],
        );
        const resolver = 'https://example.com/dns-query';
        await tester.enterText(valueField, resolver);
        final confirm = find.descendant(
          of: find.byType(FormPage),
          matching: find.widgetWithText(
            TextButton,
            currentAppLocalizations.confirm,
          ),
        );
        expect(confirm, findsOneWidget);
        expect(tester.widget<TextButton>(confirm).onPressed, isNotNull);
        await tester.tap(confirm);
        await tester.pumpAndSettle();
        expect(overrides(), selected);
        await tester.binding.handlePopRoute();
        await tester.pumpAndSettle();
        final edited = {...baseline, '+.jsdelivr.net': resolver};
        expect(overrides(), selected.copyWith.dns(nameserverPolicy: edited));
        expect(config(), appConfig);
        await tester.tap(find.text('Nameserver Policy'));
        await tester.pumpAndSettle();
        expect(
          tester.widget<MapEditView>(find.byType(MapEditView)).entries,
          edited,
        );
        await tester.binding.handlePopRoute();
        await tester.pumpAndSettle();
        expect(overrides(), selected.copyWith.dns(nameserverPolicy: edited));
        expect(config(), appConfig);
      },
    );
  });
}
