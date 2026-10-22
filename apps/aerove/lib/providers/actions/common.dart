part of '../action.dart';

@Riverpod(keepAlive: true)
class CommonAction extends _$CommonAction {
  CoreController get _core => ref.read(coreHandlerProvider);
  bool _isUpdatingTraffic = false;
  bool _isHandlingUpdate = false;
  int _trafficRevision = 0;

  @override
  void build() {
    ref.listen(appVisibleProvider, (_, _) => _trafficRevision++);
  }

  bool get _trafficVisible =>
      ref.read(appVisibleProvider) ||
      (system.isMacOS &&
          !ref.read(safeModeProvider) &&
          ref.read(appSettingProvider).showTrayTitle);

  void toggleRunning() {
    final running = !ref.read(isStartProvider);
    unawaited(
      globalState.safeRun(
        () => ref
            .read(setupActionProvider.notifier)
            .setRunning(
              running,
              initialize: running && !ref.read(initProvider),
            ),
      ),
    );
  }

  void updateSpeedStatistics() {
    ref
        .read(appSettingProvider.notifier)
        .update((state) => state.copyWith(showTrayTitle: !state.showTrayTitle));
  }

  void updateMonochromeTrayIcon() {
    ref
        .read(appSettingProvider.notifier)
        .update(
          (state) =>
              state.copyWith(monochromeTrayIcon: !state.monochromeTrayIcon),
        );
  }

  void updateMode() {
    ref.read(patchClashConfigProvider.notifier).update((state) {
      final index = Mode.values.indexWhere((item) => item == state.mode);
      if (index == -1) return state;
      final nextIndex = index + 1 > Mode.values.length - 1 ? 0 : index + 1;
      return state.copyWith(mode: Mode.values[nextIndex]);
    });
  }

  Future<void> updateTraffic() async {
    if (_isUpdatingTraffic || !_trafficVisible) {
      return;
    }
    _isUpdatingTraffic = true;
    final revision = _trafficRevision;
    try {
      final onlyStatisticsProxy = ref.read(
        appSettingProvider.select((state) => state.onlyStatisticsProxy),
      );
      final [traffic, totalTraffic] = await Future.wait([
        _readTraffic(() => _core.getTraffic(onlyStatisticsProxy)),
        _readTraffic(() => _core.getTotalTraffic(onlyStatisticsProxy)),
      ]);
      if (!ref.mounted || !_trafficVisible || revision != _trafficRevision) {
        return;
      }
      if (traffic != null) {
        ref.read(trafficsProvider.notifier).addTraffic(traffic);
      }
      if (totalTraffic != null) {
        ref.read(totalTrafficProvider.notifier).value = totalTraffic;
      }
    } finally {
      _isUpdatingTraffic = false;
    }
  }

  Future<Traffic?> _readTraffic(Future<Traffic> Function() request) async {
    try {
      return await request();
    } catch (error) {
      commonPrint.log(
        'updateTraffic error: $error',
        logLevel: coreFailureLogLevel(error),
      );
      return null;
    }
  }

  Future<bool> autoCheckUpdate() async {
    if (!ref.read(appSettingProvider).autoCheckUpdate) return false;
    final Map<String, dynamic>? res;
    try {
      res = await request.checkForUpdate();
    } catch (error) {
      commonPrint.log(
        'checkForUpdate failed: ${compactError(error)}',
        logLevel: LogLevel.warning,
      );
      return false;
    }
    if (res == null) return false;
    final tag = res['tag_name'] as String;
    if (await preferences.getPromptedUpdateTag() == tag) return true;
    await preferences.savePromptedUpdateTag(tag);
    await checkUpdateResultHandle(data: res);
    return true;
  }

  TextSpan _releaseSpan(BuildContext context, String tagName, String? body) {
    final version = parseReleaseChangelog(body, tagName: tagName);
    final notes = releaseNotesText(body, tagName: tagName);
    return TextSpan(
      text: '$tagName \n',
      style: AppTextStyle.title2.of(context),
      children: version == null || version.isEmpty
          ? [
              TextSpan(text: '\n', style: AppTextStyle.subheadline.of(context)),
              if (notes.isEmpty)
                TextSpan(
                  text: currentAppLocalizations.updateNotesUnavailable,
                  style: AppTextStyle.subheadline.of(context),
                )
              else
                for (final line in notes.split('\n'))
                  TextSpan(
                    text: '${line.replaceFirst(RegExp(r'^#{1,6}\s+'), '')}\n',
                    style: AppTextStyle.subheadline.of(
                      context,
                      emphasized: line.startsWith('#'),
                    ),
                  ),
            ]
          : _changelogSpans(context, version),
    );
  }

  List<TextSpan> _changelogSpans(
    BuildContext context,
    ChangelogVersion version,
  ) {
    return [
      for (final group in version.visibleGroups) ...[
        TextSpan(
          text:
              '\n${changelogGroupTitle(currentAppLocalizations, group.type)}\n',
          style: AppTextStyle.subheadline
              .of(context, emphasized: true)
              .copyWith(
                color: group.type == ChangelogType.breaking
                    ? context.colorScheme.error
                    : context.colorScheme.onSurface,
              ),
        ),
        for (final entry in group.entries)
          TextSpan(
            text: '• ${entry.text}\n',
            style: AppTextStyle.subheadline.of(context),
          ),
      ],
    ];
  }

  Future<void> checkUpdateResultHandle({
    Map<String, dynamic>? data,
    bool isUser = false,
  }) async {
    if (_isHandlingUpdate) return;
    _isHandlingUpdate = true;
    try {
      if (data != null) {
        final context = globalState.navigatorKey.currentContext;
        if (context == null || !context.mounted) return;
        final res = await dialogs.showMessage(
          title: currentAppLocalizations.discoverNewVersion,
          message: _releaseSpan(
            context,
            data['tag_name'] as String,
            data['body'] as String?,
          ),
          confirmText: currentAppLocalizations.goDownload,
          cancelText: isUser ? null : currentAppLocalizations.noLongerRemind,
        );
        if (!ref.mounted) return;
        if (res == true) {
          await globalState.safeRun(() async {
            try {
              final androidApp = app;
              if (androidApp != null) {
                final asset = selectAndroidUpdateAsset(
                  data,
                  await androidApp.getSupportedAbis(),
                );
                if (asset == null) {
                  throw MessageException(
                    currentAppLocalizations.updateAssetUnavailable,
                  );
                }
                await androidApp.downloadUpdate(asset.url, asset.name);
                dialogs.showNotifier(
                  currentAppLocalizations.updateDownloadStarted,
                );
              } else {
                final opened = await launchUrl(
                  Uri.parse('https://github.com/$repository/releases/latest'),
                  mode: LaunchMode.externalApplication,
                );
                if (!opened) {
                  throw MessageException(
                    currentAppLocalizations.updateDownloadFailed,
                  );
                }
              }
            } on MessageException {
              rethrow;
            } catch (error) {
              commonPrint.log(
                'Update download failed: ${compactError(error)}',
                logLevel: LogLevel.warning,
              );
              throw MessageException(
                currentAppLocalizations.updateDownloadFailed,
              );
            }
          });
        } else if (!isUser && res == false) {
          ref
              .read(appSettingProvider.notifier)
              .update((state) => state.copyWith(autoCheckUpdate: false));
        }
      } else if (isUser) {
        unawaited(
          dialogs.showMessage(
            title: currentAppLocalizations.checkUpdate,
            message: TextSpan(text: currentAppLocalizations.checkUpdateError),
          ),
        );
      }
    } finally {
      _isHandlingUpdate = false;
    }
  }
}
