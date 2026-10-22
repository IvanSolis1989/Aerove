#[test]
fn planned_inbounds_preserve_private_controller_and_reject_unsupported_fields() {
    clash_lib::mobile::outbound::regression::run("network-validation");
}

#[test]
fn planned_http_socks_and_mixed_bind_authenticate_forward_and_join() {
    clash_lib::mobile::outbound::regression::run("network-listeners");
}

#[test]
fn mixed_and_socks_keep_real_udp_association_and_control_close() {
    clash_lib::mobile::outbound::regression::run("network-udp");
}

#[test]
fn failed_bind_never_completes_readiness_and_old_ready_cannot_reach_new_owner() {
    clash_lib::mobile::outbound::regression::run("network-failure");
}

#[test]
fn network_update_reuses_runtime_and_reads_back_real_ready_listeners() {
    clash_lib::mobile::outbound::regression::run("network-update");
}

#[test]
fn network_stop_joins_accepted_update_and_live_udp_control_and_reports_panics() {
    clash_lib::mobile::outbound::regression::run("network-cancellation");
}
