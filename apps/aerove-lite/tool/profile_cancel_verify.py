"""Independent exact seven-stage client cancellation evidence consumer; no Core/VPN substitute."""
import hashlib
import json
from pathlib import Path
from uuid import UUID
from urllib.parse import urlsplit
from profile_cancel_prerequisite import validate, sha, require

STAGES=("profile-cancel-baseline","profile-single-pending","profile-single-cancelled","profile-single-retried",
        "profile-batch-pending","profile-batch-cancelled","profile-batch-retried")
COUNTS=((1,1),(2,1),(2,1),(3,1),(4,2),(4,2),(5,3))
MUTABLE={"last_update_date","last_update_date_millis","subscription_info"}

def load(path):return json.loads(Path(path).read_text(encoding="utf-8"))
def canonical(row):return json.dumps(row,sort_keys=True,separators=(",",":"),ensure_ascii=False).encode()
def digest(row):return hashlib.sha256(canonical(row)).hexdigest()
def file(root,name):
    relative=Path(name);path=Path(root)/relative
    require(not relative.is_absolute() and not relative.drive and ".." not in relative.parts and "\\" not in name and path.resolve()==path.absolute() and path.is_file(),"Unsafe/missing profile-cancel raw evidence")
    return path

def prerequisite(directory, repository=None):
    root=Path(directory)
    actual=validate(repository,root/"evidence",root/"implementation.json",root/"green.json")
    recorded=load(root/"verified.json")
    require(all(recorded.get(k)==v for k,v in actual.items()),"Staged prerequisite verification changed")
    require(recorded.get("serverSourceSha256")==sha(Path(__file__).with_name("profile_cancel_fixture.py")),"Controlled server implementation differs from sealed prerequisite")
    return recorded

def checkpoint(root,stage,info,token,proof):
    root=Path(root);rp=file(root,"checkpoints/"+stage+".json");request=load(rp)
    ack=load(file(root,"native-acks/"+stage+".json"))
    identity=dict(schema=1,qaToken=token,stage=stage,source=info["source"],tree=info["tree"],apkSha256=info["apk_sha256"])
    nonce=request.get("nonce")
    require(isinstance(nonce,str) and str(UUID(nonce))==nonce,"Invalid cancellation checkpoint nonce")
    common=dict(identity,nonce=nonce,pid=request.get("pid"),requestSha256=sha(rp),status="attested")
    require(type(common["pid"]) is int and common["pid"]>0 and all(request.get(k)==v for k,v in identity.items()) and
            request.get("checkpointKind")=="profile-update-cancel" and all(ack.get(k)==v for k,v in common.items()),"Cancellation request/ACK identity differs")
    require(ack.get("evidencePath")=="native-state/"+nonce+".json","Cancellation ACK evidence path differs")
    ep=file(root,ack["evidencePath"]);native=load(ep)
    require(sha(ep)==ack.get("evidenceSha256") and all(native.get(k)==v for k,v in common.items()) and
            native.get("producer")=="AcceptanceCheckpointRunner" and native.get("readOnly") is True and
            native.get("checkpointKind")=="profile-update-cancel" and native.get("actualVpnStopped") is True and
            native.get("actualVpnNetworkHandles")==[],"Cancellation evidence is not actual stopped read-only target readback")
    require(type(native.get("attestedElapsedRealtimeMs")) is int and native["attestedElapsedRealtimeMs"]>=request["elapsedRealtimeMs"],"Stale cancellation attestation")
    payload=request["profileCancel"];actual=native["profileCancel"]
    for key in ("implementationManifestSha256","greenEvidenceSha256"):
        require(payload.get(key)==actual.get(key)==proof[key],"Cancellation source/Green prerequisite differs")
    require(actual.get("temporaryOutputsAbsent") is True and actual.get("profiles")==payload.get("profiles") and
            len(payload.get("profiles",[]))==2,"Missing actual profiles or joined temporary-file cleanup")
    pair=actual.get("pair");require(isinstance(pair,str) and str(UUID(pair))==pair,"Invalid fixture pair")
    labels=set()
    for key,p in zip(("A","B"),payload["profiles"]):
        require(p.get("key")==key and p.get("url")=="http://10.0.2.2:18080/profile-cancel/"+pair+"/"+key+".yaml" and
                isinstance(p.get("label"),str) and p["label"] not in labels,"Profile URL/label identity differs")
        labels.add(p["label"])
        row=actual["actualProfiles"][key];sql=row["sql"]
        require(sql.get("type")=="url" and sql.get("label")==p["label"] and sql.get("url")==p["url"] and
                type(sql.get("id")) is int and sql["id"]>0 and row.get("file")=="profiles/"+str(sql["id"])+".yaml","Actual Flutter SQL/file path differs")
        require(row.get("sqlSha256")==digest(sql) and row.get("sourceSha256")==digest({k:v for k,v in sql.items() if k not in MUTABLE}),"Actual SQL/source snapshot hash differs")
        require(row.get("fileEvidence")=="native-profile-state/"+nonce+"/"+key+".yaml","Profile file evidence path differs")
        raw=file(root,row["fileEvidence"])
        require(sha(raw)==row.get("fileSha256") and raw.stat().st_size==row.get("fileBytes") and 0<raw.stat().st_size<=4*1024*1024,"Actual profile file readback changed")
    hierarchy=payload.get("uiHierarchy");require(hierarchy==stage+"-hierarchy.json","Wrong cancellation UI hierarchy")
    hp=file(root,hierarchy);require(sha(hp)==payload.get("uiHierarchySha256")==actual.get("uiHierarchySha256"),"Cancellation UI tree changed")
    nodes=load(hp)
    def visible(label):return any(n.get("visible") is True and n.get("package")=="dev.aerove.lite" and label in (n.get("text"),n.get("description")) for n in nodes)
    pending=stage in (STAGES[1],STAGES[4])
    require(all(payload.get(k)==actual.get(k)==visible(label) for k,label in (("toolbarUpdateVisible","Update"),("toolbarCancelVisible","Cancel"))) and
            visible("Cancel")==pending and visible("Update")!=pending,"UI Cancel/Update semantics do not match actual hierarchy")
    if stage in (STAGES[1],STAGES[2],STAGES[4],STAGES[5]):
        started=payload.get("updateStartedElapsedRealtimeMs");deadline=payload.get("cancelDeadlineElapsedRealtimeMs")
        require(type(started) is int and type(deadline) is int and deadline-started==18000 and
                started<=request["elapsedRealtimeMs"]<=native["attestedElapsedRealtimeMs"]<deadline and
                native["attestedElapsedRealtimeMs"]-request["elapsedRealtimeMs"]<6000,"Cancel/ACK exceeded real 18s/6s pre-timeout boundary")
    state=actual["server"];index=STAGES.index(stage)
    require(state.get("schema")==1 and state.get("pair")==pair and state.get("stage")==stage and state.get("ready") is True and
            state.get("sourceSha256")==proof["serverSourceSha256"] and set(state.get("requests",{}))=={"A","B"} and
            tuple(len(state["requests"][k]) for k in ("A","B"))==COUNTS[index],"Controlled same-URL request sequence differs")
    for key in ("A","B"):
        for ordinal,event in enumerate(state["requests"][key],1):
            require(event.get("key")==key and event.get("ordinal")==ordinal and event.get("headersSent") is True and event.get("slow")== (ordinal==2) and
                    event.get("serverInitiatedClose") is False and not event.get("holdDeadlineExceeded") and not event.get("serverError"),"Server release/timeout/error is not UI cancellation")
            cancelled=(index>=2 if key=="A" else index>=5)
            if ordinal==2:
                require(event.get("bytesSent")==1 and event.get("completed") is False and event.get("firstByteMonotonicNs",0)>=event.get("startedMonotonicNs",0)>0,"Slow request first-byte barrier missing")
                if cancelled:
                    require(event.get("peerClose") in ("FIN","RST") and event.get("handlerJoined") is True and
                            0<=event["peerClosedMonotonicNs"]-event["startedMonotonicNs"]<18_000_000_000,"Real peer abort and handler join before timeout missing")
                else:require(event.get("peerClose") is None and event.get("handlerJoined") is False,"Pending request already ended before UI Cancel")
            else:require(event.get("completed") is True and event.get("handlerJoined") is True and event.get("bytesSent")==event.get("bodyBytes")>1,"Fast request did not actually complete/join")
    return native

