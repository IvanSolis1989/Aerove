"""Exact immutable original-APK one-method diagnosis; never an acceptance receipt."""
import argparse
import json
import os
from pathlib import Path
import re

from instrumentation_shards import parse_log, sha

TARGET = "lite125-original-samples"


def selected(spec, sample):
    if sample not in ("glyph", "solid", "clear") or set(spec.get("samples", {})) != {"glyph", "solid", "clear"}:
        raise ValueError("One approved original sample is required")
    value = spec["samples"][sample]
    if (set(value) != {"description", "className", "testName", "selector"}
            or not re.fullmatch(r"[A-Za-z0-9_.$]+", value["className"])
            or not re.fullmatch(r"[A-Za-z0-9_]+(?:\[[A-Za-z0-9_]+\])?", value["testName"])
            or value["selector"] != value["className"] + "#" + value["testName"]
            or value["description"] != value["testName"] + "(" + value["className"] + ")"):
        raise ValueError("Runtime registry sample description/selector is inconsistent")
    return dict(value, sample=sample)


def context(spec, proof, sample, selector):
    value = selected(spec, sample)
    if (proof.get("runtimeTarget") != TARGET or proof.get("diagnosticOriginalRuntime") is not True
            or proof.get("promotionAccepted") is not False or proof.get("originalAppIdentity") != spec["identity"]
            or proof.get("apks") != spec["apks"] or selector != value["selector"]):
        raise ValueError("Original sample requires exact fixed runtime/App/test and selector proof")
    return dict(diagnosticOnly=True, promotionAccepted=False, originalSample=value, originalSampleApks=spec["apks"])


def verify(source, log, discovery=False):
    if (source.get("phase") != "original-sample" or source.get("runtimeTarget") != TARGET
            or source.get("diagnosticOnly") is not True or source.get("promotionAccepted") is not False
            or source.get("testShard") is not None):
        raise ValueError("Sample evidence must stay outside full-shard acceptance")
    value = source["originalSample"]
    records = parse_log(log)
    if (len(records) != 1 or records[0]["description"] != value["description"]
            or records[0]["className"] != value["className"] or records[0]["testName"] != value["testName"]
            or records[0]["status"] != "passed"):
        raise ValueError("Selector did not report exactly its single original method")
    if not discovery and re.findall(r"OK \((\d+) tests?\)", Path(log).read_text()) != ["1"]:
        raise ValueError("Original sample must report exactly one complete passing test summary")
    return dict(schema=1, diagnosticOnly=True, promotionAccepted=False, source=source,
                mode="log-only-no-execution" if discovery else "one-original-method-execution",
                descriptions=[value["description"]], logSha256=sha(log),
                passedTests=[] if discovery else [value["description"]], aggregateAcceptance=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "log", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--discovery", action="store_true")
    args = parser.parse_args()
    from emulator_original84 import runtime_spec
    from emulator_current import validate_renderer_proof
    proof = validate_renderer_proof(os.environ["AEROVE_CURRENT_RENDERER_PROOF"])
    source = json.loads(args.source.read_text())
    expected = context(runtime_spec(TARGET), proof, os.environ["AEROVE_ORIGINAL_SAMPLE"], os.environ["AEROVE_ORIGINAL_SAMPLE_SELECTOR"])
    if (any(source.get(key) != value for key, value in expected.items())
            or source.get("originalAppIdentity") != proof["originalAppIdentity"]):
        raise ValueError("Sample source evidence differs from original runtime registry/proof")
    try:
        result = verify(source, args.log, args.discovery)
    except Exception as error:
        args.output.write_text(json.dumps(dict(diagnosticOnly=True, promotionAccepted=False, validationError=repr(error),
                                                source=source, logSha256=sha(args.log)), indent=2) + "\n")
        raise
    args.output.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
