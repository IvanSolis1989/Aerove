import 'dart:async';

import 'package:fl_clash/common/preferences.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

const _homeModes = {NavigationItemMode.mobile, NavigationItemMode.desktop};

/// A degraded boot opens the dashboard: the saved page may be what crashed.
Future<void> restoreLastPage(
  ProviderContainer container, {
  required bool isAndroid,
  required bool degraded,
}) async {
  if (!isAndroid) return;
  if (degraded) {
    await preferences.saveLastPageLabel(
      container.read(currentPageLabelProvider),
    );
  } else {
    await _restore(container);
  }
  container.listen(currentPageLabelProvider, (_, label) {
    unawaited(preferences.saveLastPageLabel(label));
  });
}

Future<void> _restore(ProviderContainer container) async {
  final name = await preferences.getLastPageLabel();
  final saved = PageLabel.values.asNameMap()[name];
  final exists = container
      .read(navigationItemsStateProvider)
      .value
      .any(
        (item) => item.label == saved && item.modes.any(_homeModes.contains),
      );
  if (saved != null && exists) {
    container.read(currentPageLabelProvider.notifier).value = saved;
  }
}
