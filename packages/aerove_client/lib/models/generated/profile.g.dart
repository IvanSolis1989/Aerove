// GENERATED CODE - DO NOT MODIFY BY HAND

part of '../profile.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_SubscriptionInfo _$SubscriptionInfoFromJson(Map<String, dynamic> json) =>
    _SubscriptionInfo(
      upload: (json['upload'] as num?)?.toInt(),
      download: (json['download'] as num?)?.toInt(),
      total: (json['total'] as num?)?.toInt(),
      expire: (json['expire'] as num?)?.toInt(),
      observationVersion: (json['observationVersion'] as num?)?.toInt() ?? 1,
      observedAt: json['observedAt'] == null
          ? null
          : DateTime.parse(json['observedAt'] as String),
      sourceKey: json['sourceKey'] as String?,
      lastReported: json['lastReported'] == null
          ? null
          : SubscriptionInfo.fromJson(
              json['lastReported'] as Map<String, dynamic>,
            ),
      unqualifiedCounts:
          json['unqualifiedCounts'] as Map<String, dynamic>? ?? const {},
    );

Map<String, dynamic> _$SubscriptionInfoToJson(_SubscriptionInfo instance) =>
    <String, dynamic>{
      'upload': instance.upload,
      'download': instance.download,
      'total': instance.total,
      'expire': instance.expire,
      'observationVersion': instance.observationVersion,
      'observedAt': instance.observedAt?.toIso8601String(),
      'sourceKey': instance.sourceKey,
      'lastReported': instance.lastReported,
      'unqualifiedCounts': instance.unqualifiedCounts,
    };

_Profile _$ProfileFromJson(Map<String, dynamic> json) => _Profile(
  id: (json['id'] as num).toInt(),
  type:
      $enumDecodeNullable(_$ProfileTypeEnumMap, json['type']) ??
      ProfileType.file,
  label: json['label'] as String? ?? '',
  currentGroupName: json['currentGroupName'] as String?,
  url: json['url'] as String? ?? '',
  ageSecretKey: json['ageSecretKey'] as String? ?? '',
  lastUpdateDate: json['lastUpdateDate'] == null
      ? null
      : DateTime.parse(json['lastUpdateDate'] as String),
  autoUpdateDuration: Duration(
    microseconds: (json['autoUpdateDuration'] as num).toInt(),
  ),
  subscriptionInfo: json['subscriptionInfo'] == null
      ? null
      : SubscriptionInfo.fromJson(
          json['subscriptionInfo'] as Map<String, dynamic>,
        ),
  autoUpdate: json['autoUpdate'] as bool? ?? true,
  selectedMap:
      (json['selectedMap'] as Map<String, dynamic>?)?.map(
        (k, e) => MapEntry(k, e as String),
      ) ??
      const {},
  unfoldSet:
      (json['unfoldSet'] as List<dynamic>?)?.map((e) => e as String).toSet() ??
      const {},
  extendType:
      $enumDecodeNullable(_$ExtendTypeEnumMap, json['extendType']) ??
      ExtendType.standard,
  scriptId: (json['scriptId'] as num?)?.toInt(),
  matchTarget: json['matchTarget'] as String?,
  order: (json['order'] as num?)?.toInt(),
  overrides: json['overrides'] == null
      ? const ProfileOverrides()
      : ProfileOverrides.safeFromJson(
          json['overrides'] as Map<String, Object?>,
        ),
);

Map<String, dynamic> _$ProfileToJson(_Profile instance) => <String, dynamic>{
  'id': instance.id,
  'type': _$ProfileTypeEnumMap[instance.type]!,
  'label': instance.label,
  'currentGroupName': instance.currentGroupName,
  'url': instance.url,
  'ageSecretKey': instance.ageSecretKey,
  'lastUpdateDate': instance.lastUpdateDate?.toIso8601String(),
  'autoUpdateDuration': instance.autoUpdateDuration.inMicroseconds,
  'subscriptionInfo': instance.subscriptionInfo,
  'autoUpdate': instance.autoUpdate,
  'selectedMap': instance.selectedMap,
  'unfoldSet': instance.unfoldSet.toList(),
  'extendType': _$ExtendTypeEnumMap[instance.extendType]!,
  'scriptId': instance.scriptId,
  'matchTarget': instance.matchTarget,
  'order': instance.order,
  'overrides': instance.overrides,
};

const _$ProfileTypeEnumMap = {
  ProfileType.file: 'file',
  ProfileType.url: 'url',
  ProfileType.custom: 'custom',
};

const _$ExtendTypeEnumMap = {
  ExtendType.standard: 'standard',
  ExtendType.script: 'script',
};
