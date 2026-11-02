"""Replay two actual Flutter device scenes; this never supplies full/visual/release approval."""
import hashlib
import ipaddress
import json
from pathlib import Path


LIFECYCLE_METHOD = "listenerRebindFailureRecoveryAndNotificationLifecycle"
OWNER_METHOD = "independentApplicationTcpUdpNativeAttribution"
LIFECYCLE = ("listener-baseline", "listener-rebound", "listener-bind-failed", "listener-retry",
             "listener-stopped", "proxy-notification", "proxy-stop-hidden", "proxy-stop-restored",
             "proxy-notification-stopped", "proxy-notification-still-stopped")
OWNER = ("owner-tcp-udp", "owner-stopped")
PACKAGE = "dev.aerove.fixture.socketowner"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def endpoint(value):
    host, port = value.rsplit(":", 1)
    return str(ipaddress.ip_address(host.strip("[]"))), int(port)


def core_epoch(value):
    require(type(value) is str and value.isascii() and value.isdecimal() and
            0 < int(value) <= 18446744073709551615 and str(int(value)) == value,
            "Native network epoch must be a canonical nonzero u64 string")
    return int(value)


def validate_owner(row):
    owner = row["owner"]
    uid = owner["fixtureUid"]
    require(type(uid) is int and uid > 0 and uid not in (2000, row["appUid"]), "Not an ordinary independent application UID")
    require(owner["fixturePackage"] == PACKAGE and owner["fixturePid"] != row["pid"] and
            owner["fixturePid"] > 0 and owner["fixtureClosed"] is True and owner["retained"] is True,
            "Independent fixture lease/identity/confirmed closure missing")
    rows = json.loads(owner["connectionsJson"])["connections"]
    for protocol in ("tcp", "udp"):
        source, target = endpoint(owner[protocol]["source"]), endpoint(owner[protocol]["target"])
        require(target[0] == "10.0.2.2" and source[1] > 0 and target[1] > 0, "Unexpected real fixture tuple")
        actual = [entry for entry in rows if
                  str(entry["metadata"].get("network", "")).lower() == protocol and
                  str(entry["metadata"].get("type", "")).lower() == "tun" and
                  (entry["metadata"].get("sourceIP"), int(entry["metadata"].get("sourcePort", 0))) == source and
                  (entry["metadata"].get("destinationIP"), int(entry["metadata"].get("destinationPort", 0))) == target]
        require(len(actual) == 1 and actual[0]["metadata"].get("uid") == uid and
                actual[0]["metadata"].get("process") == PACKAGE,
                "Actual Native TCP/UDP tuple was not attributed to the ordinary fixture App")
    return owner


