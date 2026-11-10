part of '../action.dart';

String _meowProviderNamespace(ExternalProvider provider) =>
    switch (provider.type) {
      'Proxy' => 'proxy-providers',
      'Rule' => 'rule-providers',
      _ => throw const FormatException('provider: invalid type'),
    };

class _DelayTestTarget {
  const _DelayTestTarget({
    required this.proxyName,
    required this.testUrl,
    required this.key,
  });

  final String proxyName;
  final String testUrl;
  final String key;
}

typedef _DelayTestBatch = ({List<Proxy> proxies, String? testUrl});

class _DelayTestJob {
  _DelayTestJob(Iterable<String> keys, this.timeout) : held = keys.toSet();

  final Set<String> held;
  final Duration timeout;
  final Set<String> startedOnDispatch = {};
  bool cancelled = false;
}

class _DelaySync {
  final Set<String> setMeanwhile = {};
  bool cancelled = false;
}

@Riverpod(keepAlive: true)
class ProxiesAction extends _$ProxiesAction {
  CoreController get _core => ref.read(coreHandlerProvider);

  final TaskPool _delayTestPool = TaskPool(maxConcurrentDelayTests);

  final List<_DelayTestJob> _delayTestJobs = [];

  final List<Delay> _bufferedDelays = [];

  final List<String> _bufferedStarts = [];

  final List<String> _bufferedFinishes = [];

  Timer? _delayFlushTimer;

  _DelaySync? _delaySync;

  final Map<(int?, String), String> _pendingSelectedRollback = {};
  final Map<(int?, String), Object> _selectionIntents = {};
  final Map<(int?, String), (Object, int)> _pendingSelections = {};
  final Set<(int, int)> _dirtySelectionMetadata = {};
  final Map<(int, int), int> _selectionMetadataRefreshes = {};
  int _groupsRevision = 0;
  int _groupsReadRevision = 0;
  int _selectionMetadataRevision = 0;

  @override
  void build() {
    ref.onDispose(() => _delayFlushTimer?.cancel());
    ref.listen(appVisibleProvider, (_, visible) {
      if (!visible) {
        _groupsRevision++;
        debouncer.cancel(FunctionTag.updateGroups);
        cancelDelayTests();
      }
    });
    ref.listen(currentProfileIdProvider, (previous, next) {
      if (previous == next) return;
      _groupsRevision++;
      debouncer.cancel(FunctionTag.updateGroups);
      cancelDelayTests();
      ref.read(groupsProvider.notifier).value = [];
      ref.read(groupsReadFailedProvider.notifier).value = false;
    });
    ref.listen(coreStatusProvider, (_, next) {
      if (next != CoreStatus.connected) {
        _groupsRevision++;
        cancelDelayTests();
      }
    });
  }

  void cancelDelayTests() {
    _delaySync?.cancelled = true;
    _delaySync = null;
    for (final job in _delayTestJobs) {
      job.cancelled = true;
      job.held.clear();
    }
    _delayFlushTimer?.cancel();
    _delayFlushTimer = null;
    _bufferedDelays.clear();
    _bufferedStarts.clear();
    _bufferedFinishes.clear();
    ref.read(pendingDelayTestsProvider.notifier).clear();
  }

  void _scheduleDelayFlush() {
    _delayFlushTimer ??= Timer(renderThrottleDuration, _flushDelayResults);
  }

  void _flushDelayResults() {
    _delayFlushTimer?.cancel();
    _delayFlushTimer = null;
    if (_bufferedDelays.isNotEmpty) {
      final delays = List.of(_bufferedDelays);
      _bufferedDelays.clear();
      ref.read(delayDataSourceProvider.notifier).setDelays(delays);
    }
    if (_bufferedStarts.isNotEmpty || _bufferedFinishes.isNotEmpty) {
      final started = List.of(_bufferedStarts);
      final finished = List.of(_bufferedFinishes);
      _bufferedStarts.clear();
      _bufferedFinishes.clear();
      ref
          .read(pendingDelayTestsProvider.notifier)
          .apply(started: started, finished: finished);
    }
  }

