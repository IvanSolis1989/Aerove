import 'package:emoji_regex/emoji_regex.dart';
import 'package:flutter/foundation.dart' show defaultTargetPlatform;
import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/measure.dart';
import 'package:material_ui/material_ui.dart';

import 'font.dart';
import 'native_emoji_cover.dart';

export 'native_emoji_cover.dart' show EmojiInkReadyNotification;

final _emojiPattern = emojiRegex();

class TooltipText extends StatelessWidget {
  final Text text;

  const TooltipText({super.key, required this.text});

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final maxWidth = constraints.maxWidth;
        final isOverflow = MeasureScope.of(
          context,
        ).computeTextIsOverflow(text, maxWidth: maxWidth);
        if (isOverflow) {
          return Tooltip(
            triggerMode: TooltipTriggerMode.longPress,
            preferBelow: false,
            message: text.data,
            child: text,
          );
        }
        return text;
      },
    );
  }
}

class TooltipLabel extends StatelessWidget {
  final String text;
  final TextStyle? style;
  final int maxLines;

  const TooltipLabel(this.text, {super.key, this.style, this.maxLines = 2});

  @override
  Widget build(BuildContext context) {
    return TooltipText(
      text: GlassText(
        text,
        maxLines: maxLines,
        overflow: TextOverflow.ellipsis,
        style: DefaultTextStyle.of(context).style.merge(style),
      ),
    );
  }
}

class EmojiText extends StatelessWidget {
  final String text;
  final TextStyle? style;
  final int? maxLines;
  final TextOverflow? overflow;

  const EmojiText(
    this.text, {
    super.key,
    this.maxLines,
    this.overflow,
    this.style,
  });

  /// The spans [EmojiText] lays [text] out in, emoji in their own font.
  static TextSpan spanOf(String text, TextStyle? style) {
    if (!useDesktopEmojiFont(defaultTargetPlatform)) {
      return TextSpan(text: text, style: style);
    }
    final List<TextSpan> spans = [];
    final matches = _emojiPattern.allMatches(text);

    int lastMatchEnd = 0;
    for (final match in matches) {
      if (match.start > lastMatchEnd) {
        spans.add(
          TextSpan(
            text: text.substring(lastMatchEnd, match.start),
            style: style,
          ),
        );
      }
      spans.add(
        TextSpan(
          text: match.group(0),
          style: (style ?? const TextStyle()).copyWith(
            fontFamily: desktopEmojiFontFamily,
          ),
        ),
      );
      lastMatchEnd = match.end;
    }
    if (lastMatchEnd < text.length) {
      spans.add(TextSpan(text: text.substring(lastMatchEnd), style: style));
    }

    return TextSpan(children: spans);
  }

  /// The width [EmojiText] lays [text] out at on one line under [style].
  static double widthOf(BuildContext context, String text, TextStyle style) {
    final painter = TextPainter(
      text: TextSpan(
        style: MediaQuery.boldTextOf(context)
            ? style.merge(const TextStyle(fontWeight: FontWeight.bold))
            : style,
        children: [spanOf(text, null)],
      ),
      textDirection: Directionality.of(context),
      textScaler: MediaQuery.textScalerOf(context),
      locale: Localizations.maybeLocaleOf(context),
      maxLines: 1,
    )..layout();
    try {
      return painter.maxIntrinsicWidth;
    } finally {
      painter.dispose();
    }
  }

  @override
  Widget build(BuildContext context) {
    return GlassText.rich(
      spanOf(text, style),
      style: style,
      maxLines: maxLines,
      overflow: overflow ?? TextOverflow.clip,
    );
  }
}

/// One emoji, such as a flag, scaled so its picture covers a [size] square.
class EmojiCover extends StatelessWidget {
  const EmojiCover(this.emoji, {super.key, required this.size, this.platform});

  final String emoji;
  final double size;
  final TargetPlatform? platform;

  @override
  Widget build(BuildContext context) {
    final target = platform ?? defaultTargetPlatform;
    if (useDesktopEmojiFont(target)) {
      return _DesktopEmojiCover(emoji, size: size);
    }
    final fallback = FittedBox(
      fit: BoxFit.cover,
      child: MediaQuery.withNoTextScaling(
        child: EmojiText(
          emoji,
          maxLines: 1,
          style: const TextStyle(inherit: false, fontSize: 32, height: 1),
        ),
      ),
    );
    return target == TargetPlatform.android
        ? NativeEmojiCover(emoji, size: size, fallback: fallback)
        : SizedBox.square(dimension: size, child: fallback);
  }
}

class _DesktopEmojiCover extends StatelessWidget {
  const _DesktopEmojiCover(this.emoji, {required this.size});

  static const _twemojiUnitsPerEm = 1024.0;
  static const _twemojiFlagAdvance = 1275.0;
  static const _twemojiAscentFraction = 950 / 1200;

  final String emoji;
  final double size;

  @override
  Widget build(BuildContext context) {
    // COLR layer bounds in Twemoji.Mozilla.ttf, relative to its baseline.
    final bounds = switch (emoji) {
      '🇧🇪' => const Rect.fromLTRB(138, -83, 1138, 783),
      '🇨🇭' || '🇻🇦' => const Rect.fromLTRB(204, -83, 1071, 783),
      '🇳🇵' => const Rect.fromLTRB(304, -83, 964, 783),
      '🇸🇲' => const Rect.fromLTRB(38, -83, 1238, 782),
      _ => const Rect.fromLTRB(38, -83, 1238, 783),
    };
    final scale = size / bounds.shortestSide;
    final fontSize = scale * _twemojiUnitsPerEm;
    // SkParagraph rounds its painted baseline; keep the layout baseline integral.
    final lineBaseline = fontSize * _twemojiAscentFraction;
    return SizedBox.square(
      dimension: size,
      child: OverflowBox(
        minWidth: 0,
        minHeight: 0,
        maxWidth: double.infinity,
        maxHeight: double.infinity,
        child: SizedBox(
          width: _twemojiFlagAdvance * scale,
          height: bounds.height * scale,
          child: OverflowBox(
            alignment: Alignment.topCenter,
            minHeight: 0,
            maxHeight: double.infinity,
            child: Transform.translate(
              offset: Offset(
                (_twemojiFlagAdvance / 2 - bounds.center.dx) * scale,
                0,
              ),
              child: Baseline(
                baseline: bounds.bottom * scale,
                baselineType: TextBaseline.alphabetic,
                child: MediaQuery.withNoTextScaling(
                  child: EmojiText(
                    emoji,
                    maxLines: 1,
                    style: TextStyle(
                      inherit: false,
                      fontFamily: desktopEmojiFontFamily,
                      fontSize: fontSize,
                      height: lineBaseline.ceilToDouble() / lineBaseline,
                      leadingDistribution: TextLeadingDistribution.proportional,
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
