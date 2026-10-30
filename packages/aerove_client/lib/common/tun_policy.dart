import 'core_capabilities.dart';

void validateTunPolicy({
  required String coreId,
  required String stack,
  required String congestionController,
  required String device,
  Object? sourceTun,
}) {
  final capabilities = CoreCapabilities.forCore(coreId);
  void stackValue(Object? value) {
    final stack = value is String && coreId == CoreCapabilities.mihomo.coreId
        ? value.toLowerCase()
        : value;
    if (stack is! String || !capabilities.tunStacks.contains(stack)) {
      throw FormatException('\$.tun.stack: $value is not supported by $coreId');
    }
  }

  void congestionValue(Object? value) {
    if (value is! String ||
        !capabilities.tunCongestionControllers.contains(value)) {
      throw FormatException(
        '\$.tun.congestion-controller: $value is not supported by $coreId',
      );
    }
  }

  stackValue(stack);
  congestionValue(congestionController);
  if (capabilities.androidHostTun && device.isNotEmpty) {
    throw const FormatException(
      '\$.tun.device: Android owns the TUN device; an explicit device cannot be reconciled',
    );
  }
  if (sourceTun == null) return;
  if (sourceTun is! Map) {
    throw const FormatException('\$.tun: Expected an object');
  }
  if (sourceTun.containsKey('stack')) stackValue(sourceTun['stack']);
  if (sourceTun.containsKey('congestion-controller')) {
    congestionValue(sourceTun['congestion-controller']);
  }
}

Map<String, Object?>? meowSourceTun(
  Object? sourceTun,
  Map<String, Object?> overrides,
) {
  if (sourceTun == null && overrides.isEmpty) return null;
  if (sourceTun != null && sourceTun is! Map) {
    throw const FormatException('\$.tun: Expected an object');
  }
  return {
    if (sourceTun is Map) ...Map<String, Object?>.from(sourceTun),
    ...overrides,
  };
}
