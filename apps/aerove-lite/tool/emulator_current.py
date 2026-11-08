"""Own one emulator for already-verified, same-run current runtime artifacts."""
import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import tempfile

import ci_artifacts as artifacts
from emulator_bootstrap import wait_ready
from emulator_qa79 import (GPU_BACKEND, RENDERER_LABEL, emulator_environment, renderer_evidence,
                          crash_inventory, preserve_owned_crash, monitor, stop_owned,
                          finish_emulator, sha)

APK_ROOT = Path('apps/aerove-lite/app/build/outputs/apk')


def verify_current_runtime(directory):
    from lite343_anr import enabled as anr343_enabled, runtime as anr343_runtime
    if anr343_enabled():
        return anr343_runtime(directory)
    from lite290_surface_probe import enabled as probe290_enabled, runtime as probe290_runtime
    if probe290_enabled():
        return probe290_runtime(directory)
    from lite265_recheck import enabled, runtime
    if enabled():
        return runtime(directory)
    if os.environ.get('AEROVE_ORIGINAL84_PROOF') or os.environ.get('AEROVE_ORIGINAL_RUNTIME_PROOF'):
        from emulator_original84 import verify_original_runtime
        return verify_original_runtime(directory)
    expected = artifacts.identity()
    value = artifacts.verify(directory, expected, runtime=True)
    from verify_apk import verify_bundled_script_apk
    bundled_script = verify_bundled_script_apk(Path(directory) / 'apks/app-release.apk')
    apks = {}
    for name, location in [('app-release.apk', 'release'),
                           ('app-release-androidTest.apk', 'androidTest/release')]:
        original = Path(directory) / 'apks' / name
        installed = APK_ROOT / location / name
        if installed.is_symlink() or sha(installed) != sha(original):
            raise ValueError('Installed APK differs from verified current runtime artifact')
        apks[name] = sha(installed)
    return dict(identity=expected, apks=apks, runtimeManifestSha256=sha(Path(directory) / 'source-manifest.json'),
                apkInspectorOutcome=value['apkInspectorOutcome'], bundledMeowScript=bundled_script)


# Guest GLES is observed in SurfaceFlinger; it is not host GLES startup evidence.
GUEST_RENDERER_LABEL = 'Guest ANGLE (Vulkan + SwiftShader)'


def graphics_configuration():
    from lite265_recheck import enabled, BACKEND
    from lite290_surface_probe import lavapipe_requested
    comparison = lavapipe_requested()
    lavapipe = enabled() or comparison
    return dict(gpuBackend='lavapipe' if lavapipe else GPU_BACKEND,
                vulkanBackend='lavapipe' if lavapipe else 'swiftshader',
                guestRenderer=BACKEND if lavapipe else GUEST_RENDERER_LABEL)


def validate_renderer_proof(path):
    graphics = graphics_configuration()
    guest_angle = os.environ.get('AEROVE_GUEST_ANGLE') == '1'
    if not guest_angle and (os.environ.get('AEROVE_ORIGINAL84_PROOF') or os.environ.get('AEROVE_ORIGINAL_RUNTIME_PROOF')):
        from emulator_original84 import validate_original_renderer
        return validate_original_renderer(path)
    proof = json.loads(Path(path).read_text())
    expected_renderer = graphics['guestRenderer'] if guest_angle else RENDERER_LABEL
    if (proof.get('schema') != 1 or proof.get('owner') != 'emulator_current.py'
            or proof.get('deviceSerial') != os.environ.get('ANDROID_SERIAL')
            or proof.get('gpuBackend') != graphics['gpuBackend']
            or proof.get('rendererBackend') != expected_renderer):
        raise ValueError('Invalid current-run renderer proof')
    actual = verify_current_runtime(proof['runtimeDirectory'])
    if any(proof.get(key) != actual[key] for key in actual):
        raise ValueError('Current runtime proof identity or APK hashes changed')
    if guest_angle:
        guard_guest_angle_phase(proof.get('phase'), actual)
        directory = Path(path).parent
        if proof.get('guestGraphicsEvidence') != guest_angle_file_hashes(directory):
            raise ValueError('Guest ANGLE raw evidence changed after the owner proof')
        outputs = {name: (directory / ('guest-angle-' + name + '.out')).read_text()
                   for name, _ in GUEST_ANGLE_READS}
        for name, _ in GUEST_ANGLE_READS:
            if (directory / ('guest-angle-' + name + '.exit.txt')).read_text().strip() != '0':
                raise ValueError('Guest ANGLE device read did not succeed')
        route = guest_angle_route(outputs, (directory / 'guest-angle-host-vulkan.out').read_text(),
                                  vulkan_backend=graphics['vulkanBackend'])
        if (proof.get('guestGraphicsRoute') != route
                or proof.get('rendererEvidence') != route['guestRenderer'] + route['hostVulkanDevice']):
            raise ValueError('Guest ANGLE route differs from its original device evidence')
    else:
        if proof.get('identity') != artifacts.identity() or 'guestGraphicsRoute' in proof:
            raise ValueError('Invalid current host graphics identity or route')
        renderer_evidence('\n'.join(proof['rendererEvidence']))
    return proof



def write_owner_receipt(path, receipt):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent,
                                         prefix='.EMULATOR-OWNER-', suffix='.tmp',
                                         delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(receipt, stream, indent=2)
            stream.write('\n')
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def check_cancelled(receipt):
    if receipt.get('interruptSignals'):
        raise InterruptedError('Owner cancellation observed during runtime')


# Same device reads supply both boot admission and the sealed renderer proof replay.
GUEST_ANGLE_READS = (
    ('api-level', ['getprop', 'ro.build.version.sdk']),
    ('boot-egl', ['getprop', 'ro.boot.hardwareegl']),
    ('hardware-egl', ['getprop', 'ro.hardware.egl']),
    ('angle-overrides-disabled', ['getprop', 'debug.angle.feature_overrides_disabled']),
    ('libraries', ['ls', '-l', '/vendor/lib64/egl/libEGL_angle.so',
                   '/vendor/lib64/egl/libGLESv2_angle.so', '/vendor/lib64/hw/vulkan.ranchu.so']),
    ('surfaceflinger', ['dumpsys', '-t', '30', 'SurfaceFlinger']),
)


