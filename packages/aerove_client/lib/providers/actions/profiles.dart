part of '../action.dart';

@Riverpod(keepAlive: true)
class ProfilesAction extends _$ProfilesAction {
  CoreController get _core => ref.read(coreHandlerProvider);

  void _showCompatibility(SubscriptionCompatibilityReport? report) {
    if (!ref.mounted || report == null || !report.changed) return;
    final l10n = currentAppLocalizations;
    final adjustments = report.tunStackProjected
        ? l10n.subscriptionCompatibilityTunStack
        : '';
    final message = report.removed == 0
        ? adjustments
        : l10n.subscriptionCompatibilitySummary(
            report.removedByType.entries
                .map((entry) => '${entry.key}: ${entry.value}')
                .join(', '),
            report.removed,
            adjustments,
          );
    dialogs.showNotifier(message, level: MessageLevel.info);
  }

  Future<Profile> _commitPreparedImport(
    Profile profile,
    PreparedProfileFile prepared,
    SubscriptionCompatibilityReport? report, {
    bool associateDefault = true,
  }) => ref
      .read(setupActionProvider.notifier)
      .withProviderSourceWrite(
        () => _commitPreparedImportSource(
          profile,
          prepared,
          report,
          associateDefault: associateDefault,
        ),
      );

  Future<Profile> _commitPreparedImportSource(
    Profile profile,
    PreparedProfileFile prepared,
    SubscriptionCompatibilityReport? report, {
    bool associateDefault = true,
  }) async {
    try {
      final selected = associateDefault
          ? await xclashDefaults?.associateNewProfile(profile) ?? profile
          : profile;
      await ref
          .read(profilesProvider.notifier)
          .putCommitted(
            selected,
            publishFile: () => prepared.publish(isCurrent: () => ref.mounted),
            rollbackFile: prepared.rollback,
          );
      if (ref.mounted && ref.read(currentProfileIdProvider) == null) {
        ref.read(currentProfileIdProvider.notifier).value = selected.id;
      }
      _showCompatibility(report);
      return selected;
    } finally {
      await prepared.dispose();
    }
  }

  Future<Profile> _saveImportedBytes(
    Profile profile,
    Uint8List bytes, {
    Uint8List? originalInputBytes,
    bool associateDefault = true,
  }) async {
    SubscriptionCompatibilityReport? report;
    final prepared = await profile.prepareFile(
      bytes,
      originalInputBytes: originalInputBytes,
      validate: (path, key) =>
          _core.validateProfile(path, key, (value) => report = value),
      isCurrent: () => ref.mounted,
    );
    return _commitPreparedImport(
      profile.copyWith(lastUpdateDate: DateTime.now()),
      prepared,
      report,
      associateDefault: associateDefault,
    );
  }

  bool _autoUpdating = false;
  final _automaticBackoff = AutomaticUpdateBackoff();

  void resetAutomaticBackoff() => _automaticBackoff.reset();

  final _profileUpdates = <int, ProfileUpdateIntent>{};

  @override
  void build() {
    ref.onDispose(() {
      for (final intent in _profileUpdates.values) {
        intent.cancel();
      }
    });
    ref.listen(maintenanceVisibleProvider, (_, visible) {
      if (visible) return;
      for (final intent in _profileUpdates.values) {
        if (intent.automatic) intent.cancel();
      }
    });
    ref.listen(profilesProvider, (_, profiles) {
      for (final intent in _profileUpdates.values) {
        if (!intent.isCurrent) intent.cancel();
      }
    });
  }

  Future<void> cancelProfileUpdate(int id) async {
    final intent = _profileUpdates[id];
    intent?.cancel();
    await intent?.done;
  }

  Future<void> cancelProfileUpdates(Iterable<int> ids) async {
    final intents = [for (final id in ids) ?_profileUpdates[id]];
    for (final intent in intents) {
      intent.cancel();
    }
    await Future.wait(intents.map((intent) => intent.done));
  }

  Future<bool> updateCurrentSelectedMap(
    String groupName,
    String proxyName, {
    required int? profileId,
    bool Function()? isCurrent,
  }) {
    if (profileId == null || !ref.mounted) return Future.value(false);
    final lite = ClientProduct.current.expectedCoreId == 'meow-rs';
    Future<bool> write() {
      if (!ref.mounted || (isCurrent != null && !isCurrent()))
        return Future.value(false);
      return ref
          .read(profilesProvider.notifier)
          .setSelectedProxy(
            profileId,
            groupName,
            proxyName,
            isCurrent: isCurrent,
            optimistic: !lite,
          );
    }

    return lite
        ? ref.read(setupActionProvider.notifier).withProviderSourceWrite(write)
        : write();
  }

