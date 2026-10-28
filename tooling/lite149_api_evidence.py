"""Read fixed run149 methods privately; never compile, install, or authorize an APK."""
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import tempfile
import time
import zipfile

import artifact_evidence as reader

HERE = Path(__file__).resolve().parent
CONTRACT = json.loads((HERE / 'lite149_api_contract.json').read_text())


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def request(raw):
    value = reader.parse_request(raw)
    reader.require(value == CONTRACT['request'], 'Only the frozen run149 index identity is allowed')
    return value


def usage_results(text, methods):
    removed_classes, members, owner = set(), set(), None
    for line in text.splitlines():
        if line and not line[0].isspace():
            owner = line.rstrip(':')
            if not line.endswith(':'):
                removed_classes.add(owner)
        elif owner and line.strip():
            match = re.search(r'(?:^|\s)([^\s(]+)\(([^)]*)\)', line.strip())
            if match:
                members.add((owner, match[1], match[2]))
    return [dict(method, explicitlyRemoved=(method['owner'] in removed_classes or
             (method['owner'], method['name'], ','.join(method['parameters'])) in members)) for method in methods]


def mapping_targets(text, methods):
    classes, names, owner = {}, {}, None
    groups, pending, unsupported_owners = [], [], set()
    group_metadata = False

    def finish_group():
        nonlocal pending, group_metadata
        if pending:
            groups.append((pending[0]['owner'], pending[0]['alias'], pending[-1]['signature'], group_metadata))
        pending, group_metadata = [], False

    for line in text.splitlines():
        match = re.fullmatch(r'([^\s]+) -> ([^\s:]+):', line)
        if match:
            finish_group()
            owner = match[1]
            reader.require(owner not in classes, 'Duplicate original class in mapping')
            classes[owner] = match[2]
        elif line.lstrip().startswith('#'):
            # Preserve frame adjacency; unknown per-method metadata cannot prove identity.
            group_metadata = group_metadata or bool(pending)
        elif line.startswith('    '):
            match = re.fullmatch(r'\s+(?:(\d+):(\d+):)?([^ ]+) ([^ (]+)\(([^)]*)\)(?::(\d+)(?::(\d+))?)? -> ([^ ]+)', line)
            if not match:
                finish_group()
                if '(' in line and '->' in line:
                    unsupported_owners.add(owner)
                continue
            interval = (match[1], match[2]) if match[1] is not None else None
            if interval is None and match[6] is not None:
                unsupported_owners.add(owner)
            row = dict(owner=owner, alias=match[8], interval=interval,
                signature=(owner, match[4], match[3], match[5]))
            if pending and (interval is None or pending[0]['interval'] != interval or pending[0]['alias'] != row['alias']):
                finish_group()
            pending.append(row)
        elif line.strip():
            finish_group()
    finish_group()
    residuals = {}
    for class_owner, alias, outer, metadata in groups:
        signatures, uncertain = residuals.setdefault((class_owner, alias), (set(), set()))
        signatures.add(outer)
        if metadata or class_owner in unsupported_owners or '.' in outer[1]:
            uncertain.add(True)
    for (_, alias), (signatures, uncertain) in residuals.items():
        if len(signatures) == 1 and not uncertain:
            names.setdefault(next(iter(signatures)), set()).add(alias)
    def descriptor(name):
        primitive = dict(void='V', boolean='Z', byte='B', short='S', char='C', int='I', long='J', float='F', double='D')
        if name.endswith('[]'):
            return '[' + descriptor(name[:-2])
        return primitive[name] if name in primitive else 'L' + classes.get(name, name).replace('.', '/') + ';'
    result = []
    for method in methods:
        key = (method['owner'], method['name'], method['returns'], ','.join(method['parameters']))
        mapped = sorted(names.get(key, set()))
        result.append(dict(method, mappedOwner=classes.get(method['owner']), mappedNames=mapped,
            mappingStatus='CONFIRMED_OUTER_METHOD' if mapped else 'MISSING_OR_AMBIGUOUS_OUTER_MAPPING',
            descriptor='(' + ''.join(descriptor(p) for p in method['parameters']) + ')' + descriptor(method['returns'])))
    return result


def definition_results(smali, targets):
    declarations = set(re.findall(r'^\s*\.method\s+[^\n]*?\s([^\s]+\([^\n]*)$', smali, re.MULTILINE))
    return [dict(target, definitionCandidates=[name + target['descriptor'] for name in target['mappedNames']
             if name + target['descriptor'] in declarations]) for target in targets]


