"""Use the documented Central mirror for the two exact, hash-pinned Kotlin files."""
import argparse
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "tooling"))
import kotlin_compiler_prefetch as pinned

CENTRAL = "repo.maven.apache.org"
MIRROR = "maven-central.storage-download.googleapis.com"
MODULE_DIR = f"/maven2/org/jetbrains/kotlin/{pinned.MODULE}/{pinned.VERSION}/"
PATHS = {
    MODULE_DIR + pinned.FILENAME,
    MODULE_DIR + f"{pinned.MODULE}-{pinned.VERSION}.pom",
}


def download(command, *, check, timeout, run=subprocess.run):
    parsed = urlsplit(command[-1])
    if parsed.netloc != CENTRAL:
        return run(command, check=check, timeout=timeout)
    if (parsed.scheme != "https" or parsed.path not in PATHS
            or parsed.query not in ("", "download=1") or parsed.fragment):
        raise ValueError("Unexpected Kotlin coordinate or Maven URL")
    # Exactly the same coordinate on the documented mirror. The stock loader
    # still verifies the Maven POM, JAR SHA-256/SHA-1 and installed copies.
    output = command[command.index("--output") + 1]
    return run(["curl", "--fail", "--silent", "--show-error", "--location",
                "--proto", "=https", "--proto-redir", "=https",
                "--connect-timeout", "15", "--max-time", "90",
                "--retry", "1", "--retry-max-time", "100",
                "--output", output, f"https://{MIRROR}{parsed.path}"],
               check=check, timeout=110)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("gradle_home", type=Path)
    a = parser.parse_args()
    jar = pinned.prefetch(a.gradle_home, download=download)
    pinned.configure_repository(a.gradle_home, jar, download=download)
    print("Verified Kotlin compiler and metadata installed in the exact-coordinate local repository.")


if __name__ == "__main__":
    main()
