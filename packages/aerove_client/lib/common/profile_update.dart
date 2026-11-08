import 'dart:async';

import 'package:dio/dio.dart';
import 'package:fl_clash/models/common.dart';
import 'package:fl_clash/models/profile.dart';

class ProfileUpdateIntent {
  ProfileUpdateIntent({required this.isSourceCurrent, required this.automatic});

  final bool Function() isSourceCurrent;
  final bool automatic;
  final cancelToken = CancelToken();
  final _done = Completer<void>();
  // Set only from publishUpdate after its SQL transaction returns successfully.
  Profile? committedProfile;
  bool _committing = false;
  bool _cancelled = false;

  Future<void> get done => _done.future;
  bool get cancelled => _cancelled;
  bool get isCurrent => !_cancelled && isSourceCurrent();

  void cancel() {
    if (_committing || _cancelled) return;
    _cancelled = true;
    if (!cancelToken.isCancelled) cancelToken.cancel();
  }

  // After synchronous rename admission, cancellation joins the committed item.
  void beginCommit() {
    checkUpdateCurrent(() => isCurrent);
    _committing = true;
  }

  void finish() {
    if (!_done.isCompleted) _done.complete();
  }
}