def guest_angle_route(outputs, startup, *, vulkan_backend='swiftshader'):
    if outputs['api-level'].strip() != '33':
        raise ValueError('Guest ANGLE requires the actual API33 image')
    if any(outputs[name].strip() != 'angle' for name in ('boot-egl', 'hardware-egl')):
        raise ValueError('Guest ANGLE was requested but the booted EGL driver is not angle')
    if outputs['angle-overrides-disabled'].strip() != 'supportsHostQueryReset':
        raise ValueError('Guest ANGLE host-query-reset compatibility override changed during boot')
    if 'DUMP TIMEOUT' in outputs['surfaceflinger']:
        raise ValueError('SurfaceFlinger dump timed out before renderer evidence completed')
    if vulkan_backend not in ('swiftshader', 'lavapipe'):
        raise ValueError('Unsupported Guest ANGLE Vulkan diagnostic backend')
    if vulkan_backend == 'lavapipe':
        if not re.search(r'vulkan_mode_selected:lavapipe\s+gles_mode_selected:swangle\b', startup):
            raise ValueError('Requested Lavapipe Vulkan and SwANGLE GLES modes were not selected')
        host = [line for line in startup.splitlines() if 'Selecting Vulkan device:' in line]
        devices = {line.split('Selecting Vulkan device:', 1)[1].split(', Version:', 1)[0].strip()
                   for line in host}
        if len(devices) != 1:
            raise ValueError('Lavapipe diagnosis requires one actual host Vulkan device')
        device = next(iter(devices))
        # ANGLE's frontend removes commas from the backend renderer description.
        driver = re.compile(r'\b(llvmpipe|lavapipe)\s+\(LLVM\s+(\d+\.\d+\.\d+),?\s+(\d+)\s+bits\)', re.I)
        selected = driver.fullmatch(device)
        if selected is None:
            raise ValueError('Requested Mesa LLVM Vulkan device was not selected')
        signature = (selected[1].lower(), selected[2], int(selected[3]))
        guest = []
        for line in outputs['surfaceflinger'].splitlines():
            version = re.search(r'OpenGL ES\s+(\d+)\.(\d+)\b', line)
            if (re.search(r'\bGLES:', line) and version
                    and tuple(map(int, version.groups())) >= (3, 1)
                    and all(word in line.upper() for word in ('ANGLE', 'VULKAN'))
                    and [(backend.lower(), llvm, int(bits))
                         for backend, llvm, bits in driver.findall(line)] == [signature]):
                guest.append(line)
        if not guest:
            raise ValueError('Guest ANGLE GLES3.1 and the same host Mesa LLVM driver signature were not observed')
        return dict(route='guest-angle-vulkan-lavapipe', apiLevel=33,
                    bootEgl='angle', hardwareEgl='angle', hostVulkanBackend='lavapipe',
                    angleFeatureOverridesDisabled=outputs['angle-overrides-disabled'].strip(),
                    guestRenderer=guest, hostVulkanDevice=host)
    guest = [line for line in outputs['surfaceflinger'].splitlines()
             if re.search(r'\bGLES:', line) and re.search(r'OpenGL ES\s+3\.1\b', line)
             and all(word in line.upper() for word in ('ANGLE', 'VULKAN', 'SWIFTSHADER'))]
    host = [line for line in startup.splitlines()
            if 'Selecting Vulkan device:' in line and 'SWIFTSHADER' in line.upper()]
    if not guest or not host:
        raise ValueError('Guest ANGLE Vulkan and the host SwiftShader device were not both observed')
    return dict(route='guest-angle-vulkan', apiLevel=33, bootEgl='angle', hardwareEgl='angle',
                angleFeatureOverridesDisabled=outputs['angle-overrides-disabled'].strip(),
                guestRenderer=guest, hostVulkanDevice=host)


def guest_angle_file_hashes(directory):
    names = ['guest-angle-' + name + suffix for name, _ in GUEST_ANGLE_READS
             for suffix in ('.out', '.err', '.exit.txt')]
    names.append('guest-angle-host-vulkan.out')
    return {name: sha(directory / name) for name in names}


def guest_angle_evidence(directory, receipt, startup, *, vulkan_backend='swiftshader'):
    """Confirm the original image actually selected guest ANGLE over Vulkan."""
    outputs = {}
    for name, arguments in GUEST_ANGLE_READS:
        check_cancelled(receipt)
        result = subprocess.run(['adb', '-s', 'emulator-5554', 'shell', *arguments],
                                capture_output=True, text=True,
                                timeout=35 if name == 'surfaceflinger' else 15)
        (directory / ('guest-angle-' + name + '.out')).write_text(result.stdout)
        (directory / ('guest-angle-' + name + '.err')).write_text(result.stderr)
        (directory / ('guest-angle-' + name + '.exit.txt')).write_text(str(result.returncode) + '\n')
        result.check_returncode()
        outputs[name] = result.stdout
    check_cancelled(receipt)
    route = guest_angle_route(outputs, startup, vulkan_backend=vulkan_backend)
    # Seal the admission snapshot: live stdout can grow before the proof is replayed.
    (directory / 'guest-angle-host-vulkan.out').write_text(startup)
    return route


def derived_host_evidence(path, emulator_pid, directory):
    """Bind a tested source-built module to this owned emulator's loaded file."""
    build = json.loads(Path(path).read_text())
    if (build.get('status') != 'host-module-built-and-tested' or build.get('googleOriginal') is not False
            or build.get('hostRegressionPassed') is not True
            or build.get('builderSha256') != sha(Path(__file__).with_name('build_gfxstream_derived_host.sh'))):
        raise ValueError('Derived SDK module lacks this source builder and completed host regressions')
    library = (Path(os.environ['ANDROID_HOME']) / 'emulator/lib64/libgfxstream_backend.so').resolve()
    actual = sha(library)
    if build.get('derivedLibrarySha256') != actual:
        raise ValueError('Installed SDK module differs from the tested derived library')
    mappings = (Path('/proc') / str(emulator_pid) / 'maps').read_text()
    output = directory / 'derived-gfxstream-emulator-maps.txt'
    output.write_text(mappings)
    if not any(line.endswith(' ' + str(library)) for line in mappings.splitlines()):
        raise ValueError('Owned emulator did not map the tested derived SDK module')
    return dict(componentBuild=build, emulatorPid=emulator_pid, loadedLibrary=str(library),
                loadedLibrarySha256=actual, emulatorMapsSha256=sha(output), loadedInOwnedEmulator=True)


