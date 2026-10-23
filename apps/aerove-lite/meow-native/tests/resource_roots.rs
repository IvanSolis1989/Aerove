#![cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
use aerove_meow_core::{network_context::NetworkContext, resource_roots::ResourceRoots};
use meow_config::owned_load::{LoadContext, OwnedLoadScope};
use std::{os::unix::fs::symlink, sync::Arc};
use tokio_util::sync::CancellationToken;
#[path = "support/resource_roots.rs"]
mod fixture;
use fixture::Roots;

#[test]
fn canonical_existing_disjoint_roots_reject_aliases_escape_and_overlap() {
    let roots = Roots::new();
    let read = roots.read.to_str().unwrap(); let cache = roots.cache.to_str().unwrap();
    let captured = ResourceRoots::validate(Some(read), Some(cache)).unwrap().provider_roots().unwrap();
    assert_eq!(captured.resource_read_root.as_deref(), Some(roots.read.as_path()));
    assert_eq!(captured.cache_root, roots.cache);
    assert!(ResourceRoots::validate(None, Some(cache)).is_ok());
    assert!(ResourceRoots::validate(Some(read), None).is_err());
    let missing = roots.base.join("missing");
    for path in ["relative", "", "/", missing.to_str().unwrap()] {
        assert!(ResourceRoots::validate(Some(read), Some(path)).is_err());
    }
    assert!(ResourceRoots::validate(Some(read), Some(read)).is_err());
    assert!(ResourceRoots::validate(Some(roots.base.to_str().unwrap()), Some(cache)).is_err());
    assert!(ResourceRoots::validate(Some(read), Some(roots.base.to_str().unwrap())).is_err());
    let nested = roots.read.join("cache"); std::fs::create_dir(&nested).unwrap();
    assert!(ResourceRoots::validate(Some(read), Some(nested.to_str().unwrap())).is_err());
    let alias = roots.base.join("alias"); symlink(&roots.cache, &alias).unwrap();
    assert!(ResourceRoots::validate(Some(read), Some(alias.to_str().unwrap())).is_err());
    let child = roots.cache.join("child"); std::fs::create_dir(&child).unwrap();
    let traversal = format!("{}/child/..", roots.cache.display());
    assert!(ResourceRoots::validate(Some(read), Some(&traversal)).is_err());
    let file = roots.base.join("file"); std::fs::write(&file, b"not a directory").unwrap();
    assert!(ResourceRoots::validate(Some(read), Some(file.to_str().unwrap())).is_err());
}

#[tokio::test]
async fn captured_roots_follow_owned_blocking_context_and_never_borrow_a_successor() {
    let roots = Roots::new(); let next = Roots::new();
    let captured = Arc::new(ResourceRoots::validate(Some(roots.read.to_str().unwrap()), Some(roots.cache.to_str().unwrap())).unwrap());
    let cancel = CancellationToken::new();
    let context = NetworkContext::platform(cancel.clone()).with_resource_roots(captured);
    let scope = OwnedLoadScope::with_context(Arc::new(context.clone()));
    std::fs::write(roots.read.join("provider.yaml"), b"sealed-source").unwrap();
    let read = roots.read.clone(); let cache = roots.cache.clone();
    scope.run(async { context.run_blocking(Box::new(move || {
        let actual = OwnedLoadScope::current().unwrap().provider_roots().unwrap().unwrap();
        assert_eq!(actual.resource_read_root.as_ref(), Some(&read)); assert_eq!(actual.cache_root, cache);
        assert_eq!(std::fs::read(read.join("provider.yaml")).unwrap(), b"sealed-source");
        std::fs::write(cache.join("provider-cache.yaml"), b"mutable-cache").unwrap();
    })); }).await;
    assert_eq!(std::fs::read(roots.read.join("provider.yaml")).unwrap(), b"sealed-source");
    assert!(!roots.read.join("provider-cache.yaml").exists());
    let successor = NetworkContext::platform(CancellationToken::new()).with_resource_roots(Arc::new(ResourceRoots::validate(
        Some(next.read.to_str().unwrap()), Some(next.cache.to_str().unwrap())).unwrap()));
    cancel.cancel(); scope.close().await.unwrap();
    assert!(context.requires_provider_roots()); assert!(context.provider_roots().is_none());
    assert_eq!(successor.provider_roots().unwrap().cache_root, next.cache);
    let parent = successor.provider_roots().unwrap().cache_root;
    let moved = next.base.join("old-cache"); std::fs::rename(&parent, &moved).unwrap(); symlink(&roots.cache, &parent).unwrap();
    assert!(successor.provider_roots().is_none(), "changed root must fail, not follow symlink");
}

#[tokio::test]
async fn start_owned_rejects_missing_or_aliased_cache_before_registering_a_session() {
    let roots = Roots::new();
    let value = serde_json::json!({"schemaVersion":1,"runtimeMode":"proxy","initiallyParked":true,
        "engineYaml":"strict: true\ndns: {enable: true, nameserver: ['127.0.0.1:53']}\n",
        "privateControl":{"address":"127.0.0.1:19091","secret":"resource-root-private-secret-0000001"},
        "inbounds":{"configYaml":"mixed-port: 17891\n"},"tun":null,
        "physicalNetwork":{"handle":null,"dns":{"defaultInterface":null,"interfaces":{}}},
        "hostPolicyYaml":"{}","profileId":null,"revision":null,"resourceGeneration":null,
        "resourceReadRoot":roots.read.to_str().unwrap()});
    let failure = aerove_meow_core::Registry::start_owned(value.to_string(), None,
        NetworkContext::platform(CancellationToken::new()), None).err().unwrap();
    assert_eq!(failure.field.as_deref(), Some("cacheRootMissing"));
    let alias = roots.base.join("cache-alias"); symlink(&roots.cache, &alias).unwrap();
    let mut value = value; value["cacheRoot"] = alias.to_str().unwrap().into();
    let failure = aerove_meow_core::Registry::start_owned(value.to_string(), None,
        NetworkContext::platform(CancellationToken::new()), None).err().unwrap();
    assert_eq!(failure.field.as_deref(), Some("cacheRoot"));
    assert!(aerove_meow_core::Registry::current().is_none());
}
