#[test]
fn provider_loaded_retains_multiple_keys_and_fences_stopped_sessions() { clash_lib::mobile::outbound::regression::run("provider-loaded-state"); }

#[test]
fn provider_loaded_real_manual_initial_same_changed_and_failure() { clash_lib::mobile::outbound::regression::run("provider-loaded-manual"); }

#[test]
fn provider_loaded_interval_installs_before_revision() { clash_lib::mobile::outbound::regression::run("provider-loaded-interval"); }

#[test]
fn provider_loaded_file_watch_reports_changed_and_same_success() { clash_lib::mobile::outbound::regression::run("provider-loaded-file"); }

#[test]
fn provider_loaded_get_rejects_busy_and_changed_snapshot() { clash_lib::mobile::outbound::regression::run("provider-loaded-snapshot"); }

#[test]
fn provider_loaded_websocket_replays_multiple_keys_then_deltas() { clash_lib::mobile::outbound::regression::run("provider-loaded-websocket"); }

#[test]
fn provider_loaded_single_and_list_get_share_real_nodes_and_manual_revision() { clash_lib::mobile::outbound::regression::run("provider-loaded-http"); }