  void updateGroupsDebounce([Duration? duration]) {
    if (!ref.read(appVisibleProvider)) return;
    debouncer.call(FunctionTag.updateGroups, updateGroups, duration: duration);
  }

  void changeProxyDebounce(String groupName, String proxyName) {
    final lite = ClientProduct.current.expectedCoreId == 'meow-rs';
    final profileId = ref.read(currentProfileProvider)?.id;
    final groupsRevision = _groupsRevision;
    final key = (profileId, groupName);
    final intent = _selectionIntents[key] = Object();
    if (lite) {
      _selectionMetadataRevision++;
      _pendingSelections[key] = (intent, groupsRevision);
    }
    _pendingSelectedRollback.putIfAbsent(
      key,
      () => _currentSelectedName(groupName),
    );
    final selection = lite
        ? null
        : _saveSelection(
            profileId: profileId,
            groupName: groupName,
            proxyName: proxyName,
            isCurrent: () =>
                ref.mounted && identical(_selectionIntents[key], intent),
          );
    debouncer.call((FunctionTag.changeProxy, groupName), (
      String groupName,
      String proxyName,
    ) async {
      await _changeProxy(
        groupName: groupName,
        proxyName: proxyName,
        profileId: profileId,
        groupsRevision: groupsRevision,
        intent: intent,
        selection: selection,
      );
      updateGroupsDebounce();
    }, args: [groupName, proxyName]);
  }

  String _currentSelectedName(String groupName) {
    return ref.read(currentProfileProvider)?.selectedMap[groupName] ?? '';
  }

  Future<bool> updateGroups() async {
    if (!ref.read(appVisibleProvider)) return false;
    final revision = _groupsRevision;
    final readRevision = ++_groupsReadRevision;
    bool isCurrent() =>
        ref.mounted &&
        ref.read(appVisibleProvider) &&
        revision == _groupsRevision &&
        readRevision == _groupsReadRevision;
    try {
      final groups = await retry<List<Group>?>(
        task: () async {
          if (!isCurrent()) return null;
          final sortType = ref.read(
            proxiesStyleSettingProvider.select((state) => state.sortType),
          );
          final delayMap = ref.read(delayDataSourceProvider);
          final testUrl = ref.read(
            appSettingProvider.select((state) => state.testUrl),
          );
          final selectedMap = ref.read(
            currentProfileProvider.select((state) => state?.selectedMap ?? {}),
          );
          try {
            return await _core.getProxiesGroups(
              selectedMap: selectedMap,
              sortType: sortType,
              delayMap: delayMap,
              defaultTestUrl: testUrl,
            );
          } catch (e) {
            commonPrint.log(
              'updateGroups error: $e',
              logLevel: coreFailureLogLevel(e),
            );
            return null;
          }
        },
        retryIf: (res) => res == null && isCurrent(),
      );
      if (isCurrent()) {
        ref.read(groupsProvider.notifier).value = groups ?? [];
        ref.read(groupsReadFailedProvider.notifier).value = groups == null;
        return groups != null;
      }
    } catch (e) {
      commonPrint.log(
        'updateGroups failed: $e',
        logLevel: coreFailureLogLevel(e),
      );
    }
    return false;
  }

  void updateCurrentGroupName(String groupName) {
    final profile = ref.read(currentProfileProvider);
    if (profile == null || profile.currentGroupName == groupName) return;
    ref
        .read(profilesProvider.notifier)
        .put(profile.copyWith(currentGroupName: groupName));
  }

  void updateCurrentUnfoldSet(Set<String> value) {
    final currentProfile = ref.read(currentProfileProvider);
    if (currentProfile == null) return;
    ref
        .read(profilesProvider.notifier)
        .put(currentProfile.copyWith(unfoldSet: value));
  }

