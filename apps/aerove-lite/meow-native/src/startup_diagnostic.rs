//! Startup-only safe projection. Never serialize the source error or its text.
use parking_lot::Mutex;
use serde::Serialize;
use std::{io, sync::Arc};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum Stage { OwnerCreate, PlanDecode, HostPolicy, PhysicalDns, ManagedConfig, ConfigLoad, DnsSetup, ControlSetup, GraphActivation, UserActivation, ReadyWait, Running }
impl Stage {
    fn fallback(self) -> &'static str {
        match self {
            Self::OwnerCreate => "ownerCreateFailed",
            Self::PlanDecode => "planDecodeFailed",
            Self::HostPolicy => "hostPolicyFailed",
            Self::PhysicalDns => "physicalDnsFailed",
            Self::ManagedConfig => "managedConfigFailed",
            Self::ConfigLoad => "configLoadFailed",
            Self::DnsSetup => "dnsSetupFailed",
            Self::ControlSetup => "controlSetupFailed",
            Self::GraphActivation => "graphActivationFailed",
            Self::UserActivation => "userActivationFailed",
            Self::ReadyWait => "readyWaitFailed",
            Self::Running => "unclassified",
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum CauseType { SerdeYaml, Io, Join, DnsBootstrapResolve, Unknown }
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Primary { pub stage: Stage, pub code: &'static str, pub io_kind: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cause_type: Option<CauseType> }
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Diagnostic {
    pub stage: Stage, pub completed: bool, pub primary: Option<Primary>, pub cleanup: &'static str,
}
pub(crate) type Shared = Arc<Mutex<Diagnostic>>;
impl Default for Diagnostic {
    fn default() -> Self { Self { stage: Stage::OwnerCreate, completed: false, primary: None, cleanup: "pending" } }
}
impl Diagnostic {
    pub fn shared() -> Shared { Arc::new(Mutex::new(Self::default())) }
    pub fn advance(&mut self, stage: Stage) { if !self.completed { self.stage = stage; } }
    pub fn running(&mut self) { self.stage = Stage::Running; self.completed = true; }
    pub fn record_io(&mut self, cause: &io::Error) {
        if self.completed || self.primary.is_some() { return; }
        let code = crate::runtime::failure_detail(cause)
            .map(|e| allowed_code(e.code)).unwrap_or_else(|| self.stage.fallback());
        self.primary = Some(Primary { stage: self.stage, code, io_kind: Some(io_kind(cause.kind())),
            cause_type: (self.stage == Stage::ConfigLoad).then(|| crate::runtime::startup_cause_type(cause)) });
    }
    pub fn record_code(&mut self, stage: Stage, code: &'static str) {
        if !self.completed && self.primary.is_none() {
            self.primary = Some(Primary { stage, code: allowed_code(code), io_kind: None, cause_type: None });
        }
    }
    pub fn cleaned(&mut self, runtime_confirmed: bool, loaded: bool) {
        self.cleanup = match (runtime_confirmed, loaded) {
            (true, true) => "complete",
            (false, true) => "runtimeCleanupFailed",
            (true, false) => "loadJoinFailed",
            (false, false) => "runtimeAndLoadJoinFailed",
        };
    }
}
fn allowed_code(code: &str) -> &'static str {
    match code {
        "ownerCreateFailed" => "ownerCreateFailed",
        "planDecodeFailed" => "planDecodeFailed",
        "hostPolicyFailed" => "hostPolicyFailed",
        "physicalDnsFailed" => "physicalDnsFailed",
        "managedConfigFailed" => "managedConfigFailed",
        "configLoadFailed" => "configLoadFailed",
        "dnsSetupFailed" => "dnsSetupFailed",
        "controlSetupFailed" => "controlSetupFailed",
        "graphActivationFailed" => "graphActivationFailed",
        "userActivationFailed" => "userActivationFailed",
        "readyWaitFailed" => "readyWaitFailed",
        "candidateFieldNotIntegrated" => "candidateFieldNotIntegrated",
        "managedDialerProxyGraphRejected" => "managedDialerProxyGraphRejected",
        "managedConfigRejected" => "managedConfigRejected",
        "managedGroupConfigRejected" => "managedGroupConfigRejected",
        "providerConfigRejected" => "providerConfigRejected",
        "managedDialerProxyGraphNotIntegrated" => "managedDialerProxyGraphNotIntegrated",
        "cancelled" => "cancelled",
        "ownerExited" => "ownerExited",
        "ownedRuntimePanicked" => "ownedRuntimePanicked",
        "unclassified" => "unclassified",
        _ => "unclassified",
    }
}
fn io_kind(kind: io::ErrorKind) -> &'static str {
    match kind {
        io::ErrorKind::PermissionDenied => "permissionDenied",
        io::ErrorKind::AddrInUse => "addressInUse",
        io::ErrorKind::AddrNotAvailable => "addressNotAvailable",
        io::ErrorKind::InvalidData => "invalidData",
        io::ErrorKind::InvalidInput => "invalidInput",
        io::ErrorKind::NotConnected => "notConnected",
        io::ErrorKind::Interrupted => "interrupted",
        io::ErrorKind::TimedOut => "timedOut",
        _ => "other",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn wrapped(source: anyhow::Error) -> io::Error {
        crate::runtime::failure_with_source(crate::runtime::Failure { code: "configLoadFailed",
            field: Some("engineYaml".into()), reason: Some("password=private-context".into()) }, source)
    }
    fn assert_cause(source: anyhow::Error, expected: CauseType) {
        let error = wrapped(source.context("https://private.invalid/secret"));
        let mut d = Diagnostic::default(); d.advance(Stage::ConfigLoad); d.record_io(&error);
        assert_eq!(d.primary.as_ref().unwrap().cause_type, Some(expected));
        assert_eq!(crate::runtime::failure_detail(&error).unwrap().code, "configLoadFailed");
        assert_eq!(crate::runtime::retained_failure_context(&error).unwrap().reason.as_deref(), Some("password=private-context"));
        d.record_code(Stage::ReadyWait, "cancelled");
        d.cleaned(false, false);
        assert_eq!(d.primary.as_ref().unwrap().cause_type, Some(expected));
        assert_eq!(d.cleanup, "runtimeAndLoadJoinFailed");
        let json = serde_json::to_string(&d).unwrap();
        for private in ["password", "private", "https", "secret", "cancelled"] { assert!(!json.contains(private)); }
    }
    #[test]
    fn config_cause_classification_uses_types_never_error_text() {
        let yaml = serde_yaml::from_str::<serde_yaml::Value>("password: [secret").unwrap_err();
        assert_cause(anyhow::Error::new(yaml), CauseType::SerdeYaml);
        assert_cause(anyhow::Error::new(io::Error::new(io::ErrorKind::PermissionDenied, "/private/secret")), CauseType::Io);
        assert_cause(anyhow::anyhow!("serde_yaml::Error permission denied JoinError password=secret"), CauseType::Unknown);
        let mut d = Diagnostic::default(); d.advance(Stage::ControlSetup);
        d.record_io(&io::Error::other("private"));
        assert!(!serde_json::to_string(&d).unwrap().contains("causeType"));
        let mut d = Diagnostic::default(); d.record_code(Stage::ReadyWait, "cancelled");
        d.advance(Stage::ConfigLoad); d.record_io(&wrapped(anyhow::anyhow!("private")));
        assert_eq!(d.primary.unwrap().code, "cancelled");
    }
    #[test]
    fn bootstrap_resolve_is_typed_private_and_preserves_failure_priority() {
        use meow_dns::resolver::BootstrapError;
        assert_cause(anyhow::Error::new(BootstrapError::CannotResolve {
            host: "private.secret.invalid".into(),
            source: Box::new(io::Error::new(io::ErrorKind::NetworkUnreachable, "password=secret")),
        }), CauseType::DnsBootstrapResolve);
        assert_cause(anyhow::Error::new(BootstrapError::UnknownProxy {
            nameserver: "https://private.invalid/secret".into(), proxy: "password".into(),
        }), CauseType::Unknown);
        assert_cause(anyhow::anyhow!("CannotResolve dnsBootstrapResolve password=secret"), CauseType::Unknown);
    }
    #[tokio::test]
    async fn config_task_join_failure_has_fixed_type() {
        let task = tokio::spawn(std::future::pending::<()>()); task.abort();
        assert_cause(anyhow::Error::new(task.await.unwrap_err()), CauseType::Join);
    }
    #[test]
    fn primary_preserves_boundary_and_excludes_all_dynamic_payloads() {
        let mut d = Diagnostic::default(); d.advance(Stage::ConfigLoad);
        let e = io::Error::other(crate::runtime::Failure { code: "providerConfigRejected",
            field: Some("/private/provider-name".into()), reason: Some("password=secret https://private.invalid".into()) });
        d.record_io(&e); d.cleaned(false, true);
        d.record_code(Stage::ReadyWait, "cancelled");
        let j = serde_json::to_string(&d).unwrap();
        assert!(j.contains("providerConfigRejected") && j.contains("runtimeCleanupFailed"));
        assert!(j.contains("configLoad"));
        for secret in ["private", "password", "https", "cancelled"] { assert!(!j.contains(secret)); }
    }
    #[test]
    fn untyped_io_retains_real_stage_kind_and_does_not_parse_message() {
        let mut d = Diagnostic::default(); d.advance(Stage::PhysicalDns);
        d.record_io(&io::Error::new(io::ErrorKind::PermissionDenied, "providerConfigRejected: password=secret"));
        let p = d.primary.unwrap(); assert_eq!(p.code, "physicalDnsFailed"); assert_eq!(p.io_kind, Some("permissionDenied"));
    }
    #[test]
    fn cleanup_matrix_keeps_both_failures_and_never_replaces_primary() {
        let cases = [(true, true, "complete"), (false, true, "runtimeCleanupFailed"),
            (true, false, "loadJoinFailed"), (false, false, "runtimeAndLoadJoinFailed")];
        for (runtime_confirmed, loaded, expected) in cases {
            let mut d = Diagnostic::default(); d.advance(Stage::ConfigLoad);
            d.record_io(&io::Error::new(io::ErrorKind::InvalidData, "private startup payload"));
            d.cleaned(runtime_confirmed, loaded);
            assert_eq!(d.cleanup, expected);
            let primary = d.primary.as_ref().unwrap();
            assert_eq!(primary.stage, Stage::ConfigLoad);
            assert_eq!(primary.code, "configLoadFailed");
            assert_eq!(primary.io_kind, Some("invalidData"));
            assert!(!serde_json::to_string(&d).unwrap().contains("private startup payload"));
        }
    }
    #[test]
    fn running_and_unknown_codes_cannot_invent_a_startup_failure() {
        let mut d = Diagnostic::default(); d.running(); d.record_io(&io::Error::other("secret"));
        assert!(d.primary.is_none());
        let mut cancelled = Diagnostic::default(); cancelled.record_code(Stage::ReadyWait, "cancelled");
        assert_eq!(cancelled.primary.unwrap().code,"cancelled");
        let mut unknown = Diagnostic::default(); unknown.record_code(Stage::OwnerCreate, "passwordSecret");
        assert_eq!(unknown.primary.unwrap().code, "unclassified");
    }
}
