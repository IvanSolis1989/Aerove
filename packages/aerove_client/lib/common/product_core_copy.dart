import 'package:fl_clash/l10n/l10n.dart';

typedef ProductCoreCopy = ({
  String description,
  String software,
  String license,
});

ProductCoreCopy productCoreCopy(String coreId, AppLocalizations l) =>
    switch (coreId) {
      'mihomo' => (
        description: l.desc,
        software: l.disclaimerSoftwareContent,
        license: l.disclaimerLicenseContent,
      ),
      'meow-rs' => (
        description: l.liteDesc,
        software: l.liteDisclaimerSoftwareContent,
        license: l.liteDisclaimerLicenseContent,
      ),
      _ => throw ArgumentError.value(coreId, 'coreId', 'Unknown product core'),
    };
