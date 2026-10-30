use std::{io::{self, Write as _}, sync::{Arc, OnceLock}, time::Duration};

use axum::body::Body;
use futures::StreamExt;
use axum::http::{HeaderMap, Method, StatusCode, Uri};
use hyper_util::rt::TokioIo;
use tokio::{io::{AsyncRead, AsyncWrite}, sync::Semaphore, time::Instant};

use super::{Hits, RouteControl, resolve, exit_from_chains, exit_through};
use meow_transport::{Transport, tls::{TlsConfig, TlsLayer}};

static SLOTS: OnceLock<Semaphore> = OnceLock::new();
pub(super) const USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

#[derive(Clone)]
pub(super) struct ProbeRequest {
    pub method: Method,
    pub url: String,
    pub headers: HeaderMap,
    pub body: Vec<u8>,
    pub timeout: Duration,
    pub max_body: usize,
    pub no_redirect: bool,
    pub strict: bool,
    pub truncate_body: bool,
    pub proof: Option<Arc<crate::control::RoutedProbeProof>>,
    pub until: Option<fn(&str) -> bool>,
}

impl ProbeRequest {
    pub fn get(url: &str, max_body: usize, timeout: Duration) -> Self {
        Self { method: Method::GET, url: url.into(), headers: HeaderMap::new(), body: Vec::new(),
            timeout, max_body, no_redirect: false, strict: false, truncate_body: false, proof: None, until: None }
    }
}

pub(super) struct ProbeResult {
    pub url: String,
    pub status: StatusCode,
    pub headers: HeaderMap,
    pub body: Vec<u8>,
    pub delay: u64,
    pub chains: Vec<String>,
    pub rule: String,
    pub rule_payload: String,
}

pub(super) async fn run_probe(control: Arc<RouteControl>, proxy: String, hits: Hits, mut request: ProbeRequest) -> io::Result<ProbeResult> {
    let _slot = tokio::time::timeout(request.timeout, SLOTS.get_or_init(|| Semaphore::new(8)).acquire()).await
        .map_err(|error| io::Error::new(io::ErrorKind::TimedOut, error))?.map_err(io::Error::other)?;
    let timeout = request.timeout;
    tokio::time::timeout(timeout, meow_common::probe_policy::DiagnosticToken::measurement().scope(async {
        let start = Instant::now();
        for redirects in 0..10 {
            let result = round_trip(control.clone(), &proxy, &hits, &request, start).await?;
            if request.no_redirect || !matches!(result.status.as_u16(), 301 | 302 | 303 | 307 | 308) {
                return Ok(result);
            }
            let Some(location) = result.headers.get(axum::http::header::LOCATION) else { return Ok(result); };
            if redirects == 9 { return Err(io::Error::other("probe redirect limit exceeded")); }
            let previous = url::Url::parse(&request.url).map_err(io::Error::other)?;
            let next = previous.join(location.to_str().map_err(io::Error::other)?).map_err(io::Error::other)?;
            if !matches!(next.scheme(), "http" | "https") || !next.username().is_empty() || next.password().is_some() {
                return Err(io::Error::other("invalid probe redirect"));
            }
            let old_host = previous.host_str().unwrap_or("");
            let next_host = next.host_str().unwrap_or("");
            if next_host != old_host && !next_host.ends_with(&format!(".{old_host}")) {
                request.headers.remove(axum::http::header::AUTHORIZATION);
                request.headers.remove(axum::http::header::COOKIE);
            }
            if matches!(result.status.as_u16(), 301 | 302 | 303) && request.method != Method::HEAD {
                request.method = Method::GET;
                request.body.clear();
            }
            request.url = next.to_string();
        }
        unreachable!()
    })).await.map_err(|error| io::Error::new(io::ErrorKind::TimedOut, error))?
}

