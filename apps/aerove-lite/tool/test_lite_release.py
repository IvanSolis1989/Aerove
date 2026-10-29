import importlib.util
import hashlib
import json
import re
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


def workflow_aggregate_step():
    workflow = Path(__file__).resolve().parents[3] / ".github/workflows/aerove-lite.yaml"
    text = workflow.read_text(encoding="utf-8")
    section_match = re.search(r"(?ms)^  accepted:\s*\n(?P<section>.*?)(?=^  [A-Za-z0-9_-]+:\s*$|\Z)", text)
    if not section_match:
        raise AssertionError("accepted job is absent from the actual Lite workflow")
    steps = re.findall(r"(?m)^    - name: (.+)$", section_match.group("section"))
    matches = [name for name in steps if "runtime-discovered shard sets form one complete APK test selection" in name]
    if len(matches) != 1:
        raise AssertionError(f"expected one aggregate acceptance step in the actual Lite workflow, got {matches!r}")
    return matches[0]


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
                                 f"Build source: `{source}`\nRelease source: `{source}`\nAPK SHA-256: `{validated['apk_sha256']}`\n")
            asset_names = ("AEROVE-Lite-android-arm64.apk", "BUILD_INFO.json", "SHA256SUMS")
            assets = [{"id": 800 + index, "name": name,
                       "size": (directory / name).stat().st_size if (directory / name).exists()
                               else len(sums.encode())}
                      for index, name in enumerate(asset_names)]
            release_api = {"id": 700, "draft": False, "prerelease": True, "tag_name": tag,
                           "target_commitish": source, "name": f"AEROVE Lite {tag}",
                           "body": body, "assets": assets}
            projection = dict(release_tree=tree)
            selected = dict(projection=projection, artifacts={}, build_tree=tree, build_source=source)
            promotion = dict(schema=1, role="originalProductArtifactPromotion", product="aerove-lite",
                repository=release.REPO, compiled=False, projection=projection, artifacts={},
                build_run=123, build_attempt=2, build_number=123, build_tree=tree, build_source=source,
                release_source=source, release_tree=tree, release_run=999, release_attempt=1,
                files=dict(arm={name: dict(bytes=(directory / name).stat().st_size,
                    sha256=release._sha(directory / name)) for name in asset_names}))
            receipt = root / "promotion.json"
            receipt.write_text(json.dumps(promotion), encoding="utf-8")

            def gh_json(route):
                if route.endswith("/actions/runs/999"):
                    return dict(id=999, run_attempt=1, run_number=200, head_sha=source,
                        path=".github/workflows/aerove-lite.yaml", event="workflow_dispatch", head_branch="main",
                        repository=dict(full_name=release.REPO), head_repository=dict(full_name=release.REPO))
                self.assertIn(tag, route)
                return release_api

            def read_asset(_release_id, asset, destination):
                (Path(destination)).write_bytes((directory / asset["name"]).read_bytes())

            with mock.patch.object(release, "_has_release", return_value=True), \
                    mock.patch.object(release, "accepted_build", return_value=selected), \
                    mock.patch.object(release, "_current_main_inputs"), \
                    mock.patch.object(release, "_gh_json", side_effect=gh_json), \
                    mock.patch.object(release, "_tag_commit", return_value=source), \
                    mock.patch.object(release, "_release_asset_bytes", side_effect=read_asset), \
                    mock.patch.object(release, "_assert_release_not_latest"), \
                    mock.patch.dict(release.os.environ, {"RUNNER_TEMP": temp,
                        "GITHUB_STEP_SUMMARY": str(root / "summary.md")}):
                release.publish(directory, validated_path, notes_path, source, 123, 2, 123,
                    receipt=receipt, root=root, release_run=999, release_attempt=1)
            self.assertIn("already matches", (root / "summary.md").read_text(encoding="utf-8"))

    def test_parallel_jobs_require_every_producer_and_android_phase_in_same_attempt(self):
        for count in (8, 16):
            with self.subTest(count=count), mock.patch.object(release, "_source_shard_count", return_value=count):
                self.check_parallel_jobs(count)

    def check_parallel_jobs(self, count):
        actual_aggregate_step = f"Verify the {count} runtime-discovered shard sets form one complete APK test selection"
        self.assertEqual(
            "Verify the 16 runtime-discovered shard sets form one complete APK test selection",
            workflow_aggregate_step(),
        )
        required = {
            "resolve": ["Stable lock and host production regression"],
            "verify": ["Kotlin data, lifecycle and UI compilation checks"],
            "accepted": ["All exact-source acceptance gates passed", actual_aggregate_step],
            "acceptance (matrix)": ["Real API 33 matrix verification"],
        }
        for shard_index in range(count):
            required[f"acceptance (remaining shard {shard_index}/{count})"] = ["Real API 33 remaining verification"]
        for abi in ("arm64-v8a", "x86_64"):
            prefix = f"build ({abi}) / "
            required[prefix+"rust"] = ["Build the pinned Rust Android bridge"]
            required[prefix+"helper"] = ["Build the isolated client helper for this ABI"]
            required[prefix+"package"] = ["Prepare the independent Lite release signing identity",
                "Signed preview ARM64 APK" if abi=="arm64-v8a" else "Emulator application and instrumentation APK",
                "Check the signed APK and record its exact provenance"]
        jobs = [dict(name=name,status="completed",conclusion="success",run_id=11,run_attempt=2,
            steps=[dict(name=step,conclusion="success") for step in steps]) for name,steps in required.items()]
        for index, job in enumerate(jobs):
            job.update(id=index + 1, head_sha="a" * 40)
        with mock.patch.object(release, "_gh_json", return_value=dict(total_count=len(jobs), jobs=jobs)):
            release._candidate_jobs(11, 2, "a" * 40)
            for job in jobs:
                job["head_sha"] = "b" * 40
                with self.subTest(sourceMismatch=job["name"]), self.assertRaises(release.PromotionError):
                    release._candidate_jobs(11, 2, "a" * 40)
                job["head_sha"] = "a" * 40
        with mock.patch.object(release,"_gh_items",return_value=jobs):
            self.assertEqual(len(required),len(release._candidate_jobs(11,2,"a" * 40)))
            for target in range(len(jobs)):
                removed = jobs.pop(target)
                with self.subTest(missing=removed["name"]), self.assertRaises(release.PromotionError):
                    release._candidate_jobs(11,2,"a" * 40)
                jobs.insert(target, removed)
            for job in list(jobs):
                jobs.append(dict(job, conclusion="failure"))
                with self.subTest(duplicateEvenFailed=job["name"]), self.assertRaises(release.PromotionError):
                    release._candidate_jobs(11,2,"a" * 40)
                jobs.pop()
            for job in list(jobs):
                if not job["name"].startswith("acceptance ("):
                    continue
                jobs.append(dict(job, conclusion="failure"))
                with self.subTest(duplicate=job["name"]), self.assertRaises(release.PromotionError):
                    release._candidate_jobs(11,2,"a" * 40)
                jobs.pop()
            for unexpected in ("acceptance (remaining)", "acceptance (remaining shard 8/8)",
                               "acceptance (remaining shard 0/7)", "acceptance (remaining shard 0/8) extra",
                               "acceptance (remaining shard 0/4)"):
                jobs.append(dict(jobs[0], name=unexpected))
                with self.subTest(mixed=unexpected), self.assertRaises(release.PromotionError):
                    release._candidate_jobs(11,2,"a" * 40)
                jobs.pop()
            for target, job in enumerate(jobs):
                if not job["name"].startswith("acceptance ("):
                    continue
                original_steps = jobs[target]["steps"]
                jobs[target]["steps"] = []
                with self.subTest(missingVerification=job["name"]), self.assertRaises(release.PromotionError):
                    release._candidate_jobs(11,2,"a" * 40)
                jobs[target]["steps"] = original_steps
                for field, invalid in (("run_attempt", 1), ("run_id", 12)):
                    original = jobs[target][field]
                    jobs[target][field] = invalid
                    with self.subTest(shard=job["name"], field=field), self.assertRaises(release.PromotionError):
                        release._candidate_jobs(11,2,"a" * 40)
                    jobs[target][field] = original
            for target in range(len(jobs)):
                jobs[target]["conclusion"]="failure"
                with self.assertRaises(release.PromotionError):release._candidate_jobs(11,2,"a" * 40)
                jobs[target]["conclusion"]="success"
            jobs[-1]["run_attempt"]=1
            with self.assertRaises(release.PromotionError):release._candidate_jobs(11,2,"a" * 40)
            jobs[-1]["run_attempt"]=2
            jobs.append(dict(jobs[-1]))
            with self.assertRaises(release.PromotionError):release._candidate_jobs(11,2,"a" * 40)


    def test_original_source_plan_is_read_from_git_and_not_artifact_claims(self):
        root = Path(__file__).resolve().parents[3]
        workflow = (root / ".github/workflows/aerove-lite.yaml").read_text()
        for count in (8, 16):
            candidate = workflow
            if count == 8:
                for index in range(8, 16):
                    candidate = candidate.replace(
                        f"        - phase: remaining\n          label: remaining shard {index}/16\n"
                        f"          artifact_suffix: remaining-shard-{index}-of-16\n"
                        f"          shard_count: 16\n          shard_index: {index}\n", "")
                candidate = candidate.replace("/16", "/8").replace("-of-16", "-of-8").replace("shard_count: 16", "shard_count: 8")
                candidate = candidate.replace("Verify the 16 runtime-discovered", "Verify the 8 runtime-discovered")
                candidate = candidate.replace("--shard-count 16 ", "")
            with self.subTest(count=count), mock.patch.object(release.subprocess, "check_output", return_value=candidate.encode()) as git:
                self.assertEqual(count, release._source_shard_count(root, "a" * 40))
                git.assert_called_once_with(["git", "show", "a" * 40 + ":.github/workflows/aerove-lite.yaml"], cwd=root)
        invalid = [workflow.replace("shard_count: 16", "shard_count: 8", 1),
                   workflow.replace("shard_index: 15", "shard_index: 14", 1),
                   workflow.replace("Verify the 16 runtime-discovered", "Verify the 8 runtime-discovered"),
                   workflow.replace("--shard-count 16 ", ""),
                   workflow.replace("-of-16-attempt-${{ github.run_attempt }}", "-of-8-attempt-${{ github.run_attempt }}")]
        for text in invalid:
            with self.subTest(invalid=text[-100:]), mock.patch.object(release.subprocess, "check_output", return_value=text.encode()):
                with self.assertRaises(release.PromotionError):
                    release._source_shard_count(root, "a" * 40)
        with self.assertRaises(release.PromotionError):
            release._source_shard_count(root, "main")

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
            for branch in ("codex/aerove-independent-architecture", "codex/aerove-lite-meow-app"):
                for event in ("push", "workflow_dispatch"):
                    run.update(head_branch=branch,event=event)
                    with self.subTest(branch=branch,event=event):
                        result = release.select_acceptance(source, 999, outputs)
                        self.assertEqual(result["run_id"], run_id)
                        self.assertEqual(result["run_attempt"], attempt)
                        self.assertEqual(set(result["artifact_ids"]), {"arm", "x86", "matrix", "helper"})
            for branch in ("codex/aerove-meow-preflight", "codex/unrelated", "main",
                    "codex/aerove-lite-meow-app-extra", "codex/aerove-lite-meow-app/child", "", None):
                run["head_branch"] = branch
                with self.subTest(rejectedBranch=branch), self.assertRaisesRegex(release.PromotionError,"No exact-source"):
                    release.select_acceptance(source,999,outputs)
            run["head_branch"] = "codex/aerove-lite-meow-app"
            for key,value in (("head_sha","b"*40),("path",".github/workflows/aerove-meow-preflight.yaml"),
                    ("event","pull_request"),("conclusion","failure"),("run_attempt",attempt+1)):
                original=run[key];run[key]=value
                with self.subTest(newBranchGate=key), self.assertRaisesRegex(release.PromotionError,"No exact-source"):
                    release.select_acceptance(source,999,outputs)
                run[key]=original
            with self.assertRaisesRegex(release.PromotionError,"No exact-source"):
                release.select_acceptance(source,run_id,outputs)
            artifact_rows.pop()
            with self.assertRaisesRegex(release.PromotionError, "No exact-source"):
                release.select_acceptance(source, 999, outputs)




