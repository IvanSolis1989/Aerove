import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:crypto/crypto.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:freezed_annotation/freezed_annotation.dart';
import 'package:flutter/foundation.dart' show listEquals;

import 'clash_config.dart';
import 'common.dart';

part 'generated/profile.freezed.dart';
part 'generated/profile.g.dart';

typedef ValidateConfig =
    Future<String> Function(String path, String ageSecretKey);

@freezed
abstract class SubscriptionInfo with _$SubscriptionInfo {
  const factory SubscriptionInfo({
    int? upload,
    int? download,
    int? total,
    int? expire,
    @Default(1) int observationVersion,
    DateTime? observedAt,
    String? sourceKey,
    SubscriptionInfo? lastReported,
    @Default({}) Map<String, Object?> unqualifiedCounts,
  }) = _SubscriptionInfo;

  factory SubscriptionInfo.fromJson(Map<String, Object?> json) =>
      _$SubscriptionInfoFromJson(_qualifiedJson(json));

  static Map<String, Object?> _qualifiedJson(Map<String, Object?> json) {
    const fields = ['upload', 'download', 'total', 'expire'];
    final result = <String, Object?>{
      ...json,
      'observationVersion':
          json['observationVersion'] is int && json['observationVersion'] == 1
          ? 1
          : 0,
      if (json['lastReported'] case final Map previous)
        'lastReported': {...previous, 'lastReported': null},
    };
    final rejected = <String, Object?>{};
    if (json['unqualifiedCounts'] case final Map values) {
      for (final key in fields) {
        final value = values[key];
        if (value is num || value is String || value is bool) {
          rejected[key] = value is double && !value.isFinite
              ? value.toString()
              : value;
        }
      }
    }
    for (final key in fields) {
      final value = json[key];
      final count = _jsonCount(value);
      result[key] = count;
      if (value != null &&
          count == null &&
          (value is num || value is String || value is bool)) {
        rejected[key] = value is double && !value.isFinite
            ? value.toString()
            : value;
      }
    }
    result['unqualifiedCounts'] = rejected;
    return result;
  }

  static int? _jsonCount(Object? value) {
    if (value is int) return value >= 0 ? value : null;
    // Beyond this bound a JSON double may already have lost source digits.
    if (value is double &&
        value.isFinite &&
        value >= 0 &&
        value <= 9007199254740991 &&
        value == value.truncateToDouble()) {
      return value.toInt();
    }
    return null;
  }

  factory SubscriptionInfo.formHString(String? info) {
    if (info == null) return const SubscriptionInfo();
    final Map<String, int?> map = {};
    for (final field in info.split(';')) {
      final separator = field.indexOf('=');
      if (separator == -1) continue;
      final value = _parseCount(field.substring(separator + 1).trim());
      map[field.substring(0, separator).trim().toLowerCase()] = value;
    }
    return SubscriptionInfo(
      upload: map['upload'],
      download: map['download'],
      total: map['total'],
      expire: map['expire'],
    );
  }

  static int? _parseCount(String value) {
    if (value.length > 128) return null;
    final match = RegExp(
      r'^\+?(\d+)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$',
    ).firstMatch(value);
    if (match == null) return null;
    final fraction = match.group(2) ?? '';
    final exponent = int.tryParse(match.group(3) ?? '0');
    if (exponent == null || exponent.abs() > 128) return null;
    var number = BigInt.parse('${match.group(1)}$fraction');
    final scale = exponent - fraction.length;
    if (scale >= 0) {
      number *= BigInt.from(10).pow(scale);
    } else {
      final divisor = BigInt.from(10).pow(-scale);
      if (number.remainder(divisor) != BigInt.zero) return null;
      number ~/= divisor;
    }
    return number <= BigInt.parse('9223372036854775807')
        ? number.toInt()
        : null;
  }

  static String keyForUrl(String url) =>
      sha256.convert(utf8.encode(url)).toString();

