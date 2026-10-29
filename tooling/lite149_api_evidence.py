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
TRACE_OWNER = 'androidx.compose.ui.semantics.SemanticsNode'
TRACE_METHODS = [dict(owner=TRACE_OWNER, name=name, returns=returns, parameters=[]) for name, returns in (
    ('getBoundsInRoot', 'androidx.compose.ui.geometry.Rect'), ('getId', 'int'),
    ('getLayoutInfo', 'androidx.compose.ui.layout.LayoutInfo'))]
TRACE_BYTES = 24576


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


def mapping_targets(text, methods, _trace=None):
    classes, names, owner = {}, {}, None
    groups, pending, unsupported_owners = [], [], set()
    group_metadata = False
    trace_groups, metadata_ids = [], []
    trace_truncated = False
    trace_paths = [dict(exactRows=0, qualifiedRows=0, sameNameRows=0, matchingGroups=0,
        outerGroups=0, nonOuterGroups=0, aliases=set(), outerAliases=set(), sampleGroups=0,
        mismatchSampleGroups=0) for _ in TRACE_METHODS]

    def finish_group():
        nonlocal pending, group_metadata, metadata_ids, trace_truncated
        if pending:
            groups.append((pending[0]['owner'], pending[0]['alias'], pending[-1]['signature'], group_metadata))
            if _trace is not None and pending[0]['owner'] == TRACE_OWNER:
                keep = False
                signatures = [row['signature'] for row in pending]
                for method, path in zip(TRACE_METHODS, trace_paths):
                    key = (TRACE_OWNER, method['name'], method['returns'], '')
                    qualified = (TRACE_OWNER, TRACE_OWNER + '.' + method['name'], method['returns'], '')
                    exact, qualified_rows = signatures.count(key), signatures.count(qualified)
                    named = sum(frame[1] in (method['name'], TRACE_OWNER + '.' + method['name']) for frame in signatures)
                    path['exactRows'] += exact
                    path['qualifiedRows'] += qualified_rows
                    path['sameNameRows'] += named
                    if exact or qualified_rows:
                        path['matchingGroups'] += 1
                        path['aliases'].add(pending[0]['alias'])
                        path['outerGroups' if signatures[-1] in (key, qualified) else 'nonOuterGroups'] += 1
                        if signatures[-1] in (key, qualified):
                            path['outerAliases'].add(pending[0]['alias'])
                    if named:
                        sample_key = 'sampleGroups' if exact or qualified_rows else 'mismatchSampleGroups'
                        if path[sample_key] < 2:
                            keep = True
                            path[sample_key] += 1
                        else:
                            trace_truncated = True
                if keep:
                    positions = []
                    for method in TRACE_METHODS:
                        for index, frame in enumerate(signatures):
                            if frame in ((TRACE_OWNER, method['name'], method['returns'], ''),
                                         (TRACE_OWNER, TRACE_OWNER + '.' + method['name'], method['returns'], '')):
                                positions.append(index)
                                break
                    positions += [index for index, frame in enumerate(signatures) if any(frame[1] in
                        (method['name'], TRACE_OWNER + '.' + method['name']) for method in TRACE_METHODS)]
                    positions = list(dict.fromkeys(positions))[:7]
                    positions = sorted(set(positions + [len(pending) - 1]))
                    trace_groups.append(dict(alias=pending[0]['alias'], interval=pending[0]['interval'],
                        frames=[signatures[index] for index in positions], outer=signatures[-1],
                        sourceLines=[pending[index]['sourceLine'] for index in positions],
                        framePositions=positions, frameCount=len(pending),
                        metadataRejected=group_metadata, metadataIds=metadata_ids[:8],
                        metadataIdsTruncated=len(metadata_ids) > 8))
                    trace_truncated = trace_truncated or len(positions) < len(pending) or len(metadata_ids) > 8
        pending, group_metadata = [], False
        metadata_ids = []

    for source_line, line in enumerate(text.splitlines(), 1):
        match = re.fullmatch(r'([^\s]+) -> ([^\s:]+):', line)
        if match:
            finish_group()
            owner = match[1]
            reader.require(owner not in classes, 'Duplicate original class in mapping')
            classes[owner] = match[2]
        elif line.lstrip().startswith('#'):
            # Preserve frame adjacency; unknown per-method metadata cannot prove identity.
            group_metadata = group_metadata or bool(pending)
            if _trace is not None and pending and owner == TRACE_OWNER and len(metadata_ids) < 9:
                match = re.search(r'"id"\s*:\s*"([A-Za-z0-9._-]{1,80})"', line)
                metadata_ids.append(match[1] if match else 'UNKNOWN')
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
            if _trace is not None and owner == TRACE_OWNER:
                row['sourceLine'] = source_line
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
    def target(method):
        key = (method['owner'], method['name'], method['returns'], ','.join(method['parameters']))
        mapped = sorted(names.get(key, set()))
        return dict(method, mappedOwner=classes.get(method['owner']), mappedNames=mapped,
            mappingStatus='CONFIRMED_OUTER_METHOD' if mapped else 'MISSING_OR_AMBIGUOUS_OUTER_MAPPING',
            descriptor='(' + ''.join(descriptor(p) for p in method['parameters']) + ')' + descriptor(method['returns']))
    if _trace is not None:
        fixed_targets = [target(method) for method in TRACE_METHODS]
        selected_samples = []
        for method in TRACE_METHODS:
            exact = [group for group in trace_groups if any(frame in
                ((TRACE_OWNER, method['name'], method['returns'], ''),
                 (TRACE_OWNER, TRACE_OWNER + '.' + method['name'], method['returns'], '')) for frame in group['frames'])]
            named = [group for group in trace_groups if any(frame[1] in
                (method['name'], TRACE_OWNER + '.' + method['name']) for frame in group['frames'])]
            for group in (exact or named)[:2]:
                if not any(group is prior for prior in selected_samples):
                    selected_samples.append(group)
        touched = set().union(*(path['aliases'] for path in trace_paths))
        alias_paths = {alias: dict(groups=0, metadataGroups=0, qualifiedOuterGroups=0) for alias in touched}
        for class_owner, alias, outer, metadata in groups:
            if class_owner == TRACE_OWNER and alias in alias_paths:
                path = alias_paths[alias]
                path['groups'] += 1
                path['metadataGroups'] += bool(metadata)
                path['qualifiedOuterGroups'] += '.' in outer[1]
        diagnoses = []
        for method, mapped, path in zip(TRACE_METHODS, fixed_targets, trace_paths):
            reasons = set()
            rejected = dict(ALIAS_MULTIPLE_OUTER_SIGNATURES=0, METADATA_REJECTED=0, QUALIFIED_OUTER_REJECTED=0,
                UNSUPPORTED_OWNER_METHOD_SYNTAX=0)
            accepted_aliases = 0
            for alias in path['outerAliases']:
                signatures, uncertain = residuals[(TRACE_OWNER, alias)]
                accepted_aliases += len(signatures) == 1 and not uncertain
                rejected['ALIAS_MULTIPLE_OUTER_SIGNATURES'] += len(signatures) != 1
                rejected['METADATA_REJECTED'] += alias_paths[alias]['metadataGroups'] > 0
                rejected['QUALIFIED_OUTER_REJECTED'] += alias_paths[alias]['qualifiedOuterGroups'] > 0
                rejected['UNSUPPORTED_OWNER_METHOD_SYNTAX'] += TRACE_OWNER in unsupported_owners
            if not mapped['mappedNames']:
                if not path['exactRows'] and not path['qualifiedRows']:
                    reasons.add('GETTER_SIGNATURE_MISMATCH' if path['sameNameRows'] else 'SIGNATURE_NOT_PARSED_IN_OWNER')
                elif not path['outerAliases']:
                    reasons.add('NON_OUTER_FRAME')
                reasons.update(reason for reason, count in rejected.items() if count)
                if TRACE_OWNER in unsupported_owners:
                    reasons.add('UNSUPPORTED_OWNER_METHOD_SYNTAX')
                if not reasons:
                    reasons.add('UNKNOWN_UNCERTAINTY')
            summary = {key: value for key, value in path.items() if key not in
                ('aliases', 'outerAliases', 'sampleGroups', 'mismatchSampleGroups')}
            summary.update(aliasCount=len(path['aliases']), outerCandidateAliasCount=len(path['outerAliases']),
                aliasClosureGroupCount=sum(alias_paths[alias]['groups'] for alias in path['aliases']),
                rejectedCandidateAliasCounts=rejected, mappedAliasCount=len(mapped['mappedNames']),
                acceptedOuterCandidateAliasCount=accepted_aliases,
                matchesExistingMappedNameCount=accepted_aliases == len(mapped['mappedNames']),
                fullCandidateScanComplete=True)
            diagnoses.append(dict(name=method['name'], reasons=sorted(reasons), mappedNames=mapped['mappedNames'],
                candidatePath=summary, observation='CONFIRMED_MAPPING_ONLY' if mapped['mappedNames'] else 'UNKNOWN'))
        _trace.update(owner=TRACE_OWNER, mappedOwner=classes.get(TRACE_OWNER), targets=fixed_targets,
            groups=selected_samples, methods=diagnoses, truncated=False, sampleTruncated=trace_truncated,
            fullCandidateScanComplete=True,
            unsupportedOwner=TRACE_OWNER in unsupported_owners)
    return [target(method) for method in methods]


