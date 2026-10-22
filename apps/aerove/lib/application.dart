import 'dart:async';
import 'dart:io';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/window.dart';
import 'package:fl_clash/bootstrap.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/common/system_dns.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart' show UpdateCancelledException;
import 'package:fl_clash/manager/hotkey_manager.dart';
import 'package:fl_clash/manager/manager.dart';
import 'package:fl_clash/plugins/app.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'pages/pages.dart';

class ForegroundAutoUpdateScheduler {
  ForegroundAutoUpdateScheduler({
    required this.run,
    this.interval = const Duration(minutes: 20),
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now;

  final Future<void> Function(bool Function() isCurrent) run;
  final Duration interval;
  final DateTime Function() _now;
  Timer? _timer;
  DateTime? _dueAt;
  bool _visible = false;
  bool _started = false;
  bool _due = false;
  bool _running = false;
  bool _disposed = false;
  int _generation = 0;

  void setVisible(bool visible) {
    if (_disposed || (_started && visible == _visible)) return;
    final first = !_started;
    _started = true;
    _visible = visible;
    _generation++;
    _timer?.cancel();
    _timer = null;
    if (!visible) {
      if (first || _running) _due = true;
    } else if (first) {
      _schedule();
    } else if (_due || !_now().isBefore(_dueAt!)) {
      unawaited(_run());
    } else {
      final remaining = _dueAt!.difference(_now());
      _schedule(remaining < interval ? remaining : interval);
    }
  }

  void _schedule([Duration? delay]) {
    if (_disposed || !_visible || _running || _timer != null) return;
    if (delay == null) _dueAt = _now().add(interval);
    _timer = Timer(delay ?? interval, () {
      _timer = null;
      unawaited(_run());
    });
  }

  Future<void> _run() async {
    if (_disposed || !_visible) return;
    if (_running) {
      _due = true;
      return;
    }
    _running = true;
    _due = false;
    final generation = _generation;
    try {
      await run(() => !_disposed && _visible && generation == _generation);
    } finally {
      _running = false;
      if (!_disposed && _visible) {
        if (_due) {
          unawaited(_run());
        } else {
          _schedule();
        }
      }
    }
  }

  void dispose() {
    _disposed = true;
    _generation++;
    _timer?.cancel();
    _timer = null;
  }
}

Widget buildManagerStack({
  required bool isDesktop,
  required Future<void> Function(List<ConnectivityResult> results)
  onConnectivityChanged,
  Stream<ProcessSignal>? terminateSignals,
  required Widget child,
}) {
  final platformApp = isDesktop
      ? WindowHeaderContainer(child: child)
      : VpnManager(child: child);
  final state = AppStateManager(
    child: CoreManager(
      child: ConnectivityManager(
        onConnectivityChanged: onConnectivityChanged,
        child: platformApp,
      ),
    ),
  );
  final platformState = isDesktop
      ? WindowManager(
          terminateSignals: terminateSignals,
          child: TrayManager(
            child: HotKeyManager(child: ProxyManager(child: state)),
          ),
        )
      : AndroidManager(child: TileManager(child: state));
  return AppEnvManager(
    child: LocaleManager(
      child: StatusManager(child: ThemeManager(child: platformState)),
    ),
  );
}

class Application extends ConsumerStatefulWidget {
  const Application({super.key}) : _testHome = null, _testManagers = null;

  @visibleForTesting
  const Application.forTesting({
    super.key,
    required Widget home,
    required Widget Function(Widget child) wrapManagers,
  }) : _testHome = home,
       _testManagers = wrapManagers;

  final Widget? _testHome;
  final Widget Function(Widget child)? _testManagers;

  @override
  ConsumerState<Application> createState() => ApplicationState();
}

class ApplicationState extends ConsumerState<Application> {
  ForegroundAutoUpdateScheduler? _autoUpdates;
  bool _preHasVpn = false;
  // Windows can only watch SIGINT and SIGHUP.
  final _terminateSignals = system.isDesktop && !system.isWindows
      ? ProcessSignal.sigterm.watch()
      : null;

  @override
  void initState() {
    super.initState();
    if (widget._testHome != null) return;
    SystemNavigator.setFrameworkHandlesBack(true);
    WidgetsBinding.instance.addPostFrameCallback((timeStamp) async {
      if (globalState.navigatorKey.currentContext != null) {
        await bootstrap.attach();
      } else {
        exit(0);
      }
      if (!mounted) return;
      _autoUpdates = ForegroundAutoUpdateScheduler(run: _runAutoUpdates);
      ref.listenManual(appVisibleProvider, (_, visible) {
        _autoUpdates?.setVisible(visible);
      }, fireImmediately: true);
      _initLink();
      if (!safeModeBuild) {
        unawaited(app?.initShortcuts());
      }
    });
  }

  void _initLink() {
    linkManager.initAppLinksListen((url) async {
      unawaited(window?.show());
      final message = currentAppLocalizations.createProfileFromUrlTip(url);
      final parts = message.split(url);
      final res = await dialogs.showMessage(
        title: currentAppLocalizations.addProfile,
        message: TextSpan(
          children: [
            TextSpan(text: parts.first),
            TextSpan(
              text: url,
              style: const TextStyle(decoration: TextDecoration.underline),
            ),
            if (parts.length > 1) TextSpan(text: parts.last),
          ],
        ),
      );
      if (res != true) return;
      unawaited(
        ref.read(profilesActionProvider.notifier).addProfileFromLink(url),
      );
    });
  }

  Future<void> _runAutoUpdates(bool Function() isCurrent) async {
    try {
      if (!mounted || !isCurrent()) return;
      await ref
          .read(profilesActionProvider.notifier)
          .autoUpdateProfiles(isCurrent: isCurrent);
      if (!mounted || !isCurrent()) return;
      await ref
          .read(resourcesActionProvider.notifier)
          .autoUpdate(isCurrent: isCurrent);
    } catch (error) {
      if (error is UpdateCancelledException || !mounted || !isCurrent()) return;
      commonPrint.log(
        'auto update failed: ${compactError(error)}',
        logLevel: LogLevel.warning,
      );
    }
  }

  Future<void> _handleConnectivityChanged(
    List<ConnectivityResult> results,
  ) async {
    commonPrint.log('connectivityChanged ${results.toString()}');
    unawaited(systemDnsCoordinator?.resync() ?? Future.value());
    unawaited(ref.read(systemActionProvider.notifier).updateLocalIp());
    final hasVpn = results.contains(ConnectivityResult.vpn);
    if (_preHasVpn == hasVpn) {
      ref.read(routeTrackerProvider.notifier).bumpHostEpoch();
    }
    _preHasVpn = hasVpn;
  }

  @override
  Widget build(context) {
    return Consumer(
      builder: (_, ref, child) {
        final locale = ref.watch(
          appSettingProvider.select((state) => state.locale),
        );
        final themeProps = ref.watch(themeSettingProvider);
        return MaterialApp(
          debugShowCheckedModeBanner: false,
          navigatorKey: globalState.navigatorKey,
          onNavigationNotification: (_) => true,
          localizationsDelegates: const [
            AppLocalizations.delegate,
            AppUiLocalizations.delegate,
            ...GlobalMaterialLocalizations.delegates,
          ],
          builder: (context, child) {
            return ThemeCrossFade(
              child: AuroraAppSurface(
                child:
                    widget._testManagers?.call(child!) ??
                    buildManagerStack(
                      isDesktop: system.isDesktop,
                      onConnectivityChanged: _handleConnectivityChanged,
                      terminateSignals: _terminateSignals,
                      child: RemoteFocusAdapter(
                        enabled: system.isTV,
                        floatingKeyboard: app?.floatingKeyboard,
                        child: child!,
                      ),
                    ),
              ),
            );
          },
          scrollBehavior: const BaseScrollBehavior(),
          title: displayAppName,
          locale: getLocaleForString(locale),
          supportedLocales: AppLocalizations.delegate.supportedLocales,
          themeMode: themeProps.themeMode,
          theme: watchAppTheme(ref, Brightness.light),
          darkTheme: watchAppTheme(ref, Brightness.dark),
          themeAnimationStyle: AnimationStyle.noAnimation,
          home: KeyboardInsetHold(child: child!),
        );
      },
      child: widget._testHome ?? const HomePage(),
    );
  }

  @override
  void dispose() {
    if (widget._testHome == null) linkManager.destroy();
    _autoUpdates?.dispose();
    super.dispose();
  }
}
