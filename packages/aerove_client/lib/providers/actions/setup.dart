part of '../action.dart';

enum _SetupTaskResult { completed, handoffToCoreRestart, failed }

// getRunTime waits out a service transition in flight, which can outlast the
// splash; past this the button catches up in initStatus instead.
const _runTimePrefillTimeout = Duration(milliseconds: 300);

typedef _SetupInputs = (
  SetupState,
  PatchClashConfig,
  NetworkProps,
  SetupParams,
);

typedef BuiltProfile = ({
  String yaml,
  String md5,
  ConfigCounts counts,
  SetupBase? base,
  ProfileSelectionPolicy? selectionPolicy,
  List<String> providerSourceWitness,
});

class _RunRequest {
  final bool running;
  final bool initialize;
  final DateTime? previousStartTime;

  const _RunRequest({
    required this.running,
    required this.initialize,
    required this.previousStartTime,
  });
}

@Riverpod(keepAlive: true)
class SetupAction extends _$SetupAction {
  CoreController get _core => ref.read(coreHandlerProvider);

  void _publishAppliedCounts(ConfigCounts? counts) {
    globalState.lastConfigCounts = counts;
    if (ref.mounted) {
      ref.read(appliedConfigCountsProvider.notifier).publish(counts);
    }
  }

  Timer? _runtimeTimer;
  final _builderFilterRequests = <String, CoreController>{};
  int _runtimeRevision = 0;
  int _geoRevision = 0;
  String? _lastGeoGlobal;
  ({CoreController core, String transactionId})? _geoTransaction;

  void _cancelGeoRequest() {
    _geoRevision++;
    final previous = _geoTransaction;
    _geoTransaction = null;
    if (previous != null) {
      unawaited(
        previous.core
            .updateGeoPrepared(
              MeowGeoRequest.abort(transactionId: previous.transactionId),
            )
            .then<void>(
              (_) {},
              onError: (Object error) {
                commonPrint.log(
                  'Geo cancellation status: ${compactError(error)}',
                  logLevel: LogLevel.warning,
                );
              },
            ),
      );
    }
  }

  final _setupScheduler = SerialTaskScheduler();
  final _providerCancellations = <ValueNotifier<bool>>{};
  final _providerCompletions = <ValueNotifier<bool>, Completer<void>>{};
  int _providerSourceWriters = 0;
  final _providerSourceScheduler = SerialTaskScheduler();
  final _providerSourceLease = Object();

  Future<T> withProviderSourceWrite<T>(Future<T> Function() write) async {
    return _withProviderSourceWrite(write);
  }

  Future<T> _withProviderSourceWrite<T>(
    Future<T> Function() write, {
    ValueNotifier<bool>? operation,
  }) async {
    if (ClientProduct.current.expectedCoreId != 'meow-rs') return write();
    if (identical(Zone.current[_providerSourceLease], this)) return write();
    _providerSourceWriters++;
    try {
      return await _providerSourceScheduler.run(() async {
        final pending = Map.of(_providerCompletions)..remove(operation);
        for (final signal in pending.keys) {
          signal.value = true;
        }
        await Future.wait(pending.values.map((done) => done.future));
        return runZoned(() async {
          await _recoverProviderSources();
          return write();
        }, zoneValues: {_providerSourceLease: this});
      });
    } finally {
      _providerSourceWriters--;
    }
  }

  Future<Map<String, dynamic>> _providerBusinessProof({
    bool saveCurrent = false,
  }) async {
    await waitForDatabaseWrites();
    final storage = await preferences.sharedPreferences;
    if (storage == null) throw StateError('Shared data storage is unavailable');
    if (saveCurrent) debouncer.cancel(FunctionTag.savePreferences);
    return nativeLiteBusinessSource(
      database,
      Directory(await appPath.homeDirPath),
      storage,
      saveCurrent ? ref.read(configProvider) : null,
    );
  }

  Future<void> _recoverProviderSources() async {
    final journals = await FileProviderSourceJournal.pending(
      Directory(await appPath.homeDirPath),
    );
    for (final journal in journals) {
      await journal.recover(
        invoke: _core.invokeMeowProviderSource,
        readProof: _providerBusinessProof,
        database: database,
      );
    }
  }

  void _cancelProviderRequests() {
    for (final cancellation in _providerCancellations) {
      cancellation.value = true;
    }
  }

  final _listenerScheduler = SerialTaskScheduler();
  final _updateScheduler = SerialTaskScheduler();
  _RunRequest? _latestRunRequest;
  DateTime? _startTime;
  _SetupInputs? _appliedInputs;
  var _pendingForceApply = false;
  Completer<bool>? _lanEnableReceipt;
  ProviderSubscription<CoreController>? _lanCoreSubscription;
  ({Completer<bool> receipt, CoreAction owner, int revision})? _lanRestart;

  bool get isLanEnableRestarting {
    final restart = _lanRestart;
    return restart != null &&
        identical(restart.receipt, _lanEnableReceipt) &&
        restart.owner._requestedRestartRevision == restart.revision;
  }

  bool get _isRunning => _startTime != null && _startTime!.isBeforeNow;

  @override
  void build() {
    ref.onDispose(_cancelProviderRequests);
    ref.listen(currentProfileIdProvider, (_, _) => _cancelProviderRequests());
    ref.listen(coreHandlerProvider, (_, _) => _cancelProviderRequests());
    ref.listen(coreStatusProvider, (_, status) {
      if (status != CoreStatus.connected) _cancelProviderRequests();
    });
    ref.listen(updateParamsProvider, (_, _) => _cancelProviderRequests());
    ref.listen(setupPatchProvider, (_, _) => _cancelProviderRequests());
    ref.onDispose(_stopRuntimeTicker);
    ref.onDispose(_cancelGeoRequest);
    ref.listen(currentProfileIdProvider, (_, _) => _cancelGeoRequest());
    ref.listen(coreHandlerProvider, (_, _) => _cancelGeoRequest());
    ref.listen(coreStatusProvider, (_, next) {
      if (next != CoreStatus.connected) _cancelGeoRequest();
    });
    ref.onDispose(
      () => unawaited(
        _cancelBuilderFilters().catchError((Object _) {
          commonPrint.log(
            'Builder filter cancellation did not join',
            logLevel: LogLevel.warning,
          );
        }),
      ),
    );
    ref.onDispose(() => _finishLanEnable(false));
    ref.listen(updateParamsProvider, (_, _) => _finishLanEnable(false));
    ref.listen(currentProfileIdProvider, (_, _) => _finishLanEnable(false));
    ref.listen(coreStatusProvider, (_, status) {
      if (status == CoreStatus.disconnected || !isLanEnableRestarting) {
        _finishLanEnable(false);
      }
    });
    ref.listen(
      appVisibleProvider,
      (_, visible) => _syncRuntimeTicker(refresh: visible),
    );
    ref.listen(trafficDemandProvider, (previous, next) {
      final added = previous == null || !previous.containsAll(next);
      _syncRuntimeTicker(refresh: added);
    });
    ref.listen(
      appSettingProvider.select((state) => state.showTrayTitle),
      (_, _) => _syncRuntimeTicker(),
    );
  }

  bool _isShown(TrafficNeed need) =>
      ref.read(appVisibleProvider) &&
      ref.read(trafficDemandProvider).contains(need);

  bool get _isTrafficShown =>
      TrafficNeed.values.any(_isShown) ||
      (system.isMacOS &&
          !ref.read(safeModeProvider) &&
          ref.read(appSettingProvider).showTrayTitle);

  SetupParams get _setupParams {
    final selectedMap = ref.read(selectedMapProvider);
    final appSetting = ref.read(appSettingProvider);
    return SetupParams(
      selectedMap: selectedMap,
      testUrl: appSetting.testUrl,
      skipCertVerify: !appSetting.checkCertificate,
    );
  }

  // The selection reaches the Core through changeProxy, not through an apply.
  SetupParams get _appliedParams =>
      _setupParams.copyWith(selectedMap: const {});

  Future<bool> fullSetup() async {
    if (!ref.read(initProvider)) return true;
    ref.read(proxiesActionProvider.notifier).cancelDelayTests();
    ref.read(delayDataSourceProvider.notifier).value = {};
    final setupResult = applyProfile(force: true);
    ref.read(logsProvider.notifier).value = FixedList(maxLogsLength);
    ref.read(requestsProvider.notifier).value = FixedList(maxRequestsLength);
    ref.read(dnsQueriesProvider.notifier).value = FixedList(
      maxDnsQueriesLength,
    );
    ref.read(dnsQueryCountProvider.notifier).value = 0;
    ref.read(requestCountProvider.notifier).value = 0;
    try {
      return await setupResult;
    } catch (e, s) {
      commonPrint.log('fullSetup ===> ${compactError(e)}, $s');
      return false;
    }
  }

  void _setLocalRunning(bool running) {
    _stopRuntimeTicker();
    if (!running) {
      _startTime = null;
      debouncer.cancel(FunctionTag.applyProfile);
      _pendingForceApply = false;
      _updateRunTime();
      return;
    }

    _startTime ??= DateTime.now();
    _updateRunTime();
    _syncRuntimeTicker();
  }

  void _syncRuntimeTicker({bool refresh = false}) {
    if (_startTime == null || !_isTrafficShown) {
      _stopRuntimeTicker();
      return;
    }
    if (_runtimeTimer != null) {
      if (refresh) _refreshRunningState();
      return;
    }
    _refreshRunningState();
    _runtimeTimer = Timer.periodic(
      const Duration(seconds: 1),
      (_) => _refreshRunningState(),
    );
  }

