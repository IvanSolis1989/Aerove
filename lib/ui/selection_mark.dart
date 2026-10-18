import 'package:fl_clash/ui/context.dart';
import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/glass_foreground.dart';
import 'package:fl_clash/ui/icons/glyph_painter.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:material_ui/material_ui.dart';

class SelectionCheck extends StatelessWidget {
  const SelectionCheck({super.key});

  static const size = 24.0;

  @override
  Widget build(BuildContext context) {
    return GlyphIcon(
      AppGlyphs.check,
      size: size,
      fill: 1,
      color: context.colorScheme.primary,
    );
  }
}

class SelectionBadge extends StatelessWidget {
  const SelectionBadge({super.key, this.size = 22})
    : glyph = AppGlyphs.check,
      tonal = false;

  /// The proxy a URLTest or Fallback group prefers, mihomo's `fixed`.
  const SelectionBadge.preferred({
    super.key,
    this.size = 22,
    bool inUse = false,
  }) : glyph = AppGlyphs.pin,
       tonal = !inUse;

  final double size;
  final Glyph glyph;
  final bool tonal;

  @override
  Widget build(BuildContext context) {
    final colorScheme = context.colorScheme;
    if (GlassForeground.colorsInk(context, colorScheme.onSurface)) {
      return GlassInk(
        source: colorScheme.onSurface,
        role: GlassInkRole.icon,
        child: CustomPaint(
          size: Size.square(size),
          painter: _SelectionBadgePainter(
            glyph: glyph,
            color: colorScheme.onSurface,
            tonal: tonal,
          ),
        ),
      );
    }
    return SizedBox.square(
      dimension: size,
      child: DecoratedBox(
        decoration: ShapeDecoration(
          shape: AppShape.circle,
          color: tonal
              ? colorScheme.primary.withValues(alpha: 0.14)
              : colorScheme.primary,
        ),
        child: Center(
          child: GlyphIcon(
            glyph,
            size: size - 6,
            fill: 1,
            color: tonal ? colorScheme.primary : colorScheme.onPrimary,
          ),
        ),
      ),
    );
  }
}

class _SelectionBadgePainter extends CustomPainter {
  const _SelectionBadgePainter({
    required this.glyph,
    required this.color,
    required this.tonal,
  });

  final Glyph glyph;
  final Color color;
  final bool tonal;

  @override
  void paint(Canvas canvas, Size size) {
    final bounds = Offset.zero & size;
    canvas.drawCircle(
      bounds.center,
      size.shortestSide / 2,
      Paint()..color = tonal ? color.withValues(alpha: 0.14) : color,
    );
    canvas.saveLayer(
      bounds,
      Paint()..blendMode = tonal ? BlendMode.srcOver : BlendMode.dstOut,
    );
    canvas.translate(3, 3);
    GlyphPainter(glyph: glyph, fill: 1, color: color).paint(
      canvas,
      Size(size.width - 6, size.height - 6),
    );
    canvas.restore();
  }

  @override
  bool shouldRepaint(_SelectionBadgePainter oldDelegate) =>
      oldDelegate.glyph != glyph ||
      oldDelegate.color != color ||
      oldDelegate.tonal != tonal;
}
