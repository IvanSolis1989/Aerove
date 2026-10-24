"""Inspect Lite APK identity, ABI and verified signing output before promotion."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import zipfile

HELPER_LIBRARIES = ("libaerove_filter_helper.so", "libaerove_filter_jni.so")
HELPER_LICENSE_PREFIX = "assets/licenses/client-helper/"
ANDROID_MAX_PAGE_SIZE = 16 * 1024
MEOW_SOURCE = "727ddcd06f592e2f12c5222c4de6cce328df808c"
CORE_LIBRARY = "libaerove_meow_core.so"
MEOW_ROOT = Path(__file__).resolve().parents[1] / "meow-native"
_ORIGINAL19_CORE_JNI_SYMBOLS = tuple("Java_dev_aerove_lite_core_MeowNativeCore_" + name for name in
    ("startOwned", "status", "listenerStatus", "awaitReady", "stop", "awaitExit", "listenerCommand",
     "configurePolicies", "policyStatus", "capabilities", "validate", "inspectRuleSet", "dumpRuleSet", "validateResource",
     "hostPolicyCommand")) + tuple(
    "Java_dev_aerove_lite_overrides_NativeScriptEngine_native" + name for name in
    ("Begin", "Evaluate", "Cancel", "Release"))

RESOURCE_JNI_DESCRIPTORS = {'Java_dev_aerove_lite_core_JniResourceFetch_nativeCreate': '(Ljava/lang/String;)J', 'Java_dev_aerove_lite_core_JniResourceFetch_nativeLoad': '(JLjava/lang/String;)V', 'Java_dev_aerove_lite_core_JniResourceFetch_nativeDownload': '(JLjava/lang/String;)Ljava/lang/String;', 'Java_dev_aerove_lite_core_JniResourceFetch_nativeCancel': '(J)V', 'Java_dev_aerove_lite_core_JniResourceFetch_nativeCloseJoin': '(J)V', 'Java_dev_aerove_lite_core_JniResourceFetch_nativeValidatePrepared': '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;', 'Java_dev_aerove_lite_core_JniResourceFetch_nativePreflightRuleResource': '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;'}
RESOURCE_JNI_SYMBOLS = tuple(RESOURCE_JNI_DESCRIPTORS)
RESOURCE_HEALTH_CASES = {'resource_fetch': ['materialized_file_provider_is_the_named_download_graph_and_never_reads_old_cache', 'provider_transport_rejects_external_payload_and_unowned_dns_geodata_before_factory', 'named_candidate_graph_real_http_headers_staging_cap_and_no_bootstrap_rule_fetch', 'cancelled_named_fetch_joins_real_socket_and_never_returns_a_staged_success', 'invalid_candidate_dialer_cycle_has_no_network_or_staged_commit', 'prepared_geosite_uses_actual_owned_database_and_rejects_absent_or_external_resource', 'resource_preflight_is_typed_pending_and_standalone_remains_strict', 'resource_preflight_rejects_invalid_independent_rows_and_validates_no_geo'], 'lib': ['provider_policy_fields_defaults_disabled_and_fail_closed', 'providers_keep_distinct_policies_and_only_declared_group_touch_mapping', 'independent_initial_periodic_status_and_stop_read_back', 'lazy_only_real_group_business_touch_and_no_probe_self_touch', 'epoch_and_cancel_fence_actual_pending_health', 'live_network_context_refreshes_each_round_after_hot_network_and_probe_epoch', 'manual_intent_wins_and_member_replacement_retires_old_result', 'newer_automatic_intent_fences_pending_manual_real_response', 'configured_timeout_is_real_and_stop_joins_the_pending_socket', 'provider_health_defaults_match_standard_without_enabling_periodic_meow_default']}

CORE_JNI_SYMBOLS = _ORIGINAL19_CORE_JNI_SYMBOLS + RESOURCE_JNI_SYMBOLS


HOST_NO_FAIL_FAST_TARGETS = (
    'lib', 'api_lifecycle', 'config_validation', 'control_plane',
    'general_groups', 'host_policy_runtime', 'node_delay', 'offline_scope',
    'owned_runtime', 'owned_spawn', 'packet_fd', 'physical_dns',
    'provider_events', 'provider_files', 'provider_rules', 'proxy_runtime',
    'request_policy', 'resource_roots', 'runtime_transports', 'transport_tasks',
    'tun_driver',
)


HOST_POLICY_RUNTIME_TARGETS = ('lib',
 'anytls_owned_shutdown',
 'api_lifecycle',
 'config_validation',
 'control_plane',
 'dns_construction',
 'general_groups',
 'group_delay',
 'health_round_context',
 'host_policy_runtime',
 'node_delay',
 'offline_scope',
 'owned_runtime',
 'owned_spawn',
 'packet_fd',
 'physical_dns',
 'provider_events',
 'provider_files',
 'provider_rules',
 'proxy_runtime',
 'request_policy',
 'resource_roots',
 'runtime_transports',
 'statistics_actual_outcome',
 'tcp_dns',
 'transport_tasks',
 'tun_driver',
 'urltest_gate',
 'user_request_context')
CORE_POLICY_TEST_CASES = (('meow-common', 'cli_without_scope_keeps_output_and_real_join_error', 1),
 ('meow-common', 'observer_does_not_consume_protocol_output_or_panic', 1),
 ('meow-common', 'protocol_can_consume_error_before_native_observer', 1),
 ('meow-common', 'abort_handle_targets_actual_work_and_real_socket_reaches_eof', 1),
 ('meow-common', 'dropped_protocol_handle_remains_owned_until_actual_join', 1),
 ('meow-common', 'cancelled_join_waiter_retains_actual_handle_for_retry', 1),
 ('meow-common', 'closed_admission_drops_future_without_polling_user_work', 1),
 ('meow-common', 'nested_task_captures_same_scope_without_an_observer_task', 1),
 ('meow-common', 'ready_then_session_cancel_has_actual_completed_ack', 1),
 ('meow-common', 'captured_closed_session_never_adopts_another_current_owner', 1),
 ('meow-common', 'send_only_user_future_keeps_scoped_join_send_sync', 1),
 ('meow-proxy', 'actual_tasked_duplex_preserves_stream_and_proxy_conversion', 1),
 ('meow-proxy', 'actual_vmess_read_write_supervisor_are_joined_before_transport_eof_ack', 1),
 ('meow-proxy', 'closed_session_rejects_vmess_before_any_relay_task_is_spawned', 1),
 ('meow-transport', 'actual_stream_preserves_send_sync_transport_contract', 3),
 ('meow-transport', 'closed_origin_upgrade_has_repeatable_error_and_zero_http_bytes', 1),
 ('meow-anytls', 'actual_pool_reaper_drop_aborts_and_native_observer_joins_resource_release', 1),
 ('meow-anytls',
  'pooled_close_cancelled_after_admission_can_resume_and_last_arc_drops_both_transports',
  1),
 ('meow-anytls',
  'concurrent_close_waiter_observes_completion_and_takes_over_cancelled_first_attempt',
  1),
 ('meow-dns', 'resolver_captures_generation_scope_when_constructed', 1),
 ('meow-dns', 'upstream_exchange_stays_with_captured_generation', 1),
 ('meow-dns', 'retiring_generation_cancels_and_joins_pending_exchange', 1))
HOST_POLICY_RUNTIME_V2_TARGETS = ('lib',
 'anytls_owned_shutdown',
 'api_lifecycle',
 'config_validation',
 'control_plane',
 'dns_construction',
 'ech_lazy',
 'ech_response_binding',
 'general_groups',
 'group_delay',
 'health_round_context',
 'host_policy_runtime',
 'native_ech_context',
 'node_delay',
 'offline_scope',
 'owned_runtime',
 'owned_spawn',
 'packet_fd',
 'physical_dns',
 'provider_events',
 'provider_files',
 'provider_rules',
 'proxy_runtime',
 'request_policy',
 'resource_roots',
 'runtime_admission',
 'runtime_transports',
 'statistics_actual_outcome',
 'tcp_dns',
 'transport_tasks',
 'tun_driver',
 'urltest_gate',
 'user_request_context')
# Traffic stage has a separate mode, so deleting its sole new target cannot admit old33.
HOST_POLICY_RUNTIME_TRAFFIC_TARGETS = tuple(
    target for original in HOST_POLICY_RUNTIME_V2_TARGETS
    for target in ((original, 'native_telemetry') if original == 'native_ech_context' else (original,)))
HOST_POLICY_RUNTIME_OBSERVABILITY_TARGETS = tuple(
    target for original in HOST_POLICY_RUNTIME_TRAFFIC_TARGETS
    for target in ((original, 'native_observability') if original == 'native_ech_context' else (original,)))
HOST_POLICY_RUNTIME_PROVIDER_GENERAL_TARGETS = ('lib',)+tuple(sorted(set(HOST_POLICY_RUNTIME_OBSERVABILITY_TARGETS[1:])|{'runtime_providers', 'provider_groups', 'provider_projection_gate', 'runtime_dialers', 'runtime_general_groups', 'runtime_provider_projection'}))
HOST_POLICY_RUNTIME_RESOURCE_HEALTH_TARGETS = ('lib',)+tuple(sorted(set(HOST_POLICY_RUNTIME_PROVIDER_GENERAL_TARGETS[1:])|{'resource_fetch'}))
CORE_POLICY_TEST_CASES_V2 = (('meow-transport', 'scoped_join::', 11, 'lib', ('ws', 'grpc', 'h2', 'xhttp')),
 ('meow-transport',
  'actual_stream_preserves_send_sync_transport_contract',
  3,
  'lib',
  ('ws', 'grpc', 'h2', 'xhttp')),
 ('meow-transport',
  'closed_origin_upgrade_has_repeatable_error_and_zero_http_bytes',
  1,
  'lib',
  ('ws', 'grpc', 'h2', 'xhttp')),
 ('meow-proxy', 'vmess::conn::scoped_owner_tests::', 2, 'lib', ('vmess',)),
 ('meow-proxy',
  'actual_tasked_duplex_preserves_stream_and_proxy_conversion',
  1,
  'lib',
  ('vmess',)),
 ('meow-anytls',
  'actual_pool_reaper_drop_aborts_and_native_observer_joins_resource_release',
  1,
  'lib',
  ()),
 ('meow-common', None, 3, 'transport_scope_compat', ('aerove-android-runtime',)),
 ('meow-anytls',
  'pooled_close_cancelled_after_admission_can_resume_and_last_arc_drops_both_transports',
  1,
  'lib',
  ()),
 ('meow-anytls',
  'concurrent_close_waiter_observes_completion_and_takes_over_cancelled_first_attempt',
  1,
  'lib',
  ()),
 ('meow-dns', 'resolver_captures_generation_scope_when_constructed', 1, 'lib', ()),
 ('meow-dns', 'upstream_exchange_stays_with_captured_generation', 1, 'lib', ()),
 ('meow-dns', 'retiring_generation_cancels_and_joins_pending_exchange', 1, 'lib', ()),
 ('meow-dns',
  'resolver::tests::ech_cycle_guard_survives_dns_owned_spawn',
  1,
  'lib',
  ('encrypted',)),
 ('meow-dns',
  'resolver::owned_generation_tests::ech_cycle_guard_survives_run_owned_query_spawn',
  1,
  'lib',
  ('encrypted',)))

CORE_POLICY_TEST_CASES_OBSERVABILITY = CORE_POLICY_TEST_CASES_V2 + tuple(
    (p,'observations_',n,'lib',required) for p,n,required in
    [('meow-common',5,('aerove-android-runtime',)),('meow-dns',6,('aerove-android-runtime','encrypted')),
     ('meow-tunnel',6,()),('meow-api',1,())])
CORE_OBSERVABILITY_COMPILE_COMMAND = ['cargo','check','--manifest-path','.upstream/meow-rs/Cargo.toml',
    '--locked','--package','meow-api','--package','meow-listener','--all-features']
CORE_OBSERVABILITY_PACKAGES = tuple(sorted({p for p,_,_,_,_ in CORE_POLICY_TEST_CASES_OBSERVABILITY}|{'meow-listener'}))

CORE_POLICY_TEST_CASES_PROVIDER_GENERAL = tuple((r[:2]+(12,)+r[3:]+(False,)) if i==0 else r+(False,)
    for i,r in enumerate(CORE_POLICY_TEST_CASES_OBSERVABILITY))+(('meow-config', 'proxy_provider::tests::companion_', 2, 'lib', (), False), ('meow-config', 'proxy_provider::tests::provider_strict_refresh_keeps_last_good_on_bad_dialer', 1, 'lib', (), True), ('meow-common', 'diagnostic_', 2, 'lib', (), False), ('meow-config', 'diagnostic_', 1, 'lib', (), False), ('meow-proxy', 'diagnostic_', 10, 'lib', (), False), ('meow-transport', 'diagnostic_', 3, 'lib', (), False), ('meow-config', 'managed_dependency_', 3, 'lib', (), False), ('meow-config', 'proxy_provider::tests::projected_', 5, 'lib', (), False))
CORE_PROVIDER_GENERAL_PACKAGES = tuple(sorted(set(CORE_OBSERVABILITY_PACKAGES)|{'meow-config'}))

CORE_POLICY_TEST_PACKAGES = tuple(sorted({package for package, _, _ in CORE_POLICY_TEST_CASES}))


def native_identity(root=MEOW_ROOT):
    root = Path(root)
    spec = json.loads((root / "source.json").read_text())
    policy = json.loads((root / "build-policy.json").read_text())
    if "featureBoundary" in policy and policy["featureBoundary"] != "capabilities-no-default":
        raise ValueError("Unknown Meow feature boundary check")
    if (spec.get("commit") != MEOW_SOURCE or spec.get("tag") != "v0.26.1"
            or not re.fullmatch(r"[0-9a-f]{64}", str(spec.get("archiveSha256", "")))
            or policy.get("resolveLock") is not False):
        raise ValueError("Meow requires a pinned source and accepted committed lock policy")
    if "coreTests" in policy:
        approved_targets = {"policy-runtime-v1": HOST_POLICY_RUNTIME_TARGETS,
            "policy-runtime-v2": HOST_POLICY_RUNTIME_V2_TARGETS,
            "policy-runtime-traffic-v1": HOST_POLICY_RUNTIME_TRAFFIC_TARGETS,
            "policy-runtime-observability-v1": HOST_POLICY_RUNTIME_OBSERVABILITY_TARGETS,
            "policy-runtime-provider-general-v1": HOST_POLICY_RUNTIME_PROVIDER_GENERAL_TARGETS,
            "policy-runtime-resource-health-v1": HOST_POLICY_RUNTIME_RESOURCE_HEALTH_TARGETS}.get(policy["coreTests"])
        if (not approved_targets
                or tuple(policy.get("targets", ())) != approved_targets
                or policy.get("featureBoundary") != "capabilities-no-default"
                or not re.fullmatch(r"[0-9a-f]{64}", str(policy.get("coreTestLockSha256", "")))):
            raise ValueError("Unrecognized Meow Core unit test policy")
    elif "coreTestLockSha256" in policy or tuple(policy.get("targets", ())) in (HOST_POLICY_RUNTIME_TARGETS, HOST_POLICY_RUNTIME_V2_TARGETS, HOST_POLICY_RUNTIME_TRAFFIC_TARGETS, HOST_POLICY_RUNTIME_OBSERVABILITY_TARGETS, HOST_POLICY_RUNTIME_PROVIDER_GENERAL_TARGETS, HOST_POLICY_RUNTIME_RESOURCE_HEALTH_TARGETS):
        raise ValueError("Meow policy lacks approved Core unit test admission")
    def owned(name):
        path = root / name
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError("Meow identity path escapes source root")
        return path
    overlay = json.loads(owned(policy["overlay"]).read_text())
    if (sha256(root / "Cargo.toml") != policy["manifestSha256"]
            or sha256(owned(policy["overlay"])) != policy["overlaySha256"]
            or sha256(owned(overlay["patch"])) != overlay["patchSha256"]):
        raise ValueError("Meow policy/overlay/manifest identity changed")
    if "coreTests" in policy and sha256(owned("core-tests.lock")) != policy["coreTestLockSha256"]:
        raise ValueError("Meow Core unit test lock identity changed")
    return dict(meow_source=MEOW_SOURCE, meow_version="v0.26.1", core_library=CORE_LIBRARY, meow_source_spec_sha256=sha256(root / "source.json"),
        meow_lock_sha256=sha256(root / "Cargo.lock"), meow_manifest_sha256=sha256(root / "Cargo.toml"),
        meow_policy_sha256=sha256(root / "build-policy.json"), meow_overlay_sha256=policy["overlaySha256"],
        meow_patch_sha256=overlay["patchSha256"], meow_archive_sha256=spec["archiveSha256"],
        meow_file_manifest_sha256=overlay["adaptedFileManifestSha256"])


def validate_native_receipt(receipt_path, abi=None, image=None, root=MEOW_ROOT, workflow=None):
    expected = native_identity(root)
    receipt = receipt_path if isinstance(receipt_path, dict) else json.loads(Path(receipt_path).read_text())
    source = receipt.get("source", {})
    if (receipt.get("schema") != 1 or receipt.get("commandExit") != 0
            or receipt.get("acceptedLock") is not True
            or receipt.get("bootstrapRequested") is not False or receipt.get("resolveLockRequested") is not False
            or receipt.get("lockUnchangedDuringLockedCommand") is not True
            or not str(receipt.get("toolchain", "")).startswith("rustc 1.98.1 ")
            or source.get("commit") != MEOW_SOURCE or source.get("tag") != "v0.26.1"
            or source.get("archiveHashBootstrapped") is not False):
        raise ValueError("Meow receipt is not an accepted locked build")
    fields = {"declaredSourceSpecSha256": "meow_source_spec_sha256", "bridgeManifestSha256": "meow_manifest_sha256",
        "buildPolicySha256": "meow_policy_sha256", "lockSha256Before": "meow_lock_sha256",
        "resolvedLockSha256": "meow_lock_sha256", "lockSha256After": "meow_lock_sha256"}
    if any(receipt.get(key) != expected[value] for key, value in fields.items()) or any(
        source.get(key) != expected[value] for key, value in {"archiveSha256": "meow_archive_sha256",
        "fileManifestSha256": "meow_file_manifest_sha256", "overlaySha256": "meow_overlay_sha256",
        "patchSha256": "meow_patch_sha256"}.items()):
        raise ValueError("Meow receipt source/lock/overlay identity mismatch")
    if workflow and any(str(receipt.get(key)) != str(value) for key, value in workflow.items()):
        raise ValueError("Meow receipt source/run/attempt mismatch")
    if abi is None:
        policy = json.loads((Path(root)/"build-policy.json").read_text())
        targets = policy["targets"]
        if "node_delay" in targets and tuple(targets) not in (HOST_NO_FAIL_FAST_TARGETS, HOST_POLICY_RUNTIME_TARGETS, HOST_POLICY_RUNTIME_V2_TARGETS, HOST_POLICY_RUNTIME_TRAFFIC_TARGETS, HOST_POLICY_RUNTIME_OBSERVABILITY_TARGETS, HOST_POLICY_RUNTIME_PROVIDER_GENERAL_TARGETS, HOST_POLICY_RUNTIME_RESOURCE_HEALTH_TARGETS):
            raise ValueError("Unrecognized Meow no-fail-fast target contract")
        command = ["cargo", "test", "--locked"]
        if tuple(targets) in (HOST_NO_FAIL_FAST_TARGETS, HOST_POLICY_RUNTIME_TARGETS, HOST_POLICY_RUNTIME_V2_TARGETS, HOST_POLICY_RUNTIME_TRAFFIC_TARGETS, HOST_POLICY_RUNTIME_OBSERVABILITY_TARGETS, HOST_POLICY_RUNTIME_PROVIDER_GENERAL_TARGETS, HOST_POLICY_RUNTIME_RESOURCE_HEALTH_TARGETS):
            command.append("--no-fail-fast")
        for target in targets:
            command += ["--lib"] if target == "lib" else ["--test", target]
        command += ["--", "--test-threads=1", "--nocapture"]
        if receipt.get("command") != command or receipt.get("androidTarget") is not None or receipt.get("representativeTargets") != targets:
            raise ValueError("Meow host receipt did not run the complete approved suite")
        if policy.get("coreTests") == "policy-runtime-resource-health-v1" and receipt.get("addedNativeTests") != RESOURCE_HEALTH_CASES:
            raise ValueError("Meow host receipt lacks authored resource/health Native test proof")
        if "featureBoundary" in policy:
            boundary_command = ["cargo", "test", "--locked", "--no-default-features", "--lib",
                "capabilities::tests::", "--", "--test-threads=1", "--nocapture"]
            if (receipt.get("featureBoundaryCommand") != boundary_command
                    or type(receipt.get("featureBoundaryExit")) is not int
                    or receipt["featureBoundaryExit"] != 0):
                raise ValueError("Meow host receipt lacks successful approved feature boundary proof")
        if "coreTests" in policy:
            validate_core_test_receipt(receipt.get("coreTests"), policy, Path(root))
    else:
        target = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"}[abi]
        output = receipt.get("output", {})
        if (receipt.get("command") != ["cargo", "build", "--locked", "--release", "--target", target]
                or receipt.get("androidTarget") != target or output.get("abi") != abi
                or output.get("sha256") != hashlib.sha256(image).hexdigest() or output.get("bytes") != len(image)
                or output.get("minPageAlignment") != 16384
                or not re.search(r"Pkg.Revision\s*=\s*28\.2\.13676358\s*$", receipt.get("ndkSourceProperties", ""), re.MULTILINE)):
            raise ValueError("Meow receipt output/NDK/ABI mismatch")
    return expected


def validate_core_test_receipt(state, policy, root):
    """Require the producer's fixed dependency tests and separately accepted locked graph."""
    def reject():
        raise ValueError("Meow host receipt lacks complete approved Core unit proof")
    def is_hash(value):
        return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None
    def exited(value):
        return type(value) is int and value == 0
    if not isinstance(state, dict):
        reject()
    overlay = json.loads((root / policy["overlay"]).read_text())
    upstream_lock = overlay.get("files", {}).get("Cargo.lock")
    mode = policy["coreTests"]
    if mode not in ("policy-runtime-v1", "policy-runtime-v2", "policy-runtime-traffic-v1", "policy-runtime-observability-v1", "policy-runtime-provider-general-v1", "policy-runtime-resource-health-v1"):
        reject()
    cases = (tuple((package, name, count, "lib", ()) for package, name, count in CORE_POLICY_TEST_CASES)
        if mode == "policy-runtime-v1" else CORE_POLICY_TEST_CASES_OBSERVABILITY
        if mode == "policy-runtime-observability-v1" else CORE_POLICY_TEST_CASES_V2)
    if mode in ("policy-runtime-provider-general-v1", "policy-runtime-resource-health-v1"):cases = CORE_POLICY_TEST_CASES_PROVIDER_GENERAL
    cases = tuple(r if len(r)==6 else r+(False,) for r in cases)
    packages = CORE_PROVIDER_GENERAL_PACKAGES if mode in ("policy-runtime-provider-general-v1", "policy-runtime-resource-health-v1") else CORE_OBSERVABILITY_PACKAGES if mode == "policy-runtime-observability-v1" else CORE_POLICY_TEST_PACKAGES
    if (state.get("mode") != mode or state.get("accepted") is not True
            or state.get("upstreamLockRestored") is not True or not is_hash(upstream_lock)
            or state.get("upstreamLockBeforeSha256") != upstream_lock
            or state.get("seedSha256") != policy["coreTestLockSha256"]
            or state.get("resolvedLockSha256") != policy["coreTestLockSha256"]
            or state.get("bridgeMetadataCommand") != ["cargo", "metadata", "--locked", "--format-version", "1"]
            or not exited(state.get("bridgeMetadataExit"))
            or state.get("metadataCommand") != ["cargo", "metadata", "--manifest-path",
                ".upstream/meow-rs/Cargo.toml", "--format-version", "1", "--locked"]
            or not exited(state.get("metadataExit"))):
        reject()
    members, features, commands = state.get("workspaceMembers"), state.get("features"), state.get("commands")
    if (not isinstance(members, list) or any(not isinstance(m, str) for m in members)
            or members != sorted(set(members)) or not set(packages) <= set(members)
            or not isinstance(features, dict) or set(features) != set(packages)
            or not isinstance(commands, list) or len(commands) != len(cases)):
        reject()
    for package in packages:
        values = features[package]
        if (not isinstance(values, list) or any(not isinstance(f, str)
                or re.fullmatch(r"[A-Za-z0-9_-]+", f) is None for f in values)
                or values != sorted(set(values))):
            reject()
    for entry, (package, name, count, target, required_features, exact) in zip(commands, cases):
        if not set(required_features) <= set(features[package]):
            reject()
        command = ["cargo", "test", "--manifest-path", ".upstream/meow-rs/Cargo.toml", "--locked",
            "--package", package]
        command += ["--lib"] if target == "lib" else ["--test", "transport_scope_compat"]
        command += ["--no-default-features"]
        if features[package]:
            command += ["--features", ",".join(package + "/" + f for f in features[package])]
        if name is not None:
            command += [name]
        command += ["--", "--test-threads=1", "--nocapture"]
        if exact:command += ["--exact"]
        if mode in ("policy-runtime-provider-general-v1", "policy-runtime-resource-health-v1") and (not isinstance(entry, dict) or type(entry.get("exact")) is not bool or entry["exact"] != exact):
            reject()
        if (not isinstance(entry, dict) or entry.get("package") != package or entry.get("filter") != name
                or (mode in ("policy-runtime-v2", "policy-runtime-traffic-v1", "policy-runtime-observability-v1", "policy-runtime-provider-general-v1", "policy-runtime-resource-health-v1") and (entry.get("target") != target or "filter" not in entry))
                or entry.get("command") != command or not exited(entry.get("exit"))
                or type(entry.get("expectedPass")) is not int or entry["expectedPass"] != count
                or type(entry.get("passed")) is not int or entry["passed"] != count
                or not is_hash(entry.get("stdoutSha256")) or not is_hash(entry.get("stderrSha256"))):
            reject()

    if mode in ("policy-runtime-observability-v1", "policy-runtime-provider-general-v1", "policy-runtime-resource-health-v1"):
        compile_check = state.get("compileCheck")
        if (not isinstance(compile_check, dict)
                or compile_check.get("command") != CORE_OBSERVABILITY_COMPILE_COMMAND
                or not exited(compile_check.get("exit"))
                or not is_hash(compile_check.get("stdoutSha256"))
                or not is_hash(compile_check.get("stderrSha256"))):
            reject()