  void _stopRuntimeTicker() {
    _runtimeRevision++;
    _runtimeTimer?.cancel();
    _runtimeTimer = null;
  }

  void _refreshRunningState() {
    if (_isShown(TrafficNeed.runTime)) {
      final revision = _runtimeRevision;
      FramePacer.publish(this, () {
        if (ref.mounted &&
            revision == _runtimeRevision &&
            _isShown(TrafficNeed.runTime)) {
          _updateRunTime();
        }
      });
    }
    unawaited(ref.read(commonActionProvider.notifier).updateTraffic());
  }

  void _updateRunTime() {
    final startTime = _startTime;
    ref.read(runTimeProvider.notifier).value = startTime == null
        ? null
        : DateTime.now().millisecondsSinceEpoch -
              startTime.millisecondsSinceEpoch;
  }

  @protected
  Future<ServiceRunTime?> readServiceRunTime() async =>
      await service?.getRunTime();

  @protected
  Future<AppliedSetup?> readAppliedSetup() => _core.getAppliedSetup();

  void _takeServiceStartTime(ServiceRunTime? runTime) {
    final startedAt = runTime?.startedAt;
    _startTime = startedAt != null && startedAt.isBeforeNow ? startedAt : null;
    _updateRunTime();
  }

  /// Runs before the first frame, so a run the service holds never shows as
  /// stopped while [initStatus] catches up.
  Future<void> prefillRunTime() async {
    try {
      _takeServiceStartTime(
        await readServiceRunTime().timeout(
          _runTimePrefillTimeout,
          onTimeout: () => null,
        ),
      );
    } catch (e) {
      commonPrint.log(
        'prefill run time ===> ${compactError(e)}',
        logLevel: LogLevel.warning,
      );
    }
  }

  /// A degraded launch hands the Core no config and starts nothing, but still
  /// takes over a run the Core already serves.
  Future<void> initStatus({bool degraded = false}) async {
    if (!globalState.needInitStatus) {
      return;
    }
    final runTime = await _readServiceRunTime();
    if (_latestRunRequest == null) {
      _takeServiceStartTime(runTime);
    }
    final applied = await _readAppliedSetup();
    if (!globalState.needInitStatus) {
      return;
    }
    final running =
        _latestRunRequest == null &&
        (_isRunning || (!degraded && ref.read(appSettingProvider).autoRun));
    final adopt = applied?.running == false ? null : runTime;
    final record = applied?.applied;
    if (record != null) {
      await _reattach(record, running: running, adopt: adopt, push: !degraded);
    } else if (degraded) {
      if (running) {
        await _adoptRunOnly(adopt);
      }
    } else if (running) {
      await setRunning(true, initialize: true);
    } else {
      await globalState.safeRun(() => applyProfile(force: true));
    }
  }

  Future<ServiceRunTime?> _readServiceRunTime() async {
    try {
      return await readServiceRunTime();
    } catch (e) {
      commonPrint.log(
        'getRunTime ===> ${compactError(e)}',
        logLevel: LogLevel.warning,
      );
      return null;
    }
  }

  Future<void> _adoptRunOnly(ServiceRunTime? adopt) async {
    final request = _claim(true, initialize: true);
    await _settleInitialStart(request, () async {
      await _startCoreRunning(request, adopt: adopt);
      return true;
    });
  }

  Future<AppliedSetup?> _readAppliedSetup() async {
    try {
      return await readAppliedSetup();
    } catch (e) {
      commonPrint.log(
        'getAppliedSetup ===> ${compactError(e)}',
        logLevel: coreFailureLogLevel(e),
      );
      return null;
    }
  }

  Future<void> _reattach(
    SetupRecord record, {
    required bool running,
    required ServiceRunTime? adopt,
    required bool push,
  }) async {
    if (running) {
      final request = _claim(true, initialize: true);
      await _settleInitialStart(
        request,
        () => _adoptOrApply(
          record,
          push: push,
          preloadInvoke: () => _startCoreRunning(request, adopt: adopt),
        ),
      );
    } else {
      globalState.needInitStatus = false;
      await globalState.safeRun(() => _adoptOrApply(record, push: push));
    }
    await updateConfig();
  }

  /// Keeps the config the Core runs when the inputs still match [record];
  /// otherwise rebuilds and pushes only if the YAML moved, unless [push] is off.
  Future<bool> _adoptOrApply(
    SetupRecord record, {
    required bool push,
    Future<void> Function()? preloadInvoke,
  }) async {
    final adopted = await _setupScheduler.run(() async {
      if (!await _adoptAppliedSetup(record) && push) {
        return false;
      }
      if (preloadInvoke != null) {
        await _runSurfaced(preloadInvoke);
      }
      return true;
    });
    final applied = adopted || await applyProfile(preloadInvoke: preloadInvoke);
    await globalState.safeRun(
      () => ref.read(providersProvider.notifier).syncProviders(),
    );
    return applied;
  }

  Future<bool> _adoptAppliedSetup(SetupRecord record) async {
    globalState.lastConfigMd5 = record.yamlMd5;
    _publishAppliedCounts((
      groups: record.groups,
      proxies: record.proxies,
      rules: record.rules,
    ));
    final inputs = await _currentInputs();
    final adopted =
        inputs != null && await _inputsDigest(inputs) == record.digest;
    _appliedInputs = adopted ? inputs : null;
    return adopted;
  }

  Future<bool> setRunning(bool running, {bool initialize = false}) {
    if (running && !initialize && !ref.read(initProvider)) {
      return Future.value(true);
    }
    final request = _claim(running, initialize: running && initialize);
    if (!running)
      unawaited(
        _cancelBuilderFilters().catchError((Object _) {
          commonPrint.log(
            'Builder filter cancellation did not join',
            logLevel: LogLevel.warning,
          );
        }),
      );
    return running ? _start(request) : _stop(request);
  }

  _RunRequest _claim(bool running, {required bool initialize}) {
    if (!(running && initialize && isLanEnableRestarting)) {
      _finishLanEnable(false);
    }
    final request = _RunRequest(
      running: running,
      initialize: initialize,
      previousStartTime: _startTime,
    );
    _latestRunRequest = request;
    _setLocalRunning(running);
    if (initialize) {
      globalState.needInitStatus = false;
    }
    return request;
  }

  Future<bool> _settleInitialStart(
    _RunRequest request,
    Future<bool> Function() apply,
  ) async {
    var applied = false;
    try {
      applied = await apply();
    } catch (_) {
      applied = false;
    }
    if (!applied && _isCurrent(request)) {
      await globalState.safeRun(() => setRunning(false));
    }
    return applied;
  }

  Future<bool> _start(_RunRequest request) async {
    if (request.initialize) {
      return _settleInitialStart(
        request,
        () => applyProfile(
          force: true,
          preloadInvoke: () => _startCoreRunning(request),
        ),
      );
    }

    try {
      await _startCoreRunning(request);
    } catch (_) {
      _rollbackRunning(request);
      rethrow;
    }
    final inputsApplied = await _inputsApplied();
    if (_isCurrent(request) && !inputsApplied) {
      applyProfileDebounce(force: true, silence: true);
    }
    return true;
  }

  Future<void> _startCoreRunning(
    _RunRequest request, {
    ServiceRunTime? adopt,
  }) async {
    if (!await _setCoreRunning(request, adopt: adopt) && _isCurrent(request)) {
      throw _unanswered(CoreMethod.startListener);
    }
  }

  Future<bool> _stop(_RunRequest request) async {
    final bool answered;
    try {
      answered = await _setCoreRunning(request);
    } catch (_) {
      _rollbackRunning(request);
      rethrow;
    }
    if (!_isCurrent(request)) {
      return true;
    }
    resetCoreTraffic();
    ref.read(trafficsProvider.notifier).clear();
    ref.read(totalTrafficProvider.notifier).value = const Traffic();
    // A silent Core is gone or hung; running again would proxy traffic to it.
    if (!answered) {
      throw _unanswered(CoreMethod.stopListener);
    }
    return true;
  }

  Future<bool> _setCoreRunning(_RunRequest request, {ServiceRunTime? adopt}) {
    return _listenerScheduler.run(() async {
      if (!_isCurrent(request)) {
        return true;
      }
      if (ref.read(safeModeProvider)) {
        return true;
      }
      if (request.running && ref.read(suspendProvider)) {
        return _parkListening();
      }
      if (adopt != null) {
        return _adoptListening(adopt);
      }
      return _setListening(request.running);
    });
  }

  Future<bool> _adoptListening(ServiceRunTime runTime) async {
    final vpnOptions =
        runTime.vpnOptions ?? await ref.read(vpnOptionsProvider.future);
    final answered = runTime.parked
        ? await setCoreRunning(true)
        : await adoptCoreRunning();
    ref.read(runningVpnOptionsProvider.notifier).value = answered
        ? vpnOptions
        : null;
    return answered;
  }

  Future<bool> _parkListening() async {
    final answered = await setCoreParked();
    if (answered) ref.read(runningVpnOptionsProvider.notifier).value = null;
    return answered;
  }

  Future<bool> _setListening(bool running) async {
    final vpnOptions = await ref.read(vpnOptionsProvider.future);
    final answered = await setCoreRunning(running);
    ref.read(runningVpnOptionsProvider.notifier).value = running && answered
        ? vpnOptions
        : null;
    return answered;
  }