  Future<void> deleteProfile(int id) async {
    await cancelProfileUpdate(id);
    await ref
        .read(setupActionProvider.notifier)
        .withProviderSourceWrite(() => _deleteProfileSource(id));
  }

  Future<void> _deleteProfileSource(int id) async {
    await ref.read(profilesProvider.notifier).del(id);
    await clearEffect(id);
    final currentProfileId = ref.read(currentProfileIdProvider);
    if (currentProfileId == id) {
      final profiles = ref.read(profilesProvider);
      if (profiles.isNotEmpty) {
        final updateId = profiles.first.id;
        ref.read(currentProfileIdProvider.notifier).value = updateId;
      } else {
        ref.read(currentProfileIdProvider.notifier).value = null;
        unawaited(ref.read(setupActionProvider.notifier).setRunning(false));
      }
    }
  }

  Future<String> validateConfigWithData(List<int> data) async {
    return _core.validateConfigWithData(data);
  }

  Future<Profile> saveProfileEdit(
    Profile profile, {
    Uint8List? fileData,
  }) async {
    return ref
        .read(setupActionProvider.notifier)
        .withProviderSourceWrite(
          () => _saveProfileEditSource(profile, fileData: fileData),
        );
  }

  Future<Profile> _saveProfileEditSource(
    Profile profile, {
    Uint8List? fileData,
  }) async {
    if (fileData != null)
      return _saveImportedBytes(profile, fileData, associateDefault: false);
    await ref.read(profilesProvider.notifier).putCommitted(profile);
    if (ref.mounted && ref.read(currentProfileIdProvider) == null) {
      ref.read(currentProfileIdProvider.notifier).value = profile.id;
    }
    return profile;
  }

  Future<void> autoUpdateProfiles({bool Function()? isCurrent}) async {
    bool canUpdate() =>
        ref.mounted &&
        ref.read(maintenanceVisibleProvider) &&
        (isCurrent?.call() ?? true);
    if (_autoUpdating || !canUpdate()) return;
    _autoUpdating = true;
    try {
      for (final id in [
        for (final profile in ref.read(profilesProvider)) profile.id,
      ]) {
        if (!canUpdate()) return;
        final profile = ref.read(profilesProvider).getProfile(id);
        if (profile == null || !profile.autoUpdate) continue;
        final isNotNeedUpdate = profile.lastUpdateDate
            ?.add(profile.autoUpdateDuration)
            .isBeforeNow;
        if (isNotNeedUpdate == false || profile.type != ProfileType.url) {
          continue;
        }
        final key = profile.id.toString();
        final source = '${profile.url}\n${profile.ageSecretKey}';
        if (!_automaticBackoff.ready(key, source)) continue;
        try {
          await updateProfile(profile, isCurrent: canUpdate);
          if (canUpdate()) _automaticBackoff.reset(key);
        } catch (e) {
          if (e is UpdateCancelledException || !canUpdate()) return;
          _automaticBackoff.failed(key, source, profile.autoUpdateDuration);
          commonPrint.log(compactError(e), logLevel: LogLevel.warning);
        }
      }
    } finally {
      _autoUpdating = false;
    }
  }

  void putProfile(Profile profile) {
    ref.read(profilesProvider.notifier).put(profile);
    if (ref.read(currentProfileIdProvider) != null) return;
    ref.read(currentProfileIdProvider.notifier).value = profile.id;
  }

  Future<List<Profile>> providerUsers(Profile profile) async {
    if (profile.type == ProfileType.custom) {
      return const [];
    }
    final users = await ref
        .read(clashProvidersActionProvider.notifier)
        .profilesReferencing(ProviderKind.proxy, profile.realLabel);
    return [
      for (final user in users)
        if (user.id != profile.id) user,
    ];
  }

  /// Where a profile's own group takes a name, the name stands for that group.
  Future<List<Profile>> proxyUsers(Set<String> names) async {
    final (ruleTargets, dialerTargets) = await (
      database.rulesDao.targetsNaming(names),
      database.proxyDialersDao.targetsNaming(names),
    ).wait;
    final users = <Profile>[];
    for (final profile in ref.read(profilesProvider)) {
      if (profile.type != ProfileType.custom) {
        continue;
      }
      final groups = await database.proxyGroupsDao.query(profile.id).get();
      final inUse = proxyNamesInUse(
        names.difference({for (final group in groups) group.name}),
        groups: groups,
        ruleTargets: ruleTargets[profile.id] ?? const {},
        dialerTargets: dialerTargets[profile.id] ?? const {},
        overrides: profile.overrides,
      );
      if (inUse.isNotEmpty) {
        users.add(profile);
      }
    }
    return users;
  }

