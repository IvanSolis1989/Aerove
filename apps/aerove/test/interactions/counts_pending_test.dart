import 'dart:async';
import 'dart:io';
import 'package:fl_clash/common/path.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import '../helpers/app_paths.dart';
import '../helpers/test_app.dart';
import 'counts_null_test.dart' show Counts;

void main() {
  testWidgets(
    'an apply receipt invalidates a file fallback waiting for its path',
    (tester) async {
      final home = Directory.systemTemp.createTempSync('counts-pending-v2-');
      addTearDown(() => home.deleteSync(recursive: true));
      await useAppPaths(home);
      await tester.runAsync(() async {
        await File(
          await appPath.configFilePath,
        ).writeAsString('rules:\n  - MATCH,DIRECT\n');
      });
      globalState.lastConfigMd5 = 'pending-v2';
      addTearDown(() => globalState.lastConfigMd5 = null);
      final path = Completer<Directory>();
      AppPath.supportDirectory = () => path.future;
      final rebound = appPath.rebind();
      final c = ProviderContainer();
      c.read(initProvider.notifier).value = true;
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: c,
          child: const TestApp(child: Scaffold(body: Counts())),
        ),
      );
      expect(path.isCompleted, isFalse);
      expect(find.text('-1/false'), findsOneWidget);
      c.read(appliedConfigCountsProvider.notifier).publish(null);
      path.complete(home);
      await tester.pump();
      await tester.runAsync(() async {
        await rebound;
        await Future<void>.delayed(const Duration(milliseconds: 200));
      });
      await tester.pump();
      expect(find.text('-1/false'), findsOneWidget);
      expect(find.text('1/false'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
      c.dispose();
      await tester.pump();
    },
  );
}
