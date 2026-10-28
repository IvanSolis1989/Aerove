"""Bounded same-repository artifact evidence consumer; never application acceptance."""
import binascii
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import selectors
import shutil
import signal
import stat
import struct
import subprocess
import tempfile
import time
import unicodedata
import zipfile

REPO = "IvanSolis1989/Aerove"
BRANCH = "refs/heads/codex/aerove-evidence-reader-34"
MAX_ZIP = 96 * 1024 * 1024
MAX_OUTPUT = 8 * 1024 * 1024
MAX_EXPANDED = 512 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def gh(route, destination, limit, timeout, receipts):
    """Bound stdout/stderr and time; never persist headers, signed URLs or stderr text."""
    command = [shutil.which("gh") or "/usr/bin/gh", "api", route]
    env = dict(PATH="/usr/bin:/bin", HOME=os.environ["HOME"], GH_TOKEN=os.environ["GH_TOKEN"],
               GH_PROMPT_DISABLED="1", NO_COLOR="1")
    proc = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, stdin=subprocess.DEVNULL,
                            env=env, start_new_session=True)
    counts = {"stdout": 0, "stderr": 0}
    stderr = bytearray()
    started = time.monotonic()
    error = None
    try:
        with selectors.DefaultSelector() as selector, Path(destination).open("wb") as output:
            selector.register(proc.stdout, selectors.EVENT_READ, "stdout")
            selector.register(proc.stderr, selectors.EVENT_READ, "stderr")
            while selector.get_map():
                require(time.monotonic() - started < timeout, "gh time limit exceeded")
                for key, _ in selector.select(0.2):
                    chunk = os.read(key.fileobj.fileno(), 65536)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    counts[key.data] += len(chunk)
                    require(counts["stdout"] <= limit and counts["stderr"] <= 8192, "gh output limit exceeded")
                    if key.data == "stdout":
                        output.write(chunk)
                    else:
                        stderr.extend(chunk)
            require(proc.wait(timeout=2) == 0, "gh API/download failed")
    except Exception as caught:
        error = caught
    finally:
        if proc.poll() is None:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait(timeout=5)
        proc.stdout.close()
        proc.stderr.close()
        receipts.append(dict(route=route, stdoutBytes=counts["stdout"], stderrBytes=counts["stderr"],
            stderrCategories=[word for word in ("eof", "timeout", "forbidden", "reset") if word.encode() in stderr.lower()],
            exitCode=proc.returncode, seconds=round(time.monotonic() - started, 3),
            stderrTextPersisted=False, credentialsOrSignedUrlsPersisted=False))
    if error:
        raise error


def sensitive_text(text):
    token = os.environ.get("GH_TOKEN")
    return bool(re.search(r"https?://|\b(?:proxy-)?authorization[\"']?\s*[:=]\s*\S|\b(?:sig|signature)[\"']?\s*[:=]\s*\S|\bBearer\s+\S|\b(?:GH_TOKEN|GITHUB_TOKEN|SAS|access_token|secret|password)[\"']?\s*[:=]\s*\S",
                          text, re.IGNORECASE) or (token and token in text))


def safe_path(name):
    path = PurePosixPath(name)
    require(isinstance(name, str) and 0 < len(name.encode()) <= 1024
            and "\\" not in name and ":" not in name and not name.startswith("/")
            and not any(ord(c) < 32 or ord(c) == 127 for c in name)
            and all(p not in ("", ".", "..") and not p.endswith((".", " ")) for p in path.parts)
            and path.as_posix() == name.rstrip("/") and not sensitive_text(name), "Unsafe member path")
    return path


