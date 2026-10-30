import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/common/common.dart';
import 'package:material_ui/material_ui.dart';

import 'active_polling.dart';

class LastUpdateTimeText extends StatefulWidget {
  final DateTime? lastUpdateDate;
  final TextStyle? style;
  final bool compact;
  final DateTime Function()? now;

  const LastUpdateTimeText({
    super.key,
    required this.lastUpdateDate,
    this.style,
    this.compact = false,
    this.now,
  });

  @override
  State<LastUpdateTimeText> createState() => _LastUpdateTimeTextState();
}

/// How long [shown] keeps reading the same at [now]: under an hour the text
/// counts minutes, under a day hours, and days beyond that.
@visibleForTesting
Duration lastUpdateTextLifetime(DateTime shown, DateTime now) {
  final age = now.difference(shown);
  if (age.isNegative) {
    return const Duration(minutes: 1);
  }
  final step = age < const Duration(hours: 1)
      ? const Duration(minutes: 1)
      : age < const Duration(days: 1)
      ? const Duration(hours: 1)
      : const Duration(days: 1);
  final next = step * (age.inMicroseconds ~/ step.inMicroseconds + 1) - age;
  if (next < const Duration(seconds: 1)) {
    return const Duration(seconds: 1);
  }
  return next > const Duration(hours: 1) ? const Duration(hours: 1) : next;
}

class _LastUpdateTimeTextState extends State<LastUpdateTimeText>
    with WidgetsBindingObserver, ActivePollingMixin<LastUpdateTimeText> {
  String? _text;

  DateTime _now() => (widget.now ?? DateTime.now)();

  @override
  Duration get pollInterval =>
      lastUpdateTextLifetime(widget.lastUpdateDate!, _now());

  @override
  bool get pollOnStart => false;

  @override
  bool get canPoll => super.canPoll && widget.lastUpdateDate != null;

  String? _describe() => widget.lastUpdateDate?.getLastUpdateTimeDesc(
    context,
    compact: widget.compact,
    now: _now(),
  );

  void _refresh() {
    final text = _describe();
    if (text != _text) {
      setState(() => _text = text);
    }
  }

  @override
  Future<void> poll(PollGuard isCurrent) async => _refresh();

  // The text froze while the app was away, so it catches up on return.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final couldPoll = canPoll;
    super.didChangeAppLifecycleState(state);
    if (!couldPoll && canPoll) {
      _refresh();
    }
  }

  @override
  void didUpdateWidget(covariant LastUpdateTimeText oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.lastUpdateDate != widget.lastUpdateDate) {
      restartPolling();
    }
  }

  @override
  Widget build(BuildContext context) {
    _text = _describe();
    return GlassText(_text ?? '', style: widget.style);
  }
}
