//! Commands execute in the existing runtime owner. Accepted commands survive
//! caller cancellation and are interrupted only by the owning session stop.
use std::{os::fd::{IntoRawFd, OwnedFd}, sync::{Arc, Mutex, RwLock}};
use serde::Serialize;
use tokio::sync::mpsc;
use crate::{Error, RuntimeComponents};
use super::{RuntimeMode, inbound, telemetry};

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all="camelCase")]
pub struct ListenerStatus {
    pub listening: bool,
    pub tun_ready: bool,
    pub user_listeners_ready: bool,
    pub active_ports: Vec<u16>,
    pub core_epoch: String,
    pub only_statistics_proxy: bool,
}
enum Action { Park, Proxy, Tun(OwnedFd), Read, Statistics(bool), Http(String,bool), Dns(super::dns_network::NetworkDns) }
pub(crate) struct Command { action: Action, reply: std::sync::mpsc::Sender<crate::Result<ListenerStatus>> }
pub struct RuntimeControl {
    tx: mpsc::UnboundedSender<Command>,
    receiver: Mutex<Option<mpsc::UnboundedReceiver<Command>>>,
    state: Mutex<Option<ListenerStatus>>,
}
impl RuntimeControl {
    pub fn new() -> Arc<Self> {
        let (tx,rx)=mpsc::unbounded_channel();
        Arc::new(Self {tx,receiver:Mutex::new(Some(rx)),state:Mutex::new(None)})
    }
    fn request(&self,action:Action)->crate::Result<ListenerStatus> {
        let (reply,rx)=std::sync::mpsc::channel();
        self.tx.send(Command {action,reply}).map_err(|_|Error::Operation("runtime control stopped".into()))?;
        rx.recv().map_err(|_|Error::Operation("runtime control stopped before completion".into()))?
    }
    pub fn park(&self)->crate::Result<ListenerStatus>{self.request(Action::Park)}
    pub fn resume_proxy(&self)->crate::Result<ListenerStatus>{self.request(Action::Proxy)}
    pub fn attach_tun(&self,fd:OwnedFd)->crate::Result<ListenerStatus>{self.request(Action::Tun(fd))}
    pub fn status(&self)->crate::Result<ListenerStatus>{self.request(Action::Read)}
    pub fn statistics(&self,only:bool)->crate::Result<ListenerStatus>{self.request(Action::Statistics(only))}
    pub fn http(&self,ua:String,check:bool)->crate::Result<ListenerStatus>{self.request(Action::Http(ua,check))}
    pub fn network_dns(&self,json:String)->crate::Result<ListenerStatus>{
        if json.len()>131072{return Err(super::invalid("$.aerove-system-dns: snapshot exceeds limit"));}
        let snapshot:super::dns_network::NetworkDns=serde_json::from_str(&json).map_err(|_|super::invalid("$.aerove-system-dns: invalid network snapshot"))?;snapshot.validate()?;self.request(Action::Dns(snapshot))
    }
    pub fn cached(&self)->Option<ListenerStatus>{self.state.lock().unwrap().clone()}
    pub(crate) fn running(&self,status:ListenerStatus){*self.state.lock().unwrap()=Some(status);}
    pub(crate) fn take(self:&Arc<Self>)->Receiver { Receiver { control:self.clone(),rx:self.receiver.lock().unwrap().take().expect("runtime control receiver already owned") } }
}
pub(crate) struct Receiver {control:Arc<RuntimeControl>,rx:mpsc::UnboundedReceiver<Command>}
impl Receiver {
    pub(crate) async fn recv(&mut self) -> Option<Command> { self.rx.recv().await }
    pub(crate) async fn handle(&mut self, command:Command, mode:RuntimeMode, components:Arc<RwLock<Arc<RuntimeComponents>>>, network:Arc<inbound::Owner>)->crate::Result<()> {
        let result=execute(command.action,mode,components,network).await;
        if let Ok(status)=&result { self.control.running(status.clone()); }
        let fatal=match &result {Err(Error::Operation(message)) if message.starts_with("listener operation failed:")=>Some(message.clone()),_=>None};
        let _=command.reply.send(result);
        if let Some(error)=fatal {return Err(Error::Operation(error));}
        Ok(())
    }
}
async fn execute(action:Action,mode:RuntimeMode,components:Arc<RwLock<Arc<RuntimeComponents>>>,network:Arc<inbound::Owner>)->crate::Result<ListenerStatus> {
    let _serial=network.serial.lock().await;
    let context=super::context()?;
    if context.cancel.is_cancelled(){return Err(Error::Operation("session stopped".into()));}
    let active=components.read().unwrap().clone();
    let resuming=matches!(&action,Action::Proxy|Action::Tun(_));
    match action {
        Action::Read=>{},
        Action::Dns(snapshot)=>{*context.network_dns.write().unwrap()=Some(snapshot);},
        Action::Statistics(only)=>context.telemetry.set_only_statistics_proxy(only),
        Action::Http(ua,check)=>context.telemetry.set_http_policy(super::http_policy::Policy::new(Some(&ua),check)?),
        Action::Park=>{
            if !network.is_parked() {
                let mutation=telemetry::route_mutation();
                let operation=async {
                    active.inbound_manager.mobile_park().await?;
                    active.tun_runner.mobile_park().await?;
                    active.mobile_dns_listener.mobile_park().await?;
                    network.close_connections().await?;
                    Ok::<(),Error>(())
                }.await;
                operation.map_err(|e|Error::Operation(format!("listener operation failed: {e}")))?;
                network.set_parked(true);
                telemetry::structure_changed();drop(mutation);
            }
        },
        Action::Proxy|Action::Tun(_)=>{
            let tun=match action {Action::Tun(fd) if mode==RuntimeMode::Tun=>Some(fd),Action::Proxy if mode==RuntimeMode::Proxy=>None,_=>return Err(super::invalid("runtimeMode: resume input does not match active runtime"))};
            if !network.is_parked(){return Err(super::invalid("listening: resume requires a parked runtime"));}
            let mutation=telemetry::route_mutation();
            let operation=async {
                if let Some(tun)=tun {
                    let device=unsafe {tun_rs::AsyncDevice::from_fd(tun.into_raw_fd())?};
                    active.tun_runner.mobile_attach(device).await?;
                }
                active.mobile_dns_listener.mobile_resume().await?;
                network.resume_users(&active).await?;
                Ok::<(),Error>(())
            };
            tokio::select! {
                biased;
                _=context.cancel.cancelled()=>return Err(Error::Operation("session stopped".into())),
                result=operation=>result.map_err(|e|Error::Operation(format!("listener operation failed: {e}")))?,
            }
            network.set_parked(false);
            telemetry::structure_changed();drop(mutation);
        },
    }
    let status=readback(mode,&active,&network).await?;
    if resuming&&!status.listening {return Err(Error::Operation("listener operation failed: readiness did not remain active".into()));}
    Ok(status)
}
pub(crate) async fn readback(mode:RuntimeMode,active:&RuntimeComponents,network:&inbound::Owner)->crate::Result<ListenerStatus> {
    let context=super::context()?;
    let mut changed=context.telemetry.subscribe().1;
    loop {
    let stamp=loop {
        if context.cancel.is_cancelled(){return Err(Error::Operation("session stopped".into()));}
        if let Ok(stamp)=telemetry::route_stamp(){break stamp;}
        tokio::select!{biased;_=context.cancel.cancelled()=>return Err(Error::Operation("session stopped".into())), result=changed.changed()=>result.map_err(|_|Error::Operation("route notifications closed".into()))?}
    };
    let listeners=active.inbound_manager.get_listeners().await;
    let mut ports:Vec<_>=listeners.iter().filter(|l|l.active).map(|l|l.port).collect();ports.sort_unstable();
    let user_ready=active.mobile_dns_listener.mobile_is_ready()&&!network.is_parked()&&!listeners.is_empty()&&listeners.iter().all(|l|l.active);
    let tun_ready=active.tun_runner.mobile_is_ready();
    if telemetry::route_stamp().ok()!=Some(stamp) {continue;}
    return Ok(ListenerStatus {listening:user_ready&&(mode==RuntimeMode::Proxy||tun_ready),tun_ready,user_listeners_ready:user_ready,active_ports:ports,core_epoch:stamp.0.to_string(),only_statistics_proxy:context.telemetry.only_statistics_proxy()});
    }
}
