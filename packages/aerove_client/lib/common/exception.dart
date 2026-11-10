final class MessageException implements Exception {
  final String message;

  const MessageException(this.message);

  @override
  String toString() => message;
}

final class AgeSecretKeyRequiredException implements Exception {
  const AgeSecretKeyRequiredException();
}

final class SourceSavedNotAppliedException implements Exception {
  const SourceSavedNotAppliedException([this.cause]);

  final Object? cause;

  @override
  String toString() => 'Source saved; runtime apply was not confirmed';
}

Future<void> requireSourceApplication(Future<bool> Function() apply) async {
  try {
    if (await apply()) return;
  } on SourceSavedNotAppliedException {
    rethrow;
  } catch (error, stack) {
    Error.throwWithStackTrace(SourceSavedNotAppliedException(error), stack);
  }
  throw const SourceSavedNotAppliedException();
}
