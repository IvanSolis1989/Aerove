#[test]
fn process_identity_strict_once_and_original_endpoints() {
    clash_lib::mobile::process_identity_tests::strict_once_and_original_endpoints();
}

#[test]
fn process_identity_udp_reuse_generation_and_off() {
    clash_lib::mobile::process_identity_tests::udp_reuse_generation_and_off();
}

#[test]
fn process_identity_unknown_is_not_root_and_parse_is_strict() {
    clash_lib::mobile::process_identity_tests::unknown_is_not_root_and_parse_is_strict();
}

#[test]
fn process_identity_late_cancelled_receipt_and_owner_release() {
    clash_lib::mobile::process_identity_tests::late_cancelled_receipt_is_discarded_and_owner_is_not_retained();
}

#[test]
fn process_identity_saturated_udp_cache_has_no_per_packet_java() {
    clash_lib::mobile::process_identity_tests::saturated_udp_cache_does_not_call_java_per_packet();
}
