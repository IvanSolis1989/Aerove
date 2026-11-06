part of '../action.dart';

class _StagedRuleSet {
  _StagedRuleSet(this.provider, this.source, this.target, this.compiledTarget);

  final ClashProvider provider;
  final String source;
  final String target;
  final String compiledTarget;

  Future<void> commit() async {
    // A format change must finish both files before its metadata is published.
    if (provider.isCompiled(ClientProduct.current.expectedCoreId)) {
      await File('$source.mrs').rename(compiledTarget);
    } else {
      await File(compiledTarget).safeDelete();
    }
    await File(source).rename(target);
  }

  Future<void> cleanup() async {
    await File(source).safeDelete();
    await File('$source.mrs').safeDelete();
  }
}

@Riverpod(keepAlive: true)
class ClashProvidersAction extends _$ClashProvidersAction {
  final _pendingAutomaticApplies = <String>{};
  @visibleForTesting
  Future<void> Function(String phase)? meowRulePublicationBarrier;
  @visibleForTesting
  void Function()? meowRuleCommitAdmitted;

  @override
  void build() {}

  CoreController get _core => ref.read(coreHandlerProvider);

  Future<ClashProvider> putProvider(
    ClashProvider provider, {
    ClashProvider? previous,
    List<int>? content,
    bool refresh = false,
    bool Function()? isCurrent,
  }) async {
    if (ClientProduct.current.expectedCoreId == 'meow-rs') {
      return _putMeowProvider(
        provider,
        previous: previous,
        content: content,
        refresh: refresh,
        isCurrent: isCurrent,
      );
    }
    checkUpdateCurrent(isCurrent);
    final before = await _stored(provider.id);
    checkUpdateCurrent(isCurrent);
    final runtimeProvider =
        ClientProduct.current.expectedCoreId == 'meow-rs' &&
            before != null &&
            await _currentUses(
              (profileId) =>
                  _isReferenced(profileId, ProviderKind.rule, {before.label}),
            )
        ? await _core.getExternalProvider(before.label)
        : null;
    final download =
        provider.isRemote &&
        (refresh || provider.fileName != previous?.fileName);
    final staged = content == null && download && isCurrent != null
        ? await _stageAutomatic(provider, isCurrent)
        : null;
    try {
      final compiled =
          staged?.provider ??
          switch (content) {
            final content? => await _compileContent(provider, content),
            null when download => await _compile(provider, download: true),
            null => provider,
          };
      checkUpdateCurrent(isCurrent);
      final latest = before == null ? null : await _stored(provider.id);
      checkUpdateCurrent(isCurrent);
      if (before != null &&
          (latest == null || latest.fileName != before.fileName)) {
        if (latest?.fileName != compiled.fileName) {
          await _clearCache(compiled);
        }
        return latest ?? compiled;
      }
      final next = switch ((before, latest)) {
        (final before?, final latest?) => latest.copyWith(
          label: compiled.label == before.label ? latest.label : compiled.label,
          url: compiled.url,
          behavior: compiled.behavior,
          format: compiled.format,
        ),
        _ => compiled,
      };
      if (staged != null) {
        await ref
            .read(setupActionProvider.notifier)
            .withProviderSourceWrite(staged.commit);
        _pendingAutomaticApplies.addAll({next.label, ?previous?.label});
      }
      await ref.read(setupActionProvider.notifier).withProviderSourceWrite(
        () async {
          await ref.read(clashProvidersProvider.notifier).put(next);
        },
      );
      checkUpdateCurrent(isCurrent);
      if (previous != null && previous.fileName != next.fileName) {
        await _clearCache(previous);
      }
      final reread = content != null || download;
      final apply = reread && next == before
          ? _reloadIfReferenced(
              next,
              isCurrent: isCurrent,
              expectedRuntimeRevision: runtimeProvider?.runtimeRevision,
            )
          : applyIfReferenced(
              ProviderKind.rule,
              {next.label, ?previous?.label},
              force: reread,
              isCurrent: isCurrent,
            );
      if (isCurrent == null &&
          !(ClientProduct.current.expectedCoreId == 'meow-rs' && reread)) {
        unawaited(apply);
      } else {
        await apply;
        _pendingAutomaticApplies.removeAll({next.label, ?previous?.label});
      }
      return next;
    } finally {
      await staged?.cleanup();
    }
  }