def sigill_gdb_script(pid, ready, fault, control=None, fault_signals=('SIGILL',), trace_exec=False, restricted=False):
    if fault_signals not in (('SIGILL',), ('SIGILL', 'SIGSEGV')):
        raise ValueError('Unsupported owned diagnostic signal set')
    # GDB reads only this live Popen PID; no process search, inferior calls or patching.
    script = """set pagination off
set confirm off
set auto-load off
set debuginfod enabled off
set print frame-arguments none
set print entry-values no
set print thread-events off
set follow-fork-mode parent
set detach-on-fork on
handle all nostop noprint pass
%s
handle SIGINT stop print pass
handle SIGILL stop print pass
%s
python
import gdb, json, os, pathlib, time
pid = %d
ready = pathlib.Path(%r)
fault = pathlib.Path(%r)
control = %r
fault_signals = %r
trace_exec = %r
event = [None]
budget = [1048576]
captures = 0
exec_events = []
exec_catchpoints = set()
resume_command = "continue"
def save(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value) + "\\n")
    os.replace(temporary, path)
def stopped(value):
    event[0] = value

def continued(value):
    if not ready.exists():
        save(ready, dict(targetPid=pid, gdbPid=os.getpid(), firstResume=True))

def owner_interrupt():
    if control is None:
        return False
    try:
        request = json.loads(pathlib.Path(control['path']).read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return False
    if request != dict(token=control['token'], targetPid=pid, gdbPid=os.getpid()):
        return False
    # A request file alone cannot authenticate an independently delivered signal.
    # Linux siginfo must show this debugger generated the inferior interrupt.
    try:
        signo = int(gdb.parse_and_eval('$_siginfo.si_signo'))
        sender = int(gdb.parse_and_eval('$_siginfo._sifields._kill.si_pid'))
    except (gdb.error, ValueError, TypeError) as error:
        print("AEROVE_GDB_OWNER_SIGNAL_PARSE_ERROR " + str(error), flush=True)
        return False
    print("AEROVE_GDB_OWNER_SIGNAL " + json.dumps(dict(signo=signo, sender=sender, gdbPid=os.getpid())), flush=True)
    return signo == 2 and sender == os.getpid()
def emit(command):
    print("AEROVE_GDB_COMMAND " + command, flush=True)
    try:
        text = gdb.execute(command, to_string=True)
        limit = min(262144, budget[0])
        print(text[:limit], flush=True)
        budget[0] -= min(len(text), limit)
        if len(text) > limit:
            print("AEROVE_GDB_OUTPUT_TRUNCATED", flush=True)
    except gdb.error as error:
        print("AEROVE_GDB_READ_ERROR " + str(error), flush=True)
gdb.events.stop.connect(stopped)
gdb.events.cont.connect(continued)
try:
    gdb.execute("attach " + str(pid), to_string=True)
    assert gdb.selected_inferior().pid == pid
    if trace_exec:
        before = {bp.number for bp in (gdb.breakpoints() or ())}
        gdb.execute("catch exec", to_string=True)
        exec_catchpoints = {bp.number for bp in (gdb.breakpoints() or ())} - before
        assert len(exec_catchpoints) == 1
    while gdb.selected_inferior().pid:
        event[0] = None
        gdb.execute(resume_command, to_string=True)
        resume_command = "continue"
        if not gdb.selected_inferior().pid:
            break
        if (trace_exec and isinstance(event[0], gdb.BreakpointEvent) and event[0].breakpoints and
                all(bp.number in exec_catchpoints for bp in event[0].breakpoints)):
            assert len(exec_events) < 4
            exec_events.append(dict(targetPid=pid, executable=gdb.current_progspace().filename,
                                    monotonic=time.monotonic()))
            save(fault.with_name("startup-exec-events.json"), dict(targetPid=pid, events=exec_events))
            continue
        observed_signal = event[0].stop_signal if isinstance(event[0], gdb.SignalEvent) else None
        generic_info = None
        if trace_exec and type(event[0]) is gdb.StopEvent:
            generic_info = dict(eventClass=type(event[0]).__name__, targetPid=pid,
                                monotonic=time.monotonic())
            try:
                generic_info["signalNumber"] = int(gdb.parse_and_eval('$_siginfo.si_signo'))
                generic_info["signalCode"] = int(gdb.parse_and_eval('$_siginfo.si_code'))
                if generic_info["signalCode"] == 0:
                    generic_info["signalSenderPid"] = int(gdb.parse_and_eval('$_siginfo._sifields._kill.si_pid'))
            except (gdb.error, ValueError, TypeError) as error:
                generic_info["siginfoReadErrorClass"] = type(error).__name__
            details = getattr(event[0], "details", {})
            generic_info["reason"] = details.get("reason") if isinstance(details, dict) else None
            save(fault.with_name("startup-generic-stop.json"), generic_info)
            if (generic_info.get("signalNumber") == 5 and generic_info.get("signalCode") == 0 and
                    generic_info.get("signalSenderPid", 0) > 0):
                observed_signal = "SIGTRAP"
            else:
                raise gdb.GdbError("Unclassified startup StopEvent; preserved siginfo requires review")
        genuine_trap = trace_exec and observed_signal == "SIGTRAP"
        if observed_signal not in fault_signals and not genuine_trap:
            break
        if genuine_trap:
            resume_command = "signal SIGTRAP"
        captures += 1
        if captures > 3:
            continue
        observed = dict(targetPid=pid, gdbPid=os.getpid(), monotonic=time.monotonic(), readbackComplete=False,
                        capture=captures, signal=observed_signal, eventClass=type(event[0]).__name__,
                        classification="signal-event-forwarded" if genuine_trap else "fault-signal-passed")
        try:
            observed["signalNumber"] = int(gdb.parse_and_eval('$_siginfo.si_signo'))
            observed["signalCode"] = int(gdb.parse_and_eval('$_siginfo.si_code'))
            if observed["signalCode"] <= 0:
                observed["signalSenderPid"] = int(gdb.parse_and_eval('$_siginfo._sifields._kill.si_pid'))
        except (gdb.error, ValueError, TypeError) as error:
            observed["siginfoReadErrorClass"] = type(error).__name__
        save(fault, observed)
        print("AEROVE_OWNED_SIGILL_CAPTURE pid=" + str(pid), flush=True)
        for command in ("p $_siginfo", "info registers rip rsp rbp eflags", "x/16bx $pc", "x/8i $pc", "info symbol $pc", "bt 24", "info sharedlibrary", "info proc mappings"):
            emit(command)
        try:
            frame0 = gdb.selected_frame()
            trap_pc = frame0.pc()
            emit("x/256bx " + hex(trap_pc - 192))
            emit("disassemble /r " + hex(trap_pc - 192) + "," + hex(trap_pc + 64))
            caller = frame0.older()
            if caller is not None:
                caller_pc = caller.pc()
                emit("info symbol " + hex(caller_pc))
                emit("x/160bx " + hex(caller_pc - 128))
                emit("disassemble /r " + hex(caller_pc - 128) + "," + hex(caller_pc + 32))
        except gdb.error as error:
            print("AEROVE_GDB_READ_ERROR " + str(error), flush=True)
        fault_thread = gdb.selected_thread()
        threads = [thread for thread in gdb.selected_inferior().threads() if thread != fault_thread]
        for thread in threads[:63]:
            if budget[0] <= 0:
                break
            thread.switch()
            emit("bt 24")
        fault_thread.switch()
        observed["readbackComplete"] = True
        save(fault, observed)
        # The captured signal stays pass: continue delivers the original failure.
except KeyboardInterrupt:
    print("AEROVE_GDB_OWNER_INTERRUPT", flush=True)
finally:
    if gdb.selected_inferior().pid:
        if owner_interrupt():
            # Clear only the authenticated owner-control interrupt, never SIGILL.
            gdb.execute("queue-signal 0")
            print("AEROVE_GDB_OWNER_CONTROL_CLEARED", flush=True)
        gdb.execute("detach")
        if control is not None:
            save(pathlib.Path(control['detached']), dict(targetPid=pid, gdbPid=os.getpid(), detached=True))
end
""" % ('handle SIGTRAP stop print nopass' if trace_exec else 'handle SIGTRAP nostop noprint pass',
       'handle SIGSEGV stop print pass' if 'SIGSEGV' in fault_signals else '',
       pid, str(ready), str(fault), control, fault_signals, trace_exec)

    if restricted:
        start = script.index('        for command in ("p $_siginfo"')
        end = script.index('        observed["readbackComplete"]', start)
        script = script[:start] + RESTRICTED_SIGILL_READBACK + script[end:]
    return script


