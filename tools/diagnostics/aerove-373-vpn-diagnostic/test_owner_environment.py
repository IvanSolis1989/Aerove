"""Check exact push-workflow settings against the diagnostic owner guard."""
from contextlib import redirect_stdout
from io import StringIO
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from types import ModuleType
import unittest
from unittest.mock import patch

import yaml

import summary


def load_guard():
    # The pure settings guard needs no SDK, runner token, or production helpers.
    original_runtime = ModuleType("original_runtime")
    original_runtime.fetch = original_runtime.pinned_runtime = original_runtime.stage = lambda *args: None
    with patch.dict(sys.modules, {"emulator_current": ModuleType("emulator_current"),
                                  "original_runtime": original_runtime}):
        import own_emulator
    return own_emulator.validate_owner_env


ROOT = Path(__file__).resolve().parent
STEM = "aerove-373-vpn-diagnostic"
WORKFLOWS = ROOT if (ROOT / (STEM + ".yaml")).is_file() else ROOT.parents[2] / ".github/workflows"


def workflow_environment(path):
    doc = yaml.load(path.read_text(), Loader=yaml.BaseLoader)
    job = doc["jobs"]["vpn"]
    shell = job["steps"][-1]["run"]
    subprocess.run(["bash", "-n"], input=shell, text=True, check=True)
    settings = dict(job["env"])
    for name in re.findall(r"(?m)^\s*export\s+(AEROVE_[A-Z_]+)=", shell):
        settings[name] = "<exported>"
    return settings


class OwnerBoundary(unittest.TestCase):
    def test_prior_workflow_eventually_rejected_its_own_setting(self):
        settings = workflow_environment(WORKFLOWS / (STEM + ".yaml"))
        self.assertIn("AEROVE_BOOT_GUEST_ID", settings)
        with self.assertRaisesRegex(ValueError, "alternate AEROVE execution controls"):
            load_guard()(settings)

    def test_new_workflow_permits_exact_settings_but_not_other_overrides(self):
        settings = workflow_environment(WORKFLOWS / (STEM + "-v2.yaml"))
        guard = load_guard()
        guard(settings)
        for extra in ({"AEROVE_BOOT_GUEST_ID": "r17"}, {"AEROVE_ORIGINAL_RUNTIME_PROOF": "/tmp/other"},
                      {"AEROVE_GUEST_ANGLE": "0"}, {"ANDROID_SERIAL": "other"}):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                guard({**settings, **extra})

    def test_summary_only_emits_safe_checkpoint_names(self):
        with tempfile.TemporaryDirectory() as home:
            root = Path(home)
            base = root / "lite-diagnostics/flutter-full"
            base.mkdir(parents=True)
            (base / "EMULATOR-OWNER.json").write_text(json.dumps({"emulatorPid": 12345, "unrelated": "PRIVATE"}))
            for phase, label in (("artifact-verification", "artifact-verification"), ("PRIVATE", "unclassified")):
                (root / "phase").write_text(phase)
                stream = StringIO()
                with redirect_stdout(stream):
                    summary.summarize(root, 1)
                lines = stream.getvalue()
                self.assertIn("Preflight step: " + label, lines)
                self.assertIn("Owned emulator PID recorded: true", lines)
                self.assertNotIn("12345", lines)
                self.assertNotIn("PRIVATE", lines)
                self.assertIn("Android boot ready: false", lines)


if __name__ == "__main__":
    unittest.main()
