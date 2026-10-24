#!/usr/bin/env python3
"""Materialize an immutable test-only Smart fixture; never execute JavaScript."""
import argparse
import ast
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
from urllib.parse import quote, unquote, urlsplit

COMMIT = "89abb221a157acc98d6b52c85583e6b5a5b2742b"
SCRIPT = "Meow内核系/Aerove Lite/AeroveLite(meow).js"
SCRIPT_SHA = "217a4d7ecfb56d99cbb79148159ee636b882af5520c4ad7b6c6c870405f938cb"
MAX_FILE = 64 * 1024 * 1024
MAX_TOTAL = 256 * 1024 * 1024
BASE = "https://raw.githubusercontent.com/IvanSolis1989/Smart-Config-Kit/"

def sha(data):
    return hashlib.sha256(data).hexdigest()

def mmdb_metadata(data):
    """Decode the actual MMDB metadata map; retain language pointers as offsets."""
    marker = b"\xab\xcd\xefMaxMind.com"
    start = data.rfind(marker)
    if start < 0:
        raise ValueError("Missing actual MaxMind metadata marker")
    def read(pos):
        control = data[pos]; pos += 1
        kind, size = control >> 5, control & 31
        if kind == 1:
            length = ((control >> 3) & 3) + 1
            value = int.from_bytes(data[pos:pos + length], "big")
            return {"metadataPointer": value}, pos + length
        if kind == 0:
            kind = data[pos] + 7; pos += 1
        if size == 29:
            size = 29 + data[pos]; pos += 1
        elif size == 30:
            size = 285 + int.from_bytes(data[pos:pos + 2], "big"); pos += 2
        elif size == 31:
            size = 65821 + int.from_bytes(data[pos:pos + 3], "big"); pos += 3
        if kind in (7, 11):
            value = {} if kind == 7 else []
            for _ in range(size):
                item, pos = read(pos)
                if kind == 7:
                    child, pos = read(pos); value[item] = child
                else:
                    value.append(item)
            return value, pos
        raw = data[pos:pos + size]
        if len(raw) != size:
            raise ValueError("Truncated metadata")
        if kind == 2:
            return raw.decode("utf-8", errors="strict"), pos + size
        if kind in (5, 6, 8, 9, 10):
            return int.from_bytes(raw, "big"), pos + size
        raise ValueError("Unsupported metadata type")
    result, end = read(start + len(marker))
    if end != len(data) or result.get("binary_format_major_version") != 2:
        raise ValueError("Invalid actual MMDB metadata boundary")
    return result
