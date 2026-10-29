"""Materialize the current bundled Smart fixture from fixed Git and real JS output."""
import ast
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
from urllib.parse import unquote, urlsplit

from prepare_smart_config_meow_fixture import SCRIPT, MAX_FILE, MAX_TOTAL, sha, mmdb_metadata

COMMIT = "c18c314d94206eb6724d235ca0d590c3e14e9121"
SCRIPT_SHA = "96b798d1d01129c0d8552a4a7a39b5108668d9007c6af996cb58b82d0d429586"
NODE_PROJECTION = r"""
const vm=require('node:vm'),assert=require('node:assert/strict');
let text='';process.stdin.on('data',b=>text+=b);process.stdin.on('end',()=>{
const p=JSON.parse(text),sandbox=vm.createContext({console:{log(){}}});
new vm.Script(p.script).runInContext(sandbox,{timeout:3000});sandbox.input=p.input;
const actual=JSON.parse(JSON.stringify(vm.runInContext('main(input)',sandbox,{timeout:3000})));
const owner={exports:{}};new vm.Script(p.projection).runInNewContext({module:owner,exports:owner.exports},{timeout:3000});
const baseline={...actual,'rule-providers':p.providers,rules:p.rules};
const expected=JSON.parse(JSON.stringify(owner.exports.projectAeroveRouting(baseline,p.splits)));
for(const key of ['rule-providers','rules'])assert.deepEqual(actual[key],expected[key],key);
process.stdout.write(JSON.stringify(actual));});
"""


def projected_routing(script, projection, adapter):
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
    regions, business = names("SMART"), names("BIZ")
    config = {"proxies": [dict(name=name, type="ss", server=f"192.0.2.{index + 1}", port=443,
                cipher="aes-128-gcm", password="synthetic-only") for index, name in enumerate([
                "香港 HKG 家宽", "台湾 TWN 家宽", "狮城 SGP 家宽", "日本 JPN 家宽", "美国 USA 家宽",
                "德国 DEU 家宽", "巴西 BRA 家宽", "南非 ZAF 家宽", "月球 ZZZ 家宽"])]}
    payload = dict(script=text, projection=projection.decode("utf-8"), input=config,
        providers=constant("MIHOMO_FUSED_RULE_PROVIDERS"), rules=constant("MIHOMO_FUSED_RULES"),
        splits=constant("SCKI_AEROVE_RULE_SPLITS"))
    result = subprocess.run(["node", "-e", NODE_PROJECTION], input=json.dumps(payload).encode(),
        capture_output=True, timeout=15, check=True)
    actual = json.loads(result.stdout)
    providers, rules = actual["rule-providers"], actual["rules"]
    routing = adapter["aerove_routing"]
    if (len(providers), len(rules), len(business), len(regions)) != (
            routing["provider_count"], routing["rule_count"],
            adapter["routing"]["business_group_count"], adapter["routing"]["maximum_region_group_count"]):
        raise ValueError("Current projected routing identity mismatch")
    if [group["name"] for group in actual["proxy-groups"]] != regions[:1] + business + regions[1:]:
        raise ValueError("Current projected group order mismatch")
    return providers, rules, regions, business


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

    previous_by_path = {}
    if reuse_fixture is not None:
        previous = json.loads((reuse_fixture / 'manifest.json').read_text(encoding='utf-8'))
        previous_by_path = {entry['sourcePath']: entry for entry in previous['providers']}
    script = source(SCRIPT)
    if sha(script) != SCRIPT_SHA:
        raise ValueError("Pinned script mismatch")
    adapter = json.loads(source("Meow内核系/adapter-manifest.json"))
    if adapter["output"]["sha256_lf"] != SCRIPT_SHA:
        raise ValueError("Adapter manifest mismatch")
    adapter_inputs = adapter["input_sha256_lf"]
    projection_path = "tools/lib/aerove-rule-projection.js"
    projection = source(projection_path)
    if sha(projection) != adapter_inputs[projection_path]:
        raise ValueError("Pinned projection module mismatch")
    providers, rules, regions, business = projected_routing(script, projection, adapter)
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
        data = source(path)
        if reuse_fixture is not None:
            prior = previous_by_path[path]
            frozen = (reuse_fixture / prior["file"]).read_bytes()
            if sha(frozen) != prior["sha256"] or len(frozen) != prior["bytes"]:
                raise ValueError("Frozen prior payload mismatch")
            if data != frozen:
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
