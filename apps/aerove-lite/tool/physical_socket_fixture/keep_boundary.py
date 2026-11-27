"""Patch exact disposable Gradle/Python sources and summarize bounded phase evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import re

from paired_assembly_summary import summarize

GRADLE = "apps/aerove-lite/build-logic/src/main/kotlin/aerove.lite-app-common.gradle.kts"
PYTHON = "apps/aerove-lite/tool/generate_android_test_keep_rules.py"
PIN = {
    GRADLE: "340f52d8ecd4b8243f8dbb65692a3c2407c5a3df9892f89ed482160a84172fcd",
    PYTHON: "c7f12f751f0fe9e7a350c0a4b98523ffe0106cb834dc5fb913075c168fc372bf",
}
ENV = "AEROVE_KEEP_BOUNDARY"
MARK = r"(?:gradle_task|gradle_manifest|gradle_python_ok|python_start|inputs_ready|javac_start|javac_ok|javac_timeout|trace_start|trace_ok|trace_timeout|typed_boundary_rejected|typed_boundary_ok|outputs_written|python_ok|python_failed|javac_exit -?[0-9]{1,3}|trace_exit -?[0-9]{1,3})"
ROW = re.compile(MARK + r"\Z")


def swap(source, old, new):
    if source.count(old) != 1:
        raise ValueError("Unexpected trace-boundary anchor")
    return source.replace(old, new, 1)


def gradual(source):
    anchor = "    @TaskAction\n    fun generate() {\n"
    mark = ('        fun keepBoundary(tag: String) {\n'
            '            System.getenv("AEROVE_KEEP_BOUNDARY")?.let { File(it).appendText(tag + "\\n") }\n'
            '        }\n')
    source = swap(source, anchor, anchor + mark + '        keepBoundary("gradle_task")\n')
    anchor = '        execOperations.exec {\n            commandLine(pythonCommand.get(), generator.get().asFile.absolutePath,'
    source = swap(source, anchor, '        keepBoundary("gradle_manifest")\n' + anchor)
    anchor = '                "--report", report.get().asFile.absolutePath)\n        }.assertNormalExitValue()\n'
    return swap(source, anchor, anchor + '        keepBoundary("gradle_python_ok")\n')


def instrument(source):
    anchor = "\ndef collect_classes("
    helper = '''
def _keep_mark(tag):
    path = os.environ.get("AEROVE_KEEP_BOUNDARY")
    if path:
        with open(path, "a", encoding="ascii") as stream:
            stream.write(tag + "\\n")


def _keep_run(phase, command, **kwargs):
    _keep_mark(phase + "_start")
    try:
        completed = subprocess.run(command, **kwargs)
    except subprocess.TimeoutExpired:
        _keep_mark(phase + "_timeout")
        raise
    except subprocess.CalledProcessError as error:
        _keep_mark(phase + "_exit " + str(error.returncode))
        raise
    _keep_mark(phase + "_ok")
    return completed

'''
    source = swap(source, anchor, "\n"+helper+anchor)
    source = swap(source, '        diagnostics["inputClasses"] = inputs\n', '        diagnostics["inputClasses"] = inputs\n        _keep_mark("inputs_ready")\n')
    source = swap(source, 'compiled = subprocess.run(compile_command,', 'compiled = _keep_run("javac", compile_command,')
    source = swap(source, 'completed = subprocess.run(command, check=True, timeout=300,', 'completed = _keep_run("trace", command, check=True, timeout=300,')
    anchor = '            raise ValueError("Typed TraceReferences did not accept the protected app boundary")\n'
    source = swap(source, anchor, '            _keep_mark("typed_boundary_rejected")\n'+anchor+'        _keep_mark("typed_boundary_ok")\n')
    anchor = '            os.replace(generated, output)\n'
    source = swap(source, anchor, anchor+'            _keep_mark("outputs_written")\n')
    anchor = '        diagnostics["orderedInputs"] = input_evidence(manifest)\n        _generate(manifest, output, report, diagnostics)\n    except Exception as error:\n'
    source = swap(source, anchor, '        _keep_mark("python_start")\n'+anchor.replace('    except Exception as error:\n','        _keep_mark("python_ok")\n    except Exception as error:\n        _keep_mark("python_failed")\n'))
    return source


def stage(root):
    outputs = []
    for path, transform in [(GRADLE, gradual), (PYTHON, instrument)]:
        target = root / path
        data = target.read_bytes()
        if hashlib.sha256(data).hexdigest() != PIN[path]:
            raise ValueError("Unexpected source identity")
        outputs.append((target, transform(data.decode("utf-8"))))
    for target, data in outputs:
        target.write_text(data, encoding="utf-8")


def evidence(path):
    # No unrecognized line, path, exception message, or tool argument can escape.
    with path.open("rb") as stream:
        data = stream.read(4097)
    lines = data.decode("ascii", errors="replace").splitlines()
    if len(data) > 4096 or len(lines) > 20 or any(not ROW.fullmatch(x) for x in lines):
        return {"phaseEvidence": "rejected", "phases": [], "releaseAccepted": False}
    return {"phaseEvidence": "seen" if lines else "no_task_marker", "phases": lines, "releaseAccepted": False}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["stage", "report"])
    parser.add_argument("path", type=Path)
    parser.add_argument("--gradle-log", type=Path)
    args = parser.parse_args()
    if args.mode == "stage":
        stage(args.path)
    else:
        report = evidence(args.path)
        if args.gradle_log is not None:
            report["lastKnownGradleTask"] = summarize(args.gradle_log, 0, 0)["lastReportedKnownGradleTask"]
        print(json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    main()