  CoreMethodException _unanswered(CoreMethod method) => CoreMethodException(
    code: 'no_response',
    message: 'Core did not answer ${method.name}',
  );

  /// An excluded SSID parks the listener without ending the run.
  void syncSuspend() {
    debouncer.call(FunctionTag.suspend, () {
      return _listenerScheduler.run(() async {
        if (!ref.read(isStartProvider) || ref.read(safeModeProvider)) {
          return;
        }
        if (ref.read(suspendProvider)) {
          await _parkListening();
        } else {
          await _setListening(true);
        }
      });
    });
  }

  Future<void> restartVpn() async {
    if (!ref.read(isStartProvider)) {
      return;
    }
    await setRunning(false);
    await setRunning(true);
    if (ref.read(isStartProvider)) {
      ref.read(profilesActionProvider.notifier).resetAutomaticBackoff();
      ref.read(resourcesActionProvider.notifier).resetAutomaticBackoff();
    }
  }

  void _rollbackRunning(_RunRequest request) {
    if (!_isCurrent(request)) {
      return;
    }
    _startTime = request.previousStartTime;
    _setLocalRunning(!request.running);
  }

  bool _isCurrent(_RunRequest request) => identical(_latestRunRequest, request);

  Future<void> updateConfigDebounce() async {
    debouncer.call(FunctionTag.updateConfig, updateConfig);
  }

  Future<bool> setAllowLan(bool enabled) {
    _finishLanEnable(false);
    final previous = ref.read(patchClashConfigProvider).allowLan;
    ref
        .read(patchClashConfigProvider.notifier)
        .update((state) => state.copyWith(allowLan: enabled));
    ref.read(updateParamsProvider);
    if (!enabled || previous || ref.read(safeModeProvider)) {
      return Future.value(false);
    }
    final receipt = Completer<bool>();
    _lanEnableReceipt = receipt;
    _lanCoreSubscription = ref.listen(
      coreHandlerProvider,
      (_, _) => _finishLanEnable(false),
    );
    return receipt.future;
  }

  void _finishLanEnable(bool applied) {
    final receipt = _lanEnableReceipt;
    _lanEnableReceipt = null;
    _lanRestart = null;
    _lanCoreSubscription?.close();
    _lanCoreSubscription = null;
    receipt?.complete(applied);
  }

  void _onCoreRestartRequested(CoreAction owner, int revision) {
    final restart = _lanRestart;
    if (restart == null ||
        !identical(restart.owner, owner) ||
        restart.revision != revision) {
      _finishLanEnable(false);
    }
  }

  Future<bool> _restartForLanEnable(Completer<bool> receipt) async {
    final owner = ref.read(coreActionProvider.notifier);
    _lanRestart = (
      receipt: receipt,
      owner: owner,
      revision: owner._requestedRestartRevision + 1,
    );
    try {
      return await _restartCoreAfterAuthorization();
    } finally {
      if (identical(_lanRestart?.receipt, receipt)) _lanRestart = null;
    }
  }

  @protected
  Future<bool> setCoreParked() => _core.parkListener();

  @protected
  Future<bool> setCoreRunning(bool running) {
    return running ? _core.startListener() : _core.stopListener();
  }

  @protected
  Future<bool> adoptCoreRunning() => _core.adoptListener();

  @protected
  void resetCoreTraffic() {
    _core.resetTraffic();
  }

  @visibleForTesting
  Future<void> updateConfig() async {
    await _updateScheduler.run(_updateConfig);
  }

  Future<void> _updateConfig() async {
    final receipt = _lanEnableReceipt;
    final params = ref.read(updateParamsProvider);
    final applied = await globalState.safeRun(() async {
      final patchConfig = ref.read(patchClashConfigProvider);
      final shouldContinueSetup = await requestAdmin(patchConfig.tun.enable);
      if (!ref.mounted) return false;
      if (!shouldContinueSetup) {
        return receipt != null && identical(receipt, _lanEnableReceipt)
            ? _restartForLanEnable(receipt)
            : _restartCoreAfterAuthorization();
      }
      final networkSetting = ref.read(networkSettingProvider);
      Map<String, dynamic>? sourceProof;
      Config? sourceConfig;
      final sourceFilterIds = {
        for (final ids
            in ref
                    .read(currentProfileProvider)
                    ?.overrides
                    .filterBindings
                    .values ??
                <List<int>>[])
          ...ids,
      };
      if (ClientProduct.current.expectedCoreId == 'meow-rs') {
        await waitForDatabaseWrites();
        debouncer.cancel(FunctionTag.savePreferences);
        final storage = await preferences.sharedPreferences;
        if (storage == null)
          throw StateError('Shared data storage is unavailable');
        sourceConfig = ref.read(configProvider);
        sourceProof = await nativeLiteBusinessSource(
          database,
          Directory(await appPath.homeDirPath),
          storage,
          sourceConfig,
        );
        if (nativeLiteJsonHash(
              nativeLiteRuntimeConfig(sourceConfig!.toJson(), sourceFilterIds),
            ) !=
            nativeLiteJsonHash(
              nativeLiteRuntimeConfig(
                ref.read(configProvider).toJson(),
                sourceFilterIds,
              ),
            )) {
          throw StateError(
            'Shared configuration changed before the live update',
          );
        }
      }
      final update = _effectivePatchConfig(patchConfig).toUpdateParams(
        coreId: ClientProduct.current.expectedCoreId,
        authentication: networkSetting.authentication.credentials,
        skipCertVerify: !ref.read(appSettingProvider).checkCertificate,
      );
      final meow = ClientProduct.current.expectedCoreId == 'meow-rs';
      final wire = meow
          ? update.copyWith(geoXUrl: meowGlobalGeoDownloadUrls(patchConfig))
          : update;
      final geoGlobal = meow
          ? nativeLiteJsonHash({
              'auto-update': wire.geoAutoUpdate,
              'interval': wire.geoUpdateInterval,
              'url': wire.geoXUrl,
            })
          : null;
      if (meow) _cancelGeoRequest();
      final message = await _core.updateConfig(
        wire.copyWith(dataSourceProof: sourceProof),
      );
      if (message.isNotEmpty) throw MessageException(message);
      if (sourceConfig != null &&
          nativeLiteJsonHash(
                nativeLiteRuntimeConfig(sourceConfig.toJson(), sourceFilterIds),
              ) !=
              nativeLiteJsonHash(
                nativeLiteRuntimeConfig(
                  ref.read(configProvider).toJson(),
                  sourceFilterIds,
                ),
              )) {
        final storage = await preferences.sharedPreferences;
        if (storage != null)
          await nativeLiteBusinessSource(
            database,
            Directory(await appPath.homeDirPath),
            storage,
            ref.read(configProvider),
          );
        throw StateError('Shared configuration changed during the live update');
      }
      if (meow &&
          geoGlobal != _lastGeoGlobal &&
          ref.read(coreStatusProvider) == CoreStatus.connected) {
        await updateMeowGeo(
          'automatic',
          isCurrent: () =>
              ref.mounted && params == ref.read(updateParamsProvider),
        );
      }
      _lastGeoGlobal = geoGlobal;
      return true;
    });
    if (receipt != null && identical(receipt, _lanEnableReceipt)) {
      _finishLanEnable(
        applied == true &&
            ref.mounted &&
            params == ref.read(updateParamsProvider) &&
            ref.read(patchClashConfigProvider).allowLan,
      );
    }
  }

  void applyProfileDebounce({bool silence = false, bool force = false}) {
    _pendingForceApply |= force;
    debouncer.call(FunctionTag.applyProfile, (silence) {
      final force = _pendingForceApply;
      _pendingForceApply = false;
      applyProfile(silence: silence, force: force);
    }, args: [silence]);
  }

  void changeMode(Mode mode) {
    ref
        .read(patchClashConfigProvider.notifier)
        .update((state) => state.copyWith(mode: mode));
    if (mode == Mode.global) {
      ref
          .read(proxiesActionProvider.notifier)
          .updateCurrentGroupName(GroupName.GLOBAL.name);
    }
  }

  /// Steps from the saved mode, so quick taps each move one step.
  void cycleMode() {
    final current = ref.read(patchClashConfigProvider).mode;
    changeMode(Mode.values[(current.index + 1) % Mode.values.length]);
  }

  /// For leaving an editor: a profile whose inputs match the config the Core
  /// already runs is not rebuilt, which on a large profile takes a while.
  void autoApplyProfile() {
    runAfterFrame(() async {
      if (!await _inputsApplied()) {
        await applyProfile();
      }
    });
  }

  void applyIfGlobalRulesUsed() {
    final profile = ref.read(currentProfileProvider);
    if (profile == null ||
        profile.type == ProfileType.custom ||
        profile.extendType != ExtendType.standard) {
      return;
    }
    applyProfileDebounce(silence: true);
  }

  Future<bool> _inputsApplied() async {
    final applied = _appliedInputs;
    if (applied == null) return false;
    final (state, patch, network, params) = applied;
    return (state, patch, network, params.copyWith(dataSourceProof: null)) ==
        await _currentInputs();
  }

  Future<_SetupInputs?> _currentInputs() async {
    final profileId = ref.read(currentProfileIdProvider);
    final SetupState setupState;
    try {
      setupState = await ref.read(setupStateProvider(profileId).future);
    } catch (_) {
      return null;
    }
    return (
      setupState,
      _effectivePatchConfig(ref.read(patchClashConfigProvider)),
      ref.read(networkSettingProvider),
      _appliedParams,
    );
  }

