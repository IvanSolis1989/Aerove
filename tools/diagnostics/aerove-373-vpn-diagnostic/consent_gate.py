"""Strict continuation for a diagnostic: not a visual or release acceptance result."""
import hashlib
import json
import re
from pathlib import Path

PACKAGE = "dev.aerove.lite"
CLASS = PACKAGE + ".FlutterProductionUiTest"
LAUNCHER = PACKAGE + "/" + PACKAGE + ".FlutterMainActivity"
THEME = "consentNavigationTheme12"
RESTART = "persistedConsentAfterProcessRestart"
STATEMENT = (
    "The Software itself does not provide any proxy server, node, subscription, or network access service, "
    "and has no partnership, agency, or guarantee relationship with any provider of such services."
)
FAILURE = "java.lang.IllegalArgumentException: SurfaceFlinger screenshot failed"
THEME_STAGE = re.compile(r"theme/(Plain solid|Soft frost|Clear shimmer)/(Classic monochrome|Living Aurora)/(Light|Dark)")
DOCK = ("Dashboard", "Profiles", "Tools", "Dashboard")


def need(condition, message):
    if not condition:
        raise ValueError(message)


def bounded(path, limit):
    path = Path(path)
    need(path.is_file() and not path.is_symlink() and path.resolve() == path.absolute()
         and 0 < path.stat().st_size <= limit, "Invalid diagnostic evidence file")
    return path.read_bytes()


def bound_receipt(directory, token, info):
    need(isinstance(token, str) and bool(re.fullmatch("qa-[0-9a-f]{32}", token)), "Invalid host token")
    raw = bounded(directory / "receipt.json", 8 * 1024 * 1024)
    result = json.loads(raw)
    expected = dict(schema=1, producer=CLASS, launcher=LAUNCHER, qaToken=token,
                    apkSha256=info["apk_sha256"], source=info["source"], tree=info["tree"],
                    workflow=info["workflow"], runAttempt=str(info["run_attempt"]))
    need(all(result.get(k) == v for k, v in expected.items()), "Wrong receipt identity")
    need(type(result.get("pid")) is int and result["pid"] > 1 and result.get("sdk") == 33
         and result.get("locales", "").split(",")[0] == "en-US", "Wrong process/API/locale")
    return result, hashlib.sha256(raw).hexdigest()


def exact_failed_selector(logfile):
    log = bounded(logfile, 2 * 1024 * 1024).decode("utf-8")
    need(not re.search(r"INSTRUMENTATION_(FAILED|ABORTED)|Process crashed|shortMsg=", log, re.I),
         "Instrumentation aborted or target process crashed")
    need(re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)[ \t]*$", log, re.M) == ["-1"],
         "Instrumentation did not terminate normally")
    blocks = re.split(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)[ \t]*$", log, flags=re.M)
    need(len(blocks) == 5 and blocks[1::2] == ["1", "-2"], "Not one failed selected test")
    for block in blocks[0:4:2]:
        for field, value in dict(id="AndroidJUnitRunner", current="1", numtests="1", **{"class": CLASS, "test": THEME}).items():
            need(re.findall(r"^INSTRUMENTATION_STATUS: " + field + r"=(.*)$", block, re.M) == [value],
                 "Unexpected instrumentation identity")
    need(re.findall(r"^INSTRUMENTATION_STATUS: stack=(.*)$", blocks[2], re.M) == [FAILURE],
         "Failure is not an exact display capture failure")
    need(bool(re.search(r"^Tests run: 1,\s+Failures: 1\s*$", blocks[-1], re.M))
         and not re.search(r"^OK \(", blocks[-1], re.M), "No failed JUnit terminal")


def consent_actions(row):
    steps = [
        ("first-launch", "click", "Agree"),
        ("actual-restatement", "set-text", "full production statement"),
        ("actual-restatement", "click", "Confirm"),
        ("actual-restatement", "click", "Got it"),
        *(("bottom-navigation", "verified-selected-destination", d) for d in DOCK),
    ]
    actions = row.get("actions")
    need(isinstance(actions, list) and 0 < len(actions) <= 4000, "Missing or unbounded actions")
    index, last_time = 0, -1
    for action in actions:
        if index == len(steps):
            break
        if tuple(action.get(k) for k in ("stage", "kind", "detail")) != steps[index]:
            continue
        tick = action.get("elapsedRealtimeMs")
        node = action.get("node", {})
        need(type(tick) is int and tick >= last_time and node.get("package") == PACKAGE
             and node.get("visible") is True, "Consent action not attached to the real ordered app UI")
        last_time, index = tick, index + 1
    need(index == len(steps) and type(row.get("finishedElapsedRealtimeMs")) is int
         and row["finishedElapsedRealtimeMs"] >= last_time, "No evidence of completed consent and navigation")


def failed_theme(directory, logfile, token, info):
    row, digest = bound_receipt(directory, token, info)
    need(row.get("scenarioPassed") is False and row.get("status") == "failed"
         and bool(THEME_STAGE.fullmatch(row.get("stage", ""))), "Not a later visual matrix failure")
    first, *rest = row.get("error", "").splitlines() or [""]
    need(first == FAILURE and any(CLASS + ".capture(" in line for line in rest[:5]),
         "Not camera-path failure in the signed driver")
    exact_failed_selector(logfile)
    need(row.get("statementCopied") is True
         and row.get("statementSha256") == hashlib.sha256(STATEMENT.encode()).hexdigest(),
         "Original consent statement not verified")
    consent_actions(row)
    return {"pid": row["pid"], "token": token, "receiptSha256": digest,
            "finished": row["finishedElapsedRealtimeMs"], "themeStillFailed": True}


def persisted_restart(directory, token, info, prior, validated):
    row, digest = bound_receipt(directory, token, info)
    need(validated == row and row.get("scenarioPassed") is True and row.get("status") == "captured"
         and row.get("stage") == "restart-consent-observed"
         and row["pid"] != prior["pid"] and token != prior["token"]
         and type(row.get("finishedElapsedRealtimeMs")) is int
         and row["finishedElapsedRealtimeMs"] > prior["finished"],
         "Restart lacks fresh process or verifier-backed consent persistence")
    frames = [frame for frame in row.get("frames", []) if frame.get("label") == "persisted-consent"]
    need(len(frames) == 1, "Missing exact restart hierarchy")
    frame = frames[0]
    need(frame.get("hierarchy") == "persisted-consent-hierarchy.json", "Wrong restart evidence path")
    raw = bounded(directory / frame["hierarchy"], 1024 * 1024)
    need(hashlib.sha256(raw).hexdigest() == frame.get("hierarchySha256"), "Restart hierarchy changed")
    nodes = json.loads(raw)
    visible = {n.get(k) for n in nodes if n.get("package") == PACKAGE and n.get("visible") is True
               for k in ("text", "description") if isinstance(n.get(k), str)}
    need("Dashboard" in visible and not {"Agree", "Restate the statement"} & visible,
         "Restart hierarchy has not ruled out the production consent screen")
    return {"pid": row["pid"], "token": token, "receiptSha256": digest}
