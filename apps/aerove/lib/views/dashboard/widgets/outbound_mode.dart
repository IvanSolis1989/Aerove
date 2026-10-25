import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/style.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'card.dart';

Glyph _glyphOf(Mode mode) => switch (mode) {
  Mode.rule => AppGlyphs.rules,
  Mode.global => AppGlyphs.proxies,
  Mode.direct => AppGlyphs.target,
};

void _changeMode(WidgetRef ref, Mode mode) {
  if (ref.read(patchClashConfigProvider).mode == mode) return;
  ref.read(setupActionProvider.notifier).changeMode(mode);
}

class OutboundModeCard extends ConsumerWidget {
  const OutboundModeCard({super.key, this.embedded = false});

  final bool embedded;

  static const _outer = 26.0;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final mode = ref.watch(
      patchClashConfigProvider.select((state) => state.mode),
    );
    final tone = DashboardColors.of(context).tone;
    final content = Column(
      children: [
        if (!embedded)
          DashboardCardHeader(
            glyph: AppGlyphs.split,
            title: context.appLocalizations.outboundMode,
          ),
        if (!embedded) const SizedBox(height: 12),
        DashboardConnectedGroup(
          children: [
            for (final (index, item) in Mode.values.indexed)
              Expanded(
                child: DashboardConnectedButton(
                  pane: true,
                  pill: true,
                  selected: item == mode,
                  first: index == 0,
                  last: index == Mode.values.length - 1,
                  outer: _outer,
                  inner: 12,
                  background: tone,
                  semanticLabel: item.label,
                  onTap: () => _changeMode(ref, item),
                  builder: (foreground) => SizedBox(
                    height: 52 * MediaQuery.textScalerOf(context).scale(1),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      spacing: 2,
                      children: [
                        GlyphIcon(
                          _glyphOf(item),
                          key: ValueKey('dashboard-mode-${item.name}-icon'),
                          size: 18,
                          color: foreground,
                        ),
                        GlassText(
                          item.label,
                          key: ValueKey('dashboard-mode-${item.name}-label'),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: AppTextStyle.footnote
                              .of(context, emphasized: item == mode)
                              .copyWith(color: foreground),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
          ],
        ),
      ],
    );
    return embedded
        ? content
        : DashboardCard(
            semanticLabel: context.appLocalizations.outboundMode,
            child: content,
          );
  }
}

/// The desktop control row's mode switch; its buttons sit as far in from the
/// card's ends as from its edges so their corners stay concentric.
class OutboundModeBar extends ConsumerWidget {
  const OutboundModeBar({super.key});

  static const _inset = 8.0;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final density = DashboardDensityScope.of(context);
    final mode = ref.watch(
      patchClashConfigProvider.select((state) => state.mode),
    );
    final appLocalizations = context.appLocalizations;
    final outer = dashboardCardRadius(density) - _inset;
    return DashboardCard(
      semanticLabel: appLocalizations.outboundMode,
      padding: EdgeInsetsDirectional.fromSTEB(
        dashboardCardPadding(density).left,
        _inset,
        _inset,
        _inset,
      ),
      child: LayoutBuilder(
        builder: (context, constraints) => Row(
          spacing: 16,
          children: [
            GlyphIcon(
              AppGlyphs.split,
              size: 18,
              color: context.colorScheme.onSurfaceVariant,
            ),
            ConstrainedBox(
              constraints: BoxConstraints(maxWidth: constraints.maxWidth / 4),
              child: GlassText(
                appLocalizations.outboundMode,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: DashboardText.cardTitle(context),
              ),
            ),
            Expanded(
              child: DashboardConnectedGroup(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (final (index, item) in Mode.values.indexed)
                    Expanded(
                      child: DashboardConnectedButton(
                        pane: true,
                        pill: true,
                        selected: item == mode,
                        first: index == 0,
                        last: index == Mode.values.length - 1,
                        outer: outer,
                        inner: 10,
                        background: DashboardColors.of(context).tone,
                        semanticLabel: item.label,
                        onTap: () => _changeMode(ref, item),
                        builder: (foreground) => Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          spacing: 6,
                          children: [
                            GlyphIcon(
                              _glyphOf(item),
                              size: 18,
                              color: foreground,
                            ),
                            Flexible(
                              child: GlassText(
                                item.label,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: AppTextStyle.subheadline
                                    .of(context, emphasized: item == mode)
                                    .copyWith(color: foreground),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
