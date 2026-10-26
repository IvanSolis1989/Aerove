from contextlib import redirect_stdout
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tool import ci_verify


LEGACY_DIGESTS = {'feather-impeller': '7e1c2413849c13fa3c97e325b83a8aaacfc347a9025f69ddae206ebf5990d6f3',
 'feather-software': 'fa649eab8e203f2628e4f1246f863663cb06868be31d6ad6fee0e13ef27ca006',
 'luminous': '348bd14f7229209a3a5dde2452f26db61b8cf168d5cea5f23caba70c4fc41f5e',
 'performance': 'eb319b303b5f9cb90ba16b4eb2133cec161c7cfdd830d2ae1ecd90558bb98274',
 'regression-impeller': '3e7b38f080854efbc42c44ddea446b2e0346d030b2fa3722ab8fa888aa987c18',
 'regression-software': 'e20c05c52f8ebb2c798ecbd46d1e6ca4b4f99bddb576f4c09c29b01962041c38',
 'reported-impeller': '10c4cfac08cef267f5c5db5b8f8e66d33a52fa3363c858d2c10f910297bf8c7e',
 'reported-software': '6b6e5527c267e712b46dc96277f46c16a76e166efcfc5ccdce3d59ff4a539990'}


class CombinationCoverageTest(unittest.TestCase):
    def test_all_eight_original_combinations_keep_paths_renderer_and_native_hooks(self):
        self.assertEqual(set(ci_verify.SUITES), set(LEGACY_DIGESTS))
        for name, suite in ci_verify.SUITES.items():
            original = {key: value for key, value in suite.items() if key != 'shards'}
            if name == 'regression-software':
                self.assertEqual(suite['paths'], ci_verify.REGRESSION_SOFTWARE_PATHS
                                 + ci_verify.CURRENT_REGRESSION_PATHS)
                original['paths'] = ci_verify.REGRESSION_SOFTWARE_PATHS
            digest = hashlib.sha256(json.dumps(
                original, sort_keys=True, separators=(',', ':'),
            ).encode()).hexdigest()
            with self.subTest(suite=name):
                self.assertEqual(digest, LEGACY_DIGESTS[name])
                self.assertTrue(all(path.startswith('test/') for path in suite['paths']))

    def test_current_regressions_are_selected_in_every_existing_software_shard(self):
        tests = [
            'test/common/preferences_store_skip_test.dart',
            'test/common/system_test_font_test.dart',
            'test/common/traffic_format_test.dart',
            'test/core/lib_test.dart',
            'test/interactions/connections_activity_test.dart',
            'test/interactions/counts_null_test.dart',
            'test/interactions/counts_pending_test.dart',
            'test/interactions/outbound_activity_test.dart',
            'test/interactions/pacer_stop_build_test.dart',
            'test/interactions/publication_and_counts_test.dart',
            'test/interactions/shared_source_and_pacer_test.dart',
            'test/manager/app_manager_lifecycle_test.dart',
            'test/manager/connectivity_ssid_tracking_test.dart',
            'test/manager/theme_font_watch_test.dart',
            'test/manager/tray_lazy_menu_test.dart',
            'test/manager/window_minimize_save_test.dart',
            'test/plugins/service_test.dart',
            'test/providers/automatic_maintenance_reset_test.dart',
            'test/providers/automatic_update_backoff_test.dart',
            'test/providers/setup_action_test.dart',
            'test/providers/tray_title_state_test.dart',
            'test/state_storage/fixed_ring_test.dart',
            'test/state_storage/profile_select_test.dart',
            'test/state_storage/profile_write_test.dart',
            'test/state_storage/request_index_test.dart',
            'test/ui/list_row_separator_scene_test.dart',
            'test/ui/route_motion_hold_hidden_test.dart',
            'test/views/connections_test.dart',
            'test/views/hidden_feed_refresh_test.dart',
            'test/views/proxy_providers_view_test.dart',
            'test/views/requests_test.dart',
        ]
        suite = ci_verify.SUITES['regression-software']
        self.assertEqual(suite['shards'], 4)
        self.assertEqual(suite['impeller'], False)
        self.assertIsNone(suite['require_impeller'])
        self.assertTrue(suite['rust'])
        for shard in range(suite['shards']):
            command = ci_verify.command('regression-software', shard)
            for test in tests:
                with self.subTest(shard=shard, test=test):
                    self.assertTrue(any(test == path or path.endswith('/')
                                        and test.startswith(path)
                                        for path in command))

    def test_shards_cover_every_original_argument_without_expanding_or_omitting_directories(self):
        matrices = ci_verify.matrices()
        self.assertEqual(set(matrices), {'render', 'regressions'})
        for name, suite in ci_verify.SUITES.items():
            group = 'regressions' if suite['rust'] else 'render'
            rows = [row for row in matrices[group] if row['suite'] == name]
            self.assertEqual([row['shard'] for row in rows], list(range(suite['shards'])))
            for row in rows:
                command = ci_verify.command(name, row['shard'])
                self.assertEqual(command[-len(suite['paths']):], suite['paths'])
                self.assertEqual('--enable-impeller' in command, suite['impeller'])
                self.assertIn('--no-pub', command)
                if suite['shards'] > 1:
                    index = command.index('--total-shards')
                    self.assertEqual(command[index + 1], str(suite['shards']))
        self.assertIn('test/widgets/', ci_verify.SUITES['regression-software']['paths'])
        self.assertIn('test/lint/', ci_verify.SUITES['regression-software']['paths'])

    def test_invalid_shard_cannot_silently_skip_a_combination(self):
        for name, suite in ci_verify.SUITES.items():
            with self.subTest(suite=name), self.assertRaises(ValueError):
                ci_verify.command(name, suite['shards'])


