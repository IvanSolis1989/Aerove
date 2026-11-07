part of '../action.dart';

typedef BackupDelivery = Future<bool> Function(String archivePath);

/// Returns the archive to restore, or null when the user backed out. A source
/// that downloads writes to [downloadPath], which is removed afterwards.
typedef BackupFetch = Future<String?> Function(String downloadPath);

@Riverpod(keepAlive: true)
class BackupAction extends _$BackupAction {
  final _retainedRestoreWork = <String>{};
  Object? restoreCompletionError;
  StackTrace? restoreCompletionStack;
  @visibleForTesting
  Future<void> Function()? restoreAfterSql;
  @visibleForTesting
  Future<void> Function()? restoreBeforeDecisionRead;
  @override
  void build() {}

  Future<bool> backup(BackupDelivery deliver) {
    return _withWorkDir((workDir) async {
      return deliver(await _createArchive(workDir));
    });
  }

  Future<bool> restore(RestoreOption option, BackupFetch fetch) {
    return _withWorkDir((workDir) async {
      final archivePath = await fetch(join(workDir, 'download.zip'));
      if (archivePath == null) {
        return false;
      }
      final stagingDirPath = join(workDir, 'staging');
      final MigrationData data;
      try {
        data = await restoreTask((
          archivePath: archivePath,
          stagingDirPath: stagingDirPath,
        ));
      } on BackupException catch (e) {
        throw MessageException(switch (e.failure) {
          BackupFailure.invalid => currentAppLocalizations.invalidBackupFile,
          BackupFailure.newerVersion =>
            currentAppLocalizations.backupFromNewerVersion,
        });
      }
      await applyRestore(data, option, stagingDirPath: stagingDirPath);
      return true;
    });
  }

  Future<bool> _withWorkDir(Future<bool> Function(String workDir) run) async {
    final workDir = Directory(join(await appPath.tempPath, 'backup$uniqueId'));
    await workDir.create(recursive: true);
    try {
      return await run(workDir.path);
    } finally {
      if (!database.ruleSqlFence.isUncertain &&
          !_retainedRestoreWork.contains(workDir.path)) {
        await workDir.safeDelete(recursive: true);
        await _discardLegacyStaging();
      }
    }
  }

  /// Earlier versions staged restores in the home directory and never removed
  /// the downloaded archive.
  Future<void> _discardLegacyStaging() async {
    final homeDirPath = await appPath.homeDirPath;
    await File(join(homeDirPath, 'backup.zip')).safeDelete();
    await Directory(join(homeDirPath, 'restore')).safeDelete(recursive: true);
  }

  Future<String> _createArchive(String workDir) async {
    return ref
        .read(setupActionProvider.notifier)
        .withProviderSourceWrite(() => _createArchiveSnapshot(workDir));
  }

  Future<String> _createArchiveSnapshot(String workDir) async {
    final databaseSnapshotPath = join(workDir, backupDatabaseName);
    await database.customStatement('VACUUM INTO ?', [databaseSnapshotPath]);
    final (profileIds, scriptIds, providers) = await (
      database.profilesDao.query().map((item) => item.id).get(),
      database.scriptsDao.query().map((item) => item.id).get(),
      database.clashProvidersDao.query().get(),
    ).wait;
    final configMap = ref.read(configProvider).toJson();
    configMap['version'] = await preferences.getVersion();
    final archivePath = join(workDir, 'backup.zip');
    await backupTask((
      archivePath: archivePath,
      configMap: configMap,
      databaseSnapshotPath: databaseSnapshotPath,
      homeDirPath: await appPath.homeDirPath,
      entries: BackupEntries.of(
        profileIds: profileIds,
        scriptIds: scriptIds,
        providers: providers,
      ),
    ));
    return archivePath;
  }

  @visibleForTesting
  Future<void> applyRestore(
    MigrationData data,
    RestoreOption option, {
    String? stagingDirPath,
  }) => ref
      .read(setupActionProvider.notifier)
      .withProviderSourceWrite(
        () async =>
            RuleSqlJournalOwner(
              database,
              Directory(await appPath.homeDirPath),
            ).externalJournal(
              () => _applyRestoreSource(
                data,
                option,
                stagingDirPath: stagingDirPath,
              ),
            ),
      );

