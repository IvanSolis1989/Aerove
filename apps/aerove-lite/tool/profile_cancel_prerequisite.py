"""Bind real existing profile-cancel Green scopes to actual app production bytes; never manufacture Green."""
import hashlib
import json
from pathlib import Path
import re
import shutil


def require(value,message):
    if not value:raise ValueError(message)


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def checked(root,relative,digest):
    path=Path(root)/relative
    require(not Path(relative).is_absolute() and not Path(relative).drive and "\\" not in relative and ".." not in Path(relative).parts and path.resolve()==path.absolute() and path.is_file(),"Unsafe/missing prerequisite evidence")
    require(sha(path)==digest,"Prerequisite evidence bytes changed")
    return path


def expanded(text,default_suite=None):
    text=re.sub(r"\x1b\[[0-9;]*m","",text)
    records=[];active=None;final=None
    for line in text.replace("\r","\n").splitlines():
        match=re.match(r"^\d+:\d+ \+(\d+)(?: -(\d+))?: (.*)$",line)
        if not match:continue
        passed,failed,payload=int(match[1]),int(match[2] or 0),match[3]
        if active is not None:
            dp,df=passed-active["passedBefore"],failed-active["failedBefore"]
            if dp+df:
                require((dp,df) in ((1,0),(0,1)),"Ambiguous Flutter terminal counter transition")
                records.append(dict(suite=active["suite"],name=active["name"],status="passed" if dp else "failed"));active=None
        if "All tests passed!" in payload or "Some tests failed" in payload:
            final=(passed,failed);continue
        if payload.startswith("loading ") or payload.endswith(("(setUpAll)","(tearDownAll)")) or payload.endswith("[E]"):continue
        suite_match=re.match(r"(.+\.dart): (.+)$",payload)
        suite,name=(suite_match[1].replace("\\","/"),suite_match[2]) if suite_match else (default_suite,payload)
        require(suite is not None,"Expanded reporter case needs one actual suite path")
        if active is not None:
            require(active["suite"]==suite and active["name"]==name,"A test began without a terminal counter")
        else:active=dict(suite=suite,name=name,passedBefore=passed,failedBefore=failed)
    require(active is None and final is not None and len(records)==sum(final),"Incomplete Flutter expanded terminal evidence")
    require(sum(r["status"]=="passed" for r in records)==final[0],"Flutter pass summary disagrees")
    return records


PRODUCTION=("common/profile_update.dart","common/request.dart","models/profile.dart","providers/action.dart","providers/actions/profiles.dart","providers/database.dart","views/profiles/profiles.dart","providers/generated/action.g.dart","providers/generated/database.g.dart")
TEST_SOURCES=("apps/aerove/test/providers/profile_update_cancel_test.dart","apps/aerove/test/features/profile_update_cancel_view_test.dart","apps/aerove/test/models/profile_save_test.dart","apps/aerove/test/providers/action_test.dart","apps/aerove/test/providers/automatic_maintenance_reset_test.dart")
SCOPES={"domain":("test/providers/profile_update_cancel_test.dart",11),"view":("test/features/profile_update_cancel_view_test.dart",2),"save":("test/models/profile_save_test.dart",5)}

