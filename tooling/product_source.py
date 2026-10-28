"""Conservative product input equivalence using only immutable Git objects."""

import hashlib
from pathlib import Path
import re
import stat
import subprocess
import zipfile


class ProjectionError(ValueError):
    pass


FACT_DOCUMENTS = {
    'README.md', 'docs/README.md',
    'apps/aerove/README.md', 'apps/aerove-lite/README.md', 'docs/CHANGELOG_Aerove.md',
    'docs/aerove/CHANGELOG.md', 'docs/aerove-lite/CHANGELOG.md',
    'docs/development/upstreams.md',
}
PUBLISHERS = {
    'apps/aerove/tool/aerove_artifact.py',
    'apps/aerove/tool/aerove_release_notes.py',
    'apps/aerove/tool/test_aerove_release_notes.py',
    'apps/aerove/tool/test_aerove_artifact.py',
    'apps/aerove/tool/test_aerove_workflow.py',
    'apps/aerove-lite/tool/lite_release.py',
    'apps/aerove-lite/tool/test_lite_release.py',
    'tooling/product_source.py', 'tooling/test_product_source.py',
}
WORKFLOWS = {
    'aerove': '.github/workflows/xclash-android.yaml',
    'aerove-lite': '.github/workflows/aerove-lite.yaml',
}
CROSS_PRODUCT_INPUTS = {'aerove': set(), 'aerove-lite': {'apps/aerove/assets/data/ASN.mmdb'}}
ACCEPTED_BUILD_INPUT = (
    '      accepted_build_run:\n'
    '        description: Explicit fully accepted original build run to promote without rebuilding\n'
    '        type: string\n'
    "        default: ''\n"
)
PUBLISH_DEFAULT = (
    '      publish_release:\n'
    '        description: Publish the verified APK to Aerove Releases\n'
    '        type: boolean\n'
    '        default: true\n'
)
BUILD_MODE = "inputs.accepted_build_run == '' && (!inputs.publish_release || github.event_name != 'workflow_dispatch')"
STANDARD_BUILD_MODE = "!(github.event_name == 'push' && github.ref == 'refs/heads/main') && " + BUILD_MODE
PROMOTION_REQUEST_JOB = '''  promotion_request:
    runs-on: ubuntu-24.04
    timeout-minutes: 2
    env:
      ACCEPTED_BUILD_RUN: ${{ inputs.accepted_build_run }}
      PUBLISH_RELEASE: ${{ inputs.publish_release }}
      BUILD_APK: ${{ inputs.build_apk }}
      PREPARE_SOURCES: ${{ inputs.prepare_sources }}
      VERIFICATION_RECHECK: ${{ inputs.verification_recheck }}
      ORIGINAL_BUILD_RUN: ${{ inputs.original_build_run }}
      DIAGNOSTIC_TARGET: ${{ inputs.diagnostic_target }}
      MEOW_HOST_ONLY: ${{ inputs.meow_host_only }}
      QA84_ONLY: ${{ inputs.qa84_only }}
      QA79_ONLY: ${{ inputs.qa79_only }}
      CLIENT_PREFLIGHT: ${{ inputs.client_preflight }}
      NATIVE_PREFLIGHT: ${{ inputs.native_preflight }}
    steps:
      - name: Reject ambiguous build and promotion requests before any producer
        working-directory: ${{ github.workspace }}
        run: |
          python3 - <<'PY'
          import os, re
          e = os.environ
          accepted, publish = e.get('ACCEPTED_BUILD_RUN', ''), e.get('PUBLISH_RELEASE') == 'true'
          if bool(accepted) != publish:
              raise SystemExit('Publish requires an explicit accepted_build_run; accepted_build_run cannot start a build')
          if accepted:
              if not re.fullmatch(r'[1-9][0-9]*', accepted):
                  raise SystemExit('accepted_build_run must be a positive original run ID')
              if e['GITHUB_EVENT_NAME'] != 'workflow_dispatch' or e['GITHUB_REF'] != 'refs/heads/main':
                  raise SystemExit('Promotion requires an explicit main workflow dispatch')
              flags = ('BUILD_APK', 'PREPARE_SOURCES', 'VERIFICATION_RECHECK', 'MEOW_HOST_ONLY', 'QA84_ONLY', 'QA79_ONLY', 'CLIENT_PREFLIGHT', 'NATIVE_PREFLIGHT')
              if any(e.get(key) == 'true' for key in flags) or e.get('DIAGNOSTIC_TARGET', '') not in ('', 'none') or e.get('ORIGINAL_BUILD_RUN'):
                  raise SystemExit('Promotion cannot build, recheck, prepare or diagnose')
          elif e.get('ORIGINAL_BUILD_RUN') and e.get('VERIFICATION_RECHECK') != 'true':
              raise SystemExit('original_build_run belongs only to verification_recheck')
          PY

'''


