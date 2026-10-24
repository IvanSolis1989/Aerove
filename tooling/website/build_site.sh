#!/usr/bin/env bash
set -euo pipefail

repo="${SITE_REPOSITORY:-IvanSolis1989/Aerove}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_root="${SITE_SOURCE_ROOT:-$root}"
out="${1:-$root/tooling/website/.output}"
if ! command -v node >/dev/null; then
  echo "error: tooling/website/render_site.mjs needs Node.js on PATH" >&2
  exit 1
fi
# Refuse existing outputs: a misspelled output path must never erase source or unrelated evidence.
if [[ -e "$out" || -L "$out" ]]; then
  echo "error: output already exists; choose a new empty destination: $out" >&2
  exit 1
fi
# Assemble in a unique sibling, so failed attempts never occupy the final output.
parent="$(dirname -- "$out")"
name="$(basename -- "$out")"
mkdir -p "$parent"
parent="$(cd "$parent" && pwd -P)"
out="$parent/$name"
work="$(mktemp -d "$parent/.$name.building.XXXXXX")"
report_failure() {
  status=$?
  if [[ "$status" -ne 0 && -d "$work" ]]; then
    printf 'error: site assembly failed; partial output preserved for diagnosis: %s\n' "$work" >&2
  fi
}
trap report_failure EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
cp -R "$root/website/." "$work/"
cp "$source_root/apps/aerove/assets/images/icon.png" "$work/icon.png"
cp "$source_root/apps/aerove-lite/app/src/main/res/drawable-nodpi/aerove_lite_icon.png" "$work/lite-icon.png"
cp "$source_root/apps/aerove/assets_source/images/icon/glyph.svg" "$work/favicon.svg"
# Optional fixed metadata makes the same assembly reproducible offline; never download application packages.
if [[ -n "${SITE_RELEASES_FILE:-}" ]]; then
  cp "$SITE_RELEASES_FILE" "$work/releases.json"
elif ! gh api --paginate --slurp "repos/$repo/releases?per_page=100" --jq 'add' >"$work/releases.json"; then
  echo 'warning: release metadata unavailable; only GitHub release navigation will be shown' >&2
  printf '[]\n' >"$work/releases.json"
fi
node "$root/tooling/website/render_site.mjs" "$work" "${SITE_URL:-https://ivansolis1989.github.io/Aerove}" "$repo"
# Refuse a destination created during assembly as well; never replace an existing artifact.
if [[ -e "$out" || -L "$out" ]]; then
  echo "error: output appeared during assembly; refusing to replace it: $out" >&2
  exit 1
fi
mv -T -n -- "$work" "$out"
# GNU mv --no-clobber may return success without moving if another writer won the race.
if [[ -d "$work" ]]; then
  echo "error: output could not be published without replacing an existing path: $out" >&2
  exit 1
fi
trap - EXIT INT TERM
echo "site assembled in $out"