def exported_functions(image):
    """Read defined dynamic ELF functions; strings or undefined imports do not qualify."""
    try:
        offset = struct.unpack_from("<Q", image, 40)[0]
        size, count = struct.unpack_from("<HH", image, 58)
        if size < 64 or count < 1 or offset + size * count > len(image):
            raise ValueError("Invalid ELF section table")
        sections = [struct.unpack_from("<IIQQQQIIQQ", image, offset + i * size) for i in range(count)]
        result = set()
        for section in sections:
            if section[1] != 11: continue  # SHT_DYNSYM
            start, length, link, entry = section[4], section[5], section[6], section[9]
            if entry < 24 or length % entry or start + length > len(image) or link >= count:
                raise ValueError("Invalid ELF dynamic symbols")
            strings = sections[link]
            table = image[strings[4]:strings[4]+strings[5]]
            for index in range(0, length, entry):
                name, info, other, defined, _value, _size = struct.unpack_from("<IBBHQQ", image, start + index)
                if info & 15 == 2 and info >> 4 in (1, 2) and other & 3 in (0, 3) and defined:
                    if name >= len(table) or b"\0" not in table[name:]: raise ValueError("Invalid ELF symbol name")
                    result.add(table[name:table.index(b"\0",name)].decode("ascii"))
        return result
    except (struct.error, UnicodeDecodeError, IndexError) as error:
        raise ValueError("Invalid ELF dynamic symbols") from error


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def artifact_name(abi, channel):
    if abi == "x86_64":
        return "AEROVE-Lite-test-x86_64.apk"
    return "AEROVE-Lite-dev-arm64.apk" if channel == "development" else "AEROVE-Lite-android-arm64.apk"