def validate_rows(method, records):
    expected = LIFECYCLE if method == LIFECYCLE_METHOD else OWNER if method == OWNER_METHOD else ()
    require(tuple(row["stage"] for row in records) == expected and bool(expected), "Missing/extra/out-of-order runtime device stages")
    indexed = {row["stage"]: row for row in records}
    for row in records:
        stopped = row["stage"] in ("listener-bind-failed", "listener-stopped", "proxy-notification-stopped",
                                   "proxy-notification-still-stopped", "owner-stopped")
        if stopped:
            require(row["phase"] in ("Stopped", "Failed") and not row["vpnHandles"] and not row["listening"],
                    "Stopped device scene retained a real listener or VPN")
        else:
            network = json.loads(row["networkReadbackJson"])
            require(row["phase"] == "Running" and row["listening"] is True and row["suspended"] is False and
                    type(row["serviceOwner"]) is int and row["serviceOwner"] > 0 and row["sessionGeneration"] > 0 and
                    str(network["coreEpoch"]) == str(row["coreEpoch"]) and
                    network["network"]["mixed-port"] == row["requestedPort"] and
                    row["requestedPort"] in network["activePorts"], "Runtime scene lacks actual listener/session readback")
            echo = row["proxyEcho"]
            from uuid import UUID
            require(str(UUID(echo["token"])) == echo["token"] and echo["port"] == row["requestedPort"] and
                    echo["response"].splitlines()[0].split()[1] == "200" and
                    "AEROVE_LITE_FIXTURE:" + echo["token"] in echo["response"].split("\r\n\r\n", 1)[1],
                    "Configured listener did not serve a real nonce-bound proxy response")
        if row["stage"] in ("listener-rebound", "listener-bind-failed", "listener-stopped", "proxy-notification-stopped",
                            "proxy-notification-still-stopped", "owner-stopped"):
            require("releasedPort" in row and row["releasedPortBindSucceeded"] is True,
                    "Required old-listener actual release probe is absent")
        if "releasedPort" in row:
            require(row["releasedPortBindSucceeded"] is True, "Old owned listener port was not actually released")
        proxy = row["stage"] in ("proxy-notification", "proxy-stop-hidden", "proxy-stop-restored")
        notifications = row["notifications"]
        if proxy:
            require(not row["vpnHandles"] and len(notifications) == 1 and notifications[0]["channel"] == "aerove-lite-vpn" and
                    notifications[0]["actions"] == ([] if row["stage"] == "proxy-stop-hidden" else ["Stop"]) and
                    notifications[0]["flags"] & 2 and notifications[0]["flags"] & 8 and
                    notifications[0]["channelImportance"] in (1, 2) and notifications[0]["title"] == row["profileName"],
                    "Real proxy notification/action lifecycle differs")
        else:
            require(not notifications, "Normal VPN or stopped scene has an extra application notification")
            if not stopped:
                require(len(row["vpnHandles"]) == 1, "Actual TUN network is missing from a live VPN scene")
    if method == LIFECYCLE_METHOD:
        baseline, rebound = indexed["listener-baseline"], indexed["listener-rebound"]
        require(all(baseline[key] == rebound[key] for key in ("serviceOwner", "sessionGeneration", "vpnHandles", "pid")) and
                baseline["requestedPort"] != rebound["requestedPort"] and
                rebound["releasedPort"] == baseline["requestedPort"], "Hot rebind replaced the owner/session/Core or retained the old port")
        previous_request = baseline.get("networkRequestId")
        # An accepted network write advances the revision; actual GET/nonce traffic and
        # unchanged platform ownership establish the same runtime without a duplicate POST ledger.
        require((previous_request is None or type(previous_request) is int and previous_request >= 0) and
                type(rebound.get("networkRequestId")) is int and
                rebound["networkRequestId"] > (0 if previous_request is None else previous_request) and
                core_epoch(json.loads(rebound["networkReadbackJson"])["coreEpoch"]) == core_epoch(rebound["coreEpoch"]) and
                core_epoch(json.loads(baseline["networkReadbackJson"])["coreEpoch"]) == core_epoch(baseline["coreEpoch"]) and
                core_epoch(rebound["coreEpoch"]) > core_epoch(baseline["coreEpoch"]),
                "Hot rebind actual request/readback does not prove the same-session target and epoch advance")
        failed = indexed["listener-bind-failed"]
        require(failed["occupiedPortRejected"] is True and bool(failed.get("listenerError") or failed.get("error")) and
                failed["releasedPort"] == rebound["requestedPort"], "Occupied-port failure did not release the failed session")
        # A failed session and a busy probe do not identify the production apply failure.
        # Old collectors lack this original diagnostic and must remain unaccepted.
        failure = failed.get("networkApplyFailure")
        require(type(failure) is dict, "Busy-port coverage lacks the original network apply diagnostic")
        from uuid import UUID
        require(failure.get("operation") == "aerove/network" and failure.get("method") == "POST" and
                type(failure.get("statusCode")) is int and failure["statusCode"] == 503 and
                type(failure.get("requestId")) is str and str(UUID(failure["requestId"])) == failure["requestId"] and
                type(rebound.get("networkRequestId")) is int and
                type(failure.get("networkRequestId")) is int and
                failure["networkRequestId"] > rebound["networkRequestId"] >= 0 and
                type(failure.get("mixedPort")) is int and failure["mixedPort"] == failed["occupiedPort"] and
                type(failure.get("expectedCoreEpoch")) is str and failure["expectedCoreEpoch"] == rebound["coreEpoch"] and
                type(failure.get("serviceOwner")) is int and type(failure.get("sessionGeneration")) is int and
                failure.get("serviceOwner") == rebound["serviceOwner"] and
                failure.get("sessionGeneration") == rebound["sessionGeneration"] and
                type(failure.get("responseBody")) is str and
                json.loads(failure["responseBody"]) == {"error": "userListenerBindFailed"} and
                failed.get("error") == "Live update failed: Core API aerove returned HTTP 503",
                "Busy-port failure is not the original Native bind rejection for this request/port/session")
        require(indexed["listener-retry"]["requestedPort"] == rebound["requestedPort"], "Retry did not recover the requested real listener")
        require(any(indexed["listener-retry"][key] != rebound[key] for key in ("serviceOwner", "sessionGeneration")),
                "Bind failure/retry reused the released running session")
        proxy = [indexed[stage] for stage in ("proxy-notification", "proxy-stop-hidden", "proxy-stop-restored")]
        require(all(all(row[key] == proxy[0][key] for key in ("serviceOwner", "sessionGeneration", "coreEpoch")) for row in proxy),
                "Notification preference publication restarted the proxy session")
    else:
        require(bool(indexed["owner-tcp-udp"]["vpnHandles"]), "Ordinary fixture did not run under real TUN")
        validate_owner(indexed["owner-tcp-udp"])
    return records


