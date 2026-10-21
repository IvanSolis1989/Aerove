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
  ProviderSubscription<bool>? _pendingStart;

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
      _pendingStart ??= ref.listenManual(initProvider, (_, initialized) {
        if (!initialized) {
          return;
        }
        _pendingStart?.close();
        _pendingStart = null;
        if (!isStart) {
          _start();
        }
      });
    }
    super.onStart();
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
