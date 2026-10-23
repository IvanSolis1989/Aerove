#[test]
fn wireguard_production_handler_handshakes_and_echoes_plaintext_via_a_protected_loopback_peer() {
    clash_lib::mobile::outbound::regression::run("wireguard-peer");
}