def sequence(directory, method, info, token):
    from android_acceptance_verify import load
    from uuid import UUID
    directory = Path(directory)
    stages = LIFECYCLE if method == LIFECYCLE_METHOD else OWNER
    require(sorted(path.stem for path in (directory / "checkpoints").glob("*.json")) == sorted(stages), "Runtime device checkpoint selection differs")
    records = []
    for stage in stages:
        request_path = directory / "checkpoints" / (stage + ".json")
        ack_path = directory / "native-acks" / (stage + ".json")
        request, ack = load(request_path), load(ack_path)
        for key, value in dict(schema=1, qaToken=token, stage=stage, source=info["source"], tree=info["tree"], apkSha256=info["apk_sha256"]).items():
            require(request.get(key) == value and ack.get(key) == value, "Runtime device source/token/APK checkpoint mismatch")
        nonce = request["nonce"]
        require(type(request["pid"]) is int and request["pid"] > 0 and str(UUID(nonce)) == nonce and ack["nonce"] == nonce and ack["pid"] == request["pid"] and
                ack["requestSha256"] == sha(request_path) and ack["status"] == "attested", "Stale/failed runtime device ACK")
        require(ack["evidencePath"] == "native-state/" + nonce + ".json", "Unsafe runtime device evidence path")
        evidence_path = directory / ack["evidencePath"]
        require(evidence_path.resolve() == evidence_path.absolute() and sha(evidence_path) == ack["evidenceSha256"], "Changed runtime device evidence")
        evidence = load(evidence_path)
        for key in ("schema", "qaToken", "stage", "nonce", "source", "tree", "apkSha256", "pid"):
            require(evidence.get(key) == request.get(key), "Runtime device evidence identity differs")
        require(evidence["requestSha256"] == sha(request_path) and evidence["status"] == "attested" and
                evidence["producer"] == "AcceptanceCheckpointRunner" and evidence["readOnly"] is True,
                "Runtime device evidence is not the current observer output")
        row = evidence["runtimeDevice"]
        require(row["pid"] == request["pid"] and row["stage"] == stage, "Runtime device graph belongs to another process/stage")
        if not stage.endswith("stopped") and stage != "listener-bind-failed":
            row["requestedPort"] = request["runtimeDevice"]["port"]
        records.append(row)
    return validate_rows(method, records)


def verify_host_owner(directory, records, info):
    from android_acceptance_verify import load, CONTRACT
    host = load(Path(directory) / "ordinary-owner-host.json")
    owner = records[0]["owner"]
    require(host["installedPackage"] == PACKAGE and host["apkSha256"] == host["build"]["apkSha256"] and
            host["installedApkSha256"] == host["apkSha256"] == owner["fixtureApkSha256"] and
            owner["fixtureTestOnly"] is True and host["echoJoined"] is True and
            host["build"]["files"] == load(CONTRACT)["ownerFixtureSources"] and
            host["source"] == info["source"] and host["tree"] == info["tree"] and
            host["workflow"] == info["workflow"] and host["runAttempt"] == str(info["run_attempt"]),
            "Ordinary fixture original APK/installation/echo cleanup differs")
    for protocol in ("tcp", "udp"):
        require(any(row["protocol"] == protocol and row["payload"] == owner["token"] for row in host["echo"]),
                "Actual host echo did not confirm the ordinary App TCP/UDP nonce")