RESTRICTED_SIGILL_READBACK = '''        emit("info registers rip")
        emit("bt 24")
        emit("info sharedlibrary")
        emit("info proc mappings")
        try:
            pc = gdb.selected_frame().pc()
            # Only code in an actual file-backed executable mapping, never stack/JIT/heap.
            maps = pathlib.Path("/proc") / str(pid) / "maps"
            for line in maps.read_text().splitlines():
                fields = line.split(None, 5)
                low, high = [int(value, 16) for value in fields[0].split("-")]
                if low <= pc < high and len(fields) == 6 and "x" in fields[1] and fields[5].startswith("/") and not fields[5].endswith(" (deleted)"):
                    emit("disassemble /r " + hex(max(low, pc - 32)) + "," + hex(min(high, pc + 64)))
                    break
        except (gdb.error, OSError, ValueError) as error:
            print("AEROVE_CODE_READ_ERROR " + type(error).__name__, flush=True)
'''


def sigill_interrupt_command(pid, starttime):
    program = """import json, os, pathlib, signal, sys
pid, expected = int(sys.argv[1]), int(sys.argv[2])
fd = os.pidfd_open(pid)
try:
    raw = (pathlib.Path('/proc') / str(pid) / 'stat').read_text()
    actual = int(raw[raw.rfind(')') + 2:].split()[19])
    if actual != expected:
        raise RuntimeError('Owned debugger identity changed')
    signal.pidfd_send_signal(fd, signal.SIGINT)
    print(json.dumps(dict(signal=2, targetPid=pid, targetStarttime=actual)), flush=True)
finally:
    os.close(fd)
"""
    return ['sudo', '-n', '--', sys.executable, '-c', program, str(pid), str(starttime)]

class SigillCapture:
    """One opt-in sidecar; the emulator remains the owner's original Popen child."""
    def __init__(self, emulator, directory, receipt, fault_signals=('SIGILL',), trace_exec=False, restricted=False):
        self.emulator, self.directory, self.receipt = emulator, Path(directory), receipt
        self.child = None
        self.fault_signals = fault_signals
        self.trace_exec = trace_exec
        self.restricted = restricted
        self.ready, self.fault = self.directory / 'sigill-gdb-ready.json', self.directory / 'sigill-observed.json'
        self.control = dict(path=str(self.directory / 'gdb-owner-stop.json'),
                            detached=str(self.directory / 'gdb-owner-detached.json'),
                            token=os.urandom(24).hex())
        self.deadline = time.monotonic() + 1500
        self.state = dict(diagnosticOnly=True, performanceEvidence=False, accepted=False,
                          targetPid=emulator.pid, commands=[], status='starting', faultSignals=list(fault_signals),
                          traceStartupExec=trace_exec, restrictedReadback=restricted)
        receipt['sigillForensics'] = self.state

    def start(self):
        import shutil
        gdb = shutil.which('gdb')
        if gdb is None:
            raise RuntimeError('Requested SIGILL capture requires the existing CI GDB')
        if self.ready.exists() or self.fault.exists():
            raise ValueError('SIGILL capture cannot reuse an earlier phase receipt')
        from beta14_render_profile import proc_stat
        self.state['targetStarttime'] = proc_stat((Path('/proc') / str(self.emulator.pid) / 'stat').read_text())['starttime']
        keys = {'processor', 'vendor_id', 'cpu family', 'model', 'model name', 'stepping', 'microcode', 'flags', 'bugs'}
        cpu = []
        for block in Path('/proc/cpuinfo').read_text().split('\n\n'):
            fields = dict((key.strip(), value.strip()) for line in block.splitlines()
                          if ':' in line for key, value in [line.split(':', 1)] if key.strip() in keys)
            if fields:
                cpu.append(fields)
        (self.directory / 'host-cpu-flags.json').write_text(json.dumps(cpu, indent=2) + '\n')
        script = self.directory / 'sigill-capture.gdb'
        args = (self.emulator.pid, self.ready, self.fault, self.control, self.fault_signals)
        script.write_text(sigill_gdb_script(*args, trace_exec=self.trace_exec, restricted=self.restricted))
        command = ['sudo', '-n', '--', gdb, '-nx', '-nh', '--batch', '-x', str(script)]
        self.state['command'] = command
        with (self.directory / 'sigill-gdb.stdout.log').open('wb') as out, (self.directory / 'sigill-gdb.stderr.log').open('wb') as err:
            self.child = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=out, stderr=err, start_new_session=True)
        self.state['sidecarPid'] = self.child.pid
        deadline = time.monotonic() + 15
        while not self.ready.exists():
            check_cancelled(self.receipt)
            if self.emulator.poll() is not None:
                raise RuntimeError('Owned emulator exited before SIGILL GDB became ready')
            self.check()
            if time.monotonic() >= deadline:
                raise TimeoutError('Owned SIGILL GDB attach did not become ready in 15 seconds')
            time.sleep(.05)
        actual = json.loads(self.ready.read_text())
        if (actual['targetPid'] != self.emulator.pid or not isinstance(actual['gdbPid'], int)
                or actual.get('firstResume') is not True):
            raise ValueError('GDB attached PID differs from the owned emulator')
        if proc_stat((Path('/proc') / str(self.emulator.pid) / 'stat').read_text())['starttime'] != self.state['targetStarttime']:
            raise ValueError('Owned emulator changed during GDB attach')
        gdb_stat = proc_stat((Path('/proc') / str(actual['gdbPid']) / 'stat').read_text())
        self.state.update(status='attached', gdbPid=actual['gdbPid'],
                          gdbStarttime=gdb_stat['starttime'], firstResume=True,
                          gdbProcessGroup=os.getpgid(actual['gdbPid']), sidecarProcessGroup=os.getpgid(self.child.pid))
        self.check()

    def check(self):
        if self.child is None:
            return
        if self.emulator.poll() is not None:
            return  # Original owner, never GDB's exit status, determines emulator failure.
        if self.child.poll() is not None:
            self.state.update(status='failed', returnCode=self.child.returncode)
            raise RuntimeError('Owned SIGILL GDB exited while emulator remained live')
        if time.monotonic() >= self.deadline:
            raise TimeoutError('Owned SIGILL capture reached its 1500-second bound')
        if self.fault.exists():
            observed = json.loads(self.fault.read_text())
            if not observed['readbackComplete'] and time.monotonic() - observed['monotonic'] >= 15:
                raise TimeoutError('Owned SIGILL readback exceeded 15 seconds')

    def close(self):
        if self.child is None or self.state.get('cleanupClosed'):
            return
        unexpected_dead = (self.emulator.poll() is None and self.child.poll() is not None
                           and self.state.get('status') != 'failed'
                           and not self.state.get('stopRequested'))
        # Allow pending SIGILL readback to continue+pass before owner cleanup intervenes.
        if self.fault.exists() and self.child.poll() is None:
            try:
                self.child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.state['readbackTimeout'] = True
        normal_exit_deadline = None
        if self.emulator.poll() == 0 and self.child.poll() is None:
            from beta14_render_profile import proc_stat
            target = Path('/proc') / str(self.emulator.pid) / 'stat'
            try:
                actual = proc_stat(target.read_text())
            except FileNotFoundError:
                actual = None
            if (actual is None or actual['starttime'] != self.state['targetStarttime']
                    or actual['state'] == 'Z'):
                # Reuse both existing command(5s)+wait(3s) allowances, not another grace window.
                normal_exit_deadline = time.monotonic() + 2 * (5 + 3)
                self.state['normalTargetReaped'] = True
        def remaining_timeout(limit):
            if normal_exit_deadline is None:
                return limit
            return max(0, min(limit, normal_exit_deadline - time.monotonic()))
        if normal_exit_deadline is not None:
            try:
                self.child.wait(timeout=remaining_timeout(3))
            except subprocess.TimeoutExpired:
                self.state['normalExitJoinTimedOut'] = True
        if self.child.poll() is None and self.state.get('gdbPid') is not None:
            request = dict(token=self.control['token'], targetPid=self.emulator.pid, gdbPid=self.state['gdbPid'])
            temporary = Path(self.control['path']).with_suffix('.tmp')
            temporary.write_text(json.dumps(request) + '\n')
            os.replace(temporary, self.control['path'])
        for name in ('INT', 'KILL'):
            if self.child.poll() is not None or remaining_timeout(5) <= 0:
                break
            if name == 'INT':
                if self.state.get('gdbPid') is None:
                    continue
                command = sigill_interrupt_command(self.state['gdbPid'], self.state['gdbStarttime'])
            else:
                command = ['sudo', '-n', '--', 'kill', '-' + name, '--', '-' + str(self.child.pid)]
            entry = dict(command=command, signal=name)
            self.state['commands'].append(entry)
            try:
                result = subprocess.run(command, capture_output=True, text=True, timeout=remaining_timeout(5))
                entry.update(returnCode=result.returncode, stdout=result.stdout, stderr=result.stderr)
                if result.returncode == 0:
                    self.state['stopRequested'] = True
            except (OSError, subprocess.TimeoutExpired) as error:
                entry['error'] = repr(error)
            try:
                self.child.wait(timeout=remaining_timeout(3))
            except subprocess.TimeoutExpired:
                continue
        if self.child.poll() is None:
            raise RuntimeError('Owned GDB sidecar did not exit after bounded cleanup')
        self.state['returnCode'] = self.child.returncode
        if self.emulator.poll() is None:
            if not self.state.get('stopRequested') and self.state.get('status') != 'failed':
                unexpected_dead = True
            from beta14_render_profile import proc_stat
            target = Path('/proc') / str(self.emulator.pid)
            deadline = time.monotonic() + 5
            resumed = False
            # sudo's exit is not the actual tracer detach boundary. Inspect only
            # this recorded target and every existing thread's tracer status.
            while self.emulator.poll() is None:
                stat = proc_stat((target / 'stat').read_text())
                if stat['starttime'] != self.state['targetStarttime']:
                    raise RuntimeError('Owned target identity changed during GDB cleanup')
                try:
                    statuses = [path.read_text() for path in (target / 'task').glob('*/status')]
                except FileNotFoundError:
                    # A thread exited during enumeration; observe the current set.
                    statuses = []
                detached = bool(statuses) and all(re.search(r'^TracerPid:\s+0$', value, re.M) for value in statuses)
                gdb_pid = self.state.get('gdbPid')
                gdb_stat = Path('/proc') / str(gdb_pid) / 'stat' if gdb_pid else None
                try:
                    actual_tracer = proc_stat(gdb_stat.read_text()) if gdb_stat else None
                except FileNotFoundError:
                    actual_tracer = None
                tracer_exited = (actual_tracer is None or actual_tracer['state'] == 'Z' or
                                 actual_tracer['starttime'] != self.state.get('gdbStarttime'))
                if detached and tracer_exited:
                    if not resumed:
                        os.killpg(self.emulator.pid, signal.SIGCONT)
                        resumed = True
                    if stat['state'] not in ('t', 'T'):
                        self.state.update(tracerDetached=True, actualTracerExited=True, targetResumed=True)
                        break
                if time.monotonic() >= deadline:
                    raise RuntimeError('Actual GDB detach/owned target resume did not join within 5 seconds')
                # Predicate-bounded observation, not an elapsed-time success condition.
                time.sleep(.01)
            if self.emulator.poll() is not None and not self.fault.exists():
                raise RuntimeError('Owned target exited while detaching GDB')
        self.state['cleanupClosed'] = True
        if unexpected_dead:
            self.state.update(status='failed', unexpectedExitBeforeCleanup=True)
            raise RuntimeError('Owned SIGILL GDB exited before owner cleanup while emulator remained live')