def product_version(path):
    product = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        key, separator, value = line.partition("=")
        if not separator or key in product:
            raise ValueError("Invalid or duplicate Lite version property")
        product[key] = value
    if set(product) != {"applicationId", "version", "versionCode", "channel", "signerSha256"}:
        raise ValueError("Incomplete Lite version identity")
    if product["applicationId"] != "dev.aerove.lite" or product["channel"] != "preview":
        raise ValueError("Unexpected Lite release channel or application identity")
    if not re.fullmatch(
        r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
        r"-(?:alpha|beta|rc)\.[1-9][0-9]*", product["version"],
    ):
        raise ValueError("Invalid Lite preview version")
    if not re.fullmatch(r"[1-9][0-9]*", product["versionCode"]) or int(product["versionCode"]) > 2_100_000_000:
        raise ValueError("Invalid Android version code")
    if not re.fullmatch(r"[0-9a-f]{64}", product["signerSha256"]):
        raise ValueError("Invalid Lite signing identity")
    return product


def verify_identity(badging, signature, product, channel):
    identity = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.MULTILINE)
    expected_id = product["applicationId"] + (".dev" if channel == "development" else "")
    expected_version = product["version"] + ("+debug" if channel == "development" else "")
    if identity is None or tuple(identity.groups()) != (expected_id, product["versionCode"], expected_version):
        raise ValueError("Unexpected package identity or version")
    if not re.search(r"^sdkVersion:'33'$", badging, re.MULTILINE):
        raise ValueError("Unexpected minimum SDK")
    lines = signature.splitlines()
    legacy_signers = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]{64})$", signature, re.MULTILINE)
    modern_signers = re.findall(r"^V2 Signer: certificate SHA-256 digest: ([0-9a-f]{64})$", signature, re.MULTILINE)
    signer_digest_lines = [line for line in lines if "Signer" in line and "certificate SHA-256 digest:" in line]
    modern_markers = any(
        line == "Verifies" or line.startswith("Verified using ") or line.startswith("Number of signers:")
        or line.startswith("V2 Signer:")
        for line in lines
    )
    if modern_markers:
        verifies = [line for line in lines if line == "Verifies"]
        v2_status = [line for line in lines if line.startswith("Verified using v2 scheme ")]
        signer_counts = [line for line in lines if line.startswith("Number of signers:")]
        if (len(verifies) != 1 or len(v2_status) != 1
                or v2_status[0] != "Verified using v2 scheme (APK Signature Scheme v2): true"
                or len(signer_counts) != 1 or signer_counts[0] != "Number of signers: 1"):
            raise ValueError("Unexpected or ambiguous verified signing certificate")
    signer = legacy_signers + modern_signers
    if len(signer_digest_lines) != len(signer) or len(signer) != 1:
        raise ValueError("Unexpected or ambiguous verified signing certificate")
    if len(signer) != 1 or (channel != "development" and signer[0] != product["signerSha256"]):
        raise ValueError("Unexpected or ambiguous verified signing certificate")
    return {"application_id": expected_id, "version": expected_version,
        "version_code": int(product["versionCode"]), "min_sdk": 33, "signer_sha256": signer[0]}