  Future<
    ({
      DateTime changed,
      DateTime modified,
      int size,
      int mode,
      FileSystemEntityType type,
    })
  >
  _meowRuleStamp(String path) async {
    final stat = await File(path).stat();
    return (
      changed: stat.changed,
      modified: stat.modified,
      size: stat.size,
      mode: stat.mode,
      type: await FileSystemEntity.type(path, followLinks: false),
    );
  }

  Future<String?> _meowRuleDigest(String path) async {
    final file = File(path);
    await nativeLitePrivateDestination(
      file,
      Directory(await appPath.homeDirPath),
    );
    final type = await FileSystemEntity.type(path, followLinks: false);
    if (type == FileSystemEntityType.notFound) return null;
    if (type != FileSystemEntityType.file)
      throw StateError('Rule cache is not a regular file');
    if (await file.length() > 64 * 1024 * 1024)
      throw StateError('Rule cache exceeds its input budget');
    final before = await _meowRuleStamp(path);
    var bytes = 0;
    final digest = await sha256
        .bind(
          file.openRead().map((chunk) {
            bytes += chunk.length;
            if (bytes > 64 * 1024 * 1024)
              throw StateError('Rule cache exceeds its input budget');
            return chunk;
          }),
        )
        .first;
    if (await _meowRuleStamp(path) != before)
      throw StateError('Rule cache changed while hashing');
    return digest.toString();
  }

  Future<({ClashProvider provider, String path, String digest})> _stageMeowRule(
    ClashProvider provider,
    List<int>? content,
    bool download,
    bool Function()? isCurrent,
  ) async {
    final name = '${provider.fileName}.${snowflake.id}.new';
    final path = await appPath.getProviderCachePath(name);
    final file = File(path);
    CoreRequestQuiescence? request;
    var created = false;
    var retained = false;
    IOSink? output;
    try {
      checkUpdateCurrent(isCurrent);
      await nativeLitePrivateDestination(
        file,
        Directory(await appPath.homeDirPath),
      );
      await file.parent.create(recursive: true);
      await file.create(exclusive: true);
      created = true;
      if (!download) {
        output = file.openWrite();
        var bytes = 0;
        final input = content == null
            ? File(await provider.path).openRead()
            : Stream<List<int>>.value(content);
        await for (final chunk in input) {
          checkUpdateCurrent(isCurrent);
          bytes += chunk.length;
          if (bytes > 64 * 1024 * 1024)
            throw StateError('Rule candidate exceeds its input budget');
          output.add(chunk);
          await output.flush();
        }
        await output.close();
        output = null;
      }
      String? inspected;
      final info = await _core.compileRuleSet(
        name,
        url: download ? provider.url : '',
        observeQuiescence: (owner) => request = owner,
        inspectedDigest: (digest) => inspected = digest,
      );
      checkUpdateCurrent(isCurrent);
      final actual = await _meowRuleDigest(path);
      if (inspected == null || actual != inspected)
        throw StateError('Inspected rule candidate changed');
      retained = true;
      return (
        provider: provider.withInfo(info),
        path: path,
        digest: inspected!,
      );
    } finally {
      await output?.close();
      if (created && !retained && (request == null || request!.isQuiescent)) {
        await file.safeDelete();
      }
    }
  }

  Future<ClashProvider?> _sqlMeowRule(int id) async {
    final row = await (database.select(
      database.clashProviders,
    )..where((table) => table.id.equals(id))).getSingleOrNull();
    if (row != null && row.kind != ProviderKind.rule)
      throw StateError('SQL rule identity belongs to another resource kind');
    return row?.toClashProvider();
  }

