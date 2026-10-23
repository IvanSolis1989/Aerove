use meow_config::owned_load::ProviderRoots;
use std::{fs, io, path::{Component, Path, PathBuf}};

#[derive(Clone, Debug)]
pub struct ResourceRoots { read: Option<PathBuf>, cache: PathBuf }
fn invalid(field: &'static str) -> io::Error { io::Error::new(io::ErrorKind::InvalidInput, field) }
fn directory(value: &str, field: &'static str) -> io::Result<PathBuf> {
    if value.is_empty() || value.len() > 16384 || value.contains('\0') { return Err(invalid(field)); }
    let path = Path::new(value);
    if !path.is_absolute() || path.parent().is_none()
        || path.components().any(|part| matches!(part, Component::ParentDir | Component::CurDir)) { return Err(invalid(field)); }
    let mut ancestor = PathBuf::new();
    for part in path.components() {
        ancestor.push(part.as_os_str());
        if fs::symlink_metadata(&ancestor).map_err(|_| invalid(field))?.file_type().is_symlink() { return Err(invalid(field)); }
    }
    let canonical = path.canonicalize().map_err(|_| invalid(field))?;
    if !canonical.is_dir() || canonical.as_os_str() != path.as_os_str() { return Err(invalid(field)); }
    Ok(canonical)
}
impl ResourceRoots {
    pub fn validate(read: Option<&str>, cache: Option<&str>) -> io::Result<Self> {
        let cache = directory(cache.ok_or_else(|| invalid("cacheRootMissing"))?, "cacheRoot")?;
        let read = read.map(|value| directory(value, "resourceReadRoot")).transpose()?;
        if read.as_ref().is_some_and(|read| cache.starts_with(read) || read.starts_with(&cache)) {
            return Err(invalid("resourceRootsOverlap"));
        }
        Ok(Self { read, cache })
    }
    pub fn provider_roots(&self) -> io::Result<ProviderRoots> {
        let read = self.read.as_ref().map(|path| path.to_str().ok_or_else(|| invalid("resourceReadRoot"))).transpose()?;
        let cache = self.cache.to_str().ok_or_else(|| invalid("cacheRoot"))?;
        let roots = Self::validate(read, Some(cache))?;
        Ok(ProviderRoots { resource_read_root: roots.read, cache_root: roots.cache })
    }
}