class CurrentRendererPromotionTests(unittest.TestCase):
    """Small fixture matrix; mocked gallery does not prove rendered pixels."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.matrix = self.root / "matrix"
        self.runtime = self.root / "runtime"
        self.matrix.mkdir()
        (self.runtime / "apks").mkdir(parents=True)
        self.expected = dict(sourceRevision="a" * 40, sourceTree="b" * 40,
                             workflowRun="123", runAttempt=2, runNumber=90)
        apks = {}
        entries = []
        for name in ("app-release.apk", "app-release-androidTest.apk"):
            path = self.runtime / "apks" / name
            path.write_bytes(("fixture-" + name).encode())
            apks[name] = release._sha(path)
            entries.append(dict(path="apks/" + name, bytes=path.stat().st_size, sha256=apks[name]))
        self.write(self.runtime / "source-manifest.json", dict(self.expected, files=entries))
        self.source = dict(self.expected, harnessRevision=self.expected["sourceRevision"],
                           harnessRun="123", appBuildRun="123", phase="matrix",
                           deviceKind="CI-emulator", deviceSerial="emulator-5554",
                           rendererBackend="SwANGLE (ANGLE + SwiftShader)",
                           prebuiltApks=True, performanceEvidence=False)
        self.write(self.matrix / "SOURCE.json", self.source)
        (self.matrix / "APK_SHA256SUMS").write_text("".join(d + "  path/" + n + "\n" for n, d in apks.items()))
        evidence = ["Graphics Adapter ANGLE (Google SwiftShader)"]
        self.actual = dict(identity=self.expected, apks=apks,
                           runtimeManifestSha256=release._sha(self.runtime / "source-manifest.json"),
                           apkInspectorOutcome="success")
        self.proof = dict(self.actual, schema=1, owner="emulator_current.py",
                          deviceSerial="emulator-5554", gpuBackend="swangle",
                          rendererBackend=self.source["rendererBackend"], rendererEvidence=evidence)
        self.owner = dict(app=self.expected, runtime=self.actual,
                          harnessRevision=self.expected["sourceRevision"], harnessRun="123",
                          command=["/sdk/emulator/emulator", "-port", "5554", "-avd", "test", "-no-window",
                                   "-gpu", "swangle", "-no-snapshot", "-noaudio", "-no-boot-anim"],
                          ownerPid=99, emulatorPid=100, rendererEvidence=evidence,
                          result=0, acceptanceReturnCode=0, unexpectedEmulatorExit=False,
                          cleanupRequested=True, emulatorReturnCode=-15, emulatorSignal=15,
                          cleanup=dict(terminationInitiated=True, exitObservedBeforeTermination=False,
                                       initialReturnCode=None, returnCode=-15))
        self.write_proofs()
        (self.matrix / "emulator.stdout.log").write_text(evidence[0] + "\n")
        (self.matrix / "instrumentation.log").write_text("dev.aerove.lite.ui.LiteDashboardMatrixTest\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")
        capture = self.matrix / "captures/dashboard-matrix"
        capture.mkdir(parents=True)
        cases = []
        for index in range(12):
            name = str(index) + ".json"
            self.write(capture / name, {})
            cases.append(dict(metadata=name, windowImage=str(index) + ".png", displayImage=str(index) + "-display.png"))
        self.write(capture / "manifest.json", dict(cases=cases))

    def write(self, path, value):
        path.write_text(json.dumps(value))

    def write_proofs(self):
        self.write(self.matrix / "CURRENT-RENDERER-PROOF.json", self.proof)
        self.write(self.matrix / "EMULATOR-OWNER.json", self.owner)

    def verify(self):
        gallery = mock.Mock()
        gallery.gallery.return_value = 12
        spec = mock.Mock()
        with mock.patch.object(release.importlib.util, "spec_from_file_location", return_value=spec), \
                mock.patch.object(release.importlib.util, "module_from_spec", return_value=gallery):
            release._verify_matrix(self.matrix, self.runtime, self.expected["sourceRevision"],
                                   self.expected["sourceTree"], 123, 2, 90)
        gallery.gallery.assert_called_once()

    def test_current_swangle_complete_proof_passes_same_matrix_contract(self):
        self.verify()

    def test_legacy_renderer_keeps_old_contract(self):
        self.source["rendererBackend"] = "SwiftShader-indirect"
        self.write(self.matrix / "SOURCE.json", self.source)
        (self.matrix / "CURRENT-RENDERER-PROOF.json").unlink()
        (self.matrix / "EMULATOR-OWNER.json").unlink()
        self.verify()

    def test_each_required_proof_field_missing_fails(self):
        for target in (self.proof, self.owner):
            for key in list(target):
                if key == "initialReturnCode":
                    continue
                value = target.pop(key)
                self.write_proofs()
                with self.subTest(key=key), self.assertRaises((release.PromotionError, FileNotFoundError)):
                    self.verify()
                target[key] = value
        self.write_proofs()

        for name in ("CURRENT-RENDERER-PROOF.json", "EMULATOR-OWNER.json", "emulator.stdout.log"):
            path = self.matrix / name
            content = path.read_bytes()
            path.unlink()
            with self.subTest(missingFile=name), self.assertRaises((release.PromotionError, FileNotFoundError)):
                self.verify()
            path.write_bytes(content)

    def test_cleanup_and_unexpected_exit_cannot_pass(self):
        for key, value in (("result", 1), ("acceptanceReturnCode", 1),
                           ("unexpectedEmulatorExit", True), ("cleanupRequested", False)):
            old = self.owner[key]
            self.owner[key] = value
            self.write_proofs()
            with self.subTest(key=key), self.assertRaises(release.PromotionError):
                self.verify()
            self.owner[key] = old
        for key in list(self.owner["cleanup"]):
            old = self.owner["cleanup"].pop(key)
            self.write_proofs()
            with self.subTest(cleanupField=key), self.assertRaises(release.PromotionError):
                self.verify()
            self.owner["cleanup"][key] = old

    def test_wrong_app_source_run_attempt_and_artifact_are_rejected(self):
        for key in ("sourceRevision", "sourceTree", "workflowRun", "runAttempt"):
            original = self.proof["identity"]
            self.proof["identity"] = dict(original, **{key: "wrong"})
            self.write_proofs()
            with self.subTest(key=key), self.assertRaises(release.PromotionError):
                self.verify()
            self.proof["identity"] = original
        self.proof["apks"] = dict(self.proof["apks"], **{"app-release.apk": "0" * 64})
        self.write_proofs()
        with self.assertRaises(release.PromotionError):
            self.verify()

    def test_qa79_owner_and_unconfirmed_renderer_are_rejected(self):
        self.proof["owner"] = "emulator_qa79.py"
        self.write_proofs()
        with self.assertRaises(release.PromotionError):
            self.verify()
        self.proof["owner"] = "emulator_current.py"
        self.write_proofs()
        (self.matrix / "emulator.stdout.log").write_text("Graphics Adapter Google SwiftShader\n")
        with self.assertRaises(release.PromotionError):
            self.verify()

    def test_twelve_capture_and_instrumentation_gates_are_still_required(self):
        (self.matrix / "instrumentation.log").write_text("FAILURES!!!")
        with self.assertRaises(release.PromotionError):
            self.verify()
        (self.matrix / "instrumentation.log").write_text("dev.aerove.lite.ui.LiteDashboardMatrixTest\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")
        path = self.matrix / "captures/dashboard-matrix/manifest.json"
        value = json.loads(path.read_text())
        value["cases"].pop()
        self.write(path, value)
        with self.assertRaises(release.PromotionError):
            self.verify()

class ExplicitBuildPromotionTest(unittest.TestCase):
    def setUp(self):
        self.source = "a" * 40
        self.target = "b" * 40
        self.run = dict(id=123, run_number=44, run_attempt=2, head_sha=self.source,
            head_branch="codex/aerove-lite-meow-app", path=".github/workflows/aerove-lite.yaml",
            event="workflow_dispatch", status="completed", conclusion="success",
            repository=dict(full_name=release.REPO), head_repository=dict(full_name=release.REPO))
        self.artifacts = [dict(id=100 + index, name=prefix + "2", expired=False,
            workflow_run=dict(id=123, head_sha=self.source), digest="sha256:" + "c" * 64,
            size_in_bytes=1000) for index, prefix in enumerate(release.ARTIFACT_PREFIXES.values())]
        self.projection = dict(build_tree="d" * 40, release_tree="e" * 40)

    def select(self):
        with mock.patch.object(release, "_gh_json", return_value=self.run), \
                mock.patch.object(release, "_gh_items", return_value=self.artifacts), \
                mock.patch.object(release, "_candidate_jobs") as jobs, \
                mock.patch.object(release.product_source, "equivalent", return_value=self.projection):
            result = release.accepted_build(Path("unused"), self.target, 123, 456)
            jobs.assert_called_once_with(123, 2, self.source, Path("unused"))
            return result

    def test_explicit_original_run_keeps_build_identity_separate_from_release(self):
        result = self.select()
        self.assertEqual(result["build_source"], self.source)
        self.assertEqual(result["run_id"], 123)
        self.assertEqual(result["run_attempt"], 2)
        self.assertEqual(set(result["artifact_ids"]), {"arm", "x86", "helper", "matrix"})

    def test_forged_failed_wrong_attempt_or_expired_original_is_rejected(self):
        for key, value in (("id", 999), ("conclusion", "failure"), ("status", "in_progress"),
                           ("repository", dict(full_name="fork/repository")), ("run_attempt", 3)):
            original = self.run[key]
            self.run[key] = value
            with self.subTest(field=key), self.assertRaises(release.PromotionError):
                self.select()
            self.run[key] = original
        for key, value in (("expired", True), ("digest", None),
                           ("workflow_run", dict(id=999, head_sha=self.source))):
            original = self.artifacts[0][key]
            self.artifacts[0][key] = value
            with self.subTest(field=key), self.assertRaises(release.PromotionError):
                self.select()
            self.artifacts[0][key] = original

    def test_missing_current_source_or_changed_product_inputs_fail_before_publication(self):
        with mock.patch.object(release, "_gh_json", return_value=self.run), \
                mock.patch.object(release.product_source, "equivalent", side_effect=release.product_source.ProjectionError("changed product input")):
            with self.assertRaises(release.product_source.ProjectionError):
                release.accepted_build(Path("unused"), self.target, 123, 456)
        with self.assertRaises(release.PromotionError):
            release.accepted_build(Path("unused"), self.target, 123, 123)

    def test_api_pagination_rejects_duplicate_or_missing_rows(self):
        for payload in (dict(total_count=2, artifacts=[]), dict(total_count=2, artifacts=[dict(id=1), dict(id=1)])):
            with mock.patch.object(release, "_gh_json", return_value=payload), self.assertRaises(release.PromotionError):
                release._gh_items("endpoint?per_page=100", "artifacts")


TAG = "lite-v0.1.0-beta.24+24"
INFO = {"version": "0.1.0-beta.24", "version_code": 24,
        "source": "a" * 40, "tree": "b" * 40,
        "workflow": "https://github.com/IvanSolis1989/Aerove/actions/runs/123",
        "run_number": 50, "run_attempt": 2}
STATUS = ("下一应用候选为 **0.1.0-beta.24（安装编号 24）**；包名 `dev.aerove.lite`、独立签名与 preview 通道沿用。"
          "新源码 APK 尚未构建，本候选未接受、未发行。真实光学、帧表现和网络取消/恢复等完整验收仍未闭合；满足自身完整契约后独立发行。")
ENTRIES = ("- **主题名称：** 主题色选项统一更名为“经典单色”，同步七种语言。\n"
           "- **来源：** [核心记录](core/meow-migration.md#rule-preview-budget)，版本 `v6.0.21-meow.7`。\n"
           "  保留缩进的功能说明。")


class LiteReleaseNotesTests(unittest.TestCase):
    def test_exact_current_candidate_status_is_removed_and_entries_are_preserved(self):
        rendered = notes.render("## 待发布\n\n" + STATUS + "\n\n" + ENTRIES, TAG, INFO, 123, 2)
        self.assertIn(ENTRIES, rendered)
        self.assertNotIn(STATUS, rendered)
        for value in (TAG, INFO["source"], INFO["tree"], INFO["workflow"],
                      "run `123`，attempt `2`", notes.BEGIN, notes.END):
            self.assertIn(value, rendered)

    def test_current_changelog_renders_only_implemented_entries(self):
        path = Path(__file__).resolve().parents[3] / notes.CHANGELOG
        rendered = notes.render(path.read_text(encoding="utf-8"), TAG, INFO, 123, 2)
        for pending in ("未接受", "未发行", "尚未构建", "待验收", "仍待", "待新候选", "尚待"):
            self.assertNotIn(pending, rendered)
        for entry in ("融合编辑", "主题名称", "局域网地址", "统计刷新", "曲线延续", "国旗定心",
                      "更新备用源", "专版脚本", "验证接线", "c18c314d94206eb6724d235ca0d590c3e14e9121",
                      "v6.0.21-meow.7"):
            self.assertIn(entry, rendered)
        self.assertNotIn("历史未发行候选记录", rendered)

    def test_unknown_duplicate_and_malformed_status_or_html_fail_closed(self):
        for body in ("尚待构建。\n\n" + ENTRIES,
                     STATUS + "\n第二行状态\n\n" + ENTRIES,
                     STATUS + "\n\n" + STATUS + "\n\n" + ENTRIES,
                     STATUS + STATUS + "\n\n" + ENTRIES,
                     STATUS + "\n" + ENTRIES,
                     "> " + STATUS + "\n\n" + ENTRIES,
                     "<b>" + STATUS + "</b>\n\n" + ENTRIES,
                     STATUS + "\n\n- <script>alert(1)</script>",
                     "<!-- control -->\n" + ENTRIES,
                     ENTRIES + "\n  <!-- control -->",
                     ENTRIES + "\n\n" + STATUS,
                     ENTRIES + "\n  " + STATUS,
                     ENTRIES + "\n无缩进的额外状态",
                     "- 更新\x00伪造"):
            with self.subTest(body=body), self.assertRaises(ValueError):
                notes.render("## 待发布\n\n" + body, TAG, INFO, 123, 2)
        with self.assertRaises(ValueError):
            notes.render("## 待发布\n- 一\n## 待发布\n- 二", TAG, INFO, 123, 2)

    def test_status_only_change_cannot_republish_previous_entries(self):
        previous = "## 待发布\n\n" + STATUS + "\n\n" + ENTRIES
        for markdown in (previous, "## 待发布\n\n" + ENTRIES,
                         previous.replace("尚未构建", "尚未重新构建")):
            with self.subTest(markdown=markdown), self.assertRaisesRegex(ValueError, "unchanged"):
                notes.render(markdown, TAG, INFO, 123, 2, previous)

    def test_first_release_and_previous_without_pending_remain_supported(self):
        for previous in ("", "# AEROVE Lite\n\n历史发行模板。"):
            rendered = notes.render("## 待发布\n\n" + ENTRIES, TAG, INFO, 123, 2, previous)
            self.assertIn(ENTRIES, rendered)

    def test_tag_source_tree_and_acceptance_binding_remain_required(self):
        for tag, info, run, attempt in (
                (TAG + "extra", INFO, 123, 2),
                (TAG, {**INFO, "source": "main"}, 123, 2),
                (TAG, {**INFO, "tree": "b" * 39}, 123, 2),
                (TAG, {**INFO, "workflow": INFO["workflow"] + "4"}, 123, 2),
                (TAG, {**INFO, "run_number": 0}, 123, 2),
                (TAG, {**INFO, "run_number": True}, 123, 2),
                (TAG, INFO, 124, 2), (TAG, INFO, 123, 3)):
            with self.subTest(tag=tag, info=info, run=run, attempt=attempt), self.assertRaises(ValueError):
                notes.render("## 待发布\n\n" + STATUS + "\n\n" + ENTRIES, tag, info, run, attempt)


if __name__ == "__main__":
    unittest.main()
