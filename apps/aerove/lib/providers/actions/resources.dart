part of '../action.dart';

@Riverpod(keepAlive: true)
class ResourcesAction extends _$ResourcesAction {
  var _autoUpdating = false;
  final _automaticBackoff = AutomaticUpdateBackoff();

  void resetAutomaticBackoff() => _automaticBackoff.reset();

  @override
  void build() {}

  Future<void> updateIconSet(IconSet iconSet, {bool Function()? isCurrent}) {
    if (isCurrent == null) _automaticBackoff.reset(iconSet.updatingKey);
    return _track(
      iconSet.updatingKey,
      () => ref
          .read(iconSetsActionProvider.notifier)
          .sync(iconSet, isCurrent: isCurrent),
    );
  }

  Future<void> updateRuleProvider(
    ClashProvider provider, {
    bool Function()? isCurrent,
  }) {
    if (isCurrent == null) _automaticBackoff.reset(provider.updatingKey);
    return _track(
      provider.updatingKey,
      () => ref
          .read(clashProvidersActionProvider.notifier)
          .putProvider(
            provider,
            previous: provider,
            refresh: true,
            isCurrent: isCurrent,
          ),
    );
  }

  Future<void> updateScript(Script script, {bool Function()? isCurrent}) {
    if (isCurrent == null) _automaticBackoff.reset(script.updatingKey);
    return ref
        .read(scriptsActionProvider.notifier)
        .updateScript(script, isCurrent: isCurrent);
  }

  Future<void> _track(String key, Future<Object?> Function() task) async {
    final updatingKeys = ref.read(updatingKeysProvider.notifier);
    final operation = updatingKeys.start(key);
    try {
      await task();
    } finally {
      updatingKeys.stop(key, operation);
    }
  }

  /// A geo file fails here only when the Core turns the request down; a
  /// download that fails later reaches the user from [GeoResourceAction].
  Future<List<UpdatingMessage>> updateAll() async {
    resetAutomaticBackoff();
    final appLocalizations = currentAppLocalizations;
    final geoResourceAction = ref.read(geoResourceActionProvider.notifier);
    final failures = <UpdatingMessage>[];
    Future<void> update(
      String key,
      String label,
      Future<void> Function() task,
    ) async {
      if (ref.read(updatingKeysProvider).contains(key)) {
        return;
      }
      try {
        await task();
      } catch (e) {
        failures.add(
          UpdatingMessage(
            label: label,
            message: userFacingErrorMessage(e, appLocalizations),
          ),
        );
      }
    }

    final (iconSets, scripts, providers) = await (
      database.iconSetsDao.query().get(),
      database.scriptsDao.query().get(),
      database.clashProvidersDao.query().get(),
    ).wait;
    await Future.wait([
      for (final geoResource in GeoResource.values)
        update(
          geoResource.updatingKey,
          geoResource.name,
          () => geoResourceAction.updateGeoResource(
            geoResource,
            announceSuccess: false,
          ),
        ),
      for (final iconSet in iconSets)
        if (iconSet.isRemote)
          update(
            iconSet.updatingKey,
            iconSet.name,
            () => updateIconSet(iconSet),
          ),
      for (final provider in providers)
        if (provider.isRemote)
          update(
            provider.updatingKey,
            provider.label,
            () => updateRuleProvider(provider),
          ),
      for (final script in scripts)
        if (script.url != null)
          update(script.updatingKey, script.label, () => updateScript(script)),
    ]);
    return failures;
  }

  /// Geo files are left to the Core, which reads the same two settings.
  Future<void> autoUpdate({bool Function()? isCurrent}) async {
    bool canUpdate() =>
        ref.mounted &&
        ref.read(appVisibleProvider) &&
        (isCurrent?.call() ?? true);
    if (!canUpdate()) return;
    final setting = ref.read(patchClashConfigProvider);
    if (_autoUpdating) {
      return;
    }
    _autoUpdating = true;
    try {
      await ref
          .read(clashProvidersActionProvider.notifier)
          .resumeAutomaticUpdates(isCurrent: canUpdate);
      if (!canUpdate()) return;
      if (!setting.geoAutoUpdate) return;
      final interval = Duration(hours: setting.geoUpdateInterval);
      bool isDue(DateTime? lastUpdateTime) =>
          lastUpdateTime?.add(interval).isBeforeNow ?? true;
      for (final iconSet in await database.iconSetsDao.query().get()) {
        if (!canUpdate()) return;
        if (iconSet.isRemote && isDue(iconSet.lastUpdateTime)) {
          await _tryUpdate(
            iconSet.name,
            iconSet.updatingKey,
            iconSet.url.toString(),
            interval,
            () => updateIconSet(iconSet, isCurrent: canUpdate),
            isCurrent: canUpdate,
          );
        }
      }
      if (!canUpdate()) return;
      for (final script in await database.scriptsDao.query().get()) {
        if (!canUpdate()) return;
        if (script.url != null && isDue(script.lastUpdateTime)) {
          await _tryUpdate(
            script.label,
            script.updatingKey,
            script.url.toString(),
            interval,
            () => updateScript(script, isCurrent: canUpdate),
            isCurrent: canUpdate,
          );
        }
      }
      if (!canUpdate()) return;
      for (final provider in await database.clashProvidersDao.query().get()) {
        if (!canUpdate()) return;
        if (ref.read(coreStatusProvider) != CoreStatus.connected) {
          break;
        }
        final info = provider.isRemote ? await provider.fileInfo : null;
        if (!canUpdate()) return;
        if (provider.isRemote && isDue(info?.lastModified)) {
          await _tryUpdate(
            provider.label,
            provider.updatingKey,
            provider.url.toString(),
            interval,
            () => updateRuleProvider(provider, isCurrent: canUpdate),
            isCurrent: canUpdate,
          );
        }
      }
    } on UpdateCancelledException {
      return;
    } finally {
      _autoUpdating = false;
    }
  }

  Future<void> _tryUpdate(
    String label,
    String key,
    String source,
    Duration interval,
    Future<void> Function() update, {
    required bool Function() isCurrent,
  }) async {
    if (!_automaticBackoff.ready(key, source)) return;
    try {
      await update();
      if (isCurrent()) _automaticBackoff.reset(key);
    } catch (e) {
      if (e is UpdateCancelledException || !isCurrent()) return;
      _automaticBackoff.failed(key, source, interval);
      commonPrint.log(
        'auto update $label: ${compactError(e)}',
        logLevel: LogLevel.warning,
      );
    }
  }
}

class AutomaticUpdateBackoff {
  AutomaticUpdateBackoff({DateTime Function()? now})
    : _now = now ?? DateTime.now;

  final DateTime Function() _now;
  final _failures =
      <String, ({String source, Duration delay, DateTime until})>{};

  bool ready(String key, String source) {
    final failure = _failures[key];
    if (failure == null) return true;
    if (failure.source != source) {
      _failures.remove(key);
      return true;
    }
    return !_now().isBefore(failure.until);
  }

  void failed(String key, String source, Duration interval) {
    final previous = _failures[key];
    final duration = previous?.source == source
        ? previous!.delay * 2
        : const Duration(minutes: 20);
    final delay = duration > interval ? interval : duration;
    _failures[key] = (source: source, delay: delay, until: _now().add(delay));
  }

  void reset([String? key]) {
    if (key == null) {
      _failures.clear();
    } else {
      _failures.remove(key);
    }
  }
}
