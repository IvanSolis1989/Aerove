#[test]
fn outbound_http_fixture_checks_methods_and_targets_and_sends_no_head_body() {
    clash_lib::mobile::outbound::regression::run("http-fixture");
}

#[test]
fn outbound_uses_the_applied_direct_rule_and_global_selection() {
    clash_lib::mobile::outbound::regression::run("routing");
}

#[test]
fn outbound_requires_bearer_and_rejects_arbitrary_source_parameters() {
    clash_lib::mobile::outbound::regression::run("auth");
}

#[test]
fn outbound_is_bounded_and_cancels_on_stop_or_selection_change() {
    clash_lib::mobile::outbound::regression::run("bounds");
}

#[test]
fn outbound_loader_rejects_redirects_oversize_and_bad_public_identity() {
    clash_lib::mobile::outbound::regression::run("loader");
}

#[test]
fn outbound_never_connects_when_socket_protection_is_refused() {
    clash_lib::mobile::outbound::regression::run("protect");
}

#[test]
fn outbound_returns_the_winning_dispatcher_connections_actual_nested_chain() {
    clash_lib::mobile::outbound::regression::run("chains");
}

#[test]
fn outbound_freshness_ignores_unrelated_picks_but_checks_related_pending_changes() {
    clash_lib::mobile::outbound::regression::run("freshness");
}

#[test]
fn outbound_detects_actual_automatic_fallback_and_urltest_reselection() {
    clash_lib::mobile::outbound::regression::run("automatic");
}

#[test]
fn outbound_matches_fulls_nine_sources_six_seconds_and_maximum_three_in_flight() {
    clash_lib::mobile::outbound::regression::run("source-budget");
}

#[test]
fn outbound_http_disconnect_releases_its_slot_and_routed_socket() {
    clash_lib::mobile::outbound::regression::run("http-cancel");
}
