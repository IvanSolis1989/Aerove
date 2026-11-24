#!/usr/bin/env python3
"""Private boot-only diagnostic. Never launch with an APK or share a real device."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import signal
import shutil
import stat
import subprocess
import sys
import time

M = json.loads(Path(__file__).with_name('versions.json').read_text())


def require(ok, what):
    if not ok:
        raise RuntimeError(what)


def run(argv, timeout=45, input=None, combined=False):
    return subprocess.run(argv, check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT if combined else subprocess.PIPE, text=True,
                          timeout=timeout, input=input).stdout


def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def image_hash(root):
    h = hashlib.sha256()
    for p in sorted(root.rglob('*')):
        require(not p.is_symlink(), 'guest image tree: symlinks not allowed')
        if p.is_file():
            h.update(json.dumps([p.relative_to(root).as_posix(), p.stat().st_size, sha(p)],
                                ensure_ascii=True, separators=(',', ':')).encode() + b'\n')
    return h.hexdigest()


def schedule(seed, rounds):
    require(3 <= rounds <= 6, 'Need 3 to 6 paired rounds')
    # Hash-based initial arm is stable across Python versions; alternate pair ordering.
    forward = hashlib.sha256(seed.encode()).digest()[0] % 2 == 0
    return [('A', 'B') if (i % 2 == 0) == forward else ('B', 'A') for i in range(rounds)]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--seed', required=True, help='Record before seeing any results')
    p.add_argument('--rounds', type=int, default=4)
    p.add_argument('--plan-only', action='store_true')
    for name in ('repo', 'archives', 'sdk', 'avdmanager', 'out'):
        p.add_argument('--' + name, type=Path)
    p.add_argument('--expected-image-tree-sha256', help='Independently recorded tree hash; pin before the experiment')
    p.add_argument('--dedicated-empty-host', action='store_true', help='Required approval/preparation: no other ADB targets')
    a = p.parse_args()
    pairs = schedule(a.seed, a.rounds)
    experiment = dict(seed=a.seed, pairs=pairs, versions=M, diagnosticOnly=True, acceptance=False)
    if a.plan_only:
        print(json.dumps(experiment, indent=2))
        return
    require(a.dedicated_empty_host and all(getattr(a, x) is not None for x in
            ('repo', 'archives', 'sdk', 'avdmanager', 'out')), 'Need an explicitly approved dedicated host and all private paths')
    require(platform.system() == 'Linux' and platform.machine() == 'x86_64', 'Need Linux x86_64')
    kvm = Path('/dev/kvm')
    require(kvm.exists() and stat.S_ISCHR(kvm.stat().st_mode) and os.access(kvm, os.R_OK | os.W_OK),
            'Usable /dev/kvm is required; do not substitute TCG')
    a.repo, a.archives, a.sdk, a.avdmanager, a.out = (getattr(a, x).resolve() for x in
            ('repo', 'archives', 'sdk', 'avdmanager', 'out'))
    def git(*args):
        return run(['git', '-C', str(a.repo), *args]).strip()
    require(git('rev-parse', 'HEAD') == M['sourceRevision'] and
            git('rev-parse', 'HEAD^{tree}') == M['sourceTree'] and
            not git('status', '--porcelain', '--untracked-files=all'), 'Use a clean exact #373 checkout')
    # Load exact committed helpers only, not ignored files in the checkout.
    a.out.mkdir(mode=0o700)
    helper_zip = a.out / 'helpers.tar'
    with helper_zip.open('wb') as f:
        subprocess.run(['git', '-C', str(a.repo), 'archive', M['sourceRevision'],
                        'apps/aerove-lite/tool', 'tooling'], stdout=f, check=True, timeout=30)
    subprocess.run(['tar', '-xf', helper_zip, '-C', a.out], check=True, timeout=30)
    sys.path.insert(0, str(a.out / 'apps/aerove-lite/tool'))
    sys.dont_write_bytecode = True
    from emulator_bootstrap import wait_ready
    from emulator_current import guest_angle_evidence, guest_angle_file_hashes
    from emulator_qa79 import stop_owned, crash_inventory, preserve_owned_crash

    image = a.sdk / 'system-images/android-33/google_apis/x86_64'
    props = (image / 'source.properties').read_text()
    for pattern in (r'^Pkg.Revision\s*=\s*17\s*$', r'^AndroidVersion.ApiLevel\s*=\s*33\s*$',
                    r'^SystemImage.Abi\s*=\s*x86_64\s*$', r'^SystemImage.TagId\s*=\s*google_apis\s*$'):
        require(re.search(pattern, props, re.M), 'Expected exact API33 Google APIs x86_64 r17')
    require(re.fullmatch(r'[0-9a-f]{64}', a.expected_image_tree_sha256 or '') and
            image_hash(image) == a.expected_image_tree_sha256, 'Provide matching independently pinned guest image tree digest')
    adb = a.sdk / 'platform-tools/adb'
    # Keep only controlled environment. Use this exclusively on the dedicated runner (default adb port).
    os.environ.clear()
    private_home = a.out / 'home'
    private_home.mkdir(mode=0o700)
    os.environ.update(PATH=str(adb.parent) + ':/usr/bin:/bin', HOME=str(private_home), LANG='C.UTF-8',
                      ANDROID_SERIAL='emulator-5554', ANDROID_USER_HOME=str(private_home / '.android'))
    require(not run([adb, 'devices']).strip().splitlines()[1:], 'Existing ADB target detected; no tests started')
    experiment.update(kernel=platform.release(), machine=platform.machine(), kvmNode=os.stat(kvm).st_rdev,
                      cpuInfoFirstEntry=Path('/proc/cpuinfo').read_text().split('\n\n', 1)[0],
                      adbVersion=run([adb, 'version']), adbSha256=sha(adb),
                      guestImageTreeSha256=a.expected_image_tree_sha256, results=[])
    def save():
        (a.out / 'experiment.json').write_text(json.dumps(experiment, indent=2) + '\n')
    save()
    arms = {}
    for arm, lock in M['arms'].items():
        archive = a.archives / lock['filename']
        require(archive.stat().st_size == lock['bytes'] and sha(archive) == lock['sha256'],
                f'{arm}: archive size/digest did not match first-party pin')
        sdk = a.out / ('sdk-' + arm)
        sdk.mkdir()
        subprocess.run(['unzip', '-qq', archive, '-d', sdk], check=True, timeout=120)
        (sdk / 'system-images').symlink_to(a.sdk / 'system-images', target_is_directory=True)
        emulator = sdk / 'emulator/emulator'
        env_sdk = dict(ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk))
        os.environ.update(env_sdk)
        version = run([emulator, '-no-window', '-version'], combined=True)
        require(f"{lock['version']} (build_id {lock['build']})" in version, 'Emulator binary version/build mismatch')
        accel = run([emulator, '-accel-check'], combined=True)
        require(re.search(r'KVM.*is installed and usable', accel), 'Emulator did not verify usable KVM')
        (a.out / f'{arm}-accel.txt').write_text(accel)
        (a.out / f'{arm}-version.txt').write_text(version)
        arms[arm] = (emulator, env_sdk)
    def cancel(signum, _):
        raise KeyboardInterrupt(signal.Signals(signum).name)
    signal.signal(signal.SIGTERM, cancel)
    for pair_no, pair in enumerate(pairs, 1):
        for arm in pair:
            emulator, env_sdk = arms[arm]
            directory = a.out / f'{pair_no:02}-{arm}'
            directory.mkdir()
            avd = directory / 'avd'
            avd.mkdir()
            os.environ.update(ANDROID_HOME=str(a.sdk), ANDROID_SDK_ROOT=str(a.sdk),
                              ANDROID_AVD_HOME=str(avd))
            require(not run([adb, 'devices']).strip().splitlines()[1:], 'Previous isolated ADB target did not clean up')
            run([a.avdmanager, 'create', 'avd', '--force', '-n', 'test',
                 '--package', M['systemImagePackage'], '--device', 'pixel_6'], input='no\n')
            os.environ.update(env_sdk)
            config = avd / 'test.avd/config.ini'
            lines = [l for l in config.read_text().splitlines() if not
                     re.match(r'^\s*(hw\.cpu\.ncore|disk\.dataPartition\.size)\s*=', l)]
            config.write_text('\n'.join(lines + ['hw.cpu.ncore=2','disk.dataPartition.size=4G']) + '\n')
            (directory / 'avd-config.ini').write_bytes(config.read_bytes())
            cmd = [str(emulator), *M['commandSuffix']]
            row = dict(arm=arm, round=pair_no, ownerPid=os.getpid(), command=cmd,
                       acceptance=False, status='failed', avdConfigSha256=sha(config))
            started = time.monotonic()
            before = crash_inventory()
            with (directory / 'emulator.stdout.log').open('wb') as stdout, (directory / 'emulator.stderr.log').open('wb') as stderr:
                child = subprocess.Popen(['nice', '-n', '19', *cmd], stdout=stdout, stderr=stderr,
                                         start_new_session=True)
                row['emulatorPid'] = child.pid
                try:
                    row['failureStage'] = 'bootstrap'
                    def check(_):
                        require(child.poll() is None, 'Owned emulator exited')
                    wait_ready(child, directory, row, check, capture_guest_logcat=True,
                               disable_guest_host_query_reset=True)
                    row['failureStage'] = 'graphics'
                    gfx = (directory / 'emulator.stdout.log').read_text(errors='replace')
                    row['graphicsRoute'] = guest_angle_evidence(directory, row, gfx)
                    row['graphicsFilesSha256'] = guest_angle_file_hashes(directory)
                    # Boot+keyguard alone is insufficient: wait past the original failure window.
                    row['failureStage'] = 'stability'
                    deadline = time.monotonic() + 90
                    while time.monotonic() < deadline:
                        check(row)
                        require(run([adb, '-s','emulator-5554','shell','getprop','sys.boot_completed'],
                                    timeout=10).strip() == '1', 'Guest not still booted')
                        time.sleep(3)
                    check(row)
                    row['status'] = 'boot-and-route-observed'
                    row.pop('failureStage', None)
                except (Exception, KeyboardInterrupt) as error:
                    row['error'] = f'{type(error).__name__}: {error}'
                    if isinstance(error, KeyboardInterrupt):
                        raise
                finally:
                    row['cleanup'] = stop_owned(child)
                    row['seconds'] = time.monotonic() - started
                    row['ownedCrashpad'] = preserve_owned_crash(directory, child.pid, before)
                    experiment['results'].append(row)
                    save()
            require(child.poll() is not None, 'Owned emulator did not terminate')
            cleanup_until = time.monotonic() + 15
            while run([adb, 'devices']).strip().splitlines()[1:]:
                require(time.monotonic() < cleanup_until, 'Owned adb target remained after cleanup')
                time.sleep(1)
            shutil.rmtree(avd)
            time.sleep(5)
    require(image_hash(image) == a.expected_image_tree_sha256, 'Guest image unexpectedly mutated during experiment')
    experiment['experimentComplete'] = True
    experiment['bootOnlyCounts'] = {arm: sum(r['arm'] == arm and r['status'] == 'boot-and-route-observed'
                                            for r in experiment['results']) for arm in M['arms']}
    save()


if __name__ == '__main__':
    main()