def verify(apk, abi, helper_source_root, helper_receipt, native_receipt, workflow=None):
    machine = {"arm64-v8a": 183, "x86_64": 62}[abi]
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None:
            raise ValueError("APK ZIP CRC failure")
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate APK entries")
        library = f"lib/{abi}/{CORE_LIBRARY}"
        if library not in names:
            raise ValueError("The actual Rust core is missing")
        core = archive.read(library)
        if f"lib/{abi}/libaerove_core.so" in names:
            raise ValueError("Legacy clash-rs library must not be packaged with Meow")
        if not valid_elf64_shared_object(core, machine):
            raise ValueError("Wrong Meow ELF architecture or 16KiB alignment")
        missing = set(CORE_JNI_SYMBOLS) - exported_functions(core)
        if missing:
            raise ValueError("Meow JNI exports missing: " + ", ".join(sorted(missing)))
        core_identity = validate_native_receipt(native_receipt, abi, core, workflow=workflow)
        helpers = [f"lib/{abi}/{name}" for name in HELPER_LIBRARIES]
        missing_helpers = [name for name in helpers if name not in names]
        if missing_helpers:
            raise ValueError("Required client helper library is missing: " + ", ".join(missing_helpers))
        helper_provenance = validate_helper_provenance(helper_source_root, helper_receipt, abi)
        libraries = [name for name in names if name.startswith("lib/") and name.endswith(".so")]
        helper_readback = {}
        for name in libraries:
            if name.split("/")[1] != abi:
                raise ValueError(f"Unexpected ABI: {name}")
            image = archive.read(name)
            if name in helpers:
                valid = valid_elf64_shared_object(image, machine)
            else:
                header = image[:20]
                valid = (len(header) >= 20 and header[:6] == b"\x7fELF\x02\x01"
                    and struct.unpack_from("<H", header, 18)[0] == machine)
            if not valid:
                raise ValueError(f"Wrong ELF architecture: {name}")
            if name in helpers:
                helper_readback[name.rsplit("/", 1)[-1]] = {
                    "sha256": hashlib.sha256(image).hexdigest(),
                    "size_bytes": len(image),
                }
        licenses = sorted(name for name in names if name.startswith(HELPER_LICENSE_PREFIX) and not name.endswith("/"))
        if not licenses:
            raise ValueError("Client helper license assets are missing")
        dependency_manifests = [name for name in licenses if name == HELPER_LICENSE_PREFIX + "dependencies.json"]
        if len(dependency_manifests) != 1:
            raise ValueError("Client helper dependency manifest is missing or ambiguous")
        validate_helper_license_manifest(archive, archive.read(dependency_manifests[0]), licenses, helper_source_root)
        for helper_name, details in helper_readback.items():
            receipt = helper_provenance["outputs"][helper_name]
            if details["sha256"] != receipt["sha256"] or details["size_bytes"] != receipt["bytes"]:
                raise ValueError(f"Client helper build receipt does not match APK: {helper_name}")
        if "AndroidManifest.xml" not in names or not any(name.endswith(".dex") for name in names):
            raise ValueError("Not a complete Android application")
        return {**core_identity, "abi": abi, "jni_libraries": libraries, "core_sha256": hashlib.sha256(archive.read(library)).hexdigest(),
            "client_helpers": helper_readback, "client_helper_license_assets": licenses,
            "client_helper_source_sha256": helper_provenance["source_sha256"]}


