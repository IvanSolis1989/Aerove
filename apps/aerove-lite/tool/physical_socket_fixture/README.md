# Physical separate-UID socket fixture

This source-only test dependency is separate from the pinned emulator
fixture. They share the package identity `dev.aerove.fixture.socketowner`
but are mutually exclusive on a device. Do not replace the emulator gate's
fixture or install on an ordinary handset. Use the isolated-lab constraints
in the physical HTTP runbook and a separately verified test signer. It has
no launcher Activity, root, shell, traffic capture or production data access.

Bind explicitly to `.PhysicalSocketService` from the AEROVE test driver.
Only a caller UID resolving solely to `dev.aerove.lite` is eligible.
The driver still needs to verify the fixture install identity and retain its
own host-pinned boot/APK/run bindings.

* 1 OPEN: supply `host` (literal assigned RFC1918, never the emulator alias),
  integer `port`, `stage` and its exact `nonce`. Returns the real local and
  remote TCP tuple, independent UID/PID/package, monotonic open time and full
  nonce echo. Reads exactly the response length, not to EOF, with no proxy
  or redirect. The caller owns the lease on successful open.
* 2 OBSERVE: matching caller and nonce, for the active stage only. Fails if
  the peer already closed or sent extra bytes. Returns the same socket's
  tuple and a device monotonic observation time; this is not a Native
  network observation.
* 3 CLOSE: matching caller and nonce. The active stage again checks for early
  remote EOF before closing locally and returning the real close time. All
  leases expire automatically, and unbind/destroy also clean up.

Use `--hold-active` on the isolated host. Sample the actual Native connection
from the instrumented runner between OPEN and CLOSE, using device
`elapsedRealtimeNanos` throughout the same boot. Collect the real Android
VPN route/UID, then join stop and verify VPN absence before the final request.
Use the exact tuple plus core UID and type, not absence of EOF alone. Join
host and device records by nonce, never by comparing their clocks. Do not
retry a nonce or turn a partial result into a release verdict.

The checked-in address test evaluates the actual Java parser on the host.
A JVM/D8 compile and manifest-only link are not an installed Binder run,
signed test dependency, or VPN acceptance. Android-side orchestration,
approved target and observed behavior remain separate work.