  Future<String?> _inputsDigest(_SetupInputs inputs) async {
    final (setupState, patchConfig, networkSetting, params) = inputs;
    try {
      return await setupInputsDigestTask((
        setupState: setupState,
        setupPatch: patchConfig.setupOnly,
        appendSystemDns: networkSetting.appendSystemDns,
        bypassPrivateRoute: networkSetting.bypassPrivateRoute,
        testUrl: params.testUrl,
        skipCertVerify: params.skipCertVerify,
        safeMode: ref.read(safeModeProvider),
        build: globalState.packageInfo.buildNumber,
      ), coreId: ClientProduct.current.expectedCoreId);
    } catch (e, s) {
      commonPrint.log(
        'setup digest ===> ${compactError(e)}, $s',
        logLevel: LogLevel.warning,
      );
      return null;
    }
  }

  Future<SetupRecord?> _setupRecord(
    _SetupInputs? inputs,
    BuiltProfile profile,
  ) async {
    final digest = inputs == null ? null : await _inputsDigest(inputs);
    if (digest == null) {
      return null;
    }
    return SetupRecord(
      digest: digest,
      yamlMd5: profile.md5,
      groups: profile.counts.groups,
      proxies: profile.counts.proxies,
      rules: profile.counts.rules,
    );
  }

  /// Go records a setup only when it applies one, so inputs that moved without
  /// moving the YAML reach it here, or every reopen would rebuild the profile.
  Future<bool> _refreshRecord(
    _SetupInputs inputs,
    BuiltProfile profile, {
    bool Function()? isCurrent,
  }) async {
    final record = await _setupRecord(inputs, profile);
    if (record == null || (isCurrent != null && !isCurrent())) return false;
    final (_, _, _, params) = inputs;
    final effective = ClientProduct.current.expectedCoreId == 'meow-rs'
        ? params.copyWith(
            selectedMap: inputs.$1.profileId == null
                ? const {}
                : Map.of(ref.read(selectedMapProvider)),
          )
        : params;
    try {
      final admitted =
          ClientProduct.current.expectedCoreId == 'meow-rs' &&
              inputs.$1.profileId != null
          ? profile.selectionPolicy!.consumeSql(
              effective,
              profileId: inputs.$1.profileId,
            )
          : effective;
      return await _core.refreshAppliedSetup(
        admitted.copyWith(applied: record),
      );
    } catch (e) {
      commonPrint.log(
        'refreshAppliedSetup ===> ${compactError(e)}',
        logLevel: coreFailureLogLevel(e),
      );
      return false;
    }
  }

  /// [globalState.safeRun] shows the failure; the caller still needs it to
  /// roll the start back.
  Future<void> _runSurfaced(Future<void> Function() task) async {
    (Object, StackTrace)? failure;
    await globalState.safeRun(() async {
      try {
        await task();
      } catch (e, s) {
        failure = (e, s);
        rethrow;
      }
    });
    if (failure case (final error, final stackTrace)?) {
      Error.throwWithStackTrace(error, stackTrace);
    }
  }

  // Go retains its empty-config fallback for build failures. Meow requires a
  // built profile and a complete applied record before touching the active config.
  // authorizeCore failures still throw; build failures keep the bool API contract.
  Future<bool> applyProfile({
    bool silence = false,
    bool force = false,
    Future<void> Function()? preloadInvoke,
  }) async {
    final result = await _runSetup(
      force: force,
      silence: silence,
      preloadInvoke: preloadInvoke,
    );
    return result != _SetupTaskResult.failed;
  }

  Future<bool> refreshSelectionMetadata({
    required int profileId,
    required String groupName,
    required String proxyName,
    required bool Function() isCurrent,
  }) async {
    if (ClientProduct.current.expectedCoreId != 'meow-rs') return false;
    bool current() =>
        ref.mounted &&
        isCurrent() &&
        ref.read(coreStatusProvider) == CoreStatus.connected &&
        ref.read(currentProfileProvider)?.id == profileId &&
        ref.read(currentProfileProvider)?.selectedMap[groupName] == proxyName;
    if (!current()) return false;
    final result = await _setupScheduler.run(
      () => _setupConfig(
        silence: true,
        metadataOnly: true,
        metadataIsCurrent: current,
      ),
    );
    return result == _SetupTaskResult.completed;
  }

  /// Provider replacement follows the same source producer and applied owner as setup.
  Future<void> updateMeowProvider({
    required String namespace,
    required String name,
    required String? expectedRuntimeRevision,
    String? replacementData,
    Uint8List? replacementBytes,
    bool Function()? isCurrent,
  }) async {
    if (ClientProduct.current.expectedCoreId != 'meow-rs')
      throw StateError('Meow provider operation requires Meow');
    if (!{'proxy-providers', 'rule-providers'}.contains(namespace) ||
        name.isEmpty) {
      throw const FormatException('provider: invalid namespace or name');
    }
    if (expectedRuntimeRevision == null || expectedRuntimeRevision.isEmpty) {
      throw const CoreMethodException(
        code: 'superseded',
        message: 'Provider selection has no captured runtime revision',
      );
    }
    if ((replacementData != null || replacementBytes != null) &&
        !identical(Zone.current[_providerSourceLease], this)) {
      return withProviderSourceWrite(
        () => updateMeowProvider(
          namespace: namespace,
          name: name,
          expectedRuntimeRevision: expectedRuntimeRevision,
          replacementData: replacementData,
          replacementBytes: replacementBytes,
          isCurrent: isCurrent,
        ),
      );
    }
    if (_providerSourceWriters != 0 &&
        !identical(Zone.current[_providerSourceLease], this))
      throw const UpdateCancelledException();
    final owner = _core;
    final revision = _runtimeRevision;
    final profileId = ref.read(currentProfileIdProvider);
    final cancellation = ValueNotifier(false);
    _providerCancellations.add(cancellation);
    final completion = Completer<void>();
    unawaited(
      completion.future.then<void>(
        (_) {},
        onError: (Object _, StackTrace __) {},
      ),
    );
    _providerCompletions[cancellation] = completion;
    CoreRequestQuiescence? nativeRequest;
    void Function()? closeSource;
    bool current() =>
        !cancellation.value &&
        ref.mounted &&
        identical(owner, _core) &&
        revision == _runtimeRevision &&
        ref.read(coreStatusProvider) == CoreStatus.connected &&
        ref.read(currentProfileIdProvider) == profileId &&
        (isCurrent?.call() ?? true);
    try {
      var observedSource = ref.read(setupStateProvider(profileId)).value;
      final ownedSourceStates = <SetupState>{};
      final sourceSubscription = ref.listen(setupStateProvider(profileId), (
        _,
        next,
      ) {
        if (next.hasError) cancellation.value = true;
        if (next.value case final source?) {
          if (observedSource != null &&
              observedSource != source &&
              !ownedSourceStates.contains(source))
            cancellation.value = true;
          observedSource = source;
        }
      });
      closeSource = sourceSubscription.close;
      final before = await owner.readMeowProviderApplied();
      if (before.revision != expectedRuntimeRevision) {
        throw const CoreMethodException(
          code: 'superseded',
          message: 'Provider selection belongs to another runtime',
        );
      }
      checkUpdateCurrent(current);
      if (replacementData != null || replacementBytes != null) {
        await _withProviderSourceWrite(
          () => _publishManagedProviderSource(
            namespace: namespace,
            name: name,
            profileId: profileId,
            before: before,
            owner: owner,
            cancellation: cancellation,
            current: current,
            bytes:
                replacementBytes ??
                Uint8List.fromList(utf8.encode(replacementData!)),
            observeQuiescence: (state) => nativeRequest = state,
            allowOwnedSource: ownedSourceStates.add,
          ),
          operation: cancellation,
        );
        return;
      }
      final update = <String, dynamic>{
        'namespace': namespace,
        'name': name,
        'expected': before.applied.toJson(),
        'expectedRevision': expectedRuntimeRevision,
      };
      await _setupScheduler.run(() async {
        checkUpdateCurrent(current);
        final result = await _setupConfig(
          force: true,
          silence: true,
          providerUpdate: update,
          metadataIsCurrent: current,
          cancellation: cancellation,
          observeQuiescence: (state) => nativeRequest = state,
          providerApplied: (record) async {
            checkUpdateCurrent(current);
            final actual = await owner.readMeowProviderApplied();
            checkUpdateCurrent(current);
            if (actual.revision == before.revision ||
                actual.applied != record) {
              throw const CoreMethodException(
                code: 'runtime_receipt_mismatch',
                message:
                    'Provider update has no new actual applied runtime receipt',
              );
            }
          },
          onUpdated: () async {
            await ref.read(proxiesActionProvider.notifier).updateGroups();
            await ref.read(providersProvider.notifier).syncProviders();
          },
        );
        if (result != _SetupTaskResult.completed) {
          throw const CoreMethodException(
            code: 'provider_update_failed',
            message: 'Provider runtime update did not commit',
          );
        }
      });
    } finally {
      closeSource?.call();
      _providerCancellations.remove(cancellation);
      {
        void release() {
          _providerCompletions.remove(cancellation);
          if (!completion.isCompleted) completion.complete();
          cancellation.dispose();
        }

        final native = nativeRequest;
        if (native == null || native.isQuiescent)
          release();
        else {
          completion.completeError(
            const CoreMethodException(
              code: 'termination_unconfirmed',
              message: 'Provider cancellation has not been confirmed',
            ),
          );
          unawaited(native.whenQuiescent.then((_) => release()));
        }
      }
    }
  }

