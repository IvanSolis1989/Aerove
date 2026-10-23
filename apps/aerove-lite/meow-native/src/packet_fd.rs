use std::{io, os::fd::{AsRawFd, BorrowedFd, FromRawFd, OwnedFd}};
use tokio::io::unix::AsyncFd;
use tokio_util::sync::CancellationToken;

pub struct PacketFd {
    fd: AsyncFd<OwnedFd>,
    mtu: usize,
    cancel: CancellationToken,
}

impl PacketFd {
    pub fn duplicate(caller: BorrowedFd<'_>, mtu: usize, cancel: CancellationToken) -> io::Result<Self> {
        if !(1280..=65535).contains(&mtu) {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid MTU"));
        }
        if cancel.is_cancelled() { return Err(cancelled()); }
        // dup shares file status flags: validate the Host's nonblocking FD, never change them.
        let flags = unsafe { libc::fcntl(caller.as_raw_fd(), libc::F_GETFL) };
        if flags < 0 { return Err(io::Error::last_os_error()); }
        if flags & libc::O_NONBLOCK == 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "packet FD must be nonblocking"));
        }
        let raw = unsafe { libc::fcntl(caller.as_raw_fd(), libc::F_DUPFD_CLOEXEC, 0) };
        if raw < 0 { return Err(io::Error::last_os_error()); }
        // F_DUPFD_CLOEXEC returned a new owned descriptor; caller remains borrowed.
        let owned = unsafe { OwnedFd::from_raw_fd(raw) };
        let fd = AsyncFd::new(owned)?;
        if cancel.is_cancelled() { return Err(cancelled()); }
        Ok(Self { fd, mtu, cancel })
    }

    pub fn mtu(&self) -> usize { self.mtu }

    pub async fn read_packet(&self) -> io::Result<Vec<u8>> {
        let mut packet = vec![0; 65536];
        loop {
            let mut ready = tokio::select! {
                biased;
                _ = self.cancel.cancelled() => return Err(cancelled()),
                ready = self.fd.readable() => ready?,
            };
            match ready.try_io(|fd| {
                let n = unsafe { libc::read(fd.as_raw_fd(), packet.as_mut_ptr().cast(), packet.len()) };
                if n < 0 { Err(io::Error::last_os_error()) } else { Ok(n as usize) }
            }) {
                Ok(result) => {
                    let length = result?;
                    if self.cancel.is_cancelled() { return Err(cancelled()); }
                    if length == 0 { return Err(io::Error::from(io::ErrorKind::UnexpectedEof)); }
                    if length > self.mtu {
                        return Err(io::Error::new(io::ErrorKind::InvalidData, "packet exceeds actual MTU"));
                    }
                    packet.truncate(length);
                    return Ok(packet);
                }
                Err(_) => continue,
            }
        }
    }

    pub async fn write_packet(&self, packet: &[u8]) -> io::Result<()> {
        if packet.is_empty() || packet.len() > self.mtu {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid packet length"));
        }
        loop {
            let mut ready = tokio::select! {
                biased;
                _ = self.cancel.cancelled() => return Err(cancelled()),
                ready = self.fd.writable() => ready?,
            };
            match ready.try_io(|fd| {
                let n = unsafe { libc::write(fd.as_raw_fd(), packet.as_ptr().cast(), packet.len()) };
                if n < 0 { Err(io::Error::last_os_error()) } else { Ok(n as usize) }
            }) {
                Ok(result) => {
                    let n = result?;
                    if n != packet.len() { return Err(io::Error::from(io::ErrorKind::WriteZero)); }
                    return Ok(());
                }
                Err(_) => continue,
            }
        }
    }
}

fn cancelled() -> io::Error { io::Error::new(io::ErrorKind::Interrupted, "packet owner cancelled") }
