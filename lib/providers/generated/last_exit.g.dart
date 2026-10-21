// GENERATED CODE - DO NOT MODIFY BY HAND

part of '../last_exit.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning
/// The last exit a probe confirmed, which the next launch shows as
/// provisional until its own probe answers.

@ProviderFor(LastExit)
final lastExitProvider = LastExitProvider._();

/// The last exit a probe confirmed, which the next launch shows as
/// provisional until its own probe answers.
final class LastExitProvider
    extends $NotifierProvider<LastExit, ExitSnapshot?> {
  /// The last exit a probe confirmed, which the next launch shows as
  /// provisional until its own probe answers.
  LastExitProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'lastExitProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$lastExitHash();

  @$internal
  @override
  LastExit create() => LastExit();

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(ExitSnapshot? value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<ExitSnapshot?>(value),
    );
  }
}

String _$lastExitHash() => r'3b666ac4805e76391b40ff95d98b46eac25aaf0b';

/// The last exit a probe confirmed, which the next launch shows as
/// provisional until its own probe answers.

abstract class _$LastExit extends $Notifier<ExitSnapshot?> {
  ExitSnapshot? build();
  @$mustCallSuper
  @override
  WhenComplete runBuild() {
    final ref = this.ref as $Ref<ExitSnapshot?, ExitSnapshot?>;
    final element =
        ref.element
            as $ClassProviderElement<
              AnyNotifier<ExitSnapshot?, ExitSnapshot?>,
              ExitSnapshot?,
              Object?,
              Object?
            >;
    return element.handleCreate(ref, build);
  }
}