def parse_request(raw):
    require(len(raw.encode()) <= 16384, "Input limit exceeded")
    request = json.loads(raw)
    required = {"runId", "attempt", "headSHA", "artifactId", "artifactName", "expectedZIPsha", "expectedZIPbytes", "mode"}
    require(isinstance(request, dict) and required <= request.keys()
            and not request.keys() - required - {"memberPaths", "basenames", "pathContains"}, "Input schema mismatch")
    for key in ("runId", "attempt", "artifactId", "expectedZIPbytes"):
        require(type(request[key]) is int and request[key] > 0, "Positive integer input required")
    require(request["expectedZIPbytes"] <= MAX_ZIP and request["mode"] in ("index", "select")
            and re.fullmatch(r"[0-9a-f]{40}", request["headSHA"]) is not None
            and re.fullmatch(r"[0-9a-f]{64}", request["expectedZIPsha"]) is not None, "Identity or mode mismatch")
    safe_path(request["artifactName"])
    lists = [key for key in ("memberPaths", "basenames") if key in request]
    require((request["mode"] == "index" and not lists) or (request["mode"] == "select" and len(lists) == 1), "Selection scope mismatch")
    if "pathContains" in request:
        value = request["pathContains"]
        require(request["mode"] == "index" and isinstance(value, str)
                and 1 <= len(value) <= 128
                and all(not unicodedata.category(c).startswith("C") for c in value)
                and len(value.encode("utf-8")) <= 256 and not sensitive_text(value),
                "Invalid literal index filter")
    if lists:
        names = request[lists[0]]
        require(isinstance(names, list) and 1 <= len(names) <= 8 and all(isinstance(n, str) for n in names)
                and len(set(names)) == len(names), "Selection count or duplicate mismatch")
        for name in names:
            path = safe_path(name)
            require(not name.endswith("/") and path.suffix.lower() in (".json", ".txt", ".png")
                    and (lists[0] != "basenames" or path.name == name), "Unsupported selected member")
    return request


def validate_api(request, run, artifact):
    require(run.get("id") == request["runId"] and run.get("head_sha") == request["headSHA"]
            and run.get("run_attempt") == request["attempt"] and run.get("status") == "completed"
            and all(run.get(k, {}).get("full_name") == REPO for k in ("repository", "head_repository")), "Original run identity mismatch")
    origin = artifact.get("workflow_run", {})
    require(artifact.get("id") == request["artifactId"] and artifact.get("name") == request["artifactName"]
            and artifact.get("size_in_bytes") == request["expectedZIPbytes"] and artifact.get("expired") is False
            and artifact.get("digest") == "sha256:" + request["expectedZIPsha"]
            and origin.get("id") == request["runId"] and origin.get("head_sha") == request["headSHA"]
            and run["run_started_at"] <= artifact.get("created_at", "") <= run["updated_at"], "Artifact origin/digest/size/attempt interval mismatch")


def validate_png(data):
    require(data.startswith(b"\x89PNG\r\n\x1a\n"), "Invalid PNG signature")
    position, count, ended = 8, 0, False
    seen_srgb, seen_palette_or_data = False, False
    seen_sbit = False
    while position < len(data):
        require(position + 12 <= len(data), "Truncated PNG chunk")
        size = struct.unpack(">I", data[position:position + 4])[0]
        kind = data[position + 4:position + 8]
        end = position + 12 + size
        require(end <= len(data) and count < 10000, "PNG chunk limit exceeded")
        require(kind in (b"IHDR", b"PLTE", b"IDAT", b"IEND", b"sRGB", b"sBIT"),
                f"Unsupported evidence PNG chunk; no image emitted (chunkHex={kind.hex()})")
        require(binascii.crc32(data[position + 4:end - 4]) & 0xffffffff == struct.unpack(">I", data[end - 4:end])[0], "PNG CRC mismatch")
        if count == 0:
            require(kind == b"IHDR" and size == 13, "PNG IHDR missing")
            width, height = struct.unpack(">II", data[position + 8:position + 16])
            require(0 < width <= 16384 and 0 < height <= 16384 and width * height <= 64000000, "PNG dimensions exceeded")
            png_bit_depth, png_color_type = data[position + 16:position + 18]
        if kind == b"sBIT":
            channels, depths = {0: (1, (1, 2, 4, 8, 16)), 2: (3, (8, 16)),
                                3: (3, (1, 2, 4, 8)), 4: (2, (8, 16)),
                                6: (4, (8, 16))}.get(png_color_type, (0, ()))
            require(png_bit_depth in depths, "Invalid PNG IHDR for sBIT")
            sample_depth = 8 if png_color_type == 3 else png_bit_depth
            require(size == channels and all(0 < value <= sample_depth
                    for value in data[position + 8:end - 4]), "Invalid PNG sBIT significant bits")
            require(not seen_sbit and not seen_palette_or_data, "Duplicate or out-of-order PNG sBIT")
            seen_sbit = True
        if kind == b"sRGB":
            require(size == 1 and data[position + 8] <= 3, "Invalid PNG sRGB rendering intent")
            require(not seen_srgb and not seen_palette_or_data, "Duplicate or out-of-order PNG sRGB")
            seen_srgb = True
        if kind in (b"PLTE", b"IDAT"):
            seen_palette_or_data = True
        count += 1
        position = end
        if kind == b"IEND":
            require(size == 0 and end == len(data), "PNG trailing data")
            ended = True
            break
    require(ended, "PNG IEND missing")