  Future<ClashProvider> _putMeowProvider(
    ClashProvider provider, {
    ClashProvider? previous,
    List<int>? content,
    bool refresh = false,
    bool inspectExisting = false,
    bool reload = true,
    bool Function()? isCurrent,
  }) async {
    final setup = ref.read(setupActionProvider.notifier);
    final target = await provider.path;
    final baseline = await setup.withProviderSourceWrite(() async {
      checkUpdateCurrent(isCurrent);
      final before = await _sqlMeowRule(provider.id);
      final sourceDigest = before == null
          ? null
          : await _meowRuleDigest(await before.path);
      final targetDigest = await _meowRuleDigest(target);
      await meowRulePublicationBarrier?.call('after-baseline');
      return (
        before: before,
        sourceDigest: sourceDigest,
        targetDigest: targetDigest,
      );
    });
    final before = baseline.before;
    final download =
        content == null &&
        provider.isRemote &&
        (refresh ||
            baseline.targetDigest == null ||
            !inspectExisting && provider.fileName != previous?.fileName);
    final staged = content != null || download || inspectExisting
        ? await _stageMeowRule(provider, content, download, isCurrent)
        : null;
    final next = staged?.provider ?? provider;
    final publishRaw = staged != null && staged.digest != baseline.targetDigest;
    final rollback = File('${staged?.path ?? target}.rollback');
    var published = false;
    var commitAdmitted = false;
    var preserveRollback = false;
    var createdRollback = false;
    try {
      await setup.withProviderSourceWrite(() async {
        try {
          final targetStamp = await _meowRuleStamp(target);
          final sourceStamp = before == null
              ? null
              : await _meowRuleStamp(await before.path);
          final candidateStamp = staged == null
              ? null
              : await _meowRuleStamp(staged.path);
          if (await _meowRuleDigest(target) != baseline.targetDigest ||
              before != null &&
                  await _meowRuleDigest(await before.path) !=
                      baseline.sourceDigest)
            throw StateError('SQL rule source changed during preparation');
          if (staged != null) {
            if (await _meowRuleDigest(staged.path) != staged.digest)
              throw StateError('Rule candidate changed before publication');
            if (publishRaw && baseline.targetDigest != null) {
              await nativeLitePrivateDestination(
                rollback,
                Directory(await appPath.homeDirPath),
              );
              await rollback.create(exclusive: true);
              createdRollback = true;
              await File(target).copy(rollback.path);
              if (await _meowRuleDigest(rollback.path) != baseline.targetDigest)
                throw StateError('Rule rollback snapshot changed');
            }
          }
          await ref
              .read(clashProvidersProvider.notifier)
              .put(
                next,
                previous: before,
                beforePersist: () async {
                  checkUpdateCurrent(isCurrent);
                  final current = await _sqlMeowRule(provider.id);
                  if (current != before ||
                      await _meowRuleStamp(target) != targetStamp ||
                      before != null &&
                          await _meowRuleStamp(await before.path) !=
                              sourceStamp ||
                      staged != null &&
                          await _meowRuleStamp(staged.path) != candidateStamp)
                    throw StateError('SQL rule source changed before commit');
                  await meowRulePublicationBarrier?.call('after-final-stamp');
                  if (staged != null && publishRaw) {
                    await File(staged.path).rename(target);
                    published = true;
                  }
                  await meowRulePublicationBarrier?.call('after-rename');
                },
                beforeCommit: () {
                  checkUpdateCurrent(isCurrent);
                  commitAdmitted = true;
                  meowRuleCommitAdmitted?.call();
                },
              );
        } catch (failure, stack) {
          if (published && staged != null) {
            try {
              if (await _meowRuleDigest(target) != staged.digest) {
                preserveRollback = true;
                throw StateError(
                  'Rule rollback refused to overwrite a third value; snapshot: ${rollback.path}',
                );
              }
              if (baseline.targetDigest == null) {
                await File(target).safeDelete();
              } else {
                if (await _meowRuleDigest(rollback.path) !=
                    baseline.targetDigest) {
                  preserveRollback = true;
                  throw StateError(
                    'Rule rollback snapshot no longer matches the original',
                  );
                }
                await rollback.rename(target);
              }
            } catch (rollbackFailure) {
              preserveRollback = true;
              Error.throwWithStackTrace(
                StateError(
                  'SQL rule publication failed: $failure; rollback failed: $rollbackFailure; retained snapshot: ${rollback.path}',
                ),
                stack,
              );
            }
          }
          rethrow;
        }
        await meowRulePublicationBarrier?.call('before-commit-readback');
        if (await _sqlMeowRule(next.id) != next ||
            await _meowRuleDigest(target) !=
                (staged?.digest ?? baseline.targetDigest)) {
          preserveRollback = true;
          throw StateError(
            'Committed SQL rule publication changed before readback; retained snapshot: ${rollback.path}',
          );
        }
        if (before != null &&
            before.fileName != next.fileName &&
            await _meowRuleDigest(await before.path) == baseline.sourceDigest) {
          await File(await before.path).safeDelete();
          await File(await before.compiledPath).safeDelete();
        }
      });
      if (reload) {
        final labels = {next.label, ?before?.label};
        if (staged != null && isCurrent != null)
          _pendingAutomaticApplies.addAll(labels);
        if (commitAdmitted && !(isCurrent?.call() ?? true)) return next;
        final reread = staged != null;
        try {
          if (reread && next == before) {
            await _reloadIfReferenced(next, isCurrent: isCurrent);
          } else {
            await applyIfReferenced(
              ProviderKind.rule,
              {next.label, ?before?.label},
              force: reread,
              isCurrent: isCurrent,
            );
          }
        } on UpdateCancelledException {
          if (commitAdmitted) return next;
          rethrow;
        }
        _pendingAutomaticApplies.removeAll(labels);
      }
      return next;
    } finally {
      if (staged != null && await _meowRuleDigest(staged.path) == staged.digest)
        await File(staged.path).safeDelete();
      if (createdRollback && !preserveRollback) await rollback.safeDelete();
    }
  }

