//! Production HTTP framing regressions with synthetic loopback resources only.
use std::time::Duration;
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::TcpListener};

async fn fetch_parts(parts: Vec<Vec<u8>>, keep_open: bool, limit: usize) -> Result<Vec<u8>, String> {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut request = Vec::new();
        while !request.ends_with(b"\r\n\r\n") {
            request.push(socket.read_u8().await.unwrap());
            assert!(request.len() < 65536);
        }
        for part in parts {
            if socket.write_all(&part).await.is_err() { return; }
            tokio::time::sleep(Duration::from_millis(2)).await;
        }
        if keep_open { std::future::pending::<()>().await; }
    });
    let result = tokio::time::timeout(Duration::from_secs(2),
        meow_config::internal_http::fetch_capped(&format!("http://{address}/synthetic-rule"), None, &[], limit))
        .await.map_err(|_| "test response deadline".to_string())
        .and_then(|result| result.map(|response| response.body).map_err(|error| error.to_string()));
    server.abort(); let _ = server.await;
    result
}
async fn fetch(response: &[u8], keep_open: bool, limit: usize) -> Result<Vec<u8>, String> {
    fetch_parts(vec![response.to_vec()], keep_open, limit).await
}
#[tokio::test]
async fn content_length_finishes_without_peer_eof() {
    assert_eq!(fetch(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok", true, 64).await.unwrap(), b"ok");
}
#[tokio::test]
async fn chunked_trailers_finish_without_peer_eof() {
    assert_eq!(fetch(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2;x=y\r\nok\r\n0\r\nX-Proof: yes\r\n\r\n", true, 64).await.unwrap(), b"ok");
}
#[tokio::test]
async fn fragmented_headers_chunks_and_trailers_finish() {
    let wire = b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nok\r\n1\r\n!\r\n0\r\nX-Proof: yes\r\n\r\n";
    assert_eq!(fetch_parts(wire.chunks(3).map(<[u8]>::to_vec).collect(), true, 64).await.unwrap(), b"ok!");
}
#[tokio::test]
async fn transfer_encoding_takes_priority_over_matching_content_length() {
    assert_eq!(fetch(b"HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nok\r\n0\r\n\r\n", true, 64).await.unwrap(), b"ok");
}
#[tokio::test]
async fn duplicate_content_lengths_still_reject_conflicts() {
    let error = fetch(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\nok", true, 64).await.unwrap_err();
    assert!(error.contains("conflicting Content-Length"), "{error}");
    assert_eq!(fetch(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\nok", true, 64).await.unwrap(), b"ok");
}
#[tokio::test]
async fn truncated_content_length_and_chunk_data_are_not_success() {
    assert!(fetch(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nok", false, 64).await.unwrap_err().contains("Content-Length"));
    assert!(fetch(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nok", false, 64).await.is_err());
    assert!(fetch(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nok\r\n0\r\nX-Proof: yes\r\n", false, 64).await.is_err());
}
#[tokio::test]
async fn body_and_header_limits_remain_enforced() {
    assert!(fetch(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nok!", true, 2).await.unwrap_err().contains("exceeds"));
    assert!(fetch(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nok!\r\n0\r\n\r\n", true, 2).await.unwrap_err().contains("exceeds"));
    let mut huge = b"HTTP/1.1 200 OK\r\nX-Huge: ".to_vec();
    huge.extend(vec![b'x'; 65536]); huge.extend_from_slice(b"\r\nContent-Length: 2\r\n\r\nok");
    assert!(fetch(&huge, true, 64).await.unwrap_err().contains("exceed"));
}
#[tokio::test]
async fn unframed_response_requires_eof_and_preserves_body() {
    assert_eq!(fetch(b"HTTP/1.1 200 OK\r\n\r\nok", false, 64).await.unwrap(), b"ok");
    assert!(fetch(b"HTTP/1.1 200 OK\r\n\r\nok", true, 64).await.is_err());
}
#[cfg(unix)]
#[tokio::test]
async fn completed_body_survives_later_peer_reset() {
    use std::os::fd::AsRawFd;
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut request = Vec::new();
        while !request.ends_with(b"\r\n\r\n") { request.push(socket.read_u8().await.unwrap()); }
        socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok").await.unwrap();
        tokio::time::sleep(Duration::from_millis(50)).await;
        let socket = socket.into_std().unwrap();
        let linger = libc::linger { l_onoff: 1, l_linger: 0 };
        // SAFETY: valid socket fd and a correctly sized initialized linger value.
        assert_eq!(unsafe { libc::setsockopt(socket.as_raw_fd(), libc::SOL_SOCKET, libc::SO_LINGER,
            &linger as *const _ as *const libc::c_void, std::mem::size_of_val(&linger) as libc::socklen_t) }, 0);
    });
    let result = tokio::time::timeout(Duration::from_secs(2), meow_config::internal_http::fetch_direct(&format!("http://{address}/body"))).await;
    server.await.unwrap();
    assert_eq!(result.unwrap().unwrap(), b"ok");
}
