import 'package:fl_clash/widgets/aurora.dart' show AuroraMotionActivity;
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

/// Builds [builder] again whenever [revision] changes, but while the user
/// interacts ([AuroraMotionActivity.interactionOf]) only the first change and
/// every other one after it, and the latest one the moment the interaction
/// ends.
class InteractionPaced extends StatefulWidget {
  const InteractionPaced({
    super.key,
    required this.revision,
    required this.builder,
  });

  final Object? revision;
  final WidgetBuilder builder;

  @override
  State<InteractionPaced> createState() => _InteractionPacedState();
}

class _InteractionPacedState extends State<InteractionPaced> {
  ValueListenable<bool>? _interaction;
  ValueListenable<TickerModeData>? _awaitedTicker;
  Widget? _built;
  Object? _shown;
  Object? _seen;
  var _forceBuild = true;
  var _odd = true;
  var _holding = false;

  bool get _interacting => _interaction?.value ?? false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _forceBuild = true;
    final interaction = AuroraMotionActivity.interactionOf(context);
    if (!identical(interaction, _interaction)) {
      _interaction?.removeListener(_onInteraction);
      _interaction = interaction?..addListener(_onInteraction);
    }
  }

  void _onInteraction() {
    if (_interacting) {
      _odd = true;
    } else {
      _showHeld();
    }
  }

  void _showHeld() {
    if (!_holding || _interacting || !mounted) return;
    final ticker = TickerMode.getValuesNotifier(context);
    if (ticker.value.enabled) {
      setState(() => _forceBuild = true);
    } else if (!identical(ticker, _awaitedTicker)) {
      _stopAwaitingTicker();
      _awaitedTicker = ticker..addListener(_showHeld);
    }
  }

  void _stopAwaitingTicker() {
    _awaitedTicker?.removeListener(_showHeld);
    _awaitedTicker = null;
  }

  @override
  void dispose() {
    _interaction?.removeListener(_onInteraction);
    _stopAwaitingTicker();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final revision = widget.revision;
    if (_built case final built?
        when !_forceBuild && _interacting && revision != _shown) {
      if (revision != _seen) _odd = !_odd;
      _seen = revision;
      if (_odd) {
        _holding = true;
        return built;
      }
    }
    _forceBuild = false;
    _holding = false;
    _stopAwaitingTicker();
    _shown = _seen = revision;
    return _built = widget.builder(context);
  }
}
