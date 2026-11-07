//! Explicit diagnostic feature only. Input is a NEW controlled host fixture, never historical312.
use super::*;
use std::{
    collections::BTreeSet,
    error::Error,
    fs,
    path::{Component, Path, PathBuf},
};
use tokio::time::{Duration, timeout};

fn bounded(path: &Path, max: u64) -> Vec<u8> {
    let meta = fs::symlink_metadata(path).expect("fixture file metadata unavailable");
    assert!(
        meta.is_file() && !meta.file_type().is_symlink() && meta.len() <= max,
        "unsafe fixture file"
    );
    fs::read(path).expect("fixture file read failed")
}

fn relative(root: &Path, text: &str) -> PathBuf {
    let path = Path::new(text);
    assert!(
        !text.is_empty()
            && !text.contains('\\')
            && !text.contains(':')
            && path.components().all(|p| matches!(p, Component::Normal(_))),
        "unsafe fixture path"
    );
    let candidate = root.join(path);
    assert!(
        candidate
            .canonicalize()
            .expect("missing fixture path")
            .starts_with(root),
        "fixture escaped its owner"
    );
    candidate
}

fn private_result(root: &Path, value: &serde_json::Value) {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    let path = root.join("native-result.json");
    let mut output = fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .mode(0o600)
        .open(path)
        .expect("refuse to overwrite native result");
    output
        .write_all(serde_json::to_vec_pretty(value).unwrap().as_slice())
        .unwrap();
    output.sync_all().unwrap();
}

