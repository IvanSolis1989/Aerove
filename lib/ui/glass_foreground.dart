import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/surface.dart';
import 'package:fl_clash/ui/typography.dart';
import 'package:material_ui/material_ui.dart';

abstract final class GlassForeground {
  static const opacity = 0.86;

  static Color cap(Color color) =>
      color.a > opacity ? color.withValues(alpha: opacity) : color;

  static bool _sameRgb(Color a, Color b) =>
      a.r == b.r && a.g == b.g && a.b == b.b;

  static Color _capFor(Color color, ColorScheme colors) =>
      _sameRgb(color, colors.error) || _sameRgb(color, colors.onErrorContainer)
      ? color
      : cap(color);

  static Color colorOf(BuildContext context, Color color) =>
      LiquidGlassScope.of(context) &&
          MediaQuery.maybeHighContrastOf(context) != true
      ? _capFor(color, Theme.of(context).colorScheme)
      : color;

  static Color iconColorOf(BuildContext context, Color color, double opacity) {
    final enabled =
        LiquidGlassScope.of(context) &&
        MediaQuery.maybeHighContrastOf(context) != true;
    var original = color;
    if (enabled && opacity < 1) {
      // The theme cap must not dim an existing disabled opacity a second time.
      final sources = Theme.of(context).extension<_ForegroundSources>();
      for (final source in sources?.colors ?? const <Color>[]) {
        if (source.a > GlassForeground.opacity && cap(source) == color) {
          original = source;
          break;
        }
      }
    }
    final result = original.withValues(alpha: original.a * opacity);
    return enabled ? _capFor(result, Theme.of(context).colorScheme) : result;
  }

  static TextStyle textStyle(TextStyle style) =>
      style.color == null ? style : style.copyWith(color: cap(style.color!));

  static ButtonStyle _button(
    ButtonStyle? style,
    Color? fallback,
    ColorScheme colors,
  ) {
    final base = style ?? const ButtonStyle();
    return base.copyWith(
      foregroundColor: base.foregroundColor == null && fallback == null
          ? null
          : WidgetStateProperty.resolveWith((states) {
              final color = base.foregroundColor?.resolve(states);
              if (states.contains(WidgetState.disabled)) return color;
              final resolved = color ?? fallback;
              return resolved == null ? null : _capFor(resolved, colors);
            }),
      iconColor: base.iconColor == null
          ? null
          : WidgetStateProperty.resolveWith((states) {
              final color = base.iconColor!.resolve(states);
              if (color == null || states.contains(WidgetState.disabled)) {
                return color;
              }
              return _capFor(color, colors);
            }),
    );
  }

  static ColorScheme colorScheme(ColorScheme colors) => colors.copyWith(
    onSurface: cap(colors.onSurface),
    onSurfaceVariant: cap(colors.onSurfaceVariant),
    onInverseSurface: cap(colors.onInverseSurface),
    onPrimary: cap(colors.onPrimary),
    onPrimaryContainer: cap(colors.onPrimaryContainer),
    onPrimaryFixed: cap(colors.onPrimaryFixed),
    onPrimaryFixedVariant: cap(colors.onPrimaryFixedVariant),
    onSecondary: cap(colors.onSecondary),
    onSecondaryContainer: cap(colors.onSecondaryContainer),
    onSecondaryFixed: cap(colors.onSecondaryFixed),
    onSecondaryFixedVariant: cap(colors.onSecondaryFixedVariant),
    onTertiary: cap(colors.onTertiary),
    onTertiaryContainer: cap(colors.onTertiaryContainer),
    onTertiaryFixed: cap(colors.onTertiaryFixed),
    onTertiaryFixedVariant: cap(colors.onTertiaryFixedVariant),
  );

  static ThemeData theme(ThemeData theme) {
    final colors = theme.colorScheme;
    return theme
        .copyWith(
          colorScheme: colorScheme(colors),
          extensions: [
            theme.extension<_ForegroundSources>() ??
                _ForegroundSources([
                  colors.onSurface,
                  colors.onSurfaceVariant,
                  colors.onInverseSurface,
                  colors.onPrimary,
                  colors.onPrimaryContainer,
                  colors.onPrimaryFixed,
                  colors.onPrimaryFixedVariant,
                  colors.onSecondary,
                  colors.onSecondaryContainer,
                  colors.onSecondaryFixed,
                  colors.onSecondaryFixedVariant,
                  colors.onTertiary,
                  colors.onTertiaryContainer,
                  colors.onTertiaryFixed,
                  colors.onTertiaryFixedVariant,
                  theme.iconTheme.color ?? colors.onSurface,
                  theme.primaryIconTheme.color ?? colors.onPrimary,
                ]),
            for (final extension in theme.extensions.values)
              if (extension is ElevatedColorScheme)
                ElevatedColorScheme(colorScheme(extension.colorScheme))
              else if (extension is! _ForegroundSources)
                extension,
          ],
          iconTheme: theme.iconTheme.copyWith(
            color: cap(theme.iconTheme.color ?? colors.onSurface),
          ),
          primaryIconTheme: theme.primaryIconTheme.copyWith(
            color: cap(theme.primaryIconTheme.color ?? colors.onPrimary),
          ),
          textButtonTheme: TextButtonThemeData(
            style: _button(theme.textButtonTheme.style, colors.primary, colors),
          ),
          outlinedButtonTheme: OutlinedButtonThemeData(
            style: _button(
              theme.outlinedButtonTheme.style,
              colors.primary,
              colors,
            ),
          ),
          elevatedButtonTheme: ElevatedButtonThemeData(
            style: _button(
              theme.elevatedButtonTheme.style,
              colors.primary,
              colors,
            ),
          ),
          filledButtonTheme: FilledButtonThemeData(
            style: _button(theme.filledButtonTheme.style, null, colors),
          ),
          iconButtonTheme: IconButtonThemeData(
            style: _button(
              theme.iconButtonTheme.style,
              colors.onSurfaceVariant,
              colors,
            ),
          ),
          floatingActionButtonTheme: theme.floatingActionButtonTheme.copyWith(
            foregroundColor: cap(
              theme.floatingActionButtonTheme.foregroundColor ??
                  colors.onPrimaryContainer,
            ),
          ),
          appBarTheme: theme.appBarTheme.copyWith(
            foregroundColor: cap(
              theme.appBarTheme.foregroundColor ?? colors.onSurface,
            ),
            titleTextStyle: theme.appBarTheme.titleTextStyle == null
                ? null
                : textStyle(theme.appBarTheme.titleTextStyle!),
          ),
        )
        .withForegroundAlphaCap(opacity);
  }
}

class _ForegroundSources extends ThemeExtension<_ForegroundSources> {
  const _ForegroundSources(this.colors);

  final List<Color> colors;

  @override
  _ForegroundSources copyWith({List<Color>? colors}) =>
      _ForegroundSources(colors ?? this.colors);

  @override
  _ForegroundSources lerp(_ForegroundSources? other, double t) => other == null
      ? this
      : _ForegroundSources([
          for (var i = 0; i < colors.length; i++)
            Color.lerp(colors[i], other.colors[i], t)!,
        ]);
}

class GlassForegroundText extends StatelessWidget {
  const GlassForegroundText({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    final enabled =
        LiquidGlassScope.of(context) &&
        MediaQuery.maybeHighContrastOf(context) != true;
    final original = DefaultTextStyle.of(context).style;
    return DefaultTextStyle.merge(
      style: enabled ? GlassForeground.textStyle(original) : original,
      child: child,
    );
  }
}