  Future<void> resumeAutomaticUpdates({
    required bool Function() isCurrent,
  }) async {
    checkUpdateCurrent(isCurrent);
    if (_pendingAutomaticApplies.isEmpty) return;
    final labels = Set<String>.of(_pendingAutomaticApplies);
    await applyIfReferenced(
      ProviderKind.rule,
      labels,
      force: true,
      isCurrent: isCurrent,
    );
    _pendingAutomaticApplies.removeAll(labels);
  }

  /// Downloads outlast edits, so a result lands on the set as it is by then.
  Future<ClashProvider?> _stored(int id) async {
    final rows =
        ref.read(clashProvidersProvider).value ??
        await database.clashProvidersDao.query().get();
    return rows.where((item) => item.id == id).firstOrNull;
  }

  Future<ClashProvider> _compile(
    ClashProvider provider, {
    bool download = false,
  }) => !download
      ? _compileSource(provider)
      : ref
            .read(setupActionProvider.notifier)
            .withProviderSourceWrite(
              () => _compileSource(provider, download: true),
            );

  Future<ClashProvider> _compileSource(
    ClashProvider provider, {
    bool download = false,
  }) async {
    // Made here, or a Core running as root would create it as its own.
    await File(await provider.path).parent.create(recursive: true);
    final info = await _core.compileRuleSet(
      provider.fileName,
      url: download ? provider.url : '',
    );
    final next = provider.withInfo(info);
    if (!next.isCompiled(ClientProduct.current.expectedCoreId)) {
      await File(await next.compiledPath).safeDelete();
    }
    return next;
  }

  Future<_StagedRuleSet> _stageAutomatic(
    ClashProvider provider,
    bool Function() isCurrent,
  ) async {
    checkUpdateCurrent(isCurrent);
    final name = '${provider.fileName}.${snowflake.id}.new';
    final path = await appPath.getProviderCachePath(name);
    final staged = File(path);
    try {
      checkUpdateCurrent(isCurrent);
      await staged.parent.create(recursive: true);
      checkUpdateCurrent(isCurrent);
      final info = await _core.compileRuleSet(name, url: provider.url);
      checkUpdateCurrent(isCurrent);
      final next = provider.withInfo(info);
      final compiledPath = await next.compiledPath;
      checkUpdateCurrent(isCurrent);
      final targetPath = await next.path;
      checkUpdateCurrent(isCurrent);
      return _StagedRuleSet(next, path, targetPath, compiledPath);
    } catch (_) {
      await staged.safeDelete();
      await File('$path.mrs').safeDelete();
      rethrow;
    }
  }

