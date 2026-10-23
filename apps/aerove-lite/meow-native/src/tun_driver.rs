use crate::{network_context::NetworkContext, packet_fd::PacketFd, runtime_plan::{RuntimeMode, RuntimePlan, TunPlan}};
use futures::{FutureExt, SinkExt, StreamExt};
use meow_config::owned_load::OwnedLoadScope;
use std::{future::Future, io, net::SocketAddr, os::fd::BorrowedFd, panic::AssertUnwindSafe, pin::Pin, sync::{Arc, OnceLock, atomic::{AtomicBool, AtomicU64, Ordering}}};
use tokio::{sync::{Mutex, Semaphore, mpsc, watch}, task::{JoinHandle, JoinSet}};
use tokio_util::sync::CancellationToken;

pub type PacketWork<T> = Pin<Box<dyn Future<Output = io::Result<T>> + Send + 'static>>;
pub trait PacketHandler: Send + Sync {
    fn tcp(&self, stream: lwip::TcpStream, source: SocketAddr, target: SocketAddr) -> PacketWork<()>;
    fn udp(&self, payload: Vec<u8>, source: SocketAddr, target: SocketAddr, dns_hijacked: bool) -> PacketWork<Option<Vec<u8>>>;
}

static STACK: OnceLock<Arc<Semaphore>> = OnceLock::new();

#[derive(Clone, Debug)]
pub struct PacketReceipt {
    pub ready: bool,
    pub core_done: bool,
    pub mtu: u16,
    pub ipv6: bool,
    pub packets_in: u64,
    pub packets_out: u64,
}

#[derive(Clone, Copy)]
enum Phase { Starting, Ready, Stopped, Failed }
struct Progress { ready: AtomicBool, done: AtomicBool, incoming: AtomicU64, outgoing: AtomicU64, phase: watch::Sender<Phase> }
pub struct TunDriver {
    cancel: CancellationToken,
    network: NetworkContext,
    loads: Arc<OwnedLoadScope>,
    task: Mutex<Option<JoinHandle<io::Result<()>>>>,
    progress: Arc<Progress>,
    tun: TunPlan,
}

