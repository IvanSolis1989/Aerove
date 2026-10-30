import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


LUMINOUS_PATHS = ['test/widgets/glass_foreground_test.dart',
 'test/widgets/glass_ink_cache_test.dart',
 'test/widgets/glass_ink_quality_test.dart',
 'test/widgets/glass_visibility_test.dart',
 'test/widgets/glass_body_blur_test.dart',
 'test/widgets/target_icon_test.dart',
 'test/widgets/selection_mark_test.dart',
 'test/widgets/loading_ink_test.dart',
 'test/widgets/core_status_button_test.dart',
 'test/lint/sheet_scroll_controller_test.dart',
 'test/widgets/appbar_actions_test.dart',
 'test/widgets/switch_glass_test.dart',
 'test/widgets/slider_glass_test.dart',
 'test/widgets/segmented_control_glass_test.dart',
 'test/widgets/palette_glass_test.dart',
 'test/widgets/header_button_glass_test.dart',
 'test/manager/status_message_glass_test.dart',
 'test/common/changelog_test.dart',
 'test/common/request_test.dart',
 'test/widgets/update_dialog_test.dart',
 'test/l10n/app_localizations_test.dart',
 'test/tool/packaging_config_test.dart',
 'test/common/protocol_test.dart',
 'test/views/dashboard/network_takeover_test.dart',
 'test/models/config_test.dart',
 'test/providers/config_test.dart',
 'test/common/xclash_defaults_test.dart',
 'test/manager/android_manager_test.dart',
 'test/views/network_config_test.dart',
 'test/models/dns_override_test.dart',
 'test/common/cidr_test.dart',
 'test/common/task_dns_override_test.dart',
 'test/common/task_tun_override_test.dart',
 'test/providers/state_derived_test.dart',
 'test/providers/custom_import_test.dart',
 'test/views/dns_view_test.dart',
 'test/views/disclaimer_view_test.dart',
 'test/common/dialog_test.dart',
 'test/widgets/aurora_app_test.dart',
 'test/views/theme_test.dart',
 'test/views/aurora_settings_test.dart',
 'test/providers/action_test.dart',
 'test/widgets/aurora_activity_test.dart',
 'test/widgets/aurora_lifecycle_test.dart',
 'test/manager/core_manager_test.dart',
 'test/providers/proxies_action_test.dart',
 'test/providers/common_action_test.dart',
 'test/providers/ip_quality_test.dart',
 'test/common/ip_quality_test.dart',
 'test/application_test.dart',
 'test/providers/profiles_action_test.dart',
 'test/providers/resources_action_test.dart',
 'test/providers/system_action_test.dart',
 'test/providers/icon_sets_action_test.dart',
 'test/providers/clash_providers_action_test.dart',
 'test/models/profile_save_test.dart',
 'test/providers/updating_action_test.dart',
 'test/core/protocol_contract_test.dart']

FEATHER_PATHS = ['test/widgets/header_blur_occlusion_test.dart', 'test/widgets/header_blur_test.dart']