def build_info(identity, result, source, tree, workflow, run_number, run_attempt, apk_path, channel="preview"):
    if type(run_number) is not int or run_number < 1 or type(run_attempt) is not int or run_attempt < 1:
        raise ValueError("Positive workflow run number and attempt are required")
    return {
        "product": "AEROVE Lite", "channel": channel, **identity,
        "source": source, "tree": tree, "workflow": workflow,
        "run_number": run_number, "run_attempt": run_attempt,
        "apk_sha256": sha256(apk_path), **result,
    }


def valid_elf64_shared_object(image, machine):
    """Require a complete ELF64 DSO with 16 KiB-compatible PT_LOAD segments."""
    if len(image) < 64 or image[:7] != b"\x7fELF\x02\x01\x01":
        return False
    try:
        e_type, e_machine, e_version = struct.unpack_from("<HHI", image, 16)
        e_phoff = struct.unpack_from("<Q", image, 32)[0]
        e_ehsize, e_phentsize, e_phnum = struct.unpack_from("<HHH", image, 52)
    except struct.error:
        return False
    if (e_type != 3 or e_machine != machine or e_version != 1 or e_ehsize != 64
            or e_phentsize < 56 or e_phnum < 1 or e_phoff < 64
            or e_phoff + e_phentsize * e_phnum > len(image)):
        return False
    loads = 0
    for index in range(e_phnum):
        offset = e_phoff + index * e_phentsize
        p_type, _p_flags = struct.unpack_from("<II", image, offset)
        if p_type != 1:  # PT_LOAD
            continue
        p_offset, p_vaddr, _p_paddr, p_filesz, p_memsz, p_align = struct.unpack_from("<QQQQQQ", image, offset + 8)
        if (p_filesz > p_memsz or p_offset + p_filesz > len(image) or p_align < ANDROID_MAX_PAGE_SIZE
                or p_align & (p_align - 1) or p_offset % ANDROID_MAX_PAGE_SIZE != p_vaddr % ANDROID_MAX_PAGE_SIZE
                or p_offset % p_align != p_vaddr % p_align):
            return False
        loads += 1
    return loads > 0