fn error(message: &'static str) -> io::Error { io::Error::other(message) }

impl TunDriver {
    pub fn start<F, Fut>(json: &str, caller: BorrowedFd<'_>, network: NetworkContext,
        cancel: CancellationToken, factory: F) -> io::Result<Arc<Self>>
    where F: FnOnce(Arc<RuntimePlan>, Arc<OwnedLoadScope>) -> Fut + Send + 'static,
          Fut: Future<Output = io::Result<Arc<dyn PacketHandler>>> + Send + 'static {
        let plan = Arc::new(RuntimePlan::parse(json, std::os::fd::AsRawFd::as_raw_fd(&caller), true, false)?);
        if plan.runtime_mode != RuntimeMode::Tun || plan.initially_parked {
            return Err(error("packet driver requires an attached Tun plan"));
        }
        let requested_network = plan.physical_network.handle.as_ref().and_then(|handle| handle.parse::<u64>().ok());
        if requested_network != network.network_handle() { return Err(error("physical network binding context mismatch")); }
        let runtime = tokio::runtime::Handle::try_current().map_err(|_| error("runtime unavailable"))?;
        if network.cancelled() || cancel.is_cancelled() { return Err(error("packet owner cancelled")); }
        let network = network.child();
        let cancel = cancel.child_token();
        let tun = plan.tun.as_ref().ok_or_else(|| error("Tun plan missing"))?.clone();
        let permit = STACK.get_or_init(|| Arc::new(Semaphore::new(1))).clone()
            .try_acquire_owned().map_err(|_| error("netstack already owned"))?;
        let fd = PacketFd::duplicate(caller, usize::from(tun.mtu), cancel.clone())?;
        let loads = OwnedLoadScope::with_context(Arc::new(network.clone()));
        let (phase, _) = watch::channel(Phase::Starting);
        let progress = Arc::new(Progress { ready: AtomicBool::new(false), done: AtomicBool::new(false), incoming: AtomicU64::new(0), outgoing: AtomicU64::new(0), phase });
        let owner = Arc::new(Self { cancel: cancel.clone(), network: network.clone(), loads: loads.clone(),
            task: Mutex::new(None), progress: progress.clone(), tun: tun.clone() });
        let task = runtime.spawn(async move {
            let mut core_done = None;
            let mut stack_started = false;
            let outcome = AssertUnwindSafe(async {
                let loaded = {
                    let work = loads.run(network.scope(async { factory(plan, loads.clone()).await }));
                    tokio::pin!(work);
                    tokio::select! { biased;
                        _ = cancel.cancelled() => return Ok(()),
                        _ = network.cancelled_signal() => return Ok(()),
                        result = &mut work => result,
                    }
                };
                let handler = loaded?;
                if cancel.is_cancelled() || network.cancelled() { return Ok(()); }
                stack_started = true;
                let (stack, tcp, udp) = lwip::NetStack::with_mtu(tun.mtu, 256, 64)
                    .map_err(|_| error("netstack construction failed"))?;
                core_done = Some(stack.core_done());
                network.scope(drive(fd, stack, tcp, udp, handler, tun, network.clone(), cancel.clone(), progress.clone())).await
            }).catch_unwind().await;
            progress.ready.store(false, Ordering::Release);
            network.cancel();
            loads.cancel();
            let loaded_jobs = AssertUnwindSafe(loads.close()).catch_unwind().await;
            let mut permit = Some(permit);
            if let Some(mut done) = core_done {
                if done.wait_for(|done| *done).await.is_err() {
                    // A missing teardown ACK keeps successor stacks out of the C globals.
                    std::mem::forget(permit.take());
                    progress.phase.send_replace(Phase::Failed);
                    return Err(error("netstack teardown unconfirmed"));
                }
                progress.done.store(true, Ordering::Release);
            } else if stack_started {
                std::mem::forget(permit.take());
                progress.phase.send_replace(Phase::Failed);
                return Err(error("partial netstack construction has no teardown ACK"));
            }
            drop(permit);
            let result = loaded_jobs.map_err(|_| error("load owner panicked"))
                .and_then(|jobs| jobs.map_err(error))
                .and_then(|()| outcome.map_err(|_| error("packet driver panicked")))
                .and_then(|result| result);
            progress.phase.send_replace(if result.is_err() { Phase::Failed } else { Phase::Stopped });
            result
        });
        *owner.task.try_lock().map_err(|_| error("new packet owner lock unavailable"))? = Some(task);
        Ok(owner)
    }

    pub async fn load<F, Fut>(json: &str, caller: BorrowedFd<'_>, network: NetworkContext,
        cancel: CancellationToken, factory: F) -> io::Result<Arc<Self>>
    where F: FnOnce(Arc<RuntimePlan>, Arc<OwnedLoadScope>) -> Fut + Send + 'static,
          Fut: Future<Output = io::Result<Arc<dyn PacketHandler>>> + Send + 'static {
        let owner = Self::start(json, caller, network, cancel, factory)?;
        if let Err(error) = owner.await_ready().await {
            let _ = owner.stop().await;
            return Err(error);
        }
        Ok(owner)
    }

    pub async fn await_ready(&self) -> io::Result<PacketReceipt> {
        let mut phase = self.progress.phase.subscribe();
        loop {
            match *phase.borrow_and_update() {
                Phase::Ready => return Ok(self.receipt()),
                Phase::Stopped => return Err(error("packet owner cancelled")),
                Phase::Failed => return Err(error("packet driver failed")),
                Phase::Starting => {},
            }
            phase.changed().await.map_err(|_| error("packet owner exited"))?;
        }
    }

    pub fn receipt(&self) -> PacketReceipt {
        PacketReceipt { ready: self.progress.ready.load(Ordering::Acquire), core_done: self.progress.done.load(Ordering::Acquire),
            mtu: self.tun.mtu, ipv6: self.tun.ipv6, packets_in: self.progress.incoming.load(Ordering::Relaxed),
            packets_out: self.progress.outgoing.load(Ordering::Relaxed) }
    }

    pub async fn stop(&self) -> io::Result<PacketReceipt> {
        self.cancel.cancel();
        self.network.cancel();
        self.loads.cancel();
        let mut task = self.task.lock().await;
        if let Some(worker) = task.as_mut() {
            let result = worker.await;
            task.take();
            result.map_err(|_| error("packet owner panicked"))??;
        }
        Ok(self.receipt())
    }
}

