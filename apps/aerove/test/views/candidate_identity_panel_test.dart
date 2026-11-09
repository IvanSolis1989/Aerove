import 'dart:convert';

import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/about.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void main() {
  const channel = MethodChannel('com.follow.clash/app');
  final identity = jsonEncode({
    'schema': 1,
    'product': 'aerove-lite',
    'source': 'a' * 40,
    'tree': 'b' * 40,
    'runId': '123',
    'runNumber': '350',
    'attempt': '2',
    'abi': 'arm64-v8a',
  });
  for (final brightness in Brightness.values) {
    testWidgets('390px $brightness identifies and copies original build', (
      tester,
    ) async {
      final messenger = tester.binding.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(channel, (_) async => identity);
      String? copied;
      messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.setData')
          copied = (call.arguments as Map)['text'] as String;
        return null;
      });
      addTearDown(() {
        messenger.setMockMethodCallHandler(channel, null);
        messenger.setMockMethodCallHandler(SystemChannels.platform, null);
      });
      await tester.binding.setSurfaceSize(const Size(390, 400));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(
        TestApp(
          includeNavigatorKey: false,
          theme: ThemeData(brightness: brightness),
          child: const Scaffold(body: CandidateIdentityPanel()),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        find.text(
          '${AppLocalizations.current.preview} · #350.2 · aaaaaaaa · arm64-v8a',
        ),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
      await tester.tap(find.byType(TonalButton));
      await tester.pump();
      expect(copied, contains('/actions/runs/123'));
      expect(copied, contains('a' * 40));
    });
  }
  testWidgets('missing identity is unknown and has no misleading copy action', (
    tester,
  ) async {
    final messenger = tester.binding.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (_) async => null);
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
    await tester.pumpWidget(
      const TestApp(
        includeNavigatorKey: false,
        child: Scaffold(body: CandidateIdentityPanel()),
      ),
    );
    await tester.pumpAndSettle();
    expect(
      find.text(
        '${AppLocalizations.current.preview} · ${AppLocalizations.current.unknown}',
      ),
      findsOneWidget,
    );
    expect(find.byType(TonalButton), findsNothing);
  });
}