def _git(root, *args):
    result = subprocess.run(['git', *args], cwd=root, check=True, capture_output=True)
    return result.stdout


def _revision(root, source):
    if not isinstance(source, str) or not re.fullmatch('[0-9a-f]{40}', source):
        raise ProjectionError('A full lowercase commit SHA is required')
    if _git(root, 'rev-parse', source + '^{commit}').decode().strip() != source:
        raise ProjectionError('Source is not an exact commit object')
    return _git(root, 'rev-parse', source + '^{tree}').decode().strip()


def _entries(root, source):
    entries = {}
    for entry in _git(root, 'ls-tree', '-r', '-z', '--full-tree', source).split(b'\0'):
        if not entry:
            continue
        identity, raw_path = entry.split(b'\t', 1)
        path = raw_path.decode('utf-8', errors='strict')
        mode, kind, blob = identity.decode('ascii').split(' ')
        if path in entries:
            raise ProjectionError('Duplicate Git tree path')
        entries[path] = dict(mode=mode, type=kind, blob=blob)
    return entries


def _workflow_parts(contents):
    text = contents.decode('utf-8', errors='strict')
    sections = re.split(r'(?m)^(?=[^ \t\r\n#][^\n]*:)', text)
    matches = [part for part in sections if part.startswith('jobs:\n')]
    if len(matches) != 1:
        raise ProjectionError('Expected exactly one literal jobs mapping')
    body = matches[0]
    pieces = re.split(r'(?m)^(?=  [A-Za-z_][A-Za-z0-9_-]*:\n)', body)
    if pieces.pop(0) != 'jobs:\n':
        raise ProjectionError('Ambiguous workflow jobs mapping')
    jobs = {}
    for piece in pieces:
        name = piece.split(':', 1)[0].strip()
        if name in jobs:
            raise ProjectionError('Duplicate workflow job')
        jobs[name] = piece
    return ''.join(part for part in sections if part is not body), jobs


def workflow_equivalent(before, after):
    old_header, old_jobs = _workflow_parts(before)
    new_header, new_jobs = _workflow_parts(after)
    if old_header != new_header:
        normalized = new_header
        if ACCEPTED_BUILD_INPUT not in old_header and normalized.count(ACCEPTED_BUILD_INPUT) == 1:
            normalized = normalized.replace(ACCEPTED_BUILD_INPUT, '')
        safe_default = PUBLISH_DEFAULT.replace('default: true', 'default: false')
        if old_header.count(PUBLISH_DEFAULT) == 1 and normalized.count(safe_default) == 1:
            normalized = normalized.replace(safe_default, PUBLISH_DEFAULT)
        if normalized != old_header:
            raise ProjectionError('Workflow header/toolchain/permissions/inputs changed')
    if 'promotion_request' not in old_jobs:
        if new_jobs.pop('promotion_request', None) != PROMOTION_REQUEST_JOB:
            raise ProjectionError('Promotion request admission is not the fixed reviewed job')
    elif old_jobs['promotion_request'] != new_jobs.get('promotion_request'):
        raise ProjectionError('Promotion request admission changed')
    if old_jobs.keys() != new_jobs.keys() or not {'reuse', 'release'} <= old_jobs.keys():
        raise ProjectionError('Workflow job set changed')
    for name in old_jobs.keys() - {'reuse', 'release'}:
        current = new_jobs[name]
        if old_jobs[name] != current:
            for mode in (STANDARD_BUILD_MODE, BUILD_MODE):
                expression = re.escape(mode)
                wrapped = re.compile(r'(?m)^    if: \$\{\{ ' + expression + r' && \(([^\n]+)\) \}\}\n')
                restored, count = wrapped.subn(r'    if: ${{ \1 }}\n', current)
                if count:
                    current = restored
                    break
                line = '    if: ${{ ' + mode + ' }}\n'
                if line in current:
                    current = current.replace(line, '', 1)
                    break
        if old_jobs[name] != current:
            raise ProjectionError(f'Protected producer/verification job changed: {name}')
    return sorted(old_jobs.keys() - {'reuse', 'release'})