class CancellationView:
    def __init__(self, child, receipt, capture=None):
        self.child = child
        self.receipt = receipt
        self.capture = capture

    def __getattr__(self, name):
        return getattr(self.child, name)

    def poll(self):
        check_cancelled(self.receipt)
        if self.capture is not None:
            self.capture.check()
        return self.child.poll()

    def wait(self, *args, **kwargs):
        check_cancelled(self.receipt)
        try:
            return self.child.wait(*args, **kwargs)
        finally:
            check_cancelled(self.receipt)
            if self.capture is not None:
                self.capture.check()

def original_sample_phase_selector(phase, sample, runtime):
    if phase != 'original-sample':
        if sample is not None:
            raise ValueError('A sample is valid only for the original-sample phase')
        return None
    from emulator_original84 import SAMPLE_TARGETS, original_sample_selector
    if runtime.get('runtimeTarget') not in SAMPLE_TARGETS:
        raise ValueError('Single-sample phase requires a fixed original runtime')
    return original_sample_selector(sample, runtime['runtimeTarget'])


def guard_flutter_phase(phase, sample=None, runtime=None):
    from lite343_anr import enabled as anr343_enabled, IDENTITY as ORIGINAL343
    if anr343_enabled():
        if phase not in ('flutter-ui', 'flutter-full') or sample is not None or runtime is not None and (
                runtime.get('identity') != ORIGINAL343 or runtime.get('original343Anr') is not True or
                runtime.get('harnessIdentity') != artifacts.identity() or runtime.get('diagnosticOnly') is not True or
                runtime.get('accepted') is not False):
            raise ValueError('Fixed343 requires separate diagnostic harness and UI/full phases only')
        return
    from lite290_surface_probe import enabled as probe290_enabled, IDENTITY as ORIGINAL290
    if probe290_enabled():
        if (phase != 'flutter-ui' or sample is not None or runtime is not None and
                (runtime.get('identity') != ORIGINAL290 or runtime.get('original290Probe') is not True or
                 runtime.get('harnessIdentity') != artifacts.identity() or
                 runtime.get('diagnosticOnly') is not True or runtime.get('accepted') is not False)):
            raise ValueError('Fixed290 probe requires a separate harness and only flutter-ui')
        return  # verify_current_runtime verifies the entire fixed archive before emulator creation.
    if phase not in ('flutter-ui', 'flutter-platform', 'flutter-full'):
        return
    neutral = os.environ.get('AEROVE_ORIGINAL_RUNTIME_PROOF')
    conflicts = ('AEROVE_ORIGINAL84_PROOF', 'AEROVE_QA79_PROOF', 'AEROVE_ORIGINAL_RUNTIME_TARGET',
                 'AEROVE_ORIGINAL_SAMPLE', 'AEROVE_ORIGINAL_SAMPLE_SELECTOR')
    if (phase == 'flutter-ui' and sample is None and neutral
            and not any(os.environ.get(key) for key in conflicts)
            and os.environ.get('AEROVE_BETA14_PROFILE') != '1'):
        from emulator_original84 import LITE201_FLUTTER_UI, LITE208_FLUTTER_UI
        from lite_release import _json
        try:
            proof = _json(neutral)
            target = proof.get('runtimeTarget')
            pin = {'lite201-flutter-ui': LITE201_FLUTTER_UI, 'lite208-flutter-ui': LITE208_FLUTTER_UI}.get(target)
            if pin is None:
                raise ValueError('Unknown fixed Flutter UI original')
            required = dict(runtimeTarget=target, diagnosticOriginalRuntime=True,
                originalAppIdentity=pin['identity'], harnessIdentity=artifacts.identity(),
                diagnosticOnly=True, representativeOnly=True, accepted=False, aggregateAcceptance=False, promotionAccepted=False)
            if (all(proof.get(key) == value for key,value in required.items())
                    and all(type(proof.get(key)) is bool for key in ('diagnosticOriginalRuntime', 'diagnosticOnly',
                        'representativeOnly', 'accepted', 'aggregateAcceptance', 'promotionAccepted'))
                    and (runtime is None or all(runtime.get(key) == value for key,value in required.items()))):
                return  # verify_current_runtime still checks the entire fixed ZIP before any emulator spawn.
        except (OSError, ValueError, KeyError):
            pass
    if (sample is not None or any(os.environ.get(name) for name in (
            'AEROVE_ORIGINAL84_PROOF', 'AEROVE_ORIGINAL_RUNTIME_PROOF',
            'AEROVE_ORIGINAL_RUNTIME_TARGET', 'AEROVE_ORIGINAL_SAMPLE',
            'AEROVE_ORIGINAL_SAMPLE_SELECTOR')) or os.environ.get('AEROVE_BETA14_PROFILE') == '1'
            or runtime and any(runtime.get(name) for name in (
                'runtimeTarget', 'diagnosticOriginal84', 'originalAppIdentity'))):
        raise ValueError('flutter-ui requires current production runtime, without original/profile/sample opt-ins')


