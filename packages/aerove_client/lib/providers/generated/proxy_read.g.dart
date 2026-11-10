// GENERATED CODE - DO NOT MODIFY BY HAND

part of '../proxy_read.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(GroupsReadFailed)
final groupsReadFailedProvider = GroupsReadFailedProvider._();

final class GroupsReadFailedProvider
    extends $NotifierProvider<GroupsReadFailed, bool> {
  GroupsReadFailedProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'groupsReadFailedProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$groupsReadFailedHash();

  @$internal
  @override
  GroupsReadFailed create() => GroupsReadFailed();

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(bool value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<bool>(value),
    );
  }
}

String _$groupsReadFailedHash() => r'302ccb5745006349ba2f58f82aef3f1d017309b4';

abstract class _$GroupsReadFailed extends $Notifier<bool> {
  bool build();
  @$mustCallSuper
  @override
  WhenComplete runBuild() {
    final ref = this.ref as $Ref<bool, bool>;
    final element =
        ref.element
            as $ClassProviderElement<
              AnyNotifier<bool, bool>,
              bool,
              Object?,
              Object?
            >;
    return element.handleCreate(ref, build);
  }
}
