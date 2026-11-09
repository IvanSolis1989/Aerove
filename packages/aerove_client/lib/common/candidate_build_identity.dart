import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';

class CandidateBuildIdentity {
  CandidateBuildIdentity._(this.fields);

  final Map<String, String> fields;
  String get label =>
      '#${fields['runNumber']}.${fields['attempt']} · '
      '${fields['source']!.substring(0, 8)} · ${fields['abi']}';
  String get details =>
      '$label\n${fields['source']}\n${fields['tree']}\n'
      'https://github.com/IvanSolis1989/Aerove/actions/runs/${fields['runId']}';

  static CandidateBuildIdentity? parse(String raw) {
    if (raw.length > 4096) return null;
    try {
      final value = jsonDecode(raw);
      const keys = {
        'schema',
        'product',
        'source',
        'tree',
        'runId',
        'runNumber',
        'attempt',
        'abi',
      };
      if (value is! Map<String, dynamic> ||
          value.keys.toSet().difference(keys).isNotEmpty ||
          keys.difference(value.keys.toSet()).isNotEmpty ||
          value['schema'] is! int ||
          value['schema'] != 1 ||
          value['product'] != 'aerove-lite') {
        return null;
      }
      final fields = <String, String>{};
      for (final key in keys.difference({'schema'})) {
        if (value[key] is! String) return null;
        fields[key] = value[key] as String;
      }
      final sha = RegExp(r'^[0-9a-f]{40}$');
      final number = RegExp(r'^[1-9][0-9]{0,19}$');
      if (!['source', 'tree'].every((key) => sha.hasMatch(fields[key]!)) ||
          ![
            'runId',
            'runNumber',
            'attempt',
          ].every((key) => number.hasMatch(fields[key]!)) ||
          !{'arm64-v8a', 'x86_64'}.contains(fields['abi'])) {
        return null;
      }
      return CandidateBuildIdentity._(Map.unmodifiable(fields));
    } on FormatException {
      return null;
    }
  }

  static Future<CandidateBuildIdentity?> load() async {
    try {
      final raw = await const MethodChannel('com.follow.clash/app')
          .invokeMethod<String>('getBuildIdentity')
          .timeout(const Duration(seconds: 2));
      return raw == null ? null : parse(raw);
    } on TimeoutException {
      return null;
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }
}