def equivalent(*, root, product, build_source, release_source):
    if product not in WORKFLOWS:
        raise ProjectionError('Unknown product')
    root = Path(root)
    build_tree = _revision(root, build_source)
    release_tree = _revision(root, release_source)
    before, after = _entries(root, build_source), _entries(root, release_source)
    other = 'aerove-lite' if product == 'aerove' else 'aerove'
    changes = []
    for path in sorted(before.keys() | after.keys()):
        old, new = before.get(path), after.get(path)
        if old == new:
            continue
        for value in (old, new):
            if value is not None and (value['type'] != 'blob' or value['mode'] not in ('100644', '100755')):
                raise ProjectionError(f'Changed symlink/submodule/unknown mode: {path}')
        if old and new and old['mode'] != new['mode']:
            raise ProjectionError(f'File mode changed: {path}')
        if path.rsplit('/', 1)[-1] in ('AGENTS.md', 'CLAUDE.md'):
            raise ProjectionError(f'Product/workspace instructions changed: {path}')
        if path in CROSS_PRODUCT_INPUTS[product]:
            raise ProjectionError(f'Cross-product acceptance input changed: {path}')
        category = None
        if path.startswith(f'apps/{other}/') or path == WORKFLOWS[other]:
            category = 'otherProduct'
        elif path in FACT_DOCUMENTS:
            category = 'factDocument'
        elif path in PUBLISHERS:
            category = 'publisherBootstrap'
        elif path == WORKFLOWS[product] and old and new:
            protected = workflow_equivalent(_git(root, 'cat-file', 'blob', old['blob']),
                                            _git(root, 'cat-file', 'blob', new['blob']))
            category = 'publisherWorkflow'
        if category is None:
            raise ProjectionError(f'Product/build/acceptance input changed: {path}')
        if category != 'otherProduct' and any(value and value['mode'] != '100644' for value in (old, new)):
            raise ProjectionError(f'Exception requires regular non-executable files: {path}')
        row = dict(path=path, before=old, after=new, reason=category)
        if category == 'publisherWorkflow':
            row['unchangedJobs'] = protected
        changes.append(row)
    protected = [f"{path}\0{value['mode']}\0{value['type']}\0{value['blob']}\n"
                 for path, value in sorted(before.items())
                 if (not path.startswith(f'apps/{other}/') or path in CROSS_PRODUCT_INPUTS[product])
                 and path not in FACT_DOCUMENTS | PUBLISHERS
                 and path not in WORKFLOWS.values()]
    return dict(schema=1, product=product, build_source=build_source, build_tree=build_tree,
                release_source=release_source, release_tree=release_tree, changed=changes,
                protected_inputs_sha256=hashlib.sha256(''.join(protected).encode()).hexdigest())


def bind_archive(archive, directory, artifact):
    archive, directory = Path(archive), Path(directory)
    digest = artifact.get('digest', '')
    with archive.open('rb') as stream:
        actual_digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if (not isinstance(digest, str) or not re.fullmatch('sha256:[0-9a-f]{64}', digest)
            or archive.stat().st_size != artifact.get('size_in_bytes')
            or actual_digest != digest[7:]):
        raise ProjectionError('Original GitHub artifact ZIP size/digest mismatch')
    result = {}
    with zipfile.ZipFile(archive) as package:
        names = set()
        for entry in package.infolist():
            path = entry.filename
            parts = path.rstrip('/').split('/')
            mode = entry.external_attr >> 16
            if (not path or path in names or '\\' in path or path.startswith('/')
                    or any(part in ('', '.', '..') or ':' in part for part in parts)
                    or (mode and stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR))):
                raise ProjectionError('Unsafe or duplicate original artifact ZIP entry')
            names.add(path)
            if entry.is_dir():
                continue
            target = directory.joinpath(*parts)
            if any(parent.is_symlink() for parent in (target, *target.parents)) or not target.is_file():
                raise ProjectionError(f'Missing/non-regular downloaded payload: {path}')
            with package.open(entry) as stream, target.open('rb') as local:
                original_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
                local_hash = hashlib.file_digest(local, 'sha256').hexdigest()
            if entry.file_size != target.stat().st_size or local_hash != original_hash:
                raise ProjectionError(f'Original artifact payload bytes differ: {path}')
            result[path] = dict(bytes=entry.file_size, sha256=original_hash)
    actual = {path.relative_to(directory).as_posix() for path in directory.rglob('*') if path.is_file()}
    if not result or actual != result.keys():
        raise ProjectionError('Downloaded payload inventory differs from original artifact ZIP')
    return result