REPORTED_PATHS = ['test/widgets/glass_ink_quality_test.dart',
 'test/widgets/aurora_app_test.dart',
 'test/views/disclaimer_view_test.dart',
 'test/widgets/aurora_activity_test.dart',
 'test/widgets/aurora_motion_test.dart',
 'test/widgets/aurora_lifecycle_test.dart',
 'test/widgets/theme_cross_fade_test.dart',
 'test/widgets/subscription_usage_fill_test.dart',
 'test/widgets/dashboard_text_bounds_test.dart',
 'test/widgets/proxies_scroll_work_test.dart',
 'test/widgets/proxies_list_test.dart',
 'test/widgets/list_header_focus_test.dart',
 'test/widgets/sheet_occlusion_test.dart',
 'test/widgets/sheet_surface_render_test.dart',
 'test/widgets/glass_sheet_surfaces_test.dart',
 'test/widgets/sheet_barrier_test.dart',
 'test/views/dashboard/foreground_test.dart',
 'test/views/dashboard/traffic_test.dart',
 'test/views/aurora_settings_test.dart',
 'test/views/about_text_bounds_test.dart',
 'test/android_tv_launcher_icon_test.dart',
 'test/android_branding_assets_test.dart',
 'test/widgets/scrollbar_inset_test.dart',
 'test/lint/dead_file_test.dart',
 'test/pages/tablet_sidebar_bounds_test.dart',
 'test/widgets/sidebar_test.dart',
 'test/widgets/sidebar_glass_test.dart',
 'test/widgets/header_button_glass_test.dart',
 'test/widgets/header_frame_pages_test.dart',
 'test/lint/header_bar_test.dart',
 'test/widgets/sheet_search_test.dart',
 'test/manager/status_message_glass_test.dart',
 'test/manager/status_manager_test.dart',
 'test/widgets/skeleton_test.dart',
 'test/widgets/switch_glass_test.dart',
 'test/widgets/slider_glass_test.dart',
 'test/widgets/segmented_control_glass_test.dart',
 'test/widgets/palette_glass_test.dart',
 'test/widgets/palette_test.dart',
 'test/views/theme_test.dart',
 'test/common/changelog_test.dart',
 'test/l10n/app_localizations_test.dart',
 'test/tool/packaging_config_test.dart',
 'test/widgets/glass_visibility_test.dart',
 'test/common/aurora_rotation_test.dart',
 'test/manager/aurora_rotation_test.dart',
 'test/providers/store_action_test.dart',
 'test/views/aurora_settings_test.dart',
 'test/widgets/glass_cache_lifecycle_test.dart',
 'test/common/task_test.dart',
 'test/widgets/icon_loading_test.dart',
 'test/views/icon_sets_view_test.dart',
 'test/common/update_download_test.dart',
 'test/plugins/update_download_test.dart',
 'test/widgets/update_dialog_test.dart']

PERFORMANCE_PATHS = ['test/widgets/ink_motion_baseline_test.dart',
 'test/widgets/navigation_dock_ink_test.dart',
 'test/widgets/navigation_dock_collapse_test.dart',
 'test/pages/home_dock_scroll_test.dart',
 'test/pages/home_test.dart',
 'test/widgets/aurora_transform_test.dart',
 'test/widgets/aurora_motion_test.dart',
 'test/widgets/aurora_activity_test.dart',
 'test/widgets/aurora_lifecycle_test.dart',
 'test/widgets/aurora_text_colors_test.dart',
 'test/widgets/glass_ink_cache_test.dart',
 'test/widgets/glass_ink_geometry_test.dart',
 'test/widgets/dashboard_text_bounds_test.dart',
 'test/views/about_text_bounds_test.dart',
 'test/widgets/shared_text_bounds_test.dart',
 'test/widgets/glass_wallpaper_sampling_test.dart',
 'test/widgets/source_blur_cache_test.dart',
 'test/widgets/glass_cache_lifecycle_test.dart',
 'test/widgets/sheet_occlusion_test.dart',
 'test/widgets/popup_occlusion_test.dart',
 'test/widgets/home_first_frame_glass_test.dart',
 'test/widgets/liquid_glass_host_test.dart',
 'test/widgets/glass_page_handover_test.dart',
 'test/widgets/settings_route_transition_test.dart',
 'test/widgets/open_container_test.dart',
 'test/widgets/theme_cross_fade_test.dart',
 'test/widgets/scaffold_actions_test.dart',
 'test/widgets/header_title_test.dart',
 'test/widgets/core_status_button_test.dart',
 'test/widgets/proxy_card_focus_test.dart',
 'test/widgets/proxies_list_test.dart',
 'test/widgets/proxies_scroll_work_test.dart',
 'test/widgets/proxies_tab_test.dart',
 'test/widgets/floating_scrollbar_test.dart',
 'test/widgets/scrollbar_inset_test.dart',
 'test/views/proxies_list_test.dart',
 'test/widgets/subscription_usage_fill_test.dart',
 'test/views/dashboard/foreground_test.dart',
 'test/views/dashboard/traffic_test.dart',
 'test/views/dashboard/current_outbound_test.dart',
 'test/views/dns_queries_view_test.dart',
 'test/common/aurora_rotation_test.dart',
 'test/views/aurora_settings_test.dart',
 'test/widgets/glass_foreground_test.dart',
 'test/widgets/target_icon_test.dart']