  Future<ClashProvider> _compileContent(
    ClashProvider provider,
    List<int> content,
  ) => ref
      .read(setupActionProvider.notifier)
      .withProviderSourceWrite(() => _compileContentSource(provider, content));

  Future<ClashProvider> _compileContentSource(
    ClashProvider provider,
    List<int> content,
  ) async {
    final stagedName = '${provider.fileName}.new';
    final staged = await appPath.getProviderCachePath(stagedName);
    try {
      await File(staged).safeWriteAsBytes(content);
      final next = provider.withInfo(await _core.compileRuleSet(stagedName));
      if (next.isCompiled(ClientProduct.current.expectedCoreId)) {
        await File('$staged.mrs').rename(await next.compiledPath);
      } else {
        await File(await next.compiledPath).safeDelete();
      }
      await File(staged).rename(await next.path);
      return next;
    } finally {
      await File(staged).safeDelete();
      await File('$staged.mrs').safeDelete();
    }
  }

  /// A subscription's set can share the name, so match by path.
  Future<ClashProvider?> remoteProviderOf(ExternalProvider external) async {
    final path = external.path;
    if (external.type != 'Rule' ||
        external.vehicleType != 'File' ||
        path == null) {
      return null;
    }
    for (final provider in await database.clashProvidersDao.query().get()) {
      if (provider.isRemote &&
          await provider.corePath(ClientProduct.current.expectedCoreId) ==
              path) {
        return provider;
      }
    }
    return null;
  }

  /// Returns false when the set changed shape, which only a reapply loads.
  Future<bool> syncRemote(ClashProvider provider) async {
    if (ClientProduct.current.expectedCoreId == 'meow-rs') {
      final next = await _putMeowProvider(provider, refresh: true);
      return next == provider;
    }
    final next = await _compile(provider, download: true);
    if (next == provider) {
      return true;
    }
    await database.clashProviders.put(next.toCompanion());
    unawaited(applyIfReferenced(ProviderKind.rule, {next.label}, force: true));
    return false;
  }

  /// Backups leave remote sets out and older sets have no mrs yet.
  Future<ClashProvider> prepare(ClashProvider provider) async {
    if (ClientProduct.current.expectedCoreId == 'meow-rs') {
      return _putMeowProvider(provider, inspectExisting: true, reload: false);
    }
    if (await File(
      await provider.corePath(ClientProduct.current.expectedCoreId),
    ).exists()) {
      return provider;
    }
    try {
      final next = await _compile(
        provider,
        download:
            provider.isRemote && !await File(await provider.path).exists(),
      );
      if (next != provider) {
        await database.clashProviders.put(next.toCompanion());
      }
      return next;
    } catch (e) {
      commonPrint.log(
        'prepare rule set ${provider.label}: $e',
        logLevel: LogLevel.warning,
      );
      return provider;
    }
  }

  void delProvider(ClashProvider provider) {
    unawaited(
      ref.read(setupActionProvider.notifier).withProviderSourceWrite(() async {
        await ref.read(clashProvidersProvider.notifier).del(provider.id);
        await _clearCacheSource(provider);
      }),
    );
  }

  Future<List<Profile>> profilesUsing(ClashProvider provider) {
    return profilesReferencing(ProviderKind.rule, provider.label);
  }

  Future<List<Profile>> profilesReferencing(
    ProviderKind kind,
    String name,
  ) async {
    final ids = switch (kind) {
      ProviderKind.proxy => await database.proxyGroupsDao.profileIdsUsing(name),
      ProviderKind.rule => await database.rulesDao.profileIdsUsingRuleProvider(
        name,
      ),
    };
    return [
      for (final profile in ref.read(profilesProvider))
        if (ids.contains(profile.id) ||
            kind == ProviderKind.rule &&
                profile.type == ProfileType.custom &&
                profile.overrides.ruleSets.contains(name))
          profile,
    ];
  }

