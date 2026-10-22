#[test]
fn foreground_observations_come_from_actual_proxy_and_dns_io() {
    clash_lib::mobile::outbound::regression::run("observations-io");
}
#[test]
fn observation_lease_is_bounded_and_invalidates_late_results() {
    clash_lib::mobile::outbound::regression::run("observations-lease");
}