def guard_guest_angle_phase(phase, runtime, sample=None):
    """Only the verified current Flutter suites or the fixed original208 UI diagnosis."""
    if phase not in ('flutter-ui', 'flutter-platform', 'flutter-full'):
        raise ValueError('Guest ANGLE requires a current Flutter phase or fixed original208 UI')
    guard_flutter_phase(phase, sample, runtime)
    if runtime.get('runtimeTarget') not in (None, 'lite208-flutter-ui'):
        raise ValueError('Guest ANGLE cannot select another historical runtime')
    if runtime.get('runtimeTarget') == 'lite208-flutter-ui' and phase != 'flutter-ui':
        raise ValueError('Fixed original208 Guest ANGLE permits only representative UI')


def flutter_ui_inputs(directory, runtime):
    """Require the inspected BUILD_INFO in the same verified transport before starting an emulator."""
    from lite290_surface_probe import enabled as probe290_enabled, original_inputs as probe290_inputs
    if probe290_enabled():
        return probe290_inputs(directory, runtime)
    if os.environ.get('AEROVE_LITE265_SURFACE_PROBE') == '1':
        from lite265_surface_probe import original_inputs
        return original_inputs(directory, runtime)
    guard_flutter_phase('flutter-ui', runtime=runtime)
    directory = Path(directory)
    info_path = directory / 'BUILD_INFO.json'
    manifest = json.loads((directory / 'source-manifest.json').read_text())
    entries = [row for row in manifest['files'] if row['path'] == 'BUILD_INFO.json']
    if (info_path.is_symlink() or not info_path.is_file() or len(entries) != 1
            or entries[0]['bytes'] != info_path.stat().st_size or entries[0]['sha256'] != sha(info_path)):
        raise ValueError('flutter-ui requires the sealed runtime BUILD_INFO.json')
    info = json.loads(info_path.read_text())
    from verify_apk import FLUTTER_ASSET_PREFIX, FLUTTER_RELEASE_ASSETS
    packaging = info.get('flutter_packaging', {})
    if (packaging.get('schema') != 1 or packaging.get('abi') != 'x86_64'
            or set(packaging.get('libraries', {})) != {'lib/x86_64/libapp.so', 'lib/x86_64/libflutter.so'}
            or set(packaging.get('assets', {})) != {FLUTTER_ASSET_PREFIX + name for name in FLUTTER_RELEASE_ASSETS}):
        raise ValueError('flutter-ui requires inspected Flutter release packaging')
    inputs = dict(apk=(APK_ROOT / 'release/app-release.apk').resolve(),
                  test_apk=(APK_ROOT / 'androidTest/release/app-release-androidTest.apk').resolve(),
                  build_info=info_path.resolve())
    from flutter_android_qa import validate_binding
    validate_binding(inputs['apk'], inputs['test_apk'], info, dict(runtime, owner='emulator_current.py'))
    from android_acceptance_verify import platform_binding
    inputs['platform_apk'] = platform_binding(directory, info).resolve()
    return inputs


