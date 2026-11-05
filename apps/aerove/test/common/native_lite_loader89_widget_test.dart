import 'package:fl_clash/views/config/general.dart';
import 'package:fl_clash/product.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/state.dart';
import '../helpers/test_app.dart';
import 'package:material_ui/material_ui.dart';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const core = String.fromEnvironment('LOADER_CORE', defaultValue: 'meow-rs');
  ClientProduct.configure(
    product: ProductSpec(
      id: 'fixture',
      displayName: 'fixture',
      expectedCoreId: core,
      minimumAndroidApi: 33,
      resources: ProductResources(
        defaultScriptAsset: '',
        defaultScriptSha256: '',
        defaultScriptUrl: '',
        legacyScriptUrls: {},
        iconAsset: '',
      ),
      releaseFeed: '',
      releaseTagPrefix: '',
      releaseAssetPattern: '',
      changelogPath: '',
      coreSourceUrl: '',
    ),
    backendCoreId: core,
    backendFactory: () => throw StateError('must not start backend'),
  );
  testWidgets(
    'General loader row follows actual core capability without changing Standard',
    (tester) async {
      tester.view.physicalSize = const Size(1000, 3000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final container = ProviderContainer(
        overrides: buildConfigOverrides(
          Config.realFromJson(null, coreId: core),
        ),
      );
      globalState.container = container;
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const TestApp(child: GeneralView()),
        ),
      );
      await tester.pump(const Duration(milliseconds: 100));
      final loader = find.text(AppLocalizations.current.geodataLoader);
      expect(loader, core == 'mihomo' ? findsOneWidget : findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
}
