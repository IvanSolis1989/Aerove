"""Linux process-handle signaling shared by host test owners."""
import os
import signal


def signal_qualified_process(target, signum, qualified):
    if not hasattr(os, 'pidfd_open') or not hasattr(signal, 'pidfd_send_signal'):
        raise RuntimeError('Linux pidfd signaling required for owned process termination')
    handle = os.pidfd_open(target)
    try:
        if not qualified():
            raise AssertionError('process identity/ownership changed before pidfd signal')
        signal.pidfd_send_signal(handle, signum)
    finally:
        os.close(handle)