  void setDelay(Delay delay) {
    if (!ref.read(appVisibleProvider)) return;
    _delaySync?.setMeanwhile.add(delayTestKey(delay.url, delay.name));
    _bufferedDelays.add(delay);
    _scheduleDelayFlush();
  }

  /// A result that lands while the read is in flight is newer than the read.
  Future<bool> syncLatestDelays() async {
    if (!ref.read(appVisibleProvider)) return false;
    final sync = _delaySync = _DelaySync();
    try {
      final latest = await _core.getLatestDelays();
      if (sync.cancelled || !ref.mounted || !ref.read(appVisibleProvider)) {
        return false;
      }
      final shown = ref.read(delayDataSourceProvider);
      final fresh = latest.where((delay) {
        if (sync.setMeanwhile.contains(delayTestKey(delay.url, delay.name))) {
          return false;
        }
        final current = shown[delay.url]?[delay.name];
        final failed = (delay.value ?? -1) < 0;
        return !failed || current == null || current >= 0;
      }).toList();
      ref.read(delayDataSourceProvider.notifier).setDelays(fresh);
      return true;
    } catch (error) {
      commonPrint.log(
        'syncLatestDelays failed: $error',
        logLevel: coreFailureLogLevel(error),
      );
      return false;
    } finally {
      if (identical(_delaySync, sync)) _delaySync = null;
    }
  }

  Future<void> changeProxy({
    required String groupName,
    required String proxyName,
  }) {
    final profileId = ref.read(currentProfileProvider)?.id;
    final intent = _selectionIntents[(profileId, groupName)] = Object();
    if (ClientProduct.current.expectedCoreId == 'meow-rs') {
      _selectionMetadataRevision++;
      _pendingSelections[(profileId, groupName)] = (intent, _groupsRevision);
    }
    return _changeProxy(
      groupName: groupName,
      proxyName: proxyName,
      profileId: profileId,
      groupsRevision: _groupsRevision,
      intent: intent,
    );
  }

  Future<bool> _saveSelection({
    required int? profileId,
    required String groupName,
    required String proxyName,
    required bool Function() isCurrent,
  }) async {
    try {
      return await ref
          .read(profilesActionProvider.notifier)
          .updateCurrentSelectedMap(
            groupName,
            proxyName,
            profileId: profileId,
            isCurrent: isCurrent,
          );
    } catch (error) {
      commonPrint.log(
        'Saving proxy selection failed: $error',
        logLevel: coreFailureLogLevel(error),
      );
      if (ref.mounted && isCurrent()) {
        dialogs.showNotifier(
          currentAppLocalizations.databaseWriteFailedTip,
          level: MessageLevel.error,
        );
      }
      return false;
    }
  }