def bounded_command(argv, timeout, deadline, private):
    reader.require(time.monotonic() < deadline, 'Audit deadline exceeded before tool invocation')
    proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, stdin=subprocess.DEVNULL,
        env={k: v for k, v in os.environ.items() if not any(t in k.upper() for t in ('TOKEN', 'SECRET', 'PASSWORD', 'KEYSTORE'))},
        start_new_session=True)
    output, errors = bytearray(), 0
    until = min(deadline, time.monotonic() + timeout)
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(proc.stdout, selectors.EVENT_READ, 'stdout')
            selector.register(proc.stderr, selectors.EVENT_READ, 'stderr')
            while selector.get_map():
                reader.require(time.monotonic() < until, 'Audit tool deadline exceeded')
                for key, _ in selector.select(0.1):
                    chunk = os.read(key.fileobj.fileno(), 65536)
                    if not chunk:
                        selector.unregister(key.fileobj)
                    elif key.data == 'stdout':
                        output.extend(chunk)
                    else:
                        errors += len(chunk)
                    reader.require(len(output) <= 2 * 1024 * 1024 and errors <= 8192, 'Audit tool output limit exceeded')
            code = proc.wait(timeout=2)
        reader.require(code == 0, 'Audit tool did not complete successfully')
        return output.decode('utf-8', errors='strict')
    finally:
        if proc.poll() is None:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait(timeout=5)
        proc.stdout.close()
        proc.stderr.close()


def tool_identity():
    sdk = Path(os.environ['ANDROID_SDK_ROOT'])
    analyzer = sdk / 'cmdline-tools/latest/bin/apkanalyzer'
    signer = sdk / 'build-tools/37.0.0/apksigner'
    reader.require(analyzer.is_file() and signer.is_file(), 'Required preinstalled SDK tools absent; no installation allowed')
    source = analyzer.parent.parent / 'source.properties'
    reader.require(source.is_file(), 'SDK command-line tool identity missing')
    jars = sorted((analyzer.parent.parent / 'lib').rglob('*.jar'))
    reader.require(0 < len(jars) <= 128, 'SDK command-line tool closure missing or oversized')
    revision = re.search(r'^Pkg.Revision\s*=\s*([0-9.]+)\s*$', source.read_text(), re.MULTILINE)
    reader.require(revision is not None, 'SDK command-line tool revision missing')
    signer_jar = signer.parent / 'lib/apksigner.jar'
    reader.require(signer_jar.is_file(), 'Preinstalled signature tool library missing')
    return analyzer, signer, dict(packageRevision=revision[1], sourcePropertiesSha256=digest(source),
        analyzerScriptSha256=digest(analyzer), signerScriptSha256=digest(signer), signerJarSha256=digest(signer_jar),
        libraries=[dict(name=p.relative_to(analyzer.parent.parent).as_posix(), sha256=digest(p)) for p in jars])


def verify_signature(raw):
    hashes = re.findall(r'^(?:Signer #[0-9]+|V2 Signer:) certificate SHA-256 digest: ([0-9a-f]{64})$', raw, re.MULTILINE)
    reader.require(hashes == [CONTRACT['signerSha256']] and raw.splitlines().count('Verifies') == 1
        and 'Verified using v2 scheme (APK Signature Scheme v2): true' in raw,
        'Original APK signer mismatch')


