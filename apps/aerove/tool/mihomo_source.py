"""Prepare the pinned official Mihomo tree with Aerove's offline patch series."""

from contextlib import contextmanager
from dataclasses import dataclass
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import time


OFFICIAL_URL = 'https://github.com/MetaCubeX/mihomo.git'
MANIFEST = 'core/mihomo-upstream.json'
METADATA = '.aerove-mihomo-source.json'
LOCK_TIMEOUT = 15


class SourceError(Exception):
    pass


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()


def _pairs(items):
    result = {}
    for key, value in items:
        if key in result:
            raise SourceError(f'Duplicate JSON key: {key}')
        result[key] = value
    return result


def read_json(path):
    try:
        return json.loads(path.read_bytes(), object_pairs_hook=_pairs)
    except (ValueError, OSError) as error:
        raise SourceError(f'Cannot read {path}: {error}') from error


def safe_name(name):
    if not isinstance(name, str) or not name or '\\' in name or ':' in name or '\0' in name:
        raise SourceError(f'Unsafe source path: {name!r}')
    parts = name.split('/')
    if any(p in ('', '.', '..') or p.rstrip(' .') != p or p.casefold() == '.git' for p in parts):
        raise SourceError(f'Unsafe source path: {name!r}')
    if any(p.split('.')[0].upper() in {'CON', 'PRN', 'AUX', 'NUL',
           *(f'COM{i}' for i in range(1, 10)), *(f'LPT{i}' for i in range(1, 10))} for p in parts):
        raise SourceError(f'Nonportable source path: {name!r}')
    return name


def reparse(path):
    info = path.lstat()
    return stat.S_ISLNK(info.st_mode) or bool(getattr(info, 'st_file_attributes', 0) & 0x400)


def confined(root, relative):
    safe_name(relative)
    current = root
    for part in relative.split('/'):
        current = current / part
        if current.exists() or current.is_symlink():
            if reparse(current):
                raise SourceError(f'Linked filesystem path is not allowed here: {current}')
    return current


def git(repo, *arguments, data=None, index=None, work_tree=None):
    env = os.environ.copy()
    for name in ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR',
                 'GIT_OBJECT_DIRECTORY', 'GIT_ALTERNATE_OBJECT_DIRECTORIES'):
        env.pop(name, None)
    env.update(GIT_OPTIONAL_LOCKS='0', GIT_NO_LAZY_FETCH='1', GIT_TERMINAL_PROMPT='0')
    if index is not None:
        env['GIT_INDEX_FILE'] = str(index)
    command = ['git', '-c', 'protocol.allow=never']
    if arguments[0] != 'status':
        command += ['-c', 'core.autocrlf=false', '-c', 'core.safecrlf=false', '-c', 'core.symlinks=true']
    command += ['-C', str(work_tree if work_tree is not None else repo)]
    if work_tree is not None:
        git_dir = git(repo, 'rev-parse', '--absolute-git-dir').decode().strip()
        command += [f'--git-dir={git_dir}', f'--work-tree={work_tree}']
    result = subprocess.run(command + list(arguments), input=data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, env=env, check=False)
    if result.returncode:
        raise SourceError(f'git {arguments[0]} failed: {result.stderr.decode(errors="replace").strip()}')
    return result.stdout


@dataclass
class Inputs:
    root: Path
    source: Path
    manifest: dict
    patches: list
    identity: dict

    @property
    def fingerprint(self):
        return sha256(canonical(self.identity))