  Future<void> _clearCache(ClashProvider provider) async {
    await ref
        .read(setupActionProvider.notifier)
        .withProviderSourceWrite(() => _clearCacheSource(provider));
  }

  Future<void> _clearCacheSource(ClashProvider provider) async {
    await File(await provider.path).safeDelete();
    await File(await provider.compiledPath).safeDelete();
  }

  Future<bool> _isReferenced(
    int profileId,
    ProviderKind kind,
    Set<String> labels,
  ) async {
    return switch (kind) {
      ProviderKind.proxy =>
        (await database.proxyGroupsDao.query(profileId).get()).any(
          (group) => group.use?.any(labels.contains) ?? false,
        ),
      ProviderKind.rule =>
        (ref
                    .read(profilesProvider)
                    .getProfile(profileId)
                    ?.overrides
                    .ruleSets
                    .any(labels.contains) ??
                false) ||
            (await database.rulesDao.queryProfileRules(profileId).get()).any(
              (rule) => rule.ruleSets.any(labels.contains),
            ),
    };
  }

  /// [force] reapplies a provider whose content changed under the same path,
  /// which leaves the generated config unchanged.
  Future<void> applyIfReferenced(
    ProviderKind kind,
    Set<String> labels, {
    bool force = false,
    bool Function()? isCurrent,
  }) => _applyIf(
    (profileId) => _isReferenced(profileId, kind, labels),
    force: force,
    isCurrent: isCurrent,
  );

  Future<void> applyIfProxiesNamed(Set<String> names) => _applyIf(
    (profileId) async => (await database.proxyGroupsDao.query(profileId).get())
        .any((group) => group.proxies?.any(names.contains) ?? false),
  );

  Future<void> _applyIf(
    Future<bool> Function(int profileId) isReferenced, {
    bool force = false,
    bool Function()? isCurrent,
  }) async {
    checkUpdateCurrent(isCurrent);
    if (!await _currentUses(isReferenced)) {
      return;
    }
    await _applyCurrent(force: force, isCurrent: isCurrent);
  }

  /// A reapply would close every connection and reset the route, while the
  /// Core rereads a file set it runs under the same definition in place.
  Future<void> _reloadIfReferenced(
    ClashProvider provider, {
    bool Function()? isCurrent,
    String? expectedRuntimeRevision,
  }) async {
    checkUpdateCurrent(isCurrent);
    if (!await _currentUses(
      (profileId) =>
          _isReferenced(profileId, ProviderKind.rule, {provider.label}),
    )) {
      return;
    }
    checkUpdateCurrent(isCurrent);
    if (ClientProduct.current.expectedCoreId == 'meow-rs') {
      final bytes = await File(await provider.path).readAsBytes();
      checkUpdateCurrent(isCurrent);
      await ref
          .read(setupActionProvider.notifier)
          .updateMeowProvider(
            namespace: 'rule-providers',
            name: provider.label,
            replacementBytes: bytes,
            isCurrent: isCurrent,
            expectedRuntimeRevision: expectedRuntimeRevision,
          );
      return;
    }
    try {
      final message = await _core.updateExternalProvider(
        providerName: provider.label,
      );
      if (message.isEmpty) {
        return;
      }
    } catch (error) {
      commonPrint.log(
        'reload ${provider.label} failed: $error',
        logLevel: coreFailureLogLevel(error),
      );
    }
    await _applyCurrent(force: true, isCurrent: isCurrent);
  }

  Future<bool> _currentUses(
    Future<bool> Function(int profileId) isReferenced,
  ) async {
    final profile = ref.read(currentProfileProvider);
    return profile != null &&
        profile.type == ProfileType.custom &&
        await isReferenced(profile.id);
  }

  Future<void> _applyCurrent({
    required bool force,
    bool Function()? isCurrent,
  }) async {
    checkUpdateCurrent(isCurrent);
    final setup = ref.read(setupActionProvider.notifier);
    if (isCurrent == null) {
      setup.applyProfileDebounce(silence: true, force: force);
    } else {
      await setup.applyProfile(silence: true, force: force);
    }
  }
}