  Future<void> _changeProxy({
    required String groupName,
    required String proxyName,
    required int? profileId,
    required int groupsRevision,
    required Object intent,
    Future<bool>? selection,
  }) async {
    final lite = ClientProduct.current.expectedCoreId == 'meow-rs';
    final key = (profileId, groupName);
    bool intentCurrent() =>
        ref.mounted &&
        identical(_selectionIntents[key], intent) &&
        ref.read(currentProfileProvider)?.id == profileId &&
        (!lite ||
            groupsRevision == _groupsRevision &&
                ref.read(coreStatusProvider) == CoreStatus.connected);
    bool current() =>
        intentCurrent() &&
        ((!lite && profileId == null) ||
            ref.read(currentProfileProvider)?.selectedMap[groupName] ==
                proxyName);
    try {
      if (!intentCurrent()) return;
      final rollbackName =
          _pendingSelectedRollback.remove(key) ??
          _currentSelectedName(groupName);
      final persisted =
          !lite && profileId == null ||
          await (selection ??
              _saveSelection(
                profileId: profileId,
                groupName: groupName,
                proxyName: proxyName,
                isCurrent: intentCurrent,
              ));
      if (!persisted || !current()) return;
      final ChangeProxyResult result;
      try {
        result = await _core.changeProxy(
          ChangeProxyParams(groupName: groupName, proxyName: proxyName),
        );
        if (result.message.isNotEmpty) {
          throw MessageException(result.message);
        }
      } catch (error) {
        commonPrint.log(
          'changeProxy($groupName -> $proxyName) failed: $error',
          logLevel: coreFailureLogLevel(error),
        );
        if (!current()) return;
        await _saveSelection(
          profileId: profileId,
          groupName: groupName,
          proxyName: rollbackName,
          isCurrent: intentCurrent,
        );
        if (!intentCurrent()) return;
        dialogs.showNotifier(
          currentAppLocalizations.changeProxyFailedTip,
          level: MessageLevel.error,
        );
        return;
      }
      if (lite) {
        if (profileId == null ||
            !ref.mounted ||
            ref.read(currentProfileProvider)?.id != profileId ||
            groupsRevision != _groupsRevision ||
            ref.read(coreStatusProvider) != CoreStatus.connected) {
          return;
        }
        _dirtySelectionMetadata.add((profileId, groupsRevision));
        if (!current()) return;
      }
      if (!result.changed) {
        return;
      }
      try {
        if (ref.read(appSettingProvider).closeConnections) {
          await _core.closeConnections();
        } else {
          await _core.resetConnections();
        }
      } catch (error) {
        commonPrint.log(
          'changeProxy($groupName -> $proxyName) connection reset failed: $error',
          logLevel: coreFailureLogLevel(error),
        );
      }
    } finally {
      if (lite) {
        if (identical(_pendingSelections[key]?.$1, intent)) {
          _pendingSelections.remove(key);
        }
        if (profileId != null) {
          await _convergeSelectionMetadata(
            profileId,
            groupsRevision,
            groupName,
          );
        }
      }
    }
  }

  Future<void> _convergeSelectionMetadata(
    int profileId,
    int groupsRevision,
    String groupName,
  ) async {
    final scope = (profileId, groupsRevision);
    final revision = _selectionMetadataRevision;
    bool current() =>
        ref.mounted &&
        revision == _selectionMetadataRevision &&
        groupsRevision == _groupsRevision &&
        ref.read(coreStatusProvider) == CoreStatus.connected &&
        ref.read(currentProfileProvider)?.id == profileId &&
        !_pendingSelections.entries.any(
          (entry) =>
              entry.key.$1 == profileId && entry.value.$2 == groupsRevision,
        );
    if (!_dirtySelectionMetadata.contains(scope) ||
        !current() ||
        _selectionMetadataRefreshes[scope] == revision)
      return;
    _selectionMetadataRefreshes[scope] = revision;
    final proxyName = _currentSelectedName(groupName);
    try {
      final refreshed = await ref
          .read(setupActionProvider.notifier)
          .refreshSelectionMetadata(
            profileId: profileId,
            groupName: groupName,
            proxyName: proxyName,
            isCurrent: current,
          );
      if (refreshed && current()) _dirtySelectionMetadata.remove(scope);
    } finally {
      if (_selectionMetadataRefreshes[scope] == revision) {
        _selectionMetadataRefreshes.remove(scope);
      }
    }
  }

  Future<String> updateProvider(
    ExternalProvider provider, {
    bool showLoading = false,
  }) async {
    final operation = showLoading
        ? ref
              .read(updatingKeysProvider.notifier)
              .start(provider.updatingKey, scope: UpdatingScope.core)
        : null;
    try {
      if (ClientProduct.current.expectedCoreId == 'meow-rs') {
        await ref
            .read(setupActionProvider.notifier)
            .updateMeowProvider(
              namespace: _meowProviderNamespace(provider),
              name: provider.name,
              expectedRuntimeRevision: provider.runtimeRevision,
            );
        return '';
      }
      final clashProvidersAction = ref.read(
        clashProvidersActionProvider.notifier,
      );
      final ruleSet = await clashProvidersAction.remoteProviderOf(provider);
      if (ruleSet != null && !await clashProvidersAction.syncRemote(ruleSet)) {
        return '';
      }
      final message = await _core.updateExternalProvider(
        providerName: provider.name,
      );
      if (message.isNotEmpty) return message;
      ref
          .read(providersProvider.notifier)
          .setProvider(await _core.getExternalProvider(provider.name));
      return '';
    } finally {
      if (operation != null) {
        ref
            .read(updatingKeysProvider.notifier)
            .stop(provider.updatingKey, operation);
      }
    }
  }

