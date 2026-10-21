import 'dart:async';

import 'package:fl_clash/common/preferences.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

const _homeModes = {NavigationItemMode.mobile, NavigationItemMode.desktop};

/// Reopens Android on the home page it was left on, while that page exists.
Future<void> restoreLastPage(
  ProviderContainer container, {
  required bool isAndroid,
}) async {
  if (!isAndroid) return;
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
  container.listen(currentPageLabelProvider, (_, label) {
    unawaited(preferences.saveLastPageLabel(label));
  });
}