async fn round_trip(control: Arc<RouteControl>, proxy: &str, hits: &Hits, request: &ProbeRequest, start: Instant) -> io::Result<ProbeResult> {
    if let Some(proof) = &request.proof {
        control.readback_routed_probe(proof).map_err(|_| io::Error::other("routed probe superseded"))?;
    }
    let uri: Uri = request.url.parse().map_err(io::Error::other)?;
    let host = uri.host().ok_or_else(|| io::Error::other("missing probe host"))?;
    let https = uri.scheme_str() == Some("https");
    if !https && uri.scheme_str() != Some("http") { return Err(io::Error::other("unsupported probe scheme")); }
    let port = uri.port_u16().unwrap_or(if https { 443 } else { 80 });
    let address = if host.contains(':') { format!("[{}]:{port}", host.trim_matches(['[', ']'])) } else { format!("{host}:{port}") };
    // Record before resolution/dial so failures preceding the success receipt also participate in rechecks.
    hits.record(&address, proxy, String::new());
    let resolved = resolve(&control, &address, proxy).await?;
    if let Some(proof) = &request.proof {
        control.check_routed_target(proof, &resolved.route).map_err(|_| io::Error::other("routed probe superseded"))?;
    }
    hits.record(&address, proxy, exit_through(resolved.adapter.clone(), &resolved.metadata));
    let receipt = Arc::new(meow_common::dial_outcome::DialReceipt::default());
    let mut metadata = resolved.metadata.clone();
    metadata.dial_receipt = Some(receipt.clone());
    let conn = resolved.adapter.dial_tcp_observed(&metadata).await.map_err(io::Error::other)?;
    let chains = receipt.outcome().ok_or_else(|| io::Error::other("successful dial omitted its route receipt"))?
        .chains.iter().map(ToString::to_string).collect::<Vec<_>>();
    hits.record(&address, proxy, exit_from_chains(&resolved, &chains));
    // The resolved route generation is retained through exchange, including all dialer-proxy edges.
    let conn: Box<dyn meow_transport::Stream> = Box::new(conn);
    let stream = if https {
        let policy = meow_common::request_policy::current().map_err(io::Error::other)?;
        let mut config = TlsConfig::new(host.trim_matches(['[', ']']));
        if let Some(policy) = policy { config.skip_cert_verify = !policy.check_certificate; }
        TlsLayer::new(&config).map_err(io::Error::other)?.connect(conn).await.map_err(io::Error::other)?
    } else { conn };
    let mut result = exchange(stream, uri, request, start).await?;
    if let Some(proof) = &request.proof {
        control.check_routed_target(proof, &resolved.route).map_err(|_| io::Error::other("routed probe superseded"))?;
    }
    result.chains = chains;
    result.rule = resolved.rule.clone();
    result.rule_payload = resolved.rule_payload.clone();
    drop(resolved);
    Ok(result)
}

async fn exchange<S: AsyncRead + AsyncWrite + Unpin + Send + 'static>(stream: S, uri: Uri, probe: &ProbeRequest, start: Instant) -> io::Result<ProbeResult> {
    let mut request = axum::http::Request::builder().method(probe.method.clone())
        .uri(uri.path_and_query().map(|path| path.as_str()).unwrap_or("/"))
        .header(axum::http::header::HOST, uri.authority().ok_or_else(|| io::Error::other("missing authority"))?.as_str())
        .header(axum::http::header::USER_AGENT, meow_common::request_policy::current().map_err(io::Error::other)?.as_ref().map_or(USER_AGENT, |policy| policy.global_user_agent.as_str()))
        .header(axum::http::header::ACCEPT_ENCODING, if probe.strict { "identity" } else { "gzip" })
        .header(axum::http::header::CONNECTION, "close")
        .body(Body::from(probe.body.clone())).map_err(io::Error::other)?;
    request.headers_mut().extend(probe.headers.clone());
    let (mut sender, connection) = hyper::client::conn::http1::Builder::new()
        .max_buf_size(if probe.strict { 8192 } else { 65536 }).handshake(TokioIo::new(stream)).await.map_err(io::Error::other)?;
    let read = async {
        let response = sender.send_request(request).await.map_err(io::Error::other)?;
        let delay = start.elapsed().as_millis().min(u64::MAX as u128) as u64;
        let status = response.status();
        let headers = response.headers().clone();
        let gzip = headers.get(axum::http::header::CONTENT_ENCODING).is_some_and(|value| value == "gzip");
        if probe.strict {
            if status != StatusCode::OK { return Err(io::Error::other("probe returned a non-200 status")); }
            if headers.get(axum::http::header::CONTENT_ENCODING).is_some_and(|value| value != "identity") {
                return Err(io::Error::other("encoded probe response is not supported"));
            }
            if let Some(length) = headers.get(axum::http::header::CONTENT_LENGTH) {
                let length: usize = length.to_str().map_err(io::Error::other)?.parse().map_err(io::Error::other)?;
                if length > probe.max_body { return Err(io::Error::other("probe body limit exceeded")); }
            }
        } else if headers.get(axum::http::header::CONTENT_ENCODING).is_some_and(|value| value != "identity" && value != "gzip") {
            return Err(io::Error::other("unsupported probe content encoding"));
        }
        let limit = if !probe.no_redirect && status.is_redirection() && headers.contains_key(axum::http::header::LOCATION) { 0 } else { probe.max_body };
        let mut body = Body::new(response.into_body()).into_data_stream();
        let mut wire = 0usize;
        let mut bytes = Vec::new();
        let mut decoder = (gzip && limit > 0).then(|| flate2::write::GzDecoder::new(LimitedBody { bytes: Vec::new(), limit, until: probe.until, stopped: false, truncate: probe.truncate_body }));
        while limit > 0 {
            let Some(chunk) = body.next().await else { break; };
            let chunk = chunk.map_err(io::Error::other)?;
            wire = wire.checked_add(chunk.len()).ok_or_else(|| io::Error::other("probe wire body overflow"))?;
            if !probe.truncate_body && wire > probe.max_body { return Err(io::Error::other("probe wire body limit exceeded")); }
            {
                let previous;
                let output = if let Some(decoder) = decoder.as_mut() {
                    previous = decoder.get_ref().bytes.len();
                    if !write_decoded_chunk(decoder, &chunk)? { break; }
                    &decoder.get_ref().bytes
                } else {
                    previous = bytes.len();
                    if !probe.truncate_body && chunk.len() > limit - previous { return Err(io::Error::other("probe body limit exceeded")); }
                    bytes.extend_from_slice(&chunk[..chunk.len().min(limit - previous)]);
                    &bytes
                };
                if probe.truncate_body && output.len() == limit { break; }
                if let Some(until) = probe.until
                    && until(&String::from_utf8_lossy(&output[previous.saturating_sub(256)..])) { break; }
            }
        }
        if let Some(decoder) = decoder { bytes = finish_decoded_body(decoder)?; }
        Ok(ProbeResult { url: probe.url.clone(), status, headers, body: bytes, delay, chains: Vec::new(), rule: String::new(), rule_payload: String::new() })
    };
    tokio::pin!(read, connection);
    tokio::select! {
        result = &mut read => result,
        result = &mut connection => { result.map_err(io::Error::other)?; read.await }
    }
}

