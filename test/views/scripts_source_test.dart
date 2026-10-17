import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/xclash_defaults.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/config/scripts.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';
import '../helpers/test_database_providers.dart';
import '../helpers/test_profiles.dart';

class _PendingScriptsAction extends ScriptsAction {
  final updated = <Script>[];
  final pending = <Completer<void>>[];

  @override
  Future<void> updateScript(Script script) async {
    updated.add(script);
    final completion = Completer<void>();
    pending.add(completion);
    final keys = ref.read(updatingKeysProvider.notifier);
    final operation = keys.start(script.updatingKey);
    try {
      await completion.future;
    } finally {
      keys.stop(script.updatingKey, operation);
    }
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final remote = Script(
    id: 123,
    label: 'Smart-Config-Kit',
    lastUpdateTime: DateTime(2026),
    url: xclashDefaultScriptUrl,
  );

  Future<ProviderContainer> pumpScripts(
    WidgetTester tester,
    List<Script> scripts, {
    _PendingScriptsAction? action,
  }) async {
    final container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(TestProfiles.new),
        scriptsProvider.overrideWith(() => TestScripts(scripts)),
        if (action != null) scriptsActionProvider.overrideWith(() => action),
      ],
    );
    addTearDown(container.dispose);
    globalState.container = container;
    container.read(viewSizeProvider.notifier).value = const Size(1200, 1000);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(child: ScriptsView()),
      ),
    );
    await tester.pumpAndSettle();
    return container;
  }

  Future<void> openMenu(WidgetTester tester) async {
    await tester.longPress(find.text('Smart-Config-Kit'));
    await tester.pumpAndSettle();
  }

  testWidgets('shows the current saved URL rather than a fixed recommended URL', (
    tester,
  ) async {
    const edited = 'https://example.com/user-edited-script.js';
    await pumpScripts(tester, [remote.copyWith(url: edited)]);
    expect(find.text(edited), findsOneWidget);
    expect(find.text(xclashDefaultScriptUrl), findsNothing);
  });

  testWidgets('source actions copy the saved URL and open its confirmation', (
    tester,
  ) async {
    String? copied;
    final messenger = tester.binding.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') {
        copied = (call.arguments as Map)['text'] as String;
      }
      return null;
    });
    addTearDown(
      () => messenger.setMockMethodCallHandler(SystemChannels.platform, null),
    );
    await pumpScripts(tester, [remote]);
    final l10n = currentAppLocalizations;
    expect(find.text(xclashDefaultScriptUrl), findsOneWidget);

    await openMenu(tester);
    await tester.tap(find.text(l10n.copyLink));
    await tester.pumpAndSettle();
    expect(copied, xclashDefaultScriptUrl);

    await openMenu(tester);
    await tester.tap(find.text(l10n.externalLink));
    await tester.pumpAndSettle();
    expect(find.byType(CommonDialog), findsOneWidget);
    expect(
      find.descendant(
        of: find.byType(CommonDialog),
        matching: find.text(xclashDefaultScriptUrl, findRichText: true),
      ),
      findsWidgets,
    );
    await tester.tap(find.text(l10n.cancel));
    await tester.pumpAndSettle();
    expect(find.byType(CommonDialog), findsNothing);
  });

  testWidgets('the existing URL editor submits the edited source for update', (
    tester,
  ) async {
    final action = _PendingScriptsAction();
    await pumpScripts(tester, [remote], action: action);
    final l10n = currentAppLocalizations;
    await openMenu(tester);
    await tester.tap(find.text(l10n.url));
    await tester.pumpAndSettle();
    final field = tester.widget<TextField>(find.byType(TextField));
    expect(field.controller?.text, xclashDefaultScriptUrl);
    const edited = 'https://example.com/replacement.js';
    await tester.enterText(find.byType(TextField), edited);
    await tester.tap(find.text(l10n.submit));
    await tester.pump();
    expect(action.updated.single.url, edited);
    action.pending.single.complete();
    await tester.pumpAndSettle();
  });

  testWidgets('updating disables the menu and a failure permits retry', (
    tester,
  ) async {
    final action = _PendingScriptsAction();
    final container = await pumpScripts(tester, [remote], action: action);
    final l10n = currentAppLocalizations;
    await openMenu(tester);
    await tester.tap(find.text(l10n.sync));
    await tester.pump();
    expect(container.read(isUpdatingProvider(remote.updatingKey)), isTrue);
    expect(find.byType(CommonCircleLoading), findsOneWidget);
    await tester.longPress(find.text('Smart-Config-Kit'));
    await tester.pump();
    expect(find.text(l10n.sync), findsNothing);
    expect(action.updated, [remote]);

    action.pending.single.completeError(StateError('download failed'));
    await tester.pumpAndSettle();
    expect(container.read(isUpdatingProvider(remote.updatingKey)), isFalse);
    expect(find.byType(CommonCircleLoading), findsNothing);
    expect(tester.takeException(), isNull);
    await openMenu(tester);
    await tester.tap(find.text(l10n.sync));
    await tester.pump();
    expect(action.updated, [remote, remote]);
    action.pending.last.complete();
    await tester.pumpAndSettle();
  });

  testWidgets('a removed default script is not recreated by opening the library', (
    tester,
  ) async {
    final container = await pumpScripts(tester, const []);
    expect(find.text('Smart-Config-Kit'), findsNothing);
    expect(find.text(xclashDefaultScriptUrl), findsNothing);
    expect(container.read(scriptsProvider).value, isEmpty);
    expect(tester.takeException(), isNull);
  });
}
