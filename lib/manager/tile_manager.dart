import 'dart:async';

import 'package:fl_clash/common/app_localizations.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/plugins/app.dart';
import 'package:fl_clash/plugins/tile.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class TileManager extends ConsumerStatefulWidget {
  final Widget child;

  const TileManager({super.key, required this.child});

  @override
  ConsumerState<TileManager> createState() => _TileContainerState();
}

class _TileContainerState extends ConsumerState<TileManager> with TileListener {
  final List<ProviderSubscription<bool>> _heldStart = [];

  @override
  Widget build(BuildContext context) {
    return widget.child;
  }

  bool get isStart => ref.read(isStartProvider);

  @override
  Future<void> onStart() async {
    if (isStart && ref.read(coreStatusProvider) == CoreStatus.connected) {
      return;
    }
    unawaited(app?.tip(currentAppLocalizations.startVpn));
    if (ref.read(initProvider)) {
      _start();
    } else {
      _holdStart();
    }
    super.onStart();
  }

  // A run command that lands while the launch finishes is newer than the tap.
  void _holdStart() {
    if (_heldStart.isNotEmpty) {
      return;
    }
    _heldStart.addAll([
      ref.listenManual(initProvider, (_, initialized) {
        if (!initialized) {
          return;
        }
        _dropHeldStart();
        if (!isStart) {
          _start();
        }
      }),
      ref.listenManual(isStartProvider, (_, _) => _dropHeldStart()),
    ]);
  }

  void _dropHeldStart() {
    for (final subscription in _heldStart) {
      subscription.close();
    }
    _heldStart.clear();
  }

  void _start() {
    unawaited(ref.read(setupActionProvider.notifier).setRunning(true));
  }

  @override
  void initState() {
    super.initState();
    tile?.addListener(this);
  }

  @override
  void dispose() {
    tile?.removeListener(this);
    super.dispose();
  }
}