  Future<void> _publishManagedProviderSource({
    required String namespace,
    required String name,
    required int? profileId,
    required ({SetupRecord applied, String revision}) before,
    required CoreController owner,
    required ValueNotifier<bool> cancellation,
    required bool Function() current,
    required Uint8List bytes,
    required void Function(CoreRequestQuiescence) observeQuiescence,
    required void Function(SetupState) allowOwnedSource,
  }) async {
    if (profileId == null)
      throw const FormatException(
        'provider source: selected profile is required',
      );
    await _setupScheduler.run(() async {
      checkUpdateCurrent(current);
      final state = await ref.read(setupStateProvider(profileId).future);
      final key = '$namespace/$name';
      final previousBinding = state.overrides.localProviderSources[key];
      final nativeInput =
          state.overrides.nativeResourceInputs['\$.$namespace.$name'];
      final referencedProfile = namespace == 'proxy-providers'
          ? state.profileProviders[name]
          : null;
      final detached =
          previousBinding != null ||
          nativeInput != null ||
          referencedProfile != null;
      final sqlSource =
          namespace == 'rule-providers' &&
          previousBinding == null &&
          (state.overrides.ruleProviderBindings.containsKey(name) ||
              !state.overrides.sourceRuleProviders.containsKey(name) &&
                  state.clashProviders.any(
                    (provider) => provider.label == name,
                  ));
      if (sqlSource) {
        throw const CoreMethodException(
          code: 'source_owner_mismatch',
          message:
              'Provider source requires its independently owned publication transaction',
        );
      }
      final config = state.profileType == ProfileType.custom
          ? <String, dynamic>{
              'proxy-providers': state.overrides.sourceProxyProviders,
              'rule-providers': state.overrides.sourceRuleProviders,
            }
          : jsonDecode(await owner.getConfigJson(profileId)) as Map;
      final declaration = (config[namespace] as Map?)?[name];
      final home = Directory(
        await Directory(await appPath.homeDirPath).resolveSymbolicLinks(),
      );
      final relative = fileProviderSourcePath(profileId, namespace, name);
      Map<String, dynamic>? originalDefinition;
      if (previousBinding != null)
        originalDefinition = previousBinding.originalDefinition;
      else if (nativeInput?['sourceDefinition'] case final Map original)
        originalDefinition = Map<String, dynamic>.from(original);
      else if (referencedProfile != null)
        originalDefinition = {
          ...?state.overrides.proxyProviders[name]?.definition,
          'type': 'file',
          'path': await appPath.getProfilePath(referencedProfile.toString()),
        };
      if (detached && originalDefinition == null) {
        throw const CoreMethodException(
          code: 'source_owner_mismatch',
          message: 'Provider has no verifiable original business declaration',
        );
      }
      if (!detached && (declaration is! Map || declaration['type'] != 'file')) {
        throw const CoreMethodException(
          code: 'source_owner_mismatch',
          message:
              'Provider does not belong to the selected managed file source',
        );
      }
      if (!detached &&
          !(await fileProviderSourceBackupEntriesTask(home.path, [
            profileId,
          ])).contains(relative)) {
        throw const CoreMethodException(
          code: 'source_owner_mismatch',
          message:
              'Managed provider source has no validated source registration',
        );
      }
      checkUpdateCurrent(current);
      final proof = await _providerBusinessProof(saveCurrent: true);
      final journal = detached
          ? await FileProviderSourceJournal.prepareDetached(
              database: database,
              home: home,
              id: uuidV4,
              profileId: profileId,
              namespace: namespace,
              name: name,
              expectedRevision: before.revision,
              expected: before.applied.toJson(),
              beforeProof: proof,
              bytes: bytes,
              originalDefinition: originalDefinition!,
              immutableInputs: state.overrides.nativeResourceInputs.values
                  .expand((input) => [input['file'], input['receiptFile']])
                  .whereType<String>(),
            )
          : await FileProviderSourceJournal.prepare(
              home: home,
              id: uuidV4,
              profileId: profileId,
              namespace: namespace,
              name: name,
              expectedRevision: before.revision,
              expected: before.applied.toJson(),
              beforeProof: proof,
              bytes: bytes,
            );
      if (detached) {
        final afterRow =
            (journal.sourcePlan['profileRow'] as Map)['after'] as Map;
        final afterOverrides = ProfileOverrides.fromJson(
          Map<String, dynamic>.from(
            jsonDecode(afterRow['overrides'] as String) as Map,
          ),
        );
        allowOwnedSource(state);
        allowOwnedSource(
          state.copyWith(
            overrides: state.overrides.copyWith(
              localProviderSources: afterOverrides.localProviderSources,
            ),
          ),
        );
      }
      var beginAttempted = false;
      try {
        checkUpdateCurrent(current);
        beginAttempted = true;
        final prepared = await owner.invokeMeowProviderSource(
          journal.beginRequest(proof),
          cancellation: cancellation,
          observeQuiescence: observeQuiescence,
        );
        journal.checkReply(prepared);
        if (prepared['state'] != 'PREPARED' ||
            prepared['decision'] != 'UNDECIDED') {
          throw const CoreMethodException(
            code: 'source_prepare_failed',
            message: 'Provider source preparation did not complete',
          );
        }
        checkUpdateCurrent(current);
        await journal.publish(database: database);
        if (detached) {
          ref.invalidate(profilesStreamProvider);
          await ref.read(profilesStreamProvider.future);
          ref.invalidate(setupStateProvider(profileId));
          await ref.read(setupStateProvider(profileId).future);
        }
        final result = await _setupConfig(
          force: true,
          silence: true,
          metadataIsCurrent: current,
          cancellation: cancellation,
          providerSourceApply: (params) async {
            checkUpdateCurrent(current);
            final reply = await owner.invokeMeowProviderSource(
              {
                ...journal.envelope('apply'),
                'publication': {
                  'sourceJournalSha256': journal.journalSha256,
                  'afterStateSha256': nativeLiteJsonHash(
                    params.dataSourceProof!,
                  ),
                },
                'setupParams': params.toJson(),
              },
              cancellation: cancellation,
              observeQuiescence: observeQuiescence,
            );
            journal.checkReply(reply);
            if (reply['decision'] != 'COMMITTED' ||
                reply['revision'] is! String ||
                reply['revision'] == before.revision ||
                reply['applied'] is! Map ||
                SetupRecord.fromJson(
                      Map<String, dynamic>.from(reply['applied'] as Map),
                    ) !=
                    params.applied) {
              throw const CoreMethodException(
                code: 'runtime_receipt_mismatch',
                message:
                    'Provider source has no matching actual committed runtime',
              );
            }
          },
          onUpdated: () async {
            await ref.read(proxiesActionProvider.notifier).updateGroups();
            await ref.read(providersProvider.notifier).syncProviders();
          },
        );
        if (result != _SetupTaskResult.completed)
          throw const CoreMethodException(
            code: 'provider_update_failed',
            message: 'Provider source runtime update did not commit',
          );
      } catch (_) {
        // Cancellation/transport errors are not a rollback decision. Query must
        // first join Native work and read its sole durable commit point.
        if (!beginAttempted)
          await journal.discardUnbegun(database: database);
        else
          await journal.recover(
            invoke: owner.invokeMeowProviderSource,
            readProof: _providerBusinessProof,
            database: database,
          );
        rethrow;
      }
      await journal.recover(
        invoke: owner.invokeMeowProviderSource,
        readProof: _providerBusinessProof,
        database: database,
      );
      checkUpdateCurrent(current);
    });
  }

  /// Runs Geo requests through the same profile and JS producer as setup.
  Future<void> updateMeowGeo(String type, {bool Function()? isCurrent}) async {
    if (ClientProduct.current.expectedCoreId != 'meow-rs') {
      throw StateError('The Geo producer is only available for Meow');
    }
    final revision = _geoRevision;
    final owner = _core;
    final profileId = ref.read(currentProfileIdProvider);
    final transactionId = uuidV4;
    bool current() =>
        ref.mounted &&
        revision == _geoRevision &&
        identical(owner, _core) &&
        ref.read(coreStatusProvider) == CoreStatus.connected &&
        ref.read(currentProfileIdProvider) == profileId &&
        (isCurrent?.call() ?? true);
    await _setupScheduler.run(() async {
      if (!current()) throw UpdateCancelledException();
      _geoTransaction = (core: owner, transactionId: transactionId);
      try {
        if (type == 'automatic') {
          final admission = await owner.updateGeoPrepared(
            MeowGeoRequest.admit(transactionId: transactionId),
          );
          if (!current()) throw UpdateCancelledException();
          if (admission.state == 'skipped') return;
          if (admission.state != 'needs-source') {
            throw StateError(
              admission.message ?? 'Geo maintenance admission failed',
            );
          }
        }
        final result = await _setupConfig(
          silence: true,
          geoType: type,
          geoTransactionId: transactionId,
          metadataIsCurrent: current,
        );
        if (result != _SetupTaskResult.completed) {
          throw StateError(
            'Geo transaction did not confirm the generated profile',
          );
        }
      } finally {
        if (_geoTransaction?.transactionId == transactionId)
          _geoTransaction = null;
      }
    });
  }

