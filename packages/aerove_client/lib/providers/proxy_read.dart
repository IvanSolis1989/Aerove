import 'package:fl_clash/common/mixin.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'generated/proxy_read.g.dart';

@Riverpod(keepAlive: true)
class GroupsReadFailed extends _$GroupsReadFailed
    with AutoDisposeNotifierMixin {
  @override
  bool build() => false;
}