  Future<Set<String>> groupsInUse(int profileId, Set<int> groupIds) async {
    final groups = await database.proxyGroupsDao.query(profileId).get();
    final names = {
      for (final group in groups)
        if (groupIds.contains(group.id)) group.name,
    };
    final (ruleTargets, dialerTargets) = await (
      database.rulesDao.targetsNaming(names),
      database.proxyDialersDao.targetsNaming(names),
    ).wait;
    return proxyNamesInUse(
      names,
      groups: groups.where((group) => !groupIds.contains(group.id)),
      ruleTargets: ruleTargets[profileId] ?? const {},
      dialerTargets: dialerTargets[profileId] ?? const {},
      overrides:
          ref.read(profilesProvider).getProfile(profileId)?.overrides ??
          const ProfileOverrides(),
    );
  }

  Future<void> updateProfiles() async {
    for (final id in [
      for (final profile in ref.read(profilesProvider)) profile.id,
    ]) {
      final profile = ref.read(profilesProvider).getProfile(id);
      if (profile == null || profile.type != ProfileType.url) continue;
      try {
        await updateProfile(profile, showLoading: true);
      } on UpdateCancelledException {
        return;
      }
    }
  }

  Future<void> updateProfile(
    Profile profile, {
    bool showLoading = false,
    bool Function()? isCurrent,
  }) => _updateProfileSource(
    profile,
    showLoading: showLoading,
    isCurrent: isCurrent,
  );

