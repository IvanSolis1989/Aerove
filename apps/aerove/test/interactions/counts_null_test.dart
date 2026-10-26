import 'dart:io';
import 'package:fl_clash/common/path.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/dashboard/widgets/profile_detail.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import '../helpers/test_app.dart';
import '../helpers/app_paths.dart';

class Counts extends ConsumerStatefulWidget {
  const Counts({super.key});
  @override
  ConsumerState<Counts> createState() => CountsState();
}

class CountsState extends ConsumerState<Counts> with ConfigCountsWatch<Counts> {
  @override
  Widget build(BuildContext context) {
    watchConfigCounts();
    return Text('${configCounts?.rules ?? -1}/$configCountsFailed');
  }
}

void main() {
  setUpAll(() async {
    final home = Directory.systemTemp.createTempSync('counts-null-v2-');
    addTearDown(() => home.deleteSync(recursive: true));
    await useAppPaths(home);
    addTearDown(() => globalState.lastConfigMd5 = null);
    await File(await appPath.configFilePath).writeAsString(
      'rules:\n  - MATCH,DIRECT\n  - DOMAIN,a.com,DIRECT\n  - GEOIP,CN,DIRECT\n',
    );
    globalState.lastConfigMd5 = 'review-fallback-before-apply';
  });
  testWidgets('an apply-null receipt clears a previously completed file fallback', (
    tester,
  ) async {
    final c = ProviderContainer();
    c.read(initProvider.notifier).value = true;
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: c,
        child: const TestApp(child: Scaffold(body: Counts())),
      ),
    );
    for (var i = 0; i < 40 && find.text('3/false').evaluate().isEmpty; i++) {
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 25)),
      );
      await tester.pump();
    }
    expect(find.text('3/false'), findsOneWidget);
    expect(c.read(appliedConfigCountsProvider), isNull);
    expect(
      c.read(appliedConfigCountsProvider.notifier).allowsFileFallback,
      isTrue,
    );
    c.read(appliedConfigCountsProvider.notifier).publish(null);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    stdout.writeln(
      'after publish(null): provider=${c.read(appliedConfigCountsProvider)}, fallbackAllowed=${c.read(appliedConfigCountsProvider.notifier).allowsFileFallback}, stale3Visible=${find.text("3/false").evaluate().length}',
    );
    final stale = find.text('3/false').evaluate().isNotEmpty;
    await tester.pumpWidget(const SizedBox.shrink());
    c.dispose();
    expect(
      stale,
      isFalse,
      reason:
          'apply has cleared the authoritative counts, so file-fallback counts must be cleared too',
    );
  });
}