  Future<_SetupTaskResult> _runSetup({
    bool silence = false,
    bool force = false,
    Future<void> Function()? preloadInvoke,
  }) async {
    final result = await _setupScheduler.run(() {
      return _setupConfig(
        force: force,
        silence: silence,
        preloadInvoke: preloadInvoke,
        onUpdated: () async {
          await ref.read(proxiesActionProvider.notifier).updateGroups();
          await ref.read(providersProvider.notifier).syncProviders();
        },
      );
    });
    if (result != _SetupTaskResult.handoffToCoreRestart) {
      return result;
    }
    // Release the current serial task before restartCore reapplies the profile.
    final restarted = await _restartCoreAfterAuthorization();
    return restarted ? _SetupTaskResult.completed : _SetupTaskResult.failed;
  }

  Future<bool> _restartCoreAfterAuthorization() async {
    try {
      return await ref.read(coreActionProvider.notifier).restartCore();
    } catch (_) {
      ref.read(authorizedTunEnableProvider.notifier).value =
          TunAuthorizationState.none;
      rethrow;
    }
  }

  /// With a base, [BuiltProfile.yaml] is only the overlay the Core applies.
  Future<void> _cancelBuilderFilters() async {
    final pending = _builderFilterRequests.entries.toList();
    final joined = await Future.wait([
      for (final entry in pending) entry.value.cancelFilterMatch(entry.key),
    ]);
    if (joined.any((value) => !value))
      throw StateError('Builder filter cancellation did not join');
  }

  Future<List<ProxyGroup>> _resolveLibraryGroups(SetupState state) async {
    final overrides = state.overrides;
    if (overrides.filterBindings.isEmpty && overrides.iconBindings.isEmpty)
      return state.proxyGroups;
    final filters = ref.read(appSettingProvider).filters;
    final icons = await database.iconSetsDao.query().get();
    final names = state.appProxies.map((proxy) => proxy.name).toList();
    final groups = <ProxyGroup>[];
    for (var group in state.proxyGroups) {
      if (overrides.filterBindings[group.id] case final bindings?) {
        final patterns = <String>[];
        for (final id in bindings) {
          final matched = filters.where((filter) => filter.id == id).toList();
          if (matched.length != 1)
            throw StateError(
              'profile.overrides.filterBindings: linked filter is missing or ambiguous',
            );
          patterns.add(matched.single.regex);
        }
        final id = 'builder:${snowflake.id}';
        final core = _core;
        _builderFilterRequests[id] = core;
        try {
          final matched = await core.matchFilters(id, patterns, names);
          group = group.copyWith(
            proxies: {
              ...?overrides.filterManualMembers[group.id],
              for (final index in matched)
                if (names[index] != group.name) names[index],
            }.toList(),
          );
        } finally {
          _builderFilterRequests.remove(id);
        }
      }
      if (overrides.iconBindings[group.id] case final binding?) {
        final sets = icons.where((set) => set.id == binding['set']).toList();
        if (sets.length != 1 ||
            sets.single.icons
                    .where((icon) => icon.url == binding['url'])
                    .length !=
                1)
          throw StateError(
            'profile.overrides.iconBindings: linked icon is missing or ambiguous',
          );
        group = group.copyWith(icon: binding['url'] as String);
      }
      groups.add(group);
    }
    return groups;
  }

  Future<BuiltProfile> getProfile({
    required SetupState setupState,
    required PatchClashConfig patchConfig,
  }) async {
    final profileId = setupState.profileId;
    if (profileId == null &&
        ClientProduct.current.expectedCoreId != 'meow-rs') {
      return (
        yaml: '',
        md5: '',
        counts: configCountsOf(const {}),
        base: null,
        selectionPolicy: null,
        providerSourceWitness: const <String>[],
      );
    }
    final defaultUA = globalState.packageInfo.ua;
    final networkSetting = ref.read(
      networkSettingProvider.select(
        (state) => (
          appendSystemDns: state.appendSystemDns,
          bypassPrivateRoute: state.bypassPrivateRoute,
          authentication: state.authentication,
        ),
      ),
    );
    final appendSystemDns = networkSetting.appendSystemDns;
    final isCustom = setupState.profileType == ProfileType.custom;
    final scriptContent =
        profileId != null &&
            (!isCustom || setupState.overrides.applyExtensionToCustom) &&
            setupState.extendType == ExtendType.script
        ? await setupState.script?.content
        : null;
    final hasScript = scriptContent?.isNotEmpty == true;
    final materializeMeow = ClientProduct.current.expectedCoreId == 'meow-rs';
    final profileBase =
        profileId != null && !isCustom && !hasScript && !materializeMeow
        ? await _core.getProfileBase(profileId)
        : null;
    final resolvedGroups = isCustom
        ? await _resolveLibraryGroups(setupState)
        : <ProxyGroup>[];
    final rawConfig = isCustom
        ? <String, dynamic>{
            ...setupState.overrides.baseConfig,
            'proxy-groups': [
              for (final group in resolvedGroups) group.definition,
            ],
            'rules': [for (final rule in setupState.rules) rule.rawValue],
            'proxy-providers': setupState.overrides.sourceProxyProviders,
            'rule-providers': {
              for (final entry
                  in setupState.overrides.sourceRuleProviders.entries)
                entry.key: entry.value,
            },
            'proxies': appProxiesPayload(
              setupState.appProxies,
              dialers: setupState.proxyDialers,
              groups: resolvedGroups,
            ),
          }
        : profileBase?.base.config ??
              (materializeMeow && profileId != null
                  ? Map<String, dynamic>.from(
                      jsonDecode(await _core.getConfigJson(profileId)) as Map,
                    )
                  : <String, dynamic>{});
    final proxyGroups = resolvedGroups;
    final rules = isCustom ? setupState.rules : <Rule>[];
    final directory = await appPath.profilesPath;
    final injected = await _resolveInjectedProviders(
      setupState,
      proxyGroups: proxyGroups,
      rules: rules,
    );
    if (isCustom) {
      rawConfig['proxy-providers'] = {
        ...injected.proxies,
        ...setupState.overrides.sourceProxyProviders,
      };
      rawConfig['rule-providers'] = {
        ...injected.rules,
        for (final entry in setupState.overrides.sourceRuleProviders.entries)
          entry.key: entry.value,
      };
    }
    if (hasScript && setupState.overrides.localProviderSources.isNotEmpty) {
      rawConfig.addAll(
        await applyLocalProviderSourcesTask(
          rawConfig,
          profileId: profileId,
          homeDirPath: await appPath.homeDirPath,
          bindings: setupState.overrides.localProviderSources,
          coreId: ClientProduct.current.expectedCoreId,
        ),
      );
    }
    final scriptOutput = profileId != null && hasScript
        ? await handleEvaluate(
            scriptContent!,
            isCustom
                ? nativeProxyProviderScriptInput(
                    ClientProduct.current.expectedCoreId == 'meow-rs'
                        ? meowDnsScriptInput(
                            jsonEncode(rawConfig),
                            overrides: setupState.overrides,
                          )
                        : jsonEncode(rawConfig),
                    setupState.overrides,
                  )
                : nativeProxyProviderScriptInput(
                    ClientProduct.current.expectedCoreId == 'meow-rs'
                        ? meowDnsScriptInput(
                            jsonEncode(rawConfig),
                            overrides: setupState.overrides,
                          )
                        : setupState.overrides.localProviderSources.isNotEmpty
                        ? jsonEncode(rawConfig)
                        : await _core.getConfigJson(profileId),
                    setupState.overrides,
                  ),
          )
        : null;
    final res = await makeRealProfileTask(
      MakeRealProfileState(
        rules: const [],
        proxyGroups: const [],
        injectedProxyProviders: isCustom && hasScript
            ? const {}
            : injected.proxies,
        injectedRuleProviders: isCustom && hasScript
            ? const {}
            : injected.rules,
        profilesPath: directory,
        profileId: profileId,
        rawConfig: rawConfig,
        rawConfigJson: scriptOutput,
        realPatchConfig: patchConfig,
        overrides: setupState.overrides,
        bypassPrivateRoute: networkSetting.bypassPrivateRoute,
        keepEmptyDnsOverrides: setupState.profileType != ProfileType.custom,
        appendSystemDns: appendSystemDns,
        addedRules: setupState.addedRules,
        defaultUA: defaultUA,
        authentication: networkSetting.authentication.credentials,
        matchTarget:
            profileBase == null ||
                setupState.matchTarget?.trim().isNotEmpty == true
            ? setupState.matchTarget
            : lastMatchTarget(profileBase.base.matchRules),
        safeMode: ref.read(safeModeProvider),
      ),
      coreId: ClientProduct.current.expectedCoreId,
      providerFileReferences: injected.files,
      selectionBinding:
          profileId != null &&
              ClientProduct.current.expectedCoreId == 'meow-rs' &&
              ref.read(profileProvider(profileId)) != null
          ? ProfileSelectionBinding(
              profileId,
              ref.read(profileProvider(profileId))!.selectedMap,
            )
          : null,
    );
    if (profileBase == null) {
      return (
        yaml: res.yaml,
        md5: res.md5,
        counts: res.counts,
        base: null,
        selectionPolicy: res.selectionPolicy,
        providerSourceWitness: res.providerSourceWitness,
      );
    }
    final (:base, :path) = profileBase;
    return (
      yaml: res.yaml,
      md5: '${res.md5}${base.digest}'.toMd5(),
      counts: (
        groups: res.counts.groups + base.proxyGroups,
        proxies: res.counts.proxies + base.proxies,
        rules: res.counts.rules + base.rules,
      ),
      base: SetupBase(path: path, overlay: res.yaml),
      selectionPolicy: res.selectionPolicy,
      providerSourceWitness: res.providerSourceWitness,
    );
  }

