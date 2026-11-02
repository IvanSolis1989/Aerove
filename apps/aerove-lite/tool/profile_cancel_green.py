"""Produce fresh same-source real Flutter scope logs; no APK build or historical receipt replay."""
import argparse
import json
import os
import signal
from pathlib import Path
import subprocess
import time
from profile_cancel_prerequisite import PRODUCTION,SCOPES,TEST_SOURCES,sha,validate,require


def produce(repository,output):
    repository=Path(repository).resolve();output=Path(output).resolve();output.mkdir(parents=True,exist_ok=False)
    files={"packages/aerove_client/lib/"+p:sha(repository/("packages/aerove_client/lib/"+p)) for p in PRODUCTION}
    test_files={p:sha(repository/p) for p in TEST_SOURCES}
    identity=dict(source=os.environ["GITHUB_SHA"],run=os.environ["GITHUB_RUN_ID"],attempt=os.environ["GITHUB_RUN_ATTEMPT"])
    proof=dict(schema=1,kind="profile-cancel-production-green",passed=False,productionFiles=files,runs=[],scopes=[],checks=[],identity=identity)
    commands=[("generation",["dart","run","build_runner","build","--delete-conflicting-outputs"],repository/"packages/aerove_client","command"),
              ("analyze",["flutter","analyze","--no-pub","--no-fatal-infos"],repository/"packages/aerove_client","command")]
    commands += [(name,["flutter","test","--no-pub","--reporter","expanded","--concurrency","1",suite],repository/"apps/aerove","flutter-expanded") for name,(suite,count) in SCOPES.items()]
    for name,command,cwd,fmt in commands:
        folder=output/"evidence"/name;folder.mkdir(parents=True)
        started=time.monotonic();failure=None
        (folder/"command.json").write_text(json.dumps(dict(command=command,cwd=str(cwd)),indent=2)+"\n")
        with (folder/"stdout.log").open("wb") as stdout,(folder/"stderr.log").open("wb") as stderr:
            child=subprocess.Popen(command,cwd=cwd,stdin=subprocess.DEVNULL,stdout=stdout,stderr=stderr,start_new_session=True)
            try:code=child.wait(timeout=600)
            except BaseException as error:
                failure=error
                # Only the process group created by this command, never another build/service owner.
                try:os.killpg(child.pid,signal.SIGTERM)
                except ProcessLookupError:pass
                try:child.wait(timeout=10)
                except subprocess.TimeoutExpired:os.killpg(child.pid,signal.SIGKILL);child.wait(timeout=10)
                code=child.returncode
        result=dict(command=command,cwd=str(cwd),pid=child.pid,processGroup=child.pid,exit=code,elapsedSeconds=time.monotonic()-started)
        if failure is not None:result["failure"]=type(failure).__name__+": "+str(failure)
        (folder/"result.json").write_text(json.dumps(result,indent=2)+"\n")
        run=dict(id=name,format=fmt)
        for field,filename in (("result","result.json"),("stdout","stdout.log"),("stderr","stderr.log")):
            run[field]=name+"/"+filename;run[field+"Sha256"]=sha(folder/filename)
        proof["runs"].append(run)
        if name in SCOPES:
            suffix,count=SCOPES[name];proof["scopes"].append(dict(name=name,run=name,suiteSuffix=suffix,expectedTests=count))
        else:proof["checks"].append(dict(name=name,run=name))
        (output/"green.json").write_text(json.dumps(proof,indent=2)+"\n")
        if failure is not None:raise failure
        require(code==0,"Actual profile-cancel "+name+" command failed; original output retained")
    require(files=={relative:sha(repository/relative) for relative in files},"Production changed during prerequisite checks")
    require(test_files=={p:sha(repository/p) for p in test_files},"Test source changed during prerequisite checks")
    manifest=dict(schema=1,kind="profile-cancel-current-ci-implementation",identity=identity,files=[dict(path=key,afterSHA256=value) for key,value in sorted(dict(files,**test_files).items())])
    (output/"implementation.json").write_text(json.dumps(manifest,indent=2)+"\n")
    proof.update(passed=True,implementationManifestSha256=sha(output/"implementation.json"))
    (output/"green.json").write_text(json.dumps(proof,indent=2)+"\n")
    validate(repository,output/"evidence",output/"implementation.json",output/"green.json")


def interrupted(_signal,_frame):raise InterruptedError("Owned prerequisite producer interrupted")

def main():
    signal.signal(signal.SIGTERM,interrupted)
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--repository",type=Path,required=True);p.add_argument("--output",type=Path,required=True);a=p.parse_args();produce(a.repository,a.output)
if __name__=="__main__":main()