REGRESSION_IMPELLER_PATHS = ['test/widgets/glass_surface_scope_test.dart',
 'test/widgets/page_surfaces_glass_test.dart',
 'test/widgets/text_context_menu_test.dart',
 'test/widgets/linear_loading_test.dart',
 'test/widgets/record_table_scroll_test.dart',
 'test/widgets/liquid_glass_test.dart',
 'test/widgets/sheet_drag_handle_test.dart',
 'test/widgets/header_blur_test.dart',
 'test/widgets/glass_scroll_test.dart',
 'test/widgets/liquid_glass_host_test.dart',
 'test/widgets/glass_foreground_test.dart',
 'test/widgets/sliver_glass_group_test.dart',
 'test/widgets/header_title_test.dart',
 'test/widgets/sheet_surface_render_test.dart',
 'test/widgets/subscription_usage_fill_test.dart',
 'test/views/dashboard/traffic_test.dart',
 'test/widgets/aurora_motion_test.dart',
 'test/widgets/glass_button_test.dart',
 'test/widgets/record_glass_routes_test.dart',
 'test/widgets/sidebar_glass_test.dart',
 'test/widgets/target_icon_test.dart',
 'test/widgets/settings_route_transition_test.dart',
 'test/widgets/open_container_test.dart',
 'test/widgets/route_motion_hold_test.dart']

REGRESSION_SOFTWARE_PATHS = ['test/application_theme_test.dart',
 'test/features/glass_feature_surfaces_test.dart',
 'test/features/custom_quick_edit_test.dart',
 'test/features/editor_completion_test.dart',
 'test/manager/window_header_test.dart',
 'test/manager/window_manager_test.dart',
 'test/pages/editor_load_test.dart',
 'test/pages/scan_test.dart',
 'test/models/config_test.dart',
 'test/models/dns_override_test.dart',
 'test/providers/config_test.dart',
 'test/manager/theme_manager_test.dart',
 'test/manager/status_manager_test.dart',
 'test/manager/status_message_glass_test.dart',
 'test/common/preferences_test.dart',
 'test/common/dialog_test.dart',
 'test/common/tray_menu_test.dart',
 'test/common/request_test.dart',
 'test/common/path_test.dart',
 'test/common/protocol_test.dart',
 'test/common/xclash_defaults_test.dart',
 'test/common/task_dns_override_test.dart',
 'test/common/aurora_test.dart',
 'test/common/task_test.dart',
 'test/common/migration_test.dart',
 'test/common/update_download_test.dart',
 'test/plugins/update_download_test.dart',
 'test/providers/profiles_action_test.dart',
 'test/providers/backup_action_test.dart',
 'test/providers/action_test.dart',
 'test/views/aurora_settings_test.dart',
 'test/views/theme_test.dart',
 'test/views/dns_view_test.dart',
 'test/views/scripts_source_test.dart',
 'test/views/proxies_list_test.dart',
 'test/views/dashboard/',
 'test/views/script_content_test.dart',
 'test/pages/home_test.dart',
 'test/widgets/',
 'test/l10n/',
 'test/lint/']

CURRENT_REGRESSION_PATHS = [
    'test/product_boundary_test.dart',
    'test/common/preferences_store_skip_test.dart',
    'test/common/system_test_font_test.dart',
    'test/common/traffic_format_test.dart',
    'test/core/lib_test.dart',
    'test/interactions/',
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
    'test/state_storage/',
    'test/ui/list_row_separator_scene_test.dart',
    'test/ui/route_motion_hold_hidden_test.dart',
    'test/views/connections_test.dart',
    'test/views/hidden_feed_refresh_test.dart',
    'test/views/proxy_providers_view_test.dart',
    'test/views/requests_test.dart',
]

