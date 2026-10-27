"""Failure-tolerant diagnostic index; never an instrumentation acceptance receipt."""
import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re

import instrumentation_shards as shards

STATES = {0: "passed", -1: "error", -2: "failed", -3: "ignored", -4: "assumption"}
MAX_LOG_BYTES = 32 * 1024 * 1024


def read_json(path):
    path = Path(path)
    if path.is_symlink() or path.stat().st_size > 1024 * 1024:
        raise ValueError("Invalid diagnostic JSON input: " + path.name)
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Diagnostic JSON must be an object: " + path.name)
    return value


def parse_execution(path, expected):
    path = Path(path)
    output = dict(log=path.as_posix(), sha256=None, bytes=None, errors=[],
                  records={x: dict(description=x, status="unknown") for x in expected}, observedStarts=[], terminalCodes=[])
    if path.is_symlink() or not path.is_file():
        output["errors"].append("Missing/nonregular execution log")
        return output
    with path.open("rb") as stream:
        output["sha256"] = hashlib.file_digest(stream, "sha256").hexdigest()
    output["bytes"] = path.stat().st_size
    if output["bytes"] > MAX_LOG_BYTES:
        output["errors"].append("Execution log exceeds diagnostic read bound")
        return output
    text = path.read_text(encoding="utf-8-sig", errors="strict")
    fields, starts, terminals, active = {}, {}, {}, None
    uncertain = set()
    fields_start, stack_line, quarantine = None, None, False
    last_ordinal = 0
    for number, raw in enumerate(text.splitlines(), 1):
        timestamp = None
        match = re.search(r"(\d{4}-\d\d-\d\dT[\d:.]+Z)\s?(.*)$", raw)
        if match:
            timestamp, raw = match.groups()
        if raw.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = raw[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if sep:
                if key in fields:
                    output["errors"].append(f"line {number}: duplicate status field {key}")
                fields.setdefault(key, value)
                fields_start = fields_start or number
                if key == "stack":
                    stack_line = number
        elif raw.startswith("INSTRUMENTATION_STATUS_CODE: "):
            try:
                code = int(raw.split(": ", 1)[1])
                if code not in (1, *STATES):
                    raise ValueError("unknown status code")
                if fields.get("id") != "AndroidJUnitRunner" or not all(fields.get(k) for k in ("class", "test", "current", "numtests")):
                    raise ValueError("missing Runner identity/count")
                name = f"{fields['test']}({fields['class']})"
                ordinal, total = int(fields["current"]), int(fields["numtests"])
                if total != len(expected):
                    quarantine = True
                    raise ValueError("count differs from verified execution plan; possible logOnly/combined input")
                if name not in expected:
                    raise ValueError("test outside verified execution plan")
                record = dict(description=name, className=fields["class"], method=fields["test"], log=path.as_posix(), logSha256=output["sha256"],
                              firstStatusLine=fields_start, statusCodeLine=number, timestamp=timestamp)
                if code == 1:
                    if name in starts or active is not None or ordinal != last_ordinal + 1:
                        output["errors"].append(f"line {number}: duplicate/overlapping/noncontiguous start")
                    starts.setdefault(name, record)
                    last_ordinal = ordinal
                    active = (name, ordinal, total)
                else:
                    if active != (name, ordinal, total) or name in terminals:
                        raise ValueError("orphan/mismatched/duplicate terminal")
                    start = starts[name]
                    seconds = None
                    if start["timestamp"] and timestamp:
                        seconds = (datetime.fromisoformat(timestamp) - datetime.fromisoformat(start["timestamp"])).total_seconds()
                        if seconds < 0:
                            raise ValueError("negative host-observed duration")
                    record.update(status=STATES[code], code=code, lineSpan=[start["firstStatusLine"], number],
                                  startLine=start["statusCodeLine"], stackLine=stack_line,
                                  firstException=fields.get("stack", "").split("\n", 1)[0][:800] or None,
                                  durationSeconds=seconds,
                                  durationMeasurement="decoded-host-log-arrival" if seconds is not None else "unavailable-in-raw-log")
                    terminals[name] = record
                    active = None
            except (ValueError, KeyError) as error:
                output["errors"].append(f"line {number}: {error}")
                if fields.get("class") and fields.get("test"):
                    uncertain.add(f"{fields['test']}({fields['class']})")
            fields, fields_start, stack_line = {}, None, None
        elif raw.startswith("INSTRUMENTATION_CODE: "):
            try:
                output["terminalCodes"].append(int(raw.split(": ", 1)[1]))
            except ValueError:
                output["errors"].append(f"line {number}: malformed instrumentation terminal")
        elif raw.startswith(("INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED")):
            output["errors"].append(f"line {number}: instrumentation aborted")
        elif stack_line is not None and not fields.get("stack") and raw.strip():
            fields["stack"] = raw.strip()
    if fields:
        output["errors"].append("Truncated status fields at end of log")
        if fields.get("class") and fields.get("test"):
            uncertain.add(f"{fields['test']}({fields['class']})")
    if output["terminalCodes"] != [-1]:
        output["errors"].append("Missing/multiple instrumentation terminal; combined discovery is forbidden")
    if len(output["terminalCodes"]) > 1:
        quarantine = True
    output["quarantined"] = quarantine
    output["observedStarts"] = sorted(starts)
    for name in expected:
        if quarantine:
            record = dict(description=name, status="unknown")
        elif name in terminals:
            record = terminals[name]
        elif name in starts:
            record = dict(starts[name], status="active", lineSpan=[starts[name]["firstStatusLine"], len(text.splitlines())])
        elif name in uncertain:
            record = dict(description=name, status="unknown")
        else:
            record = dict(description=name, status="unstarted")
        output["records"][name] = record
    return output


def build_index(source, discovery, sums, ordinary, isolated=None, discovery_log=None, host_exit=None):
    index = dict(schema=1, kind="instrumentation-diagnostic-index", diagnosticOnly=True,
                 accepted=False, aggregateAcceptance=False, complete=False, identityBound=False,
                 errors=[], hostExitCode=host_exit)
    try:
        state = read_json(source)
        index.update(sourceSha256=shards.sha(source), sourceFields={key: state.get(key) for key in
                     (*shards.IDENTITY, "appBuildRun", "harnessRevision", "harnessRun", "originalAppIdentity", "harnessIdentity")})
        found = read_json(discovery)
        plan = shards.split_plan(found)
        if state.get("phase") != "remaining" or found.get("discoveryMode") != "logOnly-no-test-method-execution":
            raise ValueError("Expected verified remaining discovery with explicit logOnly exclusion")
        identity = {key: str(state[key]) for key in shards.IDENTITY}
        if (identity != found["identity"] or state["testShard"]["shardIndex"] != found["shardIndex"]
                or state["testShard"]["numShards"] != found["numShards"]
                or found["discoveryCount"] != len(found["discoveredDescriptions"])):
            raise ValueError("Discovery/source shard identity mismatch")
        apk = {}
        for line in Path(sums).read_text().splitlines():
            digest, name = line.split(None, 1)
            name = Path(name.lstrip("*")).name
            if name in apk or not re.fullmatch("[0-9a-f]{64}", digest):
                raise ValueError("Malformed/duplicate APK checksum")
            apk[name] = digest
        if (set(apk) != {"app-release.apk", "app-release-androidTest.apk"} or apk != found["apkSha256"]
                or state.get("apks", apk) != apk):
            raise ValueError("Discovery/APK checksum mismatch")
        if discovery_log is None or shards.sha(discovery_log) != found["discoveryLogSha256"]:
            raise ValueError("Discovery log differs from verified discovery")
        harness = state.get("harnessIdentity") or dict(sourceRevision=state.get("harnessRevision"), workflowRun=state.get("harnessRun"))
        if not isinstance(harness, dict):
            raise ValueError("Separate harness identity must be an object")
        if not re.fullmatch("[0-9a-f]{40}", state["sourceRevision"]) or not re.fullmatch("[0-9a-f]{40}", harness.get("sourceRevision") or ""):
            raise ValueError("Missing original App or separate harness revision")
        index.update(identityBound=True, identity=identity, originalAppIdentity=state.get("originalAppIdentity") or dict(identity, appBuildRun=state.get("appBuildRun")),
                     harnessIdentity=harness, apkSha256=apk, shardIndex=found["shardIndex"], numShards=found["numShards"],
                     discoveryFingerprint=found["discoveryFingerprint"], discoveredCount=found["discoveryCount"],
                     discoverySyntheticExcluded=True, sourceSha256=shards.sha(source), discoveryReceiptSha256=shards.sha(discovery),
                     discoveryLogSha256=found["discoveryLogSha256"])
        normal = parse_execution(ordinary, plan["ordinary"])
        if normal["sha256"] == found["discoveryLogSha256"]:
            normal["errors"].append("Discovery synthetic log was supplied as execution")
            normal["records"] = {x: dict(description=x, status="unknown") for x in plan["ordinary"]}
        inputs = [dict(role="ordinary", **normal)]
        records = list(normal["records"].values())
        index["errors"] += normal["errors"]
        if plan["isolated"]:
            ordinary_passed = not normal["errors"] and all(x["status"] == "passed" for x in records)
            if isolated is not None and Path(isolated).is_file():
                scoped = parse_execution(isolated, plan["isolated"])
                inputs.append(dict(role="isolated-SSID", **scoped))
                records += list(scoped["records"].values())
                index["errors"] += scoped["errors"]
                if not ordinary_passed:
                    index["errors"].append("Isolated SSID was observed before ordinary full-pass gate")
            else:
                records += [dict(description=x, status="unstarted", reason="ordinary gate failed" if not ordinary_passed else "isolated invocation missing") for x in plan["isolated"]]
        elif isolated is not None and Path(isolated).is_file():
            index["errors"].append("Unexpected isolated log for a shard without SSID")
        for item in inputs:
            item.pop("records")
        counts = Counter(x["status"] for x in records)
        index.update(expectedCount=len(plan["ordinary"] + plan["isolated"]), counts=dict(counts), tests=sorted(records, key=lambda x:x["description"]), inputs=inputs,
                     slowest=sorted((x for x in records if x.get("durationSeconds") is not None), key=lambda x:x["durationSeconds"], reverse=True)[:10])
        index["complete"] = not index["errors"] and not any(counts[x] for x in ("active", "unstarted", "unknown"))
    except (OSError, ValueError, KeyError, TypeError) as error:
        index["errors"].append(f"{type(error).__name__}: {error}")
    return index


def markdown(index):
    rows = ["# Instrumentation diagnostic index", "", f"Diagnostic only; accepted=false; complete={index['complete']}; hostExit={index['hostExitCode']}",
            "", json.dumps(index.get("counts", {}), sort_keys=True), "", "|Test|State|Log lines|First exception|", "|---|---|---|---|"]
    for item in index.get("tests", []):
        if item["status"] != "passed":
            rows.append("|" + "|".join(str(x).replace("|", "\\|").replace("\n", " ") for x in
                        (item["description"], item["status"], str(item.get("log", "")) + ":" + str(item.get("lineSpan", "")), item.get("firstException") or item.get("reason", ""))) + "|")
    rows += ["", "## Slowest observed methods", "", "Raw logs without status timestamps have null duration; no device timing claim.",
             "", "|Test|Host-observed seconds|", "|---|---:|"]
    for item in index.get("slowest", []):
        rows.append("|" + item["description"].replace("|", "\\|") + "|" + str(item["durationSeconds"]) + "|")
    return "\n".join(rows + ["", "Errors: " + json.dumps(index["errors"]), ""])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("source", "discovery", "sums", "ordinary-log", "discovery-log", "output", "markdown"):
        parser.add_argument("--" + key, type=Path, required=True)
    parser.add_argument("--isolated-log", type=Path)
    parser.add_argument("--host-exit-code", type=int)
    args = parser.parse_args()
    index = build_index(args.source, args.discovery, args.sums, args.ordinary_log, args.isolated_log, args.discovery_log, args.host_exit_code)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.markdown.write_text(markdown(index), encoding="utf-8")
    print("Diagnostic index (never acceptance): " + json.dumps(index.get("counts", {}), sort_keys=True) + f"; complete={index['complete']}; errors={len(index['errors'])}")


if __name__ == "__main__":
    main()
