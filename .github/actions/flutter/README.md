# Pinned Flutter setup

The upstream Flutter action at the commit in `SOURCES.json` is kept locally because its composite action uses a moving
cache action tag, rejected by the repository full-SHA policy. The installer script is unchanged; nested cache references
are pinned to a verified commit. The optional `cache-read-only` input restores SDK and Pub caches without making parallel
consumers compete to save the same key. Preserve the MIT license and upstream source hashes when updating.