def diagnostic_projection(trace):
    def frame_identity(frame):
        for method in TRACE_METHODS:
            if frame in ((TRACE_OWNER, method['name'], method['returns'], ''),
                         (TRACE_OWNER, TRACE_OWNER + '.' + method['name'], method['returns'], '')):
                return dict(method, qualified=frame[1] != method['name'])
        return dict(redacted=True, observation='UNKNOWN')
    aliases = list(dict.fromkeys(group['alias'] for group in trace['groups']))
    groups = [dict(aliasGroup=aliases.index(group['alias']) + 1, interval=group['interval'],
        frameCount=group['frameCount'], frames=[dict(frame_identity(frame), isOuter=group['framePositions'][index] == group['frameCount'] - 1)
            for index, frame in enumerate(group['frames'])],
        sampledFramePositions=group['framePositions'],
        outer=frame_identity(group['outer']), sourceLines=group['sourceLines'],
        metadataRejected=group['metadataRejected'], metadataIdCount=len(group['metadataIds']),
        metadataIdsRedacted=True, metadataIdsTruncated=group['metadataIdsTruncated']) for group in trace['groups']]
    methods = [dict(name=row['name'], reasons=row['reasons'], mappedNameCount=len(row['mappedNames']),
        candidatePath=row['candidatePath'], observation=row['observation']) for row in trace['methods']]
    return dict(owner=TRACE_OWNER, mappedOwnerPresent=trace['mappedOwner'] is not None, groups=groups,
        aliasGroupCount=len(aliases), methods=methods, truncated=trace['truncated'],
        sampleTruncated=trace['sampleTruncated'], fullCandidateScanComplete=trace['fullCandidateScanComplete'],
        groupScope='FIXED_SIGNATURE_PRIORITY_SAMPLES_FULL_RESIDUAL_SUMMARY',
        unsupportedOwner=trace['unsupportedOwner'])