def validate(repository,evidence_directory,implementation,green):
    root=Path(evidence_directory);manifest=json.loads(Path(implementation).read_text());proof=json.loads(Path(green).read_text())
    require(proof.get("schema")==1 and proof.get("kind")=="profile-cancel-production-green" and proof.get("passed") is True and
            proof.get("implementationManifestSha256")==sha(implementation),"Missing exact final profile-cancel Green prerequisite")
    expected={row["path"]:row["afterSHA256"] for row in manifest["files"] if row["path"].startswith("packages/aerove_client/lib/")}
    require(set(expected)=={"packages/aerove_client/lib/"+p for p in PRODUCTION} and proof.get("productionFiles")==expected,"Green production source manifest differs")
    all_sources={row["path"]:row["afterSHA256"] for row in manifest["files"]}
    require(len(manifest["files"])==len(all_sources)==14 and set(all_sources)==set(expected)|set(TEST_SOURCES),"Exact 9 production + 5 test implementation inputs required")
    for relative,digest in all_sources.items():
        if repository is not None:require(hashlib.sha256((Path(repository)/relative).read_bytes().replace(b"\r\n",b"\n")).hexdigest()==digest,"Actual App production file differs from Green: "+relative)
    runs={}
    for run in proof["runs"]:
        require(run["id"] not in runs,"Duplicate Green run identity")
        result_path=checked(root,run["result"],run["resultSha256"])
        stdout=checked(root,run["stdout"],run["stdoutSha256"]);stderr=checked(root,run["stderr"],run["stderrSha256"])
        result=json.loads(result_path.read_text());command=result.get("command",result.get("spec",{}).get("command"))
        require(type(result.get("exit")) is int and isinstance(command,list),"Missing real command result")
        result=dict(result,command=command)
        suites=[str(v).replace("\\","/") for v in result["command"] if str(v).endswith(".dart")]
        cases=expanded(stdout.read_text(encoding="utf-8-sig"),suites[0] if len(suites)==1 else None) if run.get("format")=="flutter-expanded" else None
        runs[run["id"]]=(run,result,cases)
    scopes={}
    for scope in proof["scopes"]:
        name=scope["name"];require(name in ("domain","view","save") and name not in scopes,"Unknown/duplicate profile-cancel scope")
        require((scope.get("suiteSuffix"),scope.get("expectedTests"))==SCOPES[name],"Required profile-cancel suite/count changed")
        run,result,cases=runs[scope["run"]];require(cases is not None,"Scope requires actual Flutter terminal records")
        selected=[r for r in cases if r["suite"].endswith(scope["suiteSuffix"])]
        require(len(selected)==scope["expectedTests"] and len(selected)>0 and len({(r["suite"],r["name"]) for r in selected})==len(selected) and all(r["status"]=="passed" for r in selected),"Selected Green scope is incomplete/failed")
        scopes[name]=dict(run=scope["run"],actualRunExit=result["exit"],suiteSuffix=scope["suiteSuffix"],cases=selected)
    require(set(scopes)=={"domain","view","save"},"All real domain/view/save scopes are required")
    checks={}
    for check in proof["checks"]:
        name=check["name"];require(name in ("analyze","generation") and name not in checks,"Unknown/duplicate prerequisite check")
        _,result,_=runs[check["run"]];require(result["exit"]==0,"Formal generation/analyze did not succeed")
        command=" ".join(map(str,result["command"]))
        require(("analyze" in command) if name=="analyze" else ("build_runner" in command or "generate" in command),"Check command does not implement its claimed role")
        checks[name]=dict(run=check["run"],exit=0)
    require(set(checks)=={"analyze","generation"},"Formal generation and analyze evidence are required")
    return dict(schema=1,kind="profile-cancel-verified-prerequisite",implementationManifestSha256=sha(implementation),
                greenEvidenceSha256=sha(green),productionFiles=expected,testSourceFiles={p:all_sources[p] for p in TEST_SOURCES},scopes=scopes,checks=checks,
                scope="related real Flutter host/unit scopes; not Android acceptance",androidExecuted=False)


def stage(repository,evidence_directory,implementation,green,output):
    result=validate(repository,evidence_directory,implementation,green)
    output=Path(output);output.mkdir(parents=True,exist_ok=False)
    proof=json.loads(Path(green).read_text())
    files={name for row in proof["runs"] for name in (row["result"],row["stdout"],row["stderr"])}
    for relative in sorted(files):
        destination=output/"evidence"/relative;destination.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(Path(evidence_directory)/relative,destination)
    shutil.copyfile(implementation,output/"implementation.json");shutil.copyfile(green,output/"green.json")
    result["serverSourceSha256"]=sha(Path(__file__).with_name("profile_cancel_fixture.py"))
    (output/"verified.json").write_text(json.dumps(result,indent=2)+"\n")
    return result
