"""Protocol parser fixtures and actual loopback transport, not Android acceptance results."""
import copy
import hashlib
import http.client
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
import unittest
from uuid import uuid4
import fixture_server
from profile_cancel_fixture import CancelFixture,STAGES,body,userinfo
import profile_cancel_verify as v
import profile_cancel_prerequisite as prerequisite
import seal_platform_runner as seal

class RealAbortFixtureTest(unittest.TestCase):
    def setUp(self):
        self.fixture=CancelFixture();handler=type("OwnedHandler",(fixture_server.Handler,),dict(cancel_fixture=self.fixture))
        self.server=ThreadingHTTPServer(("127.0.0.1",0),handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.addCleanup(self.server.server_close);self.addCleanup(self.thread.join,3);self.addCleanup(self.server.shutdown)
        self.pair=str(uuid4())
    def get(self,key,slow=False):
        connection=http.client.HTTPConnection("127.0.0.1",self.server.server_port,timeout=3)
        connection.request("GET","/profile-cancel/"+self.pair+"/"+key+".yaml")
        response=connection.getresponse();self.assertEqual(response.status,200)
        if slow:
            self.assertEqual(response.read(1),b"#");return connection,response
        payload=response.read();response.close();connection.close();return payload
    def state(self,stage):return self.fixture.state(self.pair,stage,1000)
    def test_actual_same_url_fast_slow_peer_abort_join_retry_and_batch_sequence(self):
        self.assertEqual(self.get("A"),body(self.pair,"A",1));self.get("B")
        self.assertTrue(self.state(STAGES[0])["ready"])
        a,response=self.get("A",True)
        pending=self.state(STAGES[1]);self.assertTrue(pending["ready"])
        self.assertIsNone(pending["requests"]["A"][1]["peerClose"])
        response.close();a.close()
        stopped=self.state(STAGES[2]);self.assertTrue(stopped["ready"])
        event=stopped["requests"]["A"][1]
        self.assertIn(event["peerClose"],("FIN","RST"));self.assertTrue(event["handlerJoined"])
        self.assertFalse(event["serverInitiatedClose"]);self.assertLess(event["peerClosedMonotonicNs"]-event["startedMonotonicNs"],18_000_000_000)
        self.get("A");self.assertTrue(self.state(STAGES[3])["ready"])
        self.get("A");b,response=self.get("B",True);self.assertTrue(self.state(STAGES[4])["ready"])
        response.close();b.close();self.assertTrue(self.state(STAGES[5])["ready"])
        self.get("A");self.get("B");self.assertTrue(self.state(STAGES[6])["ready"])
    def test_server_timeout_can_never_satisfy_client_cancel(self):
        self.fixture.hold_seconds=.04
        self.get("A");self.get("B");connection,response=self.get("A",True)
        self.addCleanup(connection.close);self.addCleanup(response.close)
        self.assertFalse(self.state(STAGES[2])["ready"])
        event=self.state(STAGES[2])["requests"]["A"][1]
        self.assertTrue(event["serverInitiatedClose"]);self.assertTrue(event["holdDeadlineExceeded"])
        self.assertIsNone(event["peerClose"])

class CancellationEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        for name in ("checkpoints","native-acks","native-state"):(self.root/name).mkdir()
        self.info=dict(source="a"*40,tree="b"*40,apk_sha256="c"*64);self.token="qa-parser-fixture";self.pair=str(uuid4())
        self.proof=dict(implementationManifestSha256="d"*64,greenEvidenceSha256="e"*64,serverSourceSha256="f"*64)
        self.profiles=[dict(key=k,label="Parser fixture "+k,url="http://10.0.2.2:18080/profile-cancel/"+self.pair+"/"+k+".yaml") for k in ("A","B")]
        self.construct()
    def save(self,path,value):Path(path).write_text(json.dumps(value,ensure_ascii=False)+"\n",encoding="utf-8")
    def snapshot(self,key,ordinal,nonce):
        index=0 if key=="A" else 1
        sql=dict(id=index+1,type="url",label=self.profiles[index]["label"],url=self.profiles[index]["url"],selected_map="{}",auto_update=1,
                 last_update_date="parser-fixture-date-"+str(ordinal),last_update_date_millis=ordinal*100,subscription_info=json.dumps(userinfo(ordinal)))
        raw=body(self.pair,key,ordinal);name="native-profile-state/"+nonce+"/"+key+".yaml";p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(raw)
        return dict(sql=sql,sqlSha256=v.digest(sql),sourceSha256=v.digest({k:val for k,val in sql.items() if k not in v.MUTABLE}),file="profiles/"+str(index+1)+".yaml",fileEvidence=name,fileSha256=v.sha(p),fileBytes=len(raw))
    def construct(self):
        ordinals=((1,1),(1,1),(1,1),(3,1),(4,1),(4,1),(5,3))
        for index,stage in enumerate(STAGES):
            nonce=str(uuid4());common=dict(schema=1,qaToken=self.token,stage=stage,nonce=nonce,source=self.info["source"],tree=self.info["tree"],apkSha256=self.info["apk_sha256"],pid=123)
            pending=index in (1,4);hp=self.root/(stage+"-hierarchy.json")
            self.save(hp,[dict(visible=True,package="dev.aerove.lite",text="Cancel" if pending else "Update")])
            payload=dict(profiles=self.profiles,implementationManifestSha256=self.proof["implementationManifestSha256"],greenEvidenceSha256=self.proof["greenEvidenceSha256"],uiHierarchy=hp.name,uiHierarchySha256=v.sha(hp),toolbarUpdateVisible=not pending,toolbarCancelVisible=pending)
            if index in (1,2,4,5):payload.update(updateStartedElapsedRealtimeMs=1000,cancelDeadlineElapsedRealtimeMs=19000)
            rp=self.root/"checkpoints"/(stage+".json");request=dict(common,checkpointKind="profile-update-cancel",profileCancel=payload,elapsedRealtimeMs=1500+index*100)
            self.save(rp,request)
            requests={}
            for key,count in zip(("A","B"),v.COUNTS[index]):
                events=[]
                for ordinal in range(1,count+1):
                    slow=ordinal==2;cancelled=slow and (index>=2 if key=="A" else index>=5)
                    raw=body(self.pair,key,ordinal)
                    event=dict(key=key,ordinal=ordinal,bodySha256=hashlib.sha256(raw).hexdigest(),bodyBytes=len(raw),userinfo=userinfo(ordinal),headersSent=True,bytesSent=1 if slow else len(raw),slow=slow,completed=not slow,peerClose="FIN" if cancelled else None,serverInitiatedClose=False,handlerJoined=not slow or cancelled,startedMonotonicNs=100,firstByteMonotonicNs=101)
                    if cancelled:event["peerClosedMonotonicNs"]=200
                    events.append(event)
                requests[key]=events
            native_payload=dict(payload,pair=self.pair,actualProfiles={k:self.snapshot(k,o,nonce) for k,o in zip(("A","B"),ordinals[index])},server=dict(schema=1,pair=self.pair,stage=stage,sourceSha256=self.proof["serverSourceSha256"],ready=True,requests=requests),temporaryOutputsAbsent=True)
            native=dict(common,requestSha256=v.sha(rp),status="attested",producer="AcceptanceCheckpointRunner",readOnly=True,checkpointKind="profile-update-cancel",actualVpnStopped=True,actualVpnNetworkHandles=[],attestedElapsedRealtimeMs=2000+index*100,profileCancel=native_payload)
            ep=self.root/"native-state"/(nonce+".json");self.save(ep,native)
            self.save(self.root/"native-acks"/(stage+".json"),dict(common,requestSha256=v.sha(rp),status="attested",evidencePath="native-state/"+nonce+".json",evidenceSha256=v.sha(ep)))
    def mutate(self,index,change):
        ap=self.root/"native-acks"/(STAGES[index]+".json");ack=v.load(ap);ep=self.root/ack["evidencePath"];native=v.load(ep);change(native);self.save(ep,native);ack["evidenceSha256"]=v.sha(ep);self.save(ap,ack)
    def test_exact_seven_stages_preserve_cancelled_and_completed_authorities(self):
        result=v.sequence(self.root,self.info,self.token,self.proof)
        self.assertEqual(len(result),7)
    def test_service_timeout_and_fake_join_are_rejected_even_with_matching_hashes(self):
        for field,value in (("serverInitiatedClose",True),("peerClose",None),("handlerJoined",False),("holdDeadlineExceeded",True)):
            self.construct();self.mutate(2,lambda n:n["profileCancel"]["server"]["requests"]["A"][1].update({field:value}))
            with self.assertRaises(ValueError):v.sequence(self.root,self.info,self.token,self.proof)
    def test_wrong_green_identity_missing_stage_and_late_ack_are_rejected(self):
        proof=dict(self.proof,greenEvidenceSha256="0"*64)
        with self.assertRaises(ValueError):v.sequence(self.root,self.info,self.token,proof)
        self.mutate(2,lambda n:n.update(attestedElapsedRealtimeMs=19000))
        with self.assertRaises(ValueError):v.sequence(self.root,self.info,self.token,self.proof)
        (self.root/"checkpoints"/(STAGES[-1]+".json")).unlink()
        with self.assertRaises(ValueError):v.sequence(self.root,self.info,self.token,self.proof)
    def test_cancelled_sql_or_file_cannot_be_changed_and_batch_completed_a_must_survive(self):
        for index,key in ((2,"A"),(5,"A"),(5,"B")):
            self.construct()
            def change(n):
                row=n["profileCancel"]["actualProfiles"][key];row["sql"]["last_update_date_millis"]+=1;row["sqlSha256"]=v.digest(row["sql"])
            self.mutate(index,change)
            with self.assertRaisesRegex(ValueError,"Cancelled update"):v.sequence(self.root,self.info,self.token,self.proof)
    def test_file_readback_and_actual_ui_hierarchy_cannot_be_replaced_by_claims(self):
        path=next((self.root/"native-profile-state").rglob("A.yaml"));path.write_bytes(b"changed")
        with self.assertRaises(ValueError):v.sequence(self.root,self.info,self.token,self.proof)
        self.construct();self.mutate(1,lambda n:n["profileCancel"].update(toolbarCancelVisible=False))
        with self.assertRaises(ValueError):v.sequence(self.root,self.info,self.token,self.proof)

class R8AbiTest(unittest.TestCase):
    def test_positive_mapping_chain_and_negative_missing_remap_or_wildcard_keep(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);rules=root/"rules.pro";rules.write_text("-keep,allowobfuscation class actual.Target { public void invoked(); }\n")
            report=root/"inputs.json";report.write_text(json.dumps(dict(schema=3,generator="R8 TraceReferences",allowObfuscation=True,rulesSha256=seal.sha(rules),referenceDiagnostics=dict(traceCompleted=True,fatalCount=0),inputClasses=dict(source=dict(classes=10),target=dict(classes=20)))))
            mapping=root/"test-mapping.txt";mapping.write_text("test.Source -> test.Source:\n");config=root/"configuration.txt";config.write_text("-keep class * { *; }\n")
            names=["dev.aerove.lite.core.MeowCapabilitiesCodec","dev.aerove.lite.flutter.RuntimeAppliedIdentity","dev.aerove.lite.flutter.NativeLiteVerifiedSource","dev.aerove.lite.flutter.MeowQueryGraph"]
            target="".join(k+" -> a.C"+str(i)+":\n" for i,k in enumerate(names));refs="".join("C r 0 1 20 a.C"+str(i)+"\n" for i in range(4))
            tasks=[":app:compileReleaseKotlin",":platform-qa:compileReleaseKotlin",":app:generateReleasePlatformTestAppKeepRules",":app:minifyReleaseWithR8",":platform-qa:minifyReleaseWithR8"]
            graph="".join(t+" SKIPPED\n" for t in tasks)
            self.assertTrue(seal.seal_abi(graph,target,refs,rules,report,mapping,config)["testR8Enabled"])
            for changed_graph,changed_refs in ((graph.replace(tasks[-1],":wrong"),refs),(graph,refs.replace("a.C0","dev.aerove.lite.core.MeowCapabilitiesCodec"))):
                with self.assertRaises(ValueError):seal.seal_abi(changed_graph,target,changed_refs,rules,report,mapping,config)
            rules.write_text("-keep class dev.aerove.lite.** { *; }\n")
            data=json.loads(report.read_text());data["rulesSha256"]=seal.sha(rules);report.write_text(json.dumps(data))
            with self.assertRaises(ValueError):seal.seal_abi(graph,target,refs,rules,report,mapping,config)

class PrerequisiteParserTest(unittest.TestCase):
    def test_exact_production_green_scopes_are_bound_without_relabelling_failed_aggregate(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);repo=root/"repository";evidence=root/"evidence";evidence.mkdir()
            files=[]
            for name in prerequisite.PRODUCTION:
                path=repo/"packages/aerove_client/lib"/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(b"// parser fixture source\n")
                files.append(dict(path=path.relative_to(repo).as_posix(),afterSHA256=prerequisite.sha(path)))
            for relative in prerequisite.TEST_SOURCES:
                path=repo/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(b"// parser fixture test source\n")
                files.append(dict(path=relative,afterSHA256=prerequisite.sha(path)))
            implementation=root/"implementation.json";implementation.write_text(json.dumps(dict(files=files)))
            proof=dict(schema=1,kind="profile-cancel-production-green",passed=True,implementationManifestSha256=prerequisite.sha(implementation),productionFiles={row["path"]:row["afterSHA256"] for row in files if row["path"].startswith("packages/aerove_client/lib/")},runs=[],scopes=[],checks=[])
            def run(name,command,text,code=0,fmt="command"):
                result=evidence/(name+"-result.json");result.write_text(json.dumps(dict(command=command,exit=code)))
                stdout=evidence/(name+"-stdout.log");stdout.write_text(text)
                stderr=evidence/(name+"-stderr.log");stderr.write_text("")
                row=dict(id=name,format=fmt)
                for field,path in (("result",result),("stdout",stdout),("stderr",stderr)):
                    row[field]=path.name;row[field+"Sha256"]=prerequisite.sha(path)
                proof["runs"].append(row)
            for name,(suite,count) in prerequisite.SCOPES.items():
                text="".join("00:00 +"+str(i)+": "+suite+": case "+str(i)+"\n" for i in range(count))+"00:01 +"+str(count)+": All tests passed!\n"
                run(name,["flutter","test",suite],text,fmt="flutter-expanded")
                proof["scopes"].append(dict(name=name,run=name,suiteSuffix=suite,expectedTests=count))
            for name,command in (("analyze",["flutter","analyze"]),("generation",["dart","run","build_runner","build"])):
                run(name,command,"actual parser fixture command output\n");proof["checks"].append(dict(name=name,run=name))
            green=root/"green.json"
            def save():green.write_text(json.dumps(proof))
            save();verified=prerequisite.validate(repo,evidence,implementation,green)
            self.assertFalse(verified["androidExecuted"])
            staged=root/"staged";prerequisite.stage(repo,evidence,implementation,green,staged)
            self.assertEqual(v.prerequisite(staged)["greenEvidenceSha256"],prerequisite.sha(green))
            proof["scopes"][0]["expectedTests"]=1;save()
            with self.assertRaisesRegex(ValueError,"suite/count"):prerequisite.validate(repo,evidence,implementation,green)
            proof["scopes"][0]["expectedTests"]=13;save()
            source=repo/files[0]["path"];source.write_bytes(b"changed actual App")
            with self.assertRaisesRegex(ValueError,"Actual App"):prerequisite.validate(repo,evidence,implementation,green)

    def test_real_expanded_failure_stays_failure_and_terminal_is_required(self):
        text="00:00 +0: test/a.dart: old fails\n00:01 +0 -1: test/a.dart: old fails [E]\n00:01 +0 -1: test/b.dart: independent passes\n00:02 +1 -1: Some tests failed.\n"
        records=prerequisite.expanded(text)
        self.assertEqual([r["status"] for r in records],["failed","passed"])
        with self.assertRaises(ValueError):prerequisite.expanded(text.rsplit("00:02",1)[0])
if __name__=="__main__":unittest.main()
