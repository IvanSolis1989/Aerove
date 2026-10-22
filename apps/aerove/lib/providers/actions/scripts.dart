part of '../action.dart';

@Riverpod(keepAlive: true)
class ScriptsAction extends _$ScriptsAction {
  @override
  void build() {}

  void putScript(Script script) {
    ref.read(scriptsProvider.notifier).put(script);
    _applyIfInUse(script.id);
  }

  Future<void> updateScript(Script script, {bool Function()? isCurrent}) async {
    checkUpdateCurrent(isCurrent);
    final updatingKeys = ref.read(updatingKeysProvider.notifier);
    final operation = updatingKeys.start(script.updatingKey);
    try {
      final updated = await script.update(isCurrent: isCurrent);
      checkUpdateCurrent(isCurrent);
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
