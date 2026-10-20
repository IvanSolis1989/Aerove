import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/core/core.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/action.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/core.dart';
import 'package:fl_clash/providers/route_state.dart';
import 'package:fl_clash/providers/state.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class CoreManager extends ConsumerStatefulWidget {
  final Widget child;

  const CoreManager({super.key, required this.child});

  @override
  ConsumerState<CoreManager> createState() => _CoreContainerState();
}

class _CoreContainerState extends ConsumerState<CoreManager>
    with CoreEventListener {
  late final CoreController _core;
  final _loadedWhileHidden = <String, int>{};
  Future<void> _pendingUiSync = Future<void>.value();
  bool _syncingUi = false;
  bool _refreshingUi = false;
  bool _refreshAgain = false;
  bool _closed = false;
  bool _groupsNeedRefresh = false;
  String? _hiddenDialerLoop;
  int _groupsRevision = 0;
  int _providerRevision = 0;
  int _uiRevision = 0;

  bool get _uiVisible => !_closed && mounted && ref.read(appVisibleProvider);

  @override
  Widget build(BuildContext context) {
    return widget.child;
  }

  @override
  void initState() {
    super.initState();
    _core = ref.read(coreHandlerProvider);
    _groupsNeedRefresh = !ref.read(appVisibleProvider);
    coreEventManager.addListener(this);
    ref.read(updatingActionProvider.notifier);
    // A rejected profile stays selected on purpose: silently reverting to
    // the previous one hides the error and looks like the switch was lost.
    ref.listenManual(currentProfileIdProvider, (prev, next) {
      if (prev == next) return;
      runAfterFrame(() {
        unawaited(ref.read(setupActionProvider.notifier).fullSetup());
      });
    });
    ref.listenManual(updateParamsProvider, (prev, next) {
      if (prev != next) {
        ref.read(setupActionProvider.notifier).updateConfigDebounce();
      }
    });
    ref.listenManual(setupPatchProvider, (prev, next) {
      if (prev != next) {
        ref
            .read(setupActionProvider.notifier)
            .applyProfileDebounce(silence: true);
      }
    });
    ref.listenManual(appSettingProvider.select((state) => state.openLogs), (
      prev,
      next,
    ) {
      if (prev != next) {
        _syncLogStream();
      }
    });
    ref.listenManual(appVisibleProvider, (_, visible) {
      if (!visible) {
        _markGroupsDirty();
        _cancelUiWork();
        ref.read(proxiesActionProvider.notifier).cancelDelayTests();
      } else if (_hiddenDialerLoop case final proxyName?) {
        _hiddenDialerLoop = null;
        _notifyDialerLoop(proxyName);
      }
      _requestUiSync();
    });
    ref.listenManual(coreStatusProvider, (prev, next) {
      if (prev != next) {
        _requestUiSync();
      }
    }, fireImmediately: true);
  }

  void _requestUiSync() {
    if (_closed) return;
    _uiRevision++;
    _syncLogStream();
    if (!_syncingUi) {
      _pendingUiSync = _syncUiTelemetry();
      unawaited(_pendingUiSync);
    }
  }

  Future<void> _syncUiTelemetry() async {
    if (_syncingUi) return;
    _syncingUi = true;
    try {
      while (!_closed &&
          mounted &&
          ref.read(coreStatusProvider) == CoreStatus.connected) {
        final revision = _uiRevision;
        final active = _uiVisible;
        try {
          await _core.setUiTelemetryActive(active);
          if (_closed || !mounted) return;
          if (revision != _uiRevision) continue;
          if (active) {
            _requestUiRefresh();
          }
        } catch (error) {
          commonPrint.log(
            'UI telemetry sync failed: $error',
            logLevel: coreFailureLogLevel(error),
          );
        }
        if (_closed || !mounted || revision == _uiRevision) return;
      }
    } finally {
      _syncingUi = false;
    }
  }

  void _markGroupsDirty() {
    _groupsNeedRefresh = true;
    _groupsRevision++;
  }

  void _requestUiRefresh() {
    if (!_uiVisible || ref.read(coreStatusProvider) != CoreStatus.connected) {
      return;
    }
    _refreshAgain = true;
    if (!_refreshingUi) unawaited(_drainUiData());
  }

  Future<void> _drainUiData() async {
    _refreshingUi = true;
    try {
      while (_refreshAgain &&
          _uiVisible &&
          ref.read(coreStatusProvider) == CoreStatus.connected) {
        _refreshAgain = false;
        await _refreshUiData(_uiRevision);
      }
    } catch (error) {
      commonPrint.log(
        'UI data refresh failed: $error',
        logLevel: coreFailureLogLevel(error),
      );
    } finally {
      _refreshingUi = false;
    }
  }

  Future<void> _refreshUiData(int revision) async {
    final pending = _loadedWhileHidden.entries.toList();
    try {
      // setProvider skips unlisted names; a Core that does not answer lists [].
      if (pending.length > 1 ||
          (pending.isNotEmpty && ref.read(providersProvider).isEmpty)) {
        final providers = await _core.getExternalProviders();
        if (!_uiVisible || revision != _uiRevision) return;
        if (providers.isEmpty && ref.read(providersProvider).isNotEmpty) {
          return;
        }
        ref.read(providersProvider.notifier).value = providers;
      } else if (pending.isNotEmpty) {
        final provider = await _core.getExternalProvider(pending.single.key);
        if (!_uiVisible || revision != _uiRevision) return;
        ref.read(providersProvider.notifier).setProvider(provider);
      }
      if (!_uiVisible || revision != _uiRevision) return;
      for (final entry in pending) {
        if (_loadedWhileHidden[entry.key] == entry.value) {
          _loadedWhileHidden.remove(entry.key);
        }
      }
    } catch (error) {
      commonPrint.log(
        'Provider refresh failed: $error',
        logLevel: coreFailureLogLevel(error),
      );
    }
    if (!_groupsNeedRefresh || !_uiVisible || revision != _uiRevision) return;
    final groupsRevision = _groupsRevision;
    final refreshed = await ref
        .read(proxiesActionProvider.notifier)
        .updateGroups();
    if (refreshed &&
        _uiVisible &&
        revision == _uiRevision &&
        groupsRevision == _groupsRevision) {
      _groupsNeedRefresh = false;
    }
  }

  void _cancelUiWork() {
    debouncer.cancel(FunctionTag.updateDelay);
    debouncer.cancel(FunctionTag.updateGroups);
    debouncer.cancel(FunctionTag.loadedProvider);
    throttler.cancel(FunctionTag.coreErrorNotifier);
  }

  void _syncLogStream() {
    if (ref.read(coreStatusProvider) != CoreStatus.connected) {
      return;
    }
    if (_uiVisible && ref.read(appSettingProvider).openLogs) {
      _core.startLog();
    } else {
      _core.stopLog();
    }
  }

  @override
  void dispose() {
    _closed = true;
    _uiRevision++;
    _cancelUiWork();
    coreEventManager.removeListener(this);
    try {
      _core.stopLog();
    } catch (error) {
      commonPrint.log('Log stream release failed: $error');
    }
    unawaited(_pendingUiSync.then((_) => _closeUiTelemetry()));
    super.dispose();
  }

  Future<void> _closeUiTelemetry() async {
    try {
      await _core.setUiTelemetryActive(false);
    } catch (error) {
      commonPrint.log('UI telemetry release failed: $error');
    } finally {
      try {
        _core.stopLog();
      } catch (error) {
        commonPrint.log('Log stream release failed: $error');
      }
    }
  }

  @override
  Future<void> onDelay(Delay delay) async {
    if (!_uiVisible) {
      _markGroupsDirty();
      return;
    }
    super.onDelay(delay);
    final proxiesAction = ref.read(proxiesActionProvider.notifier);
    proxiesAction.setDelay(delay);
    debouncer.call(FunctionTag.updateDelay, () async {
      if (!_uiVisible) return;
      proxiesAction.updateGroupsDebounce();
    }, duration: const Duration(milliseconds: 5000));
  }

  @override
  void onLog(Log log) {
    if (!_uiVisible) return;
    ref.read(logsProvider.notifier).add(log);
    if (log.logLevel == LogLevel.error) {
      throttler.call(
        FunctionTag.coreErrorNotifier,
        () => dialogs.showNotifier(log.payload, level: MessageLevel.error),
        duration: const Duration(seconds: 3),
        fire: true,
      );
    }
    super.onLog(log);
  }

  @override
  void onDialerLoop(String proxyName) {
    if (_uiVisible) {
      _notifyDialerLoop(proxyName);
    } else {
      _hiddenDialerLoop = proxyName;
    }
    super.onDialerLoop(proxyName);
  }

  void _notifyDialerLoop(String proxyName) {
    throttler.call(
      FunctionTag.dialerLoopNotifier,
      () => dialogs.showNotifier(
        currentAppLocalizations.dialerProxyLoopStopped(proxyName),
        level: MessageLevel.error,
      ),
      duration: const Duration(seconds: 3),
      fire: true,
    );
  }

  @override
  void onRequest(TrackerInfo trackerInfo) async {
    if (!_uiVisible) return;
    ref.read(requestsProvider.notifier).addRequest(trackerInfo);
    ref.read(requestCountProvider.notifier).update((count) => count + 1);
    super.onRequest(trackerInfo);
  }

  @override
  void onRequestTraffic(Map<String, RequestTraffic> traffic) {
    if (!_uiVisible) return;
    ref.read(requestsProvider.notifier).updateTraffic(traffic);
    super.onRequestTraffic(traffic);
  }

  @override
  void onDns(DnsQuery dnsQuery) {
    if (!_uiVisible) return;
    ref.read(dnsQueriesProvider.notifier).addQuery(dnsQuery);
    ref.read(dnsQueryCountProvider.notifier).update((count) => count + 1);
    super.onDns(dnsQuery);
  }

  @override
  Future<void> onLoaded(String providerName) async {
    if (_closed) return;
    _loadedWhileHidden[providerName] = ++_providerRevision;
    _markGroupsDirty();
    if (_uiVisible) {
      debouncer.call(
        FunctionTag.loadedProvider,
        _requestUiRefresh,
        duration: const Duration(milliseconds: 500),
      );
    }
    super.onLoaded(providerName);
  }

  @override
  Future<void> onCrash(String message) async {
    if (ref.read(coreStatusProvider) != CoreStatus.connected) {
      return;
    }
    ref.read(coreStatusProvider.notifier).value = CoreStatus.disconnected;
    if (WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed) {
      context.showNotifier(message, level: MessageLevel.error);
    }
    super.onCrash(message);
  }

  @override
  void onGeoUpdate(String geoType, bool updating, bool skipped, String? error) {
    ref
        .read(geoResourceActionProvider.notifier)
        .handleCoreUpdate(geoType, updating, skipped, error);
    super.onGeoUpdate(geoType, updating, skipped, error);
  }

  @override
  void onRouteChanged(RouteSnapshot snapshot) {
    ref.read(routeTrackerProvider.notifier).applySnapshot(snapshot);
    super.onRouteChanged(snapshot);
  }
}