class GateTest(unittest.TestCase):
    def setUp(self):
        self.needs = {name: {'result': 'success'} for name in
                      ['prep', 'static', 'render', 'native', 'regressions']}

    def test_only_all_successful_expected_jobs_are_accepted(self):
        self.assertTrue(ci_verify.check_gate(self.needs))
        for name in self.needs:
            for result in ['failure', 'cancelled', 'skipped', None]:
                changed = {**self.needs, name: {'result': result}}
                with self.subTest(job=name, result=result):
                    self.assertFalse(ci_verify.check_gate(changed))
        self.assertFalse(ci_verify.check_gate({**self.needs, 'unknown': {'result': 'success'}}))
        self.assertFalse(ci_verify.check_gate({'prep': {'result': 'success'}}))


class HookRestorationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.path = self.root / 'pubspec.yaml'
        self.original = b'hooks:\r\n  user_defines:\r\n    setup:\r\n      build_assets: true\r\n    rust_api:\r\n      build_assets: true\r\n'
        self.path.write_bytes(self.original)

    def test_hook_flags_restore_exact_original_bytes_after_failure(self):
        with self.assertRaisesRegex(RuntimeError, 'test failed'):
            with ci_verify.hooks(self.root, rust=False):
                text = self.path.read_text()
                self.assertEqual(text.count('build_assets: false'), 2)
                raise RuntimeError('test failed')
        self.assertEqual(self.path.read_bytes(), self.original)

    def test_native_mode_keeps_rust_enabled(self):
        with ci_verify.hooks(self.root, rust=True):
            text = self.path.read_text()
            self.assertIn('setup:\n      build_assets: false', text)
            self.assertIn('rust_api:\n      build_assets: true', text)
        self.assertEqual(self.path.read_bytes(), self.original)

    def test_renderer_failure_is_not_hidden_by_log_capture(self):
        process = unittest.mock.Mock(stdout=io.StringIO('original failure\n'))
        process.wait.return_value = 7
        with patch.object(ci_verify.subprocess, 'Popen', return_value=process), redirect_stdout(io.StringIO()):
            code = ci_verify.run_suite('performance', 0, self.root, self.root / 'evidence')
        self.assertEqual(code, 7)
        self.assertEqual((self.root / 'evidence/focused.log').read_text(), 'original failure\n')
        self.assertEqual(self.path.read_bytes(), self.original)


class HostRustCacheTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.library = self.root / '.dart_tool/hooks_runner/shared/rust_api/build/config/target/x86_64-unknown-linux-gnu/release/librust_api.so'

    def write_library(self, machine=62):
        self.library.parent.mkdir(parents=True)
        header = bytearray(64)
        header[:6] = b'\x7fELF\x02\x01'
        header[16:18] = (3).to_bytes(2, 'little')
        header[18:20] = machine.to_bytes(2, 'little')
        self.library.write_bytes(header)

    def test_empty_producer_cannot_record_a_successful_host_cache(self):
        with self.assertRaisesRegex(RuntimeError, 'native producer'):
            ci_verify.host_assets(self.root, record=True)

    def test_consumer_requires_a_producer_manifest(self):
        self.write_library()
        with self.assertRaisesRegex(RuntimeError, 'native producer'):
            ci_verify.host_assets(self.root)

    def test_recorded_x64_elf_is_accepted_without_rebuilding(self):
        self.write_library()
        proof = ci_verify.host_assets(self.root, record=True)
        self.assertEqual(ci_verify.host_assets(self.root), proof)

    def test_missing_or_changed_host_binary_fails_closed(self):
        self.write_library()
        ci_verify.host_assets(self.root, record=True)
        self.library.write_bytes(self.library.read_bytes() + b'changed')
        with self.assertRaisesRegex(RuntimeError, 'native producer'):
            ci_verify.host_assets(self.root)
        self.library.unlink()
        with self.assertRaisesRegex(RuntimeError, 'native producer'):
            ci_verify.host_assets(self.root)

    def test_wrong_architecture_cannot_be_saved_as_host_assets(self):
        self.write_library(machine=183)
        with self.assertRaisesRegex(RuntimeError, 'native producer'):
            ci_verify.host_assets(self.root, record=True)


if __name__ == '__main__':
    unittest.main()
