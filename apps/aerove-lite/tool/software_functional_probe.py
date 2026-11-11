"""Synthetic UI/proxy diagnosis on the caller's disposable, owned emulator."""
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import shlex
import subprocess
import threading
import time
from uuid import uuid4
import xml.etree.ElementTree as ET

PACKAGE = 'dev.aerove.lite'
STATEMENT = ('The Software itself does not provide any proxy server, node, subscription, '
             'or network access service, and has no partnership, agency, or guarantee '
             'relationship with any provider of such services.')


class Driver:
    def __init__(self, adb, output, receipt):
        self.adb, self.output, self.receipt = adb, output, receipt
        self.sequence = 0
        self.stage = 'consent'

    def run(self, args, timeout=90):
        result = subprocess.run(self.adb + args, capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(str(args) + ': ' + result.stderr.decode(errors='replace')[-1000:])
        return result.stdout

    def tree(self):
        self.sequence += 1
        self.run(['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'])
        self.run(['shell', 'wm', 'dismiss-keyguard'])
        self.run(['shell', 'uiautomator', 'dump', '/sdcard/software-probe.xml'])
        raw = self.run(['exec-out', 'cat', '/sdcard/software-probe.xml'])
        (self.output / f'functional-{self.sequence:03d}-{self.stage}.xml').write_bytes(raw)
        return ET.fromstring(raw)

    @staticmethod
    def label(node):
        return node.get('text') or node.get('content-desc') or ''

    @staticmethod
    def box(node):
        numbers = [int(n) for n in re.findall(r'\d+', node.get('bounds', ''))]
        if len(numbers) != 4 or numbers[2] <= numbers[0] or numbers[3] <= numbers[1]:
            raise RuntimeError('Node lacks usable observed bounds')
        return numbers

    def tap(self, node):
        x1, y1, x2, y2 = self.box(node)
        self.run(['shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)])

    def find(self, label, timeout=90, app=True):
        deadline = time.monotonic() + timeout
        dismissed = 0
        while time.monotonic() < deadline:
            root = self.tree()
            rows = list(root.iter('node'))
            if any("isn't responding" in self.label(n) for n in rows) and dismissed < 2:
                waits = [n for n in rows if self.label(n) == 'Wait' and n.get('package') == 'android']
                if len(waits) == 1:
                    self.tap(waits[0]); dismissed += 1; time.sleep(5); continue
            hits = [n for n in rows if self.label(n) == label and n.get('enabled') == 'true'
                    and (not app or n.get('package') == PACKAGE)]
            unique = {n.get('bounds'): n for n in hits}
            if len(unique) == 1:
                return next(iter(unique.values()))
            if len(unique) > 1:
                raise RuntimeError('Ambiguous UI target: ' + label)
            time.sleep(3)
        raise TimeoutError('No observed enabled UI target: ' + label)

    def click(self, label, **kwargs):
        print('Functional step: ' + self.stage + ' / ' + label, flush=True)
        self.tap(self.find(label, **kwargs))

    def input_field(self, value, hint=None):
        root = self.tree()
        fields = [n for n in root.iter('node') if n.get('package') == PACKAGE
                  and n.get('class') == 'android.widget.EditText'
                  and (hint is None or hint in (self.label(n), n.get('hint')))]
        if len(fields) != 1:
            raise RuntimeError('No unique observed text field: ' + str(hint))
        self.tap(fields[0])
        self.run(['shell', 'input', 'text', shlex.quote(value.replace(' ', '%s'))], timeout=180)

    def capture(self, label):
        self.tree()
        (self.output / ('functional-' + label + '.png')).write_bytes(
            self.run(['exec-out', 'screencap', '-p']))


def run(adb, output, outer):
    receipt = dict(diagnosticOnly=True, accepted=False, functionalAcceptance=False,
                   fixture='synthetic-loopback-only', steps=[], status='running')
    driver = Driver(adb, output, receipt)
    marker = 'AEROVE_SYNTHETIC_' + uuid4().hex
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == '/profile.yaml':
                body = b"mixed-port: 7890\nmode: direct\nrules: ['MATCH,DIRECT']\n"
            elif self.path == '/echo':
                body = marker.encode()
            else:
                self.send_error(404); return
            requests.append(dict(path=self.path, at=time.time()))
            self.send_response(200); self.send_header('Content-Length', str(len(body)))
            self.end_headers(); self.wfile.write(body)

    fixture = ThreadingHTTPServer(('127.0.0.1', 18080), Handler)
    worker = threading.Thread(target=fixture.serve_forever, daemon=True)
    worker.start()
    forwarded = None
    try:
        driver.click('Agree')
        driver.find('Restate the statement')
        driver.input_field(STATEMENT)
        driver.run(['shell', 'input', 'keyevent', 'KEYCODE_BACK'])
        driver.click('Confirm'); driver.click('Got it')
        driver.find('Dashboard'); driver.capture('consent-complete')
        receipt['steps'].append('production-consent-completed')
        driver.stage = 'profile-import'
        driver.click('Profiles'); driver.click('Add profile'); driver.click('Link')
        driver.find('Import from link')
        label = 'software-synthetic-profile'
        driver.input_field(label, 'Optional')
        driver.input_field('http://10.0.2.2:18080/profile.yaml', 'Required')
        driver.run(['shell', 'input', 'keyevent', 'KEYCODE_BACK'])
        driver.click('Submit'); driver.click(label, timeout=120)
        driver.capture('profile-imported')
        if not any(row['path'] == '/profile.yaml' for row in requests):
            raise RuntimeError('No actual local synthetic profile download observed')
        receipt['steps'].append('synthetic-profile-downloaded-and-clicked')
        driver.click('Dashboard')
        driver.stage = 'proxy-only-state'
        vpn = driver.find('VPN')
        if vpn.get('checkable') != 'true':
            raise RuntimeError('VPN preference is not explicitly readable; refusing to start')
        if vpn.get('checked') == 'true':
            driver.tap(vpn)
            vpn = driver.find('VPN')
        if vpn.get('checked') != 'false':
            raise RuntimeError('VPN preference did not read back disabled')
        driver.capture('vpn-disabled')
        receipt['steps'].append('vpn-disabled-before-start')
        driver.stage = 'proxy-start'
        driver.click('Start'); driver.find('Stop', timeout=120)
        driver.capture('proxy-started')
        forwarded = driver.run(['forward', 'tcp:0', 'tcp:7890']).decode().strip()
        if not forwarded.isdecimal():
            raise RuntimeError('No owned local adb forwarding port')
        connection = http.client.HTTPConnection('127.0.0.1', int(forwarded), timeout=60)
        try:
            connection.request('GET', 'http://10.0.2.2:18080/echo')
            response = connection.getresponse()
            body = response.read()
            if response.status != 200 or body != marker.encode():
                raise RuntimeError('Proxy did not return the controlled fixture response')
        finally:
            connection.close()
        receipt['steps'].append('real-loopback-proxy-roundtrip')
        driver.stage = 'proxy-stop'
        driver.click('Stop'); driver.find('Start', timeout=120)
        driver.capture('proxy-stopped')
        connection = http.client.HTTPConnection('127.0.0.1', int(forwarded), timeout=15)
        responded = False
        try:
            connection.request('GET', 'http://10.0.2.2:18080/echo')
            response = connection.getresponse()
            responded = response.status == 200 and response.read() == marker.encode()
        except (OSError, http.client.HTTPException):
            pass
        finally:
            connection.close()
        if responded:
            raise RuntimeError('Proxy still served fixture after production Stop')
        receipt['steps'].append('production-stop-returned-to-start-and-proxy-ceased')
        receipt['status'] = 'targeted-evidence-collected'
    except BaseException as error:
        receipt.update(status='failed', stage=driver.stage, error=repr(error))
        try: driver.capture('failure')
        except BaseException as capture_error: receipt['captureError'] = repr(capture_error)
        raise
    finally:
        if forwarded:
            try: driver.run(['forward', '--remove', 'tcp:' + forwarded])
            except BaseException as error: receipt['forwardCleanupError'] = repr(error)
        fixture.shutdown(); fixture.server_close(); worker.join(timeout=5)
        receipt['fixtureRequests'] = requests
        outer['functionalProbe'] = receipt
        (output / 'functional-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
