# Pinned Flutter setup

The upstream Flutter action at the commit in `SOURCES.json` is kept locally because its composite action uses a moving
cache action tag, rejected by the repository full-SHA policy. The installer is unchanged; only the two nested cache
references are pinned to a verified commit. Preserve the MIT license and upstream source hashes when updating.
