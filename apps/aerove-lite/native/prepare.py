#!/usr/bin/env python3
"""Prepare the pinned engine without modifying the product's other runtimes."""
from __future__ import annotations

import argparse
import hashlib
import io
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent
COMMIT = "36eec77463d1243ede287bf6b7197e38237948d5"
ARCHIVE_SHA256 = "89269275c27bfd70cae2a57e80700d0f393d94a01342e87703a610f0f232a376"
URL = f"https://api.github.com/repos/ibigbug/clash-rs/tarball/{COMMIT}"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def tree_digest(directory: Path) -> str:
    hasher = hashlib.sha256()
    for path in sorted(directory.rglob("*"), key=lambda path: path.relative_to(directory).parts):
        if path.is_symlink():
            raise ValueError("prepared source must not contain symlinks")
        if path.is_file() and path.name != "AEROVE_PROVENANCE.json":
            hasher.update(path.relative_to(directory).as_posix().encode("utf-8"))
            hasher.update(b"\0")
            hasher.update(bytes.fromhex(digest(path.read_bytes())))
    return hasher.hexdigest()


def extract(data: bytes, destination: Path) -> None:
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        prefix = None
        for member in archive:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or not path.parts:
                raise ValueError(f"unsafe upstream archive path: {member.name}")
            prefix = prefix or path.parts[0]
            if path.parts[0] != prefix:
                raise ValueError("upstream archive has multiple roots")
            target = destination.joinpath(*path.parts[1:])
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                content = archive.extractfile(member)
                if content is None:
                    raise ValueError(f"unreadable archive entry: {member.name}")
                target.write_bytes(content.read())
            else:
                raise ValueError(f"unsupported upstream archive entry: {member.name}")


