#[test]
fn ssh_real_loopback_peer_authenticates_echoes_and_obeys_protection_and_stop() {
    clash_lib::mobile::outbound::regression::run("ssh-peer");
}