def load_inputs(root):
    root = Path(root).resolve(strict=True)
    manifest_path = confined(root, MANIFEST)
    manifest = read_json(manifest_path)
    if not isinstance(manifest, dict) or manifest.get('schema') != 1:
        raise SourceError('Unsupported Mihomo source manifest schema')
    upstream = manifest.get('upstream', {})
    if upstream.get('url') != OFFICIAL_URL:
        raise SourceError('Mihomo source must use the canonical official HTTPS URL')
    for value in (upstream.get('commit'), upstream.get('tree'), manifest.get('result_tree')):
        if not isinstance(value, str) or not re.fullmatch('[0-9a-f]{40}', value):
            raise SourceError('Exact SHA-1 source commit and tree pins are required')
    if not isinstance(upstream.get('version'), str) or not re.fullmatch(r'v\d+\.\d+\.\d+', upstream['version']):
        raise SourceError('An exact stable upstream version is required')
    specs = manifest.get('patches')
    if not isinstance(specs, list) or not specs:
        raise SourceError('A nonempty ordered patch series is required')
    patches, seen = [], set()
    for spec in specs:
        name = safe_name(spec['path'])
        if not name.startswith('core/patches/') or not name.endswith('.patch') or name in seen:
            raise SourceError(f'Invalid or duplicate patch path: {name}')
        seen.add(name)
        data = confined(root, name).read_bytes()
        if sha256(data) != spec.get('sha256'):
            raise SourceError(f'Patch SHA256 mismatch: {name}')
        patches.append((name, data))
    source = confined(root, 'core/Clash.Meta')
    if git(source, 'remote', 'get-url', 'origin').decode().strip() != OFFICIAL_URL:
        raise SourceError('Mihomo submodule origin is not the canonical official HTTPS URL')
    if git(source, 'rev-parse', 'HEAD').decode().strip() != upstream['commit']:
        raise SourceError('Mihomo submodule HEAD differs from the official source pin')
    if git(source, 'rev-parse', 'HEAD^{tree}').decode().strip() != upstream['tree']:
        raise SourceError('Official source tree pin mismatch')
    if git(source, 'status', '--porcelain', '--untracked-files=all', '--ignore-submodules=none'):
        raise SourceError('Official Mihomo submodule must remain pristine')
    merge_head = git(source, 'rev-parse', '--git-path', 'MERGE_HEAD').decode().strip()
    if (source / merge_head).exists():
        raise SourceError('Official Mihomo submodule has an unfinished merge')
    input_files = ['tool/mihomo_source.py', MANIFEST] + [name for name, _ in patches]
    identity = {'upstream': upstream, 'result_tree': manifest['result_tree'],
                'manifest_sha256': sha256(manifest_path.read_bytes()),
                'tool_sha256': sha256(Path(__file__).read_bytes()),
                'patches': manifest['patches'], 'input_files': input_files}
    return Inputs(root, source, manifest, patches, identity)


def validate_links(modes, links):
    folded = set()
    resolved = {}
    for name, mode in modes.items():
        safe_name(name)
        if name == METADATA or mode not in ('100644', '100755', '120000'):
            raise SourceError(f'Unsupported tree entry: {mode} {name}')
        if name.casefold() in folded:
            raise SourceError(f'Case-colliding source path: {name}')
        folded.add(name.casefold())
        for parent in PurePosixPath(name).parents:
            if parent.as_posix() in modes:
                raise SourceError(f'File or symlink used as a directory: {name}')
    for name, target in links.items():
        visited = {name}
        current = name
        while current in links:
            target = links[current]
            if not target or target.startswith('/') or '\\' in target or ':' in target or '\0' in target:
                raise SourceError(f'Unsafe symlink target: {name} -> {target!r}')
            parts = current.split('/')[:-1]
            target_parts = target.split('/')
            for position, part in enumerate(target_parts):
                if part == '..':
                    if not parts:
                        raise SourceError(f'Symlink escapes source root: {name}')
                    parts.pop()
                elif part not in ('', '.'):
                    safe_name(part)
                    parts.append(part)
                if '/'.join(parts) in links and position < len(target_parts) - 1:
                    raise SourceError(f'Symlink target traverses another link: {name}')
            current = '/'.join(parts)
            if current == METADATA or current in visited:
                raise SourceError(f'Cyclic or reserved symlink target: {name}')
            visited.add(current)
            for parent in PurePosixPath(current).parents:
                if parent.as_posix() in links:
                    raise SourceError(f'Symlink target traverses another link: {name}')
        resolved[name] = current
    return resolved


def tree_entries(source, tree):
    modes, links = {}, {}
    for record in git(source, 'ls-tree', '-r', '-z', tree).split(b'\0'):
        if not record:
            continue
        header, name_bytes = record.split(b'\t', 1)
        mode, kind, oid = header.decode().split()
        name = name_bytes.decode('utf-8')
        if kind != 'blob':
            raise SourceError(f'Nested submodule or unsupported object: {name}')
        modes[name] = mode
        if mode == '120000':
            links[name] = git(source, 'cat-file', 'blob', oid).decode('utf-8')
    validate_links(modes, links)
    return modes