struct LimitedBody { bytes: Vec<u8>, limit: usize, until: Option<fn(&str) -> bool>, stopped: bool, truncate: bool }

impl LimitedBody {
    fn completed_prefix(&self) -> bool { self.stopped && self.bytes.len() <= self.limit }
}

fn write_decoded_chunk(decoder: &mut flate2::write::GzDecoder<LimitedBody>, bytes: &[u8]) -> io::Result<bool> {
    match decoder.write_all(bytes) {
        Ok(()) => Ok(!decoder.get_ref().completed_prefix()),
        Err(_) if decoder.get_ref().completed_prefix() => Ok(false),
        Err(error) => Err(error),
    }
}

fn finish_decoded_body(mut decoder: flate2::write::GzDecoder<LimitedBody>) -> io::Result<Vec<u8>> {
    if !decoder.get_ref().completed_prefix() {
        // flate2 may flush its first decoded output only during try_finish.
        // A requested prefix is complete then too; other decode and CRC errors fail.
        match decoder.try_finish() {
            Ok(()) => {},
            Err(_) if decoder.get_ref().completed_prefix() => {},
            Err(error) => return Err(error),
        }
    }
    Ok(std::mem::take(&mut decoder.get_mut().bytes))
}

impl io::Write for LimitedBody {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        let previous = self.bytes.len();
        let remaining = self.limit.checked_sub(previous)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "probe decoded body exceeded its limit"))?;
        if bytes.is_empty() { return Ok(0); }
        if self.stopped { return Err(io::Error::other("probe scan complete")); }
        let count = bytes.len().min(remaining);
        if !self.truncate && count != bytes.len() { return Err(io::Error::other("probe decoded body limit reached")); }
        self.bytes.extend_from_slice(&bytes[..count]);
        self.stopped = (self.truncate && self.bytes.len() == self.limit) || self.until.is_some_and(|until| until(&String::from_utf8_lossy(&self.bytes[previous.saturating_sub(256)..])));
        Ok(count)
    }
    fn flush(&mut self) -> io::Result<()> { Ok(()) }
}

#[cfg(test)]
mod limited_body_tests {
    use super::*;
    use std::io::Write as _;

    fn sink(limit: usize, truncate: bool) -> LimitedBody {
        LimitedBody { bytes: Vec::new(), limit, until: None, stopped: false, truncate }
    }

