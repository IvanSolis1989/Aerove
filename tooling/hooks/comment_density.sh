#!/usr/bin/env bash

set -euo pipefail

# Git exports these to its hooks, and they outrank the `-C <dir>` the probes
# below rely on: every file then resolves its own directory as the repository,
# so a main checkout skips the file and a linked worktree measures all of it.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE

max_density="${COMMENT_DENSITY_MAX:-5}"
min_lines="${COMMENT_DENSITY_MIN_LINES:-20}"
rename_repo=''
rename_pairs=''

prepare_renames() {
  [ "$rename_repo" = "$1" ] && return 0
  rename_repo="$1"
  rename_pairs="$(git -C "$1" -c core.quotePath=false diff HEAD --find-renames --name-status --diff-filter=R -- 2>/dev/null || true)"
}

is_checkable() {
  local file="$1"
  case "$file" in
    *.dart | *.kt | *.kts | *.swift | *.go | *.rs | *.java | *.cpp | *.cc | *.h | *.hpp | *.gradle | *.yaml | *.yml) ;;
    *) return 1 ;;
  esac
  case "$file" in
    */build/* | */generated/* | */l10n/intl/* | */l10n/l10n.dart | */Clash.Meta/* | */.dart_tool/* | */third_party/material-color-utilities/src/* | */.github/actions/flutter/action.yaml) return 1 ;;
    *.g.dart | *.freezed.dart | */frb_generated*.dart | */frb_generated.rs | */open_container.dart) return 1 ;;
  esac
  return 0
}

added_for() {
  local file="$1"
  [ -f "$file" ] || return 0
  is_checkable "$file" || return 0

  local repo relative physical previous
  repo="$(git -C "$(dirname "$file")" rev-parse --show-toplevel 2>/dev/null || true)"
  [ -n "$repo" ] || return 0

  relative="$file"
  physical="$(cd "$repo" && pwd)"
  case "$file" in
    "$repo"/*) relative="${file#"$repo"/}" ;;
    "$physical"/*) relative="${file#"$physical"/}" ;;
  esac

  if git -C "$repo" ls-files --error-unmatch "$relative" >/dev/null 2>&1; then
    prepare_renames "$repo"
    previous="$(printf '%s\n' "$rename_pairs" | awk -F '\t' -v target="$relative" '$3 == target { print $2; exit }')"
    git -C "$repo" diff HEAD --find-renames -U0 -- "${previous:-$relative}" "$relative" | grep -E '^\+' | grep -vE '^\+\+\+' |
      sed "s|^+|$relative\t|" || true
  else
    sed "s|^|$relative\t|" "$file" || true
  fi
}

comment_lines() {
  local body="$1"
  printf '%s\n' "$body" |
    grep -E "$(printf '\t')[[:space:]]*(//|/\*|#|\*([[:space:]]|/|$))" |
    grep -vE 'ignore:|ignore_for_file:|coverage:ignore|Copyright|SPDX-|Licensed under|GENERATED|dart format off|yaml-language-server|[[:space:]]#!$|[[:space:]]#!/|#!?\[|#(include|define|undef|ifdef|ifndef|if|elif|else|endif|pragma|error)\b' |
    awk '
      {
        separator = index($0, "\t")
        if (!separator) {
          print
          next
        }
        path = substr($0, 1, separator - 1)
        line = substr($0, separator + 1)
        if (path ~ /\.go$/ && (line ~ /^[[:space:]]*\/\/go:build[[:space:]].+$/ || line ~ /^[[:space:]]*\/\/export[[:space:]]+[[:alpha:]_][[:alnum:]_]*[[:space:]]*$/)) {
          next
        }
        print
      }
    ' || true
}

changed_files() {
  local repo="$1"
  {
    git -C "$repo" diff HEAD --name-only -z 2>/dev/null || true
    git -C "$repo" ls-files --others --exclude-standard -z 2>/dev/null || true
  }
}

evaluate() {
  local file="$1" added total hits count density
  added="$(added_for "$file")"
  [ -n "$added" ] || return 0

  total="$(printf '%s\n' "$added" | grep -c . || true)"
  [ "$total" -ge "$min_lines" ] || return 0

  hits="$(comment_lines "$added")"
  count="$(printf '%s' "$hits" | grep -c . || true)"
  [ "$count" -gt 0 ] || return 0

  density="$(awk -v c="$count" -v t="$total" 'BEGIN { printf "%.1f", c * 100 / t }')"
  [ "$(awk -v d="$density" -v m="$max_density" 'BEGIN { print (d > m) ? 1 : 0 }')" -eq 1 ] || return 0

  printf '%s: %s of %s added lines are standalone comments (%s%%)\n\n' \
    "$(printf '%s\n' "$added" | head -1 | cut -f1)" "$count" "$total" "$density"
  printf '%s\n\n' "$(printf '%s\n' "$hits" | sed 's/\t/: /')"
}

collect() {
  local file repo
  for file in "$@"; do
    case "$file" in
      /*) ;;
      [A-Za-z]:*) file="${file//\\//}" ;;
      *) file="$PWD/$file" ;;
    esac
    repo="$(git -C "$(dirname "$file")" rev-parse --show-toplevel 2>/dev/null || true)"
    [ -z "$repo" ] || prepare_renames "$repo"
    evaluate "$file"
  done
}

collect_changed() {
  local repo file
  repo="$(git rev-parse --show-toplevel 2>/dev/null || true)"
  [ -n "$repo" ] || return 0
  prepare_renames "$repo"

  while IFS= read -r -d '' file; do
    evaluate "$repo/$file"
  done < <(changed_files "$repo")
}

if [ "$#" -gt 0 ]; then
  mode='argv'
  findings="$(collect "$@")"
else
  mode='hook'
  payload="$(cat)"
  file="$(printf '%s' "$payload" | jq -r '.tool_input.file_path // .tool_response.filePath // empty' 2>/dev/null || true)"
  if [ -n "$file" ]; then
    findings="$(collect "$file")"
  else
    findings="$(collect_changed)"
  fi
fi

[ -n "$findings" ] || exit 0

cat >&2 <<REPORT
These files are over the ${max_density}% comment cap this repository holds to. Healthy changes
here sit under 4%; the outlier that prompted this gate ran 22%.

$findings
Comments are welcome when they carry something the code cannot say. Trim the ones that
restate what the code already does, narrate the change you just made, or annotate step by
step - a block needing a comment per line needs better names or a smaller decomposition
instead. Keep the few that record a non-obvious constraint, and prefer a test or an
.agents/ entry when the fact belongs there. See the Comments section of .agents/rules.md.
Set COMMENT_DENSITY_MAX to adjust this gate when a change genuinely warrants it.
REPORT

if [ "$mode" = 'argv' ]; then
  exit 1
fi
exit 2