  /// Only what the custom profile actually references is injected, so an
  /// app-level provider costs nothing in the profiles that never name it.
  Future<
    ({
      Map<String, dynamic> proxies,
      Map<String, dynamic> rules,
      ({Map<String, String> proxies, Map<String, String> rules}) files,
    })
  >
  _resolveInjectedProviders(
    SetupState setupState, {
    required List<ProxyGroup> proxyGroups,
    required List<Rule> rules,
  }) async {
    final usedProxyProviders = <String>{
      for (final proxyGroup in proxyGroups) ...?proxyGroup.use,
    };
    final usedRuleProviders = <String>{
      for (final rule in rules) ...rule.ruleSets,
      ...setupState.overrides.ruleProviderBindings.keys,
      if (setupState.profileType == ProfileType.custom)
        ...setupState.overrides.ruleSets,
    };
    if (usedProxyProviders.isEmpty && usedRuleProviders.isEmpty) {
      return (
        proxies: const <String, dynamic>{},
        rules: const <String, dynamic>{},
        files: (
          proxies: const <String, String>{},
          rules: const <String, String>{},
        ),
      );
    }
    final options = setupState.overrides.proxyProviders;
    final proxies = <String, dynamic>{};
    final proxyFiles = <String, String>{};
    final ruleFiles = <String, String>{};
    for (final entry in setupState.profileProviders.entries) {
      if (!usedProxyProviders.contains(entry.key) ||
          setupState.overrides.localProviderSources.containsKey(
            'proxy-providers/${entry.key}',
          )) {
        continue;
      }
      final path = await appPath.getProfilePath(entry.value.toString());
      proxyFiles[entry.key] = path;
      proxies[entry.key] = {
        'type': 'file',
        'path': path,
        ...?options[entry.key]?.definition,
      };
    }
    final clashProvidersAction = ref.read(
      clashProvidersActionProvider.notifier,
    );
    final ruleProviders = <String, dynamic>{};
    for (final provider in setupState.clashProviders) {
      final aliases = [
        for (final entry in setupState.overrides.ruleProviderBindings.entries)
          if (entry.value == provider.id) entry.key,
      ];
      final labels = (aliases.isEmpty ? [provider.label] : aliases)
          .where(
            (label) => !setupState.overrides.localProviderSources.containsKey(
              'rule-providers/$label',
            ),
          )
          .toList();
      if (labels.isEmpty) continue;
      if (aliases.isEmpty &&
          setupState.overrides.sourceRuleProviders.containsKey(provider.label))
        continue;
      if (!usedRuleProviders.contains(provider.label) &&
          !aliases.any(usedRuleProviders.contains)) {
        continue;
      }
      final prepared = await clashProvidersAction.prepare(provider);
      final path = await prepared.corePath(
        ClientProduct.current.expectedCoreId,
      );
      final filePath = prepared.sourceDefinition != null
          ? await prepared.path
          : path;
      for (final label in labels) {
        ruleProviders[label] = prepared.definition(
          path,
          coreId: ClientProduct.current.expectedCoreId,
        );
        ruleFiles[label] = filePath;
      }
    }
    return (
      proxies: proxies,
      rules: ruleProviders,
      files: (proxies: proxyFiles, rules: ruleFiles),
    );
  }

  Future<String> getProfileWithId(int profileId) async {
    try {
      final setupState = await ref.read(setupStateProvider(profileId).future);
      final patchClashConfig = ref.read(patchClashConfigProvider);
      final res = await getProfile(
        setupState: setupState,
        patchConfig: patchClashConfig,
      );
      return switch (res.base) {
        final base? => await _core.buildProfile(base),
        null => res.yaml,
      };
    } catch (e) {
      dialogs.showNotifier(e.toString(), level: MessageLevel.error);
    }
    return '';
  }

  PatchClashConfig _effectivePatchConfig(PatchClashConfig patchConfig) {
    if (ref.read(safeModeProvider)) {
      return patchConfig.copyWith(
        tun: patchConfig.tun.copyWith(enable: false),
        externalController: ExternalControllerStatus.close,
      );
    }
    final authorized =
        ref.read(authorizedTunEnableProvider) ==
        TunAuthorizationState.authorized;
    return patchConfig.copyWith(
      tun: patchConfig.tun.copyWith(
        enable: patchConfig.tun.enable && authorized,
      ),
      externalController: patchConfig.secret.isEmpty
          ? ExternalControllerStatus.close
          : patchConfig.externalController,
    );
  }

  @protected
  Future<AuthorizeCode> authorizeCore() {
    return system.authorizeCore();
  }

  @visibleForTesting
  Future<bool> requestAdmin(bool enableTun) async {
    if (!enableTun || ref.read(safeModeProvider)) {
      return true;
    }
    final authorizationState = ref.read(authorizedTunEnableProvider);
    if (authorizationState != TunAuthorizationState.none) {
      return true;
    }

    final authorizationNotifier = ref.read(
      authorizedTunEnableProvider.notifier,
    );
    authorizationNotifier.value = TunAuthorizationState.unauthorized;

    final code = await authorizeCore();

    switch (code) {
      case AuthorizeCode.success:
        authorizationNotifier.value = TunAuthorizationState.authorized;
        return false;
      case AuthorizeCode.none:
        authorizationNotifier.value = TunAuthorizationState.authorized;
        return true;
      case AuthorizeCode.error:
        authorizationNotifier.value = TunAuthorizationState.none;
        ref
            .read(patchClashConfigProvider.notifier)
            .update((state) => state.copyWith.tun(enable: false));
        dialogs.showNotifier(
          currentAppLocalizations.tunAuthorizationFailed,
          level: MessageLevel.error,
        );
        return true;
    }
  }

  /// An empty profile list is left alone: it is the first-run state, and it is
  /// what the profile stream holds before its first emission.
  @visibleForTesting
  Profile? recoverMissingProfile() {
    final profileId = ref.read(currentProfileIdProvider);
    if (profileId == null) return null;
    final profiles = ref.read(profilesProvider);
    if (profiles.isEmpty) return null;
    final fallback = profiles.first;
    commonPrint.log(
      'profile $profileId is missing, falling back to ${fallback.id}',
      logLevel: LogLevel.warning,
    );
    ref.read(currentProfileIdProvider.notifier).value = fallback.id;
    return fallback;
  }

