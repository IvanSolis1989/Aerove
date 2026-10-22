#!/usr/bin/env bash
set -euo pipefail

repo="${SITE_REPOSITORY:-IvanSolis1989/Aerove}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
app="$root/apps/aerove"
out="${1:-$root/tooling/website/.output}"

if ! command -v node >/dev/null; then
  echo "error: tooling/website/render_site.mjs needs Node.js on PATH" >&2
  exit 1
fi

rm -rf "$out"
mkdir -p "$out"
cp -R "$root/website/." "$out/"
cp "$app/snapshots/preview.png" "$app/snapshots/preview-dark.png" "$out/"
cp "$app/assets/images/icon.png" "$out/icon.png"
cp "$app/assets_source/images/icon/glyph.svg" "$out/favicon.svg"

if stars="$(gh api "repos/$repo" --jq '.stargazers_count' 2>/dev/null)" &&
  release="$(gh api "repos/$repo/releases/latest" --jq ".tag_name, ({
  tag: .tag_name,
  publishedAt: .published_at,
  assets: [.assets[] | {name, size, digest}],
  stars: $stars
} | tojson)" 2>/dev/null)"; then
  { read -r tag && read -r json; } <<<"$release"
  printf '%s\n' "$json" >"$out/release.json"
else
  echo "warning: no release metadata from $repo; the page falls back to CHANGELOG.md" >&2
  tag=""
fi

# The changelog comes from the released tag so the notes never run ahead of the
# downloads; the working tree copy is only a fallback for local previews.
if [[ -n "$tag" ]] && ! git -C "$root" rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
  git -C "$root" fetch -q --depth=1 origin "refs/tags/$tag:refs/tags/$tag"
fi
if [[ -n "$tag" ]]; then
  found=false
  for path in docs/aerove/CHANGELOG.md docs/CHANGELOG_Aerove.md CHANGELOG.md; do
    if git -C "$root" show "$tag:$path" >"$out/CHANGELOG.md" 2>/dev/null; then
      found=true
      break
    fi
  done
  if [[ "$found" != true ]]; then
    echo "error: released tag $tag has no recorded changelog" >&2
    exit 1
  fi
else
  cp "$root/docs/aerove/CHANGELOG.md" "$out/CHANGELOG.md"
fi

node "$root/tooling/website/render_site.mjs" "$out" "${SITE_URL:-https://ivansolis1989.github.io/Aerove}"

echo "site assembled in $out"
