use std::{path::PathBuf, sync::atomic::{AtomicU64, Ordering}};
static NEXT: AtomicU64 = AtomicU64::new(0);
pub struct Roots { pub base: PathBuf, pub read: PathBuf, pub cache: PathBuf }
impl Roots {
    pub fn new() -> Self {
        let temporary = std::env::temp_dir().canonicalize().unwrap();
        let base = temporary.join(format!("aerove-meow-roots-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
        std::fs::create_dir(&base).unwrap();
        let read = base.join("sealed-bundle"); let cache = base.join("session-cache");
        std::fs::create_dir(&read).unwrap(); std::fs::create_dir(&cache).unwrap();
        Self { base, read, cache }
    }
}
impl Drop for Roots { fn drop(&mut self) { std::fs::remove_dir_all(&self.base).unwrap(); } }
