part of '../action.dart';

@Riverpod(keepAlive: true)
class SystemAction extends _$SystemAction {
  CoreController get _core => ref.read(coreHandlerProvider);

  SystemExitCoordinator? _exitCoordinator;
  bool _localIpStale = false;
  bool _localIpUpdating = false;
  Future<void>? _localIpTask;
  int _localIpGeneration = 0;

  @override
  void build() {
    ref.listen(appVisibleProvider, (_, visible) {
      if (!visible) {
        _localIpGeneration++;
      } else if (_localIpStale) {
        unawaited(_flushLocalIp());
      }
    });
  }

  Future<List<Package>> getPackages() async {
    ref.read(packagesProvider.notifier).value = await app?.getPackages() ?? [];
    return ref.read(packagesProvider);
  }

  Future<bool> isInstalledAppsPermissionGranted() async {
    return await app?.isInstalledAppsPermissionGranted() ?? true;
  }

  Future<bool> requestInstalledAppsPermission() async {
    return await app?.requestInstalledAppsPermission() ?? false;
  }

  Future<void> handleExit([bool needSave = true]) {
    final coordinator = _exitCoordinator ??= SystemExitCoordinator(
      watchdogDuration: exitWatchdogDuration,
      closeWindow: closeWindow,
      closeCore: closeCore,
      exitApplication: exitApplication,
    );
    return coordinator.exit(cleanup: () => cleanupExitResources(needSave));
  }

  @protected
  Duration get exitWatchdogDuration => const Duration(seconds: 3);

  @protected
  Future<void> cleanupExitResources(bool needSave) async {
    final saveOperation = needSave ? _savePreferencesSafely() : null;
    final tray = trayPort;
    if (tray != null) {
      await tray.shutdown().onError<Object>((error, stackTrace) {
        commonPrint.log(
          'Tray shutdown failed: ${compactError(error)}',
          logLevel: LogLevel.error,
        );
      });
    }
    await Future.wait([
      ?saveOperation,
      bootGuard.markClosed(),
      if (systemDnsCoordinator != null) systemDnsCoordinator!.shutdown(),
      ?systemProxyQueue?.close(),
    ]);
  }

  Future<void> _savePreferencesSafely() async {
    try {
      await savePreferences();
    } catch (error) {
      commonPrint.log(
        'Preferences save failed: ${compactError(error)}',
        logLevel: LogLevel.error,
      );
    }
  }

  @protected
  Future<void> savePreferences() async {
    final port = windowPort;
    if (port != null) {
      try {
        final current = ref.read(windowSettingProvider);
        final geometry = await port.captureNormalGeometry(current);
        if (geometry != null) {
          ref.read(windowSettingProvider.notifier).value = geometry;
        }
      } catch (error) {
        commonPrint.log(
          'Window geometry capture failed: ${compactError(error)}',
          logLevel: LogLevel.warning,
        );
      }
    }
    await preferences.saveConfig(ref.read(configProvider));
  }

  @protected
  Future<void> closeWindow() async {
    await windowPort?.close();
  }

  @protected
  Future<void> closeCore() async {
    await _core.close();
    commonPrint.log('exit');
  }

  @protected
  Future<void> exitApplication() async {
    await system.exit();
    windowPort?.forceExit();
  }

  Future<void> handleClose([bool exit = true]) async {
    if (ref.read(appSettingProvider).minimizeOnExit || !exit) {
      if (system.isDesktop) {
        await _savePreferencesSafely();
      }
      await system.back();
      await windowPort?.hide();
    } else {
      await handleExit();
    }
  }

  Future<void> updateVisible() async {
    await windowPort?.toggle();
  }

  void updateTun() {
    ref
        .read(patchClashConfigProvider.notifier)
        .update((state) => state.copyWith.tun(enable: !state.tun.enable));
  }

  void updateSystemProxy() {
    ref
        .read(networkSettingProvider.notifier)
        .update((state) => state.copyWith(systemProxy: !state.systemProxy));
  }

  Future<void> copyProxyEnv() async {
    final port = ref.read(
      patchClashConfigProvider.select((state) => state.mixedPort),
    );
    await Clipboard.setData(
      ClipboardData(text: proxyEnvCommand(port, isWindows: system.isWindows)),
    );
  }

  void updateAutoLaunch() {
    ref
        .read(appSettingProvider.notifier)
        .update((state) => state.copyWith(autoLaunch: !state.autoLaunch));
  }

  Future<void> updateTray() async {
    await trayPort?.update(
      trayState: ref.read(trayStateProvider),
      traffic: ref.read(
        trafficsProvider.select(
          (state) => state.list.safeLast(const Traffic()),
        ),
      ),
      read: globalState.container.read,
    );
  }

  Future<void> updateLocalIp() async {
    _localIpStale = true;
    await _flushLocalIp();
  }

  @protected
  Future<String?> readLocalIpAddress() => getLocalIpAddress(
    excludedInterface: ref.read(patchClashConfigProvider).tun.device,
  );

  Future<void> _flushLocalIp() =>
      _localIpTask ??= _readLocalIp().whenComplete(() async {
        _localIpTask = null;
        if (_localIpStale && ref.mounted && ref.read(appVisibleProvider)) {
          await _flushLocalIp();
        }
      });

  Future<void> _readLocalIp() async {
    if (_localIpUpdating || !ref.mounted || !ref.read(appVisibleProvider)) {
      return;
    }
    _localIpUpdating = true;
    try {
      while (_localIpStale && ref.mounted && ref.read(appVisibleProvider)) {
        _localIpStale = false;
        final generation = _localIpGeneration;
        bool isCurrent() =>
            ref.mounted &&
            ref.read(appVisibleProvider) &&
            generation == _localIpGeneration;
        ref.read(localIpProvider.notifier).value = null;
        await Future<void>.delayed(commonDuration);
        if (!isCurrent()) {
          _localIpStale = true;
          break;
        }
        final address = await readLocalIpAddress();
        if (!isCurrent()) {
          _localIpStale = true;
          break;
        }
        ref.read(localIpProvider.notifier).value = address;
      }
    } finally {
      _localIpUpdating = false;
    }
  }
}