  Future<void> _updateProfileSource(
    Profile profile, {
    bool showLoading = false,
    bool Function()? isCurrent,
  }) async {
    checkUpdateCurrent(isCurrent);
    if (isCurrent == null) _automaticBackoff.reset(profile.id.toString());
    final previous = _profileUpdates[profile.id];
    previous?.cancel();
    final initial = ref.read(profilesProvider).getProfile(profile.id);
    var baselineReady = false;
    Profile? baseline = initial;
    var source = profile;
    late final ProfileUpdateIntent intent;
    bool sourceCurrent() {
      if (!ref.mounted ||
          !identical(_profileUpdates[profile.id], intent) ||
          !(isCurrent?.call() ?? true)) {
        return false;
      }
      final latest = ref.read(profilesProvider).getProfile(profile.id);
      if (!baselineReady)
        return true; // The predecessor is joined below before binding a baseline.
      final expected = intent.committedProfile ?? baseline;
      return expected == null
          ? latest == null
          : latest != null &&
                latest.type == expected.type &&
                latest.url == expected.url &&
                latest.ageSecretKey == expected.ageSecretKey &&
                latest.lastUpdateDate == expected.lastUpdateDate;
    }

    intent = ProfileUpdateIntent(
      isSourceCurrent: sourceCurrent,
      automatic: isCurrent != null,
    );
    _profileUpdates[profile.id] = intent;
    final operation = showLoading
        ? ref.read(updatingKeysProvider.notifier).start(profile.updatingKey)
        : null;
    PreparedProfileUpdate? prepared;
    SubscriptionCompatibilityReport? compatibility;
    try {
      await previous?.done;
      checkUpdateCurrent(() => intent.isCurrent);
      final latest = ref.read(profilesProvider).getProfile(profile.id);
      bool sameIdentity(Profile? a, Profile? b) => a == null
          ? b == null
          : b != null &&
                a.type == b.type &&
                a.url == b.url &&
                a.ageSecretKey == b.ageSecretKey;
      final predecessor = previous?.committedProfile;
      if (!sameIdentity(initial, latest) &&
          !(predecessor != null &&
              sameIdentity(predecessor, latest) &&
              predecessor.lastUpdateDate == latest?.lastUpdateDate))
        throw const UpdateCancelledException();
      baseline = latest;
      baselineReady = true;
      if (latest != null && initial != null) {
        T merge<T>(T requested, T before, T after) =>
            const DeepCollectionEquality().equals(before, after)
            ? requested
            : after;
        source = profile.copyWith(
          label: merge(profile.label, initial.label, latest.label),
          currentGroupName: merge(
            profile.currentGroupName,
            initial.currentGroupName,
            latest.currentGroupName,
          ),
          autoUpdateDuration: merge(
            profile.autoUpdateDuration,
            initial.autoUpdateDuration,
            latest.autoUpdateDuration,
          ),
          autoUpdate: merge(
            profile.autoUpdate,
            initial.autoUpdate,
            latest.autoUpdate,
          ),
          selectedMap: merge(
            profile.selectedMap,
            initial.selectedMap,
            latest.selectedMap,
          ),
          unfoldSet: merge(
            profile.unfoldSet,
            initial.unfoldSet,
            latest.unfoldSet,
          ),
          extendType: merge(
            profile.extendType,
            initial.extendType,
            latest.extendType,
          ),
          scriptId: merge(profile.scriptId, initial.scriptId, latest.scriptId),
          matchTarget: merge(
            profile.matchTarget,
            initial.matchTarget,
            latest.matchTarget,
          ),
          order: merge(profile.order, initial.order, latest.order),
          overrides: merge(
            profile.overrides,
            initial.overrides,
            latest.overrides,
          ),
          lastUpdateDate: latest.lastUpdateDate,
          subscriptionInfo: latest.subscriptionInfo,
        );
      }
      // Preparing metadata must not erase a newer source before the source CAS.
      final original = baseline;
      if (!sameIdentity(original, source))
        source = source.copyWith(subscriptionInfo: null);
      final update = await source.prepareUpdate(
        validate: (path, key) =>
            _core.validateProfile(path, key, (value) => compatibility = value),
        isCurrent: () => intent.isCurrent,
        cancelToken: intent.cancelToken,
      );
      prepared = update;
      checkUpdateCurrent(() => intent.isCurrent);
      final committed = await ref
          .read(setupActionProvider.notifier)
          .withProviderSourceWrite(
            () => ref
                .read(profilesProvider.notifier)
                .publishUpdate(
                  update.profile,
                  original: original,
                  isCurrent: () => intent.isCurrent,
                  publishFile: () {
                    intent.beginCommit();
                    update.file.publish();
                  },
                  rollbackFile: update.file.rollback,
                ),
          );
      intent.committedProfile = committed;
      _automaticBackoff.reset(profile.id.toString());
      if (!ref.mounted || !identical(_profileUpdates[profile.id], intent)) {
        return;
      }
      _showCompatibility(compatibility);
      if (profile.id == ref.read(currentProfileIdProvider)) {
        final setup = ref.read(setupActionProvider.notifier);
        if (isCurrent == null) {
          setup.applyProfileDebounce(silence: true);
        } else {
          await setup.applyProfile(silence: true);
        }
      } else {
        final latest = ref.read(profilesProvider).getProfile(profile.id);
        if (latest == null) return;
        await ref
            .read(clashProvidersActionProvider.notifier)
            .applyIfReferenced(
              ProviderKind.proxy,
              {latest.realLabel},
              force: true,
              isCurrent: () =>
                  ref.mounted && identical(_profileUpdates[profile.id], intent),
            );
      }
    } catch (_) {
      if (intent.cancelled) {
        throw const UpdateCancelledException();
      }
      rethrow;
    } finally {
      try {
        await prepared?.file.dispose();
      } finally {
        if (identical(_profileUpdates[profile.id], intent)) {
          _profileUpdates.remove(profile.id);
        }
        if (operation != null && ref.mounted) {
          ref
              .read(updatingKeysProvider.notifier)
              .stop(profile.updatingKey, operation);
        }
        intent.finish();
      }
    }
  }

  Future<void> addProfileFormFile() async {
    final platformFile = await globalState.safeRun(picker.pickerFile);
    if (platformFile == null) return;
    final bytes = await platformFile.readBytes();
    globalState.navigatorKey.currentState?.popUntil((route) => route.isFirst);
    ref.read(currentPageLabelProvider.notifier).toProfiles();
    await globalState.loadingRun(
      tag: LoadingTag.profiles,
      () => _withAgeSecretKey(
        Profile.normal(label: platformFile.name),
        (profile) => _saveImportedBytes(profile, bytes),
      ),
      title: currentAppLocalizations.addProfile,
    );
  }

  int addCustomProfile(String label) {
    final profile = Profile.custom(label: label);
    putProfile(profile);
    return profile.id;
  }

  void touchProfile(int id) {
    final profile = ref.read(profilesProvider).getProfile(id);
    if (profile == null) return;
    ref
        .read(profilesProvider.notifier)
        .put(profile.copyWith(lastUpdateDate: DateTime.now()));
  }

