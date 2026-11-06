"""Fixed run277 consumer evidence only; never build, accept or publish an APK."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import zipfile
import xml.etree.ElementTree as ET

SOURCE = '1cd4f0a9600a38ceeef93282455f70d3418485d7'
TREE = '81dab492327b70194de241b6eacc76e26f5c1131'
RUN = 42126686916
REPO = 'IvanSolis1989/Aerove'
PINS = (
    (13462926385, 1126036, 'd36c856b5ac722dd335df29a534ff7439f7a3d70683dfd92bc2f42fa9d9f690f',
     'AEROVE-Lite-Kotlin-verification', 'verify', 'kotlin'),
    (13462088169, 150282, '135fac4f6d7a000edb027fbe00b37b15cd9d70ec407e4c5b98b6ca9b5001c68b',
     'AEROVE-Lite-Flutter-host-compilation',
     'Shared Flutter client verification and Android host compilation (no APK)', 'flutter'),
)
TESTS = {
    'GeoOrigin95Test': 'actualTaskProducerIsStrictlyConsumedPersistedAndRebasedWithoutCoveringProfileIntent',
    'MeowDns99StageTest': 'actualGetProfileDefaultsAndSingleJsOutputPassCompleteStageForBothRootIpv6States',
    'MeowTunReconciliation90Test': 'actualTaskYamlTraversesSameSealerAndStageWithoutGrantingStartAuthority',
}


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, text=True).strip()


def save(path, value):
    Path(path).write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def prepare(root, out):
    require(git(root, 'rev-parse', 'HEAD') == SOURCE and git(root, 'rev-parse', 'HEAD^{tree}') == TREE,
            'Fixed App checkout identity mismatch')
    # Reuse the original repository's authenticated API transport, never artifact code.
    import sys
    sys.path.insert(0, str(root / 'apps/aerove-lite/tool'))
    from lite_release import _gh_json, _gh_binary
    run = _gh_json(f'repos/{REPO}/actions/runs/{RUN}')
    require(run['id'] == RUN and run['run_number'] == 277 and run['run_attempt'] == 1
            and run['head_sha'] == SOURCE and run['path'] == '.github/workflows/aerove-lite.yaml'
            and run['repository']['full_name'] == REPO and run['head_repository']['full_name'] == REPO,
            'Original run277 identity mismatch')
    page = _gh_json(f'repos/{REPO}/actions/runs/{RUN}/attempts/1/jobs?per_page=100')
    require(page['total_count'] == len(page['jobs']), 'Incomplete original producer job list')
    records = []
    for aid, size, expected, name, producer, label in PINS:
        row = _gh_json(f'repos/{REPO}/actions/artifacts/{aid}')
        jobs = [job for job in page['jobs'] if job['name'] == producer]
        require(len(jobs) == 1, 'Original producer must be unique')
        job = jobs[0]
        require(job['run_id'] == RUN and job['run_attempt'] == 1 and job['head_sha'] == SOURCE
                and job['status'] == 'completed' and job['conclusion'] == 'success', 'Original producer failed')
        require(row['id'] == aid and row['name'] == name and row['size_in_bytes'] == size
                and row['digest'] == 'sha256:' + expected and row['expired'] is False
                and row['workflow_run']['id'] == RUN and row['workflow_run']['head_sha'] == SOURCE,
                'Original artifact identity mismatch')
        stamp = lambda text: datetime.fromisoformat(text.replace('Z', '+00:00'))
        require(stamp(job['started_at']) <= stamp(row['created_at']) <= stamp(job['completed_at']),
                'Artifact creation outside original producer interval')
        archive = out / (label + '.zip')
        _gh_binary(f'repos/{REPO}/actions/artifacts/{aid}/zip', archive)
        require(archive.stat().st_size == size and digest(archive) == expected, 'Archive bytes mismatch')
        destination = out / label
        with zipfile.ZipFile(archive) as source:
            entries = source.infolist()
            require(entries and len({x.filename for x in entries}) == len(entries), 'Duplicate/empty ZIP')
            require(sum(x.file_size for x in entries) <= 128 * 1024 * 1024, 'Oversized fixture ZIP')
            for entry in entries:
                path = PurePosixPath(entry.filename)
                require(not path.is_absolute() and '..' not in path.parts and '\\' not in entry.filename
                        and ':' not in entry.filename and not stat.S_ISLNK(entry.external_attr >> 16)
                        and not entry.flag_bits & 1, 'Unsafe ZIP entry')
            require(source.testzip() is None, 'Fixture ZIP CRC failure')
            prefix = ('_temp/lite-client-fixtures/' if label == 'kotlin' else
                      '_temp/lite-flutter-host/dart-closure/')
            selected = [entry for entry in entries if entry.filename.startswith(prefix)]
            require(selected and not any(entry.filename.lower().endswith('.apk') for entry in selected),
                    'Required fixture subset missing or contains an APK')
            # The sealed reports also contain unrelated owner-fixture APKs; never restore those.
            source.extractall(destination, members=selected)
        records.append(dict(artifact=row, producer=job,
                            files={p.relative_to(destination).as_posix(): digest(p)
                                   for p in sorted(destination.rglob('*')) if p.is_file()}))
    fixtures = out / 'kotlin/_temp/lite-client-fixtures'
    manifest = json.loads((fixtures / 'FIXTURE-INPUTS.json').read_text())
    require(manifest['source'] == SOURCE and str(manifest['run']) == str(RUN)
            and str(manifest['attempt']) == '1', 'Original fixture manifest identity mismatch')
    for name, expected in manifest['files'].items():
        path = PurePosixPath(name)
        require(not path.is_absolute() and '..' not in path.parts, 'Unsafe fixture manifest path')
        require(digest(fixtures / name) == expected, 'Original fixture member mismatch')
    resource = Path(os.environ['RUNNER_TEMP']) / 'lite-client-fixtures/resource'
    require(str(resource) == '/home/runner/work/_temp/lite-client-fixtures/resource',
            'Runner fixture path differs; no silent fixture rewriting is allowed')
    require(not resource.exists(), 'Refusing to overwrite fixture directory')
    resource.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(fixtures / 'resource', resource)
    # The original synthetic YAML embeds this path; reuse its original location byte-for-byte.
    dns = out / 'flutter/_temp/lite-flutter-host/dart-closure/dns-meow.json'
    closure = json.loads((dns.parent / 'results.json').read_text())
    require(closure['source'] == SOURCE and str(closure['run']) == str(RUN)
            and str(closure['attempt']) == '1' and closure['results']['dns-meow']['testExit'] == 0
            and closure['results']['dns-meow']['logExit'] == 0, 'DNS producer receipt mismatch')
    require(dns.is_file(), 'Original DNS fixture missing')
    save(out / 'inputs.json', dict(source=SOURCE, tree=TREE, originalRun=RUN, archives=records,
                                 resourcePath=str(resource), dnsPath=str(dns), dnsSha256=digest(dns)))


def graph(path):
    tasks = re.findall(r'^(:\S+)\s+SKIPPED\s*$', path.read_text(), re.M)
    require(':app:testReleaseUnitTest' in tasks, 'Consumer task absent from dry-run')
    forbidden = r'(assemble.*|package(?:Release|Debug)(?:AndroidTest|Bundle)?|bundle(?:Release|Debug)|minify.*WithR8|install.*|connected.*)'
    require(not any(re.fullmatch(forbidden, task.rsplit(':', 1)[-1], re.I) for task in tasks),
            'Forbidden App/APK task in consumer graph')


def finish(root, out):
    require(git(root, 'rev-parse', 'HEAD') == SOURCE and git(root, 'rev-parse', 'HEAD^{tree}') == TREE,
            'App source changed')
    require(not git(root, 'diff', '--name-only') and not git(root, 'diff', '--cached', '--name-only'),
            'Fixed tracked App inputs were modified')
    require(not list(root.rglob('*.apk')), 'APK generated during consumer verification')
    xml_dir = root / 'apps/aerove-lite/app/build/test-results/testReleaseUnitTest'
    require({p.name for p in xml_dir.glob('TEST-*.xml')} ==
            {f'TEST-dev.aerove.lite.data.{cls}.xml' for cls in TESTS}, 'Unexpected consumer XML set')
    execution = re.findall(r'^> Task :app:testReleaseUnitTest(?:[ \t]+([^\r\n]*))?$',
                           (out / 'consumers.log').read_text(), re.M)
    require(execution == [''], 'Consumer task did not execute freshly in this worker')
    results = []
    for cls, method in TESTS.items():
        path = xml_dir / f'TEST-dev.aerove.lite.data.{cls}.xml'
        suite = ET.parse(path).getroot()
        cases = suite.findall('testcase')
        require(len(cases) == 1 and cases[0].get('name') == method
                and cases[0].get('classname') == 'dev.aerove.lite.data.' + cls,
                'Unexpected consumer selector/count')
        require(not any(cases[0].find(tag) is not None for tag in ('failure', 'error', 'skipped')),
                'Consumer did not actually pass')
        require(int(suite.get('tests', '-1')) == 1
                and int(suite.get('failures', '-1')) == 0 and int(suite.get('errors', '-1')) == 0
                and int(suite.get('skipped', '-1')) == 0, 'Consumer XML failed or skipped')
        shutil.copy2(path, out / path.name)
        results.append(dict(test=cls + '.' + method, xmlSha256=digest(path), passed=True, skipped=False))
    geo = out / 'producer-fixtures.json'
    require(geo.is_file() and geo.stat().st_size > 0, 'Geo producer output missing')
    save(out / 'receipt.json', dict(role='sameSourceConsumerClosure', accepted=False, appApkBuilt=False,
         fullCiAccepted=False, published=False, source=SOURCE, tree=TREE, originalRun=RUN,
         executorSource=os.environ['GITHUB_SHA'], run=os.environ['GITHUB_RUN_ID'],
         attempt=os.environ['GITHUB_RUN_ATTEMPT'], inputs=json.loads((out / 'inputs.json').read_text()),
         geoProducerCommand=(out / 'geo-command.txt').read_text(), geoProducerSha256=digest(geo),
         nodeVersion=(out / 'node-version.txt').read_text().strip(),
         graphSha256=digest(out / 'consumer-graph.txt'), noApk=True, tests=results))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('prepare', 'graph', 'finish'))
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    root, out = args.root.resolve(), args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    if args.mode == 'prepare':
        prepare(root, out)
    elif args.mode == 'graph':
        graph(out / 'consumer-graph.txt')
    else:
        finish(root, out)
