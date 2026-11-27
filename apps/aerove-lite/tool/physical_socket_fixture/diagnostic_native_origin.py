"""Bind only the ephemeral pair's native verifier to the immutable #373 producer."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

from keep_boundary import GRADLE, PIN, gradual, swap
from stage_original373 import BASE, PINS


def workflow_identity():
    return {"workflowSourceSha": BASE["sourceRevision"], "workflowRun": BASE["workflowRun"],
            "workflowAttempt": str(BASE["runAttempt"])}


def verify_origin(staged):
    origin = json.loads((staged / "original-identity.json").read_text())
    receipt = json.loads((staged / "core/provenance.json").read_text())
    expected = {kind: {"id": artifact, "archiveSha256": sha}
                for kind, (artifact, _, sha) in PINS.items()}
    if (origin.get("baseline") != BASE or origin.get("artifacts") != expected
            or origin.get("releaseAccepted") is not False
            or any(str(receipt.get(key)) != value for key, value in workflow_identity().items())):
        raise ValueError("The verified original native producer identity differs")


def transform(stock, current):
    if hashlib.sha256(stock).hexdigest() != PIN[GRADLE] or current != gradual(stock.decode("utf-8")):
        raise ValueError("Unexpected diagnostic Gradle source identity")
    anchor = ("            workflow = {key: os.environ[name] for key, name in [('workflowSourceSha','GITHUB_SHA'), "
              "('workflowRun','GITHUB_RUN_ID'), ('workflowAttempt','GITHUB_RUN_ATTEMPT')]} "
              "if os.environ.get('GITHUB_ACTIONS') == 'true' else None\n")
    # The full verifier and native byte checks remain. The running job is the
    # newly generated APK's identity, not the historical JNI producer's identity.
    return swap(current, anchor, "            workflow = " + repr(workflow_identity()) + "\n")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("repository", type=Path)
    p.add_argument("staged_original", type=Path)
    a = p.parse_args()
    verify_origin(a.staged_original)
    stock = subprocess.check_output(["git", "show", "HEAD:" + GRADLE], cwd=a.repository)
    target = a.repository / GRADLE
    target.write_text(transform(stock, target.read_text()), encoding="utf-8")
    print("Verified immutable #373 JNI producer; diagnostic APK retains current job identity.")


if __name__ == "__main__":
    main()
