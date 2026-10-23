use std::net::SocketAddr;

pub struct FlowIdentity { pub uid: u32, pub process: String, pub process_path: String }
pub trait FlowOwnerLookup: Send + Sync {
    fn lookup(&self, protocol: i32, source: SocketAddr, target: SocketAddr) -> Option<FlowIdentity>;
}
