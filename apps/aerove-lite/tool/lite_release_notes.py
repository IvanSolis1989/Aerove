"""Render release notes from the exact accepted Lite source and Build Info."""

import argparse
import json
import re
from pathlib import Path


CHANGELOG = "docs/aerove-lite/CHANGELOG.md"
BEGIN = "<!-- aerove-lite:promotion:begin -->"
END = "<!-- aerove-lite:promotion:end -->"


def _sections(markdown):
    headings = list(re.finditer(r"^##[ \t]+(.+)$", markdown, re.MULTILINE))
    result = {}
    for i, heading in enumerate(headings):
        title = heading.group(1).strip()
        if title in result:
            raise ValueError("Duplicate Lite changelog section")
        end = headings[i + 1].start() if i + 1 < len(headings) else len(markdown)
        result[title] = markdown[heading.end():end].strip()
    return result


def render(markdown, tag, info, acceptance_run, acceptance_attempt, previous_markdown=""):
    expected_tag = f"lite-v{info.get('version')}+{info.get('version_code')}"
    if tag != expected_tag:
        raise ValueError("Lite tag does not match accepted Build Info")
    source = info.get("source", "")
    tree = info.get("tree", "")
    if not re.fullmatch(r"[0-9a-f]{40}", source) or not re.fullmatch(r"[0-9a-f]{40}", tree):
        raise ValueError("Build Info must identify exact source and tree")
    if (info.get("workflow") != f"https://github.com/IvanSolis1989/Aerove/actions/runs/{acceptance_run}"
            or info.get("run_attempt") != acceptance_attempt
            or type(info.get("run_number")) is not int or info["run_number"] < 1):
        raise ValueError("Acceptance attempt does not match Build Info")
    sections = _sections(markdown)
    notes = sections.get("待发布")
    if not notes or not re.search(r"^[ \t]*-[ \t]+\S", notes, re.MULTILINE) or "<!--" in notes:
        raise ValueError("Lite changelog must contain real pending release notes")
    if previous_markdown and notes == _sections(previous_markdown).get("待发布"):
        raise ValueError("Lite pending release notes are unchanged from the previous release source")
    return (f"# AEROVE Lite {tag}\n\n{BEGIN}\n"
            f"验收工作流：`{info['workflow']}`；run `{acceptance_run}`，attempt `{acceptance_attempt}`。\n\n"
            f"源码：`{source}`；源码树：`{tree}`。\n\n{notes}\n{END}\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--build-info", required=True, type=Path)
    parser.add_argument("--source-changelog", required=True, type=Path)
    parser.add_argument("--previous-changelog", type=Path)
    parser.add_argument("--acceptance-run", required=True, type=int)
    parser.add_argument("--acceptance-attempt", required=True, type=int)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    info = json.loads(args.build_info.read_text(encoding="utf-8"))
    args.output.write_text(render(args.source_changelog.read_text(encoding="utf-8"), args.tag,
                                  info, args.acceptance_run, args.acceptance_attempt,
                                  args.previous_changelog.read_text(encoding="utf-8") if args.previous_changelog else ""), encoding="utf-8")


if __name__ == "__main__":
    main()
