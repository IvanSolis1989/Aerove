#!/usr/bin/env bash
set -euo pipefail
# Delegate the existing read-only commands to the bounded recorder; no ADB here.
exec python3 "$(dirname "$0")/readonly-check.py" "$@"
