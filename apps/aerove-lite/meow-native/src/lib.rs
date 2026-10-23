//! Independent Meow candidate; the existing Android core is not switched here.
pub mod runtime;
pub mod api;
pub mod control;
#[cfg(unix)]
pub mod packet_fd;
pub mod runtime_plan;
#[cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
pub mod network_context;
#[cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
pub mod tun_driver;
#[cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
pub mod physical_dns;
mod script_engine;
mod script_jni;

pub use runtime::{Failure, Phase, Registry, Session, Snapshot, StartOptions};