def content_tree(directory, modes):
    entries, links, content = {}, {}, []
    for base, directories, files in os.walk(directory, followlinks=False):
        parent = Path(base)
        for name in list(directories):
            path = parent / name
            if reparse(path):
                directories.remove(name)
                files.append(name)
            elif not any(p.startswith(path.relative_to(directory).as_posix() + '/') for p in modes):
                raise SourceError(f'Unexpected cache directory: {path}')
        for name in files:
            path = parent / name
            relative = path.relative_to(directory).as_posix()
            if relative == METADATA:
                if reparse(path) or not path.is_file():
                    raise SourceError('Linked or invalid source metadata')
                continue
            safe_name(relative)
            mode = modes.get(relative)
            info = path.lstat()
            if stat.S_ISLNK(info.st_mode):
                if mode != '120000':
                    raise SourceError(f'Unexpected cache symlink: {relative}')
                links[relative] = os.readlink(path)
                data = links[relative].encode('utf-8')
            else:
                if reparse(path) or not stat.S_ISREG(info.st_mode) or mode not in ('100644', '100755'):
                    raise SourceError(f'Unexpected cache file: {relative}')
                if os.name != 'nt' and bool(info.st_mode & 0o111) != (mode == '100755'):
                    raise SourceError(f'Cache executable mode mismatch: {relative}')
                data = path.read_bytes()
            oid = hashlib.sha1(f'blob {len(data)}\0'.encode() + data).digest()
            entries[relative] = (mode, oid)
            content.append((relative, mode, sha256(data)))
    if set(entries) != set(modes):
        raise SourceError('Prepared source file inventory differs from the expected tree')
    validate_links(modes, links)
    tree = {}
    for name, entry in entries.items():
        node = tree
        parts = name.split('/')
        for part in parts[:-1]:
            node = node.setdefault(part, {})
        node[parts[-1]] = entry

    def digest(node):
        rows = []
        for name, value in node.items():
            if isinstance(value, dict):
                rows.append((name.encode() + b'/', b'40000 ' + name.encode() + b'\0' + digest(value)))
            else:
                mode, oid = value
                rows.append((name.encode(), mode.encode() + b' ' + name.encode() + b'\0' + oid))
        data = b''.join(row for _, row in sorted(rows))
        return hashlib.sha1(f'tree {len(data)}\0'.encode() + data).digest()

    return digest(tree).hex(), sha256(canonical(sorted(content)))


@contextmanager
def source_lock(root, readonly=False):
    path = confined(root, 'core/.mihomo.lock')
    if readonly and not path.exists():
        raise SourceError('Mihomo source has not been prepared')
    with path.open('rb' if readonly else 'a+b') as handle:
        if not readonly and path.stat().st_size == 0:
            handle.write(b'\0')
            handle.flush()
        deadline = time.monotonic() + LOCK_TIMEOUT
        while True:
            try:
                if os.name == 'nt':
                    import msvcrt
                    handle.seek(0)
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBRLCK if readonly else msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle, (fcntl.LOCK_SH if readonly else fcntl.LOCK_EX) | fcntl.LOCK_NB)
                break
            except OSError as error:
                if time.monotonic() >= deadline:
                    raise SourceError('Mihomo preparation is busy; retry after the active writer completes') from error
                time.sleep(0.05)
        try:
            yield
        finally:
            if os.name == 'nt':
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle, fcntl.LOCK_UN)


def inspect_cache(inputs, output):
    if not output.is_dir() or reparse(output):
        raise SourceError('Prepared Mihomo source is missing or linked')
    metadata_path = output / METADATA
    if not metadata_path.exists() or reparse(metadata_path):
        raise SourceError('Prepared source metadata is missing or linked')
    metadata = read_json(metadata_path)
    if not isinstance(metadata, dict) or metadata.get('schema') != 1 or metadata.get('inputs') != inputs.identity:
        raise SourceError('Prepared source inputs have changed; run prepare')
    modes = metadata.get('file_modes')
    if not isinstance(modes, dict) or not all(isinstance(name, str) and isinstance(mode, str) for name, mode in modes.items()):
        raise SourceError('Prepared source mode inventory is invalid')
    tree, content = content_tree(output, modes)
    if tree != inputs.manifest['result_tree'] or tree != metadata.get('result_tree') or content != metadata.get('content_sha256'):
        raise SourceError('Prepared source content integrity check failed')
    return {'source_dir': str(output), 'input_fingerprint': inputs.fingerprint,
            'input_files': inputs.identity['input_files'], 'upstream': inputs.identity['upstream'],
            'result_tree': tree, 'content_sha256': content, 'file_count': len(metadata['file_modes'])}


