import 'package:fl_clash/product.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/common/maintenance_visibility.dart';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:flutter/widgets.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const core = String.fromEnvironment('DTO_CORE', defaultValue: 'meow-rs');
  ClientProduct.configure(
    product: ProductSpec(
      id: 'dto-fixture',
      displayName: 'DTO fixture',
      expectedCoreId: core,
      minimumAndroidApi: 33,
      resources: ProductResources(
        defaultScriptAsset: 'fixture',
        defaultScriptSha256: 'fixture',
        defaultScriptUrl: 'https://example.invalid/fixture',
        legacyScriptUrls: {},
        iconAsset: 'fixture',
      ),
      releaseFeed: 'https://example.invalid/fixture',
      releaseTagPrefix: 'fixture',
      releaseAssetPattern: 'fixture',
      changelogPath: 'fixture',
      coreSourceUrl: 'https://example.invalid/fixture',
    ),
    backendCoreId: core,
    backendFactory: () =>
        throw StateError('DTO test must not create a Core backend'),
  );
  test(
    'existing visible sweep dispatches automatic only to Lite and respects pause',
    () async {
      final protocol = _RecordingProtocol();
      final container = ProviderContainer(
        overrides: [
          currentProfileProvider.overrideWith((ref) => null),
          coreHandlerProvider.overrideWithValue(
            CoreController.scoped(protocol),
          ),
          coreStatusProvider.overrideWithBuild(
            (ref, notifier) => CoreStatus.connected,
          ),
        ],
      );
      try {
        expect(container.read(patchClashConfigProvider).geoAutoUpdate, false);
        final action = container.read(resourcesActionProvider.notifier);
        final visibility = container.read(maintenanceVisibleProvider.notifier);
        visibility.setLifecycle(AppLifecycleState.paused);
        await action.autoUpdate();
        expect(protocol.calls, isEmpty);
        visibility.setLifecycle(AppLifecycleState.resumed);
        await action.autoUpdate();
        expect(protocol.calls, core == 'meow-rs' ? ['automatic'] : isEmpty);
        visibility.setLifecycle(AppLifecycleState.paused);
        await action.autoUpdate();
        expect(protocol.calls.length, core == 'meow-rs' ? 1 : 0);
      } finally {
        container.dispose();
      }
    },
  );
}

// Protocol recorder only: this test creates no Native/Core instance or download.
class _RecordingProtocol implements CoreHandlerInterface {
  final calls = <String>[];
  @override
  Future<MeowGeoReply> updateGeoPrepared(MeowGeoRequest request) async {
    expect(request, isA<MeowGeoAdmissionRequest>());
    calls.add('automatic');
    return MeowGeoReply(
      schemaVersion: 1,
      transactionId: request.transactionId,
      state: 'skipped',
      active: false,
    );
  }

  @override
  Future<String> updateGeoData(String type) async {
    calls.add(type);
    return '';
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw StateError('Unexpected protocol call: ${invocation.memberName}');
}