  static SubscriptionInfo observe(
    String? header, {
    required String url,
    required DateTime at,
    SubscriptionInfo? previous,
  }) {
    final key = keyForUrl(url);
    final sameSource = previous?.sourceKey == key;
    final retained = sameSource && previous!.hasKnownValues
        ? previous
        : sameSource
        ? previous?.lastReported
        : previous?.observationVersion == 0
        ? previous
        : null;
    return SubscriptionInfo.formHString(header).copyWith(
      sourceKey: key,
      observedAt: at,
      lastReported: retained?.copyWith(lastReported: null),
    );
  }
}

extension SubscriptionInfoExt on SubscriptionInfo {
  int? _known(int? value) =>
      value == null || value < 0 || observationVersion == 0 && value == 0
      ? null
      : value;

  int? get knownUpload => _known(upload);
  int? get knownDownload => _known(download);
  int? get knownTotal => _known(total);
  int? get knownExpire => _known(expire);
  int? get used =>
      knownUpload != null &&
          knownDownload != null &&
          knownUpload! <= 9223372036854775807 - knownDownload!
      ? knownUpload! + knownDownload!
      : null;
  bool get hasKnownValues => [
    knownUpload,
    knownDownload,
    knownTotal,
    knownExpire,
  ].any((v) => v != null);
  SubscriptionInfo? get sameSourceLastReported =>
      sourceKey != null && lastReported?.sourceKey == sourceKey
      ? lastReported
      : null;
  double? get share =>
      observationVersion == 1 &&
          used != null &&
          knownTotal != null &&
          knownTotal! > 0
      ? used! / knownTotal!
      : null;
  double? get usage => share?.clamp(0.0, 1.0).toDouble();

  DateTime? get expireDate {
    final seconds = knownExpire;
    if (observationVersion != 1 ||
        seconds == null ||
        seconds <= 0 ||
        seconds > 8640000000000)
      return null;
    return DateTime.fromMillisecondsSinceEpoch(seconds * 1000);
  }
}

@freezed
abstract class Profile with _$Profile {
  const factory Profile({
    required int id,
    @Default(ProfileType.file) ProfileType type,
    @Default('') String label,
    String? currentGroupName,
    @Default('') String url,
    @Default('') String ageSecretKey,
    DateTime? lastUpdateDate,
    required Duration autoUpdateDuration,
    SubscriptionInfo? subscriptionInfo,
    @Default(true) bool autoUpdate,
    @Default({}) Map<String, String> selectedMap,
    @Default({}) Set<String> unfoldSet,
    @Default(ExtendType.standard) ExtendType extendType,
    int? scriptId,
    String? matchTarget,
    int? order,
    @Default(ProfileOverrides())
    @JsonKey(fromJson: ProfileOverrides.safeFromJson)
    ProfileOverrides overrides,
  }) = _Profile;

  factory Profile.fromJson(Map<String, Object?> json) =>
      _$ProfileFromJson(json);

  factory Profile.normal({String? label, String url = ''}) {
    final id = snowflake.id;
    return Profile(
      type: url.isEmpty ? ProfileType.file : ProfileType.url,
      label: label ?? '',
      url: url,
      id: id,
      autoUpdateDuration: defaultUpdateDuration,
    );
  }

  factory Profile.custom({String? label}) {
    return Profile(
      id: snowflake.id,
      type: ProfileType.custom,
      label: label ?? '',
      lastUpdateDate: DateTime.now(),
      autoUpdate: false,
      autoUpdateDuration: defaultUpdateDuration,
    );
  }
}

/// A profile leaving out one of the global rules its extension would add.
@freezed
abstract class DisabledRule with _$DisabledRule {
  const factory DisabledRule({required int profileId, required int ruleId}) =
      _DisabledRule;
}