def consume(archive, request, out, receipt, deadline):
    require(archive.stat().st_size == request["expectedZIPbytes"], "ZIP size mismatch")
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    require(digest == request["expectedZIPsha"], "ZIP digest mismatch")
    receipt.update(wholeZipDigestVerified=True, actualZipSha256=digest)
    with zipfile.ZipFile(archive) as package:
        infos = package.infolist()
        require(len(infos) <= 12000, "ZIP entry count exceeded")
        seen, expanded = set(), 0
        for entry in infos:
            safe_path(entry.orig_filename)
            safe_path(entry.filename)
            require(entry.orig_filename == entry.filename and entry.filename.casefold() not in seen
                    and (not entry.is_dir() or entry.file_size == 0)
                    and stat.S_IFMT(entry.external_attr >> 16) in (0, stat.S_IFREG, stat.S_IFDIR)
                    and not entry.flag_bits & 1 and entry.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                    and 0 <= entry.header_offset < package.start_dir, "Duplicate or unsupported ZIP entry")
            seen.add(entry.filename.casefold())
            expanded += entry.file_size
            require(entry.file_size <= 128 * 1024 * 1024 and entry.file_size <= max(1, entry.compress_size) * 1000
                    and expanded <= MAX_EXPANDED, "ZIP expansion limit exceeded")
        selected = []
        if request["mode"] == "select":
            for name in request.get("memberPaths", request.get("basenames", [])):
                matches = [e for e in infos if not e.is_dir() and (e.filename == name if "memberPaths" in request else PurePosixPath(e.filename).name == name)]
                require(len(matches) == 1, "Selected member missing or ambiguous")
                selected.append(matches[0].filename)
            require(sum(e.file_size for e in infos if e.filename in selected) <= MAX_OUTPUT - 262144, "Selected output limit exceeded")
        entries, payloads = [], {}
        for entry in infos:
            hasher, size, chunks = hashlib.sha256(), 0, []
            with package.open(entry) as stream:
                while True:
                    require(time.monotonic() < deadline, "Evidence processing deadline exceeded")
                    chunk = stream.read(65536)
                    if not chunk:
                        break
                    size += len(chunk)
                    require(size <= entry.file_size, "Entry size mismatch")
                    hasher.update(chunk)
                    if entry.filename in selected:
                        chunks.append(chunk)
            require(size == entry.file_size, "Entry size mismatch")
            if entry.is_dir():
                continue
            entries.append(dict(path=entry.filename, bytes=size, crc32=f"{entry.CRC:08x}", rawSha256=hasher.hexdigest()))
            if chunks:
                payloads[entry.filename] = b"".join(chunks)
            elif entry.filename in selected:
                payloads[entry.filename] = b""
        receipt["allEntryCrcVerified"] = True
        if request["mode"] == "index":
            path_filter = request.get("pathContains")
            matched = entries if path_filter is None else [row for row in entries if path_filter in row["path"]]
            data = (json.dumps(matched, indent=2) + "\n").encode()
            require(len(data) <= 131072, "Index output limit exceeded; select exact members instead")
            (out / "INDEX.json").write_bytes(data)
            receipt["outputScope"] = dict(mode="index", entryCount=len(matched), bytes=len(data),
                totalEntryCount=len(entries), matchedEntryCount=len(matched),
                filter=None if path_filter is None else dict(pathContains=path_filter, interpretation="literal substring", caseSensitive=True))
        else:
            outputs = []
            for index, name in enumerate(selected, 1):
                data = payloads[name]
                suffix = PurePosixPath(name).suffix.lower()
                if suffix == ".png":
                    validate_png(data)
                else:
                    text = data.decode("utf-8", errors="strict")
                    require(not sensitive_text(text), "Selected text contains suspected credential/URL material; no text emitted")
                    require("\x00" not in text, "Selected text contains NUL")
                    if suffix == ".json":
                        json.loads(text)
                outputs.append(dict(member=name, output=f"member-{index:02d}{suffix}", bytes=len(data),
                                    rawSha256=hashlib.sha256(data).hexdigest(), outputSha256=hashlib.sha256(data).hexdigest(), transformation="none"))
            for row in outputs:
                (out / row["output"]).write_bytes(payloads[row["member"]])
            receipt["outputScope"] = dict(mode="select", members=outputs, sensitiveTextPolicy="reject entire selection before any payload write")