def audit_archive(archive, private, deadline):
    inspected = private / 'index'
    inspected.mkdir()
    proof = {}
    reader.consume(archive, CONTRACT['request'], inspected, proof, deadline)
    actual = json.loads((inspected / 'INDEX.json').read_text())
    reader.require(actual == CONTRACT['index'], 'Original runtime member closure differs from sealed 22-entry index')
    needed = ['apks/app-release.apk', 'mapping/release/mapping.txt', 'mapping/release/usage.txt', 'BUILD_INFO.json', 'source-manifest.json']
    paths = {}
    with zipfile.ZipFile(archive) as z:
        for index, name in enumerate(needed):
            path = private / Path(name).name
            with z.open(name) as source, path.open('xb') as output:
                while chunk := source.read(65536):
                    reader.require(time.monotonic() < deadline, 'Private extraction deadline exceeded')
                    output.write(chunk)
            expected = next(row for row in CONTRACT['index'] if row['path'] == name)
            reader.require(path.stat().st_size == expected['bytes'] and digest(path) == expected['rawSha256'], 'Original private member hash mismatch')
            paths[name] = path
    info = json.loads(paths['BUILD_INFO.json'].read_text())
    manifest = json.loads(paths['source-manifest.json'].read_text())
    reader.require(info.get('source') == CONTRACT['request']['headSHA'] and info.get('tree') == CONTRACT['sourceTree']
        and info.get('run_number') == 149 and info.get('run_attempt') == 1
        and info.get('version') == '0.1.0-beta.23' and info.get('version_code') == 23
        and info.get('signer_sha256') == CONTRACT['signerSha256']
        and info.get('apk_sha256') == digest(paths[needed[0]]), 'Original BUILD_INFO scalar identity mismatch')
    reader.require(manifest.get('sourceRevision') == CONTRACT['request']['headSHA']
        and manifest.get('sourceTree') == CONTRACT['sourceTree'] and manifest.get('workflowRun') == '40627739662'
        and manifest.get('runAttempt') == 1 and manifest.get('runNumber') == 149
        and manifest.get('abi') == 'x86_64' and manifest.get('prebuiltApks') is True
        and manifest.get('apkInspectorOutcome') == 'success', 'Original runtime manifest identity mismatch')
    usage = usage_results(paths[needed[2]].read_text(), CONTRACT['methods'])
    result = dict(wholeZipDigestVerified=True, allEntryCrcVerified=True, originalAppApkSha256=digest(paths[needed[0]]),
        originalMappingSha256=digest(paths[needed[1]]), originalUsageSha256=digest(paths[needed[2]]), usage=usage,
        compileAuthorized=False, testApkBuilt=False, appApkBuilt=False, installed=False, accepted=False,
        diagnosticOnly=True, selector=CONTRACT['selector'], diagnosticPatchSha256=CONTRACT['diagnosticPatchSha256'])
    if any(row['explicitlyRemoved'] for row in usage):
        result.update(status='REQUIRED_API_EXPLICITLY_REMOVED', definitionsChecked=False)
        return result
    analyzer, signer, identity = tool_identity()
    signature = bounded_command([str(signer), 'verify', '--verbose', '--print-certs', str(paths[needed[0]])], 20, deadline, private)
    verify_signature(signature)
    targets = mapping_targets(paths[needed[1]].read_text(), CONTRACT['methods'])
    results = []
    for owner in sorted({row['mappedOwner'] for row in targets if row['mappedOwner']}):
        smali = bounded_command([str(analyzer), 'dex', 'code', '--class', owner, str(paths[needed[0]])], 20, deadline, private)
        results += definition_results(smali, [row for row in targets if row['mappedOwner'] == owner])
    results += [dict(row, definitionCandidates=[]) for row in targets if not row['mappedOwner']]
    found = len(results) == len(CONTRACT['methods']) and all(len(row['definitionCandidates']) == 1 for row in results)
    result.update(status='REQUIRED_DEFINITIONS_FOUND_COMPILE_STILL_REQUIRED' if found else 'REQUIRED_DEFINITION_MISSING_OR_REWRITTEN',
        definitionsChecked=True, allRequiredDefinitionsFound=found, methods=results,
        sdkToolIdentity=identity, signatureVerifiedAtRuntime=True,
        boundary='DEX declarations only; not test compilation, R8 mapping consumption, classloader linkage or Android execution')
    return result


def main():
    out = Path(os.environ['RUNNER_TEMP']) / 'artifact-evidence-out'
    out.mkdir(exist_ok=False)
    receipt = dict(role='evidenceOnly', compiled=False, emulatorStarted=False, accepted=False, completed=False, requests=[],
        controller={k: os.environ.get(k) for k in ('GITHUB_SHA', 'GITHUB_REF', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT')})
    code = 1
    try:
        reader.require(os.environ.get('GITHUB_REPOSITORY') == reader.REPO and os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and os.environ.get('GITHUB_REF') == reader.BRANCH, 'Evidence controller context mismatch')
        fixed = request(os.environ.get('EVIDENCE_REQUEST', ''))
        receipt['original'] = fixed
        deadline = time.monotonic() + 220
        with tempfile.TemporaryDirectory(prefix='lite149-private-api-') as directory:
            private = Path(directory)
            for name, route in (('run', f"repos/{reader.REPO}/actions/runs/{fixed['runId']}/attempts/{fixed['attempt']}"),
                                ('artifact', f"repos/{reader.REPO}/actions/artifacts/{fixed['artifactId']}")):
                reader.gh(route, private / (name + '.json'), 1024 * 1024, 20, receipt['requests'])
            reader.validate_api(fixed, json.loads((private / 'run.json').read_text()), json.loads((private / 'artifact.json').read_text()))
            reader.gh(f"repos/{reader.REPO}/actions/artifacts/{fixed['artifactId']}/zip", private / 'original.zip', fixed['expectedZIPbytes'], min(160, deadline-time.monotonic()), receipt['requests'])
            result = audit_archive(private / 'original.zip', private, deadline)
        payload = (json.dumps(result, indent=2) + '\n').encode()
        reader.require(len(payload) <= 65536 and not reader.sensitive_text(payload.decode()), 'Whitelist audit result output rejected')
        (out / 'API-WHITELIST.json').write_bytes(payload)
        receipt.update(completed=True, resultSha256=hashlib.sha256(payload).hexdigest(),
            outputScope='Only fixed whitelist signatures and status; private APK/mapping/smali never uploaded')
        code = 0
    except Exception as error:
        receipt['failure'] = dict(type=type(error).__name__, message=str(error) if type(error) is ValueError else 'Audit failed; raw exception withheld')
    finally:
        (out / 'RECEIPT.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return code


if __name__ == '__main__':
    raise SystemExit(main())
