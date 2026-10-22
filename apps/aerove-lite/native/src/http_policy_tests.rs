#[test]
fn core_http_ua_and_certificate_policy_affect_real_requests_without_weakening_node_tls() {
    clash_lib::mobile::outbound::regression::run("http-policy-io");
}
#[test]
fn core_http_policy_is_typed_and_rejects_header_injection_without_echoing_input() {
    clash_lib::mobile::outbound::regression::run("http-policy-typed");
}