  Future<_SetupTaskResult> _setupConfig({
    bool force = false,
    bool silence = false,
    Future<void> Function()? preloadInvoke,
    FutureOr Function()? onUpdated,
    bool metadataOnly = false,
    String? geoType,
    String? geoTransactionId,
    Map<String, dynamic>? providerUpdate,
    Future<void> Function(SetupParams params)? providerSourceApply,
    Future<void> Function(SetupRecord record)? providerApplied,
    ValueListenable<bool>? cancellation,
    void Function(CoreRequestQuiescence state)? observeQuiescence,
    bool Function()? metadataIsCurrent,
  }) async {
    final producerOnly =
        metadataOnly ||
        geoType != null ||
        providerUpdate != null ||
        providerSourceApply != null;
    if (ClientProduct.current.expectedCoreId == 'meow-rs' && !producerOnly) {
      await _recoverProviderSources();
    }
    var profile = ref.read(currentProfileProvider);
    if (producerOnly &&
        (profile == null || !(metadataIsCurrent?.call() ?? false)))
      return _SetupTaskResult.failed;
    profile ??= recoverMissingProfile();
    // A refresh failure is surfaced by safeRun; setup keeps the old profile.
    final nextProfile = producerOnly
        ? null
        : await globalState.safeRun(
            () => profile?.checkAndUpdateAndCopy(
              validate: (path, key) => _core.validateProfile(path, key),
            ),
          );
    if (nextProfile != null) {
      profile = nextProfile;
      ref.read(profilesProvider.notifier).put(nextProfile);
    }
    commonPrint.log('setup ===> ${profile?.realLabel}');
    final patchConfig = ref.read(patchClashConfigProvider);
    final shouldContinueSetup =
        producerOnly || await requestAdmin(patchConfig.tun.enable);
    if (!shouldContinueSetup) {
      return _SetupTaskResult.handoffToCoreRestart;
    }
    final bindSharedSource = ClientProduct.current.expectedCoreId == 'meow-rs';
    Map<String, dynamic>? dataSourceProof;
    Config? businessConfig;
    var businessFilterIds = <int>{};

    if (bindSharedSource) {
      final dependencies = await ref.read(
        setupStateProvider(profile?.id).future,
      );
      if (dependencies.profileType == ProfileType.custom) {
        await _resolveInjectedProviders(
          dependencies,
          proxyGroups: dependencies.proxyGroups,
          rules: dependencies.rules,
        );
        ref.invalidate(setupStateProvider(profile?.id));
      }
      await waitForDatabaseWrites();

      businessConfig = ref.read(configProvider);
      businessFilterIds = {
        for (final ids
            in ref
                    .read(currentProfileProvider)
                    ?.overrides
                    .filterBindings
                    .values ??
                <List<int>>[])
          ...ids,
      };
      if (businessConfig!.patchClashConfig != patchConfig)
        throw StateError(
          'Shared core settings changed while preparing dependencies',
        );
      if (businessConfig.currentProfileId != profile?.id)
        throw StateError(
          'Shared profile selection changed while preparing the runtime',
        );
      debouncer.cancel(FunctionTag.savePreferences);
      final storage = await preferences.sharedPreferences;
      if (storage == null)
        throw StateError('Shared data storage is unavailable');
      dataSourceProof = await nativeLiteBusinessSource(
        database,
        Directory(await appPath.homeDirPath),
        storage,
        businessConfig,
      );
    }
    final realPatchConfig = _effectivePatchConfig(patchConfig);
    final networkSetting = ref.read(networkSettingProvider);
    SetupState? setupState;
    final realProfile = await globalState.safeRun(() async {
      final state = await ref.read(setupStateProvider(profile?.id).future);
      setupState = state;
      return getProfile(setupState: state, patchConfig: realPatchConfig);
    }, title: 'build profile');
    final profileFailed = realProfile == null;
    if (bindSharedSource && profileFailed) return _SetupTaskResult.failed;
    if (bindSharedSource) {
      await waitForDatabaseWrites();
      final storage = await preferences.sharedPreferences;
      final readBack = storage == null
          ? null
          : await nativeLiteBusinessSource(
              database,
              Directory(await appPath.homeDirPath),
              storage,
              null,
            );
      if (nativeLiteJsonHash(
                nativeLiteRuntimeConfig(
                  businessConfig!.toJson(),
                  businessFilterIds,
                ),
              ) !=
              nativeLiteJsonHash(
                nativeLiteRuntimeConfig(
                  ref.read(configProvider).toJson(),
                  businessFilterIds,
                ),
              ) ||
          readBack == null) {
        throw StateError(
          'Shared configuration changed while preparing the runtime',
        );
      }
      dataSourceProof = admitProviderSourceRegistration(
        dataSourceProof!,
        readBack,
        realProfile!.providerSourceWitness,
      );
    }
    var setupParams = _setupParams.copyWith(
      base: realProfile?.base,
      dataSourceProof: dataSourceProof,
    );
    if (bindSharedSource && realProfile != null) {
      setupParams = profile == null
          ? setupParams.copyWith(selectedMap: const {})
          : realProfile.selectionPolicy!.consumeSql(
              setupParams,
              profileId: profile.id,
            );
    }
    final inputs = switch (setupState) {
      final state? when !profileFailed => (
        state,
        realPatchConfig,
        networkSetting,
        _appliedParams.copyWith(dataSourceProof: dataSourceProof),
      ),
      _ => null,
    };
    final yamlString = realProfile?.yaml ?? '';
    final yamlMd5 = realProfile?.md5 ?? '';
    if (producerOnly && !(metadataIsCurrent?.call() ?? false))
      return _SetupTaskResult.failed;
    if (geoType != null) {
      if (!bindSharedSource ||
          realProfile == null ||
          inputs == null ||
          geoTransactionId == null) {
        return _SetupTaskResult.failed;
      }
      final record = await _setupRecord(inputs, realProfile);
      if (record == null || !(metadataIsCurrent?.call() ?? false))
        return _SetupTaskResult.failed;
      final body = realProfile.base == null
          ? realProfile.yaml
          : await _core.buildProfile(realProfile.base!);
      if (!(metadataIsCurrent?.call() ?? false)) return _SetupTaskResult.failed;
      await waitForDatabaseWrites();
      final storage = await preferences.sharedPreferences;
      final actualProof = storage == null
          ? null
          : await nativeLiteBusinessSource(
              database,
              Directory(await appPath.homeDirPath),
              storage,
              null,
            );
      if (jsonEncode(actualProof) != jsonEncode(dataSourceProof)) {
        throw StateError(
          'Shared source changed while rendering the Geo producer body',
        );
      }
      final source = await compute(nativeLiteWriteGeoSource, (
        home: await appPath.homeDirPath,
        transactionId: geoTransactionId,
        body: body,
      ));
      final owner = _core;
      try {
        if (!(metadataIsCurrent?.call() ?? false))
          return _SetupTaskResult.failed;
        MeowGeoReply reply;
        try {
          reply = await owner.updateGeoPrepared(
            MeowGeoRequest.update(
              transactionId: geoTransactionId,
              type: geoType,
              source: source,
              setupParams: setupParams.copyWith(applied: record),
            ),
          );
        } catch (_) {
          // A lost observer is not evidence that the Core rolled back.
          reply = await owner.updateGeoPrepared(
            MeowGeoRequest.status(transactionId: geoTransactionId),
          );
        }
        if (reply.state != 'committed' ||
            !reply.active ||
            reply.applied != record ||
            reply.producerSha256 != source.sha256 ||
            reply.compiledSha256 == null) {
          throw StateError(
            reply.message ?? 'Geo transaction has no matching applied receipt',
          );
        }
        if (!(metadataIsCurrent?.call() ?? false))
          return _SetupTaskResult.failed;
        final finalStorage = await preferences.sharedPreferences;
        final finalProof = finalStorage == null
            ? null
            : await nativeLiteBusinessSource(
                database,
                Directory(await appPath.homeDirPath),
                finalStorage,
                null,
              );
        if (jsonEncode(finalProof) != jsonEncode(dataSourceProof) ||
            !(metadataIsCurrent?.call() ?? false)) {
          return _SetupTaskResult.failed;
        }
        _appliedInputs = inputs;
        globalState.lastConfigMd5 = yamlMd5;
        _publishAppliedCounts(realProfile.counts);
        return _SetupTaskResult.completed;
      } finally {
        await File(
          source.path,
        ).delete().catchError((Object _) => File(source.path));
      }
    }
    if (realProfile != null && yamlMd5 == globalState.lastConfigMd5 && !force) {
      var refreshed = true;
      if (inputs != null && (metadataOnly || inputs != _appliedInputs))
        refreshed = await _refreshRecord(
          inputs,
          realProfile,
          isCurrent: metadataOnly ? metadataIsCurrent : null,
        );
      if (metadataOnly && !(metadataIsCurrent?.call() ?? false))
        return _SetupTaskResult.failed;
      if (!bindSharedSource || refreshed) _appliedInputs = inputs;
      if ((metadataOnly || bindSharedSource) && !refreshed)
        return _SetupTaskResult.failed;
      _publishAppliedCounts(realProfile.counts);
      if (preloadInvoke != null) {
        await _runSurfaced(preloadInvoke);
      }
      return _SetupTaskResult.completed;
    }
    if (metadataOnly) {
      commonPrint.log(
        'Selection metadata was not renewed because the actual generated YAML changed',
        logLevel: LogLevel.warning,
      );
      return _SetupTaskResult.failed;
    }
    final meowRecord = bindSharedSource
        ? await _setupRecord(inputs, realProfile!)
        : null;
    if (bindSharedSource && meowRecord == null) return _SetupTaskResult.failed;
    // Go answers a config it rejects by running its built-in default, which
    // has no proxies, so until this push succeeds no applied config is known.
    if (providerUpdate == null && providerSourceApply == null) {
      _appliedInputs = null;
      globalState.lastConfigMd5 = null;
      _publishAppliedCounts(null);
    }
    final record = bindSharedSource
        ? meowRecord
        : realProfile == null
        ? null
        : await _setupRecord(inputs, realProfile);
    if (system.isAndroid) {
      await ref.read(vpnOptionsProvider.future);
      await preferences.saveShareState(ref.read(sharedStateProvider));
    }
    // Recaptured so _start's catch can roll back after safeRun swallows it.
    (Object, StackTrace)? handoffFailure;
    var setupFailed = false;
    await globalState.loadingRun(
      () async {
        try {
          if (providerUpdate != null) checkUpdateCurrent(metadataIsCurrent);
          if (setupParams.base == null) {
            final configFilePath = await appPath.configFilePath;
            await File(configFilePath).writeAsStringAtomically(yamlString);
          }
          final profileId = profile?.id;
          if (profileId != null) {
            await appPath.ensureProviderDirs(profileId);
          }
          final message = providerSourceApply != null
              ? await (() async {
                  await providerSourceApply(
                    setupParams.copyWith(applied: record),
                  );
                  return '';
                })()
              : await _core.setupConfig(
                  params: setupParams.copyWith(
                    applied: record,
                    providerUpdate: providerUpdate,
                  ),
                  preloadInvoke: preloadInvoke,
                  cancellation: cancellation,
                  observeQuiescence: observeQuiescence,
                );
          if (message.isNotEmpty) {
            if (providerUpdate == null)
              _publishAppliedCounts(configCountsOf(const {}));
            await globalState.safeRun(() => onUpdated?.call());
            throw MessageException(message);
          }
          if (providerApplied != null) await providerApplied(record!);
        } catch (e, s) {
          setupFailed = true;
          if (preloadInvoke != null ||
              cancellation != null ||
              providerSourceApply != null) {
            handoffFailure = (e, s);
          }
          rethrow;
        }
        globalState.lastConfigMd5 = yamlMd5;
        _publishAppliedCounts(realProfile?.counts ?? configCountsOf(const {}));
        _appliedInputs = inputs;
        await onUpdated?.call();
      },
      silence: true,
      tag: !silence ? LoadingTag.proxies : null,
    );
    if (handoffFailure != null) {
      Error.throwWithStackTrace(handoffFailure!.$1, handoffFailure!.$2);
    }
    if (setupFailed || profileFailed) {
      return _SetupTaskResult.failed;
    }
    return _SetupTaskResult.completed;
  }
}