impl Drop for TunDriver {
    fn drop(&mut self) { self.cancel.cancel(); self.loads.cancel(); self.network.cancel(); }
}

async fn drive(fd: PacketFd, mut stack: lwip::NetStack, mut tcp: lwip::TcpListener,
    udp: Box<lwip::UdpSocket>, handler: Arc<dyn PacketHandler>, tun: TunPlan, network: NetworkContext,
    cancel: CancellationToken, progress: Arc<Progress>) -> io::Result<()> {
    let (udp_write, mut udp_read) = udp.split();
    let (reply_tx, mut replies) = mpsc::channel::<(Vec<u8>, SocketAddr, SocketAddr)>(64);
    let mut flows = JoinSet::new();
    if cancel.is_cancelled() || network.cancelled() { return Ok(()); }
    progress.ready.store(true, Ordering::Release);
    progress.phase.send_replace(Phase::Ready);
    let outcome = AssertUnwindSafe(async { loop {
        tokio::select! { biased;
            _ = cancel.cancelled() => break Ok(()),
            _ = network.cancelled_signal() => break Ok(()),
            joined = flows.join_next(), if !flows.is_empty() => {
                if joined.is_some_and(|joined| joined.is_err()) { break Err(error("packet flow panicked")); }
            },
            packet = fd.read_packet() => {
                let packet = match packet { Ok(packet) => packet, Err(e) => break Err(e) };
                if packet.first().is_none_or(|v| !matches!(v >> 4, 4 | 6)) || (!tun.ipv6 && packet[0] >> 4 == 6) {
                    break Err(error("packet family disagrees with actual Tun plan"));
                }
                let sent = tokio::select! { biased;
                    _ = cancel.cancelled() => break Ok(()),
                    sent = stack.send(packet) => sent,
                };
                if let Err(e) = sent { break Err(e); }
                progress.incoming.fetch_add(1, Ordering::Relaxed);
            },
            packet = stack.next() => {
                let packet = match packet { Some(Ok(packet)) => packet, Some(Err(e)) => break Err(e), None => break Err(error("netstack egress closed")) };
                if !tun.ipv6 && packet.first().is_some_and(|byte| byte >> 4 == 6) { continue; }
                if let Err(e) = fd.write_packet(&packet).await { break Err(e); }
                progress.outgoing.fetch_add(1, Ordering::Relaxed);
            },
            accepted = tcp.next(), if flows.len() < 256 => {
                let Some((stream, source, target)) = accepted else { break Err(error("netstack TCP closed")); };
                let work = handler.tcp(stream, source, target);
                let context = network.clone(); let stop = cancel.clone();
                flows.spawn(async move { context.scope(async move { tokio::select! { biased;
                    _ = stop.cancelled() => Ok(()), result = work => result,
                } }).await });
            },
            packet = udp_read.recv_from(), if flows.len() < 256 => {
                let (payload, source, target) = match packet { Ok(packet) => packet, Err(e) => break Err(e) };
                let work = handler.udp(payload, source, target, tun.hijacks(target));
                let context = network.clone(); let stop = cancel.clone(); let replies = reply_tx.clone();
                flows.spawn(async move { context.scope(async move { tokio::select! { biased;
                    _ = stop.cancelled() => Ok(()),
                    result = async move {
                        if let Some(reply) = work.await? { replies.send((reply, target, source)).await.map_err(|_| error("packet reply owner closed"))?; }
                        Ok(())
                    } => result,
                } }).await });
            },
            Some((payload, source, target)) = replies.recv() => {
                if let Err(e) = udp_write.send_to(&payload, &source, &target) { break Err(e); }
            },
        }
    } }).catch_unwind().await;
    flows.abort_all();
    while flows.join_next().await.is_some() {}
    drop((tcp, udp_read, udp_write, reply_tx, replies, stack, fd));
    if cancel.is_cancelled() || network.cancelled() { return Ok(()); }
    outcome.map_err(|_| error("packet loop panicked"))?
}