def prepare(output, repository, reuse_fixture, geo_files):
    def source(path):
        parts = PurePosixPath(path).parts
        if not parts or path.startswith("/") or ".." in parts:
            raise ValueError("Unsafe source path")
        if repository:
            data = subprocess.check_output(["git", "-C", str(repository), "show", COMMIT + ":" + path])
        else:
            raise ValueError("A read-only local fixed Git repository is required")
        if len(data) > MAX_FILE:
            raise ValueError("Fixture file limit exceeded")
        return data

    previous = json.loads((reuse_fixture / 'manifest.json').read_text(encoding='utf-8'))
    previous_by_path = {entry['sourcePath']: entry for entry in previous['providers']}
    script = source(SCRIPT)
    if sha(script) != SCRIPT_SHA:
        raise ValueError("Pinned script mismatch")
    adapter = json.loads(source("Meow内核系/adapter-manifest.json"))
    if adapter["output"]["sha256_lf"] != SCRIPT_SHA:
        raise ValueError("Adapter manifest mismatch")
    text = script.decode("utf-8", errors="strict")
    def constant(name):
        match = re.search(r"const " + name + r" = (.+)\n", text)
        if not match:
            raise ValueError("Pinned source anchor missing: " + name)
        return json.loads(match[1].rstrip(";"))
    def names(name):
        match = re.search(r"const " + name + r" = \{(.*?)\n\}", text, re.S)
        if not match:
            raise ValueError("Pinned group anchor missing")
        return [ast.literal_eval(item) for item in re.findall(r":\s*('(?:[^'\\]|\\.)*')", match[1])]
    providers = constant("MIHOMO_FUSED_RULE_PROVIDERS")
    rules = constant("MIHOMO_FUSED_RULES")
    regions, business = names("SMART"), names("BIZ")
    routing = adapter["routing"]
    if (len(providers), len(rules), len(business), len(regions)) != (
            routing["provider_count"], routing["rule_count"], routing["business_group_count"], routing["maximum_region_group_count"]):
        raise ValueError("Routing identity mismatch")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    (output / "script.js").write_bytes(script)
    entries, total = [], len(script)
    for index, (name, provider) in enumerate(providers.items(), 1):
        url = urlsplit(provider["url"])
        prefix = "/IvanSolis1989/Smart-Config-Kit/main/"
        if url.scheme != "https" or url.netloc != "raw.githubusercontent.com" or not url.path.startswith(prefix):
            raise ValueError("Unexpected provider origin")
        path = unquote(url.path[len(prefix):])
        prior = previous_by_path[path]
        data = (reuse_fixture / prior["file"]).read_bytes()
        if sha(data) != prior["sha256"] or len(data) != prior["bytes"]:
            raise ValueError("Frozen prior payload mismatch")
        if data != source(path):
            raise ValueError("Payload changed at new fixed Git commit")
        total += len(data)
        if total > MAX_TOTAL:
            raise ValueError("Fixture total limit exceeded")
        relative = f"providers/{index:03d}.bin"
        destination = output / relative
        destination.parent.mkdir(exist_ok=True)
        destination.write_bytes(data)
        entries.append(dict(name=name, file=relative, sha256=sha(data), bytes=len(data),
            sourcePath=path, sourceUrl=provider["url"], runtimePath=provider["path"],
            behavior=provider["behavior"], format=provider["format"]))
    geo_entries = []
    for key, file in geo_files.items():
        data = file.read_bytes()
        if not data or len(data) > MAX_FILE:
            raise ValueError("Invalid Geo fixture size")
        metadata = mmdb_metadata(data) if key != "geosite" else None
        if metadata and metadata.get("database_type") != {"mmdb": "GeoLite2-Country", "asn-mmdb": "GeoLite2-ASN"}[key]:
            raise ValueError("Wrong real Geo MMDB database type")
        relative = "geo/" + file.name
        destination = output / relative
        destination.parent.mkdir(exist_ok=True)
        destination.write_bytes(data)
        geo_entries.append(dict(key=key, file=relative, bytes=len(data), sha256=sha(data), sourceIdentity="existing-SCK-official-check" if key != "asn-mmdb" else "existing-Standard-asset", metadata=metadata))
    manifest = dict(schemaVersion=2, smartCommit=COMMIT, geo=geo_entries,
        script=dict(file="script.js", sha256=SCRIPT_SHA, bytes=len(script)),
        expected=dict(providerCount=len(providers), ruleCount=len(rules), businessCount=len(business),
            regionCount=len(regions), groupOrder=regions[:1] + business + regions[1:], rules=rules),
        providers=entries, totalBytes=total)
    encoded = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode()
    (output / "manifest.json").write_bytes(encoded)
    return dict(manifestSha256=sha(encoded), files=len(entries) + 2 + len(geo_entries), payloadBytes=total, fixtureBytes=total + sum(e["bytes"] for e in geo_entries))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--smart-repository", required=True, type=Path)
    parser.add_argument("--reuse-fixture", required=True, type=Path)
    parser.add_argument("--country", required=True, type=Path)
    parser.add_argument("--geosite", required=True, type=Path)
    parser.add_argument("--asn", required=True, type=Path)
    arguments = parser.parse_args()
    print(json.dumps(prepare(arguments.output, arguments.smart_repository, arguments.reuse_fixture, dict(mmdb=arguments.country, geosite=arguments.geosite, **{"asn-mmdb": arguments.asn})), indent=2))
