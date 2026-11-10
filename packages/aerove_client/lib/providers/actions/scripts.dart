part of '../action.dart';

@Riverpod(keepAlive: true)
class ScriptsAction extends _$ScriptsAction {
  final _activeUpdates =
      <int, ({bool Function() current, CancelToken cancellation})>{};

  @override
  void build() {
    ref.onDispose(() {
      for (final operation in _activeUpdates.values)
        operation.cancellation.cancel();
    });
    void cancelStale() {
      for (final entry in _activeUpdates.entries) {
        if (!entry.value.current()) entry.value.cancellation.cancel();
      }
    }

    ref.listen(scriptsProvider, (_, _) => cancelStale());
    ref.listen(maintenanceVisibleProvider, (_, _) => cancelStale());
  }

  void putScript(Script script) {
    ref.read(scriptsProvider.notifier).put(script);
    _applyIfInUse(script.id);
  }

  Future<void> updateScript(Script script, {bool Function()? isCurrent}) async {
    checkUpdateCurrent(isCurrent);
    await ref.read(scriptsProvider.future);
    checkUpdateCurrent(() => ref.mounted && (isCurrent?.call() ?? true));
    final baseline = ref.read(scriptsProvider).value?.get(script.id);
    if (baseline != script) throw const UpdateCancelledException();
    bool current() =>
        ref.mounted &&
        (isCurrent?.call() ?? true) &&
        ref.read(scriptsProvider).value?.get(script.id) == baseline;
    final cancellation = CancelToken();
    _activeUpdates[script.id] = (current: current, cancellation: cancellation);
    final updatingKeys = ref.read(updatingKeysProvider.notifier);
    final operation = updatingKeys.start(script.updatingKey);
    try {
      final updated = await script.update(
        isCurrent: current,
        cancelToken: cancellation,
      );
      checkUpdateCurrent(current);
      if (isCurrent == null) {
        putScript(updated);
      } else {
        ref.read(scriptsProvider.notifier).put(updated);
        final profile = ref.read(currentProfileProvider);
        if (profile?.extendType == ExtendType.script &&
            profile?.scriptId == script.id) {
          await ref
              .read(setupActionProvider.notifier)
              .applyProfile(silence: true);
        }
      }
    } finally {
      if (identical(_activeUpdates[script.id]?.cancellation, cancellation))
        _activeUpdates.remove(script.id);
      updatingKeys.stop(script.updatingKey, operation);
    }
  }

  void _applyIfInUse(int scriptId) {
    final profile = ref.read(currentProfileProvider);
    if (profile?.extendType != ExtendType.script ||
        profile?.scriptId != scriptId) {
      return;
    }
    ref.read(setupActionProvider.notifier).applyProfileDebounce(silence: true);
  }
}
