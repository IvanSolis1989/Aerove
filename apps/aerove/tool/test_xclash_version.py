import subprocess
import tempfile
import unittest
from pathlib import Path

from tool import xclash_version as version


class XClashVersionTest(unittest.TestCase):
    def test_prerelease_tags_sort_numerically_and_stable_wins(self):
        refs = [
            "refs/upstream-tags/v0.9.1-pre.4",
            "refs/upstream-tags/v0.9.1-pre.10",
            "refs/upstream-tags/v0.9.1",
        ]

        self.assertEqual(
            version.select_upstream_tag(refs), "refs/upstream-tags/v0.9.1"
        )
        self.assertEqual(
            version.select_upstream_tag(refs[:-1]),
            "refs/upstream-tags/v0.9.1-pre.10",
        )
        self.assertEqual(
            version.select_upstream_tag(
                refs + ["refs/upstream-tags/v0.9.2-pre.1"]
            ),
            "refs/upstream-tags/v0.9.2-pre.1",
        )

    def test_semver_prerelease_identifiers_follow_semver_precedence(self):
        refs = [
            "refs/upstream-tags/v1.0.0-alpha.2",
            "refs/upstream-tags/v1.0.0-alpha.10",
            "refs/upstream-tags/v1.0.0-beta.1",
            "refs/upstream-tags/v1.0.0-rc.1",
            "refs/upstream-tags/v1.0.0",
            "refs/upstream-tags/v1.0.0-beta.01",
        ]

        self.assertEqual(
            version.select_upstream_tag(refs), "refs/upstream-tags/v1.0.0"
        )
        self.assertEqual(
            version.select_upstream_tag(refs[:-3]),
            "refs/upstream-tags/v1.0.0-beta.1",
        )
        self.assertIsNone(version.parse_release_tag("v1.0.0-beta.01"))

    def test_tag_selection_ignores_untrusted_namespaces_and_illegal_tags(self):
        refs = [
            "refs/tags/v9.9.9",
            "refs/remotes/upstream/v8.0.0",
            "refs/upstream-tags/v1.2.3-beta.02",
            "refs/upstream-tags/v0.9.1-pre.4",
        ]

        self.assertEqual(
            version.select_upstream_tag(refs), "refs/upstream-tags/v0.9.1-pre.4"
        )
        with self.assertRaisesRegex(version.VersionError, "No reachable legal"):
            version.select_upstream_tag(refs[:-1])

    def test_metadata_rejects_old_schema_and_untrusted_sources(self):
        metadata = self.metadata_fixture()
        old_metadata = dict(metadata, schema_version=0)
        wrong_source = dict(metadata, repository="attacker/FlClash")

        with self.assertRaisesRegex(version.VersionError, "Unsupported"):
            version.validate_metadata_shape(old_metadata)
        with self.assertRaisesRegex(version.VersionError, "untrusted source"):
            version.validate_metadata_shape(wrong_source)

    def test_metadata_rejects_tag_pubspec_mismatch_and_source_mismatch(self):
        metadata = dict(self.metadata_fixture(), version="0.9.1-pre.10")
        wrong_source = dict(
            self.metadata_fixture(), pubspec_source_commit="b" * 40
        )

        with self.assertRaisesRegex(version.VersionError, "does not match"):
            version.validate_metadata_shape(metadata)
        with self.assertRaisesRegex(version.VersionError, "source commit"):
            version.validate_metadata_shape(wrong_source)

    def test_sync_action_distinguishes_no_change_tag_only_and_code(self):
        current = self.metadata_fixture()
        same = dict(self.metadata_fixture(), source_branches=["dev", "main"])
        newer_tag = dict(
            current,
            tag="v0.9.1-pre.10",
            version="0.9.1-pre.10",
        )
        newer_code = dict(
            current,
            source_commit="c" * 40,
            tag_commit="c" * 40,
            pubspec_source_commit="c" * 40,
        )

        self.assertEqual(
            version.sync_action(current, same, source_in_base=True), "no_change"
        )
        self.assertEqual(
            version.sync_action(current, newer_tag, source_in_base=True),
            "metadata_only",
        )
        self.assertEqual(
            version.sync_action(current, newer_code, source_in_base=False), "merge"
        )
        self.assertEqual(
            version.sync_action(
                dict(current, schema_version=0), same, source_in_base=True
            ),
            "metadata_only",
        )

    def test_temp_git_fixture_peels_annotated_tag_and_checks_ancestry(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            self.git(repo, "init", "-b", "main")
            self.git(repo, "config", "user.name", "Offline Test")
            self.git(repo, "config", "user.email", "offline@example.invalid")
            (repo / "pubspec.yaml").write_text(
                "name: fixture\nversion: 0.9.1+2026101701\n",
                encoding="utf-8",
            )
            self.git(repo, "add", "pubspec.yaml")
            self.git(repo, "commit", "-m", "upstream source")
            source_commit = self.git(repo, "rev-parse", "HEAD")
            self.git(repo, "tag", "-a", "v0.9.1-pre.4", "-m", "release tag")
            self.git(
                repo,
                "update-ref",
                "refs/upstream-tags/v0.9.1-pre.4",
                "refs/tags/v0.9.1-pre.4",
            )
            tag_commit = version.resolve_tag_commit(
                repo, "refs/upstream-tags/v0.9.1-pre.4"
            )

            self.assertEqual(tag_commit, source_commit)
            self.assertTrue(version.git_is_ancestor(repo, tag_commit, source_commit))
            self.assertTrue(version.git_is_ancestor(repo, source_commit, source_commit))

            (repo / "change.txt").write_text("next commit\n", encoding="utf-8")
            self.git(repo, "add", "change.txt")
            self.git(repo, "commit", "-m", "new dev source")
            new_source_commit = self.git(repo, "rev-parse", "HEAD")
            self.assertTrue(
                version.git_is_ancestor(repo, source_commit, new_source_commit)
            )
            self.assertFalse(
                version.git_is_ancestor(repo, new_source_commit, source_commit)
            )

    def test_selection_requires_a_tag_reachable_from_any_upstream_branch(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            self.git(repo, "init", "-b", "main")
            self.git(repo, "config", "user.name", "Offline Test")
            self.git(repo, "config", "user.email", "offline@example.invalid")
            (repo / "pubspec.yaml").write_text(
                "name: fixture\nversion: 0.9.1+2026101701\n",
                encoding="utf-8",
            )
            self.git(repo, "add", "pubspec.yaml")
            self.git(repo, "commit", "-m", "pre4 source")
            pre4 = self.git(repo, "rev-parse", "HEAD")
            self.git(repo, "tag", "-a", "v0.9.1-pre.4", "-m", "pre4")
            self.git(
                repo,
                "update-ref",
                "refs/upstream-tags/v0.9.1-pre.4",
                "refs/tags/v0.9.1-pre.4",
            )
            self.git(repo, "update-ref", "refs/remotes/upstream/dev", pre4)

            self.git(repo, "checkout", "-b", "feature")
            (repo / "next.txt").write_text("tagged feature\n", encoding="utf-8")
            self.git(repo, "add", "next.txt")
            self.git(repo, "commit", "-m", "pre10 source")
            pre10 = self.git(repo, "rev-parse", "HEAD")
            self.git(repo, "tag", "-a", "v0.9.1-pre.10", "-m", "pre10")
            self.git(
                repo,
                "update-ref",
                "refs/upstream-tags/v0.9.1-pre.10",
                "refs/tags/v0.9.1-pre.10",
            )
            self.git(repo, "update-ref", "refs/remotes/upstream/feature", pre10)
            (repo / "orphan.txt").write_text(
                "not on an upstream branch\n", encoding="utf-8"
            )
            self.git(repo, "add", "orphan.txt")
            self.git(repo, "commit", "-m", "orphan release source")
            self.git(repo, "tag", "-a", "v9.9.9", "-m", "unreachable tag")
            self.git(
                repo,
                "update-ref",
                "refs/upstream-tags/v9.9.9",
                "refs/tags/v9.9.9",
            )

            selected = version.select_reachable_release(repo)
            self.assertEqual(selected["tag"], "v0.9.1-pre.10")
            self.assertEqual(selected["source_commit"], pre10)
            self.assertEqual(selected["source_branches"], ["feature"])
            with self.assertRaisesRegex(version.VersionError, "No legal upstream stable"):
                version.select_reachable_release(repo, stable_only=True)

    def test_release_plan_requires_source_commit_in_head_and_matching_pubspec(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            self.git(repo, "init", "-b", "main")
            self.git(repo, "config", "user.name", "Offline Test")
            self.git(repo, "config", "user.email", "offline@example.invalid")
            (repo / "pubspec.yaml").write_text(
                "name: fixture\nversion: 0.9.1+2026101701\n",
                encoding="utf-8",
            )
            self.git(repo, "add", "pubspec.yaml")
            self.git(repo, "commit", "-m", "upstream source")
            source_commit = self.git(repo, "rev-parse", "HEAD")
            self.git(repo, "tag", "-a", "v0.9.1-pre.4", "-m", "release tag")
            self.git(
                repo,
                "update-ref",
                "refs/upstream-tags/v0.9.1-pre.4",
                "refs/tags/v0.9.1-pre.4",
            )
            self.git(repo, "update-ref", "refs/remotes/upstream/dev", source_commit)
            metadata = version.make_metadata(
                source_commit=source_commit,
                tag_ref="refs/upstream-tags/v0.9.1-pre.4",
                tag_commit=version.resolve_tag_commit(
                    repo, "refs/upstream-tags/v0.9.1-pre.4"
                ),
                source_branches=["dev"],
                pubspec_version="0.9.1+2026101701",
            )

            plan = version.build_release_plan(
                metadata=metadata,
                current_pubspec=(repo / "pubspec.yaml").read_text(encoding="utf-8"),
                repo=repo,
                head=source_commit,
                run_number=1,
            )
            self.assertEqual(plan["name"], "0.9.1-pre.4-X")
            self.assertEqual(plan["app_env"], "pre")
            self.assertEqual(plan["number"], 2026101701)
            self.assertEqual(plan["tag"], "v0.9.1-pre.4-X+2026101701")

            forged_newer_tag = dict(
                metadata, tag="v99.0.0-pre.1", version="99.0.0-pre.1"
            )
            with self.assertRaisesRegex(version.VersionError, "highest official"):
                version.build_release_plan(
                    metadata=forged_newer_tag,
                    current_pubspec=(repo / "pubspec.yaml").read_text(encoding="utf-8"),
                    repo=repo,
                    head=source_commit,
                    run_number=2,
                )

            self.git(repo, "checkout", "-b", "newer-source")
            (repo / "next.txt").write_text("new source\n", encoding="utf-8")
            self.git(repo, "add", "next.txt")
            self.git(repo, "commit", "-m", "newer upstream source")
            newer_source = self.git(repo, "rev-parse", "HEAD")
            self.git(repo, "checkout", "main")
            self.git(repo, "update-ref", "refs/remotes/upstream/dev", newer_source)
            self.git(
                repo,
                "tag",
                "-a",
                "v0.9.1-pre.5",
                newer_source,
                "-m",
                "newer upstream release",
            )
            self.git(
                repo,
                "update-ref",
                "refs/upstream-tags/v0.9.1-pre.5",
                "refs/tags/v0.9.1-pre.5",
            )
            with self.assertRaisesRegex(version.VersionError, "not contained in HEAD"):
                version.build_release_plan(
                    metadata=dict(
                        metadata,
                        tag="v0.9.1-pre.5",
                        version="0.9.1-pre.5",
                        tag_commit=newer_source,
                        source_commit=newer_source,
                        pubspec_source_commit=newer_source,
                    ),
                    current_pubspec=(repo / "pubspec.yaml").read_text(encoding="utf-8"),
                    repo=repo,
                    head=source_commit,
                    run_number=2,
                )
            self.git(
                repo,
                "update-ref",
                "refs/upstream-tags/v0.9.1-pre.4",
                newer_source,
            )
            with self.assertRaisesRegex(version.VersionError, "highest official"):
                version.build_release_plan(
                    metadata=metadata,
                    current_pubspec=(repo / "pubspec.yaml").read_text(encoding="utf-8"),
                    repo=repo,
                    head=source_commit,
                    run_number=2,
                )
            with self.assertRaisesRegex(version.VersionError, "does not match"):
                version.build_release_plan(
                    metadata=metadata,
                    current_pubspec="version: 0.9.2+2026101701\n",
                    repo=repo,
                    head=source_commit,
                    run_number=2,
                )

    def test_stable_release_remains_valid_when_next_preview_is_published(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            self.git(repo, "init", "-b", "main")
            self.git(repo, "config", "user.name", "Offline Test")
            self.git(repo, "config", "user.email", "offline@example.invalid")
            stable_pubspec = "name: fixture\nversion: 0.9.1+2026101701\n"
            (repo / "pubspec.yaml").write_text(stable_pubspec, encoding="utf-8")
            self.git(repo, "add", "pubspec.yaml")
            self.git(repo, "commit", "-m", "stable source")
            stable = self.git(repo, "rev-parse", "HEAD")
            self.git(repo, "update-ref", "refs/remotes/upstream/main", stable)
            self.git(repo, "update-ref", "refs/upstream-tags/v0.9.1", stable)
            metadata = version.make_metadata(
                source_commit=stable,
                tag_ref="refs/upstream-tags/v0.9.1",
                tag_commit=stable,
                source_branches=["main"],
                pubspec_version="0.9.1+2026101701",
            )

            preview_pubspec = "name: fixture\nversion: 0.9.2+2026102101\n"
            (repo / "pubspec.yaml").write_text(preview_pubspec, encoding="utf-8")
            self.git(repo, "add", "pubspec.yaml")
            self.git(repo, "commit", "-m", "preview source")
            preview = self.git(repo, "rev-parse", "HEAD")
            self.git(repo, "update-ref", "refs/remotes/upstream/dev", preview)
            self.git(repo, "update-ref", "refs/upstream-tags/v0.9.2-pre.1", preview)

            plan = version.build_release_plan(
                metadata=metadata, current_pubspec=stable_pubspec,
                repo=repo, head=stable, run_number=3,
            )
            self.assertEqual(plan["name"], "0.9.1-X")
            self.assertEqual(plan["app_env"], "stable")
            self.assertEqual(plan["upstream_source_commit"], stable)
            self.assertEqual(version.select_reachable_release(repo)["tag"], "v0.9.2-pre.1")
            self.assertEqual(
                version.select_reachable_release(repo, stable_only=True)["tag"],
                "v0.9.1",
            )
            preview_metadata = version.make_metadata(
                source_commit=preview,
                tag_ref="refs/upstream-tags/v0.9.2-pre.1",
                tag_commit=preview,
                source_branches=["dev"],
                pubspec_version="0.9.2+2026102101",
            )
            preview_plan = version.build_release_plan(
                metadata=preview_metadata, current_pubspec=preview_pubspec,
                repo=repo, head=preview, run_number=4,
            )
            self.assertEqual(preview_plan["name"], "0.9.2-pre.1-X")
            self.assertEqual(preview_plan["app_env"], "pre")

            for tag in ("v0.9.1", "v0.9.2"):
                with self.subTest(changed_tag=tag):
                    self.git(repo, "update-ref", f"refs/upstream-tags/{tag}", preview)
                    with self.assertRaisesRegex(version.VersionError, "highest official"):
                        version.build_release_plan(
                            metadata=metadata, current_pubspec=stable_pubspec,
                            repo=repo, head=stable, run_number=5,
                        )
                    self.git(repo, "update-ref", "refs/upstream-tags/v0.9.1", stable)

    def test_release_environment_follows_selected_tag_instead_of_pubspec_or_branch(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            self.git(repo, "init", "-b", "main")
            self.git(repo, "config", "user.name", "Offline Test")
            self.git(repo, "config", "user.email", "offline@example.invalid")
            pubspec = "name: fixture\nversion: 0.9.1+2026101701\n"
            (repo / "pubspec.yaml").write_text(pubspec, encoding="utf-8")
            self.git(repo, "add", "pubspec.yaml")
            self.git(repo, "commit", "-m", "upstream source")
            source_commit = self.git(repo, "rev-parse", "HEAD")
            self.git(repo, "update-ref", "refs/remotes/upstream/dev", source_commit)
            self.git(repo, "update-ref", "refs/remotes/upstream/main", source_commit)

            for tag, app_env in (("v0.9.1-pre.4", "pre"), ("v0.9.1", "stable")):
                with self.subTest(tag=tag):
                    tag_ref = f"refs/upstream-tags/{tag}"
                    self.git(repo, "update-ref", tag_ref, source_commit)
                    selected = version.select_reachable_release(repo)
                    self.assertEqual(selected["tag"], tag)
                    self.assertEqual(selected["source_branches"], ["dev", "main"])
                    metadata = version.make_metadata(
                        source_commit=source_commit,
                        tag_ref=tag_ref,
                        tag_commit=source_commit,
                        source_branches=selected["source_branches"],
                        pubspec_version="0.9.1+2026101701",
                    )
                    plan = version.build_release_plan(
                        metadata=metadata,
                        current_pubspec=pubspec,
                        repo=repo,
                        head=source_commit,
                        run_number=2,
                    )
                    self.assertEqual(plan["app_env"], app_env)
                    self.assertEqual(plan["name"], f"{tag.removeprefix('v')}-X")
                    self.assertEqual(plan["number"], 2026101702)

    @staticmethod
    def metadata_fixture():
        return {
            "schema_version": version.SCHEMA_VERSION,
            "repository": version.UPSTREAM_REPOSITORY,
            "source_commit": "a" * 40,
            "tag": "v0.9.1-pre.4",
            "tag_commit": "a" * 40,
            "source_branches": ["dev"],
            "version": "0.9.1-pre.4",
            "pubspec_version": "0.9.1+2026101701",
            "pubspec_source_commit": "a" * 40,
        }

    @staticmethod
    def git(repo, *args):
        result = subprocess.run(
            ["git", *args],
            cwd=repo,
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            raise AssertionError(result.stderr)
        return result.stdout.strip()


if __name__ == "__main__":
    unittest.main()
