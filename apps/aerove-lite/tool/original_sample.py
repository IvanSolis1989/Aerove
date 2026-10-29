"""Exact immutable original-APK one-method diagnosis; never an acceptance receipt."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex

from instrumentation_shards import parse_log, sha

TARGET = "lite125-original-samples"
FRAME_TARGET = "lite158-frame-sample"
TARGETS = (TARGET, FRAME_TARGET)


def selected(spec, sample):
    if sample not in spec.get("samples", {}) or set(spec.get("samples", {})) not in ({"glyph", "solid", "clear"}, {"solid"}):
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
    target = proof.get("runtimeTarget")
    expected_samples = {"solid"} if target == FRAME_TARGET else {"glyph", "solid", "clear"}
    if (target not in TARGETS or set(spec["samples"]) != expected_samples or proof.get("diagnosticOriginalRuntime") is not True
            or proof.get("promotionAccepted") is not False or proof.get("originalAppIdentity") != spec["identity"]
            or proof.get("apks") != spec["apks"] or selector != value["selector"]):
        raise ValueError("Original sample requires exact fixed runtime/App/test and selector proof")
    return dict(diagnosticOnly=True, accepted=False, promotionAccepted=False, originalSample=value, originalSampleApks=spec["apks"])


def execution_provenance(source, log, selection, trace_receipt):
    if selection is None or trace_receipt is None:
        raise ValueError('Frame execution provenance requires selection and trace receipt')
    selection, trace_receipt, log = Path(selection), Path(trace_receipt), Path(log)
    base = selection.parent.resolve()
    discovery_log = base / 'sample-discovery.log'
    expected_trace = base / 'clear-trace' / 'TRACE-RECEIPT.json'
    paths = (selection, trace_receipt, discovery_log, log)
    if (selection.name != 'SAMPLE-SELECTION.json' or log.resolve() != base / 'instrumentation.log'
            or trace_receipt.resolve() != expected_trace or trace_receipt.parent.is_symlink()
            or any(path.is_symlink() or not path.is_file() for path in paths)
            or selection.stat().st_size > 1024 * 1024 or trace_receipt.stat().st_size > 1024 * 1024):
        raise ValueError('Frame execution provenance paths are not the isolated task outputs')
    chosen = json.loads(selection.read_text())
    if (chosen.get('source') != source or chosen.get('mode') != 'log-only-no-execution'
            or chosen.get('passedTests') != [] or chosen.get('promotionAccepted') is not False
            or chosen.get('diagnosticOnly') is not True or chosen.get('aggregateAcceptance') is not False
            or chosen.get('logSha256') != sha(discovery_log)):
        raise ValueError('Frame discovery selection/source/hash differs')
    discovery = verify(source, discovery_log, discovery=True)
    if chosen.get('descriptions') != discovery['descriptions']:
        raise ValueError('Frame discovery selection is not its exact original method')
    if log.resolve() == discovery_log.resolve() or sha(log) == chosen['logSha256']:
        raise ValueError('Frame discovery log cannot be reused as execution')
    trace = json.loads(trace_receipt.read_text())
    command = trace.get('instrumentationCommand')
    serial = source.get('deviceSerial')
    expected = ['-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'debug', 'false',
                '-e', 'timeout_msec', '120000', '-e', 'class', shlex.quote(source['originalSample']['selector']),
                '-e', 'fixtureUrl', 'http://10.0.2.2:18080', '-e', 'sourceRevision', source.get('sourceRevision'),
                '-e', 'isolatedBackgroundPolicy', 'true', '-e', 'deviceKind', 'CI-emulator',
                '-e', 'rendererBackend', shlex.quote(source.get('rendererBackend', '')),
                'dev.aerove.lite.test/androidx.test.runner.AndroidJUnitRunner']
    if (not isinstance(command, list) or not command or Path(command[0]).name not in ('adb', 'adb.exe')
            or command[1:] != expected or not re.fullmatch(r'emulator-[0-9]+', serial or '')
            or source.get('testTimeoutMillis') != 120000 or trace.get('deviceSerial') != serial
            or trace.get('targetPackage') != 'dev.aerove.lite' or trace.get('traceStarted') is not True
            or trace.get('instrumentationSkipped', False) is not False or trace.get('interruptSignals') != []
            or trace.get('instrumentationReturnCode') != 0 or trace.get('wrapperReturnCode') != 0):
        raise ValueError('Frame trace does not identify the original execution command/serial/terminal')
    start, end = (trace.get(key) for key in ('instrumentationStartedHostMonotonicNanos', 'instrumentationFinishedHostMonotonicNanos'))
    if type(start) is not int or type(end) is not int or not 0 < start < end:
        raise ValueError('Frame trace execution timestamps are incomplete')
    commands = trace.get('commands', [])
    if (not commands or any(not isinstance(row.get('command'), list) or row['command'][:3] != command[:3] for row in commands)):
        raise ValueError('Frame trace before/after commands changed device identity')
    pid_reads = [row for row in commands if row['command'][3:] == ['shell', 'pidof', 'dev.aerove.lite']]
    if (len(pid_reads) != 2 or not 0 < pid_reads[0].get('hostMonotonicEndNanos', 0) <= start
            or not start <= pid_reads[1].get('hostMonotonicStartNanos', 0) < pid_reads[1].get('hostMonotonicEndNanos', 0) <= end):
        raise ValueError('Frame trace before/after target observations are incomplete')
    return dict(selectionReceiptSha256=sha(selection), discoveryLogSha256=chosen['logSha256'],
                traceReceiptSha256=sha(trace_receipt), deviceSerial=serial, instrumentationCommand=command,
                instrumentationStartedHostMonotonicNanos=start, instrumentationFinishedHostMonotonicNanos=end,
                tracePulled=trace.get('tracePulled') is True, traceErrors=trace.get('errors', []),
                traceCoverageValidated=False, crossHostProducerAttestation=False)


def verify(source, log, discovery=False, *, selection=None, trace_receipt=None):
    if (source.get("phase") != "original-sample" or source.get("runtimeTarget") not in TARGETS
            or source.get("diagnosticOnly") is not True or source.get("promotionAccepted") is not False
            or source.get("runtimeTarget") == FRAME_TARGET and source.get("accepted") is not False
            or source.get("testShard") is not None):
        raise ValueError("Sample evidence must stay outside full-shard acceptance")
    value = source["originalSample"]
    if source['runtimeTarget'] == FRAME_TARGET and not discovery:
        provenance = execution_provenance(source, log, selection, trace_receipt)
        from instrumentation_results import parse_execution
        parsed = parse_execution(log, [value['description']])
        record = parsed['records'][value['description']]
        if (parsed['errors'] or parsed['quarantined'] or parsed['observedStarts'] != [value['description']]
                or record['status'] not in ('passed', 'failed', 'error') or source.get('accepted') is not False):
            raise ValueError('Frame sample did not report exactly its single original terminal: ' + repr(parsed['errors']))
        if record['status'] == 'passed' and re.findall(r"OK \((\d+) tests?\)", Path(log).read_text()) != ['1']:
            raise ValueError('Passing original frame sample requires its complete one-test summary')
        return dict(schema=1, diagnosticOnly=True, accepted=False, promotionAccepted=False,
                    source=source, mode='one-original-method-execution', selectionVerified=True,
                    originalTestStatus=record['status'], originalStatusCode=record['code'], originalRecord=record,
                    descriptions=[value['description']], logSha256=sha(log), passedTests=[],
                    aggregateAcceptance=False, performanceEvidence=False, executionProvenance=provenance)
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
    parser.add_argument('--selection', type=Path)
    parser.add_argument('--trace-receipt', type=Path)
    args = parser.parse_args()
    from emulator_original84 import runtime_spec
    from emulator_current import validate_renderer_proof
    proof = validate_renderer_proof(os.environ["AEROVE_CURRENT_RENDERER_PROOF"])
    source = json.loads(args.source.read_text())
    expected = context(runtime_spec(proof['runtimeTarget']), proof, os.environ["AEROVE_ORIGINAL_SAMPLE"], os.environ["AEROVE_ORIGINAL_SAMPLE_SELECTOR"])
    if (source.get('runtimeTarget') != proof['runtimeTarget'] or any(source.get(key) != value for key, value in expected.items())
            or source.get("originalAppIdentity") != proof["originalAppIdentity"]):
        raise ValueError("Sample source evidence differs from original runtime registry/proof")
    if source['runtimeTarget'] == FRAME_TARGET:
        harness = proof.get('harnessIdentity', {})
        if (source.get('harnessIdentity') != harness or source.get('harnessRevision') != harness.get('sourceRevision')
                or source.get('harnessRun') != harness.get('workflowRun')
                or source.get('sourceRevision') != proof['originalAppIdentity'].get('sourceRevision')
                or source.get('deviceSerial') != proof.get('deviceSerial')
                or source.get('rendererBackend') != proof.get('rendererBackend')):
            raise ValueError('Frame sample source lost original App/current harness/device identity')
        if not args.discovery and (args.selection is None or args.trace_receipt is None
                or args.selection.resolve() != args.source.parent.resolve() / 'SAMPLE-SELECTION.json'
                or args.trace_receipt.resolve() != args.source.parent.resolve() / 'clear-trace' / 'TRACE-RECEIPT.json'):
            raise ValueError('Frame execution provenance must belong to the current SOURCE directory')
    try:
        result = verify(source, args.log, args.discovery, selection=args.selection, trace_receipt=args.trace_receipt)
    except Exception as error:
        args.output.write_text(json.dumps(dict(diagnosticOnly=True, promotionAccepted=False, validationError=repr(error),
                                                source=source, logSha256=sha(args.log)), indent=2) + "\n")
        raise
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    return 1 if result.get('originalTestStatus') in ('failed', 'error') else 0


if __name__ == "__main__":
    raise SystemExit(main())
