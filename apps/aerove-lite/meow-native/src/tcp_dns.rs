//! Framing/time budget reference: MetaCubeX/mihomo 3f27ef39, resolver/relay.go (GPL-3.0).
use meow_tunnel::Tunnel;
use std::{io, time::Duration};
use tokio::{io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt}, time::timeout};
use tokio_util::sync::CancellationToken;

#[derive(Clone, Copy, Debug)]
pub struct Limits {
    pub read: Duration,
    pub exchange: Duration,
    pub write: Duration,
}
impl Default for Limits {
    fn default() -> Self {
        Self { read: Duration::from_secs(10), exchange: Duration::from_secs(5), write: Duration::from_secs(5) }
    }
}

pub async fn serve<S>(stream: S, tunnel: Tunnel, cancel: CancellationToken, limits: Limits) -> io::Result<()>
where S: AsyncRead + AsyncWrite + Unpin { serve_inner(stream,tunnel,None,cancel,limits).await }
pub async fn serve_scoped<S>(stream: S, tunnel: Tunnel, network: crate::network_context::NetworkContext, cancel: CancellationToken, limits: Limits) -> io::Result<()>
where S: AsyncRead + AsyncWrite + Unpin { serve_inner(stream,tunnel,Some(network),cancel,limits).await }
async fn serve_inner<S>(mut stream: S, tunnel: Tunnel, network: Option<crate::network_context::NetworkContext>, cancel: CancellationToken, limits: Limits) -> io::Result<()>
where S: AsyncRead + AsyncWrite + Unpin {
    if limits.read.is_zero() || limits.exchange.is_zero() || limits.write.is_zero() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "TCP DNS timeout must be positive"));
    }
    loop {
        let query = tokio::select! { biased;
            _ = cancel.cancelled() => return Ok(()),
            result = timeout(limits.read, read_frame(&mut stream)) =>
                result.map_err(|_| timed_out("TCP DNS frame read timeout"))??,
        };
        let Some(query) = query else { return Ok(()); };
        let captured = network.as_ref().map(|network| network.captured_with_resolver());
        let resolver = captured.as_ref().and_then(|(_,resolver)|resolver.clone()).unwrap_or_else(||tunnel.resolver());
        let query = async {
            match &captured {
                Some((network,_)) => network.scope(meow_dns::DnsServer::handle_query(&query,&resolver)).await,
                None => meow_dns::DnsServer::handle_query(&query,&resolver).await,
            }
        };
        let response = tokio::select! { biased;
            _ = cancel.cancelled() => return Ok(()),
            result = timeout(limits.exchange, query) =>
                result.map_err(|_| timed_out("TCP DNS exchange timeout"))?
                    .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, format!("TCP DNS query: {error}")))?,
        };
        if response.len() < 12 || response.len() > usize::from(u16::MAX) {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "TCP DNS response length outside 12..65535"));
        }
        tokio::select! { biased;
            _ = cancel.cancelled() => return Ok(()),
            result = timeout(limits.write, async {
                stream.write_u16(response.len() as u16).await?;
                stream.write_all(&response).await?;
                stream.flush().await
            }) => result.map_err(|_| timed_out("TCP DNS response write timeout"))??,
        }
    }
}

fn timed_out(message: &'static str) -> io::Error { io::Error::new(io::ErrorKind::TimedOut, message) }

async fn read_frame<S: AsyncRead + Unpin>(stream: &mut S) -> io::Result<Option<Vec<u8>>> {
    let mut prefix = [0; 2];
    if stream.read(&mut prefix[..1]).await? == 0 { return Ok(None); }
    stream.read_exact(&mut prefix[1..]).await?;
    let length = usize::from(u16::from_be_bytes(prefix));
    if length < 12 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "TCP DNS query length outside 12..65535"));
    }
    let mut query = vec![0; length];
    stream.read_exact(&mut query).await?;
    Ok(Some(query))
}