  Future<void> _applyRestoreSource(
    MigrationData data,
    RestoreOption option, {
    String? stagingDirPath,
  }) async {
    restoreCompletionError = null;
    restoreCompletionStack = null;
    final configMap = data.configMap;
    final config = option == RestoreOption.onlyProfiles || configMap == null
        ? null
        : Config.fromJson(configMap);
    final isOverride =
        ref.read(appSettingProvider).restoreStrategy ==
        RestoreStrategy.override;
    final previousProviders = await database.clashProvidersDao.query().get();
    final oldIncarnation = await database.ruleSqlIncarnation();
    final nextIncarnation = uuidV4;
    final putBack = stagingDirPath == null
        ? null
        : await _copyStagedFiles(data, stagingDirPath);
    try {
      await database.restore(
        data.profiles,
        data.scripts,
        data.rules,
        data.disabledRules,
        data.proxyGroups,
        clashProviders: data.clashProviders,
        customProxies: data.customProxies,
        proxyDialers: data.proxyDialers,
        isOverride: isOverride,
        restoreIncarnation: nextIncarnation,
      );
      await restoreAfterSql?.call();
    } catch (primary, stack) {
      if (database.ruleSqlFence.isUncertain) {
        if (stagingDirPath != null)
          _retainedRestoreWork.add(Directory(stagingDirPath).parent.path);
        Error.throwWithStackTrace(primary, stack);
      }
      try {
        await restoreBeforeDecisionRead?.call();
        final decision = await database.ruleSqlIncarnation();
        if (decision == oldIncarnation) {
          await putBack?.call();
          Error.throwWithStackTrace(primary, stack);
        }
        if (decision != nextIncarnation)
          throw StateError(
            'Restore incarnation acquired a third durable value',
          );
        restoreCompletionError = primary;
        restoreCompletionStack = stack;
        // Actual commit won. Keep its files and preserve the completion error.
        commonPrint.log(
          'Restore committed with completion error: ${compactError(primary)}',
          logLevel: LogLevel.warning,
        );
      } catch (resolution, resolutionStack) {
        if (identical(resolution, primary))
          Error.throwWithStackTrace(primary, stack);
        if (stagingDirPath != null)
          _retainedRestoreWork.add(Directory(stagingDirPath).parent.path);
        Error.throwWithStackTrace(
          RuleSqlPublicationFailure(
            primary,
            resolution,
            stagingDirPath ?? '',
            null,
          ),
          resolutionStack,
        );
      }
    }
    await _clearReplacedProviderCaches(previousProviders);
    await _clearOutdatedCompiledRuleSets(data.clashProviders);
    if (config == null) {
      return;
    }
    // A desktop backup carries no package lists; the apps picked here stay.
    final accessControlProps = config.vpnProps.accessControlProps.hasPackages
        ? config.vpnProps.accessControlProps
        : ref.read(vpnSettingProvider).accessControlProps;
    writeConfig(
      ref,
      config.copyWith(
        davProps: config.davProps ?? ref.read(davSettingProvider),
        vpnProps: config.vpnProps.copyWith(
          accessControlProps: accessControlProps,
        ),
      ),
    );
  }

  /// The files go in before the rows that name them, so the returned callback
  /// is how a restore whose rows fail takes them back out.
  Future<Future<void> Function()> _copyStagedFiles(
    MigrationData data,
    String stagingDirPath,
  ) async {
    final homeDirPath = await appPath.homeDirPath;
    final replacedDirPath = join(stagingDirPath, 'replaced');
    final copied = <String, ({String? before, String? after})>{};
    final home = Directory(homeDirPath);
    Future<String?> digest(File file, {Directory? boundary}) async {
      await nativeLitePrivateDestination(file, boundary ?? home);
      if (!await file.exists()) return null;
      if (await FileSystemEntity.type(file.path, followLinks: false) !=
          FileSystemEntityType.file)
        throw StateError('Invalid restore CAS file');
      return (await sha256.bind(file.openRead()).first).toString();
    }

    final fileSources = await fileProviderSourceBackupEntriesTask(
      stagingDirPath,
      data.profiles.map((profile) => profile.id),
    );
    await checkFileProviderSourceRestorePathsTask(homeDirPath, fileSources);
    Future<void> putBack() async {
      // Validate the entire rollback set before changing its first file.
      for (final entry in copied.entries) {
        if (await digest(File(BackupEntries.resolve(homeDirPath, entry.key))) !=
            entry.value.after)
          throw StateError('Restore rollback encountered a third file value');
        if (entry.value.before != null &&
            await digest(
                  File(BackupEntries.resolve(replacedDirPath, entry.key)),
                  boundary: Directory(stagingDirPath),
                ) !=
                entry.value.before)
          throw StateError('Restore before image changed');
      }
      for (final entry in copied.entries) {
        final target = File(BackupEntries.resolve(homeDirPath, entry.key));
        if (await digest(target) != entry.value.after)
          throw StateError('Restore rollback file CAS failed');
        if (entry.value.before == null)
          await target.safeDelete();
        else
          await File(
            BackupEntries.resolve(replacedDirPath, entry.key),
          ).safeCopy(target.path);
      }
    }

    try {
      for (final entry in {...BackupEntries.ofData(data), ...fileSources}) {
        final staged = File(BackupEntries.resolve(stagingDirPath, entry));
        final target = File(BackupEntries.resolve(homeDirPath, entry));
        final exists = await staged.exists();
        if (!exists && !entry.endsWith('.input')) continue;
        final before = await digest(target);
        final after = exists
            ? await digest(staged, boundary: Directory(stagingDirPath))
            : null;
        if (before != null)
          await target.safeCopy(BackupEntries.resolve(replacedDirPath, entry));
        copied[entry] = (before: before, after: after);
        if (exists)
          await staged.safeCopy(target.path);
        else
          await target.safeDelete();
      }
    } catch (primary, stack) {
      try {
        await putBack();
      } catch (recovery, recoveryStack) {
        _retainedRestoreWork.add(Directory(stagingDirPath).parent.path);
        Error.throwWithStackTrace(
          RuleSqlPublicationFailure(primary, recovery, stagingDirPath, null),
          recoveryStack,
        );
      }
      Error.throwWithStackTrace(primary, stack);
    }
    return putBack;
  }

  Future<void> _clearReplacedProviderCaches(
    List<ClashProvider> previousProviders,
  ) async {
    final kept = (await database.clashProvidersDao.fileNames().get()).toSet();
    for (final provider in previousProviders) {
      if (kept.contains(provider.fileName)) {
        continue;
      }
      await File(await provider.path).safeDelete();
      await File(await provider.compiledPath).safeDelete();
    }
  }

  /// A restored source outdates the mrs compiled from the one it replaced.
  Future<void> _clearOutdatedCompiledRuleSets(
    List<ClashProvider> restored,
  ) async {
    for (final provider in restored) {
      if (!provider.isRemote) {
        await File(await provider.compiledPath).safeDelete();
      }
    }
  }
}
