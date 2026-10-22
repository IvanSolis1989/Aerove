#[test]
fn unified_delay_reuses_one_connection_and_resets_to_second_head() {
    clash_lib::mobile::outbound::regression::run("probe-reuse");
}

#[test]
fn disabled_unified_delay_keeps_dial_and_first_head() {
    clash_lib::mobile::outbound::regression::run("probe-single");
}

#[test]
fn first_head_timeout_fails_and_closes_driver() {
    clash_lib::mobile::outbound::regression::run("probe-first-failure");
}

#[test]
fn second_head_failure_keeps_first_measurement() {
    clash_lib::mobile::outbound::regression::run("probe-second-failure");
}

#[test]
fn second_head_deadline_keeps_first_measurement() {
    clash_lib::mobile::outbound::regression::run("probe-second-timeout");
}

#[test]
fn cancelled_probe_drops_same_connection_and_driver() {
    clash_lib::mobile::outbound::regression::run("probe-cancel");
}

#[test]
fn concurrent_dial_races_every_candidate_and_drops_losers() {
    clash_lib::mobile::outbound::regression::run("dial-parallel");
}

#[test]
fn disabled_concurrent_dial_serializes_each_family() {
    clash_lib::mobile::outbound::regression::run("dial-serial");
}

#[test]
fn serial_dial_preserves_dual_stack_fallback() {
    clash_lib::mobile::outbound::regression::run("dial-families");
}

#[test]
fn all_dial_failures_keep_all_candidate_errors() {
    clash_lib::mobile::outbound::regression::run("dial-all-fail");
}

#[test]
fn cancelled_dial_drops_every_candidate() {
    clash_lib::mobile::outbound::regression::run("dial-cancel");
}

#[test]
fn dial_deadline_drops_every_candidate() {
    clash_lib::mobile::outbound::regression::run("dial-timeout");
}

#[test]
fn node_group_and_healthcheck_share_selected_history_and_hot_policy() {
    clash_lib::mobile::outbound::regression::run("probe-history");
}
