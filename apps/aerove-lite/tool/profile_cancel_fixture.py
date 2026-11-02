"""Controlled same-URL ordinal profile fixture; cancellation is real peer EOF/RST, never release/timeout."""
import errno
import hashlib
import json
from pathlib import Path
import re
import select
import socket
import threading
import time
from urllib.parse import urlsplit, parse_qs
from uuid import UUID

STAGES=("profile-cancel-baseline","profile-single-pending","profile-single-cancelled","profile-single-retried",
        "profile-batch-pending","profile-batch-cancelled","profile-batch-retried")
EXPECTED={STAGES[0]:(1,1),STAGES[1]:(2,1),STAGES[2]:(2,1),STAGES[3]:(3,1),STAGES[4]:(4,2),STAGES[5]:(4,2),STAGES[6]:(5,3)}


def body(pair,key,ordinal):
    return (f"# AEROVE_PROFILE_CANCEL:{pair}:{key}:{ordinal}\nmixed-port: 18081\nmode: direct\nrules: ['MATCH,DIRECT']\n").encode()


def userinfo(ordinal):
    return dict(upload=ordinal*1000,download=ordinal*2000,total=1000000000,expire=2000000000)


class CancelFixture:
    def __init__(self, hold_seconds=18):
        self.condition=threading.Condition()
        self.pairs={}
        self.hold_seconds=hold_seconds
        self.source_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()

    def notify(self, record, **fields):
        with self.condition:
            record.update(fields)
            self.condition.notify_all()

    def serve_profile(self,handler,pair,key):
        with self.condition:
            if pair not in self.pairs:
                if len(self.pairs)>=32:
                    handler.send_error(429);return
                self.pairs[pair]={"A":[],"B":[]}
            history=self.pairs[pair][key]
            ordinal=len(history)+1
            if ordinal>(5 if key=="A" else 3):
                handler.send_error(409);return
            payload=body(pair,key,ordinal)
            record=dict(key=key,ordinal=ordinal,bodySha256=hashlib.sha256(payload).hexdigest(),bodyBytes=len(payload),
                        userinfo=userinfo(ordinal),startedMonotonicNs=time.monotonic_ns(),headersSent=False,bytesSent=0,
                        slow=ordinal==2,completed=False,peerClose=None,serverInitiatedClose=False,
                        thread=threading.current_thread())
            history.append(record);self.condition.notify_all()
        handler.close_connection=True
        handler.send_response(200)
        handler.send_header("Content-Type","application/yaml")
        handler.send_header("Content-Length",str(len(payload)))
        handler.send_header("Connection","close")
        handler.send_header("Subscription-Userinfo","; ".join(f"{k}={v}" for k,v in userinfo(ordinal).items()))
        handler.end_headers()
        self.notify(record,headersSent=True)
        try:
            if ordinal!=2:
                handler.wfile.write(payload);handler.wfile.flush()
                self.notify(record,bytesSent=len(payload),completed=True,completedMonotonicNs=time.monotonic_ns())
                return
            handler.wfile.write(payload[:1]);handler.wfile.flush()
            self.notify(record,bytesSent=1,firstByteMonotonicNs=time.monotonic_ns())
            deadline=time.monotonic()+self.hold_seconds
            while time.monotonic()<deadline:
                ready,_,_=select.select([handler.connection],[],[],min(.1,max(0,deadline-time.monotonic())))
                if not ready:continue
                try:
                    data=handler.connection.recv(1,socket.MSG_PEEK)
                except OSError as error:
                    if error.errno in (errno.ECONNRESET,errno.ECONNABORTED):
                        self.notify(record,peerClose="RST",peerClosedMonotonicNs=time.monotonic_ns());return
                    raise
                if data==b"":
                    self.notify(record,peerClose="FIN",peerClosedMonotonicNs=time.monotonic_ns());return
                self.notify(record,protocolError="Unexpected client data while response body held",serverInitiatedClose=True)
                return
            self.notify(record,holdDeadlineExceeded=True,serverInitiatedClose=True)
        except (BrokenPipeError,ConnectionResetError) as error:
            # A reset while writing the first byte is still observed transport failure, but pending stage requires that byte first.
            self.notify(record,peerClose="RST",peerClosedMonotonicNs=time.monotonic_ns(),writeError=type(error).__name__)
        except BaseException as error:
            self.notify(record,serverInitiatedClose=True,serverError=type(error).__name__+": "+str(error))
            raise
        finally:
            self.notify(record,handlerReturnedMonotonicNs=time.monotonic_ns())

    def ready(self,pair,stage):
        history=self.pairs.get(pair,{"A":[],"B":[]})
        counts=EXPECTED[stage]
        if tuple(len(history[k]) for k in ("A","B"))!=counts:return False
        for key,rows in history.items():
            for row in rows:
                if row["slow"]:
                    cancelled=(stage in (STAGES[2],STAGES[3],STAGES[4],STAGES[5],STAGES[6]) if key=="A" else stage in (STAGES[5],STAGES[6]))
                    if cancelled:
                        if row.get("peerClose") not in ("FIN","RST") or row.get("serverInitiatedClose") or row["bytesSent"]!=1:return False
                    elif row["bytesSent"]!=1 or row.get("peerClose") or row.get("serverInitiatedClose"):return False
                elif not row["completed"]:return False
        return True

    def state(self,pair,stage,wait_ms=0):
        deadline=time.monotonic()+wait_ms/1000
        with self.condition:
            while not self.ready(pair,stage) and time.monotonic()<deadline:
                self.condition.wait(max(0,deadline-time.monotonic()))
            rows=[r for history in self.pairs.get(pair,{"A":[],"B":[]}).values() for r in history]
        # This reader is a different HTTP thread. A joined flag is computed from a real Thread.join, never set by the handler itself.
        for row in rows:
            if row.get("completed") or row.get("peerClose"):
                if row["thread"] is not threading.current_thread():row["thread"].join(timeout=max(0,min(.5,deadline-time.monotonic())))
        with self.condition:
            histories={key:[dict({k:v for k,v in r.items() if k!="thread"},handlerJoined=not r["thread"].is_alive())
                            for r in self.pairs.get(pair,{"A":[],"B":[]})[key]] for key in ("A","B")}
            joined=all(r["handlerJoined"] for values in histories.values() for r in values if r["completed"] or r.get("peerClose"))
            return dict(schema=1,pair=pair,stage=stage,sourceSha256=self.source_sha256,ready=self.ready(pair,stage) and joined,requests=histories)

    def handle(self,handler):
        url=urlsplit(handler.path)
        match=re.fullmatch(r"/profile-cancel/([0-9a-f-]+)/([AB])\.yaml",url.path)
        if match:
            try:
                pair=str(UUID(match[1]));assert pair==match[1] and not url.query
            except (ValueError,AssertionError):handler.send_error(400);return True
            self.serve_profile(handler,pair,match[2]);return True
        match=re.fullmatch(r"/profile-cancel-state/([0-9a-f-]+)",url.path)
        if match:
            try:
                pair=str(UUID(match[1]));query=parse_qs(url.query,strict_parsing=True)
                assert pair==match[1] and set(query)=={"stage","waitMs"} and all(len(v)==1 for v in query.values())
                stage=query["stage"][0];wait=int(query["waitMs"][0]);assert stage in STAGES and 0<=wait<=5000
            except (ValueError,AssertionError):handler.send_error(400);return True
            payload=json.dumps(self.state(pair,stage,wait),sort_keys=True,separators=(",",":")).encode()
            handler.send_response(200);handler.send_header("Content-Type","application/json")
            handler.send_header("Content-Length",str(len(payload)));handler.end_headers();handler.wfile.write(payload);return True
        return False
