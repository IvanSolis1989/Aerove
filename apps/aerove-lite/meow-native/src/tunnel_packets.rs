//! Owned adaptation of fixed Meow tun/udp.rs and TunTcpConn (MIT, Meow contributors).
use crate::{flow_owner::FlowOwnerLookup, network_context::NetworkContext, runtime_plan::TunPlan, tun_driver::{PacketHandler, PacketWork}};
use futures::FutureExt;
use meow_common::{ConnType, Metadata, Network, ProxyConn, ProxyPacketConn};
use meow_tunnel::{ResolvedTarget, Tunnel};
use std::{collections::BTreeMap, io, net::SocketAddr, panic::AssertUnwindSafe, pin::Pin, sync::{Arc, Mutex, atomic::{AtomicU64, Ordering}}, task::{Context, Poll}, time::Duration};
use tokio::{io::{AsyncRead, AsyncWrite, ReadBuf}, sync::mpsc, task::JoinSet, time::{Instant, sleep}};
use tokio_util::sync::CancellationToken;

pub struct TunnelPackets { tunnel: Tunnel, idle: Duration, owner: Option<Arc<dyn FlowOwnerLookup>>, processes: Option<Arc<crate::process_policies::ProcessPolicies>>, network: Option<NetworkContext> }
impl TunnelPackets {
    pub fn new(tunnel: Tunnel, idle: Duration) -> io::Result<Arc<Self>> {
        if idle.is_zero() { return Err(io::Error::new(io::ErrorKind::InvalidInput, "UDP idle timeout")); }
        Ok(Arc::new(Self { tunnel, idle, owner: None, processes: None, network: None }))
    }
    pub fn with_process(tunnel: Tunnel, idle: Duration, processes: Arc<crate::process_policies::ProcessPolicies>) -> io::Result<Arc<Self>> {
        if idle.is_zero() { return Err(io::Error::new(io::ErrorKind::InvalidInput, "UDP idle timeout")); }
        Ok(Arc::new(Self { tunnel, idle, owner: None, processes: Some(processes), network: None }))
    }
    pub fn with_context(tunnel: Tunnel, idle: Duration, processes: Arc<crate::process_policies::ProcessPolicies>, network: NetworkContext) -> io::Result<Arc<Self>> {
        if idle.is_zero() { return Err(io::Error::new(io::ErrorKind::InvalidInput, "UDP idle timeout")); }
        Ok(Arc::new(Self { tunnel, idle, owner: None, processes: Some(processes), network: Some(network) }))
    }
    pub fn with_owner(tunnel: Tunnel, idle: Duration, owner: Option<Arc<dyn FlowOwnerLookup>>) -> io::Result<Arc<Self>> {
        if idle.is_zero() { return Err(io::Error::new(io::ErrorKind::InvalidInput, "UDP idle timeout")); }
        Ok(Arc::new(Self { tunnel, idle, owner, processes: None, network: None }))
    }
}
fn error(message: &'static str) -> io::Error { io::Error::other(message) }

impl PacketHandler for TunnelPackets {
    fn tcp(&self, stream: lwip::TcpStream, source: SocketAddr, target: SocketAddr) -> PacketWork<()> {
        let tunnel = self.tunnel.clone();
        let owner = self.owner.clone(); let processes = self.processes.clone(); let network = self.network.clone();
        let work: PacketWork<()> = Box::pin(async move {
            let mut meta = metadata(Network::Tcp, source, target, owner.as_deref());
            if let Some(processes) = processes { processes.current().attach(&mut meta,source,target)?; }
            meow_tunnel::tcp::handle_tcp(tunnel.inner(), Box::new(TunStream(Mutex::new(stream))),
                meta).await;
            Ok(())
        });
        Box::pin(async move { match network { Some(network) => network.scope(work).await, None => work.await } })
    }
    fn tcp_with_tun(&self, stream: lwip::TcpStream, source: SocketAddr, target: SocketAddr,
        tun: TunPlan, cancel: CancellationToken) -> PacketWork<()> {
        if !tun.hijacks(target) { return self.tcp(stream, source, target); }
        let tunnel = self.tunnel.clone(); let network = self.network.clone();
        Box::pin(async move { match network {
            Some(network) => crate::tcp_dns::serve_scoped(TunStream(Mutex::new(stream)),tunnel,network,cancel,crate::tcp_dns::Limits::default()).await,
            None => crate::tcp_dns::serve(TunStream(Mutex::new(stream)),tunnel,cancel,crate::tcp_dns::Limits::default()).await,
        } })
    }
    fn udp(&self, payload: Vec<u8>, _source: SocketAddr, _target: SocketAddr, dns_hijacked: bool) -> PacketWork<Option<Vec<u8>>> {
        let resolver = self.tunnel.resolver();
        Box::pin(async move {
            if !dns_hijacked { return Err(io::Error::from(io::ErrorKind::Unsupported)); }
            meow_dns::DnsServer::handle_query(&payload, &resolver).await.map(Some).map_err(|_| error("DNS query failed"))
        })
    }
    fn udp_driver(self: Arc<Self>, socket: Box<lwip::UdpSocket>, tun: TunPlan,
        cancel: CancellationToken, network: NetworkContext) -> PacketWork<()> {
        let network = self.network.clone().unwrap_or(network);
        Box::pin(async move { self.run_udp(socket, tun, cancel, network).await })
    }
}

fn metadata(network: Network, source: SocketAddr, target: SocketAddr, owner: Option<&dyn FlowOwnerLookup>) -> Metadata {
    let protocol = if matches!(network, Network::Tcp) { 6 } else { 17 };
    let mut metadata = Metadata { network, conn_type: ConnType::Tun, src_ip: Some(source.ip()), src_port: source.port(),
        dst_ip: Some(target.ip()), dst_port: target.port(), in_name: "AEROVE-TUN".into(), ..Default::default() };
    if let Some(identity) = owner.and_then(|owner| owner.lookup(protocol, source, target)) {
        metadata.uid = Some(identity.uid); metadata.process = identity.process.into(); metadata.process_path = identity.process_path.into();
    }
    metadata
}
type Reply = (Vec<u8>, SocketAddr, SocketAddr);
struct Entry { sender: mpsc::Sender<Vec<u8>>, activity: Arc<AtomicU64>, cancel: CancellationToken }
impl Drop for Entry { fn drop(&mut self) { self.cancel.cancel(); } }

impl TunnelPackets {
    async fn run_udp(&self, socket: Box<lwip::UdpSocket>, tun: TunPlan,
        cancel: CancellationToken, network: NetworkContext) -> io::Result<()> {
        let (writer, mut reader) = socket.split();
        let (replies, mut reply_rx) = mpsc::channel::<Reply>(512);
        let mut tasks = JoinSet::new();
        let mut entries = BTreeMap::<(SocketAddr, SocketAddr), Entry>::new();
        let dns_permits = Arc::new(tokio::sync::Semaphore::new(64));
        let epoch = std::time::Instant::now();
        let subnet4 = tun.inet4_address.parse::<ipnet::Ipv4Net>().map_err(|_| error("invalid Tun subnet"))?;
        let subnet6 = tun.inet6_address.as_ref().map(|net| net.parse::<ipnet::Ipv6Net>()).transpose().map_err(|_| error("invalid Tun subnet"))?;
        let outcome = AssertUnwindSafe(async { loop { tokio::select! { biased;
            _ = cancel.cancelled() => break Ok(()),
            joined = tasks.join_next(), if !tasks.is_empty() => {
                if joined.is_some_and(|joined| joined.is_err()) { break Err(error("UDP flow owner panicked")); }
            },
            packet = reader.recv_from(), if tasks.len() < 1088 => {
                let (payload, source, target) = match packet { Ok(packet) => packet, Err(e) => break Err(e) };
                if tun.hijacks(target) {
                    let Ok(permit) = dns_permits.clone().try_acquire_owned() else { continue; };
                    let (context, resolver) = network.captured_with_resolver();
                    let resolver = resolver.unwrap_or_else(|| self.tunnel.resolver());
                    let sender = replies.clone(); let stop = cancel.clone();
                    tasks.spawn(async move { context.scope(async move {
                        let _permit = permit;
                        tokio::select! { biased;
                            _ = stop.cancelled() => {},
                            response = meow_dns::DnsServer::handle_query(&payload, &resolver) => {
                                if let Ok(response) = response { tokio::select! { biased;
                                    _ = stop.cancelled() => {},
                                    _ = sender.send((response, target, source)) => {},
                                } }
                            },
                        }
                    }).await });
                    continue;
                }
                let looping = match target.ip() {
                    std::net::IpAddr::V4(ip) => ip.is_broadcast() || ip.is_multicast() || subnet4.contains(&ip),
                    std::net::IpAddr::V6(ip) => ip.is_multicast() || subnet6.is_some_and(|net| net.contains(&ip)),
                };
                if looping { continue; }
                let key = (source, target);
                if let Some(entry) = entries.get(&key) {
                    entry.activity.store(epoch.elapsed().as_millis() as u64, Ordering::Relaxed);
                    match entry.sender.try_send(payload) {
                        Ok(()) | Err(mpsc::error::TrySendError::Full(_)) => continue,
                        Err(mpsc::error::TrySendError::Closed(payload)) => {
                            entries.remove(&key);
                            self.spawn_udp_flow(payload, key, &mut entries, &mut tasks, &replies, &network, &cancel, epoch);
                        },
                    }
                } else {
                    entries.retain(|_, entry| !entry.sender.is_closed());
                    if entries.len() >= 1024 {
                        if let Some(key) = entries.iter().min_by_key(|(_, entry)| entry.activity.load(Ordering::Relaxed)).map(|(key, _)| *key) { entries.remove(&key); }
                    }
                    self.spawn_udp_flow(payload, key, &mut entries, &mut tasks, &replies, &network, &cancel, epoch);
                }
            },
            Some((payload, source, target)) = reply_rx.recv() => {
                if let Err(e) = writer.send_to(&payload, &source, &target) {
                    if e.kind() != io::ErrorKind::WouldBlock { break Err(e); }
                }
            },
        } } }).catch_unwind().await;
        cancel.cancel();
        drop(entries);
        while tasks.join_next().await.is_some() {}
        outcome.map_err(|_| error("UDP owner panicked"))?
    }

    fn spawn_udp_flow(&self, payload: Vec<u8>, key: (SocketAddr, SocketAddr),
        entries: &mut BTreeMap<(SocketAddr, SocketAddr), Entry>, tasks: &mut JoinSet<()>,
        replies: &mpsc::Sender<Reply>, network: &NetworkContext, cancel: &CancellationToken, epoch: std::time::Instant) {
        let (sender, receiver) = mpsc::channel(64);
        sender.try_send(payload).expect("new flow queue has capacity");
        let activity = Arc::new(AtomicU64::new(epoch.elapsed().as_millis() as u64));
        let cancel = cancel.child_token();
        entries.insert(key, Entry { sender, activity: activity.clone(), cancel: cancel.clone() });
        let tunnel = self.tunnel.clone(); let replies = replies.clone();
        let context = network.clone(); let idle = self.idle;
        let owner = self.owner.clone(); let processes = self.processes.clone();
        tasks.spawn(async move { context.scope(async move {
            let _ = udp_flow(tunnel, receiver, replies, key, cancel, idle, activity, epoch, owner, processes).await;
        }).await });
    }
}

struct ClosePacket(Arc<dyn ProxyPacketConn>);
impl Drop for ClosePacket { fn drop(&mut self) { let _ = self.0.close(); } }

async fn udp_flow(tunnel: Tunnel, mut queued: mpsc::Receiver<Vec<u8>>, replies: mpsc::Sender<Reply>,
    (source, target): (SocketAddr, SocketAddr), cancel: CancellationToken, idle: Duration,
    activity: Arc<AtomicU64>, epoch: std::time::Instant, owner: Option<Arc<dyn FlowOwnerLookup>>, processes: Option<Arc<crate::process_policies::ProcessPolicies>>) -> io::Result<()> {
    if cancel.is_cancelled() { return Ok(()); }
    let mut meta = metadata(Network::Udp, source, target, owner.as_deref());
    if let Some(processes) = processes { processes.current().attach(&mut meta,source,target)?; }
    if cancel.is_cancelled() { return Ok(()); }
    let inner = tunnel.inner();
    let mut flush = inner.udp_flush_watch();
    let prepare = async {
        if matches!(inner.pre_handle_metadata(&mut meta), meow_tunnel::PreHandleVerdict::Drop) { return Err(error("unmapped fake IP")); }
        inner.pre_resolve(&mut meta).await;
        if meta.dst_ip.is_none() && !meta.host.is_empty() { meta.dst_ip = inner.resolver().resolve_ip_real(&meta.host).await; }
        let target = SocketAddr::new(meta.dst_ip.ok_or_else(|| error("UDP target unresolved"))?, meta.dst_port);
        let Some(ResolvedTarget { adapter, route, .. }) = inner.resolve_proxy(&meta).await else { return Err(error("UDP route unavailable")); };
        let conn: Arc<dyn ProxyPacketConn> = Arc::from(inner.dial_udp(adapter.as_ref(), &meta).await.map_err(|_| error("UDP dial failed"))?);
        drop(route);
        Ok((conn, target))
    };
    let (conn, remote) = tokio::select! { biased;
        _ = cancel.cancelled() => return Ok(()),
        _ = flush.flushed() => return Ok(()),
        result = prepare => result?,
    };
    let _close = ClosePacket(conn.clone());
    let deadline = sleep(idle); tokio::pin!(deadline);
    let mut buffer = vec![0u8; 65535];
    loop {
        let packet = {
            let reading = conn.read_packet(&mut buffer); tokio::pin!(reading);
            loop { tokio::select! { biased;
                _ = cancel.cancelled() => return Ok(()),
                _ = flush.flushed() => return Ok(()),
                _ = &mut deadline => return Ok(()),
                sent = queued.recv() => match sent {
                    Some(data) => {
                        let written = tokio::select! { biased;
                            _ = cancel.cancelled() => return Ok(()),
                            result = conn.write_packet(&data, &remote) => result.map_err(|_| error("UDP send failed"))?,
                        };
                        if written != data.len() { return Err(error("short UDP write")); }
                        deadline.as_mut().reset(Instant::now() + idle);
                    },
                    None => return Ok(()),
                },
                result = &mut reading => break result.map_err(|_| error("UDP receive failed"))?,
            } }
        };
        let (count, _) = packet;
        let data = buffer[..count].to_vec();
        tokio::select! { biased;
            _ = cancel.cancelled() => return Ok(()),
            result = replies.send((data, target, source)) => result.map_err(|_| error("UDP reply owner closed"))?,
        }
        activity.store(epoch.elapsed().as_millis() as u64, Ordering::Relaxed);
        deadline.as_mut().reset(Instant::now() + idle);
    }
}

struct TunStream(Mutex<lwip::TcpStream>);
impl AsyncRead for TunStream {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buffer: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut *self.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner)).poll_read(cx, buffer)
    }
}
impl AsyncWrite for TunStream {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, bytes: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut *self.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner)).poll_write(cx, bytes)
    }
    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut *self.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner)).poll_flush(cx)
    }
    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut *self.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner)).poll_shutdown(cx)
    }
}
impl ProxyConn for TunStream {}
