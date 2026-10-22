#[test]
fn tun_mtu_config_and_constructors_reject_out_of_range_values() {
    clash_lib::mobile::mtu_tests::config_and_constructor_bounds();
}

#[tokio::test]
async fn actual_tcp_syn_ack_uses_1500_and_jumbo_9000_mss() {
    clash_lib::mobile::mtu_tests::legacy_constructor_keeps_1500_mss().await;
    clash_lib::mobile::mtu_tests::tcp_mss_uses_actual_mtu(1500).await;
    clash_lib::mobile::mtu_tests::tcp_mss_uses_actual_mtu(9000).await;
}

#[cfg(target_os = "linux")]
#[tokio::test]
async fn actual_framed_fd_round_trip_does_not_truncate_1500_9000_or_65535() {
    for mtu in [1500, 9000, 65535] {
        clash_lib::mobile::mtu_tests::framed_fd_preserves_entire_packet(mtu).await;
    }
}
