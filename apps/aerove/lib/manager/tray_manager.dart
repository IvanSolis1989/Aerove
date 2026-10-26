import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/tray.dart';
import 'package:fl_clash/common/window.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/action.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/state.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:tray/tray.dart';

class TrayManager extends ConsumerStatefulWidget {
  final Widget child;
  @visibleForTesting
  final bool? isMacOS;

  const TrayManager({super.key, this.isMacOS, required this.child});

  @override
  ConsumerState<TrayManager> createState() => _TrayManagerState();
}

class _TrayManagerState extends ConsumerState<TrayManager> {
  StreamSubscription<TrayEvent>? _subscription;
  bool _isUpdating = false;
  bool _hasPendingUpdate = false;
  bool _menuStale = false;
  Completer<void>? _drained;

  bool get _isMacOS => widget.isMacOS ?? system.isMacOS;

  @override
  void initState() {
    super.initState();
    _subscription = Tray.instance.events.listen(_handleTrayEvent);
    ref.listenManual(trayStateProvider, (prev, next) {
      if (prev == next) {
        return;
      }
      if (_isMacOS && prev != null && _onlyGroupsChanged(prev, next)) {
        _menuStale = true;
        return;
      }
      _requestUpdate();
    });
    ref.listenManual(loadedLocaleProvider, (prev, next) {
      if (prev != null && prev != next) {
        _requestUpdate();
      }
    });
    if (_isMacOS) {
      // Submenus show only once opened, so what feeds them waits for a click.
      void markMenuStale(Object? _, Object? _) {
        _menuStale = true;
      }

      ref.listenManual(delayDataSourceProvider, markMenuStale);
      ref.listenManual(groupsProvider, markMenuStale);
      ref.listenManual(
        appSettingProvider.select((state) => state.testUrl),
        markMenuStale,
      );
      ref.listenManual(trayTitleStateProvider, (prev, next) {
        if (prev != next) {
          _reportFailure(
            appTray?.updateTitle(
              showTrayTitle: next.showTrayTitle,
              traffic: next.traffic,
            ),
          );
        }
      });
    }
  }

  bool _onlyGroupsChanged(TrayState prev, TrayState next) {
    return prev.copyWith(groups: next.groups, selectedMap: next.selectedMap) ==
        next;
  }

  /// Updates in flight coalesce, so a burst of changes pushes the tray once.
  void _requestUpdate() {
    if (_isUpdating) {
      _hasPendingUpdate = true;
      return;
    }
    _isUpdating = true;
    _drained ??= Completer<void>();
    _reportFailure(_drainUpdates());
  }

  Future<void> _drainUpdates() async {
    try {
      do {
        _hasPendingUpdate = false;
        _menuStale = false;
        await ref.read(systemActionProvider.notifier).updateTray();
      } while (_hasPendingUpdate && mounted);
    } finally {
      _isUpdating = false;
      if (_hasPendingUpdate && mounted) {
        _requestUpdate();
      } else {
        final drained = _drained;
        _drained = null;
        drained?.complete();
      }
    }
  }

  Future<void> _openMenu() async {
    if (_isMacOS && (_menuStale || _isUpdating)) {
      if (_menuStale && mounted) {
        _requestUpdate();
      }
      await _drained?.future;
    }
    await Tray.instance.openMenu();
  }

  void _reportFailure(Future<void>? operation) {
    if (operation == null) {
      return;
    }
    unawaited(
      operation.onError<Object>((error, stackTrace) {
        commonPrint.log(
          'Tray operation failed: ${compactError(error)}',
          logLevel: LogLevel.error,
        );
      }),
    );
  }

  void _handleTrayEvent(TrayEvent event) {
    switch (event) {
      case TrayIconActivated():
        window?.show();
      case TrayMenuRequested():
        _reportFailure(_openMenu());
      case TrayMenuItemSelected():
        break;
    }
  }

  @override
  Widget build(BuildContext context) {
    return widget.child;
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }
}