  Future<CustomProfileImport> readCustomImport(
    int profileId,
    Uint8List bytes,
  ) async {
    final profiles = ref.read(profilesProvider);
    final (ruleProviders, proxies, groupNames) = await (
      database.clashProvidersDao.query().get(),
      database.customProxiesDao.query().get(),
      database.proxyGroupsDao.names(except: profileId).get(),
    ).wait;
    final CustomImportApp app = (
      profileLabels: {
        for (final profile in profiles) ...[profile.label, profile.realLabel],
      },
      profileSources: {
        for (final profile in profiles)
          if (profile.type == ProfileType.url)
            (url: profile.url, ageSecretKey: profile.ageSecretKey):
                profile.realLabel,
      },
      ruleProviderLabels: {
        for (final provider in ruleProviders) provider.label,
      },
      ruleProviderSources: {
        for (final provider in ruleProviders)
          if (provider.isRemote)
            (url: provider.url, ageSecretKey: ''): provider.label,
      },
      proxies: [for (final proxy in proxies) proxy.definition],
      groupNames: groupNames.toSet(),
    );
    try {
      return await compute(readCustomProfileImport, (bytes: bytes, app: app));
    } on CustomImportException catch (error) {
      final appLocalizations = currentAppLocalizations;
      throw MessageException(switch (error.issue) {
        CustomImportIssue.unreadable => appLocalizations.importConfigInvalid,
        CustomImportIssue.ruleType => appLocalizations.failedItem(
          error.item,
          appLocalizations.ruleTextInvalid,
        ),
        CustomImportIssue.entry => appLocalizations.failedItem(
          error.item,
          error.detail,
        ),
      });
    }
  }

  /// [config] replaces what [profileId] holds. The providers it adds are saved
  /// before the groups and rules naming them, and loaded afterwards; the
  /// future completes once they have loaded.
  Future<void> importCustomProfile(
    int profileId,
    CustomProfileImport config,
  ) async {
    final profiles = ref.read(profilesProvider.notifier);
    final clashProviders = ref.read(clashProvidersProvider.notifier);
    final loads = <({String label, Future<void> Function() load})>[];
    for (final (:label, :url, :ageSecretKey, :content)
        in config.proxyProviders) {
      final profile = Profile.normal(
        label: label,
        url: url,
      ).copyWith(ageSecretKey: ageSecretKey);
      profiles.put(profile);
      loads.add((
        label: label,
        load: url.isNotEmpty
            ? () => updateProfile(profile, showLoading: true)
            : () async {
                await _saveImportedBytes(
                  profile,
                  utf8.encode(content),
                  associateDefault: false,
                );
              },
      ));
    }
    for (final (:label, :url, ageSecretKey: _, :content)
        in config.ruleProviders) {
      final provider = ClashProvider.create(label: label, url: url);
      await ref
          .read(setupActionProvider.notifier)
          .withProviderSourceWrite(() => clashProviders.put(provider));
      loads.add((
        label: label,
        load: url.isNotEmpty
            ? () => ref
                  .read(resourcesActionProvider.notifier)
                  .updateRuleProvider(provider)
            : () => ref
                  .read(clashProvidersActionProvider.notifier)
                  .putProvider(provider, content: utf8.encode(content)),
      ));
    }
    final customProxies = ref.read(customProxiesProvider.notifier);
    customProxies.putAll([
      for (final definition in config.proxies)
        CustomProxy.fromDefinition(definition),
    ]);
    final proxyIds = {
      for (final proxy in customProxies.value) proxy.name: proxy.id,
    };
    ref.read(proxyGroupsProvider(profileId).notifier).setAll([
      for (final group in config.proxyGroups) group.copyWith(id: snowflake.id),
    ]);
    ref.read(profileRulesProvider(profileId).notifier).setAll([
      for (final rule in config.rules) rule.copyWith(id: snowflake.id),
    ]);
    ref.read(proxyDialersProvider(profileId).notifier).setAll({
      for (final MapEntry(:key, :value) in config.dialers.entries)
        ?proxyIds[key]: value,
    });
    profiles.updateProfile(
      profileId,
      (profile) => profile.copyWith(overrides: config.overrides),
    );
    return globalState.batchRun(
      loads,
      (item) => item.load(),
      label: (item) => item.label,
      concurrency: maxConcurrentImports,
      tag: null,
    );
  }

  void _showProfiles() {
    if (globalState.navigatorKey.currentState?.canPop() ?? false) {
      globalState.navigatorKey.currentState?.popUntil((route) => route.isFirst);
    }
    ref.read(currentPageLabelProvider.notifier).value = PageLabel.profiles;
  }

