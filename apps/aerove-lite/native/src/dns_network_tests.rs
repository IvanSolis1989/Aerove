#[test]
fn dns_physical_snapshot_is_typed_and_never_falls_back_to_public_dns(){clash_lib::mobile::outbound::regression::run("dns-typed");}
#[test]
fn dns_udp_tcp_bind_query_park_and_resume_share_one_core(){clash_lib::mobile::outbound::regression::run("dns-runtime-io");}
#[test]
fn dns_partial_bind_failure_fails_and_cleans_existing_owner(){clash_lib::mobile::outbound::regression::run("dns-runtime-failure");}
#[test]
fn rule_dump_pages_all_domains_and_real_mrs_domain_cidr_counts(){clash_lib::mobile::outbound::regression::run("rules-dump");}
