import 'package:fl_clash/models/models.dart';
import 'package:flutter/foundation.dart';

@immutable
class ExitSnapshot {
  const ExitSnapshot({
    required this.profileId,
    required this.proxied,
    required this.ip,
    this.quality,
  });

  final int profileId;
  final bool proxied;
  final IpInfo ip;
  final IpQuality? quality;

  IpQuality? qualityOf(String address) => ip.ip == address ? quality : null;

  ExitSnapshot withQuality(IpQuality? quality) => ExitSnapshot(
    profileId: profileId,
    proxied: proxied,
    ip: ip,
    quality: quality,
  );

  Map<String, Object?> toJson() => {
    'profileId': profileId,
    'proxied': proxied,
    'ip': ip.ip,
    'countryCode': ip.countryCode,
    'quality': quality?.toJson(),
  };

  static ExitSnapshot? fromJson(Object? json) => switch (json) {
    {
      'profileId': final int profileId,
      'proxied': final bool proxied,
      'ip': final String ip,
      'countryCode': final String countryCode,
    } =>
      ExitSnapshot(
        profileId: profileId,
        proxied: proxied,
        ip: IpInfo(ip: ip, countryCode: countryCode),
        quality: switch (json['quality']) {
          final Map<String, dynamic> quality when quality['ip'] == ip =>
            IpQuality.fromJson(quality),
          _ => null,
        },
      ),
    _ => null,
  };

  @override
  bool operator ==(Object other) =>
      other is ExitSnapshot &&
      other.profileId == profileId &&
      other.proxied == proxied &&
      other.ip == ip &&
      other.quality == quality;

  @override
  int get hashCode => Object.hash(profileId, proxied, ip, quality);
}
