import 'dart:collection';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/features/form/form.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

sealed class _FontChoice {
  const _FontChoice();
}

final class _DefaultFont extends _FontChoice {
  const _DefaultFont();
}

final class _InstalledFont extends _FontChoice {
  const _InstalledFont(this.family);

  final String family;

  @override
  bool operator ==(Object other) =>
      other is _InstalledFont && other.family == family;

  @override
  int get hashCode => family.hashCode;
}

class FontFamilyView extends ConsumerWidget {
  const FontFamilyView({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final appLocalizations = context.appLocalizations;
    final appVisible = ref.watch(appVisibleProvider);
    final families = ref.watch(installedSystemFontsProvider).asData?.value;
    final installed =
        families?.map(_InstalledFont.new).toList() ?? const <_InstalledFont>[];
    return SelectionSheet<_FontChoice>(
      title: appLocalizations.fontFamily,
      loadingStatus: families == null
          ? const NullStatus(illustration: NullStatusIllustration.fonts)
          : null,
      sections: [
        const SelectionSection(items: [_DefaultFont()]),
        if (installed.isNotEmpty)
          SelectionSection(
            label: system.isAndroid
                ? appLocalizations.bilingualSystemFonts
                : appLocalizations.installedFonts,
            items: installed,
          ),
      ],
      labelBuilder: (choice) => switch (choice) {
        _DefaultFont() => appLocalizations.defaultText,
        _InstalledFont(:final family) => family,
      },
      titleBuilder: (choice, title) => switch (choice) {
        _DefaultFont() => title,
        _InstalledFont(:final family) => _FontLabel(
          family: family,
          appVisible: appVisible,
          child: title,
        ),
      },
      selectedOf: (ref) => switch (ref.watch(
        themeSettingProvider.select((state) => state.fontFamily),
      )) {
        final family? when installed.any((font) => font.family == family) =>
          _InstalledFont(family),
        _ => const _DefaultFont(),
      },
      onSelected: (choice) {
        final String? family;
        switch (choice) {
          case _DefaultFont():
            family = null;
          case _InstalledFont():
            family = choice.family;
        }
        ref
            .read(themeSettingProvider.notifier)
            .update((state) => state.copyWith(fontFamily: family));
      },
    );
  }
}

/// Laying out a family for the first time loads it on the UI thread, a few
/// milliseconds each, so each name switches to its family on a frame of its
/// own.
class _FontLabel extends StatefulWidget {
  const _FontLabel({
    required this.family,
    required this.appVisible,
    required this.child,
  });

  final String family;
  final bool appVisible;
  final Widget child;

  @override
  State<_FontLabel> createState() => _FontLabelState();
}

class _FontLabelState extends State<_FontLabel>
    with RouteMotionHoldMixin<_FontLabel> {
  static final _shown = <String>{};
  static final _waiting = Queue<_FontLabelState>();
  static var _scheduled = false;
  var _inTree = true;

  bool get _isShown => _shown.contains(widget.family);

  bool get _canPreview =>
      mounted &&
      _inTree &&
      !_isShown &&
      widget.appVisible &&
      !pageHidden &&
      !isRouteMoving(context);

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _request();
  }

  @override
  void didUpdateWidget(_FontLabel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.family != widget.family ||
        oldWidget.appVisible != widget.appVisible) {
      _request();
    }
  }

  @override
  void deactivate() {
    _inTree = false;
    super.deactivate();
  }

  @override
  void activate() {
    super.activate();
    _inTree = true;
    _request();
  }

  @override
  void dispose() {
    _waiting.remove(this);
    super.dispose();
  }

  void _request() {
    if (_isShown) return;
    if (!_waiting.contains(this)) _waiting.add(this);
    _resume();
  }

  void _resume() {
    if (!_inTree || !widget.appVisible) return;
    updateWhenRouteSettled(_schedule);
  }

  static void _schedule() {
    if (_scheduled || !_waiting.any((label) => label._canPreview)) {
      return;
    }
    _scheduled = true;
    SchedulerBinding.instance
      ..addPostFrameCallback(_showNext)
      ..ensureVisualUpdate();
  }

  static void _showNext(Duration _) {
    _scheduled = false;
    _waiting.removeWhere((label) => !label.mounted || label._isShown);
    _FontLabelState? next;
    for (final label in _waiting) {
      if (!label._canPreview) continue;
      next = label;
      break;
    }
    if (next != null) {
      _waiting.remove(next);
      _shown.add(next.widget.family);
      next.setState(() {});
    }
    for (final label in _waiting) {
      label._resume();
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_isShown) {
      return widget.child;
    }
    return DefaultTextStyle.merge(
      style: TextStyle(fontFamily: widget.family),
      child: widget.child,
    );
  }
}
