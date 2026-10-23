use serde::Deserialize;
use std::{collections::BTreeMap, io, net::{IpAddr, SocketAddr}};

#[derive(Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum RuntimeMode { Tun, Proxy }

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RuntimePlan {
    pub schema_version: u8,
    pub runtime_mode: RuntimeMode,
    pub initially_parked: bool,
    pub engine_yaml: String,
    pub private_control: PrivateControl,
    pub inbounds: InboundPlan,
    pub tun: Option<TunPlan>,
    pub physical_network: PhysicalNetwork,
    pub host_policy_yaml: String,
    pub profile_id: Option<String>,
    pub revision: Option<String>,
    pub resource_generation: Option<String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PrivateControl { pub address: SocketAddr, pub secret: String }

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InboundPlan { pub config_yaml: String }

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TunPlan {
    pub mtu: u16,
    pub ipv6: bool,
    pub inet4_address: String,
    pub inet6_address: Option<String>,
    pub dns_hijack: Vec<String>,
    pub auto_route: bool,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PhysicalNetwork { pub handle: Option<String>, pub dns: PhysicalDns }

#[derive(Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PhysicalDns {
    pub default_interface: Option<String>,
    pub interfaces: BTreeMap<String, Vec<String>>,
}

fn invalid(field: &'static str) -> io::Error { io::Error::new(io::ErrorKind::InvalidInput, field) }
fn interface(value: &str) -> bool {
    !value.is_empty() && value.len() <= 128
        && value.bytes().all(|c| c.is_ascii_alphanumeric() || b"_.-".contains(&c))
}
fn cidr(value: &str, ipv6: bool) -> bool {
    let Some((ip, prefix)) = value.split_once('/') else { return false; };
    let (Ok(ip), Ok(prefix)) = (ip.parse::<IpAddr>(), prefix.parse::<u8>()) else { return false; };
    ip.is_ipv6() == ipv6 && prefix <= if ipv6 { 128 } else { 32 }
        && !ip.is_unspecified() && !ip.is_multicast()
}

impl RuntimePlan {
    pub fn parse(json: &str, fd: i32, protector: bool, owner: bool) -> io::Result<Self> {
        if json.len() > 4 * 1024 * 1024 + 65536 { return Err(invalid("plan")); }
        let plan: Self = serde_json::from_str(json).map_err(|_| invalid("plan"))?;
        if plan.schema_version != 1 { return Err(invalid("schemaVersion")); }
        if !plan.private_control.address.ip().is_loopback() || plan.private_control.address.port() == 0 {
            return Err(invalid("privateControl.address"));
        }
        let secret = &plan.private_control.secret;
        if !(32..=512).contains(&secret.len()) || !secret.is_ascii() || secret.bytes().any(|c| c.is_ascii_control()) {
            return Err(invalid("privateControl.secret"));
        }
        match (plan.runtime_mode, &plan.tun) {
            (RuntimeMode::Proxy, None) if fd == -1 && !protector && !owner => {},
            (RuntimeMode::Tun, Some(tun)) if protector && ((plan.initially_parked && fd == -1) || (!plan.initially_parked && fd >= 0)) => {
                tun.validate()?;
            },
            _ => return Err(invalid("runtimeMode/tunFd/protector")),
        }
        if let Some(handle) = &plan.physical_network.handle {
            if handle.parse::<u64>().ok().filter(|n| *n != 0).map(|n| n.to_string()) != Some(handle.clone()) {
                return Err(invalid("physicalNetwork.handle"));
            }
        }
        let dns = &plan.physical_network.dns;
        if dns.interfaces.len() > 64 { return Err(invalid("physicalNetwork.dns.interfaces")); }
        if dns.default_interface.as_ref().is_some_and(|name| !dns.interfaces.contains_key(name)) {
            return Err(invalid("physicalNetwork.dns.defaultInterface"));
        }
        for (name, servers) in &dns.interfaces {
            if !interface(name) || servers.len() > 16 { return Err(invalid("physicalNetwork.dns.interfaces")); }
            for server in servers {
                let (literal, zone) = server.split_once('%').map_or((server.as_str(), None), |(ip, zone)| (ip, Some(zone)));
                let ip = literal.parse::<IpAddr>().map_err(|_| invalid("physicalNetwork.dns.address"))?;
                if ip.is_unspecified() || ip.is_multicast() { return Err(invalid("physicalNetwork.dns.address")); }
                if let Some(zone) = zone {
                    if !ip.is_ipv6() || !(zone == name || (zone.bytes().all(|c| c.is_ascii_digit()) && zone.parse::<u32>().ok().is_some_and(|n| n != 0))) {
                        return Err(invalid("physicalNetwork.dns.scope"));
                    }
                }
            }
        }
        Ok(plan)
    }
}

impl TunPlan {
    pub fn validate(&self) -> io::Result<()> {
        if self.mtu < 1280 { return Err(invalid("tun.mtu")); }
        if self.auto_route { return Err(invalid("tun.autoRoute")); }
        if !cidr(&self.inet4_address, false) { return Err(invalid("tun.inet4Address")); }
        if self.ipv6 != self.inet6_address.is_some()
            || self.inet6_address.as_ref().is_some_and(|ip| !cidr(ip, true)) {
            return Err(invalid("tun.inet6Address"));
        }
        if self.dns_hijack.len() > 16 { return Err(invalid("tun.dnsHijack")); }
        for target in &self.dns_hijack {
            if target != "any:53" && !target.parse::<SocketAddr>().ok().is_some_and(|target| target.port() == 53) {
                return Err(invalid("tun.dnsHijack"));
            }
        }
        Ok(())
    }

    pub fn hijacks(&self, destination: SocketAddr) -> bool {
        destination.port() == 53 && self.dns_hijack.iter().any(|target| target == "any:53"
            || target.parse::<SocketAddr>().ok() == Some(destination))
    }
}