def log_json_outputs(out, receipt):
    require(receipt.get("completed") is True and receipt.get("wholeZipDigestVerified") is True
            and receipt.get("allEntryCrcVerified") is True, "JSON log transport requires successful evidence read")
    scope = receipt["outputScope"]
    candidates = [dict(output="INDEX.json")] if scope["mode"] == "index" else scope["members"]
    transport = dict(limitBytes=16384, loggedFiles=[], omittedFiles=[])
    frames, total = [], 0
    identity = {key: receipt["original"][key] for key in
                ("runId", "attempt", "headSHA", "artifactId", "artifactName", "expectedZIPsha", "expectedZIPbytes")}
    for row in candidates:
        name = row["output"]
        if not name.endswith(".json"):
            continue
        require(name == "INDEX.json" if scope["mode"] == "index" else
                re.fullmatch(r"member-0[1-8]\.json", name) is not None, "Unexpected JSON output name")
        with (out / name).open("rb") as stream:
            data = stream.read(16385)
        if len(data) > 16384:
            transport["omittedFiles"].append(dict(filename=name, reason="single-file byte limit"))
            continue
        digest = hashlib.sha256(data).hexdigest()
        if scope["mode"] == "select":
            require(len(data) == row["bytes"] and digest == row["outputSha256"], "JSON output readback mismatch")
        text = data.decode("utf-8", errors="strict")
        json.loads(text)
        if sensitive_text(text):
            transport["omittedFiles"].append(dict(filename=name, reason="sensitive text policy"))
            continue
        frame = dict(version=1, filename=name, bytes=len(data), sha256=digest,
                     encoding="base64", data=base64.b64encode(data).decode("ascii"), original=identity,
                     wholeZipDigestVerified=True, allEntryCrcVerified=True)
        if scope["mode"] == "index":
            frame["indexScope"] = scope
        else:
            frame["member"] = row["member"]
        line = "AEROVE_JSON_FRAME_V1 " + json.dumps(frame, separators=(",", ":")) + " AEROVE_JSON_FRAME_END\n"
        if sensitive_text(json.dumps({key: value for key, value in frame.items() if key != "data"})):
            transport["omittedFiles"].append(dict(filename=name, reason="sensitive metadata policy"))
            continue
        size = len(line.encode("utf-8"))
        if total + size > 16384:
            transport["omittedFiles"].append(dict(filename=name, reason="total framed byte limit"))
            continue
        total += size
        frames.append(line)
        transport["loggedFiles"].append(dict(filename=name, bytes=len(data), sha256=digest, framedBytes=size))
    transport["totalFramedBytes"] = total
    receipt["jsonLogTransport"] = transport
    for line in frames:
        print(line, end="", flush=True)


def main():
    out = Path(os.environ["RUNNER_TEMP"]) / "artifact-evidence-out"
    out.mkdir(exist_ok=False)
    receipt = dict(role="evidenceOnly", compiled=False, emulatorStarted=False, accepted=False,
                   controller={k: os.environ.get(k) for k in ("GITHUB_SHA", "GITHUB_REF", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT")},
                   completed=False, requests=[], wholeZipDigestVerified=False, allEntryCrcVerified=False)
    result = 1
    try:
        require(os.environ.get("GITHUB_REPOSITORY") == REPO and os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
                and os.environ.get("GITHUB_REF") == BRANCH, "Evidence controller context mismatch")
        request = parse_request(os.environ.get("EVIDENCE_REQUEST", ""))
        receipt["original"] = request
        deadline = time.monotonic() + 220
        with tempfile.TemporaryDirectory(prefix="original-evidence-") as private:
            private = Path(private)
            for name, route in (("run", f"repos/{REPO}/actions/runs/{request['runId']}/attempts/{request['attempt']}"),
                                ("artifact", f"repos/{REPO}/actions/artifacts/{request['artifactId']}")):
                gh(route, private / (name + ".json"), 1024 * 1024, 20, receipt["requests"])
            validate_api(request, json.loads((private / "run.json").read_text()), json.loads((private / "artifact.json").read_text()))
            gh(f"repos/{REPO}/actions/artifacts/{request['artifactId']}/zip", private / "original.zip", request["expectedZIPbytes"], 160, receipt["requests"])
            consume(private / "original.zip", request, out, receipt, deadline)
        receipt["completed"] = True
        log_json_outputs(out, receipt)
        result = 0
    except Exception as error:
        receipt["failure"] = dict(type=type(error).__name__, message=str(error) if isinstance(error, ValueError) and type(error) is ValueError else "Evidence read failed; raw exception/stderr withheld")
    finally:
        (out / "RECEIPT.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return result


if __name__ == "__main__":
    raise SystemExit(main())