def unpack_archive(inputs, stage):
    base_modes = tree_entries(inputs.source, inputs.manifest['upstream']['tree'])
    archive = git(inputs.source, 'archive', '--format=tar', inputs.manifest['upstream']['commit'])
    pending_links = {}
    with tarfile.open(fileobj=io.BytesIO(archive), mode='r:') as bundle:
        for member in bundle:
            name = member.name.rstrip('/') if member.isdir() else member.name
            safe_name(name)
            target = confined(stage, name)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            mode = base_modes.get(name)
            if mode is None or not (member.isfile() or member.issym()):
                raise SourceError(f'Unexpected archive entry: {name}')
            target.parent.mkdir(parents=True, exist_ok=True)
            if member.issym():
                if mode != '120000':
                    raise SourceError(f'Unexpected archive link: {name}')
                pending_links[name] = member.linkname
            else:
                if mode == '120000':
                    raise SourceError(f'Archive lost symlink semantics: {name}')
                with bundle.extractfile(member) as source, target.open('xb') as destination:
                    shutil.copyfileobj(source, destination)
                target.chmod(0o755 if mode == '100755' else 0o644)
    destinations = validate_links(base_modes, pending_links)
    for name, link in pending_links.items():
        try:
            os.symlink(link, stage / name, target_is_directory=(stage / destinations[name]).is_dir())
        except OSError as error:
            raise SourceError('Cannot preserve upstream symlinks; enable OS symlink support') from error
    tree, _ = content_tree(stage, base_modes)
    if tree != inputs.manifest['upstream']['tree']:
        raise SourceError('Official archive differs from its complete pinned Git tree')


def publish(stage, output, previous):
    if previous.exists():
        marker = previous / METADATA
        if reparse(previous) or not marker.is_file() or reparse(marker) or read_json(marker).get('schema') != 1:
            raise SourceError(f'Refusing to remove unowned backup: {previous}')
        shutil.rmtree(previous)
    moved = False
    try:
        if output.exists():
            if reparse(output) or not (output / METADATA).is_file():
                raise SourceError(f'Refusing to replace an unowned source directory: {output}')
            os.replace(output, previous)
            moved = True
        os.replace(stage, output)
    except BaseException:
        if moved and not output.exists():
            os.replace(previous, output)
        raise


def prepare(root):
    root = Path(root).resolve(strict=True)
    with source_lock(root):
        inputs = load_inputs(root)
        output = confined(root, 'core/.mihomo')
        previous = confined(root, 'core/.mihomo.previous')
        if not output.exists() and previous.exists():
            if not (previous / METADATA).is_file():
                raise SourceError('Interrupted publication has an unowned backup')
            os.replace(previous, output)
        if output.exists():
            try:
                return dict(inspect_cache(inputs, output), reused=True)
            except SourceError:
                pass
        with tempfile.TemporaryDirectory(prefix='.mihomo-prepare-', dir=root / 'core') as temporary:
            temporary = Path(temporary)
            index = temporary / 'index'
            git(inputs.source, 'read-tree', inputs.manifest['upstream']['commit'], index=index)
            for _, patch in inputs.patches:
                git(inputs.source, 'apply', '--cached', '--binary', '--whitespace=nowarn', '-', data=patch, index=index)
            tree = git(inputs.source, 'write-tree', index=index).decode().strip()
            if tree != inputs.manifest['result_tree']:
                raise SourceError(f'Replayed tree mismatch: expected {inputs.manifest["result_tree"]}, got {tree}')
            modes = tree_entries(inputs.source, tree)
            stage = temporary / 'source'
            stage.mkdir()
            unpack_archive(inputs, stage)
            for _, patch in inputs.patches:
                git(inputs.source, 'apply', '--binary', '--whitespace=nowarn', '-', data=patch, index=index, work_tree=stage)
            actual, content = content_tree(stage, modes)
            if actual != tree:
                raise SourceError('Replayed filesystem differs from the expected patched tree')
            metadata = {'schema': 1, 'inputs': inputs.identity, 'result_tree': tree,
                        'content_sha256': content, 'file_modes': modes}
            (stage / METADATA).write_bytes(canonical(metadata) + b'\n')
            if load_inputs(root).fingerprint != inputs.fingerprint:
                raise SourceError('Source inputs changed during preparation; old output preserved')
            publish(stage, output, previous)
            return dict(inspect_cache(inputs, output), reused=False)


def verify(root):
    root = Path(root).resolve(strict=True)
    with source_lock(root, readonly=True):
        return inspect_cache(load_inputs(root), confined(root, 'core/.mihomo'))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('prepare', 'verify'))
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    arguments = parser.parse_args(argv)
    try:
        result = prepare(arguments.root) if arguments.command == 'prepare' else verify(arguments.root)
    except (SourceError, OSError, ValueError, KeyError, TypeError) as error:
        print(f'Mihomo source error: {error}', file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    sys.exit(main())
