#[test]
fn shadowquic_production_handler_authenticates_local_peer_and_echoes_protected_streams() {
    clash_lib::mobile::outbound::regression::run("shadowquic-peer");
}
