# Frozen provider lock-resolution inputs

These are unit-test fixtures, not accepted build outputs or production lockfiles.
They remove the test's dependency on reconstructing an old seed from the current
Cargo.lock and on the current build-policy mode.

- `inputs.zip` contains the approved 32-command/179-hit historical source tuple.
  Cargo.lock is copied byte-for-byte from `281a2f335962ec8d2985f7ee8cf81e10c06551db`;
  the other six files are from `9f2db578c9b5b61dfe9a5a0d1750602d9c0f4336`.
- `legacy31-inputs.zip` preserves the older 31-command/177-hit tuple solely for a
  rejection test against today's stronger producer. Its Cargo.lock comes from
  `156101c397356f4704d73ab59d03a703748b0ddf`; its other files come from
  `281a2f335962ec8d2985f7ee8cf81e10c06551db`.

Each provenance JSON records every original Git revision, path and raw SHA-256,
plus the archive hash. Reproduce a member with
`git show <revision>:<path>` using its provenance row. Archives use fixed ZIP
member timestamps (1980-01-01) and deflate level 9. Tests verify the complete
member list and all hashes before writing the fixture to a temporary directory.

The test changes only `resolveLock` and `expectedLockBeforeSha256` in the staged
policy to exercise the explicitly pending resolution contract. The production
admission tuples, source pins, and accepted lockfiles are unchanged. No fixture
runs Cargo or constitutes a release receipt.
