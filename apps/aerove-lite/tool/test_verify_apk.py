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
            for name in module.BUNDLED_SCRIPT_FILES:
                archive.writestr("assets/scripts/" + name, (module.BUNDLED_SCRIPT_ROOT / "app/src/main/assets/scripts" / name).read_bytes())
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
        self.assertEqual(26, len(module.CORE_JNI_SYMBOLS))
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
        self.assertEqual(26, len(module.CORE_JNI_SYMBOLS))
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
        self.assertEqual(expected + module.RESOURCE_JNI_SYMBOLS, module.CORE_JNI_SYMBOLS)
        self.assertEqual(19, len(set(expected)))
        with tempfile.TemporaryDirectory() as temp:
            apk = self.make_apk(temp)
            self.assertEqual("arm64-v8a", self.verify(apk, "arm64-v8a")["abi"])
            with zipfile.ZipFile(apk) as archive:
                original = {name: archive.read(name) for name in archive.namelist()}
            for omitted in module.CORE_JNI_SYMBOLS:
                with self.subTest(omitted=omitted):
                    entries = dict(original)
                    entries["lib/arm64-v8a/" + module.CORE_LIBRARY] = self.core_elf(
                        symbols=[name for name in module.CORE_JNI_SYMBOLS if name != omitted])
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
            new_targets = list(module.HOST_NO_FAIL_FAST_TARGETS)
            next_policy = dict(feature_policy,targets=new_targets)
            next_command = ['cargo','test','--locked','--no-fail-fast']
            for target in new_targets:
                next_command += ['--lib'] if target == 'lib' else ['--test',target]
            next_command += ['--','--test-threads=1','--nocapture']
            next_host = dict(host,buildPolicySha256=write('build-policy.json',next_policy),
                representativeTargets=new_targets,command=next_command)
            module.validate_native_receipt(next_host,root=root)
            for command in [next_command[:3]+next_command[4:],next_command[:3]+next_command[4:-3]+['--no-fail-fast']+next_command[-3:]]:
                with self.subTest(command=command),self.assertRaisesRegex(ValueError,'complete approved suite'):
                    module.validate_native_receipt(dict(next_host,command=command),root=root)
            unsupported = dict(next_policy,targets=new_targets[:-1])
            with self.assertRaisesRegex(ValueError,'target contract'):
                module.validate_native_receipt(dict(next_host,buildPolicySha256=write('build-policy.json',unsupported)),root=root)
            for unknown in ['other',None,True]:
                write('build-policy.json',dict(policy,featureBoundary=unknown))
                with self.subTest(unknown=unknown),self.assertRaisesRegex(ValueError,'Unknown Meow feature boundary'):
                    module.native_identity(root)
            write('build-policy.json',dict(policy,resolveLock=True))
            with self.assertRaisesRegex(ValueError,'accepted committed lock'):module.native_identity(root)

    def test_policy_runtime_requires_complete_locked_core_units(self):
        import copy
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            def write(name, value):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps(value) if isinstance(value, dict) else value)
                return module.sha256(path)
            manifest = write('Cargo.toml', 'manifest')
            lock = write('Cargo.lock', 'bridge-lock')
            core_lock = write('core-tests.lock', 'independent-core-test-lock')
            patch = write('patches/core.patch', 'patch')
            upstream_lock = hashlib.sha256(b'original-upstream-lock').hexdigest()
            overlay = write('source-metadata/overlay.json', dict(patch='patches/core.patch',
                patchSha256=patch, adaptedFileManifestSha256='d'*64, files={'Cargo.lock': upstream_lock}))
            write('source.json', dict(commit=module.MEOW_SOURCE, tag='v0.26.1', archiveSha256='a'*64))
            targets = list(module.HOST_POLICY_RUNTIME_TARGETS)
            self.assertEqual(29, len(targets))
            self.assertEqual(22, len(module.CORE_POLICY_TEST_CASES))
            self.assertEqual(24, sum(count for _, _, count in module.CORE_POLICY_TEST_CASES))
            policy = dict(resolveLock=False, manifestSha256=manifest, overlay='source-metadata/overlay.json',
                overlaySha256=overlay, targets=targets, featureBoundary='capabilities-no-default',
                coreTests='policy-runtime-v1', coreTestLockSha256=core_lock)
            policy_hash = write('build-policy.json', policy)
            identity = module.native_identity(root)
            command = ['cargo', 'test', '--locked', '--no-fail-fast']
            for target in targets:
                command += ['--lib'] if target == 'lib' else ['--test', target]
            command += ['--', '--test-threads=1', '--nocapture']
            features = {package: [] for package in module.CORE_POLICY_TEST_PACKAGES}
            features['meow-proxy'] = ['tls']
            entries = []
            for package, name, count in module.CORE_POLICY_TEST_CASES:
                args = ['cargo', 'test', '--manifest-path', '.upstream/meow-rs/Cargo.toml', '--locked',
                    '--package', package, '--lib', '--no-default-features']
                if features[package]:
                    args += ['--features', ','.join(package+'/'+f for f in features[package])]
                entries.append(dict(package=package, filter=name,
                    command=args+[name, '--', '--test-threads=1', '--nocapture'], exit=0,
                    expectedPass=count, passed=count, stdoutSha256='e'*64, stderrSha256='f'*64))
            state = dict(mode='policy-runtime-v1', accepted=True, upstreamLockRestored=True,
                upstreamLockBeforeSha256=upstream_lock, seedSha256=core_lock, resolvedLockSha256=core_lock,
                bridgeMetadataCommand=['cargo', 'metadata', '--locked', '--format-version', '1'], bridgeMetadataExit=0,
                metadataCommand=['cargo', 'metadata', '--manifest-path', '.upstream/meow-rs/Cargo.toml',
                    '--format-version', '1', '--locked'], metadataExit=0,
                workspaceMembers=list(module.CORE_POLICY_TEST_PACKAGES), features=features, commands=entries)
            receipt = dict(schema=1, command=command, commandExit=0, acceptedLock=True,
                bootstrapRequested=False, resolveLockRequested=False, lockUnchangedDuringLockedCommand=True,
                toolchain='rustc 1.98.1 (fixture)', androidTarget=None, representativeTargets=targets,
                declaredSourceSpecSha256=identity['meow_source_spec_sha256'], bridgeManifestSha256=manifest,
                buildPolicySha256=policy_hash, lockSha256Before=lock, resolvedLockSha256=lock, lockSha256After=lock,
                source=dict(commit=module.MEOW_SOURCE, tag='v0.26.1', archiveHashBootstrapped=False,
                    archiveSha256='a'*64, fileManifestSha256='d'*64, overlaySha256=overlay, patchSha256=patch),
                featureBoundaryCommand=['cargo', 'test', '--locked', '--no-default-features', '--lib',
                    'capabilities::tests::', '--', '--test-threads=1', '--nocapture'], featureBoundaryExit=0,
                coreTests=state)
            self.assertEqual(identity, module.validate_native_receipt(receipt, root=root))
            for omitted in state:
                bad = copy.deepcopy(state); bad.pop(omitted)
                with self.subTest(omitted=omitted), self.assertRaises(ValueError):
                    module.validate_native_receipt(dict(receipt, coreTests=bad), root=root)
            for key, value in [('mode','other'), ('accepted',False), ('upstreamLockRestored',False),
                    ('upstreamLockBeforeSha256','0'*64), ('seedSha256',lock), ('resolvedLockSha256',lock),
                    ('bridgeMetadataExit',False), ('bridgeMetadataExit',1), ('metadataExit','0'),
                    ('metadataExit',1), ('metadataCommand',state['metadataCommand'][:-1]),
                    ('workspaceMembers',[]), ('features',{}), ('commands',entries[:-1])]:
                with self.subTest(key=key,value=value), self.assertRaises(ValueError):
                    module.validate_native_receipt(dict(receipt, coreTests=dict(state, **{key:value})), root=root)
            for index in range(len(entries)):
                for key, value in [('exit',1), ('exit',False), ('passed',0), ('expectedPass',0),
                        ('command',entries[index]['command'][:-1]), ('filter','arbitrary'),
                        ('stdoutSha256',None), ('stderrSha256','wrong')]:
                    bad = copy.deepcopy(state); bad['commands'][index][key] = value
                    with self.subTest(index=index,key=key,value=value), self.assertRaises(ValueError):
                        module.validate_native_receipt(dict(receipt, coreTests=bad), root=root)
            for bad in [None, {}, dict(state, commands=list(reversed(entries)))]:
                with self.subTest(core=bad), self.assertRaises(ValueError):
                    module.validate_native_receipt(dict(receipt, coreTests=bad), root=root)
            for values in [['tls','tls'], ['tls; arbitrary'], ['z','a']]:
                bad = copy.deepcopy(state); bad['features']['meow-proxy'] = values
                with self.subTest(features=values), self.assertRaises(ValueError):
                    module.validate_native_receipt(dict(receipt, coreTests=bad), root=root)
            for changes in [dict(coreTests='other'), dict(coreTestLockSha256='f'*64),
                    dict(targets=targets[:-1]), dict(featureBoundary=None)]:
                write('build-policy.json',dict(policy,**changes))
                with self.subTest(policy=changes), self.assertRaises(ValueError):
                    module.native_identity(root)
            for omitted in ['coreTests','coreTestLockSha256']:
                bad = dict(policy); bad.pop(omitted); write('build-policy.json',bad)
                with self.subTest(policyOmitted=omitted), self.assertRaises(ValueError):
                    module.native_identity(root)
            write('build-policy.json',policy)
            # Android receipts do not claim these host-only Core executions.
            image=self.core_elf();android=dict(receipt,command=['cargo','build','--locked','--release','--target','aarch64-linux-android'],
                androidTarget='aarch64-linux-android',ndkSourceProperties='Pkg.Revision = 28.2.13676358',
                output=dict(abi='arm64-v8a',sha256=hashlib.sha256(image).hexdigest(),bytes=len(image),minPageAlignment=16384))
            android.pop('coreTests'); module.validate_native_receipt(android,'arm64-v8a',image,root)

    def test_core_v2_requires_authored_targets_features_and_counts(self):
        import copy
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            (root/'overlay.json').write_text(json.dumps({'files':{'Cargo.lock':'a'*64}}))
            policy=dict(coreTests='policy-runtime-v2',coreTestLockSha256='b'*64,overlay='overlay.json')
            cases=module.CORE_POLICY_TEST_CASES_V2
            self.assertEqual(14,len(cases))
            self.assertEqual(29,sum(c for _,_,c,_,_ in cases))
            features={package:sorted({f for p,_,_,_,required in cases if p==package for f in required})
                for package in module.CORE_POLICY_TEST_PACKAGES}
            commands=[]
            for package,name,count,target,_ in cases:
                command=['cargo','test','--manifest-path','.upstream/meow-rs/Cargo.toml','--locked','--package',package]
                command += ['--lib'] if target=='lib' else ['--test','transport_scope_compat']
                command += ['--no-default-features']
                if features[package]:
                    command += ['--features',','.join(package+'/'+f for f in features[package])]
                if name is not None:command += [name]
                command += ['--','--test-threads=1','--nocapture']
                commands.append(dict(package=package,filter=name,target=target,command=command,
                    expectedPass=count,passed=count,exit=0,stdoutSha256='c'*64,stderrSha256='d'*64))
            state=dict(mode='policy-runtime-v2',accepted=True,upstreamLockRestored=True,
                upstreamLockBeforeSha256='a'*64,seedSha256='b'*64,resolvedLockSha256='b'*64,
                bridgeMetadataCommand=['cargo','metadata','--locked','--format-version','1'],bridgeMetadataExit=0,
                metadataCommand=['cargo','metadata','--manifest-path','.upstream/meow-rs/Cargo.toml',
                    '--format-version','1','--locked'],metadataExit=0,
                workspaceMembers=list(module.CORE_POLICY_TEST_PACKAGES),features=features,commands=commands)
            module.validate_core_test_receipt(state,policy,root)
            integration=next(i for i,x in enumerate(commands) if x['target']=='transport_scope_compat')
            self.assertEqual('meow-common',commands[integration]['package'])
            self.assertIsNone(commands[integration]['filter'])
            self.assertEqual(3,commands[integration]['passed'])
            self.assertIn('meow-common/aerove-android-runtime',commands[integration]['command'])
            self.assertNotIn('--lib',commands[integration]['command'])
            for index in range(len(commands)):
                for key,value in [('target','arbitrary'),('target',None),('exit',1),('exit',False),
                        ('passed',0),('expectedPass',0),('command',commands[index]['command'][:-1]),
                        ('filter','arbitrary'),('stdoutSha256',None)]:
                    bad=copy.deepcopy(state);bad['commands'][index][key]=value
                    with self.subTest(index=index,key=key,value=value),self.assertRaises(ValueError):
                        module.validate_core_test_receipt(bad,policy,root)
            for omitted in ['filter','target']:
                bad=copy.deepcopy(state);bad['commands'][integration].pop(omitted)
                with self.subTest(omitted=omitted),self.assertRaises(ValueError):
                    module.validate_core_test_receipt(bad,policy,root)
            bad=copy.deepcopy(state);cmd=bad['commands'][integration]['command'];pos=cmd.index('--test');cmd[pos:pos+2]=['--lib']
            with self.assertRaises(ValueError):module.validate_core_test_receipt(bad,policy,root)
            for package,_,_,_,required in cases:
                for feature in required:
                    bad=copy.deepcopy(state);bad['features'][package].remove(feature)
                    with self.subTest(package=package,feature=feature),self.assertRaises(ValueError):
                        module.validate_core_test_receipt(bad,policy,root)
            for key,value in [('mode','policy-runtime-v1'),('accepted',False),('upstreamLockRestored',False),
                    ('seedSha256','e'*64),('resolvedLockSha256','e'*64),('metadataExit',1),
                    ('metadataCommand',state['metadataCommand'][:-1]),('commands',commands[:-1]),
                    ('commands',list(reversed(commands)))]:
                with self.subTest(key=key),self.assertRaises(ValueError):
                    module.validate_core_test_receipt(dict(state,**{key:value}),policy,root)
            with self.assertRaises(ValueError):
                module.validate_core_test_receipt(state,dict(policy,coreTests='arbitrary'),root)
            # Full host admission uses the final33-target contract, not the historical29.
            def write(name,value):
                path=root/name;path.write_text(json.dumps(value) if isinstance(value,dict) else value)
                return module.sha256(path)
            manifest=write('Cargo.toml','manifest');lock=write('Cargo.lock','bridge-lock')
            core_lock=write('core-tests.lock','independent-core-v2-lock');patch=write('patch','patch')
            overlay=write('overlay.json',dict(files={'Cargo.lock':'a'*64},patch='patch',
                patchSha256=patch,adaptedFileManifestSha256='d'*64))
            write('source.json',dict(commit=module.MEOW_SOURCE,tag='v0.26.1',archiveSha256='e'*64))
            targets=list(module.HOST_POLICY_RUNTIME_V2_TARGETS);self.assertEqual(33,len(targets))
            policy=dict(resolveLock=False,manifestSha256=manifest,overlay='overlay.json',overlaySha256=overlay,
                targets=targets,featureBoundary='capabilities-no-default',coreTests='policy-runtime-v2',coreTestLockSha256=core_lock)
            policy_hash=write('build-policy.json',policy);identity=module.native_identity(root)
            state=dict(state,seedSha256=core_lock,resolvedLockSha256=core_lock)
            command=['cargo','test','--locked','--no-fail-fast']
            for target in targets:command += ['--lib'] if target=='lib' else ['--test',target]
            command += ['--','--test-threads=1','--nocapture']
            receipt=dict(schema=1,command=command,commandExit=0,acceptedLock=True,
                bootstrapRequested=False,resolveLockRequested=False,lockUnchangedDuringLockedCommand=True,
                toolchain='rustc 1.98.1 (fixture)',androidTarget=None,representativeTargets=targets,
                declaredSourceSpecSha256=identity['meow_source_spec_sha256'],bridgeManifestSha256=manifest,
                buildPolicySha256=policy_hash,lockSha256Before=lock,resolvedLockSha256=lock,lockSha256After=lock,
                source=dict(commit=module.MEOW_SOURCE,tag='v0.26.1',archiveHashBootstrapped=False,
                    archiveSha256='e'*64,fileManifestSha256='d'*64,overlaySha256=overlay,patchSha256=patch),
                featureBoundaryCommand=['cargo','test','--locked','--no-default-features','--lib',
                    'capabilities::tests::','--','--test-threads=1','--nocapture'],featureBoundaryExit=0,coreTests=state)
            self.assertEqual(identity,module.validate_native_receipt(receipt,root=root))
            for changes in [dict(command=command[:3]+command[4:]),dict(coreTests=None),
                    dict(coreTests=dict(state,mode='policy-runtime-v1')),dict(representativeTargets=list(module.HOST_POLICY_RUNTIME_TARGETS))]:
                with self.subTest(fullHost=changes),self.assertRaises(ValueError):
                    module.validate_native_receipt(dict(receipt,**changes),root=root)
            for changes in [dict(targets=list(module.HOST_POLICY_RUNTIME_TARGETS)),dict(targets=targets[:-1]),
                    dict(coreTests='policy-runtime-v1'),dict(resolveLock=True)]:
                write('build-policy.json',dict(policy,**changes))
                with self.subTest(fullPolicy=changes),self.assertRaises(ValueError):
                    module.native_identity(root)
            # Stage1 retains the same Core14/29 proof and requires its new complete Native argv.
            traffic_targets=list(module.HOST_POLICY_RUNTIME_TRAFFIC_TARGETS)
            self.assertEqual(34,len(traffic_targets))
            self.assertEqual(targets,[t for t in traffic_targets if t!='native_telemetry'])
            traffic_policy=dict(policy,targets=traffic_targets,coreTests='policy-runtime-traffic-v1')
            traffic_hash=write('build-policy.json',traffic_policy)
            traffic_command=['cargo','test','--locked','--no-fail-fast']
            for target in traffic_targets:traffic_command += ['--lib'] if target=='lib' else ['--test',target]
            traffic_command += ['--','--test-threads=1','--nocapture']
            traffic_receipt=dict(receipt,command=traffic_command,representativeTargets=traffic_targets,
                buildPolicySha256=traffic_hash,coreTests=dict(state,mode='policy-runtime-traffic-v1'))
            module.validate_native_receipt(traffic_receipt,root=root)
            for changes in [dict(command=command),dict(representativeTargets=targets),
                    dict(command=traffic_command[:3]+traffic_command[4:]),dict(coreTests=None),dict(coreTests=state)]:
                with self.subTest(trafficReceipt=changes),self.assertRaises(ValueError):
                    module.validate_native_receipt(dict(traffic_receipt,**changes),root=root)
            for changes in [dict(targets=targets),dict(targets=traffic_targets+['arbitrary']),dict(targets=traffic_targets[:-1]),
                    dict(targets=list(reversed(traffic_targets))),dict(coreTests='policy-runtime-v1'),dict(coreTests='policy-runtime-v2')]:
                write('build-policy.json',dict(traffic_policy,**changes))
                with self.subTest(trafficPolicy=changes),self.assertRaises(ValueError):module.native_identity(root)
            write('build-policy.json',traffic_policy)
            for key,value in [('target','other'),('filter','arbitrary'),('expectedPass',0),('passed',0),
                    ('command',traffic_receipt['coreTests']['commands'][0]['command'][:-1])]:
                bad=copy.deepcopy(traffic_receipt)
                bad['coreTests']['commands'][0][key]=value
                with self.subTest(trafficCore=key),self.assertRaises(ValueError):module.validate_native_receipt(bad,root=root)
            # An old33 receipt cannot be relabelled as the new34 graph.
            with self.assertRaises(ValueError):module.validate_native_receipt(receipt,root=root)
            missing_gate=dict(traffic_policy);missing_gate.pop('coreTests');missing_gate.pop('coreTestLockSha256')
            write('build-policy.json',missing_gate)
            with self.assertRaises(ValueError):module.native_identity(root)

    def test_observability_requires_all18_exact47_cases_compile_and_separate_identity(self):
        import copy
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'overlay.json').write_text(json.dumps(dict(files={'Cargo.lock':'a'*64})))
            policy=dict(coreTests='policy-runtime-observability-v1',coreTestLockSha256='b'*64,overlay='overlay.json')
            cases=module.CORE_POLICY_TEST_CASES_OBSERVABILITY
            self.assertEqual(module.CORE_POLICY_TEST_CASES_V2,cases[:14])
            self.assertEqual(18,len(cases));self.assertEqual(47,sum(row[2] for row in cases))
            features={p:sorted({f for package,_,_,_,required in cases if package==p for f in required}) for p in module.CORE_OBSERVABILITY_PACKAGES}
            commands=[]
            for package,name,count,target,_ in cases:
                args=['cargo','test','--manifest-path','.upstream/meow-rs/Cargo.toml','--locked','--package',package]
                args += ['--lib'] if target=='lib' else ['--test','transport_scope_compat']
                args += ['--no-default-features']
                if features[package]:args += ['--features',','.join(package+'/'+f for f in features[package])]
                if name is not None:args += [name]
                args += ['--','--test-threads=1','--nocapture']
                commands.append(dict(package=package,filter=name,target=target,command=args,exit=0,expectedPass=count,passed=count,stdoutSha256='c'*64,stderrSha256='d'*64))
            check=dict(command=module.CORE_OBSERVABILITY_COMPILE_COMMAND,exit=0,stdoutSha256='e'*64,stderrSha256='f'*64)
            state=dict(mode=policy['coreTests'],accepted=True,upstreamLockRestored=True,upstreamLockBeforeSha256='a'*64,
                seedSha256='b'*64,resolvedLockSha256='b'*64,bridgeMetadataCommand=['cargo','metadata','--locked','--format-version','1'],bridgeMetadataExit=0,
                metadataCommand=['cargo','metadata','--manifest-path','.upstream/meow-rs/Cargo.toml','--format-version','1','--locked'],metadataExit=0,
                workspaceMembers=list(module.CORE_OBSERVABILITY_PACKAGES),features=features,commands=commands,compileCheck=check)
            module.validate_core_test_receipt(state,policy,root)
            old44=copy.deepcopy(state)
            for index,count in [(14,3),(16,5)]:
                old44['commands'][index].update(expectedPass=count,passed=count)
            with self.assertRaises(ValueError):module.validate_core_test_receipt(old44,policy,root)
            for index in range(14,18):
                for key,value in [('target','other'),('filter','wrong'),('expectedPass',0),('passed',0),('exit',False),('stdoutSha256',None),('command',commands[index]['command'][:-1])]:
                    bad=copy.deepcopy(state);bad['commands'][index][key]=value
                    with self.subTest(index=index,key=key),self.assertRaises(ValueError):module.validate_core_test_receipt(bad,policy,root)
                bad=copy.deepcopy(state);del bad['commands'][index]
                with self.subTest(missing=index),self.assertRaises(ValueError):module.validate_core_test_receipt(bad,policy,root)
            for changes in [dict(mode='policy-runtime-v2'),dict(mode='policy-runtime-traffic-v1'),dict(commands=commands[:14]),
                    dict(upstreamLockRestored=False),dict(seedSha256='0'*64),dict(metadataExit=1),dict(compileCheck=None),dict(features={})]:
                with self.subTest(state=changes),self.assertRaises(ValueError):module.validate_core_test_receipt(dict(state,**changes),policy,root)
            for key,value in [('exit',1),('exit',False),('command',check['command'][:-1]),('stdoutSha256',None),('stderrSha256','bad')]:
                with self.subTest(compile=key,value=value),self.assertRaises(ValueError):module.validate_core_test_receipt(dict(state,compileCheck=dict(check,**{key:value})),policy,root)
            for package in ['meow-api','meow-tunnel','meow-listener']:
                bad=copy.deepcopy(state);del bad['features'][package]
                with self.subTest(package=package),self.assertRaises(ValueError):module.validate_core_test_receipt(bad,policy,root)
            bad=copy.deepcopy(state);bad['features']['meow-common']=[]
            with self.assertRaises(ValueError):module.validate_core_test_receipt(bad,policy,root)
            def write(name,value):
                path=root/name;path.write_text(json.dumps(value) if isinstance(value,dict) else value)
                return module.sha256(path)
            manifest=write('Cargo.toml','manifest');lock=write('Cargo.lock','accepted-bridge')
            core_lock=write('core-tests.lock','accepted-core');patch=write('patch','patch')
            overlay=write('overlay.json',dict(files={'Cargo.lock':'a'*64},patch='patch',patchSha256=patch,adaptedFileManifestSha256='d'*64))
            write('source.json',dict(commit=module.MEOW_SOURCE,tag='v0.26.1',archiveSha256='e'*64))
            targets=list(module.HOST_POLICY_RUNTIME_OBSERVABILITY_TARGETS)
            self.assertEqual(35,len(targets))
            self.assertEqual(list(module.HOST_POLICY_RUNTIME_TRAFFIC_TARGETS),[t for t in targets if t!='native_observability'])
            policy=dict(policy,resolveLock=False,manifestSha256=manifest,overlaySha256=overlay,targets=targets,
                featureBoundary='capabilities-no-default',coreTestLockSha256=core_lock)
            policy_hash=write('build-policy.json',policy);identity=module.native_identity(root)
            state=dict(state,seedSha256=core_lock,resolvedLockSha256=core_lock)
            command=['cargo','test','--locked','--no-fail-fast']
            for target in targets:command += ['--lib'] if target=='lib' else ['--test',target]
            command += ['--','--test-threads=1','--nocapture']
            receipt=dict(schema=1,command=command,commandExit=0,acceptedLock=True,bootstrapRequested=False,
                resolveLockRequested=False,lockUnchangedDuringLockedCommand=True,toolchain='rustc 1.98.1 (fixture)',androidTarget=None,
                representativeTargets=targets,declaredSourceSpecSha256=identity['meow_source_spec_sha256'],bridgeManifestSha256=manifest,
                buildPolicySha256=policy_hash,lockSha256Before=lock,resolvedLockSha256=lock,lockSha256After=lock,
                source=dict(commit=module.MEOW_SOURCE,tag='v0.26.1',archiveHashBootstrapped=False,archiveSha256='e'*64,
                    fileManifestSha256='d'*64,overlaySha256=overlay,patchSha256=patch),coreTests=state,
                featureBoundaryCommand=['cargo','test','--locked','--no-default-features','--lib','capabilities::tests::','--','--test-threads=1','--nocapture'],featureBoundaryExit=0)
            module.validate_native_receipt(receipt,root=root)
            for changes in [dict(coreTests=dict(state,mode='policy-runtime-traffic-v1')),dict(coreTests=None),
                    dict(command=command[:3]+command[4:]),dict(representativeTargets=targets[:-1])]:
                with self.subTest(host=changes),self.assertRaises(ValueError):module.validate_native_receipt(dict(receipt,**changes),root=root)
            for changes in [dict(targets=[t for t in targets if t!='native_observability']),
                    dict(targets=targets+['arbitrary']),dict(coreTests='policy-runtime-traffic-v1'),dict(resolveLock=True)]:
                write('build-policy.json',dict(policy,**changes))
                with self.subTest(policy=changes),self.assertRaises(ValueError):module.native_identity(root)


    def test_provider_general_requires_all26_exact75_cases_compile_and_separate_identity(self):
        self._provider_general_receipt_contract()

    def test_resource_health_requires42_targets_all26_core_commands_and_exact_native_proof(self):
        self._provider_general_receipt_contract(resource_health=True)

    def test_mrs_reader_requires46_source_tests_format_proof_and_retains42_native_targets(self):
        self._provider_general_receipt_contract(resource_health=True,mrs_reader=True)

    def test_independent_core_approved_tables_match_producer_for_all_modes(self):
        producer_spec=importlib.util.spec_from_file_location("producer_core_cases",Path(__file__).resolve().parents[1]/"meow-native/core_tests.py")
        producer=importlib.util.module_from_spec(producer_spec);producer_spec.loader.exec_module(producer)
        tables={producer.MODE:tuple((p,f,n,"lib",(),False) for p,f,n in module.CORE_POLICY_TEST_CASES),
            producer.MODE_V2:module.CORE_POLICY_TEST_CASES_V2,producer.MODE_TRAFFIC:module.CORE_POLICY_TEST_CASES_V2,
            producer.MODE_OBSERVABILITY:module.CORE_POLICY_TEST_CASES_OBSERVABILITY,
            producer.MODE_PROVIDER_GENERAL:module.CORE_POLICY_TEST_CASES_PROVIDER_GENERAL,
            producer.MODE_RESOURCE_HEALTH:module.CORE_POLICY_TEST_CASES_PROVIDER_GENERAL,
            producer.MODE_MRS_READER:module.CORE_POLICY_TEST_CASES_MRS_READER,
            producer.MODE_PROVIDER_READER:module.CORE_POLICY_TEST_CASES_PROVIDER_READER}
        for mode,approved in tables.items():
            normalized=tuple(row if len(row)==6 else row+(False,) for row in approved)
            authored=tuple((row['package'],row['filter'],row['expectedPass'],row['target'],tuple(row['requiredFeatures']),row.get('exact',False)) for row in producer.entries(mode))
            with self.subTest(mode=mode):self.assertEqual(authored,normalized)
        specs=tables[producer.MODE_PROVIDER_READER]
        self.assertEqual((32,179),(len(specs),sum(row[2] for row in specs)))

    def test_provider_reader_requires_all_mrs_commands_51_provider_tests_both_format_proofs_and42_native_targets(self):
        self._provider_general_receipt_contract(resource_health=True,mrs_reader=True,provider_reader=True)

    def _provider_general_receipt_contract(self, resource_health=False, mrs_reader=False, provider_reader=False):
        import copy
        with tempfile.TemporaryDirectory() as directory:
            gated=module.CORE_MRS_READER_FORMAT_COMMAND[4:]+module.CORE_PROVIDER_READER_FORMAT_COMMAND[4:] if provider_reader else []
            format_files={name:f'{index+1:064x}' for index,name in enumerate(gated)}
            adapted=dict({'Cargo.lock':'a'*64},**{name.removeprefix('.upstream/meow-rs/'):value for name,value in format_files.items()})
            root=Path(directory);(root/'overlay.json').write_text(json.dumps(dict(files=adapted)))
            policy=dict(coreTests='policy-runtime-provider-reader-v1' if provider_reader else 'policy-runtime-mrs-reader-v1' if mrs_reader else 'policy-runtime-resource-health-v1' if resource_health else 'policy-runtime-provider-general-v1',coreTestLockSha256='b'*64,overlay='overlay.json')
            cases=module.CORE_POLICY_TEST_CASES_PROVIDER_READER if provider_reader else module.CORE_POLICY_TEST_CASES_MRS_READER if mrs_reader else module.CORE_POLICY_TEST_CASES_PROVIDER_GENERAL
            self.assertEqual(12,cases[0][2])
            self.assertEqual(module.CORE_POLICY_TEST_CASES_OBSERVABILITY[1:],tuple(r[:5] for r in cases[1:18]))
            self.assertEqual(32 if provider_reader else 29 if mrs_reader else 26,len(cases));self.assertEqual(179 if provider_reader else 121 if mrs_reader else 75,sum(row[2] for row in cases))
            if provider_reader:
                self.assertEqual(module.CORE_POLICY_TEST_CASES_MRS_READER,cases[:29])
                self.assertEqual((31,177),(len(cases[:31]),sum(row[2] for row in cases[:31])))
                self.assertEqual(('meow-rules','mrs_parser::host_admission_index_tests::',2,'lib',(),False),cases[31])
                self.assertEqual((('meow-config','rule_provider::reader::tests::reader15_',15,'lib',(),False),('meow-config','rule_provider::tests::',41,'lib',(),False)),cases[29:31])
                self.assertEqual(['rule_provider.rs','rule_pages.rs','rule_provider_reader.rs','rule_provider_reader_tests.rs'],
                    [name.rsplit('/',1)[1] for name in module.CORE_PROVIDER_READER_FORMAT_COMMAND[4:]])
            packages=tuple(sorted(set(module.CORE_PROVIDER_GENERAL_PACKAGES)|({"meow-rules"} if mrs_reader else set())))
            features={p:sorted({f for package,_,_,_,required,_ in cases if package==p for f in required}) for p in packages}
            commands=[]
            for package,name,count,target,_,exact in cases:
                args=['cargo','test','--manifest-path','.upstream/meow-rs/Cargo.toml','--locked','--package',package]
                args += ['--lib'] if target=='lib' else ['--test','transport_scope_compat']
                args += ['--no-default-features']
                if features[package]:args += ['--features',','.join(package+'/'+f for f in features[package])]
                if name is not None:args += [name]
                args += ['--','--test-threads=1','--nocapture']
                if exact:args += ['--exact']
                commands.append(dict(package=package,filter=name,target=target,exact=exact,command=args,exit=0,expectedPass=count,passed=count,stdoutSha256='c'*64,stderrSha256='d'*64))
            check=dict(command=module.CORE_OBSERVABILITY_COMPILE_COMMAND,exit=0,stdoutSha256='e'*64,stderrSha256='f'*64)
            state=dict(mode=policy['coreTests'],accepted=True,upstreamLockRestored=True,upstreamLockBeforeSha256='a'*64,
                seedSha256='b'*64,resolvedLockSha256='b'*64,bridgeMetadataCommand=['cargo','metadata','--locked','--format-version','1'],bridgeMetadataExit=0,
                metadataCommand=['cargo','metadata','--manifest-path','.upstream/meow-rs/Cargo.toml','--format-version','1','--locked'],metadataExit=0,
                workspaceMembers=list(packages),features=features,commands=commands,compileCheck=check)
            if mrs_reader:
                fmt=dict(command=module.CORE_MRS_READER_FORMAT_COMMAND,exit=0,stdoutSha256='e'*64,stderrSha256='f'*64)
                if provider_reader:fmt['files']={name:format_files[name] for name in fmt['command'][4:]}
                state['formatCheck']=fmt
                for key,value in [('exit',1),('exit',False),('command',fmt['command'][:-1]),('stdoutSha256',None)]:
                    with self.subTest(format=key),self.assertRaises(ValueError):
                        module.validate_core_test_receipt(dict(state,formatCheck=dict(fmt,**{key:value})),policy,root)
                with self.assertRaises(ValueError):module.validate_core_test_receipt(dict(state,formatCheck=None),policy,root)
            if provider_reader:
                provider_fmt=dict(command=module.CORE_PROVIDER_READER_FORMAT_COMMAND,exit=0,stdoutSha256='e'*64,stderrSha256='f'*64,
                    files={name:format_files[name] for name in module.CORE_PROVIDER_READER_FORMAT_COMMAND[4:]})
                with self.assertRaises(ValueError):module.validate_core_test_receipt(state,policy,root)
                state['providerFormatCheck']=provider_fmt
                pages=module.CORE_PROVIDER_READER_FORMAT_COMMAND[5]
                self.assertTrue(pages.endswith('/rule_pages.rs'))
                for key,files in [('providerFormatCheck',None),('providerFormatCheck',{n:v for n,v in provider_fmt['files'].items() if n!=pages}),
                        ('providerFormatCheck',dict(provider_fmt['files'],**{pages:'0'*64})),('providerFormatCheck',dict(provider_fmt['files'],extra='1'*64)),
                        ('formatCheck',None),('formatCheck',dict(fmt['files'],**{fmt['command'][4]:'0'*64}))]:
                    gate=dict(state[key],files=files) if files is not None else {k:v for k,v in state[key].items() if k!='files'}
                    with self.subTest(format_files=key,files=None if files is None else sorted(files)),self.assertRaises(ValueError):
                        module.validate_core_test_receipt(dict(state,**{key:gate}),policy,root)
                (root/'overlay.json').write_text(json.dumps(dict(files={n:v for n,v in adapted.items() if not n.endswith('/rule_pages.rs')})))
                with self.assertRaises(ValueError):module.validate_core_test_receipt(state,policy,root)
                (root/'overlay.json').write_text(json.dumps(dict(files=adapted)))
                for key,value in [('exit',1),('exit',False),('command',provider_fmt['command'][:-1]),('command',module.CORE_MRS_READER_FORMAT_COMMAND),('stdoutSha256',None),('stderrSha256','bad')]:
                    with self.subTest(provider_format=key,value=value),self.assertRaises(ValueError):
                        module.validate_core_test_receipt(dict(state,providerFormatCheck=dict(provider_fmt,**{key:value})),policy,root)
                for changes in [dict(providerFormatCheck=None),dict(formatCheck=provider_fmt),dict(commands=commands[:29]),dict(mode='policy-runtime-mrs-reader-v1')]:
                    with self.subTest(provider_state=sorted(changes)),self.assertRaises(ValueError):module.validate_core_test_receipt(dict(state,**changes),policy,root)
                mrs_policy=dict(policy,coreTests='policy-runtime-mrs-reader-v1')
                with self.assertRaises(ValueError):module.validate_core_test_receipt(dict(state,mode='policy-runtime-mrs-reader-v1'),mrs_policy,root)
            module.validate_core_test_receipt(state,policy,root)
            if provider_reader:
                with self.assertRaisesRegex(ValueError,'complete approved Core unit proof'):
                    module.validate_core_test_receipt(dict(state,commands=commands[:31]),policy,root)
                with self.assertRaises(ValueError):
                    module.validate_core_test_receipt(dict(state,commands=commands+[copy.deepcopy(commands[-1])]),policy,root)
                for key,value in [('filter','mrs_parser::tests::'),('expectedPass',1),('expectedPass',3),('exact',True),('package','meow-config'),('command',commands[-1]['command']+['--exact'])]:
                    bad=copy.deepcopy(state);bad['commands'][-1][key]=value
                    with self.subTest(host_admission=key,value=value),self.assertRaises(ValueError):
                        module.validate_core_test_receipt(bad,policy,root)
            old74=copy.deepcopy(state)
            for index,count in [(0,11)]:
                old74['commands'][index].update(expectedPass=count,passed=count)
            with self.assertRaises(ValueError):module.validate_core_test_receipt(old74,policy,root)
            for index in range(18,len(cases)):
                for key,value in [('exact',None),('target','other'),('filter','wrong'),('expectedPass',0),('passed',0),('exit',False),('stdoutSha256',None),('command',commands[index]['command'][:-1])]:
                    bad=copy.deepcopy(state);bad['commands'][index][key]=value
                    with self.subTest(index=index,key=key),self.assertRaises(ValueError):module.validate_core_test_receipt(bad,policy,root)
                bad=copy.deepcopy(state);del bad['commands'][index]
                with self.subTest(missing=index),self.assertRaises(ValueError):module.validate_core_test_receipt(bad,policy,root)
            for changes in [dict(mode='policy-runtime-v2'),dict(mode='policy-runtime-traffic-v1'),dict(mode='policy-runtime-observability-v1'),dict(commands=commands[:18]),
                    dict(upstreamLockRestored=False),dict(seedSha256='0'*64),dict(metadataExit=1),dict(compileCheck=None),dict(features={})]:
                with self.subTest(state=changes),self.assertRaises(ValueError):module.validate_core_test_receipt(dict(state,**changes),policy,root)
            for key,value in [('exit',1),('exit',False),('command',check['command'][:-1]),('stdoutSha256',None),('stderrSha256','bad')]:
                with self.subTest(compile=key,value=value),self.assertRaises(ValueError):module.validate_core_test_receipt(dict(state,compileCheck=dict(check,**{key:value})),policy,root)
            for package in ['meow-api','meow-tunnel','meow-listener','meow-config']:
                bad=copy.deepcopy(state);del bad['features'][package]
                with self.subTest(package=package),self.assertRaises(ValueError):module.validate_core_test_receipt(bad,policy,root)
            bad=copy.deepcopy(state);bad['features']['meow-common']=[]
            with self.assertRaises(ValueError):module.validate_core_test_receipt(bad,policy,root)
            def write(name,value):
                path=root/name;path.write_text(json.dumps(value) if isinstance(value,dict) else value)
                return module.sha256(path)
            manifest=write('Cargo.toml','manifest');lock=write('Cargo.lock','accepted-bridge')
            core_lock=write('core-tests.lock','accepted-core');patch=write('patch','patch')
            overlay=write('overlay.json',dict(files=adapted,patch='patch',patchSha256=patch,adaptedFileManifestSha256='d'*64))
            write('source.json',dict(commit=module.MEOW_SOURCE,tag='v0.26.1',archiveSha256='e'*64))
            targets=list(module.HOST_POLICY_RUNTIME_RESOURCE_HEALTH_TARGETS if resource_health else module.HOST_POLICY_RUNTIME_PROVIDER_GENERAL_TARGETS)
            self.assertEqual(42 if resource_health else 41,len(targets))
            self.assertEqual(list(module.HOST_POLICY_RUNTIME_OBSERVABILITY_TARGETS),[t for t in targets if t not in ['provider_groups','provider_projection_gate','runtime_dialers','runtime_general_groups','runtime_provider_projection','runtime_providers','resource_fetch']])
            policy=dict(policy,resolveLock=False,manifestSha256=manifest,overlaySha256=overlay,targets=targets,
                featureBoundary='capabilities-no-default',coreTestLockSha256=core_lock)
            policy_hash=write('build-policy.json',policy);identity=module.native_identity(root)
            state=dict(state,seedSha256=core_lock,resolvedLockSha256=core_lock)
            command=['cargo','test','--locked','--no-fail-fast']
            for target in targets:command += ['--lib'] if target=='lib' else ['--test',target]
            command += ['--','--test-threads=1','--nocapture']
            receipt=dict(schema=1,command=command,commandExit=0,acceptedLock=True,bootstrapRequested=False,
                resolveLockRequested=False,lockUnchangedDuringLockedCommand=True,toolchain='rustc 1.98.1 (fixture)',androidTarget=None,
                representativeTargets=targets,declaredSourceSpecSha256=identity['meow_source_spec_sha256'],bridgeManifestSha256=manifest,
                buildPolicySha256=policy_hash,lockSha256Before=lock,resolvedLockSha256=lock,lockSha256After=lock,
                source=dict(commit=module.MEOW_SOURCE,tag='v0.26.1',archiveHashBootstrapped=False,archiveSha256='e'*64,
                    fileManifestSha256='d'*64,overlaySha256=overlay,patchSha256=patch),coreTests=state,
                featureBoundaryCommand=['cargo','test','--locked','--no-default-features','--lib','capabilities::tests::','--','--test-threads=1','--nocapture'],featureBoundaryExit=0)
            if resource_health:receipt['addedNativeTests']=module.RESOURCE_HEALTH_CASES
            module.validate_native_receipt(receipt,root=root)
            if resource_health:
                for bad in [None, {}, dict(resource_fetch=module.RESOURCE_HEALTH_CASES['resource_fetch'])]:
                    with self.subTest(proof=bad),self.assertRaisesRegex(ValueError,'Native test proof'):
                        module.validate_native_receipt(dict(receipt,addedNativeTests=bad),root=root)
                for omitted in targets[1:]:
                    write('build-policy.json',dict(policy,targets=[t for t in targets if t!=omitted]))
                    with self.subTest(omitted_target=omitted),self.assertRaises(ValueError):module.native_identity(root)
                write('build-policy.json',policy)

            for changes in [dict(coreTests=dict(state,mode='policy-runtime-traffic-v1')),dict(coreTests=None),
                    dict(command=command[:3]+command[4:]),dict(representativeTargets=targets[:-1])]:
                with self.subTest(host=changes),self.assertRaises(ValueError):module.validate_native_receipt(dict(receipt,**changes),root=root)
            for changes in [dict(targets=[t for t in targets if t!='provider_projection_gate']),
                    dict(targets=targets+['arbitrary']),dict(coreTests='policy-runtime-traffic-v1'),dict(resolveLock=True)]:
                write('build-policy.json',dict(policy,**changes))
                with self.subTest(policy=changes),self.assertRaises(ValueError):module.native_identity(root)


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
            self.assertEqual(result["bundled_meow_script"]["commit"], "c18c314d94206eb6724d235ca0d590c3e14e9121")
            self.assertEqual(len(result["bundled_meow_script"]["assets"]), 3)
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



class BundledScriptApkTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.TemporaryDirectory()
        self.addCleanup(self.root.cleanup)
        self.path = Path(self.root.name) / "app.apk"
        self.entries = {"assets/scripts/" + name:
            (module.BUNDLED_SCRIPT_ROOT / "app/src/main/assets/scripts" / name).read_bytes()
            for name in module.BUNDLED_SCRIPT_FILES}

    def inspect(self):
        with zipfile.ZipFile(self.path, "w") as archive:
            for name, raw in self.entries.items():
                archive.writestr(name, raw)
        with zipfile.ZipFile(self.path) as archive:
            return module.validate_bundled_script(archive)

    def test_actual_three_source_asset_bytes_read_back(self):
        proof = self.inspect()
        self.assertEqual(proof["commit"], "c18c314d94206eb6724d235ca0d590c3e14e9121")
        self.assertEqual(len(proof["assets"]), 3)
        self.assertEqual(proof["assets"]["assets/scripts/smart-config-kit-meow.js"]["bytes"], 116534)

    def test_explicit_frozen_bundle_pins_stay_independent_of_current_table(self):
        proof = self.inspect()
        expected = dict(files=proof["assets"], commit=proof["commit"], update_url=proof["update_url"])
        with mock.patch.object(module, "BUNDLED_SCRIPT_FILES", {}):
            self.assertEqual(proof, module.verify_bundled_script_apk(self.path, expected_bundle=expected))
            with self.assertRaises((ValueError, KeyError)): module.verify_bundled_script_apk(self.path)
        for key in ("commit", "update_url"):
            wrong = dict(expected); wrong[key] = "wrong"
            with self.assertRaisesRegex(ValueError, "metadata"):
                module.verify_bundled_script_apk(self.path, expected_bundle=wrong)

    def test_explicit_frozen_bundle_pins_reject_all_three_changed_assets(self):
        proof = self.inspect()
        expected = dict(files=proof["assets"], commit=proof["commit"], update_url=proof["update_url"])
        original = dict(self.entries)
        for name, raw in original.items():
            with self.subTest(name=name):
                self.entries = dict(original); self.entries[name] = raw[:-1] + bytes([raw[-1] ^ 1])
                with zipfile.ZipFile(self.path, "w") as archive:
                    for entry, data in self.entries.items(): archive.writestr(entry, data)
                with self.assertRaises(ValueError):
                    module.verify_bundled_script_apk(self.path, expected_bundle=expected)

    def test_each_missing_asset_rejected(self):
        original = dict(self.entries)
        for name in original:
            with self.subTest(name=name):
                self.entries = dict(original); del self.entries[name]
                with self.assertRaises(ValueError): self.inspect()

    def test_each_modified_asset_and_crlf_rejected(self):
        original = dict(self.entries)
        for name, raw in original.items():
            for changed in [raw[:-1] + bytes([raw[-1] ^ 1]), raw.replace(b"\n", b"\r\n")]:
                with self.subTest(name=name, crlf=b"\r" in changed):
                    self.entries = dict(original); self.entries[name] = changed
                    with self.assertRaises(ValueError): self.inspect()

    def test_wrong_metadata_source_product_and_update_url_rejected(self):
        name = "assets/scripts/smart-config-kit-meow.source.json"
        raw = self.entries[name]
        for key, value in [("commit", "0"*40), ("path", "Mihomo/Standard.js"),
                           ("updateUrl", "https://example.invalid/script.js"), ("bytes", 1)]:
            with self.subTest(key=key):
                metadata = json.loads(raw); metadata[key] = value
                self.entries[name] = json.dumps(metadata, ensure_ascii=False).encode()
                with self.assertRaises(ValueError): self.inspect()

    def test_duplicate_asset_rejected(self):
        self.inspect()
        name, raw = next(iter(self.entries.items()))
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.path, "a") as archive: archive.writestr(name, raw)
        with zipfile.ZipFile(self.path) as archive:
            with self.assertRaisesRegex(ValueError, "duplicate"): module.validate_bundled_script(archive)

    def test_wrong_kotlin_constant_or_source_file_rejected(self):
        source = module.BUNDLED_SCRIPT_ROOT / "app/src/main"
        fixture = Path(self.root.name) / "source"
        import shutil
        for relative in ["java/dev/aerove/lite/overrides/BundledMeowScript.kt"] + [
                "assets/scripts/" + name for name in module.BUNDLED_SCRIPT_FILES]:
            target = fixture / "app/src/main" / relative
            target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(source / relative, target)
        self.inspect()
        kotlin = fixture / "app/src/main/java/dev/aerove/lite/overrides/BundledMeowScript.kt"
        kotlin.write_text(kotlin.read_text().replace("refs/heads/main", "refs/heads/other"))
        with zipfile.ZipFile(self.path) as archive:
            with self.assertRaisesRegex(ValueError, "constant mismatch"):
                module.validate_bundled_script(archive, fixture)

    def test_normal_verifier_cannot_accept_legacy_or_android_test_without_assets(self):
        self.entries = {"AndroidManifest.xml": b"test package fixture", "classes.dex": b"dex fixture"}
        with self.assertRaises(ValueError): self.inspect()
        with self.assertRaisesRegex(ValueError, "bundled script asset"):
            module.verify(self.path, "x86_64", Path(self.root.name), {}, {})


if __name__ == "__main__":
    unittest.main()