  Future<String> sideLoadExternalProvider(
    ExternalProvider provider,
    String data, {
    bool showLoading = false,
  }) async {
    final operation = showLoading
        ? ref
              .read(updatingKeysProvider.notifier)
              .start(provider.updatingKey, scope: UpdatingScope.core)
        : null;
    try {
      if (ClientProduct.current.expectedCoreId == 'meow-rs') {
        await ref
            .read(setupActionProvider.notifier)
            .updateMeowProvider(
              namespace: _meowProviderNamespace(provider),
              name: provider.name,
              replacementData: data,
              expectedRuntimeRevision: provider.runtimeRevision,
            );
        return '';
      }
      final message = await _core.sideLoadExternalProvider(
        providerName: provider.name,
        data: data,
      );
      if (message.isNotEmpty) return message;
      ref
          .read(providersProvider.notifier)
          .setProvider(await _core.getExternalProvider(provider.name));
      return '';
    } finally {
      if (operation != null) {
        ref
            .read(updatingKeysProvider.notifier)
            .stop(provider.updatingKey, operation);
      }
    }
  }

  Future<void> addToLocalProxies(String proxyName) async {
    final appLocalizations = currentAppLocalizations;
    final definition = await _core.getProxyDefinition(proxyName);
    if (definition == null) {
      throw MessageException(
        appLocalizations.customIssueMissingProxies(proxyName),
      );
    }
    final (proxies, groupNames) = await (
      database.customProxiesDao.query().get(),
      database.proxyGroupsDao.names().get(),
    ).wait;
    final (:name, :added) = importProxy(
      definition,
      existing: [for (final proxy in proxies) proxy.definition],
      groupNames: groupNames.toSet(),
    );
    if (added == null) {
      dialogs.showNotifier(appLocalizations.alreadyInLocalProxies(name));
      return;
    }
    final order = indexing.generateKeyBetween(
      proxies.map((proxy) => proxy.order).nonNulls.lastOrNull,
      null,
    );
    await database.customProxies.put(
      CustomProxy.fromDefinition(added).copyWith(order: order).toCompanion(),
    );
    dialogs.showNotifier(appLocalizations.addedToLocalProxies(name));
  }

  Future<void> proxyDelayTest(Proxy proxy, [String? testUrl]) {
    return _runDelayTests([
      (proxies: [proxy], testUrl: testUrl),
    ]);
  }

  Future<void> delayTest(List<Proxy> proxies, [String? testUrl]) async {
    final revision = _groupsRevision;
    await _runDelayTests([(proxies: proxies, testUrl: testUrl)]);
    if (ref.mounted &&
        ref.read(appVisibleProvider) &&
        revision == _groupsRevision) {
      ref.read(sortNumProvider.notifier).add();
    }
  }

  Future<void> delayTestGroups(List<Group> groups) async {
    final revision = _groupsRevision;
    await _runDelayTests([
      for (final group in groups) (proxies: group.all, testUrl: group.testUrl),
    ]);
    if (ref.mounted &&
        ref.read(appVisibleProvider) &&
        revision == _groupsRevision) {
      ref.read(sortNumProvider.notifier).add();
    }
  }

