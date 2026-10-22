#[test]
fn user_controller_uses_fixed_address_saved_secret_and_unicode_byte_auth() {
    clash_lib::mobile::user_controller::regression::run("validation");
}

#[test]
fn user_controller_shares_real_components_and_excludes_private_routes_and_reload() {
    clash_lib::mobile::user_controller::regression::run("shared-auth");
}

#[test]
fn user_controller_rotation_disable_and_stop_close_http_and_websocket_transports() {
    clash_lib::mobile::user_controller::regression::run("transport");
}

#[test]
fn user_controller_bind_failure_and_parent_cancel_preserve_private_api() {
    clash_lib::mobile::user_controller::regression::run("bind-cancel");
}