def apply_patch(directory: Path, patch: str) -> None:
    lines = patch.replace("\r\n", "\n").splitlines(keepends=True)
    position = 0
    while position < len(lines):
        if not lines[position].startswith("--- a/"):
            raise ValueError("invalid upstream patch header")
        path = PurePosixPath(lines[position][6:].strip())
        if path.is_absolute() or ".." in path.parts:
            raise ValueError("unsafe patch path")
        position += 1
        if lines[position] != "+++ b/" + path.as_posix() + "\n":
            raise ValueError("patch renames are not supported")
        position += 1
        original = directory.joinpath(*path.parts).read_text(encoding="utf-8").splitlines(keepends=True)
        result = []
        consumed = 0
        while position < len(lines) and lines[position].startswith("@@ "):
            match = re.fullmatch(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@\n", lines[position])
            if not match:
                raise ValueError("invalid patch hunk")
            start = int(match[1]) - 1
            if start < consumed:
                raise ValueError("overlapping patch hunks")
            result.extend(original[consumed:start])
            consumed = start
            removed = added = 0
            position += 1
            while position < len(lines) and lines[position][:1] in (" ", "+", "-"):
                line = lines[position]
                if line.startswith("--- a/"):
                    break
                if line[0] in " -":
                    if consumed >= len(original) or original[consumed] != line[1:]:
                        raise ValueError(f"patch context mismatch: {path}:{consumed + 1}")
                    consumed += 1
                    removed += 1
                if line[0] in " +":
                    result.append(line[1:])
                    added += 1
                position += 1
            if removed != int(match[2] or 1) or added != int(match[4] or 1):
                raise ValueError("incorrect patch hunk counts")
        result.extend(original[consumed:])
        directory.joinpath(*path.parts).write_text("".join(result), encoding="utf-8", newline="\n")


def prepare_dependency(name: str, version: str, checksum: str, patch_bytes: bytes) -> dict:
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", name) or not re.fullmatch(r"[0-9.]+", version):
        raise ValueError("invalid pinned dependency identity")
    cache = ROOT / ".upstream"
    cache.mkdir(exist_ok=True)
    blob = cache / f"{name}-{version}.crate"
    if not blob.exists():
        url = f"https://static.crates.io/crates/{name}/{name}-{version}.crate"
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
        if digest(data) != checksum:
            raise ValueError(f"{name}: dependency archive SHA256 mismatch")
        blob.write_bytes(data)
    data = blob.read_bytes()
    if digest(data) != checksum:
        raise ValueError(f"{name}: dependency archive SHA256 mismatch")
    expected = {"name": name, "version": version, "archiveSha256": checksum, "patchSha256": digest(patch_bytes)}
    target = cache / name
    marker = target / "AEROVE_PROVENANCE.json"
    if marker.exists():
        saved = json.loads(marker.read_text(encoding="utf-8"))
        tree = saved.pop("treeSha256", None)
        if saved == expected and tree == tree_digest(target):
            return {**expected, "treeSha256": tree}
    with tempfile.TemporaryDirectory(prefix=f"{name}-", dir=cache) as temporary:
        staging = Path(temporary) / "source"
        staging.mkdir()
        extract(data, staging)
        apply_patch(staging, patch_bytes.decode("utf-8"))
        provenance = {**expected, "treeSha256": tree_digest(staging)}
        (staging / "AEROVE_PROVENANCE.json").write_text(json.dumps(provenance, indent=2)+"\n", encoding="utf-8", newline="\n")
        if target.exists():
            if target.resolve().parent != cache.resolve() or target.name != name:
                raise ValueError("dependency replacement escaped native cache")
            shutil.rmtree(target)
        shutil.move(str(staging), str(target))
    return provenance


def prepare_tailscale() -> dict:
    adapter = ROOT / "dependency-protect"
    manifest_bytes = (adapter / "manifest.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    archive_cache = ROOT / ".upstream" / "dependency-archives"
    archive_cache.mkdir(exist_ok=True)
    for package in manifest["packages"]:
        blob = archive_cache / f"{package['name']}-{package['version']}.crate"
        if not blob.exists():
            with urllib.request.urlopen(package["url"], timeout=60) as response:
                data = response.read()
            if digest(data) != package["sha256"]:
                raise ValueError("locked Tailscale dependency archive SHA256 mismatch")
            blob.write_bytes(data)
    spec = importlib.util.spec_from_file_location("aerove_dependency_prepare", adapter / "prepare.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    cache = ROOT / ".upstream"
    target = cache / "dependency-protect"
    with tempfile.TemporaryDirectory(prefix="tailscale-", dir=cache) as temporary:
        staging = Path(temporary) / "source"
        module.prepare(staging, archive_cache)
        tree = tree_digest(staging)
        expected = {"manifestSha256": digest(manifest_bytes), "adapterSha256": digest((adapter / "prepare.py").read_bytes()), "treeSha256": tree}
        if target.exists() and tree_digest(target) == tree:
            return expected
        if target.exists():
            if target.resolve().parent != cache.resolve() or target.name != "dependency-protect":
                raise ValueError("Tailscale dependency replacement escaped native cache")
            shutil.rmtree(target)
        shutil.move(str(staging), str(target))
    return expected


def prepare(archive: Path | None = None) -> Path:
    cache = ROOT / ".upstream"
    cache.mkdir(exist_ok=True)
    blob = archive or cache / f"{COMMIT}.tar.gz"
    if not blob.exists():
        request = urllib.request.Request(URL, headers={"User-Agent": "AEROVE-Lite-native-build"})
        with urllib.request.urlopen(request, timeout=60) as response:
            data = response.read()
        if digest(data) != ARCHIVE_SHA256:
            raise ValueError("upstream archive SHA256 mismatch")
        blob.write_bytes(data)
    data = blob.read_bytes()
    if digest(data) != ARCHIVE_SHA256:
        raise ValueError("upstream archive SHA256 mismatch")
    patch = (ROOT / "upstream.patch").read_bytes()
    parity_patch = (ROOT / "parity.patch").read_bytes()
    mtu_patch=(ROOT / "mtu.patch").read_bytes()
    controller_patch=(ROOT / "controller.patch").read_bytes()
    probe_dial_patch=(ROOT / "probe_dial.patch").read_bytes()
    runtime_policy_patch=(ROOT / "runtime_policy.patch").read_bytes()
    reset_connections_patch=(ROOT / "reset_connections.patch").read_bytes()
    protocols_patch=(ROOT / "protocols.patch").read_bytes()
    shadowquic_transport_patch=(ROOT / "shadowquic_transport.patch").read_bytes()
    shadowquic_dependency_patch=(ROOT / "shadowquic-dependency.patch").read_bytes()
    protocol_transport=(ROOT / "upstream/protocol_transport.rs").read_bytes()
    process_identity=(ROOT / "upstream/process_identity.rs").read_bytes()
    process_identity_tests=(ROOT / "upstream/process_identity_tests.rs").read_bytes()
    process_identity_patch=(ROOT / "process_identity.patch").read_bytes()
    process_policy_patch=(ROOT / "process_policy.patch").read_bytes()
    dns_transaction_patch=(ROOT / "dns_transaction.patch").read_bytes()
    tailscale_transport_patch=(ROOT / "tailscale_transport.patch").read_bytes()
    tailscale_source=prepare_tailscale()
    shadowquic_source=prepare_dependency("shadowquic","0.3.13","9ebb2a8146e287bdc7adf5b9bdd690495fa45b524458f0d1c26df73ca5d4ac57",shadowquic_dependency_patch)
    user_controller=(ROOT / "upstream/user_controller.rs").read_bytes()
    probe_dial_tests=(ROOT / "upstream/probe_dial_tests.rs").read_bytes()
    mtu_tests=(ROOT / "upstream/mtu_tests.rs").read_bytes()
    initial_park_tests = (ROOT / "upstream/initial_park_tests.rs").read_bytes()
    runtime_control = (ROOT / "upstream/runtime_control.rs").read_bytes()
    runtime_control_tests = (ROOT / "upstream/runtime_control_tests.rs").read_bytes()
    observation_patch = (ROOT / "newobs.patch").read_bytes()
    observations = (ROOT / "upstream/observations.rs").read_bytes()
    observation_tests = (ROOT / "upstream/observation_tests.rs").read_bytes()
    overlay = (ROOT / "upstream/mobile.rs").read_bytes()
    resources = (ROOT / "upstream/resources.rs").read_bytes()
    outbound = (ROOT / "upstream/outbound.rs").read_bytes()
    outbound_tests = (ROOT / "upstream/outbound_tests.rs").read_bytes()
    outbound_route_tests = (ROOT / "upstream/outbound_route_tests.rs").read_bytes()
    telemetry = (ROOT / "upstream/telemetry.rs").read_bytes()
    telemetry_tests = (ROOT / "upstream/telemetry_tests.rs").read_bytes()
    http_probe = (ROOT / "upstream/http_probe.rs").read_bytes()
    dns_network = (ROOT / "upstream/dns_network.rs").read_bytes()
    dns_network_tests = (ROOT / "upstream/dns_network_tests.rs").read_bytes()
    http_policy = (ROOT / "upstream/http_policy.rs").read_bytes()
    http_policy_tests = (ROOT / "upstream/http_policy_tests.rs").read_bytes()
    service_check = (ROOT / "upstream/service_check.rs").read_bytes()
    service_rules = (ROOT / "upstream/service_rules.rs").read_bytes()
    service_check_tests = (ROOT / "upstream/service_check_tests.rs").read_bytes()
    inbound = (ROOT / "upstream/inbound.rs").read_bytes()
    inbound_tests = (ROOT / "upstream/inbound_tests.rs").read_bytes()
    runtime_mode_tests = (ROOT / "upstream/runtime_mode_tests.rs").read_bytes()
    provenance = {
        "dependencies":{"shadowquic":shadowquic_source,"tailscale":tailscale_source},
        "dnsTransactionPatchSha256":digest(dns_transaction_patch),
        "processIdentitySha256":digest(process_identity),"processIdentityTestsSha256":digest(process_identity_tests),"processIdentityPatchSha256":digest(process_identity_patch),"processPolicyPatchSha256":digest(process_policy_patch),"tailscaleTransportPatchSha256":digest(tailscale_transport_patch),
        "protocolsPatchSha256":digest(protocols_patch),"shadowquicTransportPatchSha256":digest(shadowquic_transport_patch),"protocolTransportSha256":digest(protocol_transport),
        "repository": "https://github.com/ibigbug/clash-rs",
        "tag": "v0.10.10", "commit": COMMIT, "archiveSha256": digest(data),
        "controllerPatchSha256":digest(controller_patch),"probeDialPatchSha256":digest(probe_dial_patch),"runtimePolicyPatchSha256":digest(runtime_policy_patch),"resetConnectionsPatchSha256":digest(reset_connections_patch),"userControllerSha256":digest(user_controller),"probeDialTestsSha256":digest(probe_dial_tests),
        "mtuPatchSha256":digest(mtu_patch),"mtuTestsSha256":digest(mtu_tests),
        "initialParkTestsSha256":digest(initial_park_tests),
        "parityPatchSha256": digest(parity_patch), "runtimeControlSha256": digest(runtime_control), "runtimeControlTestsSha256": digest(runtime_control_tests),
        "observationPatchSha256": digest(observation_patch),
        "observationsSha256": digest(observations), "observationTestsSha256": digest(observation_tests),
        "patchSha256": digest(patch), "mobileSha256": digest(overlay),
        "resourcesSha256": digest(resources),
        "outboundSha256": digest(outbound), "outboundTestsSha256": digest(outbound_tests),
        "outboundRouteTestsSha256": digest(outbound_route_tests),
        "telemetrySha256": digest(telemetry), "telemetryTestsSha256": digest(telemetry_tests),
        "httpProbeSha256": digest(http_probe),
        "dnsNetworkSha256":digest(dns_network), "dnsNetworkTestsSha256":digest(dns_network_tests),
        "httpPolicySha256": digest(http_policy), "httpPolicyTestsSha256": digest(http_policy_tests),
        "serviceCheckSha256": digest(service_check),
        "serviceRulesSha256": digest(service_rules),
        "serviceCheckTestsSha256": digest(service_check_tests),
        "inboundSha256": digest(inbound), "inboundTestsSha256": digest(inbound_tests),
        "runtimeModeTestsSha256": digest(runtime_mode_tests),
    }
    target = cache / "clash-rs"
    marker = target / "AEROVE_PROVENANCE.json"
    if marker.exists():
        saved = json.loads(marker.read_text(encoding="utf-8"))
        expected_tree = saved.pop("treeSha256", None)
        if saved == provenance and expected_tree == tree_digest(target):
            print(f"clash-rs {COMMIT}: pinned source already prepared and rechecked")
            return target
    with tempfile.TemporaryDirectory(prefix="prepare-", dir=cache) as temporary:
        staging = Path(temporary) / "source"
        staging.mkdir()
        extract(data, staging)
        apply_patch(staging, patch.decode("utf-8"))
        apply_patch(staging, observation_patch.decode("utf-8"))
        apply_patch(staging, parity_patch.decode("utf-8"))
        apply_patch(staging, mtu_patch.decode("utf-8"))
        apply_patch(staging, controller_patch.decode("utf-8"))
        apply_patch(staging, probe_dial_patch.decode("utf-8"))
        apply_patch(staging, runtime_policy_patch.decode("utf-8"))
        apply_patch(staging, reset_connections_patch.decode("utf-8"))
        apply_patch(staging, protocols_patch.decode("utf-8"))
        apply_patch(staging, shadowquic_transport_patch.decode("utf-8"))
        apply_patch(staging, tailscale_transport_patch.decode("utf-8"))
        apply_patch(staging, process_policy_patch.decode("utf-8"))
        apply_patch(staging, process_identity_patch.decode("utf-8"))
        apply_patch(staging, dns_transaction_patch.decode("utf-8"))
        (staging / "clash-lib/src/mobile.rs").write_bytes(overlay)
        resource_target = staging / "clash-lib/src/mobile/resources.rs"
        resource_target.parent.mkdir(exist_ok=True)
        resource_target.write_bytes(resources)
        (resource_target.parent / "protocol_transport.rs").write_bytes(protocol_transport)
        (resource_target.parent / "process_identity.rs").write_bytes(process_identity)
        (resource_target.parent / "process_identity_tests.rs").write_bytes(process_identity_tests)
        (resource_target.parent / "user_controller.rs").write_bytes(user_controller)
        (resource_target.parent / "probe_dial_tests.rs").write_bytes(probe_dial_tests)
        (resource_target.parent / "mtu_tests.rs").write_bytes(mtu_tests)
        (resource_target.parent / "initial_park_tests.rs").write_bytes(initial_park_tests)
        (resource_target.parent / "runtime_control.rs").write_bytes(runtime_control)
        (resource_target.parent / "runtime_control_tests.rs").write_bytes(runtime_control_tests)
        (resource_target.parent / "observations.rs").write_bytes(observations)
        (resource_target.parent / "observation_tests.rs").write_bytes(observation_tests)
        (resource_target.parent / "outbound.rs").write_bytes(outbound)
        (resource_target.parent / "outbound_tests.rs").write_bytes(outbound_tests)
        (resource_target.parent / "outbound_route_tests.rs").write_bytes(outbound_route_tests)
        (resource_target.parent / "telemetry.rs").write_bytes(telemetry)
        (resource_target.parent / "telemetry_tests.rs").write_bytes(telemetry_tests)
        (resource_target.parent / "http_probe.rs").write_bytes(http_probe)
        (resource_target.parent / "dns_network.rs").write_bytes(dns_network)
        (resource_target.parent / "dns_network_tests.rs").write_bytes(dns_network_tests)
        (resource_target.parent / "http_policy.rs").write_bytes(http_policy)
        (resource_target.parent / "http_policy_tests.rs").write_bytes(http_policy_tests)
        (resource_target.parent / "service_check.rs").write_bytes(service_check)
        (resource_target.parent / "service_rules.rs").write_bytes(service_rules)
        (resource_target.parent / "service_check_tests.rs").write_bytes(service_check_tests)
        (resource_target.parent / "inbound.rs").write_bytes(inbound)
        (resource_target.parent / "inbound_tests.rs").write_bytes(inbound_tests)
        (staging / "clash-lib/src/runtime_mode_tests.rs").write_bytes(runtime_mode_tests)
        provenance["treeSha256"] = tree_digest(staging)
        (staging / "AEROVE_PROVENANCE.json").write_text(
            json.dumps(provenance, indent=2) + "\n", encoding="utf-8", newline="\n")
        if target.exists():
            if target.resolve().parent != cache.resolve() or target.name != "clash-rs":
                raise ValueError("source replacement escaped the native cache")
            shutil.rmtree(target)
        shutil.move(str(staging), str(target))
    print(f"clash-rs {COMMIT}: verified source and applied AEROVE patch")
    return target


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Use an already downloaded immutable archive")
    args = parser.parse_args()
    prepare(args.archive)
