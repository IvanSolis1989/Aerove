import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class CoreStatusButton extends ConsumerStatefulWidget {
  const CoreStatusButton({super.key});

  @override
  ConsumerState<CoreStatusButton> createState() => _CoreStatusButtonState();
}

class _CoreStatusButtonState extends ConsumerState<CoreStatusButton> {
  static const _holdDuration = Duration(milliseconds: 600);

  Timer? _holdTimer;
  CoreStatus _status = CoreStatus.disconnected;

  @override
  void initState() {
    super.initState();
    _status = ref.read(coreStatusProvider);
    ref.listenManual(coreStatusProvider, (_, next) {
      _onStatusChanged(next);
    });
  }

  @override
  void dispose() {
    _holdTimer?.cancel();
    super.dispose();
  }

  void _onStatusChanged(CoreStatus next) {
    setState(() {
      _status = next;
      switch (next) {
        case CoreStatus.connecting:
          _holdTimer ??= Timer(_holdDuration, () {
            if (mounted) {
              setState(() {
                _holdTimer = null;
              });
            }
          });
          break;
        case CoreStatus.disconnected:
          _holdTimer?.cancel();
          _holdTimer = null;
          break;
        case CoreStatus.connected:
          break;
      }
    });
  }

  Future<void> _handleConnection() async {
    if (_holdTimer != null) {
      return;
    }
    final coreStatus = ref.read(coreStatusProvider);
    if (coreStatus == CoreStatus.connecting) {
      return;
    }
    final tip = coreStatus == CoreStatus.connected
        ? context.appLocalizations.forceRestartCoreTip
        : context.appLocalizations.restartCoreTip;
    final res = await dialogs.showMessage(message: TextSpan(text: tip));
    if (res != true || !mounted) {
      return;
    }
    try {
      await ref.read(coreActionProvider.notifier).restartCore();
    } catch (error) {
      dialogs.showNotifier(error.toString(), level: MessageLevel.error);
    }
  }

  @override
  Widget build(BuildContext context) {
    final coreStatus = _holdTimer != null ? CoreStatus.connecting : _status;
    final appLocalizations = context.appLocalizations;
    final colorScheme = context.colorScheme;
    final glass =
        LiquidGlassScope.of(context) || TonalButtonTheme.glassOf(context);
    ButtonStyle? tinted(Color? color) => glass
        ? LiquidGlass.buttonStyle(
            (_) => color == null
                ? LiquidGlassStyle.regular(colorScheme)
                : LiquidGlassStyle.tinted(colorScheme, color),
          )
        : null;
    final statusColor = switch (coreStatus) {
      CoreStatus.connecting => null,
      CoreStatus.connected => colorScheme.successContainer,
      CoreStatus.disconnected => colorScheme.errorContainer,
    };
    final statusLabel = switch (coreStatus) {
      CoreStatus.connecting => appLocalizations.connecting,
      CoreStatus.connected => appLocalizations.connected,
      CoreStatus.disconnected => appLocalizations.disconnected,
    };
    return LayoutBuilder(builder: (context, constraints) {
      final text = TextPainter(
        text: TextSpan(
          text: statusLabel,
          style: Theme.of(context).filledButtonTheme.style?.textStyle?.resolve({}) ??
              Theme.of(context).textTheme.labelLarge,
        ),
        textDirection: Directionality.of(context),
        textScaler: MediaQuery.textScalerOf(context),
      )..layout();
      final padding = Theme.of(context).filledButtonTheme.style?.padding?.resolve({})
          ?.resolve(Directionality.of(context)) ??
          const EdgeInsetsDirectional.only(start: 16, end: 24)
              .resolve(Directionality.of(context));
      final minimum = text.width +
          globalState.measure.textHeight(AppTextStyle.subheadline) + 8 + padding.horizontal;
      final compact = constraints.hasBoundedWidth && constraints.maxWidth < minimum;
      text.dispose();
      return Tooltip(
      message: appLocalizations.coreStatus,
      child: FadeScaleBox(
        alignment: Alignment.centerRight,
        child: compact || coreStatus == CoreStatus.connected
            ? Semantics(
              label: statusLabel,
              child: IconButton.filled(
                style: ElasticPress.buttonStyle
                    .merge(tinted(statusColor))
                    .merge(
                      IconButton.styleFrom(
                        backgroundColor: statusColor,
                        foregroundColor: switch (coreStatus) {
                          CoreStatus.connecting => null,
                          CoreStatus.connected => colorScheme.onSuccessContainer,
                          CoreStatus.disconnected => colorScheme.onErrorContainer,
                        },
                      ),
                    ),
                onPressed: _handleConnection,
                icon: coreStatus == CoreStatus.connecting
                    ? SizedBox.square(
                        dimension: TonalButtonSize.bar.icon,
                        child: CommonCircleLoading(color: colorScheme.onPrimary),
                      )
                    : GlyphIcon(
                        coreStatus == CoreStatus.connected
                            ? AppGlyphs.check : AppGlyphs.refresh,
                        fill: 1,
                      ),
              )
            )
            : FilledButton.icon(
                key: ValueKey(coreStatus),
                onPressed: _handleConnection,
                style: ElasticPress.buttonStyle
                    .merge(tinted(statusColor))
                    .merge(
                      FilledButton.styleFrom(
                        backgroundColor: statusColor,
                        foregroundColor: switch (coreStatus) {
                          CoreStatus.connecting => null,
                          CoreStatus.connected =>
                            context.colorScheme.onSuccessContainer,
                          CoreStatus.disconnected =>
                            context.colorScheme.onErrorContainer,
                        },
                      ),
                    ),
                icon: SizedBox(
                  height: globalState.measure.textHeight(
                    AppTextStyle.subheadline,
                  ),
                  width: globalState.measure.textHeight(
                    AppTextStyle.subheadline,
                  ),
                  child: switch (coreStatus) {
                    CoreStatus.connecting => CommonCircleLoading(
                      color: context.colorScheme.onPrimary,
                    ),
                    CoreStatus.connected => const GlyphIcon(
                      AppGlyphs.check,
                      fill: 1,
                    ),
                    CoreStatus.disconnected => const GlyphIcon(
                      AppGlyphs.refresh,
                      fill: 1,
                    ),
                  },
                ),
                label: GlassText(statusLabel),
              ),
      ),
    );
    });
  }
}
