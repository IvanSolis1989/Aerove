import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/profiles/profiles.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

class _CancellationAction extends ProfilesAction {
  final cancelled = <int>[];

  @override
  Future<void> cancelProfileUpdate(int id) async {
    cancelled.add(id);
  }

  @override
  Future<void> cancelProfileUpdates(Iterable<int> ids) async {
    cancelled.addAll(ids);
  }
}

void main() {
  Future<ProviderContainer> host(
    WidgetTester tester,
    List<Profile> profiles,
    Widget child,
  ) async {
    tester.view.physicalSize = const Size(1200, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(() => TestProfiles(profiles)),
        profilesActionProvider.overrideWith(_CancellationAction.new),
        currentProfileIdProvider.overrideWithBuild((_, _) => null),
      ],
    );
    addTearDown(container.dispose);
    globalState.container = container;
    container.read(viewSizeProvider.notifier).value = const Size(1200, 1000);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(child: child),
      ),
    );
    await tester.pump();
    return container;
  }

  testWidgets(
    'batch action changes from update to cancel while work is owned',
    (tester) async {
      final profiles = [
        Profile.normal(label: 'one', url: 'https://example.com/one'),
        Profile.normal(label: 'two', url: 'https://example.com/two'),
      ];
      final container = await host(tester, profiles, const ProfilesView());
      final keys = container.read(updatingKeysProvider.notifier);
      final operations = [
        for (final profile in profiles) keys.start(profile.updatingKey),
      ];
      await tester.pump();
      expect(find.byTooltip(currentAppLocalizations.cancel), findsOneWidget);
      await tester.tap(find.byTooltip(currentAppLocalizations.cancel));
      await tester.pump();
      final action =
          container.read(profilesActionProvider.notifier)
              as _CancellationAction;
      expect(action.cancelled, profiles.map((profile) => profile.id));
      for (final (index, profile) in profiles.indexed) {
        keys.stop(profile.updatingKey, operations[index]);
      }
      await tester.pump();
      expect(find.byTooltip(currentAppLocalizations.update), findsOneWidget);
      expect(find.byTooltip(currentAppLocalizations.cancel), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('an updating row keeps an actionable cancel context menu', (
    tester,
  ) async {
    final profile = Profile.normal(label: 'remote', url: 'https://example.com');
    final container = await host(
      tester,
      [profile],
      Scaffold(
        body: ProfileItem(
          profile: profile,
          groupValue: null,
          onChanged: (_) {},
        ),
      ),
    );
    container.read(updatingKeysProvider.notifier).start(profile.updatingKey);
    await tester.pump();
    final region = tester.widget<ContextMenuRegion>(
      find.byType(ContextMenuRegion),
    );
    expect(region.menuItems, hasLength(1));
    expect(region.menuItems!.single.label, currentAppLocalizations.cancel);
    final tile = find.descendant(
      of: find.byType(ProfileItem),
      matching: find.byType(ListTile),
    );
    expect(tile, findsOneWidget);
    await tester.longPress(tile);
    await tester.pump(const Duration(milliseconds: 400));
    await tester.tap(find.text(currentAppLocalizations.cancel));
    await tester.pump();
    final action =
        container.read(profilesActionProvider.notifier) as _CancellationAction;
    expect(action.cancelled, [profile.id]);
    expect(tester.takeException(), isNull);
  });
}
