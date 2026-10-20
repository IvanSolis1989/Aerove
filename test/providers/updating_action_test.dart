import 'package:fake_async/fake_async.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/providers/action.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:riverpod/riverpod.dart';

void main() {
  test('sweeps only while a Core update is active', () {
    fakeAsync((async) {
      final container = ProviderContainer();
      container.read(updatingActionProvider.notifier);
      final updates = container.read(updatingKeysProvider.notifier);

      updates.start('local-download');
      expect(async.pendingTimers, isEmpty);

      final coreOperation = updates.start(
        'core-update',
        scope: UpdatingScope.core,
      );
      expect(async.pendingTimers, hasLength(1));

      final secondCoreOperation = updates.start(
        'second-core-update',
        scope: UpdatingScope.core,
      );
      updates.stop('second-core-update', secondCoreOperation);
      expect(async.pendingTimers, hasLength(1));

      updates.stop('core-update', coreOperation);
      expect(container.read(updatingKeysProvider), {'local-download'});
      expect(async.pendingTimers, isEmpty);

      updates.start('core-update', scope: UpdatingScope.core);
      expect(async.pendingTimers, hasLength(1));

      async.elapse(updatingStaleTimeout - updatingSweepInterval);
      expect(container.read(updatingKeysProvider), {
        'local-download',
        'core-update',
      });
      expect(async.pendingTimers, hasLength(1));

      async.elapse(updatingSweepInterval);
      expect(container.read(updatingKeysProvider), {'local-download'});
      expect(async.pendingTimers, isEmpty);

      container.dispose();
      expect(async.pendingTimers, isEmpty);
    });
  });
}