def declaration_projection(headers, targets):
    reviewed = {method['name'] + {'int': '()I', 'androidx.compose.ui.geometry.Rect':
        '()Landroidx/compose/ui/geometry/Rect;', 'androidx.compose.ui.layout.LayoutInfo':
        '()Landroidx/compose/ui/layout/LayoutInfo;'}[method['returns']] for method in TRACE_METHODS}
    mapped = {name + target['descriptor'] for target in targets for name in target['mappedNames']}
    flags = {'public', 'private', 'protected', 'static', 'final', 'synchronized', 'native', 'abstract',
        'strictfp', 'declared-synchronized'}
    result = []
    for header in headers:
        parts = header.split()
        if len(parts) >= 2 and parts[0] == '.method' and parts[-1] in reviewed & mapped and set(parts[1:-1]) <= flags:
            result.append(header)
        else:
            result.append(dict(redacted=True, observation='UNKNOWN'))
    return result


def mapping_diagnostic(trace, smali):
    positives = definition_results(smali, trace['targets'][1:])
    proven = all(len(row['definitionCandidates']) == 1 for row in positives)
    result = dict(diagnosticOnly=True, accepted=False, compileAuthorized=False, observation='UNKNOWN',
        mapping=diagnostic_projection(trace), positiveControls=[dict(name=row['name'],
            mappedNameCount=len(row['mappedNames']), definitionCandidateCount=len(row['definitionCandidates'])) for row in positives],
        declarationSource='WITHHELD_NO_POSITIVE_IDENTITY',
        declarationHeaders=[], truncated=trace['truncated'])
    if proven and trace['mappedOwner']:
        expected = 'L' + trace['mappedOwner'].replace('.', '/') + ';'
        classes = re.findall(r'^\.class\s+[^\n]*?\s(L[^\s;]+;)\s*$', smali, re.MULTILINE)
        if classes == [expected]:
            headers = re.findall(r'^\s*(\.method\s+[^\r\n]+)$', smali, re.MULTILINE)
            result.update(declarationSource='TWO_POSITIVE_MAPPED_DEFINITIONS_AND_EXACT_CLASS',
                smaliSha256=hashlib.sha256(smali.encode()).hexdigest(), declarationCount=len(headers),
                declarationHeaders=declaration_projection([head for head in headers[:128] if len(head.encode()) <= 512], trace['targets']),
                declarationHeadersRedacted=True,
                truncated=result['truncated'] or len(headers) > 128 or any(len(h.encode()) > 512 for h in headers))
    if len(json.dumps(result).encode()) > TRACE_BYTES:
        result['mapping']['groups'] = []
        result['mapping']['sampleTruncated'] = True
        result['declarationHeaders'] = []
        result['detailOutputTruncated'] = True
    if len(json.dumps(result).encode()) > TRACE_BYTES or reader.sensitive_text(json.dumps(result)):
        return dict(diagnosticOnly=True, accepted=False, compileAuthorized=False, observation='UNKNOWN',
            truncated=True, reason='TRACE_SIZE_OR_SAFETY_LIMIT', declarationHeaders=[])
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
    trace = {}
    targets = mapping_targets(paths[needed[1]].read_text(), CONTRACT['methods'], trace)
    results = []
    diagnostic_owner = trace['mappedOwner'] if all(row['mappedNames'] for row in trace['targets'][1:]) else None
    owners = {row['mappedOwner'] for row in targets if row['mappedOwner']}
    if diagnostic_owner:
        owners.add(diagnostic_owner)
    diagnostic_smali = ''
    for owner in sorted(owners):
        smali = bounded_command([str(analyzer), 'dex', 'code', '--class', owner, str(paths[needed[0]])], 20, deadline, private)
        if owner == diagnostic_owner:
            diagnostic_smali = smali
        results += definition_results(smali, [row for row in targets if row['mappedOwner'] == owner])
    results += [dict(row, definitionCandidates=[]) for row in targets if not row['mappedOwner']]
    found = len(results) == len(CONTRACT['methods']) and all(len(row['definitionCandidates']) == 1 for row in results)
    result.update(status='REQUIRED_DEFINITIONS_FOUND_COMPILE_STILL_REQUIRED' if found else 'REQUIRED_DEFINITION_MISSING_OR_REWRITTEN',
        definitionsChecked=True, allRequiredDefinitionsFound=found, methods=results,
        sdkToolIdentity=identity, signatureVerifiedAtRuntime=True, mappingTrace=mapping_diagnostic(trace, diagnostic_smali),
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
        reader.require(len(payload) <= 61440 and not reader.sensitive_text(payload.decode()), 'Whitelist audit result output rejected')
        (out / 'API-WHITELIST.json').write_bytes(payload)
        receipt.update(completed=True, resultSha256=hashlib.sha256(payload).hexdigest(),
            outputScope='Only fixed whitelist signatures and status; private APK/mapping/smali never uploaded')
        code = 0
    except Exception as error:
        receipt['failure'] = dict(type=type(error).__name__, message=str(error) if type(error) is ValueError else 'Audit failed; raw exception withheld')
    finally:
        receipt_payload = (json.dumps(receipt, indent=2) + '\n').encode()
        if len(receipt_payload) > 4096 or reader.sensitive_text(receipt_payload.decode()):
            receipt_payload = b'{"completed":false,"accepted":false,"failure":"RECEIPT_SIZE_OR_SAFETY_LIMIT"}\n'
        (out / 'RECEIPT.json').write_bytes(receipt_payload)
    return code


if __name__ == '__main__':
    raise SystemExit(main())