def sequence(directory,info,token,proof):
    root=Path(directory)
    require(sorted(p.stem for p in (root/"checkpoints").glob("*.json"))==sorted(STAGES),"All seven exact cancellation stages are required")
    natives=[checkpoint(root,s,info,token,proof) for s in STAGES]
    require(len({n["pid"] for n in natives})==1 and len({n["profileCancel"]["pair"] for n in natives})==1,"Cancellation lost same-process/profile authority")
    snaps=[n["profileCancel"]["actualProfiles"] for n in natives];base=snaps[0]
    def same(a,b):require(a["sqlSha256"]==b["sqlSha256"] and a["fileSha256"]==b["fileSha256"],"Cancelled update changed old committed SQL/file")
    def complete(index,key,ordinal):
        actual=snaps[index][key];event=natives[index]["profileCancel"]["server"]["requests"][key][ordinal-1]
        require(actual["fileSha256"]==event["bodySha256"] and actual["sourceSha256"]==base[key]["sourceSha256"],"Successful update body/source differs")
        usage=actual["sql"].get("subscription_info");usage=json.loads(usage) if isinstance(usage,str) else usage
        require(usage==event["userinfo"] and actual["sql"].get("last_update_date_millis",0)>base[key]["sql"].get("last_update_date_millis",0),"Successful update SQL usage/date was not committed")
    for key in ("A","B"):
        require(base[key]["fileSha256"]==natives[0]["profileCancel"]["server"]["requests"][key][0]["bodySha256"],"Baseline import file differs")
        for index in (1,2):same(snaps[index][key],base[key])
    complete(3,"A",3);same(snaps[3]["B"],base["B"])
    complete(4,"A",4);same(snaps[4]["B"],base["B"])
    same(snaps[5]["A"],snaps[4]["A"]);same(snaps[5]["B"],base["B"])
    complete(6,"A",5);complete(6,"B",3)
    return natives
