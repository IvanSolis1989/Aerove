part of '../state.dart';

@riverpod
ProfilesState profilesState(Ref ref) {
  final currentProfileId = ref.watch(currentProfileIdProvider);
  final profiles = ref.watch(profilesProvider);
  return ProfilesState(profiles: profiles, currentProfileId: currentProfileId);
}

@riverpod
Profile? currentProfile(Ref ref) {
  final profileId = ref.watch(currentProfileIdProvider);
  return ref.watch(
    profilesProvider.select((state) => state.getProfile(profileId)),
  );
}

@riverpod
Profile? profile(Ref ref, int? profileId) {
  return ref.watch(
    profilesProvider.select((state) => state.getProfile(profileId)),
  );
}

@riverpod
ExtendType extendType(Ref ref, int? profileId) {
  return ref.watch(
    profileProvider(
      profileId,
    ).select((state) => state?.extendType ?? ExtendType.standard),
  );
}

@riverpod
Future<ClashConfig> clashConfig(Ref ref, int profileId) async {
  final type = ref.read(profileProvider(profileId))?.type;
  if (type == ProfileType.custom) {
    return const ClashConfig();
  }
  final outbounds = await ref
      .read(coreHandlerProvider)
      .getProfileOutbounds(profileId);
  return clashConfigTask(outbounds);
}

@riverpod
Future<SetupState> setupState(Ref ref, int? profileId) async {
  final profile = ref.watch(profileProvider(profileId));
  final scriptId = profile?.scriptId;
  final profileLastUpdateDate = profile?.lastUpdateDate?.millisecondsSinceEpoch;
  final profileType = profile?.type ?? ProfileType.file;
  final extendType = profile?.extendType ?? ExtendType.standard;
  final native = profile?.overrides.nativeProfileUuid == null
      ? null
      : profile!.overrides;
  final overrides = profileType == ProfileType.custom
      ? ref.watch(
          patchClashConfigProvider.select(
            (state) => (profile?.overrides ?? const ProfileOverrides())
                .overAppTun(state.tunOverride, state.tunOverrideKeys),
          ),
        )
      : ref.watch(
          patchClashConfigProvider.select(
            (state) => ProfileOverrides(
              localProviderSources:
                  profile?.overrides.localProviderSources ?? const {},
              nativeProfileUuid: native?.nativeProfileUuid,
              nativeResourceInputs: native?.nativeResourceInputs ?? const {},
              proxyProviders: native?.proxyProviders ?? const {},
              hosts: native?.hosts ?? const {},
              dns: native == null
                  ? state.dns
                  : Dns.fromJson(
                      mergeDnsOverride(
                        Map<String, dynamic>.from(
                          jsonDecode(jsonEncode(state.dns)) as Map,
                        ),
                        native.dns.overrideJson(
                          native.dnsOverrideKeys,
                          keepEmpty: true,
                        ),
                      ),
                    ).copyWith(
                      generatedDefaultKeys: state.dns.generatedDefaultKeys
                          .difference(native.dnsOverrideKeys),
                    ),
              dnsOverrideKeys: {
                ...state.dnsOverrideKeys.intersection(
                  DnsOverrideKey.normalProfileKeys,
                ),
                ...?native?.dnsOverrideKeys,
              },
              tun: state.tunOverride,
              tunOverrideKeys: state.tunOverrideKeys,
            ),
          ),
        );
  final allProfileProviders = ref.watch(profileProvidersProvider);
  List<ProxyGroup> proxyGroups = [];
  List<Rule> rules = [];
  List<Rule> addedRules = [];
  List<ClashProvider> clashProviders = [];
  Map<String, int> profileProviders = const {};
  List<CustomProxy> appProxies = [];
  Map<int, String> proxyDialers = const {};
  Script? script;
  if (profileId != null) {
    if (profileType == ProfileType.custom) {
      final ownRules = await database.rulesDao
          .queryProfileRules(profileId)
          .get();
      rules = ownRules
          .where((rule) => !overrides.preRuleIds.contains(rule.id))
          .toList();
      if (extendType == ExtendType.standard && overrides.inheritGlobalRules) {
        addedRules = (await database.rulesDao.queryAddedRules(profileId).get())
            .where(
              (rule) =>
                  rule.profileId == null ||
                  overrides.preRuleIds.contains(rule.id),
            )
            .toList();
      }
      if (extendType == ExtendType.script &&
          overrides.applyExtensionToCustom &&
          scriptId != null) {
        script = await database.scriptsDao.get(scriptId).getSingleOrNull();
      }
      proxyGroups = await database.proxyGroupsDao.query(profileId).get();
      clashProviders = await database.clashProvidersDao.query().get();
      profileProviders = allProfileProviders;
      final scope = profile?.overrides.scopedCustomProxyIds;
      final allProxies = await database.customProxiesDao.query().get();
      appProxies = scope == null
          ? availableCustomProxiesForProfile(
              ref.read(profilesProvider),
              allProxies,
              null,
            )
          : [
              for (final id in scope)
                allProxies.firstWhere(
                  (proxy) => proxy.id == id,
                  orElse: () => throw StateError(
                    'profile.overrides.scopedCustomProxyIds: linked node is missing',
                  ),
                ),
            ];
      if (overrides.proxyAliases.isNotEmpty) {
        final names = {
          for (final proxy in appProxies)
            proxy.name: overrides.proxyAliases[proxy.id] ?? proxy.name,
        };
        appProxies = [
          for (final proxy in appProxies)
            proxy.copyWith(
              definition: {
                ...proxy.definition,
                'name': overrides.proxyAliases[proxy.id] ?? proxy.name,
                for (final key in ['dialer-proxy', 'connect-via'])
                  if (proxy.definition[key] case final String target)
                    key: names[target] ?? target,
              },
            ),
        ];
      }
      proxyDialers = {
        for (final dialer
            in await database.proxyDialersDao.query(profileId).get())
          dialer.proxyId: dialer.target,
      };
    } else if (extendType == ExtendType.standard) {
      addedRules = await database.rulesDao.queryAddedRules(profileId).get();
    } else if (extendType == ExtendType.script) {
      script = scriptId == null
          ? null
          : await database.scriptsDao.get(scriptId).getSingleOrNull();
    }
  }
  return SetupState(
    clashProviders: clashProviders,
    profileProviders: profileProviders,
    appProxies: appProxies,
    proxyDialers: proxyDialers,
    rules: rules,
    proxyGroups: proxyGroups,
    profileId: profileId,
    profileLastUpdateDate: profileLastUpdateDate,
    profileType: profileType,
    extendType: extendType,
    addedRules: addedRules,
    script: script,
    overrides: overrides,
    matchTarget:
        (profileType != ProfileType.custom ||
                overrides.applyExtensionToCustom) &&
            extendType == ExtendType.standard
        ? profile?.matchTarget
        : null,
  );
}

/// Subscription profiles double as proxy providers for custom profiles.
@riverpod
Map<String, int> profileProviders(Ref ref) {
  final profiles = ref.watch(profilesProvider);
  return {
    for (final profile in profiles)
      if (profile.type != ProfileType.custom) profile.realLabel: profile.id,
  };
}
