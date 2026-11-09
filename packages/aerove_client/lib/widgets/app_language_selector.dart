import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// A language choice is available before consent as well as in Tools.
class AppLanguageSelector extends ConsumerWidget {
  const AppLanguageSelector({super.key});

  String _getLocaleString(BuildContext context, Locale? locale) {
    if (locale == null) return context.appLocalizations.defaultText;
    if (locale == const Locale('zh', 'CN')) return '简体中文';
    if (locale == const Locale('en')) return 'English';
    return locale.label;
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final locale = ref.watch(
      appSettingProvider.select((state) => state.locale),
    );
    final currentLocale = getLocaleForString(locale);
    final locales = AppLocalizations.delegate.supportedLocales;
    final primary = {const Locale('en'), const Locale('zh', 'CN')};
    return ListItem<Locale?>.options(
      leading: const GlyphIcon(AppGlyphs.language),
      title: GlassText(context.appLocalizations.language),
      options: [
        ...locales.where(primary.contains),
        null,
        ...locales.where((locale) => !primary.contains(locale)),
      ],
      onChanged: (Locale? locale) {
        ref
            .read(appSettingProvider.notifier)
            .update((state) => state.copyWith(locale: locale?.toString()));
      },
      textBuilder: (locale) => _getLocaleString(context, locale),
      value: currentLocale,
    );
  }
}