def validate_helper_license_manifest(archive, raw, license_assets, helper_source_root):
    helper_source_root = Path(helper_source_root)
    try:
        expected_manifest = (helper_source_root / "dependencies.json").read_bytes()
        manifest = json.loads(raw.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("Invalid client helper dependency manifest") from error
    if raw != expected_manifest:
        raise ValueError("Client helper dependency manifest differs from pinned source")
    dependencies = manifest if isinstance(manifest, list) else None
    if not isinstance(dependencies, list) or not dependencies:
        raise ValueError("Client helper dependency manifest is incomplete")
    expected = set()
    for dependency in dependencies:
        if not isinstance(dependency, dict) or not isinstance(dependency.get("Path"), str):
            raise ValueError("Invalid client helper dependency entry")
        dependency_licenses = dependency.get("licenses")
        if not isinstance(dependency_licenses, list) or not dependency_licenses:
            raise ValueError("Client helper dependency license list is incomplete")
        for relative in dependency_licenses:
            if not isinstance(relative, str) or not relative.startswith("licenses/"):
                raise ValueError("Client helper dependency license path is invalid")
            source_path = (helper_source_root / relative).resolve()
            if not source_path.is_relative_to(helper_source_root.resolve()) or not source_path.is_file() or source_path.stat().st_size == 0:
                raise ValueError("Client helper dependency license is missing or empty")
            expected.add(HELPER_LICENSE_PREFIX + relative.removeprefix("licenses/"))
    actual = set(license_assets) - {HELPER_LICENSE_PREFIX + "dependencies.json"}
    source_licenses = {p.relative_to(helper_source_root / "licenses").as_posix() for p in (helper_source_root / "licenses").rglob("*") if p.is_file()}
    actual_relative = {name.removeprefix(HELPER_LICENSE_PREFIX) for name in actual}
    if actual_relative != source_licenses or not expected.issubset(actual):
        raise ValueError("Client helper license assets are incomplete")
    for asset in actual:
        mapped = asset.removeprefix(HELPER_LICENSE_PREFIX)
        if archive.read(asset) != (helper_source_root / "licenses" / mapped).read_bytes():
            raise ValueError(f"Client helper license asset differs from pinned source: {asset}")


def validate_helper_provenance(source_root, receipt_path, abi):
    source_root = Path(source_root).resolve()
    if not source_root.is_dir():
        raise ValueError("Client helper source root is missing")
    try:
        receipt = json.loads(Path(receipt_path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("Invalid client helper build receipt") from error
    input_files = receipt.get("inputFiles")
    if not isinstance(input_files, dict) or not input_files:
        raise ValueError("Client helper build receipt has no source inputs")
    actual_inputs = {}
    for path in source_root.rglob("*"):
        if not path.is_file() or "__pycache__" in path.parts or path.suffix.lower() == ".pyc":
            continue
        relative = path.relative_to(source_root).as_posix()
        actual_inputs[relative] = sha256(path)
    if input_files != actual_inputs:
        raise ValueError("Client helper source input hashes do not match the build receipt")
    source_sha256 = hashlib.sha256(json.dumps(input_files, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    if receipt.get("sourceSha256") != source_sha256:
        raise ValueError("Client helper source aggregate hash is invalid")
    outputs = receipt.get("outputs")
    if not isinstance(outputs, list):
        raise ValueError("Client helper build receipt has no outputs")
    matching = {}
    for output in outputs:
        if not isinstance(output, dict) or output.get("abi") != abi:
            continue
        filename = str(output.get("path", "")).replace("\\", "/").rsplit("/", 1)[-1]
        if filename not in HELPER_LIBRARIES or filename in matching:
            raise ValueError("Client helper build receipt output is ambiguous")
        if not re.fullmatch(r"[0-9a-f]{64}", str(output.get("sha256", ""))) or type(output.get("bytes")) is not int or output["bytes"] <= 0:
            raise ValueError("Client helper build receipt output is invalid")
        matching[filename] = {"sha256": output["sha256"], "bytes": output["bytes"]}
    if set(matching) != set(HELPER_LIBRARIES):
        raise ValueError("Client helper build receipt is missing selected ABI outputs")
    return {"source_sha256": source_sha256, "outputs": matching}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--abi", required=True, choices=("arm64-v8a", "x86_64"))
    parser.add_argument("--channel", choices=("development", "preview"), default="development")
    parser.add_argument("--version-file", type=Path, default=Path(__file__).resolve().parents[1] / "version.properties")
    parser.add_argument("--source", required=True)
    parser.add_argument("--tree", required=True)
    parser.add_argument("--workflow", required=True)
    parser.add_argument("--run-number", required=True, type=int)
    parser.add_argument("--run-attempt", required=True, type=int)
    parser.add_argument("--badging", required=True, type=Path)
    parser.add_argument("--signature", required=True, type=Path)
    parser.add_argument("--helper-source-root", required=True, type=Path)
    parser.add_argument("--helper-receipt", required=True, type=Path)
    parser.add_argument("--native-receipt", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not all(re.fullmatch(r"[0-9a-f]{40}", value) for value in (args.source, args.tree)):
        parser.error("Full source and tree SHA values are required")
    if not re.fullmatch(r"https://github\.com/IvanSolis1989/Aerove/actions/runs/[0-9]+", args.workflow):
        parser.error("The actual repository workflow URL is required")
    if args.run_number < 1 or args.run_attempt < 1:
        parser.error("Positive workflow run number and attempt are required")
    try:
        result = verify(args.apk, args.abi, args.helper_source_root, args.helper_receipt, args.native_receipt,
            dict(workflowSourceSha=args.source, workflowRun=args.workflow.rsplit("/", 1)[-1], workflowAttempt=str(args.run_attempt)))
    except ValueError as error:
        parser.error(str(error))
    badging = args.badging.read_text(encoding="utf-8")
    try:
        identity = verify_identity(badging, args.signature.read_text(encoding="utf-8"), product_version(args.version_file), args.channel)
    except ValueError as error:
        parser.error(str(error))
    args.output.mkdir(parents=True, exist_ok=True)
    apk = args.output / artifact_name(args.abi, args.channel)
    shutil.copyfile(args.apk, apk)
    info = args.output / "BUILD_INFO.json"
    result["native_build_receipt"] = json.loads(args.native_receipt.read_text())
    info.write_text(json.dumps(build_info(identity, result, args.source, args.tree, args.workflow,
        args.run_number, args.run_attempt, apk, args.channel), indent=2) + "\n", encoding="utf-8")
    (args.output / "SHA256SUMS").write_text("".join(f"{sha256(path)}  {path.name}\n" for path in (apk, info)), encoding="utf-8")
    print(json.dumps({"apk": str(apk), **result}))


if __name__ == "__main__":
    main()
