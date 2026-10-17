import 'package:material_ui/material_ui.dart';
import 'color.dart';

final _auroraSchemes = <Brightness, ColorScheme>{};

ColorScheme auroraColorScheme(Brightness brightness) =>
    _auroraSchemes.putIfAbsent(
      brightness,
      () => seededColorScheme(
        seedColor: const Color(0xFF808080),
        brightness: brightness,
        style: ColorStyle.monochrome,
      ),
    );

class AuroraAppearance extends InheritedWidget {
  const AuroraAppearance({
    super.key,
    required this.enabled,
    required super.child,
  });

  final bool enabled;

  static bool enabledOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<AuroraAppearance>()?.enabled ??
      false;

  static Color surfaceColorOf(
    BuildContext context,
    Color fallback, {
    double opacity = 0.28,
  }) {
    if (!enabledOf(context)) return fallback;
    final dark = Theme.of(context).brightness == Brightness.dark;
    return (dark ? fallback : Colors.white).withValues(
      alpha: dark ? (opacity + 0.12).clamp(0, 1) : opacity,
    );
  }

  @override
  bool updateShouldNotify(AuroraAppearance oldWidget) =>
      enabled != oldWidget.enabled;
}