def phase_acceptance_command(phase, directory, flutter_inputs=None):
    from lite290_surface_probe import enabled as probe290_enabled
    if probe290_enabled():
        if phase != 'flutter-ui' or flutter_inputs is None:
            raise ValueError('Fixed290 probe requires verified original APK inputs and only flutter-ui')
        return [sys.executable, str(Path(__file__).with_name('lite290_surface_probe.py')),
                '--apk', str(flutter_inputs['apk']), '--test-apk', str(flutter_inputs['test_apk']),
                '--build-info', str(flutter_inputs['build_info']), '--serial', 'emulator-5554',
                '--adb', str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'),
                '--apksigner', str(Path(os.environ['ANDROID_HOME']) / 'build-tools/37.0.0/apksigner'),
                '--harness', os.environ['AEROVE_LITE290_SURFACE_HARNESS'],
                '--harness-info', os.environ['AEROVE_LITE290_SURFACE_HARNESS_INFO'],
                '--output', str(Path(directory) / 'surface-probe')]
    if os.environ.get('AEROVE_LITE265_SURFACE_PROBE') == '1':
        from lite265_recheck import enabled
        if phase != 'flutter-ui' or not enabled() or flutter_inputs is None:
            raise ValueError('Fixed original265 Surface probe requires its verified representative runtime')
        return [sys.executable, str(Path(__file__).with_name('lite265_surface_probe.py')),
                '--apk', str(flutter_inputs['apk']), '--test-apk', str(flutter_inputs['test_apk']),
                '--build-info', str(flutter_inputs['build_info']), '--serial', 'emulator-5554',
                '--adb', str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'),
                '--apksigner', str(Path(os.environ['ANDROID_HOME']) / 'build-tools/37.0.0/apksigner'),
                '--harness', os.environ['AEROVE_LITE265_SURFACE_HARNESS'],
                '--harness-info', os.environ['AEROVE_LITE265_SURFACE_HARNESS_INFO'],
                '--output', str(Path(directory) / 'surface-probe')]
    if phase not in ('flutter-ui', 'flutter-platform', 'flutter-full'):
        return ['bash', 'apps/aerove-lite/tool/emulator_acceptance.sh', phase]
    if flutter_inputs is None:
        raise ValueError('flutter-ui requires validated original APK and BUILD_INFO inputs')
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT', os.environ['ANDROID_HOME']))
    command = [sys.executable, 'apps/aerove-lite/tool/android_acceptance.py',
            '--apk', str(flutter_inputs['apk']), '--test-apk', str(flutter_inputs['test_apk']),
            '--build-info', str(flutter_inputs['build_info']),
            '--adb', str(sdk / 'platform-tools/adb'),
            '--apksigner', str(sdk / 'build-tools/37.0.0/apksigner'),
            '--serial', 'emulator-5554', '--output', str(Path(directory) / 'production-ui')]
    if 'platform_apk' not in flutter_inputs:
        raise ValueError('Current Flutter suite requires a sealed same-target platform runner')
    command += ['--platform-apk', str(flutter_inputs['platform_apk']), '--phase', phase]
    return command


