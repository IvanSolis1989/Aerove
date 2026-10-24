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


def wait_ready(emulator, directory, receipt, check_cancelled):
    started = time.monotonic()
    deadline = started + 600
    report = dict(schema=1, ownerPid=receipt['ownerPid'], emulatorPid=emulator.pid,
                  deviceSerial=SERIAL, budgetSeconds=600, ready=False, commands=[])
    receipt['bootstrap'] = 'BOOTSTRAP.json'

    def command(arguments, boot_probe=False):
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
        return result.stdout

    def pause():
        check_cancelled(receipt)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Existing 600-second boot/readiness deadline reached')
        time.sleep(min(2, remaining))

    try:
        while command(['getprop', 'sys.boot_completed'], boot_probe=True).strip() != '1':
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