SUITES = {
    'luminous': {
        'paths': LUMINOUS_PATHS,
        'impeller': True,
        'require_impeller': 'true',
        'rust': False,
        'shards': 2,
    },
    'feather-impeller': {
        'paths': FEATHER_PATHS,
        'impeller': True,
        'require_impeller': 'true',
        'rust': False,
        'shards': 1,
    },
    'feather-software': {
        'paths': FEATHER_PATHS,
        'impeller': False,
        'require_impeller': 'false',
        'rust': False,
        'shards': 1,
    },
    'reported-impeller': {
        'paths': REPORTED_PATHS,
        'impeller': True,
        'require_impeller': 'true',
        'rust': False,
        'shards': 2,
    },
    'reported-software': {
        'paths': REPORTED_PATHS,
        'impeller': False,
        'require_impeller': 'false',
        'rust': False,
        'shards': 2,
    },
    'performance': {
        'paths': PERFORMANCE_PATHS,
        'impeller': True,
        'require_impeller': 'true',
        'rust': False,
        'shards': 3,
    },
    'regression-impeller': {
        'paths': REGRESSION_IMPELLER_PATHS,
        'impeller': True,
        'require_impeller': 'true',
        'rust': True,
        'shards': 2,
    },
    'regression-software': {
        'paths': REGRESSION_SOFTWARE_PATHS + CURRENT_REGRESSION_PATHS,
        'impeller': False,
        'require_impeller': None,
        'rust': True,
        'shards': 4,
    },
}


def matrices():
    result = {'render': [], 'regressions': []}
    for name, suite in SUITES.items():
        group = 'regressions' if suite['rust'] else 'render'
        result[group].extend(
            {'suite': name, 'shard': index, 'shards': suite['shards']}
            for index in range(suite['shards'])
        )
    return result


def command(name, shard):
    suite = SUITES[name]
    if not 0 <= shard < suite['shards']:
        raise ValueError('Invalid shard index')
    arguments = ['flutter', 'test', '--no-pub', '--reporter', 'expanded']
    if suite['impeller']:
        arguments.append('--enable-impeller')
    if suite['shards'] > 1:
        arguments.extend([
            '--total-shards', str(suite['shards']), '--shard-index', str(shard),
        ])
    return [*arguments, *suite['paths']]


@contextmanager
def hooks(root, *, rust):
    path = root / 'pubspec.yaml'
    original = path.read_bytes()
    text = original.decode('utf-8')
    for name, enabled in [('setup', False), ('rust_api', rust)]:
        text, count = re.subn(
            rf'(?m)^(    {name}:\s*\n      build_assets: )(true|false)(\s*)$',
            lambda match: match[1] + str(enabled).lower() + match[3], text,
        )
        if count != 1:
            raise ValueError(f'Expected one {name} native hook')
    path.write_text(text, encoding='utf-8', newline='\n')
    try:
        yield
    finally:
        path.write_bytes(original)


def execute(arguments, root, environment, log):
    with log.open('w', encoding='utf-8') as output:
        process = subprocess.Popen(
            arguments, cwd=root, env=environment, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace',
        )
        for line in process.stdout:
            sys.stdout.write(line)
            output.write(line)
        return process.wait()


def run_suite(name, shard, root, evidence):
    suite = SUITES[name]
    evidence.mkdir(parents=True, exist_ok=True)
    environment = dict(os.environ, XCLASH_SCREENSHOT_DIR=str(evidence))
    required = suite['require_impeller']
    if required is None:
        environment.pop('XCLASH_REQUIRE_IMPELLER', None)
    else:
        environment['XCLASH_REQUIRE_IMPELLER'] = required
    with hooks(root, rust=suite['rust']):
        return execute(
            command(name, shard), root, environment,
            evidence / ('focused.log' if name == 'performance' else 'tests.log'),
        )


