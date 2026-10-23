import importlib.util
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile

spec = importlib.util.spec_from_file_location("verify_apk", Path(__file__).with_name("verify_apk.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
HELPER_SOURCE_ROOT = Path(__file__).resolve().parents[1] / "regexp2"


class ApkInspectionTests(unittest.TestCase):
    def verify(self, apk, abi, receipt_overrides=None):
        with tempfile.TemporaryDirectory() as root:
            receipt = Path(root) / "build-receipt.json"
            inputs = {
                path.relative_to(HELPER_SOURCE_ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in HELPER_SOURCE_ROOT.rglob("*")
                if path.is_file() and "__pycache__" not in path.parts and path.suffix.lower() != ".pyc"
            }
            with zipfile.ZipFile(apk) as archive:
                outputs = []
                for name in module.HELPER_LIBRARIES:
                    data = archive.read(f"lib/{abi}/{name}") if f"lib/{abi}/{name}" in archive.namelist() else b""
                    outputs.append({"abi": abi, "path": f"{abi}/{name}",
                        "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)})
            receipt_data = {"inputFiles": inputs, "sourceSha256": hashlib.sha256(
                json.dumps(inputs, sort_keys=True, separators=(",", ":")).encode()).hexdigest(), "outputs": outputs}
            if receipt_overrides:
                receipt_data.update(receipt_overrides)
            receipt.write_text(json.dumps(receipt_data), encoding="utf-8")
            with mock.patch.object(module, "validate_native_receipt", return_value={"meow_source": module.MEOW_SOURCE}):
                return module.verify(apk, abi, HELPER_SOURCE_ROOT, receipt, {})

    def product(self):
        return {"applicationId": "dev.aerove.lite", "version": "0.1.0-beta.1", "versionCode": "1",
            "channel": "preview", "signerSha256": "a" * 64}

    def badging(self, package="dev.aerove.lite", version="0.1.0-beta.1", sdk=33, version_code="1"):
        return f"package: name='{package}' versionCode='{version_code}' versionName='{version}'\nsdkVersion:'{sdk}'\n"

    def read_product(self, **changes):
        product = {**self.product(), **changes}
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "version.properties"
            path.write_text("".join(f"{key}={value}\n" for key, value in product.items()), encoding="utf-8")
            return module.product_version(path)

    def test_preview_accepts_independent_alpha_beta_and_rc_versions(self):
        for version in ("0.0.0-alpha.1", "0.1.0-beta.1", "1.23.45-alpha.2", "0.10.0-rc.11"):
            with self.subTest(version=version):
                self.assertEqual(self.read_product(version=version)["version"], version)

    def test_preview_rejects_noncanonical_and_nonpreview_versions(self):
        for version in (
            "00.1.0-beta.1", "0.01.0-beta.1", "0.1.00-beta.1",
            "0.1-beta.1", "0.1.0", "0.1.0-dev.1", "0.1.0-pre.1",
            "0.1.0-alpha.0", "0.1.0-beta.01", "0.1.0-rc.-1",
            "0.1.0-beta.1.2", "0.1.0-beta.1+debug", "0.1.0-beta.١", "０.1.0-beta.1",
        ):
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, "preview version"):
                self.read_product(version=version)

    def test_version_code_accepts_both_positive_range_boundaries(self):
        for code in ("1", "2100000000"):
            with self.subTest(code=code):
                self.assertEqual(self.read_product(versionCode=code)["versionCode"], code)

    def test_version_code_rejects_noncanonical_ascii_and_out_of_range_values(self):
        for code in ("", "0", "-1", "+1", "01", "1.0", "١", "１", "1_000", " 1", "1 ", "2100000001"):
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, "version code"):
                self.read_product(versionCode=code)

    def test_stable_channel_remains_outside_this_preview_validator(self):
        with self.assertRaisesRegex(ValueError, "release channel"):
            self.read_product(channel="stable", version="0.1.0")

    def test_preview_stage_and_code_match_the_apk_exactly(self):
        signature = "Signer #1 certificate SHA-256 digest: " + "a" * 64
        for version in ("0.1.0-alpha.1", "0.1.0-beta.2", "0.1.0-rc.3"):
            with self.subTest(version=version):
                product = self.read_product(version=version, versionCode="2100000000")
                result = module.verify_identity(
                    self.badging(version=version, version_code="2100000000"), signature, product, "preview")
                self.assertEqual(result["version"], version)
                self.assertEqual(result["version_code"], 2100000000)

    def test_build_info_preserves_acceptance_run_number_and_attempt(self):
        with tempfile.TemporaryDirectory() as root:
            apk = Path(root) / "apk"
            apk.write_bytes(b"original APK bytes")
            info = module.build_info({"version": "0.1.0-beta.1", "version_code": 1},
                {"abi": "arm64-v8a"}, "a" * 40, "b" * 40,
                "https://github.com/IvanSolis1989/Aerove/actions/runs/123", 123, 2, apk)
            self.assertEqual((info["run_number"], info["run_attempt"]), (123, 2))
            self.assertEqual(info["apk_sha256"], hashlib.sha256(apk.read_bytes()).hexdigest())
            for number, attempt in ((0, 2), (1, 0), (True, 2)):
                with self.subTest(number=number, attempt=attempt), self.assertRaisesRegex(ValueError, "run number"):
                    module.build_info({}, {}, "a" * 40, "b" * 40, "workflow", number, attempt, apk)

    def test_preview_rejects_other_signing_certificate(self):
        with self.assertRaisesRegex(ValueError, "signing certificate"):
            module.verify_identity(self.badging(), "Signer #1 certificate SHA-256 digest: " + "b" * 64,
                self.product(), "preview")

    def test_build_tools_37_verified_v2_signer_format_is_accepted(self):
        digest = "09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8"
        signature = "\n".join((
            "Verifies",
            "Verified using v1 scheme (JAR signing): false",
            "Verified using v2 scheme (APK Signature Scheme v2): true",
            "Verified using v3 scheme (APK Signature Scheme v3): false",
            "Verified using v3.1 scheme (APK Signature Scheme v3.1): false",
            "Verified using v3.2 scheme (APK Signature Scheme v3.2): false",
            "Verified using v4 scheme (APK Signature Scheme v4): false",
            "Number of signers: 1",
            f"V2 Signer: certificate SHA-256 digest: {digest}",
        ))
        product = {**self.product(), "signerSha256": digest}
        result = module.verify_identity(self.badging(), signature, product, "preview")
        self.assertEqual(result["signer_sha256"], digest)

    def test_build_tools_37_rejects_wrong_certificate_and_ambiguous_output(self):
        digest = "09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8"
        prefix = "Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\n"
        signer = f"V2 Signer: certificate SHA-256 digest: {digest}"
        product = {**self.product(), "signerSha256": digest}
        wrong_certificate = prefix + "Number of signers: 1\n" + signer.replace(digest, "b" * 64)
        with self.assertRaisesRegex(ValueError, "signing certificate"):
            module.verify_identity(self.badging(), wrong_certificate, product, "preview")
        for incomplete in (
            signer,
            "Verifies\n" + signer,
            prefix + signer,
            "Number of signers: 1\n" + signer,
        ):
            with self.subTest(signature=incomplete), self.assertRaisesRegex(ValueError, "signing certificate"):
                module.verify_identity(self.badging(), incomplete, product, "preview")
        for signature in (
            prefix + "Number of signers: 2\n" + signer,
            prefix + "Number of signers: 1\n" + signer + "\n" + signer,
            prefix + "Number of signers: 1\n" + signer + "\nSigner #1 certificate SHA-256 digest: " + digest,
            prefix.replace("true", "false") + "Number of signers: 1\n" + signer,
            prefix + "Number of signers: 1\n" + signer + "\nVerifies",
        ):
            with self.subTest(signature=signature), self.assertRaisesRegex(ValueError, "signing certificate"):
                module.verify_identity(self.badging(), signature, product, "preview")

    def test_preview_rejects_debug_package_and_old_version(self):
        signature = "Signer #1 certificate SHA-256 digest: " + "a" * 64
        for package, version in (("dev.aerove.lite.dev", "0.1.0-beta.1+debug"), ("dev.aerove.lite", "0.1.0-dev.1")):
            with self.subTest(package=package, version=version), self.assertRaisesRegex(ValueError, "identity or version"):
                module.verify_identity(self.badging(package, version), signature, self.product(), "preview")

    def test_preview_identity_checks_minimum_sdk_and_unique_signer(self):
        signature = "Signer #1 certificate SHA-256 digest: " + "a" * 64
        self.assertEqual(module.verify_identity(self.badging(), signature, self.product(), "preview")["version"], "0.1.0-beta.1")
        with self.assertRaisesRegex(ValueError, "minimum SDK"):
            module.verify_identity(self.badging(sdk=32), signature, self.product(), "preview")
        with self.assertRaisesRegex(ValueError, "signing certificate"):
            module.verify_identity(self.badging(), signature + "\n" + signature, self.product(), "preview")

    def test_duplicate_version_identity_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "version.properties"
            path.write_text("applicationId=dev.aerove.lite\napplicationId=other\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate"):
                module.product_version(path)

    def make_apk(self, root, abi="arm64-v8a", machine=183, duplicate=False, include_core=True):
        path = Path(root) / "candidate.apk"
        image = self.elf64(machine)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest fixture")
            archive.writestr("classes.dex", b"dex fixture")
            if include_core:
                archive.writestr(f"lib/{abi}/libaerove_meow_core.so", self.core_elf(machine))
            for name in module.HELPER_LIBRARIES:
                archive.writestr(f"lib/{abi}/{name}", image)
            archive.writestr("assets/licenses/client-helper/dependencies.json", (HELPER_SOURCE_ROOT / "dependencies.json").read_bytes())
            for source in (HELPER_SOURCE_ROOT / "licenses").rglob("*"):
                if source.is_file():
                    relative = source.relative_to(HELPER_SOURCE_ROOT / "licenses").as_posix()
                    archive.writestr("assets/licenses/client-helper/" + relative, source.read_bytes())
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    archive.writestr("AndroidManifest.xml", b"second manifest")
        return path

    def elf64(self, machine=183, p_align=16384, loadable=True):
        image = bytearray(120)
        image[:16] = b"\x7fELF\x02\x01\x01" + bytes(9)
        struct.pack_into("<HHI", image, 16, 3, machine, 1)
        struct.pack_into("<Q", image, 32, 64)
        struct.pack_into("<HHH", image, 52, 64, 56, 1)
        struct.pack_into("<IIQQQQQQ", image, 64, 1 if loadable else 4, 5, 0, 0, 0, len(image), len(image), p_align)
        return bytes(image)

    def core_elf(self, machine=183, symbols=None, undefined=False):
        symbols = module.CORE_JNI_SYMBOLS if symbols is None else symbols
        image = bytearray(self.elf64(machine))
        strings = b"\0" + b"".join(name.encode()+b"\0" for name in symbols)
        string_offset = len(image); image.extend(strings)
        symbol_offset = len(image); image.extend(bytes(24))
        cursor = 1
        for name in symbols:
            image.extend(struct.pack("<IBBHQQ", cursor, 18, 0, 0 if undefined else 1, 1, 1))
            cursor += len(name)+1
        section_offset = len(image); image.extend(bytes(64))
        image.extend(struct.pack("<IIQQQQIIQQ", 0, 3, 0, 0, string_offset, len(strings), 0, 0, 1, 0))
        image.extend(struct.pack("<IIQQQQIIQQ", 0, 11, 0, 0, symbol_offset, (len(symbols)+1)*24, 1, 0, 8, 24))
        struct.pack_into("<Q", image, 40, section_offset)
        struct.pack_into("<HH", image, 58, 64, 3)
        return bytes(image)

    def test_core_exports_require_defined_dynamic_functions_not_strings_or_imports(self):
        self.assertEqual(set(module.CORE_JNI_SYMBOLS), module.exported_functions(self.core_elf()))
        self.assertEqual(set(), module.exported_functions(self.core_elf(undefined=True)))
        self.assertEqual(set(), module.exported_functions(self.core_elf(symbols=[])+b"".join(x.encode() for x in module.CORE_JNI_SYMBOLS)))
        with self.assertRaises(ValueError): module.exported_functions(b"not an ELF")

    def test_packaged_core_missing_meow_jni_is_rejected_before_receipt(self):
        with tempfile.TemporaryDirectory() as temp:
            apk=self.make_apk(temp)
            with zipfile.ZipFile(apk) as archive:
                entries={name:archive.read(name) for name in archive.namelist()}
            core='lib/arm64-v8a/'+module.CORE_LIBRARY
            entries[core]=self.core_elf(symbols=module.CORE_JNI_SYMBOLS[1:])
            with zipfile.ZipFile(apk,'w') as archive:
                for name,data in entries.items():archive.writestr(name,data)
            with self.assertRaisesRegex(ValueError,'Meow JNI exports missing.*startOwned'):
                self.verify(apk,'arm64-v8a')

    def test_packaged_core_requires_await_exit_runtime_export(self):
        symbol = "Java_dev_aerove_lite_core_MeowNativeCore_awaitExit"
        self.assertIn(symbol, module.CORE_JNI_SYMBOLS)
        self.assertEqual(19, len(module.CORE_JNI_SYMBOLS))
        with tempfile.TemporaryDirectory() as temp:
            apk = self.make_apk(temp)
            self.assertEqual("arm64-v8a", self.verify(apk, "arm64-v8a")["abi"])
            with zipfile.ZipFile(apk) as archive:
                entries = {name: archive.read(name) for name in archive.namelist()}
            entries["lib/arm64-v8a/" + module.CORE_LIBRARY] = self.core_elf(
                symbols=[name for name in module.CORE_JNI_SYMBOLS if name != symbol])
            with zipfile.ZipFile(apk, "w") as archive:
                for name, data in entries.items(): archive.writestr(name, data)
            with self.assertRaisesRegex(ValueError, "Meow JNI exports missing.*awaitExit"):
                self.verify(apk, "arm64-v8a")

    def test_packaged_core_requires_listener_command_runtime_export(self):
        symbol = "Java_dev_aerove_lite_core_MeowNativeCore_listenerCommand"
        self.assertIn(symbol, module.CORE_JNI_SYMBOLS)
        self.assertEqual(19, len(module.CORE_JNI_SYMBOLS))
        with tempfile.TemporaryDirectory() as temp:
            apk = self.make_apk(temp)
            self.assertEqual("arm64-v8a", self.verify(apk, "arm64-v8a")["abi"])
            with zipfile.ZipFile(apk) as archive:
                entries = {name: archive.read(name) for name in archive.namelist()}
            entries["lib/arm64-v8a/" + module.CORE_LIBRARY] = self.core_elf(
                symbols=[name for name in module.CORE_JNI_SYMBOLS if name != symbol])
            with zipfile.ZipFile(apk, "w") as archive:
                for name, data in entries.items(): archive.writestr(name, data)
            with self.assertRaisesRegex(ValueError, "Meow JNI exports missing.*listenerCommand"):
                self.verify(apk, "arm64-v8a")

    def test_packaged_core_requires_all_frozen_runtime_and_script_exports(self):
        runtime = ("startOwned", "status", "listenerStatus", "awaitReady", "stop", "awaitExit", "listenerCommand",
            "configurePolicies", "policyStatus", "capabilities", "validate", "inspectRuleSet", "dumpRuleSet", "validateResource", "hostPolicyCommand")
        expected = tuple("Java_dev_aerove_lite_core_MeowNativeCore_" + name for name in runtime) + tuple(
            "Java_dev_aerove_lite_overrides_NativeScriptEngine_native" + name for name in ("Begin", "Evaluate", "Cancel", "Release"))
        self.assertEqual(expected, module.CORE_JNI_SYMBOLS)
        self.assertEqual(19, len(set(expected)))
        with tempfile.TemporaryDirectory() as temp:
            apk = self.make_apk(temp)
            self.assertEqual("arm64-v8a", self.verify(apk, "arm64-v8a")["abi"])
            with zipfile.ZipFile(apk) as archive:
                original = {name: archive.read(name) for name in archive.namelist()}
            for omitted in expected:
                with self.subTest(omitted=omitted):
                    entries = dict(original)
                    entries["lib/arm64-v8a/" + module.CORE_LIBRARY] = self.core_elf(
                        symbols=[name for name in expected if name != omitted])
                    with zipfile.ZipFile(apk, "w") as archive:
                        for name, data in entries.items(): archive.writestr(name, data)
                    with self.assertRaises(ValueError) as caught:
                        self.verify(apk, "arm64-v8a")
                    self.assertEqual("Meow JNI exports missing: " + omitted, str(caught.exception))

    def test_native_receipt_requires_accepted_policy_exact_source_and_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            def write(name,value):
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True)
                path.write_text(json.dumps(value) if isinstance(value,dict) else value)
                return module.sha256(path)
            manifest=write('Cargo.toml','manifest');write('Cargo.lock','lock')
            patch=write('patches/core.patch','patch')
            overlay=write('source-metadata/overlay.json',dict(patch='patches/core.patch',patchSha256=patch,adaptedFileManifestSha256='d'*64))
            write('source.json',dict(commit=module.MEOW_SOURCE,tag='v0.26.1',archiveSha256='a'*64))
            policy=dict(resolveLock=False,manifestSha256=manifest,overlay='source-metadata/overlay.json',overlaySha256=overlay,targets=['lib','proxy_runtime'])
            write('build-policy.json',policy);identity=module.native_identity(root)
            receipt=dict(command=["cargo","test","--locked","--lib","--test","proxy_runtime","--","--test-threads=1","--nocapture"],schema=1,commandExit=0,acceptedLock=True,bootstrapRequested=False,resolveLockRequested=False,
                lockUnchangedDuringLockedCommand=True,toolchain='rustc 1.98.1 (fixture)',androidTarget=None,representativeTargets=policy['targets'],
                declaredSourceSpecSha256=identity['meow_source_spec_sha256'],bridgeManifestSha256=manifest,buildPolicySha256=identity['meow_policy_sha256'],
                lockSha256Before=identity['meow_lock_sha256'],resolvedLockSha256=identity['meow_lock_sha256'],lockSha256After=identity['meow_lock_sha256'],
                source=dict(commit=module.MEOW_SOURCE,tag='v0.26.1',archiveHashBootstrapped=False,archiveSha256='a'*64,
                    fileManifestSha256='d'*64,overlaySha256=overlay,patchSha256=patch))
            self.assertEqual(identity,module.validate_native_receipt(receipt,root=root))
            for changes in [dict(acceptedLock=False),dict(resolveLockRequested=True),dict(lockSha256Before='f'*64),dict(representativeTargets=['lib']),dict(toolchain='rustc 1.99.0')]:
                with self.subTest(changes=changes),self.assertRaises(ValueError):module.validate_native_receipt(dict(receipt,**changes),root=root)
            image=self.core_elf();android=dict(receipt,command=['cargo','build','--locked','--release','--target','aarch64-linux-android'],androidTarget='aarch64-linux-android',ndkSourceProperties='Pkg.Revision = 28.2.13676358',
                output=dict(abi='arm64-v8a',sha256=hashlib.sha256(image).hexdigest(),bytes=len(image),minPageAlignment=16384))
            module.validate_native_receipt(android,'arm64-v8a',image,root)
            with self.assertRaises(ValueError):module.validate_native_receipt(android,'arm64-v8a',image+b'changed',root)
            with self.assertRaises(ValueError):module.validate_native_receipt(android,'arm64-v8a',image,root,dict(workflowRun='123'))
            boundary_command = ['cargo','test','--locked','--no-default-features','--lib',
                'capabilities::tests::','--','--test-threads=1','--nocapture']
            feature_policy = dict(policy,featureBoundary='capabilities-no-default')
            policy_hash = write('build-policy.json',feature_policy)
            host = dict(receipt,buildPolicySha256=policy_hash,
                featureBoundaryCommand=boundary_command,featureBoundaryExit=0)
            module.validate_native_receipt(host,root=root)
            # Android must not manufacture host-only cfg-off execution evidence.
            module.validate_native_receipt(dict(android,buildPolicySha256=policy_hash),'arm64-v8a',image,root)
            for changes in [dict(featureBoundaryCommand=None),dict(featureBoundaryCommand=boundary_command[:-1]),
                    dict(featureBoundaryCommand=['cargo','test','--lib']),dict(featureBoundaryExit=None),
                    dict(featureBoundaryExit=1),dict(featureBoundaryExit=False),dict(featureBoundaryExit='0')]:
                with self.subTest(boundary=changes),self.assertRaisesRegex(ValueError,'feature boundary proof'):
                    module.validate_native_receipt(dict(host,**changes),root=root)
            for omitted in ['featureBoundaryCommand','featureBoundaryExit']:
                missing = dict(host);missing.pop(omitted)
                with self.subTest(omitted=omitted),self.assertRaisesRegex(ValueError,'feature boundary proof'):
                    module.validate_native_receipt(missing,root=root)
            for unknown in ['other',None,True]:
                write('build-policy.json',dict(policy,featureBoundary=unknown))
                with self.subTest(unknown=unknown),self.assertRaisesRegex(ValueError,'Unknown Meow feature boundary'):
                    module.native_identity(root)
            write('build-policy.json',dict(policy,resolveLock=True))
            with self.assertRaisesRegex(ValueError,'accepted committed lock'):module.native_identity(root)

    def test_rejects_wrong_elf_even_under_arm64_filename(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(ValueError, "ELF"):
                self.verify(self.make_apk(root, machine=62), "arm64-v8a")

    def test_rejects_missing_real_core(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(ValueError, "core is missing"):
                self.verify(self.make_apk(root, include_core=False), "arm64-v8a")

    def test_rejects_ambiguous_zip(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                self.verify(self.make_apk(root, duplicate=True), "arm64-v8a")

    def test_rejects_other_abi(self):
        with tempfile.TemporaryDirectory() as root:
            apk = self.make_apk(root)
            with zipfile.ZipFile(apk, "a") as archive:
                archive.writestr("lib/x86_64/libextra.so", b"foreign ABI")
            with self.assertRaisesRegex(ValueError, "Unexpected ABI"):
                self.verify(apk, "arm64-v8a")

    def test_requires_both_client_helper_libraries_in_selected_abi(self):
        with tempfile.TemporaryDirectory() as root:
            path = self.make_apk(root)
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("lib/arm64-v8a/libaerove_filter_jni.so", b"duplicate")
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                self.verify(path, "arm64-v8a")
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "missing-helper.apk"
            original = self.make_apk(root)
            with zipfile.ZipFile(original) as source, zipfile.ZipFile(path, "w") as target:
                for item in source.infolist():
                    if item.filename.endswith("libaerove_filter_helper.so"):
                        continue
                    target.writestr(item, source.read(item.filename))
            with self.assertRaisesRegex(ValueError, "helper library is missing"):
                self.verify(path, "arm64-v8a")

    def test_x86_64_requires_matching_helper_abi_and_valid_elf_machine(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual(self.verify(self.make_apk(root, abi="x86_64", machine=62), "x86_64")["abi"], "x86_64")
        with tempfile.TemporaryDirectory() as root:
            apk = self.make_apk(root, abi="x86_64", machine=62)
            rewritten = Path(root) / "wrong-helper-abi.apk"
            with zipfile.ZipFile(apk) as source, zipfile.ZipFile(rewritten, "w") as dest:
                for item in source.infolist():
                    if item.filename in {f"lib/x86_64/{name}" for name in module.HELPER_LIBRARIES}:
                        dest.writestr(item.filename.replace("lib/x86_64/", "lib/arm64-v8a/"), source.read(item.filename))
                    else:
                        dest.writestr(item, source.read(item.filename))
            with self.assertRaisesRegex(ValueError, "helper library is missing"):
                self.verify(rewritten, "x86_64")

    def test_helper_must_be_real_elf64_loadable_and_aligned_for_16k_pages(self):
        for label, image in (
            ("truncated", b"\x7fELF\x02\x01\x01" + bytes(13)),
            ("no-load", self.elf64(loadable=False)),
            ("unaligned", self.elf64(p_align=4096)),
            ("wrong-machine", self.elf64(machine=62)),
        ):
            with self.subTest(label=label), tempfile.TemporaryDirectory() as root:
                apk = self.make_apk(root)
                target = f"lib/arm64-v8a/{module.HELPER_LIBRARIES[0]}"
                rewritten = Path(root) / "rewritten.apk"
                with zipfile.ZipFile(apk) as source, zipfile.ZipFile(rewritten, "w") as dest:
                    for item in source.infolist():
                        dest.writestr(item, image if item.filename == target else source.read(item.filename))
                with self.assertRaisesRegex(ValueError, "ELF architecture"):
                    self.verify(rewritten, "arm64-v8a")

    def test_requires_complete_and_matching_helper_license_manifest(self):
        for remove in ("assets/licenses/client-helper/dependencies.json",
                       "assets/licenses/client-helper/github.com_metacubex_age/LICENSE"):
            with self.subTest(remove=remove), tempfile.TemporaryDirectory() as root:
                apk = self.make_apk(root)
                rewritten = Path(root) / "rewritten.apk"
                with zipfile.ZipFile(apk) as source, zipfile.ZipFile(rewritten, "w") as dest:
                    for item in source.infolist():
                        if item.filename != remove:
                            dest.writestr(item, source.read(item.filename))
                with self.assertRaisesRegex(ValueError, "license|manifest"):
                    self.verify(rewritten, "arm64-v8a")

    def test_helper_readback_reports_hash_size_and_source_receipt_identity(self):
        with tempfile.TemporaryDirectory() as root:
            apk = self.make_apk(root)
            result = self.verify(apk, "arm64-v8a")
            self.assertEqual(set(result["client_helpers"]), set(module.HELPER_LIBRARIES))
            for name, details in result["client_helpers"].items():
                self.assertEqual(details["size_bytes"], 120)
                self.assertEqual(details["sha256"], hashlib.sha256(self.elf64()).hexdigest())
            input_files = {
                path.relative_to(HELPER_SOURCE_ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in HELPER_SOURCE_ROOT.rglob("*")
                if path.is_file() and "__pycache__" not in path.parts and path.suffix.lower() != ".pyc"
            }
            expected_source = hashlib.sha256(json.dumps(input_files, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
            self.assertEqual(result["client_helper_source_sha256"], expected_source)

    def test_helper_receipt_must_match_packaged_bytes_and_artifact_name_is_abi_specific(self):
        self.assertEqual(module.artifact_name("arm64-v8a", "preview"), "AEROVE-Lite-android-arm64.apk")
        self.assertEqual(module.artifact_name("arm64-v8a", "development"), "AEROVE-Lite-dev-arm64.apk")
        self.assertEqual(module.artifact_name("x86_64", "preview"), "AEROVE-Lite-test-x86_64.apk")
        with tempfile.TemporaryDirectory() as root:
            apk = self.make_apk(root)
            receipt = {"outputs": [{"abi": "arm64-v8a", "path": name,
                "sha256": "0" * 64, "bytes": 120} for name in module.HELPER_LIBRARIES]}
            with self.assertRaisesRegex(ValueError, "receipt does not match APK"):
                self.verify(apk, "arm64-v8a", receipt)


if __name__ == "__main__":
    unittest.main()
