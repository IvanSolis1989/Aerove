"""Bounded API-33 window readiness; no application or instrumentation changes."""
import json
import re
import subprocess
import time

SERIAL = 'emulator-5554'


def keyguard_ready(text):
    # API-33 KeyguardServiceDelegate.dump, as included in dumpsys window policy.
    blocks = text.split('KeyguardServiceDelegate\n')
    if len(blocks) != 2:
        return False
    block = blocks[1].split('Looper state:', 1)[0]
    expected = dict(showing='false', inputRestricted='false', systemIsReady='true',
                    bootCompleted='true', screenState='SCREEN_STATE_ON',
                    interactiveState='INTERACTIVE_STATE_AWAKE')
    return all(re.findall(r'^\s*' + key + r'=(\S+)\s*$', block, re.MULTILINE) == [value]
               for key, value in expected.items())


def wait_ready(emulator, directory, receipt, check_cancelled, *, capture_guest_logcat=False,
               debug_guest_vulkan=False):
    started = time.monotonic()
    deadline = started + 600
    report = dict(schema=1, ownerPid=receipt['ownerPid'], emulatorPid=emulator.pid,
                  deviceSerial=SERIAL, budgetSeconds=600, ready=False, commands=[])
    receipt['bootstrap'] = 'BOOTSTRAP.json'
    boot_shell_ready = False
    initial_logcat = False
    deadline_logcat = False
    debug_vulkan_attempted = False
    if debug_guest_vulkan:
        report['guestVulkanDebug'] = dict(property='debug.vulkan.enable_callback',
                                        requestedValue='true', propertyReadbackVerified=False)
    if capture_guest_logcat:
        report['guestLogcat'] = []

    def guest_logcat(window):
        check_cancelled(receipt)
        if emulator.poll() is not None:
            raise RuntimeError('Owned emulator exited before bootstrap guest logcat')
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return
        arguments = ['logcat', '-b', 'main', '-b', 'system', '-b', 'crash',
                     '-d', '-v', 'threadtime']
        row = dict(window=window, arguments=arguments,
                   elapsedStart=time.monotonic() - started)
        report['guestLogcat'].append(row)
        try:
            result = subprocess.run(['adb', '-s', SERIAL, 'shell', *arguments],
                                    capture_output=True, text=True, timeout=min(3, remaining))
            row.update(returnCode=result.returncode, stdout=result.stdout, stderr=result.stderr)
        except subprocess.TimeoutExpired as error:
            row.update(errorType=type(error).__name__, error=str(error), timedOut=True,
                       stdout=error.stdout.decode('utf-8', errors='replace') if isinstance(error.stdout, bytes) else error.stdout or '',
                       stderr=error.stderr.decode('utf-8', errors='replace') if isinstance(error.stderr, bytes) else error.stderr or '')
        except OSError as error:
            row.update(errorType=type(error).__name__, error=str(error))
        finally:
            row['elapsedEnd'] = time.monotonic() - started
        check_cancelled(receipt)
        if emulator.poll() is not None:
            raise RuntimeError('Owned emulator exited during bootstrap guest logcat')

    def command(arguments, boot_probe=False):
        nonlocal boot_shell_ready, initial_logcat
        check_cancelled(receipt)
        if emulator.poll() is not None:
            raise RuntimeError('Owned emulator exited during bootstrap')
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Existing 600-second boot/readiness deadline reached')
        row = dict(arguments=arguments, elapsedStart=time.monotonic() - started)
        report['commands'].append(row)
        try:
            result = subprocess.run(['adb', '-s', SERIAL, 'shell', *arguments],
                                    capture_output=True, text=True, timeout=min(10, remaining))
            row.update(returnCode=result.returncode, stdout=result.stdout, stderr=result.stderr)
        except subprocess.TimeoutExpired as error:
            row.update(timedOut=True, stdout=str(error.stdout or ''), stderr=str(error.stderr or ''))
            if not boot_probe:
                raise
            return ''
        finally:
            row['elapsedEnd'] = time.monotonic() - started
        check_cancelled(receipt)
        if emulator.poll() is not None:
            raise RuntimeError('Owned emulator exited during bootstrap command')
        if result.returncode and boot_probe:
            return ''
        if result.returncode:
            raise RuntimeError('Bootstrap adb command failed: ' + ' '.join(arguments))
        if capture_guest_logcat and boot_probe and not debug_guest_vulkan:
            boot_shell_ready = True
            if not initial_logcat:
                initial_logcat = True
                guest_logcat('initial')
        return result.stdout

    def debug_boot_probe():
        nonlocal debug_vulkan_attempted, boot_shell_ready, initial_logcat
        # Run outside command(): property commands cannot recursively trigger this setup.
        value = command(['getprop', 'sys.boot_completed'], boot_probe=True)
        if report['commands'][-1].get('returnCode') == 0:
            if not debug_vulkan_attempted:
                debug_vulkan_attempted = True
                if command(['getprop', 'ro.debuggable']).strip() != '1':
                    raise ValueError('Guest Vulkan callback diagnosis requires ro.debuggable=1')
                command(['setprop', 'debug.vulkan.enable_callback', 'true'])
                if command(['getprop', 'debug.vulkan.enable_callback']).strip() != 'true':
                    raise ValueError('Guest Vulkan callback property readback differs from true')
                # This proves the property only; a real loader log must prove callback installation.
                report['guestVulkanDebug']['propertyReadbackVerified'] = True
            if capture_guest_logcat:
                boot_shell_ready = True
                if not initial_logcat:
                    initial_logcat = True
                    guest_logcat('initial')
        return value

    def pause():
        nonlocal deadline_logcat
        check_cancelled(receipt)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Existing 600-second boot/readiness deadline reached')
        if capture_guest_logcat and boot_shell_ready and not deadline_logcat and remaining <= 10:
            deadline_logcat = True
            guest_logcat('before-deadline')
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('Existing 600-second boot/readiness deadline reached')
        time.sleep(min(2, remaining))
        # Capture before the next boot probe can consume the last ten seconds.
        if capture_guest_logcat and boot_shell_ready and not deadline_logcat:
            remaining = deadline - time.monotonic()
            if 0 < remaining <= 10:
                deadline_logcat = True
                guest_logcat('before-deadline')

    try:
        while (debug_boot_probe() if debug_guest_vulkan else
               command(['getprop', 'sys.boot_completed'], boot_probe=True)).strip() != '1':
            pause()
        # WindowManager performs dismissal, without synchronous injected input.
        command(['wm', 'dismiss-keyguard'])
        while not keyguard_ready(command(['dumpsys', 'window', 'policy'])):
            pause()
        check_cancelled(receipt)
        report['ready'] = True
    except BaseException as error:
        report.update(errorType=type(error).__name__, error=str(error), failureDiagnostics=[])
        # Failure evidence cannot extend startup's deadline or authorize acceptance.
        for arguments in (['dumpsys', 'window', 'policy'], ['dumpsys', 'input'],
                          ['logcat', '-d', '-t', '100']):
            remaining = deadline - time.monotonic()
            if (remaining <= 0 or isinstance(error, InterruptedError)
                    or receipt.get('interruptSignals') or emulator.poll() is not None):
                break
            row = dict(arguments=arguments)
            report['failureDiagnostics'].append(row)
            try:
                result = subprocess.run(['adb', '-s', SERIAL, 'shell', *arguments],
                                        capture_output=True, text=True, timeout=min(5, remaining))
                row.update(returnCode=result.returncode, stdout=result.stdout, stderr=result.stderr)
            except Exception as diagnostic_error:
                row.update(errorType=type(diagnostic_error).__name__, error=str(diagnostic_error))
        raise
    finally:
        report['elapsedSeconds'] = time.monotonic() - started
        (directory / 'BOOTSTRAP.json').write_text(json.dumps(report, indent=2) + '\n')