  Future<void> addProfileFromLink(
    String link, {
    String? label,
    String ageSecretKey = '',
  }) async {
    _showProfiles();
    await globalState.loadingRun(
      tag: LoadingTag.profiles,
      () => link.isShareLink
          ? _fromShareLinks(Profile.normal(label: label), [link])
          : _withAgeSecretKey(
              Profile.normal(
                label: label,
                url: link,
              ).copyWith(ageSecretKey: ageSecretKey),
              _download,
            ),
      title: currentAppLocalizations.addProfile,
    );
  }

  /// Ids are taken up front so the list keeps the order of [links]; the share
  /// links among them make up one profile, in the place of the first.
  Future<void> addProfilesFromLinks(List<String> links) async {
    _showProfiles();
    final shareLinks = [
      for (final link in links)
        if (link.isShareLink) link,
    ];
    await globalState.batchRun(
      [
        for (final link in links)
          if (!link.isShareLink)
            (profile: Profile.normal(url: link), shareLinks: const <String>[])
          else if (link == shareLinks.first)
            (profile: Profile.normal(), shareLinks: shareLinks),
      ],
      (item) async {
        await (item.shareLinks.isEmpty
            ? _download(item.profile)
            : _fromShareLinks(item.profile, item.shareLinks));
      },
      label: (item) => item.shareLinks.firstOrNull ?? item.profile.url,
      concurrency: maxConcurrentImports,
      tag: LoadingTag.profiles,
    );
  }

  Future<Profile> _download(Profile profile) async {
    SubscriptionCompatibilityReport? report;
    final pending = await profile.prepareUpdate(
      validate: (path, key) =>
          _core.validateProfile(path, key, (value) => report = value),
      isCurrent: () => ref.mounted,
    );
    return _commitPreparedImport(pending.profile, pending.file, report);
  }

  Future<Profile> _withAgeSecretKey(
    Profile profile,
    Future<Profile> Function(Profile profile) save,
  ) async {
    try {
      return await save(profile);
    } on AgeSecretKeyRequiredException {
      final key = (await dialogs.showAgeSecretKeyInput())?.trim();
      if (key == null || key.isEmpty) {
        rethrow;
      }
      return save(profile.copyWith(ageSecretKey: key));
    }
  }

  Future<Profile> _fromShareLinks(Profile profile, List<String> links) async {
    final appLocalizations = currentAppLocalizations;
    final decoded = await _core.decodeShareLinks(links);
    final taken = {...reservedCustomProxyNames};
    final proxies = <Map<String, dynamic>>[];
    for (final (index, definitions) in decoded.indexed) {
      if (definitions.isEmpty) {
        throw MessageException(
          links.length == 1
              ? appLocalizations.shareLinksInvalid
              : appLocalizations.shareLinkUnreadable(links[index]),
        );
      }
      for (final definition in definitions) {
        final name = freeProxyName(linkProxyName(definition), taken);
        proxies.add({...definition, 'name': name});
      }
    }
    final first = proxies.first['name'] as String;
    final label = proxies.length == 1
        ? first
        : appLocalizations.proxiesProfileLabel(first, proxies.length);
    final config = await encodeYamlTask({'proxies': proxies});
    return _saveImportedBytes(
      profile.copyWith(label: profile.label.takeFirstValid([label])),
      utf8.encode(config),
      originalInputBytes: utf8.encode(links.join('\n')),
    );
  }

  Future<void> addProfileFormQrCode() async {
    final url = await globalState.safeRun(picker.pickerConfigQRCode);
    if (url == null) return;
    unawaited(addProfileFromLink(url));
  }

  Future<void> clearEffect(int profileId) async {
    await File(
      BackupEntries.resolve(
        await appPath.homeDirPath,
        BackupEntries.profileInput(profileId),
      ),
    ).safeDelete();
    final profilePath = await appPath.getProfilePath(profileId.toString());
    final profileFile = File(profilePath);
    final isExists = await profileFile.exists();
    if (isExists) {
      await profileFile.safeDelete(recursive: true);
    }
    try {
      final error = await _core.clearEffect(profileId);
      if (error.isNotEmpty) {
        commonPrint.log(error, logLevel: LogLevel.warning);
      }
    } catch (error) {
      commonPrint.log(
        'clearEffect($profileId) failed: $error',
        logLevel: coreFailureLogLevel(error),
      );
    }
  }
}
