import 'dart:convert';

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/changelog.dart';

import 'common.dart';

const releaseChangelogJsonMarker = '<!-- flclash:changelog:json';

const _releaseChangelogJsonEndMarker = '-->';

String changelogGroupTitle(
  AppLocalizations appLocalizations,
  ChangelogType type,
) => switch (type) {
  ChangelogType.breaking => appLocalizations.changelogBreaking,
  ChangelogType.feat => appLocalizations.changelogFeatures,
  ChangelogType.fix => appLocalizations.changelogFixes,
  ChangelogType.perf => appLocalizations.changelogPerformance,
  ChangelogType.revert => appLocalizations.changelogReverts,
  ChangelogType.unknown => '',
};

ChangelogVersion? parseReleaseChangelog(String? body, {String? tagName}) {
  if (body == null) {
    return null;
  }
  final begin = body.indexOf(releaseChangelogJsonMarker);
  if (begin < 0) {
    return null;
  }
  final start = begin + releaseChangelogJsonMarker.length;
  final end = body.indexOf(_releaseChangelogJsonEndMarker, start);
  if (end < 0) {
    return null;
  }
  try {
    final changelog = Changelog.fromJson(
      jsonDecode(body.substring(start, end)) as Map<String, dynamic>,
    );
    if (!changelog.isSupported) {
      commonPrint.log(
        'changelog schema ${changelog.schemaVersion} is not supported',
        logLevel: LogLevel.warning,
      );
      return null;
    }
    if (tagName == null) return changelog.versions.firstOrNull;
    final versionName = tagName.replaceFirst(RegExp(r'^v'), '');
    return changelog.versions.where((version) {
      return version.tag.replaceFirst(RegExp(r'^v'), '') == versionName &&
          version.version == versionName;
    }).firstOrNull;
  } catch (error) {
    commonPrint.log(
      'changelog decode failed ${compactError(error)}',
      logLevel: LogLevel.warning,
    );
    return null;
  }
}

String releaseNotesText(String? body, {String? tagName}) {
  if (body == null || body.contains(releaseChangelogJsonMarker)) return '';
  final boundTag = RegExp(r'<!-- aerove:changelog:tag ([^\s]+) -->')
      .firstMatch(body)
      ?.group(1);
  if (boundTag != null && tagName != null && boundTag != tagName) return '';
  if (body.contains(releaseNotesBeginMarker)) {
    return scopeReleaseNotes(body)
        .replaceAll(RegExp(r'<!--[\s\S]*?-->'), '')
        .trim();
  }
  if (body.trimLeft().startsWith('# Aerove Android')) return '';
  return body.replaceAll(RegExp(r'<!--[\s\S]*?-->'), '').trim();
}

String? aeroveNotesForTag(String markdown, String tagName) {
  final version = tagName.replaceFirst(RegExp(r'^v'), '');
  final headings = RegExp(r'^##[ \t]+(.+)$', multiLine: true)
      .allMatches(markdown)
      .toList();
  String? result;
  var matched = false;
  for (final (index, heading) in headings.indexed) {
    final title = heading.group(1)!.trim();
    if (!RegExp('^v?${RegExp.escape(version)}(?:[ \\t]+—.*)?\$')
        .hasMatch(title)) {
      continue;
    }
    if (matched) return null;
    matched = true;
    final end = index + 1 < headings.length
        ? headings[index + 1].start
        : markdown.length;
    final notes = markdown.substring(heading.end, end).trim();
    result = notes.isEmpty ? null : notes;
  }
  return result;
}
