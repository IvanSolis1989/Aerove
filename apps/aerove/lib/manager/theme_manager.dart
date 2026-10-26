import 'dart:math';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/providers/action.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/foundation.dart'
    show defaultTargetPlatform, visibleForTesting;
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/state.dart';

const _pageTransitionsTheme = PageTransitionsTheme(
  builders: <TargetPlatform, PageTransitionsBuilder>{
    TargetPlatform.android: commonSharedXPageTransitions,
    TargetPlatform.windows: commonSharedXPageTransitions,
    TargetPlatform.linux: commonSharedXPageTransitions,
    TargetPlatform.macOS: commonSharedXPageTransitions,
  },
);

const _actionIconTheme = ActionIconThemeData(
  backButtonIconBuilder: _backButtonIcon,
  closeButtonIconBuilder: _closeButtonIcon,
);

Widget _backButtonIcon(BuildContext context) =>
    GlyphIcon(AppGlyphs.backFor(Theme.of(context).platform));

Widget _closeButtonIcon(BuildContext context) =>
    const GlyphIcon(AppGlyphs.close);

/// A rebuilt ThemeData never equals the last, and ThemeCrossFade fades on that.
final _appThemes = <Brightness, ({Object inputs, ThemeData theme})>{};

@visibleForTesting
bool? debugAndroidSystemFontsOverride;

bool get _androidSystemFonts =>
    debugAndroidSystemFontsOverride ?? system.isAndroid;

/// Only a chosen font is checked against the Android catalog; the default
/// resolves to null whatever the catalog holds.
bool needsSystemFontCatalog(String? selected, {required bool android}) =>
    android && selected != null;

ThemeData watchAppTheme(WidgetRef ref, Brightness brightness) {
  final locale = ref.watch(appSettingProvider.select((state) => state.locale));
  final selectedFont = ref.watch(
    themeSettingProvider.select((state) => state.fontFamily),
  );
  final fontFamily =
      needsSystemFontCatalog(selectedFont, android: _androidSystemFonts)
      ? resolveSystemFontFamily(
          selectedFont,
          ref.watch(installedSystemFontsProvider).asData?.value,
        )
      : selectedFont;
  final colorScheme = ref.watch(genColorSchemeProvider(brightness));
  final locales = [
    ?getLocaleForString(locale),
    ...WidgetsBinding.instance.platformDispatcher.locales,
  ];
  final inputs = (
    platform: defaultTargetPlatform,
    fontFamily: appFontFamily(
      platform: defaultTargetPlatform,
      selected: fontFamily,
    ),
    colorScheme: colorScheme,
    locales: locales.join(','),
  );
  if (_appThemes[brightness] case (
    inputs: final cached,
    :final theme,
  ) when cached == inputs) {
    return theme;
  }
  final theme = ThemeData(
    useMaterial3: true,
    pageTransitionsTheme: _pageTransitionsTheme,
    actionIconTheme: _actionIconTheme,
    colorScheme: colorScheme,
    textSelectionTheme: TextSelectionThemeData(
      cursorColor: colorScheme.primary,
      selectionColor: colorScheme.primary.withValues(alpha: 0.3),
      selectionHandleColor: colorScheme.primary,
    ),
    fontFamily: inputs.fontFamily,
    fontFamilyFallback: appFontFamilyFallback(
      platform: defaultTargetPlatform,
      locales: locales,
      fontFamily: fontFamily,
    ),
    extensions: [
      if (brightness == Brightness.dark)
        ElevatedColorScheme(colorScheme.elevated),
    ],
  ).withAppShapes.withAppSurfaces.withAppTypography;
  _appThemes[brightness] = (inputs: inputs, theme: theme);
  return theme;
}

class ThemeManager extends ConsumerStatefulWidget {
  final Widget child;

  const ThemeManager({super.key, required this.child});

  @override
  ConsumerState<ThemeManager> createState() => _ThemeManagerState();
}

class _ThemeManagerState extends ConsumerState<ThemeManager>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (_androidSystemFonts && state == AppLifecycleState.resumed) {
      ref.invalidate(installedSystemFontsProvider);
    }
  }

  @override
  void didChangeLocales(List<Locale>? locales) {
    if (_androidSystemFonts) ref.invalidate(installedSystemFontsProvider);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Widget _buildSystemUi(Widget child) {
    if (!system.isAndroid) {
      return child;
    }
    return Consumer(
      builder: (context, ref, _) {
        final brightness = ref.watch(currentBrightnessProvider);
        final iconBrightness = brightness == Brightness.light
            ? Brightness.dark
            : Brightness.light;
        return AnnotatedRegion<SystemUiOverlayStyle>(
          value: SystemUiOverlayStyle(
            statusBarColor: Colors.transparent,
            statusBarIconBrightness: iconBrightness,
            systemNavigationBarIconBrightness: iconBrightness,
            systemNavigationBarColor: Colors.transparent,
            systemNavigationBarDividerColor: Colors.transparent,
            // Pages scroll under the navigation bar. Android scrims only a
            // three-button bar for it; the gesture handle stays clear.
            systemNavigationBarContrastEnforced: true,
          ),
          sized: false,
          child: child,
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final textScale = ref.read(
      themeSettingProvider.select((state) => state.textScale),
    );
    final double textScaleFactor = max(
      min(
        textScale.enable ? textScale.scale : systemTextScaleOf(context),
        maxTextScale,
      ),
      minTextScale,
    );

    final measure = Measure.of(context, textScaleFactor);
    globalState.measure = measure;
    return MeasureScope(
      measure: measure,
      child: AppViewScope(
        child: _AppMediaQuery(
          textScaler: TextScaler.linear(textScaleFactor),
          child: LayoutBuilder(
            builder: (_, constraints) {
              ref
                  .read(themeActionProvider.notifier)
                  .updateViewSize(
                    Size(constraints.maxWidth, constraints.maxHeight),
                  );
              return _buildSystemUi(widget.child);
            },
          ),
        ),
      ),
    );
  }
}

class AppViewScope extends ConsumerWidget {
  const AppViewScope({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isMobile = ref.watch(isMobileViewProvider);
    return ViewScope(
      size: ref.watch(viewSizeProvider),
      isMobile: isMobile,
      windowControls: windowControlsOverPage(
        isMacOS: system.isMacOS,
        version: ref.watch(versionProvider),
        isMobileView: isMobile,
      ),
      child: child,
    );
  }
}

class _AppMediaQuery extends StatelessWidget {
  const _AppMediaQuery({required this.textScaler, required this.child});

  final TextScaler textScaler;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final data = MediaQuery.of(context);
    final padding = data.padding;
    return MediaQuery(
      data: data.copyWith(
        textScaler: textScaler,
        padding: padding.copyWith(
          top: padding.top > data.size.height * 0.3 ? 20.0 : padding.top,
        ),
      ),
      child: child,
    );
  }
}
