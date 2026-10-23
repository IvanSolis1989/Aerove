//! Independent Meow candidate; the existing Android core is not switched here.
pub mod runtime;
#[cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
pub mod transport_tasks;
pub mod capabilities;
pub mod config_validation;
pub mod api;
pub mod control;
pub mod providers;
#[cfg(unix)]
pub mod packet_fd;
pub mod runtime_plan;
pub mod resource_roots;
pub mod request_policies;
pub mod probe_policy;
pub mod host_policies;
#[cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
pub mod network_context;
#[cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
pub mod tun_driver;
#[cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
pub mod physical_dns;
#[cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
pub mod tunnel_packets;
pub mod flow_owner;
#[cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
pub mod owned_runtime;
#[cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
mod meow_jni;
mod script_engine;
mod script_jni;

pub use runtime::{Failure, Phase, Registry, Session, Snapshot, StartOptions};