fn covered_tree(root: &Path, directory: &Path, names: &BTreeSet<String>) {
    assert!(directory.is_dir(), "closure directory missing");
    for entry in fs::read_dir(directory).unwrap() {
        let entry = entry.unwrap();
        let kind = entry.file_type().unwrap();
        assert!(!kind.is_symlink(), "linked closure member");
        if kind.is_dir() {
            covered_tree(root, &entry.path(), names);
        } else {
            assert!(kind.is_file(), "non-regular closure member");
            let path = entry.path();
            assert!(
                names.contains(path.strip_prefix(root).unwrap().to_str().unwrap()),
                "unlisted closure member"
            );
        }
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn fresh_shared_producer_owned_loader() {
    let path = std::env::var_os("AEROVE_FRESH_PLAN_FIXTURE")
        .expect("explicit replay requires AEROVE_FRESH_PLAN_FIXTURE");
    let manifest_path = PathBuf::from(path)
        .canonicalize()
        .expect("fixture manifest missing");
    let root = manifest_path.parent().unwrap().to_path_buf();
    let manifest: serde_json::Value =
        serde_json::from_slice(&bounded(&manifest_path, 2 * 1024 * 1024)).unwrap();
    assert!(
        manifest["schema"] == 1
            && manifest["kind"] == "fresh-shared-producer-owned-loader"
            && manifest["synthetic"] == true
            && manifest["original312Input"] == false
            && manifest["nativeAccepted"] == false,
        "unqualified replay fixture"
    );
    let execution_source = std::env::var("AEROVE_FRESH_FIXTURE_SOURCE")
        .or_else(|_| std::env::var("GITHUB_SHA"))
        .expect("replay execution source must be bound");
    assert!(
        manifest["source"].as_str() == Some(execution_source.as_str()),
        "producer and executor source differ"
    );
    let mut names = BTreeSet::new();
    let files = manifest["files"]
        .as_array()
        .expect("missing closure manifest");
    assert!(files.len() <= 8192);
    let mut total = 0u64;
    for row in files {
        let name = row["path"].as_str().unwrap();
        assert!(names.insert(name.to_owned()), "duplicate closure path");
        let bytes = bounded(&relative(&root, name), 256 * 1024 * 1024);
        total += bytes.len() as u64;
        assert!(total <= 512 * 1024 * 1024, "closure budget exceeded");
        assert!(
            row["bytes"].as_u64() == Some(bytes.len() as u64)
                && row["sha256"].as_str() == Some(crate::geo_reload::sha256(&bytes).as_str()),
            "closure identity differs"
        );
    }
    covered_tree(&root, &root.join("closure/read"), &names);
    covered_tree(&root, &root.join("closure/cache"), &names);
    for name in [
        "final-plan.json",
        "final-plan.original.json",
        "engine.yaml",
        "engine.original.yaml",
        "producer-manifest.json",
        "input/setup-wire.json",
        "input/shared-state.json",
        "input/source-proof.json",
    ] {
        assert!(names.contains(name), "incomplete producer closure");
    }
    let wire = bounded(&relative(&root, "final-plan.json"), 4 * 1024 * 1024);
    assert!(
        manifest["plan"] == "final-plan.json"
            && manifest["planSha256"].as_str() == Some(crate::geo_reload::sha256(&wire).as_str()),
        "final plan identity differs"
    );
    let original = bounded(
        &relative(&root, "final-plan.original.json"),
        4 * 1024 * 1024,
    );
    assert!(
        manifest["originalPlanSha256"].as_str()
            == Some(crate::geo_reload::sha256(&original).as_str())
    );
    let value: serde_json::Value = serde_json::from_slice(&wire).unwrap();
    let mut expected: serde_json::Value = serde_json::from_slice(&original).unwrap();
    let engine = value["engineYaml"].as_str().unwrap();
    let original_engine = expected["engineYaml"].as_str().unwrap().to_owned();
    assert!(bounded(&relative(&root, "engine.yaml"), 4 * 1024 * 1024) == engine.as_bytes());
    assert!(
        bounded(&relative(&root, "engine.original.yaml"), 4 * 1024 * 1024)
            == original_engine.as_bytes()
    );
    assert!(
        manifest["engineSha256"].as_str()
            == Some(crate::geo_reload::sha256(engine.as_bytes()).as_str())
    );
    assert!(
        manifest["originalEngineSha256"].as_str()
            == Some(crate::geo_reload::sha256(original_engine.as_bytes()).as_str())
    );
    let rebase = &manifest["pathRebase"];
    let read_to = rebase["readTo"].as_str().unwrap();
    let cache_to = rebase["cacheTo"].as_str().unwrap();
    expected["engineYaml"] = original_engine
        .replace(rebase["readFrom"].as_str().unwrap(), read_to)
        .into();
    expected["resourceReadRoot"] = read_to.into();
    expected["cacheRoot"] = cache_to.into();
    assert!(
        expected == value,
        "host adaptation changed more than declared read/cache paths"
    );
    assert!(
        value["runtimeMode"] == "proxy"
            && value["initiallyParked"] == true
            && value["tun"].is_null(),
        "replay must remain private and parked"
    );
    for path in [read_to, cache_to] {
        assert!(Path::new(path).canonicalize().unwrap().starts_with(&root));
    }
    let producer: serde_json::Value = serde_json::from_slice(&bounded(
        &relative(&root, "producer-manifest.json"),
        2 * 1024 * 1024,
    ))
    .unwrap();
    assert!(
        producer["source"] == manifest["source"]
            && producer["producer"] == "SetupAction.applyProfile/CoreHandlerInterface.setupConfig"
            && producer["synthetic"] == true
            && producer["selectedProfile"].is_null()
            && producer["acceptedDisclaimerVersion"] == 0
    );
    for row in producer["files"]
        .as_array()
        .expect("producer file identities missing")
    {
        let name = format!("input/{}", row["path"].as_str().unwrap());
        assert!(
            names.contains(&name),
            "producer input was not carried into native closure"
        );
        let bytes = bounded(&relative(&root, &name), 8 * 1024 * 1024);
        assert!(
            row["bytes"].as_u64() == Some(bytes.len() as u64)
                && row["sha256"].as_str() == Some(crate::geo_reload::sha256(&bytes).as_str()),
            "producer input binding differs"
        );
    }
    let json = String::from_utf8(wire).unwrap();
    let plan =
        RuntimePlan::parse(&json, -1, false, false).expect("host envelope rejected before loader");
    let private_address = plan.private_control.address;
    let resources = Arc::new(
        crate::resource_roots::ResourceRoots::validate(
            plan.resource_read_root.as_deref(),
            plan.cache_root.as_deref(),
        )
        .unwrap(),
    );
    let cancel = CancellationToken::new();
    let network = NetworkContext::platform(cancel.clone())
        .with_resource_roots(resources)
        .with_physical_dns_plan(plan.physical_network.dns.clone());
    let installed = Arc::new(Mutex::new(None::<Arc<OwnedRuntime>>));
    let target = installed.clone();
    let register: RegisterControl = Arc::new(|_, _| true);
    let owner = match OwnedRuntime::start(
        json,
        None,
        network,
        1,
        cancel,
        register,
        None,
        move |owner| {
            *target.lock() = Some(owner);
        },
    ) {
        Ok(owner) => owner,
        Err(error) => {
            private_result(
                &root,
                &serde_json::json!({"schema":1,"synthetic":true,"accepted":false,"stage":"start","privateCause":error.to_string()}),
            );
            panic!("host envelope start failed; inspect owned native-result.json");
        }
    };
    assert!(Arc::ptr_eq(installed.lock().as_ref().unwrap(), &owner));
    let ready = timeout(Duration::from_secs(30), owner.ready()).await;
    let ready_ok = matches!(&ready, Ok(Ok(status)) if status.state == "Running" && status.core_ready && status.api_address == Some(private_address) && !status.listening && !status.tun_ready && !status.user_listeners_ready);
    let safe_before = owner.safe_startup.lock().clone();
    owner.cancel();
    let joined = timeout(Duration::from_secs(30), owner.wait()).await;
    let joined_ok = matches!(&joined, Ok(Ok(_))) && owner.cleanup.confirmed.load(Ordering::Acquire);
    let port_released = joined_ok && TcpListener::bind(private_address).await.is_ok();
    let original = owner.original_failure();
    let mut causes = Vec::new();
    if let Some(error) = &original {
        let mut current: Option<&(dyn Error + 'static)> = Some(error.as_ref());
        for _ in 0..32 {
            let Some(cause) = current else { break };
            let text: String = cause.to_string().chars().take(4096).collect();
            let io_kind = cause
                .downcast_ref::<io::Error>()
                .map(|error| format!("{:?}", error.kind()));
            let errno = cause
                .downcast_ref::<io::Error>()
                .and_then(io::Error::raw_os_error);
            let yaml_location = cause.downcast_ref::<serde_yaml::Error>().and_then(|error| error.location())
                .map(|location| serde_json::json!({"line":location.line(),"column":location.column()}));
            causes.push(serde_json::json!({"privateText":text,"ioKind":io_kind,"errno":errno,"yamlLocation":yaml_location}));
            current = cause.source();
        }
    }
    let context = original
        .as_ref()
        .and_then(|error| crate::runtime::retained_failure_context(error))
        .cloned();
    let ready_error = match &ready {
        Ok(Err(error)) => Some(error.to_string()),
        Err(_) => Some("readyTimeout".to_owned()),
        _ => None,
    };
    private_result(
        &root,
        &serde_json::json!({"schema":1,"kind":"fresh-shared-producer-owned-loader-result","synthetic":true,"source":manifest["source"],"planSha256":manifest["planSha256"],"original312Input":false,"accepted":false,"nativeReady":ready_ok,"nativeJoined":joined_ok,"controlPortReleased":port_released,"privateReadyError":ready_error,"startupBefore":safe_before,"startupAfter":owner.safe_startup.lock().clone(),"privateContext":context,"privateCauses":causes}),
    );
    assert!(
        joined_ok,
        "native cleanup/join not confirmed; retained evidence is not acceptance"
    );
    assert!(
        port_released,
        "private control port was not released after join"
    );
    assert!(
        ready_ok,
        "real fresh host loader failed; inspect owned native-result.json, not public logs"
    );
}
