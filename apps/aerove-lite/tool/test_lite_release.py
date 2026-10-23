import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

tool = Path(__file__).with_name("lite_release.py")
spec = importlib.util.spec_from_file_location("lite_release", tool)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
notes_tool = Path(__file__).with_name("lite_release_notes.py")
notes_spec = importlib.util.spec_from_file_location("lite_release_notes", notes_tool)
notes = importlib.util.module_from_spec(notes_spec)
notes_spec.loader.exec_module(notes)


class LitePromotionGateTests(unittest.TestCase):
    def test_gh_binary_streams_stdout_to_file_without_unsupported_output_flag(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / "asset.bin"
            seen = {}

            def run(command, stdout, check):
                seen["command"] = command
                seen["stdout_is_file"] = stdout.name == str(target)
                stdout.write(b"streamed bytes")
                return mock.Mock(returncode=0)

            with mock.patch.object(release.subprocess, "run", side_effect=run):
                release._gh_binary("repos/example/release/assets/1", target,
                                   ("Accept: application/octet-stream",))
            self.assertTrue(seen["stdout_is_file"])
            self.assertNotIn("--output", seen["command"])
            self.assertIn("-H", seen["command"])
            self.assertEqual(target.read_bytes(), b"streamed bytes")

    def test_release_readback_uses_real_fields_and_never_requires_make_latest(self):
        tag, source, body = "lite-v0.1.0-beta.7+7", "a" * 40, "notes"
        expected = {name: {"size": len(name), "sha256": "b" * 64}
                    for name in ("app.apk", "BUILD_INFO.json", "SHA256SUMS")}
        release_api = {"id": 12, "draft": False, "prerelease": True, "tag_name": tag,
                       "target_commitish": source, "name": f"AEROVE Lite {tag}", "body": body,
                       "assets": [{"name": name, "size": item["size"]} for name, item in expected.items()]}
        self.assertNotIn("make_latest", release_api)
        self.assertTrue(release._published_release_is_exact(release_api, tag, source, body, expected))
        for mutate in (
            lambda value: value.update(target_commitish="c" * 40),
            lambda value: value.update(body="different"),
            lambda value: value["assets"].pop(),
            lambda value: value["assets"][0].update(size=999),
            lambda value: value["assets"].append(dict(value["assets"][0])),
            lambda value: value.update(draft=True),
        ):
            with self.subTest(mutate=mutate):
                bad = json.loads(json.dumps(release_api))
                mutate(bad)
                self.assertFalse(release._published_release_is_exact(bad, tag, source, body, expected))

    def test_latest_readback_compares_release_ids(self):
        with mock.patch.object(release, "_gh_json", return_value={"id": 99}):
            release._assert_release_not_latest(100)
        with mock.patch.object(release, "_gh_json", return_value={"id": 100}):
            with self.assertRaisesRegex(release.PromotionError, "latest release"):
                release._assert_release_not_latest(100)

    def test_published_tag_idempotency_requires_tag_source_and_exact_release_metadata(self):
        tag, source, body = "lite-v0.1.0-beta.7+7", "a" * 40, "notes"
        expected = {name: {"size": len(name), "sha256": "b" * 64}
                    for name in ("app.apk", "BUILD_INFO.json", "SHA256SUMS")}
        value = {"id": 12, "draft": False, "prerelease": True, "tag_name": tag,
                 "target_commitish": source, "name": f"AEROVE Lite {tag}", "body": body,
                 "assets": [{"name": name, "size": item["size"]} for name, item in expected.items()]}
        with mock.patch.object(release, "_tag_commit", return_value=source):
            release._assert_exact_published_release(value, tag, source, body, expected)
            tampered = json.loads(json.dumps(value))
            tampered["assets"][0]["size"] += 1
            with self.assertRaisesRegex(release.PromotionError, "immutable"):
                release._assert_exact_published_release(tampered, tag, source, body, expected)
        with mock.patch.object(release, "_tag_commit", return_value="c" * 40):
            with self.assertRaisesRegex(release.PromotionError, "immutable"):
                release._assert_exact_published_release(value, tag, source, body, expected)

    def test_published_asset_idempotency_reads_back_exact_bytes_and_size(self):
        content = b"published source bytes"
        expected = {"app.apk": {"size": len(content), "sha256": hashlib.sha256(content).hexdigest()}}
        release_value = {"id": 7, "assets": [{"id": 8, "name": "app.apk", "size": len(content)}]}
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "app.apk"
            source.write_bytes(content)

            def write_download(_release_id, _asset, destination):
                Path(destination).write_bytes(content)

            with mock.patch.object(release, "_release_asset_bytes", side_effect=write_download):
                release._verify_release_asset_bytes(release_value, Path(temp), expected, Path(temp) / "ok")
            with mock.patch.object(release, "_release_asset_bytes",
                                   side_effect=lambda _rid, _asset, dest: Path(dest).write_bytes(b"tampered")):
                with self.assertRaisesRegex(release.PromotionError, "readback differs"):
                    release._verify_release_asset_bytes(release_value, Path(temp), expected, Path(temp) / "bad")

    def test_retry_uses_previous_published_changelog_then_reaches_exact_idempotent_publish(self):
        tag, source, tree = "lite-v0.1.0-beta.7+7", "a" * 40, "b" * 40
        previous_tag, previous_source = "lite-v0.1.0-beta.6+6", "c" * 40
        current_changelog = "# AEROVE Lite\n\n## 待发布\n\n- 修复发布重试路径。\n"
        previous_changelog = "# AEROVE Lite\n\n## 待发布\n\n- 上一版本功能。\n"
        previous_info = {"version": "0.1.0-beta.6", "version_code": 6,
                         "source": previous_source}
        info = {"version": "0.1.0-beta.7", "version_code": 7, "source": source, "tree": tree,
                "workflow": "https://github.com/IvanSolis1989/Aerove/actions/runs/123",
                "run_number": 123, "run_attempt": 2}

        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            previous_output = root / "previous.md"
            with mock.patch.object(release, "_gh_json", side_effect=[
                [{"id": 70, "tag_name": tag, "draft": False,
                  "assets": [{"id": 701, "name": "BUILD_INFO.json"}]},
                 {"id": 60, "tag_name": previous_tag, "draft": False,
                  "assets": [{"id": 601, "name": "BUILD_INFO.json"}]}],
                {"encoding": "base64", "content": __import__("base64").b64encode(
                    previous_changelog.encode()).decode()},
            ]), mock.patch.object(release, "_release_asset_bytes",
                    side_effect=lambda _rid, asset, destination: Path(destination).write_text(
                        json.dumps(previous_info), encoding="utf-8")):
                release.previous_changelog(previous_output, tag)
            self.assertEqual(previous_output.read_text(encoding="utf-8"), previous_changelog)

            notes_text = notes.render(current_changelog, tag, info, 123, 2,
                                      previous_output.read_text(encoding="utf-8"))
            notes_path = root / "notes.md"
            notes_path.write_text(notes_text, encoding="utf-8")
            directory = root / "arm"
            directory.mkdir()
            (directory / "AEROVE-Lite-android-arm64.apk").write_bytes(b"same accepted apk")
            (directory / "BUILD_INFO.json").write_text(json.dumps(info), encoding="utf-8")
            sums = "".join(f"{release._sha(directory / name)}  {name}\n"
                           for name in ("AEROVE-Lite-android-arm64.apk", "BUILD_INFO.json"))
            (directory / "SHA256SUMS").write_text(sums, encoding="utf-8")
            validated = {"tag": tag, "apk_sha256": release._sha(directory / "AEROVE-Lite-android-arm64.apk"),
                         "build_info": info, "run_id": 123, "run_number": 123, "run_attempt": 2,
                         "source": source, "tree": tree}
            validated_path = root / "validated.json"
            validated_path.write_text(json.dumps(validated), encoding="utf-8")
            body = notes_text + (f"\n\nPromotion receipt\n\nAcceptance run: `123` (run number `123`, attempt `2`)\n"
                                 f"Source: `{source}`\nAPK SHA-256: `{validated['apk_sha256']}`\n")
            asset_names = ("AEROVE-Lite-android-arm64.apk", "BUILD_INFO.json", "SHA256SUMS")
            assets = [{"id": 800 + index, "name": name,
                       "size": (directory / name).stat().st_size if (directory / name).exists()
                               else len(sums.encode())}
                      for index, name in enumerate(asset_names)]
            release_api = {"id": 700, "draft": False, "prerelease": True, "tag_name": tag,
                           "target_commitish": source, "name": f"AEROVE Lite {tag}",
                           "body": body, "assets": assets}

            def gh_json(route):
                self.assertIn(tag, route)
                return release_api

            def read_asset(_release_id, asset, destination):
                (Path(destination)).write_bytes((directory / asset["name"]).read_bytes())

            with mock.patch.object(release, "_has_release", return_value=True), \
                    mock.patch.object(release, "_gh_json", side_effect=gh_json), \
                    mock.patch.object(release, "_tag_commit", return_value=source), \
                    mock.patch.object(release, "_release_asset_bytes", side_effect=read_asset), \
                    mock.patch.object(release, "_assert_release_not_latest"), \
                    mock.patch.dict(release.os.environ, {"RUNNER_TEMP": temp,
                        "GITHUB_STEP_SUMMARY": str(root / "summary.md")}):
                release.publish(directory, validated_path, notes_path, source, 123, 2, 123)
            self.assertIn("already matches", (root / "summary.md").read_text(encoding="utf-8"))

    def test_parallel_jobs_require_every_producer_and_android_phase_in_same_attempt(self):
        required = {
            "resolve": ["Stable lock and host production regression"],
            "verify": ["Kotlin data, lifecycle and UI compilation checks"],
            "accepted": ["All exact-source acceptance gates passed"],
            "acceptance (matrix)": ["Real API 33 matrix verification"],
            "acceptance (remaining)": ["Real API 33 remaining verification"],
        }
        for abi in ("arm64-v8a", "x86_64"):
            prefix = f"build ({abi}) / "
            required[prefix+"rust"] = ["Build the pinned Rust Android bridge"]
            required[prefix+"helper"] = ["Build the isolated client helper for this ABI"]
            required[prefix+"package"] = ["Prepare the independent Lite release signing identity",
                "Signed preview ARM64 APK" if abi=="arm64-v8a" else "Emulator application and instrumentation APK",
                "Check the signed APK and record its exact provenance"]
        jobs = [dict(name=name,status="completed",conclusion="success",run_id=11,run_attempt=2,
            steps=[dict(name=step,conclusion="success") for step in steps]) for name,steps in required.items()]
        with mock.patch.object(release,"_gh_json",return_value={"jobs":jobs}):
            self.assertEqual(11,len(release._candidate_jobs(11,2)))
            for target in range(len(jobs)):
                jobs[target]["conclusion"]="failure"
                with self.assertRaises(release.PromotionError):release._candidate_jobs(11,2)
                jobs[target]["conclusion"]="success"
            jobs[-1]["run_attempt"]=1
            with self.assertRaises(release.PromotionError):release._candidate_jobs(11,2)
            jobs[-1]["run_attempt"]=2
            jobs.append(dict(jobs[-1]))
            with self.assertRaises(release.PromotionError):release._candidate_jobs(11,2)

    def test_required_build_steps_must_all_pass(self):
        job = {"status": "completed", "conclusion": "success", "steps": [
            {"name": "Kotlin data, lifecycle and UI compilation checks", "conclusion": "success"},
            {"name": "Real API 33 dashboard matrix", "conclusion": "success"},
        ]}
        self.assertTrue(release._successful_steps(job, ("Kotlin data, lifecycle and UI compilation checks",)))
        self.assertFalse(release._successful_steps(job, ("Real API 33 remaining native, TUN and graphics verification",)))
        job["conclusion"] = "failure"
        self.assertFalse(release._successful_steps(job, ()))

    def test_checksum_manifest_requires_exact_names_unique_paths_and_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "app.apk").write_bytes(b"apk")
            digest = release._sha(root / "app.apk")
            (root / "SHA256SUMS").write_text(f"{digest}  app.apk\n", encoding="utf-8")
            release._checksums(root, {"app.apk"})
            for invalid in (
                f"{digest}  app.apk\n{digest}  app.apk\n",
                f"{digest}  ../app.apk\n",
                f"{'0' * 64}  app.apk\n",
                f"{digest}  app.apk\n{digest}  extra\n",
            ):
                with self.subTest(invalid=invalid):
                    (root / "SHA256SUMS").write_text(invalid, encoding="utf-8")
                    with self.assertRaises(release.PromotionError):
                        release._checksums(root, {"app.apk"})

    def test_x86_runtime_inventory_binds_every_file_and_attempt(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            files = {
                "dependencies/releaseRuntimeClasspath.exit.txt": b"0\n",
                "dependencies/releaseAndroidTestRuntimeClasspath.exit.txt": b"0\n",
                "apks/app-release.apk": b"app",
            }
            entries = []
            for name, data in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
                entries.append({"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
            (root / "SHA256SUMS").write_text("".join(f"{entry['sha256']}  {entry['path']}\n" for entry in entries), encoding="utf-8")
            source = "a" * 40
            tree = "b" * 40
            manifest = {"sourceRevision": source, "sourceTree": tree, "workflowRun": 12,
                        "runNumber": 34, "runAttempt": 2, "abi": "x86_64", "prebuiltApks": True,
                        "apkInspectorOutcome": "success", "files": entries}
            (root / "source-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            release._validate_manifest(root, source, tree, 12, 2, 34)
            with self.assertRaisesRegex(release.PromotionError, "does not match"):
                release._validate_manifest(root, source, tree, 12, 1, 34)
            (root / "apks/app-release.apk").write_bytes(b"changed")
            with self.assertRaisesRegex(release.PromotionError, "inventory mismatch"):
                release._validate_manifest(root, source, tree, 12, 2, 34)

    def test_release_notes_bind_preview_tag_source_and_attempt(self):
        info = {"version": "0.1.0-beta.7", "version_code": 7, "source": "a" * 40,
                "tree": "b" * 40, "workflow": "https://github.com/IvanSolis1989/Aerove/actions/runs/123",
                "run_number": 123, "run_attempt": 2}
        markdown = "# AEROVE Lite\n\n## 待发布\n\n- 候选包含独立 clash-rs 核心与客户端 helper。\n"
        rendered = notes.render(markdown, "lite-v0.1.0-beta.7+7", info, 123, 2)
        self.assertIn("attempt `2`", rendered)
        self.assertIn("候选包含独立 clash-rs 核心", rendered)
        with self.assertRaisesRegex(ValueError, "tag"):
            notes.render(markdown, "lite-v0.1.0-beta.6+6", info, 123, 2)
        with self.assertRaisesRegex(ValueError, "attempt"):
            notes.render(markdown, "lite-v0.1.0-beta.7+7", info, 123, 3)
        with self.assertRaisesRegex(ValueError, "real pending"):
            notes.render("## 待发布\n\n无条目", "lite-v0.1.0-beta.7+7", info, 123, 2)
        with self.assertRaisesRegex(ValueError, "unchanged"):
            notes.render(markdown, "lite-v0.1.0-beta.7+7", info, 123, 2, markdown)

    def test_promotion_refuses_noncanonical_source_or_tree_and_duplicate_json(self):
        with self.assertRaisesRegex(release.PromotionError, "Full main source"):
            release.select_acceptance("A" * 40, 5, Path("unused"))
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "duplicate.json"
            path.write_text('{"source":"a","source":"b"}', encoding="utf-8")
            with self.assertRaisesRegex(release.PromotionError, "Duplicate JSON"):
                release._json(path)

    def test_selection_binds_unique_attempt_specific_artifacts_to_successful_source(self):
        source = "a" * 40
        run_id, attempt = 321, 2
        run = {"id": run_id, "run_number": 44, "run_attempt": attempt, "head_sha": source,
               "head_branch": "codex/aerove-independent-architecture", "path": ".github/workflows/aerove-lite.yaml",
               "status": "completed", "conclusion": "success", "event": "push"}
        artifact_rows = []
        for key, prefix in release.ARTIFACT_PREFIXES.items():
            artifact_rows.append({"id": 100 + len(artifact_rows), "name": prefix + str(attempt), "expired": False,
                "workflow_run": {"id": run_id, "head_sha": source}})
        def api(route):
            if route.endswith(f"/actions/workflows/{release.WORKFLOW}/runs?head_sha={source}&per_page=100"):
                return {"workflow_runs": [run]}
            if route.endswith(f"/actions/runs/{run_id}/artifacts?per_page=100"):
                return {"artifacts": artifact_rows}
            raise AssertionError(route)
        with tempfile.TemporaryDirectory() as temp, mock.patch.object(release, "_gh_json", side_effect=api), \
                mock.patch.object(release, "_candidate_jobs", return_value={"complete": True}):
            outputs = Path(temp) / "outputs"
            result = release.select_acceptance(source, 999, outputs)
            self.assertEqual(result["run_id"], run_id)
            self.assertEqual(result["run_attempt"], attempt)
            self.assertEqual(set(result["artifact_ids"]), {"arm", "x86", "matrix", "helper"})
            artifact_rows.pop()
            with self.assertRaisesRegex(release.PromotionError, "No exact-source"):
                release.select_acceptance(source, 999, outputs)


if __name__ == "__main__":
    unittest.main()
