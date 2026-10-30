import 'package:fl_clash/product.dart';
import 'dart:io';

import 'package:package_info_plus/package_info_plus.dart';

extension PackageInfoExtension on PackageInfo {
  String get ua => [
    '${ClientProduct.current.displayName}/v$version',
    'clash-verge',
    'Platform/${Platform.operatingSystem}',
  ].join(' ');
}

int compareVersions(String version1, String version2) {
  final pattern = RegExp(
    r'^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?'
    r'(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+(\d+))?$',
  );
  final brandSuffix = RegExp(r'-X(?=\+|$)');
  final numericIdentifier = RegExp(r'^\d+$');

  ({List<int> core, List<String> prerelease, int build}) parse(String version) {
    final match = pattern.firstMatch(version.replaceFirst(brandSuffix, ''));
    if (match == null) {
      throw FormatException('Invalid version', version);
    }
    return (
      core: [
        for (var index = 1; index <= 3; index++) int.parse(match[index] ?? '0'),
      ],
      prerelease: match[4]?.split('.') ?? const [],
      build: int.parse(match[5] ?? '0'),
    );
  }

  final first = parse(version1);
  final second = parse(version2);
  for (var index = 0; index < 3; index++) {
    final result = first.core[index].compareTo(second.core[index]);
    if (result != 0) {
      return result;
    }
  }
  final pre1 = first.prerelease;
  final pre2 = second.prerelease;
  if (pre1.isEmpty != pre2.isEmpty) {
    return pre1.isEmpty ? 1 : -1;
  }
  for (var index = 0; index < pre1.length && index < pre2.length; index++) {
    final id1 = pre1[index];
    final id2 = pre2[index];
    final numeric1 = numericIdentifier.hasMatch(id1);
    final numeric2 = numericIdentifier.hasMatch(id2);
    if (numeric1 != numeric2) {
      return numeric1 ? -1 : 1;
    }
    final result = numeric1
        ? BigInt.parse(id1).compareTo(BigInt.parse(id2))
        : id1.compareTo(id2);
    if (result != 0) {
      return result;
    }
  }
  final prereleaseLength = pre1.length.compareTo(pre2.length);
  return prereleaseLength != 0
      ? prereleaseLength
      : first.build.compareTo(second.build);
}

const releaseNotesBeginMarker = '<!-- flclash:changelog:begin -->';
const releaseNotesEndMarker = '<!-- flclash:changelog:end -->';

List<String> parseReleaseBody(String? body) {
  if (body == null) return [];
  final regex = RegExp(r'^[ \t]*-[ \t]+(.*)$', multiLine: true);
  return regex
      .allMatches(scopeReleaseNotes(body))
      .map((match) => match.group(1)?.trim() ?? '')
      .where((item) => item.isNotEmpty)
      .toList();
}

String scopeReleaseNotes(String body) {
  final begin = body.indexOf(releaseNotesBeginMarker);
  if (begin < 0) return body;
  final start = begin + releaseNotesBeginMarker.length;
  final end = body.indexOf(releaseNotesEndMarker, start);
  return end < 0 ? body.substring(start) : body.substring(start, end);
}
