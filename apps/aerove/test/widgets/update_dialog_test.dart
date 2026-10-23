import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:fl_clash/common/request.dart';
import 'package:fl_clash/providers/action.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/dialog.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../helpers/preferences.dart';
import '../helpers/test_app.dart';

const _runningVersion = '0.8.95';

const _payload =
    '{"schemaVersion":2,"versions":[{"version":"0.8.96","tag":"v0.8.96",'
    '"date":"2026-08-16","prerelease":false,"groups":['
    '{"type":"breaking","entries":[{"id":"af20769","text":'
    '"Re-import backups"}]},'
    '{"type":"feat","entries":[{"id":"1a2b3c4","text":'
    '"Override scripts"}]}]}]}';

const _bulletsOnly =
    '<!-- flclash:changelog:begin -->\n'
    '- Override scripts\n'
    '<!-- flclash:changelog:end -->\n';

String _bodyWith(String payload) =>
    '$_bulletsOnly\n<!-- flclash:changelog:json\n$payload\n-->\n';

Map<String, dynamic> release(String? body) => <String, dynamic>{
  'tag_name': 'v0.8.96',
  'body': body,
};

class _LatestRelease implements HttpClientAdapter {
  String tag = 'v0.8.96';

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    return ResponseBody.fromString(
      jsonEncode([
        {
          'tag_name': tag,
          'body': _bulletsOnly,
          'assets': [
            {
              'name': 'Aerove-android-arm64.apk',
              'browser_download_url':
                  'https://github.com/IvanSolis1989/Aerove/releases/download/$tag/Aerove-android-arm64.apk',
            },
          ],
        },
      ]),
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

Future<ProviderContainer> pumpApp(
  WidgetTester tester, {
  Locale? locale,
  bool withStatusManager = false,
}) async {
  final container = ProviderContainer();
  addTearDown(container.dispose);
  globalState.container = container;
  // appSettingProvider is autoDispose; in the app `configProvider` keeps it
  // alive, so the test has to hold a listener or edits are dropped.
  container.listen(appSettingProvider, (_, _) {}, fireImmediately: true);
  container
      .read(viewSizeProvider.notifier)
      .update((_) => const Size(1200, 800));

  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: TestApp(
        locale: locale,
        withStatusManager: withStatusManager,
        child: const Scaffold(body: SizedBox.shrink()),
      ),
    ),
  );
  await tester.pump();
  return container;
}

/// The dialog blocks until the user answers, so tests must close it before
/// awaiting the call that opened it.
Future<void> tapCancel(WidgetTester tester) async {
  await tester.tap(find.byType(TextButton).first);
  await tester.pumpAndSettle();
}

