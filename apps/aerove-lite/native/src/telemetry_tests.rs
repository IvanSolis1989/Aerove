#[test]
fn telemetry_reuses_the_statistics_tick_without_connection_snapshots() {
    clash_lib::mobile::outbound::regression::run("telemetry-counters");
}

#[test]
fn telemetry_groups_follow_selection_health_and_real_delay_results() {
    clash_lib::mobile::outbound::regression::run("telemetry-groups");
}

#[test]
fn telemetry_reports_automatic_reselection_without_polling() {
    clash_lib::mobile::outbound::regression::run("telemetry-urltest");
}

#[test]
fn telemetry_delay_results_share_fulls_five_second_trailing_debounce() {
    clash_lib::mobile::outbound::regression::run("telemetry-delay-budget");
}

#[test]
fn telemetry_stop_cancels_and_joins_pending_delay_notification() {
    clash_lib::mobile::outbound::regression::run("telemetry-delay-stop");
}

#[test]
fn telemetry_provider_updates_require_changed_valid_content() {
    clash_lib::mobile::outbound::regression::run("telemetry-provider");
}

#[test]
fn telemetry_websocket_authenticates_and_cannot_cross_runtime_sessions() {
    clash_lib::mobile::outbound::regression::run("telemetry-websocket");
}
