import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/widgets/card.dart';
import 'package:fl_clash/views/dashboard/widgets/profiles.dart';
import 'package:fl_clash/views/dashboard/widgets/profile_detail.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

class Updating extends ProfilesAction {
  Profile? received;
  @override
  Future<void> updateProfile(
    Profile profile, {
    bool showLoading = false,
    bool Function()? isCurrent,
  }) async {
    received = profile;
  }
}

void main() {
  late ProviderContainer c;
  late Profile initial;
  setUp(() {
    initial = const Profile(
      id: 1,
      type: ProfileType.url,
      label: 'One',
      url: 'https://profile.example',
      autoUpdateDuration: Duration.zero,
    );
    c = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(() => TestProfiles([initial])),
        currentProfileIdProvider.overrideWithBuild((_, _) => 1),
        profilesActionProvider.overrideWith(Updating.new),
        viewSizeProvider.overrideWithBuild((_, _) => const Size(1200, 1000)),
      ],
    );
    globalState.container = c;
    c.read(appliedConfigCountsProvider.notifier).publish((
      groups: 1,
      proxies: 2,
      rules: 3,
    ));
  });
  tearDown(() => c.dispose());
  Future<void> mount(WidgetTester tester, Widget child) async {
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: c,
        child: TestApp(
          child: DashboardDensityScope(
            density: DashboardDensity.mobile,
            scale: 1,
            child: Scaffold(
              body: SizedBox(width: 360, height: 700, child: child),
            ),
          ),
        ),
      ),
    );
    await tester.pump();
  }

  Future<void> unmount(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
    c.dispose();
    await tester.pump();
  }

  void uiEdit() => c
      .read(profilesProvider.notifier)
      .put(
        initial.copyWith(
          currentGroupName: 'new-group',
          unfoldSet: {'new-fold'},
          selectedMap: {'Proxy': 'new-node'},
        ),
      );
  testWidgets(
    'profile card ignores unrelated UI selection fields and sync reads the latest full row',
    (tester) async {
      await mount(tester, const ProfilesCard());
      final card = find.descendant(
        of: find.byType(ProfilesCard),
        matching: find.byType(DashboardCard),
      );
      final before = tester.widget(card);
      uiEdit();
      await tester.pump();
      expect(tester.widget(card), same(before));
      await tester.tap(find.byTooltip('Update'));
      await tester.pump();
      final received =
          (c.read(profilesActionProvider.notifier) as Updating).received!;
      expect(received.currentGroupName, 'new-group');
      expect(received.unfoldSet, {'new-fold'});
      expect(received.selectedMap, {'Proxy': 'new-node'});
      final latest = c.read(currentProfileProvider)!;
      c
          .read(profilesProvider.notifier)
          .put(
            latest.copyWith(
              label: 'Renamed',
              subscriptionInfo: const SubscriptionInfo(
                upload: 1,
                download: 2,
                total: 10,
              ),
            ),
          );
      await tester.pump();
      expect(tester.widget(card), isNot(same(before)));
      expect(find.text('Renamed', findRichText: true), findsOneWidget);
      await unmount(tester);
    },
  );
  testWidgets(
    'profile details ignore UI selection fields and still respond to displayed fields',
    (tester) async {
      await mount(tester, const ProfileDetailSheet());
      final scaffold = find.descendant(
        of: find.byType(ProfileDetailSheet),
        matching: find.byType(CommonScaffold),
      );
      final before = tester.widget(scaffold);
      uiEdit();
      await tester.pump();
      expect(tester.widget(scaffold), same(before));
      c
          .read(profilesProvider.notifier)
          .put(
            c
                .read(currentProfileProvider)!
                .copyWith(
                  extendType: ExtendType.script,
                  label: 'Details renamed',
                ),
          );
      await tester.pump();
      expect(tester.widget(scaffold), isNot(same(before)));
      expect(find.text('Details renamed', findRichText: true), findsOneWidget);
      await unmount(tester);
    },
  );
}
