#[test]
fn route_snapshot_matches_actual_picks_and_rejects_in_progress_mutations() {
    clash_lib::mobile::outbound::regression::run("route-snapshot");
}

#[test]
fn service_checks_cover_all_full_targets_with_real_stamps_routes_and_budget() {
    clash_lib::mobile::outbound::regression::run("service-targets");
}

#[test]
fn service_checks_report_all_ten_full_statuses_and_retry_only_timeout() {
    clash_lib::mobile::outbound::regression::run("service-statuses");
}

#[test]
fn service_checks_follow_direct_rule_global_and_explicit_proxy_routing() {
    clash_lib::mobile::outbound::regression::run("service-routing");
}

#[test]
fn service_checks_cancel_replace_stop_join_and_reject_obsolete_picks() {
    clash_lib::mobile::outbound::regression::run("service-cancel");
}

#[test]
fn service_checks_authenticate_and_bound_redirected_compressed_scans() {
    clash_lib::mobile::outbound::regression::run("service-http");
}

#[test]
fn service_checks_never_dial_after_vpn_socket_protection_is_refused() {
    clash_lib::mobile::outbound::regression::run("service-protect");
}

#[test]
fn replacing_one_service_preserves_other_checks_in_the_older_batch() {
    clash_lib::mobile::outbound::regression::run("service-partial");
}