    fn gzip(bytes: &[u8]) -> Vec<u8> {
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        encoder.write_all(bytes).unwrap(); encoder.finish().unwrap()
    }

    #[test]
    fn one_oversized_write_retains_exactly_the_reported_prefix_then_stops() {
        let input = vec![b'x'; 32768];
        for limit in [0, 8] {
            let mut body = sink(limit, true);
            assert_eq!(body.write(&input).unwrap(), limit);
            assert_eq!(body.bytes.as_slice(), &input[..limit]); assert!(body.completed_prefix());
            assert!(body.write(b"more").is_err()); assert_eq!(body.bytes.len(), limit);
            assert_eq!(body.write(b"").unwrap(), 0); assert_eq!(body.bytes.len(), limit);
        }
    }

    #[test]
    fn nontruncating_oversize_and_broken_invariant_fail_without_mutation() {
        let mut body = sink(8, false); body.write_all(b"ab").unwrap();
        assert!(body.write(b"0123456789").is_err()); assert_eq!(body.bytes.as_slice(), b"ab"); assert!(!body.stopped);
        let mut invalid = sink(1, true); invalid.bytes = b"invalid".to_vec(); invalid.stopped = true;
        assert_eq!(invalid.write(b"x").unwrap_err().kind(), io::ErrorKind::InvalidData);
        assert!(!invalid.completed_prefix()); assert_eq!(invalid.bytes.as_slice(), b"invalid");
    }

    #[test]
    fn one_large_real_gzip_input_chunk_caps_decoded_dump_and_drop_safely() {
        let input = vec![b'x'; 128 * 1024]; let compressed = gzip(&input);
        assert!(compressed.len() < 4096);
        let mut decoder = flate2::write::GzDecoder::new(sink(8, true));
        assert!(!write_decoded_chunk(&mut decoder, &compressed).unwrap());
        assert_eq!(decoder.get_ref().bytes.as_slice(), &input[..8]); assert!(decoder.get_ref().completed_prefix());
        assert_eq!(finish_decoded_body(decoder).unwrap(), input[..8].to_vec());
    }

    #[test]
    fn finalization_can_reach_the_requested_cap_without_turning_it_into_failure() {
        let mut decoder = flate2::write::GzDecoder::new(sink(8, true));
        assert!(write_decoded_chunk(&mut decoder, &gzip(b"abcdefghijklmnop")).unwrap());
        assert!(!decoder.get_ref().stopped);
        assert_eq!(finish_decoded_body(decoder).unwrap(), b"abcdefgh".to_vec());
    }

    #[test]
    fn an_uncapped_valid_stream_finishes_but_bad_crc_is_not_a_successful_prefix() {
        for corrupt in [false, true] {
            let mut compressed = gzip(b"complete body");
            if corrupt { let crc = compressed.len() - 8; compressed[crc] ^= 1; }
            let mut decoder = flate2::write::GzDecoder::new(sink(1024, true));
            assert!(write_decoded_chunk(&mut decoder, &compressed).unwrap());
            let result = finish_decoded_body(decoder);
            if corrupt { assert!(result.is_err()); } else { assert_eq!(result.unwrap(), b"complete body".to_vec()); }
        }
    }

    #[test]
    fn real_gzip_expansion_over_nontruncating_limit_remains_an_error() {
        let compressed = gzip(&vec![b'x'; 128 * 1024]);
        assert!(compressed.len() < 4096);
        let mut decoder = flate2::write::GzDecoder::new(sink(4096, false));
        assert!(write_decoded_chunk(&mut decoder, &compressed).is_err());
        assert!(decoder.get_ref().bytes.len() <= 4096); assert!(!decoder.get_ref().stopped);
    }

    #[test]
    fn requested_scan_marker_stops_a_real_gzip_stream_within_budget() {
        fn found(text: &str) -> bool { text.contains("scan-marker") }
        let input = [b"scan-marker".as_slice(), &vec![b'x'; 128 * 1024]].concat();
        let mut body = sink(65536, false); body.until = Some(found);
        let mut decoder = flate2::write::GzDecoder::new(body);
        assert!(!write_decoded_chunk(&mut decoder, &gzip(&input)).unwrap());
        let prefix = finish_decoded_body(decoder).unwrap();
        assert!(prefix.starts_with(b"scan-marker"));
        assert!(prefix.len() <= 65536); assert!(prefix.len() < input.len());
    }
}