extension ProfilesExt on List<Profile> {
  Profile? getProfile(int? profileId) {
    final index = indexWhere((profile) => profile.id == profileId);
    return index == -1 ? null : this[index];
  }

  Profile optimizeLabel(Profile profile) {
    return profile.copyWith(
      label: uniqueLabelFor(
        profile.label,
        fallback: profile.defaultLabel,
        taken: (label) =>
            any((item) => item.label == label && item.id != profile.id),
      ),
    );
  }
}

extension ProfileExtension on Profile {
  SubscriptionInfo? get sourceSubscriptionInfo {
    final info = subscriptionInfo;
    if (info == null) return null;
    return info.sourceKey == null ||
            info.sourceKey == SubscriptionInfo.keyForUrl(url)
        ? info
        : null;
  }

  bool get realAutoUpdate => type == ProfileType.url && autoUpdate;

  String get realLabel => label.takeFirstValid([defaultLabel]);

  String get defaultLabel => url.webUri?.host ?? id.toString();

  String get fileName => '$id.yaml';

  String get exportFileName {
    final label = realLabel;
    final lower = label.toLowerCase();
    if (lower.endsWith('.yaml') || lower.endsWith('.yml')) {
      return label;
    }
    return '$label.yaml';
  }

  String get updatingKey => 'profile_$id';

  Future<Profile?> checkAndUpdateAndCopy({
    required ValidateConfig validate,
  }) async {
    if (type != ProfileType.url) {
      return null;
    }
    final mFile = await _getFile(false);
    if (await mFile.exists()) {
      return null;
    }
    return update(validate: validate);
  }

  Future<File> _getFile([
    bool autoCreate = true,
    bool Function()? isCurrent,
  ]) async {
    checkUpdateCurrent(isCurrent);
    final path = await appPath.getProfilePath(id.toString());
    checkUpdateCurrent(isCurrent);
    final file = File(path);
    final isExists = await file.exists();
    checkUpdateCurrent(isCurrent);
    if (!isExists && autoCreate) {
      return file.create(recursive: true);
    }
    return file;
  }

  Future<File> get file async {
    return _getFile();
  }

  Future<PreparedProfileUpdate> prepareUpdate({
    required ValidateConfig validate,
    bool Function()? isCurrent,
    CancelToken? cancelToken,
  }) async {
    checkUpdateCurrent(isCurrent);
    final response = await request.getFileResponseForUrl(
      url,
      cancelToken: cancelToken,
    );
    checkUpdateCurrent(isCurrent);
    final disposition = response.headers.value('content-disposition');
    final userinfo = response.headers.value('subscription-userinfo');
    final updated = copyWith(
      label: label.takeFirstValid([
        getFileNameForDisposition(disposition),
        defaultLabel,
      ]),
      subscriptionInfo: SubscriptionInfo.observe(
        userinfo,
        url: url,
        at: DateTime.now(),
        previous: subscriptionInfo,
      ),
    );
    final prepared = await updated.prepareFile(
      decompressUnlabeled(response.data ?? Uint8List(0)),
      validate: validate,
      originalInputBytes: response.data,
      isCurrent: isCurrent,
    );
    return PreparedProfileUpdate(
      updated.copyWith(lastUpdateDate: DateTime.now()),
      prepared,
    );
  }

  Future<Profile> update({
    required ValidateConfig validate,
    bool Function()? isCurrent,
  }) async {
    final prepared = await prepareUpdate(
      validate: validate,
      isCurrent: isCurrent,
    );
    try {
      prepared.file.publish(isCurrent: isCurrent);
      return prepared.profile;
    } finally {
      await prepared.file.dispose();
    }
  }

  Future<Profile> saveFile(
    Uint8List bytes, {
    required ValidateConfig validate,
    Uint8List? originalInputBytes,
    bool Function()? isCurrent,
  }) async {
    final prepared = await prepareFile(
      bytes,
      validate: validate,
      originalInputBytes: originalInputBytes,
      isCurrent: isCurrent,
    );
    try {
      prepared.publish(isCurrent: isCurrent);
      return copyWith(lastUpdateDate: DateTime.now());
    } finally {
      await prepared.dispose();
    }
  }

