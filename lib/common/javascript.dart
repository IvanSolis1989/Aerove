import 'dart:convert';

import 'package:fl_clash/common/exception.dart';
import 'package:flutter/foundation.dart';
import 'package:rust_api/rust_api.dart';

typedef ScriptEvaluator =
    Future<String> Function({required String script, required String config});

@visibleForTesting
ScriptEvaluator scriptEvaluator = evaluateScript;

/// Both sides stay JSON text: a profile runs to megabytes, and decoding it is
/// left to the isolate that builds the profile.
Future<String> handleEvaluate(String scriptContent, String config) async {
  try {
    return await scriptEvaluator(script: scriptContent, config: config);
  } catch (e) {
    throw MessageException(e.toString());
  }
}

Map<String, dynamic> decodeScriptResult(String result) {
  final decoded = json.decode(result);
  if (decoded is! Map<String, dynamic>) {
    throw const MessageException(
      'script did not return a configuration object',
    );
  }
  return decoded;
}
