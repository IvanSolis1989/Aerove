import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:material_ui/material_ui.dart';

abstract final class GlassForeground {
  static bool _sameRgb(Color a, Color b) =>
      a.r == b.r && a.g == b.g && a.b == b.b;

  static bool colorsInk(BuildContext context, Color color) {
    if (!LiquidGlassScope.of(context) ||
        MediaQuery.maybeHighContrastOf(context) == true || color.a < 0.70) {
      return false;
    }
    final colors = Theme.of(context).colorScheme;
    if (_sameRgb(color, colors.error) ||
        _sameRgb(color, colors.onErrorContainer)) return false;
    return _sameRgb(color, colors.onSurface) ||
        _sameRgb(color, colors.onSurfaceVariant) ||
        HSVColor.fromColor(color).saturation < 0.20;
  }

  static Color iconColorOf(BuildContext context, Color color, double opacity) =>
      color.withValues(alpha: color.a * opacity);

}
