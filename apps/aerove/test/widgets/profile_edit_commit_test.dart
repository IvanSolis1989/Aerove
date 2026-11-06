import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import 'dart:convert';
import 'package:drift/native.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/core/method.dart';
import 'package:fl_clash/database/database.dart' as db;
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/profiles/edit.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import '../helpers/app_paths.dart';
import '../helpers/test_app.dart';

class _NoApply extends SetupAction {
  @override
  void autoApplyProfile() {}
}

class _ObservedEdit extends ProfilesAction {
  final saved = Completer<void>();
  @override
  Future<Profile> saveProfileEdit(
    Profile profile, {
    Uint8List? fileData,
  }) async {
    try {
      return await super.saveProfileEdit(profile, fileData: fileData);
    } finally {
      if (!saved.isCompleted) saved.complete();
    }
  }
}

class _EditCore extends CoreHandlerInterface {
  @override
  Future<String> validateConfig(String path) async {
    await File(path).writeAsString('mode: rule\nrules: ["MATCH,DIRECT"]\n');
    return '';
  }

  @override
  Future<T?> invokeMethod<T>({
    required CoreMethod method,
    Object? arguments,
    Duration? timeout,
  }) => throw StateError('Unexpected editor fixture RPC');
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _EditorReturn extends NavigatorObserver {
  bool returnEditedBytes = false;
  int editPops = 0;
  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) {
    if (returnEditedBytes) {
      returnEditedBytes = false;
      scheduleMicrotask(
        () => navigator!.pop<Uint8List>(
          utf8.encode('mode: direct\nrules: ["MATCH,DIRECT"]\n'),
        ),
      );
    }
  }

  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) {
    if (route.settings.name == 'profile-edit') editPops++;
  }
}

void main() {
  for (final fileEdit in [false, true]) {
    testWidgets(
      'rejected ${fileEdit ? 'file' : 'metadata'} edit retains both source files and its route',
      (tester) async {
        final home = await Directory.systemTemp.createTemp(
          'profile-edit-commit-',
        );
        await useAppPaths(home);
        final sql = db.Database(NativeDatabase.memory());
        final previous = db.database;
        db.database = sql;
        const profile = Profile(
          id: 41,
          label: 'Original',
          autoUpdateDuration: Duration.zero,
        );
        await sql.profiles.put(profile.toCompanion());
        final yaml = await profile.file;
        await yaml.writeAsString('old normalized source');
        final input = File(
          BackupEntries.resolve(
            await appPath.homeDirPath,
            BackupEntries.profileInput(41),
          ),
        );
        await input.writeAsString('old archived input');
        final action = _ObservedEdit();
        final container = ProviderContainer(
          overrides: [
            profilesActionProvider.overrideWith(() => action),
            setupActionProvider.overrideWith(() => _NoApply()),
            coreHandlerProvider.overrideWithValue(
              CoreController.scoped(_EditCore()),
            ),
          ],
        );
        globalState.container = container;
        final hold = container.listen(profilesProvider, (_, _) {});
        await container.read(profilesStreamProvider.future);
        final observer = _EditorReturn();
        try {
          await tester.pumpWidget(
            UncontrolledProviderScope(
              container: container,
              child: TestApp(
                child: Navigator(
                  observers: [observer],
                  onGenerateRoute: (_) => MaterialPageRoute<void>(
                    builder: (context) => TextButton(
                      onPressed: () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          settings: const RouteSettings(name: 'profile-edit'),
                          builder: (_) =>
                              const EditProfileView(profile: profile),
                        ),
                      ),
                      child: const Text('open editor'),
                    ),
                  ),
                ),
              ),
            ),
          );
          await tester.tap(find.text('open editor'));
          await tester.pumpAndSettle();
          await tester.runAsync(
            () => Future<void>.delayed(const Duration(milliseconds: 20)),
          );
          await tester.pumpAndSettle();
          if (fileEdit) {
            observer.returnEditedBytes = true;
            final row = find.widgetWithText(
              DecorationListItem,
              currentAppLocalizations.profile,
            );
            expect(row, findsOneWidget);
            await tester.ensureVisible(row);
            await tester.tap(row);
            await tester.pumpAndSettle();
          } else {
            await tester.enterText(
              find.byWidgetPredicate(
                (widget) =>
                    widget is TextField &&
                    widget.controller?.text == 'Original',
              ),
              'Changed',
            );
          }
          await sql.customStatement(
            'CREATE TRIGGER reject_profile_edit BEFORE INSERT ON profiles '
            "BEGIN SELECT RAISE(ABORT, 'fixture rejection'); END",
          );
          await tester.tap(find.byTooltip(currentAppLocalizations.save));
          await tester.pump();
          await tester.runAsync(
            () => action.saved.future.timeout(const Duration(seconds: 10)),
          );
          await tester.pumpAndSettle();
          expect(find.byType(EditProfileView), findsOneWidget);
          expect(observer.editPops, 0);
          expect((await sql.profilesDao.get(41))!.label, 'Original');
          expect(await yaml.readAsString(), 'old normalized source');
          expect(await input.readAsString(), 'old archived input');
          expect(tester.takeException(), isNull);
        } finally {
          await tester.pumpWidget(const SizedBox());
          hold.close();
          container.dispose();
          db.database = previous;
          await sql.close();
          await home.delete(recursive: true);
        }
      },
    );
  }
}
