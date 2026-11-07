part of '../action.dart';

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
  }) => _putLibraryProvider(
    provider,
    previous: previous,
    content: content,
    refresh: refresh,
    isCurrent: isCurrent,
  );

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

  Future<({ClashProvider provider, String path, String digest})> _stageRule(
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
    var compileEnded = false;
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
      late RuleSetInfo info;
      try {
        info = await _core.compileRuleSet(
          name,
          url: download ? provider.url : '',
          observeQuiescence: (owner) => request = owner,
          inspectedDigest: (digest) => inspected = digest,
        );
        compileEnded = true;
      } on CoreMethodException catch (error) {
        if (ClientProduct.current.expectedCoreId == 'mihomo' &&
            {
              'rule_set_empty',
              'rule_set_mixed',
              'rule_set_invalid',
            }.contains(error.code))
          compileEnded = true;
        rethrow;
      }
      checkUpdateCurrent(isCurrent);
      final actual = await _meowRuleDigest(path);
      if (actual == null ||
          ClientProduct.current.expectedCoreId == 'meow-rs' &&
              (inspected == null || actual != inspected))
        throw StateError('Inspected rule candidate changed');
      retained = true;
      return (provider: provider.withInfo(info), path: path, digest: actual);
    } finally {
      await output?.close();
      if (created &&
          !retained &&
          (compileEnded ||
              ClientProduct.current.expectedCoreId == 'meow-rs' &&
                  (request == null || request!.isQuiescent))) {
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

  Future<List<RuleSqlRowChange>> _ruleFootprint(
    ClashProvider? before,
    ClashProvider? after,
  ) async {
    final id = (after ?? before)!.id;
    final raw = await database
        .customSelect(
          'SELECT * FROM clash_providers WHERE id = ?',
          variables: [Variable(id)],
        )
        .get();
    final next = after == null
        ? null
        : <String, dynamic>{
            'id': id,
            'kind': 'rule',
            'label': after.label,
            'source_definition': after.sourceDefinition == null
                ? null
                : const JsonMapConverter().toSql(after.sourceDefinition!),
            'url': after.url,
            'behavior': after.behavior.name,
            'format': after.format.name,
            'order': after.order,
          };
    final result = <RuleSqlRowChange>[
      RuleSqlRowChange(
        'clash_providers',
        id,
        raw.isEmpty ? null : Map<String, dynamic>.from(raw.single.data),
        next,
      ),
    ];
    if (before != null && after != null && before.label != after.label) {
      final names = {before.label: after.label};
      for (final profile in await database.profilesDao.query().get()) {
        if (profile.type != ProfileType.custom) continue;
        final updated = profile.overrides.renamedRuleSets(names);
        if (updated != profile.overrides) {
          final row =
              (await database
                      .customSelect(
                        'SELECT overrides FROM profiles WHERE id = ?',
                        variables: [Variable(profile.id)],
                      )
                      .get())
                  .single;
          result.add(
            RuleSqlRowChange(
              'profiles',
              profile.id,
              {'overrides': row.data['overrides']},
              {'overrides': const ProfileOverridesConverter().toSql(updated)},
            ),
          );
        }
        for (final rule
            in await database.rulesDao.queryProfileRules(profile.id).get()) {
          final changed = rule.renamedRuleSets(names);
          if (changed == rule) continue;
          final row =
              (await database
                      .customSelect(
                        'SELECT profile_id, rule_action, rule_provider, content FROM rules WHERE id = ?',
                        variables: [Variable(rule.id)],
                      )
                      .get())
                  .single;
          result.add(
            RuleSqlRowChange(
              'rules',
              rule.id,
              Map<String, dynamic>.from(row.data),
              {
                ...row.data,
                'rule_provider': changed.ruleProvider,
                'content': changed.content,
              },
            ),
          );
        }
      }
    }
    return result;
  }

  Future<ClashProvider> _putLibraryProvider(
    ClashProvider provider, {
    ClashProvider? previous,
    List<int>? content,
    bool refresh = false,
    bool inspectExisting = false,
    bool reload = true,
    bool Function()? isCurrent,
  }) async {
    final setup = ref.read(setupActionProvider.notifier);
    final baseline = await setup.withProviderSourceWrite(() async {
      checkUpdateCurrent(isCurrent);
      final before = await _sqlMeowRule(provider.id);
      final paths = <String>{
        await provider.path,
        await provider.compiledPath,
        if (before != null) await before.path,
        if (before != null) await before.compiledPath,
      };
      final digests = <String, String?>{};
      for (final path in paths) digests[path] = await _meowRuleDigest(path);
      await meowRulePublicationBarrier?.call('after-baseline');
      return (
        before: before,
        files: digests,
        incarnation: await database.ruleSqlIncarnation(),
      );
    });
    final download =
        content == null &&
        provider.isRemote &&
        (refresh ||
            baseline.files[await provider.path] == null ||
            !inspectExisting && provider.fileName != previous?.fileName);
    final staged = content != null || download || inspectExisting
        ? await _stageRule(provider, content, download, isCurrent)
        : null;
    var next = staged?.provider ?? provider;
    var committed = false;
    try {
      await setup.withProviderSourceWrite(() async {
        final latest = await _sqlMeowRule(provider.id);
        final before = baseline.before;
        if (before == null
            ? latest != null
            : latest == null ||
                  latest.copyWith(label: before.label, order: before.order) !=
                      before)
          throw StateError('Rule source changed during preparation');
        if (latest != null && before != null)
          next = latest.copyWith(
            label: provider.label == before.label ? latest.label : next.label,
            url: next.url,
            sourceDefinition: next.sourceDefinition,
            behavior: next.behavior,
            format: next.format,
          );
        for (final entry in baseline.files.entries) {
          if (await _meowRuleDigest(entry.key) != entry.value)
            throw StateError('Rule raw cache changed during preparation');
        }
        final files = <RuleSqlFileChange>[];
        if (staged != null) {
          files.add(RuleSqlFileChange(await next.path, staged.path));
          final compiled = next.isCompiled(
            ClientProduct.current.expectedCoreId,
          );
          if (compiled && await _meowRuleDigest('${staged.path}.mrs') == null)
            throw StateError('Core did not produce the required MRS');
          files.add(
            RuleSqlFileChange(
              await next.compiledPath,
              compiled ? '${staged.path}.mrs' : null,
            ),
          );
        }
        if (latest != null && latest.fileName != next.fileName) {
          files.add(RuleSqlFileChange(await latest.path, null));
          files.add(RuleSqlFileChange(await latest.compiledPath, null));
        }
        final owner = RuleSqlJournalOwner(
          database,
          Directory(await appPath.homeDirPath),
        );
        var unchanged = latest == next;
        for (final file in files) {
          if (await _meowRuleDigest(file.path) !=
              (file.after == null ? null : await _meowRuleDigest(file.after!)))
            unchanged = false;
        }
        if (unchanged) {
          checkUpdateCurrent(isCurrent);
          committed = true;
          return;
        }
        final priorPhase = owner.onPhase;
        owner.onPhase = (phase) async {
          await priorPhase?.call(phase);
          await meowRulePublicationBarrier?.call(
            phase == 'sql-ended' ? 'before-commit-readback' : phase,
          );
        };
        try {
          Future<RuleSqlPublicationResult> publish() => owner.publish(
            providerId: next.id,
            expectedIncarnation: baseline.incarnation,
            files: files,
            captureRows: () => _ruleFootprint(latest, next),
            transaction: (beforeFiles, finishSql) => ref
                .read(clashProvidersProvider.notifier)
                .put(
                  next,
                  previous: latest,
                  beforePersist: beforeFiles,
                  finishSql: finishSql,
                  beforeCommit: () {
                    checkUpdateCurrent(isCurrent);
                    committed = true;
                    meowRuleCommitAdmitted?.call();
                  },
                ),
          );
          final result = latest != null && latest.label != next.label
              ? await ref
                    .read(profilesProvider.notifier)
                    .withRulePublicationSerial(publish)
              : await publish();
          if (result.completionError != null)
            commonPrint.log(
              'Rule SQL committed with recovered completion error: ${result.completionError}',
              logLevel: LogLevel.warning,
            );
        } finally {
          owner.onPhase = priorPhase;
        }
      });
      if (reload) {
        final labels = {next.label, ?baseline.before?.label};
        if (staged != null && isCurrent != null)
          _pendingAutomaticApplies.addAll(labels);
        if (committed && !(isCurrent?.call() ?? true)) return next;
        try {
          if (staged != null && next == baseline.before)
            await _reloadIfReferenced(next, isCurrent: isCurrent);
          else
            await applyIfReferenced(
              ProviderKind.rule,
              labels,
              force: staged != null,
              isCurrent: isCurrent,
            );
        } on UpdateCancelledException {
          if (!committed) rethrow;
        }
        _pendingAutomaticApplies.removeAll(labels);
      }
      return next;
    } finally {
      if (staged != null && await _meowRuleDigest(staged.path) == staged.digest)
        await File(staged.path).safeDelete();
      if (staged != null) await File('${staged.path}.mrs').safeDelete();
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
    final next = await _putLibraryProvider(
      provider,
      previous: provider,
      refresh: true,
      reload: false,
    );
    if (next == provider) return true;
    unawaited(applyIfReferenced(ProviderKind.rule, {next.label}, force: true));
    return false;
  }

  Future<ClashProvider> prepare(ClashProvider provider) =>
      ref.read(setupActionProvider.notifier).withProviderSourceWrite(() async {
        final actual = await _sqlMeowRule(provider.id);
        if (actual == null)
          throw StateError('SQL rule source no longer exists');
        if (ClientProduct.current.expectedCoreId == 'mihomo' &&
            await _meowRuleDigest(await actual.path) != null &&
            await _meowRuleDigest(await actual.corePath('mihomo')) != null)
          return actual;
        try {
          return await _putLibraryProvider(
            actual,
            previous: actual,
            inspectExisting: true,
            reload: false,
          );
        } on CoreMethodException {
          // Preserve Mihomo's existing core-side validation/fetch fallback.
          // This branch publishes neither files nor SQL nor a compile receipt.
          if (ClientProduct.current.expectedCoreId == 'mihomo') return actual;
          rethrow;
        }
      });

  Future<void> delProvider(ClashProvider provider) {
    return ref.read(setupActionProvider.notifier).withProviderSourceWrite(
      () async {
        final before = await _sqlMeowRule(provider.id);
        if (before == null) return;
        if (before != provider)
          throw StateError('Rule deletion source changed');
        final owner = RuleSqlJournalOwner(
          database,
          Directory(await appPath.homeDirPath),
        );
        await owner.publish(
          providerId: provider.id,
          expectedIncarnation: await database.ruleSqlIncarnation(),
          files: [
            RuleSqlFileChange(await provider.path, null),
            RuleSqlFileChange(await provider.compiledPath, null),
          ],
          captureRows: () => _ruleFootprint(before, null),
          transaction: (publishFiles, finishSql) async {
            await database.transaction(() async {
              await publishFiles();
              await database.clashProviders.remove(
                (table) => table.id.equals(provider.id),
              );
              await finishSql();
            });
          },
        );
      },
    );
  }

  Future<FileInfo?> sourceFileInfo(ClashProvider provider) async {
    final owner = RuleSqlJournalOwner(
      database,
      Directory(await appPath.homeDirPath),
    );
    return owner.inspect(
      () async => (await _sqlMeowRule(provider.id))?.fileInfo,
    );
  }

  Future<T> withPreparedSource<T>(
    ClashProvider provider,
    Future<T> Function(ClashProvider) read,
  ) => ref
      .read(setupActionProvider.notifier)
      .withProviderSourceWrite(() async => read(await prepare(provider)));

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
