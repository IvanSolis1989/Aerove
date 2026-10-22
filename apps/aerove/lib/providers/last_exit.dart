import 'dart:async';

import 'package:fl_clash/common/exit_snapshot.dart';
import 'package:fl_clash/common/preferences.dart';
import 'package:fl_clash/models/models.dart';
import 'package:riverpod/riverpod.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import 'config.dart';

part 'generated/last_exit.g.dart';

/// The last exit a probe confirmed, which the next launch shows as
/// provisional until its own probe answers.
@Riverpod(keepAlive: true)
class LastExit extends _$LastExit {
  @override
  ExitSnapshot? build() {
    ref.listen(appSettingProvider.select((state) => state.hideIp), (_, hide) {
      if (hide) _save(null);
    });
    return null;
  }

  bool get _hidden => ref.read(appSettingProvider).hideIp;

  Future<void> load() async {
    if (_hidden) {
      await preferences.saveExitSnapshot(null);
      return;
    }
    state = await preferences.getExitSnapshot();
  }

  void remember({
    required int profileId,
    required bool proxied,
    required IpInfo ip,
  }) {
    if (_hidden) return;
    _save(
      ExitSnapshot(
        profileId: profileId,
        proxied: proxied,
        ip: ip,
        quality: state?.qualityOf(ip.ip),
      ),
    );
  }

  void rememberQuality(IpQuality quality) {
    final snapshot = state;
    if (snapshot == null || snapshot.ip.ip != quality.ip) return;
    _save(snapshot.withQuality(quality));
  }

  void _save(ExitSnapshot? snapshot) {
    if (snapshot == state) return;
    state = snapshot;
    unawaited(preferences.saveExitSnapshot(snapshot));
  }
}