  Future<void> delayTestPageGroup(
    String groupName, {
    bool matchSearch = true,
  }) async {
    final group = ref.read(groupsProvider).getGroup(groupName);
    if (group == null) {
      return;
    }
    final testing = ref.read(delayTestingGroupsProvider.notifier);
    if (!testing.start(groupName)) {
      return;
    }
    try {
      final query = SearchQuery(
        matchSearch ? ref.read(queryProvider(QueryTag.proxies)) : '',
      );
      await delayTest(
        group.all.whereMatches(query, (proxy) => proxy.searchFields).toList(),
        group.testUrl,
      );
    } finally {
      testing.stop(groupName);
    }
  }

  List<_DelayTestTarget> _resolveDelayTestTargets(
    List<_DelayTestBatch> batches,
  ) {
    final groups = ref.read(groupsProvider);
    final selectedMap = ref.read(
      currentProfileProvider.select((state) => state?.selectedMap ?? {}),
    );
    final seen = <String>{};
    final targets = <_DelayTestTarget>[];
    for (final batch in batches) {
      final fallbackTestUrl = ref.read(realTestUrlProvider(batch.testUrl));
      for (final proxy in batch.proxies) {
        final state = computeRealSelectedProxyState(
          proxy.name,
          groups: groups,
          selectedMap: selectedMap,
        );
        if (state.proxyName.isEmpty) {
          continue;
        }
        final currentTestUrl = state.testUrl.takeFirstValid([fallbackTestUrl]);
        final key = delayTestKey(currentTestUrl, state.proxyName);
        if (!seen.add(key)) {
          continue;
        }
        targets.add(
          _DelayTestTarget(
            proxyName: state.proxyName,
            testUrl: currentTestUrl,
            key: key,
          ),
        );
      }
    }
    return targets;
  }

  Future<void> _runDelayTests(List<_DelayTestBatch> batches) async {
    if (!ref.read(appVisibleProvider)) return;
    final targets = _resolveDelayTestTargets(batches);
    if (targets.isEmpty) {
      return;
    }
    final pending = ref.read(pendingDelayTestsProvider.notifier);
    final job = _DelayTestJob(
      targets.map((target) => target.key),
      Duration(milliseconds: ref.read(appSettingProvider).delayTestTimeout),
    );
    _delayTestJobs.add(job);
    // TaskPool.run starts a task synchronously while it has an idle slot.
    job.startedOnDispatch.addAll(
      targets.take(_delayTestPool.idleSlots).map((target) => target.key),
    );
    pending.apply(acquired: job.held, started: job.startedOnDispatch);
    try {
      await Future.wait(
        targets.map(
          (target) => _delayTestPool.run(() => _runDelayTest(job, target)),
        ),
      );
    } finally {
      _delayTestJobs.remove(job);
      _flushDelayResults();
      final abandoned = job.held.toList();
      job.held.clear();
      pending.apply(
        finished: abandoned.where(job.startedOnDispatch.contains),
        released: abandoned.where(
          (key) => !job.startedOnDispatch.contains(key),
        ),
      );
    }
  }

  Future<void> _runDelayTest(_DelayTestJob job, _DelayTestTarget target) async {
    if (job.cancelled || !ref.mounted || !ref.read(appVisibleProvider)) {
      return;
    }
    if (!job.startedOnDispatch.contains(target.key)) {
      _bufferedStarts.add(target.key);
      _scheduleDelayFlush();
    }
    try {
      final delay = await _core.getDelay(
        target.testUrl,
        target.proxyName,
        job.timeout,
      );
      if (delay != null && !job.cancelled) {
        setDelay(delay);
      }
    } catch (error) {
      if (error is CoreMethodException && error.isCoreUnavailable) {
        job.cancelled = true;
      }
      commonPrint.log(
        'Delay test failed for ${target.proxyName}: $error',
        logLevel: coreFailureLogLevel(error),
      );
    } finally {
      if (job.held.remove(target.key)) {
        _bufferedFinishes.add(target.key);
        _scheduleDelayFlush();
      }
    }
  }
}
