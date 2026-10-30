class CoreCapabilities {
  const CoreCapabilities._({
    required this.coreId,
    required this.tunStacks,
    required this.defaultTunStack,
    required this.tunCongestionControllers,
    required this.defaultTunCongestionController,
    required this.androidHostTun,
  });

  static const mihomo = CoreCapabilities._(
    coreId: 'mihomo',
    tunStacks: ['gvisor', 'system', 'mixed', 'mips'],
    defaultTunStack: 'mips',
    tunCongestionControllers: ['cubic', 'reno', 'bbr', 'bbr3'],
    defaultTunCongestionController: 'cubic',
    androidHostTun: false,
  );
  static const meow = CoreCapabilities._(
    coreId: 'meow-rs',
    tunStacks: ['lwip'],
    defaultTunStack: 'lwip',
    tunCongestionControllers: ['builtin'],
    defaultTunCongestionController: 'builtin',
    androidHostTun: true,
  );

  static CoreCapabilities forCore(String coreId) => switch (coreId) {
    'mihomo' => mihomo,
    'meow-rs' => meow,
    _ => throw ArgumentError.value(coreId, 'coreId', 'Unknown core'),
  };

  final String coreId;
  final List<String> tunStacks;
  final String defaultTunStack;
  final List<String> tunCongestionControllers;
  final String defaultTunCongestionController;
  final bool androidHostTun;
}