  Future<PreparedProfileFile> prepareFile(
    Uint8List bytes, {
    required ValidateConfig validate,
    Uint8List? originalInputBytes,
    bool Function()? isCurrent,
  }) async {
    checkUpdateCurrent(isCurrent);
    final tempFile = File(await appPath.tempFilePath);
    PreparedProfileFile? prepared;
    try {
      checkUpdateCurrent(isCurrent);
      await tempFile.safeWriteAsBytes(bytes);
      checkUpdateCurrent(isCurrent);
      final message = await validate(tempFile.path, ageSecretKey);
      checkUpdateCurrent(isCurrent);
      if (message.isNotEmpty) throw MessageException(message);
      final target = File(await appPath.getProfilePath(id.toString()));
      checkUpdateCurrent(isCurrent);
      await target.parent.create(recursive: true);
      final stage = File('${target.path}.${snowflake.id}.update');
      final backup = File('${stage.path}.previous');
      final original = File(
        BackupEntries.resolve(
          await appPath.homeDirPath,
          BackupEntries.profileInput(id),
        ),
      );
      final originalStage = File('${original.path}.${snowflake.id}.update');
      final originalBackup = File('${originalStage.path}.previous');
      prepared = PreparedProfileFile(
        target,
        stage,
        backup,
        original: original,
        originalStage: originalStage,
        originalBackup: originalBackup,
      );
      await tempFile.copy(stage.path);
      final originalBytes = originalInputBytes ?? bytes;
      if (!listEquals(originalBytes, await tempFile.readAsBytes())) {
        await originalStage.safeWriteAsBytes(originalBytes);
      }
      if (await original.exists()) {
        await original.copy(originalBackup.path);
        prepared.hadOriginal = true;
      }
      checkUpdateCurrent(isCurrent);
      if (await target.exists()) {
        await target.copy(backup.path);
        prepared.hadPrevious = true;
      }
      checkUpdateCurrent(isCurrent);
      return prepared;
    } catch (_) {
      await prepared?.dispose();
      rethrow;
    } finally {
      await tempFile.safeDelete();
    }
  }
}

class PreparedProfileUpdate {
  const PreparedProfileUpdate(this.profile, this.file);

  final Profile profile;
  final PreparedProfileFile file;
}

class PreparedProfileFile {
  PreparedProfileFile(
    this.target,
    this.stage,
    this.backup, {
    this.original,
    this.originalStage,
    this.originalBackup,
  });

  final File target;
  final File stage;
  final File backup;
  final File? original;
  final File? originalStage;
  final File? originalBackup;
  bool hadPrevious = false;
  bool hadOriginal = false;
  bool _published = false;

  void publish({bool Function()? isCurrent}) {
    checkUpdateCurrent(isCurrent);
    try {
      if (original case final file?) {
        if (originalStage?.existsSync() == true)
          originalStage!.renameSync(file.path);
        else if (file.existsSync())
          file.deleteSync();
      }
      stage.renameSync(target.path);
      _published = true;
    } catch (_) {
      _restoreOriginal();
      rethrow;
    }
  }

  void rollback() {
    if (!_published) return;
    if (hadPrevious) {
      backup.renameSync(target.path);
    } else {
      target.deleteSync();
    }
    _restoreOriginal();
    _published = false;
  }

  void _restoreOriginal() {
    if (original case final file?) {
      if (hadOriginal)
        originalBackup!.copySync(file.path);
      else if (file.existsSync())
        file.deleteSync();
    }
  }

  Future<void> dispose() async {
    await stage.safeDelete();
    await backup.safeDelete();
    await originalStage?.safeDelete();
    await originalBackup?.safeDelete();
  }
}
