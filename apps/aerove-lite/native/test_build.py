import io
import json
from pathlib import Path
import re
import struct
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build
import prepare
import reference_resources


def archive_bytes(entries):
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        for name, content in entries:
            entry = tarfile.TarInfo(name)
            entry.size = len(content)
            archive.addfile(entry, io.BytesIO(content))
    return buffer.getvalue()


class SourcePreparationTest(unittest.TestCase):
    def test_extract_preserves_contents(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            prepare.extract(archive_bytes([("root/clash-lib/src/lib.rs", b"original\n")]), root)
            self.assertEqual((root / "clash-lib/src/lib.rs").read_bytes(), b"original\n")

    def test_extract_rejects_path_escapes_and_multiple_roots(self):
        for entries in [[("root/../../escape", b"bad")], [("/absolute", b"bad")],
            [("one/file", b"one"), ("two/file", b"two")]]:
            with self.subTest(entries=entries), tempfile.TemporaryDirectory() as temporary:
                with self.assertRaises(ValueError):
                    prepare.extract(archive_bytes(entries), Path(temporary))

    def test_extract_rejects_symlinks(self):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
            entry = tarfile.TarInfo("root/link")
            entry.type = tarfile.SYMTYPE
            entry.linkname = "../../outside"
            archive.addfile(entry)
        with tempfile.TemporaryDirectory() as temporary, self.assertRaises(ValueError):
            prepare.extract(buffer.getvalue(), Path(temporary))

    def test_patch_requires_exact_original_context(self):
        patch_text = "--- a/source.rs\n+++ b/source.rs\n@@ -1,2 +1,2 @@\n original\n-old\n+new\n"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "source.rs"
            target.write_text("original\nold\n")
            prepare.apply_patch(root, patch_text)
            self.assertEqual(target.read_text(), "original\nnew\n")
            with self.assertRaisesRegex(ValueError, "context mismatch"):
                prepare.apply_patch(root, patch_text)

    def test_patch_rejects_escape_and_bad_hunk_counts(self):
        for patch_text in ["--- a/../outside\n+++ b/../outside\n",
            "--- a/source.rs\n+++ b/source.rs\n@@ -1,2 +1 @@\n-old\n+new\n"]:
            with tempfile.TemporaryDirectory() as temporary, self.assertRaises(ValueError):
                root = Path(temporary)
                (root / "source.rs").write_text("old\n")
                prepare.apply_patch(root, patch_text)

    def test_prepare_hash_patch_provenance_and_rechecks_cached_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "upstream").mkdir()
            (root / "upstream/mobile.rs").write_bytes(b"mobile\n")
            (root / "upstream/resources.rs").write_bytes(b"resources\n")
            (root / "upstream/outbound.rs").write_bytes(b"outbound\n")
            (root / "upstream/outbound_tests.rs").write_bytes(b"outbound tests\n")
            (root / "upstream/outbound_route_tests.rs").write_bytes(b"outbound route tests\n")
            (root / "upstream/telemetry.rs").write_bytes(b"telemetry\n")
            (root / "upstream/telemetry_tests.rs").write_bytes(b"telemetry tests\n")
            (root / "upstream/http_probe.rs").write_bytes(b"http_probe\n")
            (root / "upstream/service_check.rs").write_bytes(b"service_check\n")
            (root / "upstream/service_rules.rs").write_bytes(b"service_rules\n")
            (root / "upstream/service_check_tests.rs").write_bytes(b"service_check_tests\n")
            (root / "upstream/inbound.rs").write_bytes(b"inbound\n")
            (root / "upstream/inbound_tests.rs").write_bytes(b"inbound_tests\n")
            (root / "upstream.patch").write_text(
                "--- a/clash-lib/src/lib.rs\n+++ b/clash-lib/src/lib.rs\n@@ -1 +1 @@\n-original\n+patched\n")
            blob = root / "source.tar.gz"
            blob.write_bytes(archive_bytes([("root/clash-lib/src/lib.rs", b"original\n"),
                ("root/LICENSE", b"license"), ("root/NOTICE.md", b"notice")]))
            with patch.object(prepare, "ROOT", root), patch.object(prepare, "ARCHIVE_SHA256", prepare.digest(blob.read_bytes())):
                target = prepare.prepare(blob)
                self.assertEqual((target / "clash-lib/src/lib.rs").read_bytes(), b"patched\n")
                self.assertEqual((target / "clash-lib/src/mobile.rs").read_bytes(), b"mobile\n")
                self.assertEqual((target / "clash-lib/src/mobile/resources.rs").read_bytes(), b"resources\n")
                self.assertEqual((target / "clash-lib/src/mobile/outbound.rs").read_bytes(), b"outbound\n")
                self.assertEqual((target / "clash-lib/src/mobile/outbound_tests.rs").read_bytes(), b"outbound tests\n")
                self.assertEqual((target / "clash-lib/src/mobile/outbound_route_tests.rs").read_bytes(), b"outbound route tests\n")
                self.assertEqual((target / "clash-lib/src/mobile/telemetry.rs").read_bytes(), b"telemetry\n")
                self.assertEqual((target / "clash-lib/src/mobile/telemetry_tests.rs").read_bytes(), b"telemetry tests\n")
                self.assertEqual((target / "LICENSE").read_bytes(), b"license")
                self.assertEqual((target / "NOTICE.md").read_bytes(), b"notice")
                provenance = json.loads((target / "AEROVE_PROVENANCE.json").read_text())
                self.assertEqual(provenance["commit"], prepare.COMMIT)
                self.assertEqual(provenance["resourcesSha256"], prepare.digest(b"resources\n"))
                self.assertEqual(provenance["outboundSha256"], prepare.digest(b"outbound\n"))
                self.assertEqual(provenance["outboundTestsSha256"], prepare.digest(b"outbound tests\n"))
                self.assertEqual(provenance["outboundRouteTestsSha256"], prepare.digest(b"outbound route tests\n"))
                self.assertEqual(provenance["telemetrySha256"], prepare.digest(b"telemetry\n"))
                self.assertEqual(provenance["telemetryTestsSha256"], prepare.digest(b"telemetry tests\n"))
                self.assertEqual(provenance["httpProbeSha256"], prepare.digest(b"http_probe\n"))
                self.assertEqual((target / "clash-lib/src/mobile/http_probe.rs").read_bytes(), b"http_probe\n")
                self.assertEqual(provenance["serviceCheckSha256"], prepare.digest(b"service_check\n"))
                self.assertEqual((target / "clash-lib/src/mobile/service_check.rs").read_bytes(), b"service_check\n")
                self.assertEqual(provenance["serviceRulesSha256"], prepare.digest(b"service_rules\n"))
                self.assertEqual((target / "clash-lib/src/mobile/service_rules.rs").read_bytes(), b"service_rules\n")
                self.assertEqual(provenance["serviceCheckTestsSha256"], prepare.digest(b"service_check_tests\n"))
                self.assertEqual((target / "clash-lib/src/mobile/service_check_tests.rs").read_bytes(), b"service_check_tests\n")
                self.assertEqual(provenance["inboundSha256"], prepare.digest(b"inbound\n"))
                self.assertEqual((target / "clash-lib/src/mobile/inbound.rs").read_bytes(), b"inbound\n")
                self.assertEqual(provenance["inboundTestsSha256"], prepare.digest(b"inbound_tests\n"))
                self.assertEqual((target / "clash-lib/src/mobile/inbound_tests.rs").read_bytes(), b"inbound_tests\n")
                self.assertEqual(provenance["treeSha256"], prepare.tree_digest(target))
                prepare.prepare(blob)
                (target / "clash-lib/src/lib.rs").write_text("tampered")
                prepare.prepare(blob)
                self.assertEqual((target / "clash-lib/src/lib.rs").read_bytes(), b"patched\n")
                (root / "upstream/resources.rs").write_bytes(b"updated resources\n")
                prepare.prepare(blob)
                updated = json.loads((target / "AEROVE_PROVENANCE.json").read_text())
                self.assertEqual(updated["resourcesSha256"], prepare.digest(b"updated resources\n"))
                self.assertEqual((target / "clash-lib/src/mobile/resources.rs").read_bytes(), b"updated resources\n")
                (root / "upstream/outbound.rs").write_bytes(b"updated outbound\n")
                prepare.prepare(blob)
                self.assertEqual((target / "clash-lib/src/mobile/outbound.rs").read_bytes(), b"updated outbound\n")
                (root / "upstream/telemetry.rs").write_bytes(b"updated telemetry\n")
                prepare.prepare(blob)
                updated = json.loads((target / "AEROVE_PROVENANCE.json").read_text())
                self.assertEqual(updated["telemetrySha256"], prepare.digest(b"updated telemetry\n"))
                self.assertEqual((target / "clash-lib/src/mobile/telemetry.rs").read_bytes(), b"updated telemetry\n")
                (root / "upstream/service_rules.rs").write_bytes(b"updated service rules\n")
                prepare.prepare(blob)
                updated = json.loads((target / "AEROVE_PROVENANCE.json").read_text())
                self.assertEqual(updated["serviceRulesSha256"], prepare.digest(b"updated service rules\n"))
                self.assertEqual((target / "clash-lib/src/mobile/service_rules.rs").read_bytes(), b"updated service rules\n")

    def test_prepare_rejects_wrong_archive_hash_before_extract(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            blob = root / "source.tar.gz"
            blob.write_bytes(b"not the pinned source")
            with patch.object(prepare, "ROOT", root), self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
                prepare.prepare(blob)
            self.assertFalse((root / ".upstream/clash-rs").exists())

    def test_checked_in_patch_applies_to_real_pinned_snapshot(self):
        cache = prepare.ROOT / ".upstream" / f"{prepare.COMMIT}.tar.gz"
        if not cache.exists():
            self.skipTest("run prepare.py first to cache the pinned archive")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.assertEqual(prepare.digest(cache.read_bytes()), prepare.ARCHIVE_SHA256)
            prepare.extract(cache.read_bytes(), root)
            prepare.apply_patch(root, (prepare.ROOT / "upstream.patch").read_text())
            self.assertIn("crate::mobile::protect", (root / "clash-lib/src/proxy/utils/socket_helpers.rs").read_text())
            self.assertIn("task.await", (root / "clash-lib/src/proxy/tun/runner.rs").read_text())
            proxy = (root / "clash-lib/src/proxy/mod.rs").read_text()
            tproxy_guard = re.search(r'(#\[cfg\([^\n]+\)\])\npub mod tproxy;', proxy)
            self.assertIsNotNone(tproxy_guard)
            tun = (root / "clash-lib/src/proxy/tun/mod.rs").read_text()
            reexport_guard = re.search(r'(#\[cfg\([^\n]+\)\])[^\n]*\npub use datagram::TunDatagram;', tun)
            self.assertIsNotNone(reexport_guard)
            self.assertEqual(reexport_guard[1], tproxy_guard[1])


def elf_header(machine):
    header = bytearray(20)
    header[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", header, 18, machine)
    return bytes(header)


class AndroidBuildTest(unittest.TestCase):
    def test_elf_checks_class_and_machine(self):
        with tempfile.TemporaryDirectory() as temporary:
            library = Path(temporary) / "lib.so"
            for machine in (62, 183):
                library.write_bytes(elf_header(machine))
                build.check_elf(library, machine)
                with self.assertRaises(ValueError):
                    build.check_elf(library, 62 if machine == 183 else 183)
            library.write_bytes(b"not an ELF")
            with self.assertRaises(ValueError):
                build.check_elf(library, 183)

    def test_both_abis_build_locked_and_create_provenance_parent_for_custom_output(self):
        for target, (abi, compiler_target, machine) in build.TARGETS.items():
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                engine = root / "engine"
                engine.mkdir()
                (engine / "AEROVE_PROVENANCE.json").write_text('{"commit":"fixed"}')
                ndk = root / "ndk"
                compiler = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin" / f"{compiler_target}33-clang"
                compiler.parent.mkdir(parents=True)
                compiler.touch()
                library = root / "target" / target / "release/libaerove_core.so"
                library.parent.mkdir(parents=True)
                library.write_bytes(elf_header(machine))
                output = root / "app/src/main/jniLibs"
                with patch.object(build, "ROOT", root), patch.object(build, "prepare", return_value=engine), \
                    patch.object(build.sys, "platform", "linux"), patch.object(build.subprocess, "run") as run, \
                    patch.object(sys, "argv", ["build.py", "--target", target, "--ndk", str(ndk), "--output", str(output)]):
                    build.main()
                self.assertEqual(run.call_args.args[0], ["cargo", "build", "--locked", "--release", "--target", target])
                self.assertEqual((output / abi / library.name).read_bytes(), elf_header(machine))
                self.assertTrue((root / "build/provenance.json").is_file())
                self.assertIn(f"{compiler_target}33-clang", run.call_args.kwargs["env"][f"CARGO_TARGET_{target.replace('-', '_').upper()}_LINKER"])

    def test_bad_target_abi_combination_rejected_before_fetch(self):
        with patch.object(build, "prepare") as fetch, \
            patch.object(sys, "argv", ["build.py", "--target", "aarch64-linux-android", "--abi", "x86_64"]):
            with self.assertRaises(SystemExit):
                build.main()
            fetch.assert_not_called()

    def test_first_lock_initialization_uses_real_cargo_then_locked_metadata(self):
        with patch.object(build, "prepare"), patch.object(build.subprocess, "run") as run, \
            patch.object(sys, "argv", ["build.py", "--initialize-lock", "--prepare-only"]):
            build.main()
        self.assertEqual(run.call_args_list[0].args[0], ["cargo", "metadata", "--format-version", "1"])
        self.assertEqual(run.call_args_list[1].args[0], ["cargo", "metadata", "--locked", "--format-version", "1"])

    def test_unit_test_entry_only_uses_the_bridge_lock(self):
        with patch.object(build, "prepare"), patch.object(build.subprocess, "run") as run, \
            patch.object(sys, "argv", ["build.py", "--test"]):
            build.main()
        run.assert_called_once()
        self.assertEqual(run.call_args.args[0], ["cargo", "test", "--locked", "--lib"])

    def test_production_geosite_option_verifies_hash_and_runs_the_real_regression(self):
        fixture = Path("/fixed-production-geosite.dat").resolve()
        with patch.object(build, "prepare"), patch.object(build.subprocess, "run") as run, \
            patch.object(build, "verify_geosite", return_value=fixture) as verify, \
            patch.object(sys, "argv", ["build.py", "--test", "--test-geosite", str(fixture)]):
            build.main()
        verify.assert_called_once_with(fixture)
        self.assertEqual(run.call_args.args[0], ["cargo", "test", "--locked", "--lib", "--", "--include-ignored"])
        self.assertEqual(run.call_args.kwargs["env"]["AEROVE_DEFAULT_GEOSITE"], str(fixture))


class ProductionReferenceTest(unittest.TestCase):
    def test_pinned_reference_requires_both_exact_size_and_hash(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Path(temporary) / "geosite.dat"
            fixture.write_bytes(b"a")
            with patch.object(reference_resources, "GEOSITE_BYTES", 1), \
                patch.object(reference_resources, "GEOSITE_SHA256", "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"):
                self.assertEqual(reference_resources.verify_geosite(fixture), fixture.resolve())
                fixture.write_bytes(b"b")
                with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
                    reference_resources.verify_geosite(fixture)
                fixture.write_bytes(b"aa")
                with self.assertRaisesRegex(ValueError, "size mismatch"):
                    reference_resources.verify_geosite(fixture)


if __name__ == "__main__":
    unittest.main()
