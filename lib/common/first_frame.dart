import 'dart:async';

import 'package:fl_clash/providers/app.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

class FirstFrameGate {
  FirstFrameGate._(this._binding);

  final WidgetsBinding _binding;
  Timer? _timeout;
  ProviderSubscription<Size>? _viewSize;
  bool _held = true;

  static FirstFrameGate untilViewSize(
    ProviderContainer container, {
    WidgetsBinding? binding,
    Duration timeout = const Duration(seconds: 1),
  }) {
    final gate = FirstFrameGate._(binding ?? WidgetsBinding.instance);
    gate._binding.deferFirstFrame();
    gate._timeout = Timer(timeout, gate.release);
    gate._viewSize = container.listen(viewSizeProvider, (_, size) {
      if (!size.isEmpty) gate.release();
    });
    if (!container.read(viewSizeProvider).isEmpty) gate.release();
    return gate;
  }

  void release() {
    if (!_held) return;
    _held = false;
    _timeout?.cancel();
    _viewSize?.close();
    _binding.allowFirstFrame();
    // allowFirstFrame warms up a frame only from the idle phase.
    _binding.scheduleFrame();
  }
}
