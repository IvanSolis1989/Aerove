import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

final maintenanceVisibleProvider = NotifierProvider<MaintenanceVisible, bool>(
  MaintenanceVisible.new,
);

class MaintenanceVisible extends Notifier<bool> {
  @override
  bool build() => true;

  void setLifecycle(AppLifecycleState? lifecycle) {
    state = switch (lifecycle) {
      null || AppLifecycleState.resumed || AppLifecycleState.inactive => true,
      AppLifecycleState.hidden ||
      AppLifecycleState.paused ||
      AppLifecycleState.detached => false,
    };
  }
}
