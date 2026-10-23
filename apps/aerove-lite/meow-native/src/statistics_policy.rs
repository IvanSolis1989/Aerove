use meow_common::atomic::Int;
use meow_tunnel::statistics::Statistics;
use std::{io, sync::{Arc, atomic::{AtomicBool, Ordering}}};
use tokio_util::sync::CancellationToken;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TrafficView {
    pub core_epoch: u64, pub only_statistics_proxy: bool,
    pub upload_rate: Int, pub download_rate: Int, pub upload_total: Int, pub download_total: Int,
}
pub struct StatisticsPolicy {
    stats: Arc<Statistics>, generation: u64, only_proxy: AtomicBool, cancel: CancellationToken,
}
impl StatisticsPolicy {
    pub fn new(stats: Arc<Statistics>, generation: u64, only_proxy: bool, cancel: CancellationToken) -> io::Result<Self> {
        if generation == 0 || stats.is_headless() { return Err(io::Error::new(io::ErrorKind::InvalidInput,"statistics policy requires owned fully tracked engine")); }
        Ok(Self { stats, generation, only_proxy: AtomicBool::new(only_proxy), cancel })
    }
    fn active(&self) -> io::Result<()> {
        if self.cancel.is_cancelled() { Err(io::Error::new(io::ErrorKind::Interrupted,"statistics owner stopped")) } else { Ok(()) }
    }
    /// Caller must hold the existing runtime gate and supply that session's immutable generation.
    pub fn configure(&self, generation: u64, only_proxy: bool) -> io::Result<()> {
        self.active()?;
        if generation != self.generation { return Err(io::Error::new(io::ErrorKind::InvalidInput,"stale statistics generation")); }
        self.only_proxy.store(only_proxy,Ordering::Release);
        self.active()
    }
    pub fn generation(&self) -> u64 { self.generation }
    /// Native supplies the live plane stamp under its existing CAS gate.
    pub fn read(&self, current_core_epoch: u64) -> io::Result<TrafficView> {
        if current_core_epoch == 0 { return Err(io::Error::new(io::ErrorKind::InvalidInput,"invalid current core epoch")); }
        self.active()?;
        let only_statistics_proxy=self.only_proxy.load(Ordering::Acquire);
        let (upload_rate,download_rate,upload_total,download_total)=self.stats.traffic_snapshot_for(only_statistics_proxy);
        self.active()?;
        Ok(TrafficView { core_epoch:current_core_epoch,only_statistics_proxy,upload_rate,download_rate,upload_total,download_total })
    }
    pub fn totals(&self) -> io::Result<(Int,Int)> {
        self.active()?; let values=self.stats.totals_for(self.only_proxy.load(Ordering::Acquire)); self.active()?; Ok(values)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use meow_tunnel::statistics::ConnCounters;
    #[test]
    fn direct_proxy_both_directions_hot_switch_retains_live_connections_and_history() {
        let stats=Arc::new(Statistics::new()); let cancel=CancellationToken::new();
        let policy=StatisticsPolicy::new(stats.clone(),9,true,cancel.clone()).unwrap();
        let direct=ConnCounters::default(); direct.set_direct(true);
        let proxy=ConnCounters::default(); proxy.set_direct(false);
        stats.record_upload(&direct,10); stats.record_download(&direct,20);
        stats.record_upload(&proxy,30); stats.record_download(&proxy,40);
        stats.record_packet_upload_classified(None,5,true); stats.record_packet_download_classified(None,7,false);
        stats.sample_traffic();
        let only=policy.read(90).unwrap(); assert_eq!((only.upload_total,only.download_total),(30,47));
        policy.configure(9,false).unwrap();let all=policy.read(91).unwrap();assert_eq!(all.core_epoch,91); assert_eq!((all.upload_total,all.download_total),(45,67));
        // An existing direct flow keeps its classification while the visible policy changes.
        stats.record_download(&direct,3); stats.record_upload(&proxy,2); stats.sample_traffic();
        policy.configure(9,true).unwrap();let proxy_again=policy.read(92).unwrap();assert_eq!(proxy_again.core_epoch,92);
        assert_eq!((proxy_again.upload_rate,proxy_again.download_rate,proxy_again.upload_total,proxy_again.download_total),(2,0,32,47));
        assert_eq!(stats.snapshot(),(47,70)); assert!(!stats.is_headless());
        assert!(policy.configure(10,false).is_err()); cancel.cancel();
        assert!(policy.configure(9,false).is_err()); assert!(policy.read(93).is_err());
    }
    #[tokio::test]
    async fn real_duplex_relay_feeds_classified_counters_in_both_directions() {
        use tokio::io::{AsyncReadExt,AsyncWriteExt};
        let stats=Arc::new(Statistics::new());
        let counters=Arc::new(ConnCounters::default()); counters.set_direct(false);
        let (mut client,mut left)=tokio::io::duplex(64); let (mut right,mut server)=tokio::io::duplex(64);
        let up=stats.clone(); let dn=stats.clone(); let up_c=counters.clone(); let dn_c=counters.clone();
        let relay=tokio::spawn(async move { let mut a=[0u8;16]; let mut b=[0u8;16];
            meow_tunnel::relay::copy_bidirectional_buf_tracked(&mut left,&mut right,&mut a,&mut b,
                |n|up.record_upload(&up_c,n as Int),|n|dn.record_download(&dn_c,n as Int)).await.unwrap(); });
        let peer=tokio::spawn(async move { let mut payload=[0u8;3];server.read_exact(&mut payload).await.unwrap(); assert_eq!(&payload,b"abc");server.write_all(b"response").await.unwrap();server.shutdown().await.unwrap(); });
        client.write_all(b"abc").await.unwrap();client.shutdown().await.unwrap();let mut answer=Vec::new();client.read_to_end(&mut answer).await.unwrap();assert_eq!(answer,b"response");
        peer.await.unwrap();relay.await.unwrap();stats.sample_traffic();assert_eq!(stats.totals_for(true),(3,8));
    }
    #[tokio::test]
    async fn cancelling_live_guard_still_closes_direct_connection_when_proxy_view_selected() {
        use meow_tunnel::tcp::ConnectionGuard;
        let stats=Arc::new(Statistics::new());
        let guard=ConnectionGuard::track(&stats,meow_common::Metadata::default(),"MATCH".into(),"".into(),Default::default());
        guard.counters().set_direct(true);
        let id=guard.id().unwrap(); let policy=StatisticsPolicy::new(stats.clone(),1,true,CancellationToken::new()).unwrap();
        assert_eq!(stats.active_connection_count(),1); assert_eq!(stats.active_connections().len(),1);
        policy.configure(1,false).unwrap();policy.configure(1,true).unwrap();assert_eq!(stats.active_connection_count(),1);
        stats.close_connection(id);assert!(guard.run_until_closed(std::future::pending::<()>()).await.is_none());
    }
}