def main():
    graphics = graphics_configuration()
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=['matrix', 'remaining', 'original-sample', 'flutter-ui', 'flutter-platform', 'flutter-full'])
    parser.add_argument('--runtime-directory', type=Path, required=True)
    parser.add_argument('--sample')
    args = parser.parse_args()
    if (args.phase == 'original-sample') != (args.sample is not None):
        parser.error('--sample is required only for original-sample')
    if sys.platform != 'linux':
        raise ValueError('The real acceptance owner requires the existing Linux/KVM runner')
    if os.environ.get('AEROVE_QA79_PROOF') or os.environ.get('AEROVE_CURRENT_RENDERER_PROOF'):
        raise ValueError('Current owner requires fresh, unmixed renderer proof')
    guard_flutter_phase(args.phase, args.sample)
    current = verify_current_runtime(args.runtime_directory)
    if current.get('runtimeTarget') in ('lite201-flutter-ui', 'lite208-flutter-ui') and args.phase != 'flutter-ui':
        raise ValueError('Fixed original Flutter diagnosis permits only the representative flutter-ui phase')
    forensic = os.environ.get('AEROVE_EMULATOR_SIGILL_TRACE') == '1'
    if forensic and (args.phase != 'flutter-ui' or current.get('runtimeTarget') != 'lite208-flutter-ui'):
        raise ValueError('SIGILL capture is restricted to the fixed original208 representative diagnosis')
    boot_forensic = os.environ.get('AEROVE_LITE290_BOOT_CRASH')
    if boot_forensic:
        from lite290_surface_probe import enabled as probe290_enabled
        if boot_forensic != '1' or not probe290_enabled() or args.phase != 'flutter-ui':
            raise ValueError('Bootstrap fault capture requires the fixed original290 diagnostic owner')
    guest_angle = os.environ.get('AEROVE_GUEST_ANGLE') == '1'
    if guest_angle:
        guard_guest_angle_phase(args.phase, current, args.sample)
    derived_host = os.environ.get('AEROVE_GFXSTREAM_SOURCE_RECEIPT')
    if derived_host and (args.phase != 'flutter-ui' or current.get('runtimeTarget') != 'lite208-flutter-ui' or guest_angle):
        raise ValueError('Source-built host diagnosis requires the fixed original208 host GLES runtime')
    sample_selector = original_sample_phase_selector(args.phase, args.sample, current)
    flutter_inputs = flutter_ui_inputs(args.runtime_directory, current) if args.phase in ('flutter-ui', 'flutter-platform', 'flutter-full') else None
    if args.phase in ('matrix', 'remaining') and not current.get('runtimeTarget'):
        info = json.loads((args.runtime_directory / 'BUILD_INFO.json').read_text())
        if info.get('flutter_packaging', {}).get('client_kind') == 'flutter-aot':
            raise ValueError('Current Flutter target cannot execute legacy Compose matrix/remaining; use flutter-platform/flutter-full')
    directory = Path(os.environ['RUNNER_TEMP']) / 'lite-diagnostics' / args.phase
    directory.mkdir(parents=True, exist_ok=True)
    emulator_command = [str(Path(os.environ['ANDROID_HOME']) / 'emulator/emulator'), '-port', '5554', '-avd', 'test', '-no-window', '-gpu', graphics['gpuBackend'], '-no-snapshot', '-noaudio', '-no-boot-anim']
    if guest_angle:
        emulator_command.extend(['-feature', 'GuestAngle,Vulkan'])
    receipt = dict(app=current['identity'], runtime=current, harnessRevision=os.environ['GITHUB_SHA'], harnessRun=os.environ['GITHUB_RUN_ID'], command=emulator_command, ownerPid=os.getpid(), unexpectedEmulatorExit=False, cleanupRequested=False)
    if current.get('runtimeTarget') in ('lite201-flutter-ui', 'lite208-flutter-ui') or current.get('original290Probe') or current.get('original343Anr'):
        receipt.update(originalAppIdentity=current['originalAppIdentity'], harnessIdentity=current['harnessIdentity'],
                       diagnosticOnly=True, representativeOnly=True, accepted=False, aggregateAcceptance=False, promotionAccepted=False)
    if args.phase == 'flutter-ui':
        receipt.update(phase='flutter-ui', representativeOnly=True, functionalAcceptance=False,
                       visualApproved=False, performanceAccepted=False, releaseAccepted=False)
    if sample_selector:
        receipt.update(sample=args.sample, testSelector=sample_selector, diagnosticOnly=True, accepted=False, promotionAccepted=False)
    environment = dict(os.environ, ANDROID_SERIAL='emulator-5554')
    interrupt_signals = []
    receipt['interruptSignals'] = interrupt_signals
    phase = 'runtime'
    late_handling = False
    def interrupt(signum, frame):
        nonlocal late_handling
        interrupt_signals.append(signum)
        receipt['result'] = 1
        if phase == 'finalized' and not late_handling:
            late_handling = True
            try:
                write_owner_receipt(directory / 'EMULATOR-OWNER.json', receipt)
            finally:
                raise InterruptedError(f'Finalized owner interrupted by signal {signum}')
    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGINT, interrupt)
    emulator = acceptance = capture = None
    result = 1
    crash_before = crash_inventory()
    try:
        check_cancelled(receipt)
        from lite290_surface_probe import lavapipe_requested, inspect_lavapipe_driver
        if lavapipe_requested():
            availability = inspect_lavapipe_driver(os.environ['ANDROID_HOME'])
            receipt['lavapipeDriverAvailability'] = availability
            (directory / 'lavapipe-driver-availability.json').write_text(json.dumps(availability, indent=2) + '\n')
            if not availability['passed']:
                raise ValueError('Verified SDK Lavapipe ICD/library/dependency inspection failed')
        with (directory / 'emulator.stdout.log').open('wb') as out, (directory / 'emulator.stderr.log').open('wb') as err:
            emulator = subprocess.Popen(emulator_command, stdin=subprocess.DEVNULL, stdout=out, stderr=err, start_new_session=True, env=emulator_environment(os.environ))
            receipt['emulatorPid'] = emulator.pid
            check_cancelled(receipt)
            if boot_forensic:
                capture = SigillCapture(emulator, directory, receipt, fault_signals=('SIGILL', 'SIGSEGV'), trace_exec=True)
                try:
                    capture.start()
                except Exception as error:
                    capture.state.update(status='failed', error=repr(error))
                    raise
            def bootstrap_check(value):
                check_cancelled(value)
                if boot_forensic:
                    capture.check()
            if guest_angle:
                wait_ready(emulator, directory, receipt, bootstrap_check, capture_guest_logcat=True,
                           disable_guest_host_query_reset=True,
                           debug_guest_vulkan=current.get('runtimeTarget') == 'lite208-flutter-ui')
            else:
                wait_ready(emulator, directory, receipt, bootstrap_check)
            check_cancelled(receipt)
            if guest_angle:
                receipt['guestGraphicsRoute'] = guest_angle_evidence(
                    directory, receipt, (directory / 'emulator.stdout.log').read_text(errors='replace'),
                    vulkan_backend=graphics['vulkanBackend'])
            if derived_host:
                receipt['derivedSdkHost'] = derived_host_evidence(derived_host, emulator.pid, directory)
            if forensic:
                capture = SigillCapture(emulator, directory, receipt)
                try:
                    capture.start()
                except Exception as error:
                    capture.state.update(status='failed', error=repr(error))
                    raise
            check_cancelled(receipt)
            if guest_angle:
                route = receipt['guestGraphicsRoute']
                receipt['rendererEvidence'] = route['guestRenderer'] + route['hostVulkanDevice']
            else:
                receipt['rendererEvidence'] = renderer_evidence((directory / 'emulator.stdout.log').read_text(errors='replace'))
            proof = dict(current, schema=1, owner='emulator_current.py',
                         runtimeDirectory=str(args.runtime_directory.resolve()),
                         deviceSerial='emulator-5554', gpuBackend=graphics['gpuBackend'],
                         rendererBackend=graphics['guestRenderer'] if guest_angle else RENDERER_LABEL,
                         rendererEvidence=receipt['rendererEvidence'])
            if guest_angle:
                proof['phase'] = args.phase
                proof['guestGraphicsRoute'] = receipt['guestGraphicsRoute']
                proof['guestGraphicsEvidence'] = guest_angle_file_hashes(directory)
            if derived_host:
                proof['derivedSdkHost'] = receipt['derivedSdkHost']
            proof_path = directory / 'CURRENT-RENDERER-PROOF.json'
            proof_path.write_text(json.dumps(proof, indent=2) + '\n')
            environment['AEROVE_CURRENT_RENDERER_PROOF'] = str(proof_path.resolve())
            if environment.get('AEROVE_BETA14_PROFILE') == '1':
                if current.get('runtimeTarget') != 'beta14':
                    raise ValueError('Profile-only requires verified immutable beta14 runtime')
                from beta14_render_profile import proc_stat
                receipt['profileQemuStarttime'] = proc_stat((Path('/proc') / str(emulator.pid) / 'stat').read_text())['starttime']
                profile_owner = directory / 'PROFILE-OWNER.json'
                write_owner_receipt(profile_owner, receipt)
                environment['AEROVE_BETA14_PROFILE_OWNER'] = str(profile_owner.resolve())
            check_cancelled(receipt)
            acceptance_command = phase_acceptance_command(args.phase, directory, flutter_inputs)
            if args.phase == 'flutter-ui':
                receipt['acceptanceCommand'] = acceptance_command
            if args.sample:
                environment['AEROVE_ORIGINAL_RUNTIME_TARGET'] = current['runtimeTarget']
                environment['AEROVE_ORIGINAL_SAMPLE'] = args.sample
                environment['AEROVE_ORIGINAL_SAMPLE_SELECTOR'] = sample_selector
                acceptance_command.append(args.sample)
            acceptance = subprocess.Popen(acceptance_command, env=environment, start_new_session=True)
            check_cancelled(receipt)
            result = monitor(emulator, CancellationView(acceptance, receipt, capture), receipt)
            check_cancelled(receipt)
    finally:
        phase = 'cleanup'
        cleanup_errors = []
        try:
            try:
                if acceptance is not None:
                    stop_owned(acceptance)
            except Exception as error:
                cleanup_errors.append(dict(owner='acceptance', error=str(error)))
            finally:
                if acceptance is not None:
                    receipt['acceptanceReturnCode'] = acceptance.returncode
                try:
                    if capture is not None:
                        try:
                            capture.close()
                        except Exception as error:
                            cleanup_errors.append(dict(owner='sigill-gdb', error=str(error)))
                    if emulator is not None:
                        result = finish_emulator(emulator, receipt, result)
                except Exception as error:
                    cleanup_errors.append(dict(owner='emulator', error=str(error)))
                    receipt.update(emulatorReturnCode=emulator.returncode,
                                   emulatorSignal=-emulator.returncode if emulator.returncode is not None and emulator.returncode < 0 else None)
                finally:
                    if emulator is not None:
                        try:
                            receipt['crashpad'] = preserve_owned_crash(directory, emulator.pid, crash_before)
                        except Exception as error:
                            receipt['crashpad'] = dict(status='collection failed', error=str(error))
        finally:
            receipt.update(interruptSignals=interrupt_signals, cleanupErrors=cleanup_errors)
            phase = 'finalized'
            if interrupt_signals or cleanup_errors:
                result = 1
            receipt['result'] = result
            if environment.get('AEROVE_BETA14_PROFILE') == '1':
                try:
                    write_owner_receipt(directory / 'EMULATOR-OWNER.json', receipt)
                except OSError as error:
                    # New diagnostics cannot replace a prior acceptance/cancellation result.
                    try: print('Profile owner receipt save failed: '+repr(error),file=sys.stderr)
                    except OSError: pass
                    if result == 0: result = 1
            else:
                write_owner_receipt(directory / 'EMULATOR-OWNER.json', receipt)

    return result


if __name__ == '__main__':
    raise SystemExit(main())