def warmup(root, evidence):
    evidence.mkdir(parents=True, exist_ok=True)
    environment = dict(os.environ, XCLASH_SCREENSHOT_DIR=str(evidence))
    environment.pop('XCLASH_REQUIRE_IMPELLER', None)
    with hooks(root, rust=True):
        for index, arguments in enumerate([
            ['test/common/request_test.dart', 'test/widgets/update_dialog_test.dart'],
            ['test/widgets/uncovered_views_test.dart', '--plain-name',
             'about view shows progress while checking for updates'],
        ]):
            result = execute(
                ['flutter', 'test', '--no-pub', '--reporter', 'expanded', *arguments],
                root, environment, evidence / f'smoke-{index}.log',
            )
            if result:
                return result
    return 0


def check_gate(needs):
    expected = {'prep', 'static', 'render', 'native', 'regressions'}
    return set(needs) == expected and all(
        result.get('result') == 'success' for result in needs.values()
    )


def host_assets(root, *, record=False):
    shared = root / '.dart_tool/hooks_runner/shared/rust_api'
    libraries = sorted(shared.glob(
        'build/*/target/x86_64-unknown-linux-gnu/release/librust_api.so',
    ))
    repair = 'Rebuild the native producer with a clean host cache, then retry regressions.'
    if not libraries:
        raise RuntimeError(f'Host Rust output is missing. {repair}')
    rows = []
    for library in libraries:
        if library.is_symlink() or not library.resolve().is_relative_to(shared.resolve()):
            raise RuntimeError(f'Host Rust output escapes its cache. {repair}')
        data = library.read_bytes()
        if (len(data) < 64 or data[:6] != b'\x7fELF\x02\x01'
                or int.from_bytes(data[16:18], 'little') != 3
                or int.from_bytes(data[18:20], 'little') != 62):
            raise RuntimeError(f'Host Rust output is not an x86_64 ELF library. {repair}')
        rows.append({
            'path': library.relative_to(root).as_posix(), 'bytes': len(data),
            'sha256': hashlib.sha256(data).hexdigest(),
        })
    proof = {'version': 1, 'target': 'x86_64-unknown-linux-gnu', 'libraries': rows}
    manifest = shared / 'host-assets.json'
    if record:
        manifest.write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
    else:
        try:
            saved = json.loads(manifest.read_text(encoding='utf-8'))
        except (OSError, ValueError) as error:
            raise RuntimeError(f'Host Rust producer proof is missing or invalid. {repair}') from error
        if saved != proof:
            raise RuntimeError(f'Host Rust producer proof does not match the outputs. {repair}')
    return proof


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='operation', required=True)
    sub.add_parser('matrices')
    run = sub.add_parser('run')
    run.add_argument('suite', choices=SUITES)
    run.add_argument('--shard', type=int, default=0)
    for child in [run, sub.add_parser('warmup')]:
        child.add_argument('--evidence', type=Path, required=True)
    sub.add_parser('gate')
    host = sub.add_parser('host-assets')
    host.add_argument('--record', action='store_true')
    arguments = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if arguments.operation == 'host-assets':
        print(json.dumps(host_assets(root, record=arguments.record), indent=2))
        return 0
    if arguments.operation == 'matrices':
        with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as output:
            for name, rows in matrices().items():
                output.write(f'{name}_matrix={json.dumps({"include": rows})}\n')
        return 0
    if arguments.operation == 'gate':
        needs = json.loads(os.environ['CI_VERIFY_NEEDS'])
        if not check_gate(needs):
            print(json.dumps(needs, indent=2), file=sys.stderr)
            return 1
        return 0
    if arguments.operation == 'warmup':
        return warmup(root, arguments.evidence)
    return run_suite(
        arguments.suite, arguments.shard, root, arguments.evidence,
    )


if __name__ == '__main__':
    raise SystemExit(main())
