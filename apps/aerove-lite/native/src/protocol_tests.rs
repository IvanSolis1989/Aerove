#[test]
fn four_protocol_types_aliases_defaults_and_invalid_values_are_admitted_exactly(){clash_lib::mobile::outbound::regression::run("protocol-types");}
#[test]
fn shadowquic_actual_udp_is_protected_and_refusal_cancellation_fail_before_traffic(){clash_lib::mobile::outbound::regression::run("protocol-sockets");}
