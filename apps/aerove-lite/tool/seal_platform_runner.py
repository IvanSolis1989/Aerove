"""Bind the same-graph Android test module to the single inspected Flutter release APK."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

from verify_apk import signing_certificate


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def classes(text):
    result = set(re.findall(r"^\s*C\s+d\s+\d+\s+\d+\s+\d+\s+(\S+)\s*$", text, re.M))
    require(bool(result), "No actual defined DEX classes from official apkanalyzer")
    return result


def seal(info, app, test, signature, manifest, graph, mapping, defined, driver):
    require(sha(app) == info["apk_sha256"], "Target app changed after its inspected one-time build")
    require(signing_certificate(signature) == info["signer_sha256"] and
            len([line for line in signature.splitlines() if "certificate SHA-256 digest:" in line]) == 1,
            "Platform runner signer differs from original production signer or is ambiguous")
    root = ET.fromstring(manifest); a = "{http://schemas.android.com/apk/res/android}"
    instrumentation = root.findall("instrumentation")
    require(root.get("package") == "dev.aerove.lite.platform.test" and len(instrumentation) == 1 and
            instrumentation[0].get(a + "targetPackage") == "dev.aerove.lite" and
            instrumentation[0].get(a + "name") == "dev.aerove.lite.platformqa.AcceptanceCheckpointRunner",
            "Platform APK must contain one runner for the existing production app")
    application = root.find("application")
    activities = application.findall("activity") if application is not None else []
    # These non-launcher helpers are declared by the pinned androidx.test:core:1.6.1 manifest.
    activity_helpers = {
        "androidx.test.core.app.InstrumentationActivityInvoker$BootstrapActivity",
        "androidx.test.core.app.InstrumentationActivityInvoker$EmptyActivity",
        "androidx.test.core.app.InstrumentationActivityInvoker$EmptyFloatingActivity",
    }
    require(application is not None and application.get(a + "testOnly") == "true" and
            not application.findall("activity-alias") and
            len({activity.get(a + "name") for activity in activities}) == len(activities) and
            all(activity.get(a + "name") in activity_helpers and not activity.findall("intent-filter/action")
                for activity in activities), "Test-only runner cannot be a second application launcher")
    tasks = re.findall(r"^(:[^ ]+) SKIPPED$", graph, re.M)
    require(tasks.count(":app:assembleRelease") == 1 and tasks.count(":platform-qa:assembleRelease") == 1 and
            len(tasks) == len(set(tasks)), "One Gradle graph must assemble the app once and its target test module once")
    defined_classes = classes(defined)
    target_firstparty = {target for original, target in re.findall(r"^([^ #\s][^ ]+) -> ([^ ]+):$", mapping, re.M)
                         if original.startswith("dev.aerove.lite.")}
    require(bool(target_firstparty), "Actual target R8 mapping is required")
    require(not defined_classes & target_firstparty, "Runner duplicated target production DEX definitions")
    require("dev.aerove.lite.platformqa.AcceptanceCheckpointRunner" in defined_classes and
            "dev.aerove.lite.FlutterProductionUiTest" in defined_classes, "Runner lacks actual observer/UI test definitions")
    contract = json.loads(Path(__file__).with_name("android_acceptance_contract.json").read_text())
    require(hashlib.sha256(driver.read_bytes().replace(b"\r\n",b"\n")).hexdigest() == contract["uiSourceSha256"], "Integrate the precise coordinated Flutter UI candidate before building")
    device_source = Path(__file__).resolve().parents[1] / contract["runtimeDeviceAttestorSource"]["path"]
    require(hashlib.sha256(device_source.read_bytes().replace(b"\r\n", b"\n")).hexdigest() == contract["runtimeDeviceAttestorSource"]["sha256"],
            "Actual runtime device observer source differs from its current contract")
    return dict(schema=1, source=info["source"], tree=info["tree"], productionApkSha256=sha(app), testApkSha256=sha(test),
                signerSha256=info["signer_sha256"], targetPackage="dev.aerove.lite", testPackage="dev.aerove.lite.platform.test",
                runner="dev.aerove.lite.platformqa.AcceptanceCheckpointRunner", productionBuildCount=1,
                targetR8MappingSha256=hashlib.sha256(mapping.encode()).hexdigest(),
                definedClassesSha256=hashlib.sha256(defined.encode()).hexdigest(), copiedProductionClasses=False,
                uiTestSourceSha256=contract["uiSourceSha256"], uiTestPhysicalSha256=sha(driver),
                runtimeDeviceAttestorSourceSha256=contract["runtimeDeviceAttestorSource"]["sha256"], androidExecuted=False)


def seal_abi(graph, mapping, references, rules_path, report_path, test_mapping, test_configuration):
    tasks = re.findall(r"^(:[^ ]+) SKIPPED$", graph, re.M)
    ordered = [":app:compileReleaseKotlin", ":platform-qa:compileReleaseKotlin",
               ":app:generateReleasePlatformTestAppKeepRules", ":app:minifyReleaseWithR8", ":platform-qa:minifyReleaseWithR8"]
    require(all(tasks.count(task)==1 for task in ordered) and [tasks.index(task) for task in ordered]==sorted(tasks.index(task) for task in ordered),
            "Required pre-R8 test compile -> typed App ABI keep -> App R8 -> test R8 remap DAG is absent")
    report = json.loads(report_path.read_text())
    require(report.get("schema")==3 and report.get("generator")=="R8 TraceReferences" and report.get("allowObfuscation") is True and
            report.get("rulesSha256")==sha(rules_path) and report.get("referenceDiagnostics",{}).get("traceCompleted") is True and
            report["referenceDiagnostics"].get("fatalCount")==0 and report.get("inputClasses",{}).get("source",{}).get("classes",0)>0 and
            report.get("inputClasses",{}).get("target",{}).get("classes",0)>0, "Actual typed pre-R8 ABI inference failed/missing")
    require(not re.search(r"(?:class|interface)\s+(?:\*|dev\.aerove\.lite\.\*)",rules_path.read_text()), "App-wide wildcard keep is forbidden")
    require(test_mapping.is_file() and test_mapping.stat().st_size>0 and test_configuration.is_file(), "Test R8 mapping/configuration output missing")
    translations=dict(re.findall(r"^([^ #\s][^ ]+) -> ([^ ]+):$",mapping,re.M))
    referenced=set(re.findall(r"^\s*C\s+r\s+\d+\s+\d+\s+\d+\s+(\S+)\s*$",references,re.M))
    critical=["dev.aerove.lite.core.MeowCapabilitiesCodec","dev.aerove.lite.flutter.RuntimeAppliedIdentity",
              "dev.aerove.lite.flutter.NativeLiteVerifiedSource","dev.aerove.lite.flutter.MeowQueryGraph"]
    for original in critical:
        require(original in translations and translations[original] in referenced, "Actual test DEX has no reference to mapped App ABI: "+original)
        if original!=translations[original]:require(original not in referenced,"Test DEX retained an obsolete unmapped App class reference")
    return dict(schema=1,mode="typed-pre-r8-app-keeps-and-official-test-r8-applymapping",appRulesSha256=sha(rules_path),
                appAbiReportSha256=sha(report_path),testR8MappingSha256=sha(test_mapping),testR8ConfigurationSha256=sha(test_configuration),
                targetMappingSha256=hashlib.sha256(mapping.encode()).hexdigest(),criticalMappedReferences={k:translations[k] for k in critical},
                taskOrder=ordered,appR8Enabled=True,testR8Enabled=True)


def shared_reuse(repository):
    contract = json.loads(Path(__file__).with_name("android_shared_ui_reuse.json").read_text())
    require(contract.get("schema") == 1 and contract.get("scope") == "exact-four-canonical-files-only" and
            len(contract.get("files", {})) == 4 and contract.get("liteAndroidAccepted") is False,
            "Shared reuse must retain the original limited scope")
    for relative, expected in contract["files"].items():
        path = repository / relative
        require(path.resolve() == path.absolute() and sha(path) == expected, "A canonical shared file changed: " + relative)
    return dict(scope=contract["scope"], files=contract["files"], standardSource=contract["standardSource"],
                standardRun=contract["standardRun"], standardApkSha256=contract["standardApkSha256"],
                contractSha256=sha(Path(__file__).with_name("android_shared_ui_reuse.json")), liteAndroidAccepted=False)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("info", "app", "test", "mapping", "graph", "driver", "output"):
        p.add_argument("--" + name, type=Path, required=True)
    for name in ("abi-rules","abi-report","test-mapping","test-configuration"):
        p.add_argument("--"+name,type=Path,required=True)
    for name in ("profile-cancel-implementation","profile-cancel-green","profile-cancel-evidence"):
        p.add_argument("--"+name,type=Path,required=True)
    p.add_argument("--apksigner", type=Path, required=True)
    p.add_argument("--apkanalyzer", type=Path, required=True)
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    evidence = {}
    for label, command in (
        ("signature", [args.apksigner, "verify", "--verbose", "--print-certs", args.test]),
        ("manifest", [args.apkanalyzer, "manifest", "print", args.test]),
        ("defined-classes", [args.apkanalyzer, "dex", "packages", "--defined-only", args.test]),
        ("all-references", [args.apkanalyzer, "dex", "packages", args.test])):
        run = subprocess.run([str(c) for c in command], stdin=subprocess.DEVNULL, capture_output=True, timeout=120)
        (args.output / ("platform-" + label + ".stdout.txt")).write_bytes(run.stdout)
        (args.output / ("platform-" + label + ".stderr.txt")).write_bytes(run.stderr)
        (args.output / ("platform-" + label + ".exit.txt")).write_text(str(run.returncode) + "\n")
        require(run.returncode == 0, label + " tool failed")
        evidence[label] = run.stdout.decode()
    receipt = seal(json.loads(args.info.read_text()), args.app, args.test, evidence["signature"], evidence["manifest"],
                   args.graph.read_text(), args.mapping.read_text(), evidence["defined-classes"], args.driver)
    receipt["r8Abi"] = seal_abi(args.graph.read_text(),args.mapping.read_text(),evidence["all-references"],
        args.abi_rules,args.abi_report,args.test_mapping,args.test_configuration)
    for label,source in (("app-abi-rules.pro",args.abi_rules),("app-abi-report.json",args.abi_report),
                         ("test-mapping.txt",args.test_mapping),("test-configuration.txt",args.test_configuration)):
        (args.output/label).write_bytes(source.read_bytes())
    from profile_cancel_prerequisite import stage
    actual_info=json.loads(args.info.read_text())
    prerequisite_identity=json.loads(args.profile_cancel_green.read_text()).get("identity",{})
    require(prerequisite_identity==dict(source=actual_info["source"],run=actual_info["workflow"].rsplit("/",1)[-1],attempt=str(actual_info["run_attempt"])),
            "Cancellation prerequisite is not this exact source/workflow/attempt")
    receipt["profileCancelPrerequisite"]=stage(Path(__file__).resolve().parents[3],args.profile_cancel_evidence,
        args.profile_cancel_implementation,args.profile_cancel_green,args.output/"profile-cancel-prerequisite")
    receipt["sharedUiReuse"] = shared_reuse(Path(__file__).resolve().parents[3])
    (args.output / "platform-runner-build.json").write_text(json.dumps(receipt, indent=2) + "\n")


if __name__ == "__main__":
    main()
