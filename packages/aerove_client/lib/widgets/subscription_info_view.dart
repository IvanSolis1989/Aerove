import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:material_ui/material_ui.dart';

const _expireGap = 12.0;

String subscriptionQuotaSummary(BuildContext context, SubscriptionInfo info) {
  final previous = info.hasKnownValues ? null : info.sameSourceLastReported;
  final display = previous ?? info;
  final unknown = context.appLocalizations.unknown;
  final used = display.used;
  final total = display.knownTotal;
  final values =
      '${used == null ? unknown : trafficAmount(used)} / '
      '${total == null ? unknown : trafficAmount(total)}';
  if (previous != null) {
    return '${context.appLocalizations.quotaLastReported(previous.observedAt?.show ?? unknown)} · $values';
  }
  return info.observationVersion == 0
      ? '${context.appLocalizations.quotaLegacyUnverified} · $values'
      : values;
}

class SubscriptionInfoView extends StatelessWidget {
  final SubscriptionInfo? subscriptionInfo;

  const SubscriptionInfoView({super.key, this.subscriptionInfo});

  double _textWidth(BuildContext context, String text, TextStyle? style) {
    final painter = TextPainter(
      text: TextSpan(text: text, style: style),
      textDirection: Directionality.of(context),
      textScaler: MediaQuery.textScalerOf(context),
      locale: Localizations.maybeLocaleOf(context),
      maxLines: 1,
    )..layout();
    final width = painter.width;
    painter.dispose();
    return width;
  }

  @override
  Widget build(BuildContext context) {
    final info = subscriptionInfo;
    if (info == null) {
      return const SizedBox.shrink();
    }
    final progress = info.usage;
    final expireShow =
        info.expireDate?.show ??
        (info.knownExpire?.toString() ?? context.appLocalizations.unknown);
    final valueStyle = AppTextStyle.subheadline
        .of(context, emphasized: true)
        .copyWith(color: context.colorScheme.onSurfaceVariant);
    final metaStyle = AppTextStyle.footnote.of(context).toLight;
    final trafficLabel = subscriptionQuotaSummary(context, info);
    final trafficText = GlassText(trafficLabel, style: valueStyle);
    final expireText = GlassText(
      expireShow,
      style: metaStyle,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        LayoutBuilder(
          builder: (context, constraints) {
            final showExpire =
                _textWidth(context, trafficLabel, valueStyle) +
                    _expireGap +
                    _textWidth(context, expireShow, metaStyle) <=
                constraints.maxWidth;
            return Row(
              children: [
                Expanded(child: trafficText),
                if (showExpire) ...[
                  const SizedBox(width: _expireGap),
                  expireText,
                ],
              ],
            );
          },
        ),
        if (progress != null) ...[
          const SizedBox(height: 4),
          SizedBox(height: 4, child: CommonLinearLoading(value: progress)),
        ],
      ],
    );
  }
}

class SubscriptionInfoDetailView extends StatelessWidget {
  final SubscriptionInfo subscriptionInfo;

  const SubscriptionInfoDetailView({super.key, required this.subscriptionInfo});

  Widget _buildItem({String? label, required String value}) {
    return DecorationListItem(
      title: GlassText(label ?? value),
      subtitle: label == null ? null : TooltipLabel(value),
    );
  }

  @override
  Widget build(BuildContext context) {
    final appLocalizations = context.appLocalizations;
    final info = subscriptionInfo;
    final unknown = appLocalizations.unknown;
    final used = info.used;
    String amount(int? value) => value == null ? unknown : trafficAmount(value);
    final expire =
        info.expireDate?.show ?? (info.knownExpire?.toString() ?? unknown);
    final previous =
        info.sameSourceLastReported ??
        switch (info.lastReported) {
          final legacy?
              when legacy.observationVersion == 0 && legacy.sourceKey == null =>
            legacy,
          _ => null,
        };
    return SelectionArea(
      contextMenuBuilder: glassSelectableRegionContextMenu,
      magnifierConfiguration: glassTextMagnifierConfiguration,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          generateSection(
            title: appLocalizations.trafficUsage,
            items: [
              _buildItem(
                label: appLocalizations.usedTraffic,
                value: amount(used),
              ),
              if (used == null) ...[
                _buildItem(
                  label: appLocalizations.upload,
                  value: amount(info.knownUpload),
                ),
                _buildItem(
                  label: appLocalizations.download,
                  value: amount(info.knownDownload),
                ),
              ],
              _buildItem(
                label: appLocalizations.totalTraffic,
                value: amount(info.knownTotal),
              ),
            ],
          ),
          generateSection(
            title: appLocalizations.expireTime,
            items: [_buildItem(value: expire)],
          ),
          if (info.observationVersion == 0)
            _buildItem(value: appLocalizations.quotaLegacyUnverified)
          else if (info.observedAt != null)
            _buildItem(
              value: appLocalizations.quotaCheckedAt(info.observedAt!.show),
            ),
          if (previous != null)
            generateSection(
              title: previous.observationVersion == 0
                  ? appLocalizations.quotaLegacyUnverified
                  : appLocalizations.quotaLastReported(
                      previous.observedAt?.show ?? unknown,
                    ),
              items: [
                _buildItem(
                  label: appLocalizations.upload,
                  value: amount(previous.knownUpload),
                ),
                _buildItem(
                  label: appLocalizations.download,
                  value: amount(previous.knownDownload),
                ),
                _buildItem(
                  label: appLocalizations.totalTraffic,
                  value: amount(previous.knownTotal),
                ),
                _buildItem(
                  label: appLocalizations.expireTime,
                  value:
                      previous.expireDate?.show ??
                      previous.knownExpire?.toString() ??
                      unknown,
                ),
              ],
            ),
        ],
      ),
    );
  }
}