void main() {
  setUpAll(() {
    globalState.packageInfo = PackageInfo(
      appName: 'FlClash',
      packageName: 'com.follow.clash',
      version: _runningVersion,
      buildNumber: '1',
    );
  });

  testWidgets('renders the grouped notes carried by the release', (
    tester,
  ) async {
    final container = await pumpApp(tester);

    final shown = container
        .read(commonActionProvider.notifier)
        .checkUpdateResultHandle(data: release(_bodyWith(_payload)));
    await tester.pumpAndSettle();

    expect(find.textContaining('v0.8.96'), findsOneWidget);
    expect(find.textContaining('Re-import backups'), findsOneWidget);
    expect(find.textContaining('Override scripts'), findsOneWidget);
    expect(find.textContaining('Breaking changes'), findsOneWidget);

    await tapCancel(tester);
    await shown;
  });

  testWidgets('localizes the group titles but not the entries', (tester) async {
    final container = await pumpApp(tester, locale: const Locale('zh', 'CN'));

    final shown = container
        .read(commonActionProvider.notifier)
        .checkUpdateResultHandle(data: release(_bodyWith(_payload)));
    await tester.pumpAndSettle();

    // Entry copy comes from the release payload and is English only; the group
    // headings still follow the app locale.
    expect(find.textContaining('新功能'), findsOneWidget);
    expect(find.textContaining('Override scripts'), findsOneWidget);

    await tapCancel(tester);
    await shown;
  });

  testWidgets('falls back to the bullets of a release without a payload', (
    tester,
  ) async {
    final container = await pumpApp(tester);

    final shown = container
        .read(commonActionProvider.notifier)
        .checkUpdateResultHandle(data: release(_bulletsOnly));
    await tester.pumpAndSettle();

    expect(find.textContaining('- Override scripts'), findsOneWidget);

    await tapCancel(tester);
    await shown;
  });

  testWidgets('shows only the requested version from a multi-version payload', (
    tester,
  ) async {
    final container = await pumpApp(tester);
    final payload = jsonDecode(_payload) as Map<String, dynamic>;
    (payload['versions'] as List<dynamic>).insert(0, {
      'version': '0.8.95',
      'tag': 'v0.8.95',
      'groups': [
        {
          'type': 'fix',
          'entries': [
            {'id': 'old', 'text': 'Wrong previous version notes'},
          ],
        },
      ],
    });
    final shown = container
        .read(commonActionProvider.notifier)
        .checkUpdateResultHandle(data: release(_bodyWith(jsonEncode(payload))));
    await tester.pumpAndSettle();
    expect(find.textContaining('Re-import backups'), findsOneWidget);
    expect(find.textContaining('Wrong previous version notes'), findsNothing);
    await tapCancel(tester);
    await shown;
  });

  testWidgets(
    'missing or mismatched notes are explicit and keep download available',
    (tester) async {
      final container = await pumpApp(tester);
      for (final body in [
        null,
        '# Aerove Android\n版本和签名模板',
        _bodyWith(_payload.replaceAll('0.8.96', '0.8.95')),
      ]) {
        final shown = container
            .read(commonActionProvider.notifier)
            .checkUpdateResultHandle(data: release(body), isUser: true);
        await tester.pumpAndSettle();
        expect(
          find.textContaining(
            'Release notes for this version are not available yet.',
          ),
          findsOneWidget,
        );
        expect(find.text('Download'), findsOneWidget);
        expect(find.textContaining('Re-import backups'), findsNothing);
        await tapCancel(tester);
        await shown;
      }
    },
  );

  testWidgets(
    'long target notes scroll while confirmation actions stay visible',
    (tester) async {
      tester.view.physicalSize = const Size(400, 600);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final container = await pumpApp(tester);
      final lines = List.generate(
        100,
        (index) => '- Target change $index',
      ).join('\n');
      final shown = container
          .read(commonActionProvider.notifier)
          .checkUpdateResultHandle(
            data: release('### Changes\n\n$lines'),
            isUser: true,
          );
      await tester.pumpAndSettle();
      final scrollView = find
          .descendant(
            of: find.byType(CommonDialog),
            matching: find.byType(SingleChildScrollView),
          )
          .first;
      final scrollable = tester.state<ScrollableState>(
        find
            .descendant(of: scrollView, matching: find.byType(Scrollable))
            .first,
      );
      expect(scrollable.position.maxScrollExtent, greaterThan(0));
      await tester.drag(scrollView, const Offset(0, -250));
      await tester.pumpAndSettle();
      expect(scrollable.position.pixels, greaterThan(0));
      expect(find.text('Download').hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tapCancel(tester);
      await shown;
    },
  );

  testWidgets('stops reminding when an automatic check is dismissed', (
    tester,
  ) async {
    final container = await pumpApp(tester);

    final shown = container
        .read(commonActionProvider.notifier)
        .checkUpdateResultHandle(data: release(_bodyWith(_payload)));
    await tester.pumpAndSettle();
    await tapCancel(tester);
    await shown;

    expect(container.read(appSettingProvider).autoCheckUpdate, isFalse);
  });

  testWidgets('a manual check keeps the setting and reports a failure', (
    tester,
  ) async {
    final container = await pumpApp(tester);

    final shown = container
        .read(commonActionProvider.notifier)
        .checkUpdateResultHandle(isUser: true);
    await tester.pumpAndSettle();

    expect(find.text('Check for updates'), findsOneWidget);

    await tapCancel(tester);
    await shown;
    expect(container.read(appSettingProvider).autoCheckUpdate, isTrue);
  });

  testWidgets(
    'duplicate checks share one dialog and cancellation permits retry',
    (tester) async {
      final calls = <MethodCall>[];
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      for (final name in [
        'plugins.flutter.io/url_launcher',
        'com.follow.clash/app',
      ]) {
        messenger.setMockMethodCallHandler(MethodChannel(name), (call) async {
          calls.add(call);
          return true;
        });
        addTearDown(
          () => messenger.setMockMethodCallHandler(MethodChannel(name), null),
        );
      }
      final container = await pumpApp(tester);
      final action = container.read(commonActionProvider.notifier);
      final shown = action.checkUpdateResultHandle(
        data: release(_bulletsOnly),
        isUser: true,
      );
      await tester.pumpAndSettle();
      await action.checkUpdateResultHandle(
        data: release(_bulletsOnly),
        isUser: true,
      );
      await tester.pumpAndSettle();
      expect(find.textContaining('v0.8.96'), findsOneWidget);
      await tapCancel(tester);
      await shown;
      expect(calls, isEmpty);
      expect(container.read(appSettingProvider).autoCheckUpdate, isTrue);

      final retry = action.checkUpdateResultHandle(
        data: release(_bulletsOnly),
        isUser: true,
      );
      await tester.pumpAndSettle();
      expect(find.textContaining('v0.8.96'), findsOneWidget);
      await tapCancel(tester);
      await retry;
    },
  );

  testWidgets('download launch failure is reported and the action can retry', (
    tester,
  ) async {
    const channel = MethodChannel('plugins.flutter.io/url_launcher');
    final launches = <MethodCall>[];
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async {
      launches.add(call);
      return false;
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
    final container = await pumpApp(tester, withStatusManager: true);
    final action = container.read(commonActionProvider.notifier);
    final shown = action.checkUpdateResultHandle(
      data: release(_bulletsOnly),
      isUser: true,
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Download'));
    await tester.pumpAndSettle();
    await shown;
    expect(
      launches.single.arguments['url'],
      'https://github.com/IvanSolis1989/Aerove/releases/tag/v0.8.96',
    );
    expect(
      find.text('Could not start the system download. Please try again.'),
      findsOneWidget,
    );

    final retry = action.checkUpdateResultHandle(
      data: release(_bulletsOnly),
      isUser: true,
    );
    await tester.pumpAndSettle();
    expect(find.textContaining('v0.8.96'), findsOneWidget);
    await tapCancel(tester);
    await retry;
  });

  group('automatic checks', () {
    late HttpClientAdapter adapter;
    late _LatestRelease latest;

    setUp(() async {
      await mockPreferences();
      adapter = request.dio.httpClientAdapter;
      latest = _LatestRelease();
      request.dio.httpClientAdapter = latest;
    });

    tearDown(() => request.dio.httpClientAdapter = adapter);

    Future<void> dismiss(WidgetTester tester) async {
      Navigator.of(tester.element(find.byType(CommonDialog))).pop();
      await tester.pumpAndSettle();
    }

    testWidgets('prompt each release once while a manual check still shows', (
      tester,
    ) async {
      final container = await pumpApp(tester);
      final action = container.read(commonActionProvider.notifier);

      final first = action.autoCheckUpdate();
      await tester.pumpAndSettle();
      expect(find.textContaining('v0.8.96'), findsOneWidget);
      await dismiss(tester);
      expect(await first, isTrue);

      final reopened = action.autoCheckUpdate();
      await tester.pumpAndSettle();
      expect(find.byType(CommonDialog), findsNothing);
      expect(await reopened, isTrue);

      final manual = action.checkUpdateResultHandle(
        data: release(_bulletsOnly),
        isUser: true,
      );
      await tester.pumpAndSettle();
      expect(find.textContaining('v0.8.96'), findsOneWidget);
      await tapCancel(tester);
      await manual;

      latest.tag = 'v0.8.97';
      final next = action.autoCheckUpdate();
      await tester.pumpAndSettle();
      expect(find.textContaining('v0.8.97'), findsOneWidget);
      await dismiss(tester);
      await next;
      expect(container.read(appSettingProvider).autoCheckUpdate, isTrue);
    });
  });
}
