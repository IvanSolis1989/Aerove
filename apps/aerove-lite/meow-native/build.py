"""Independent Meow bridge preflight: real lock, host representative chain, then Android link."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import shlex
import subprocess
import sys
import tomllib
from prepare import prepare
from build_policy import load_policy, digest, feature_boundary_command, full_native_suite
from clang_probe import probe as probe_libclang
from core_tests import execute as execute_core_tests
from verify_source import ROOT, sha, verify
import jni_abi

TARGETS={'aarch64-linux-android':('arm64-v8a',183),'x86_64-linux-android':('x86_64',62)}
# Strict descriptor tables: jni_abi checks Rust exports, Kotlin externals and verify_apk.py against them.
SCRIPT_JNI_DESCRIPTORS = {'Java_dev_aerove_lite_overrides_NativeScriptEngine_nativeBegin': '()J', 'Java_dev_aerove_lite_overrides_NativeScriptEngine_nativeEvaluate': '(Ljava/lang/String;Ljava/lang/String;J)Ljava/lang/String;', 'Java_dev_aerove_lite_overrides_NativeScriptEngine_nativeCancel': '(J)V', 'Java_dev_aerove_lite_overrides_NativeScriptEngine_nativeRelease': '(J)V'}
SCRIPT_JNI_SYMBOLS = list(SCRIPT_JNI_DESCRIPTORS)
RUNTIME_JNI_DESCRIPTORS = {'Java_dev_aerove_lite_core_MeowNativeCore_startOwned': '(Ljava/lang/String;ILdev/aerove/lite/core/SocketProtector;Ldev/aerove/lite/core/ConnectionOwnerLookup;)J', 'Java_dev_aerove_lite_core_MeowNativeCore_status': '(J)Ljava/lang/String;', 'Java_dev_aerove_lite_core_MeowNativeCore_listenerStatus': '(J)Ljava/lang/String;', 'Java_dev_aerove_lite_core_MeowNativeCore_awaitReady': '(J)V', 'Java_dev_aerove_lite_core_MeowNativeCore_stop': '(J)V', 'Java_dev_aerove_lite_core_MeowNativeCore_awaitExit': '(J)V', 'Java_dev_aerove_lite_core_MeowNativeCore_listenerCommand': '(JLjava/lang/String;I)Ljava/lang/String;', 'Java_dev_aerove_lite_core_MeowNativeCore_configurePolicies': '(JLjava/lang/String;)Ljava/lang/String;', 'Java_dev_aerove_lite_core_MeowNativeCore_policyStatus': '(J)Ljava/lang/String;', 'Java_dev_aerove_lite_core_MeowNativeCore_capabilities': '()Ljava/lang/String;', 'Java_dev_aerove_lite_core_MeowNativeCore_validate': '(JLjava/lang/String;)Ljava/lang/String;', 'Java_dev_aerove_lite_core_MeowNativeCore_inspectRuleSet': '(JLjava/lang/String;)Ljava/lang/String;', 'Java_dev_aerove_lite_core_MeowNativeCore_dumpRuleSet': '(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;', 'Java_dev_aerove_lite_core_MeowNativeCore_validateResource': '(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;', 'Java_dev_aerove_lite_core_MeowNativeCore_hostPolicyCommand': '(JLjava/lang/String;)Ljava/lang/String;'}
RUNTIME_JNI_SYMBOLS = list(RUNTIME_JNI_DESCRIPTORS)

RESOURCE_JNI_DESCRIPTORS = {'Java_dev_aerove_lite_core_JniResourceFetch_nativeCreate': '(Ljava/lang/String;)J', 'Java_dev_aerove_lite_core_JniResourceFetch_nativeLoad': '(JLjava/lang/String;)V', 'Java_dev_aerove_lite_core_JniResourceFetch_nativeDownload': '(JLjava/lang/String;)Ljava/lang/String;', 'Java_dev_aerove_lite_core_JniResourceFetch_nativeCancel': '(J)V', 'Java_dev_aerove_lite_core_JniResourceFetch_nativeCloseJoin': '(J)V', 'Java_dev_aerove_lite_core_JniResourceFetch_nativeValidatePrepared': '(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;', 'Java_dev_aerove_lite_core_JniResourceFetch_nativePreflightRuleResource': '(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;'}
RESOURCE_JNI_SYMBOLS = tuple(RESOURCE_JNI_DESCRIPTORS)
RESOURCE_HEALTH_CASES = {'resource_fetch': ['materialized_file_provider_is_the_named_download_graph_and_never_reads_old_cache', 'provider_transport_rejects_external_payload_and_unowned_dns_geodata_before_factory', 'named_candidate_graph_real_http_headers_staging_cap_and_no_bootstrap_rule_fetch', 'cancelled_named_fetch_joins_real_socket_and_never_returns_a_staged_success', 'invalid_candidate_dialer_cycle_has_no_network_or_staged_commit', 'prepared_geosite_uses_actual_owned_database_and_rejects_absent_or_external_resource', 'resource_preflight_is_typed_pending_and_standalone_remains_strict', 'resource_preflight_rejects_invalid_independent_rows_and_validates_no_geo'], 'lib': ['provider_policy_fields_defaults_disabled_and_fail_closed', 'providers_keep_distinct_policies_and_only_declared_group_touch_mapping', 'independent_initial_periodic_status_and_stop_read_back', 'lazy_only_real_group_business_touch_and_no_probe_self_touch', 'epoch_and_cancel_fence_actual_pending_health', 'live_network_context_refreshes_each_round_after_hot_network_and_probe_epoch', 'manual_intent_wins_and_member_replacement_retires_old_result', 'newer_automatic_intent_fences_pending_manual_real_response', 'configured_timeout_is_real_and_stop_joins_the_pending_socket', 'provider_health_defaults_match_standard_without_enabling_periodic_meow_default', 'prepared_validation_forwards_the_owner_token_into_geodata_reading', 'transport_load_forwards_the_fetch_token_into_dns_geodata_reading', 'a_concurrent_close_cannot_report_joined_before_the_first_closer_joins_its_children', 'zstd_geo_expansion_observes_cancellation_between_chunks_not_only_around_the_resource', 'interrupted_reads_are_retried_like_read_to_end_and_other_errors_stay_specific', 'text_rule_entries_stop_at_either_entry_bound_before_they_are_collected', 'yaml_event_pass_admits_the_value_bound_and_stops_one_value_past_it', 'streaming_text_admission_checks_the_entry_before_retaining_the_tail', 'streaming_yaml_admission_counts_events_before_the_rest_is_read', 'streaming_rule_admission_keeps_utf8_size_and_cancel_ahead_of_semantic_errors'], 'config_validation': ['zstd_geodata_streaming_matches_the_fixed_core_bounded_decoder', 'official_mrs_text_between_8m_and_64m_stays_previewable', 'official_mrs_compressed_input_between_32m_and_64m_is_admitted_and_the_64m_input_bound_holds', 'text_rule_input_up_to_the_64m_text_budget_is_admitted_and_the_input_bound_holds']}

def verify_resource_jni_source(root):
    source=(Path(root)/'src/meow_jni.rs').read_text()
    for symbol,expected in RESOURCE_JNI_DESCRIPTORS.items():
        matches=re.findall(r'#\[unsafe\(no_mangle\)\]\s*pub extern "system" fn '+re.escape(symbol)+r"<'local>\(([^)]*)\)\s*(?:->\s*([^{]+))?\s*{",source)
        if len(matches)!=1:raise ValueError('Resource JNI source export missing or ambiguous: '+symbol)
        args,result=matches[0];all_types=[part.split(':',1)[1].strip() for part in args.split(',')]
        if all_types[:2]!=["EnvUnowned<'local>","JClass<'local>"]:raise ValueError('Resource JNI source receiver mismatch: '+symbol)
        types=all_types[2:]
        table={"JString<'local>":'Ljava/lang/String;','i64':'J','':'V'}
        try:descriptor='('+''.join(table[t] for t in types)+')'+table[result.strip()]
        except KeyError:raise ValueError('Resource JNI source type unsupported: '+symbol)
        if descriptor!=expected:raise ValueError('Resource JNI source descriptor mismatch: '+symbol)
    # The admission contract calls this one source gate; it also holds every runtime and script export to its Kotlin declaration.
    jni_abi.verify(root,{**RUNTIME_JNI_DESCRIPTORS,**SCRIPT_JNI_DESCRIPTORS,**RESOURCE_JNI_DESCRIPTORS})
    return dict(RESOURCE_JNI_DESCRIPTORS)

def added_native_test_proof(output):
    # Require real libtest pass lines. Mentioning a case in stderr/source is insufficient.
    names=re.findall(r'^test (\S+) \.\.\. ok$',output,re.MULTILINE)
    for target,cases in RESOURCE_HEALTH_CASES.items():
        for name in cases:
            if sum(actual.split('::')[-1]==name for actual in names)!=1:
                raise ValueError('Missing or duplicate authored Native test pass: '+target+'::'+name)
    return dict(RESOURCE_HEALTH_CASES)


def run(command, environment, capture=False):
    return subprocess.run(command,cwd=ROOT,env=environment,check=True,text=True,stdout=subprocess.PIPE if capture else None).stdout

def android_environment(ndk,target,environment):
    ndk=Path(ndk).resolve();abi,_=TARGETS[target]
    if not re.search(r'Pkg.Revision\s*=\s*28\.2\.13676358\s*$',(ndk/'source.properties').read_text(),re.MULTILINE):
        raise ValueError('Exact NDK 28.2.13676358 required')
    compiler=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
    for name in (target+'33-clang',target+'33-clang++','llvm-ar','llvm-readelf'):
        if not (compiler/name).is_file():raise ValueError('Required Android API33 tool missing')
    toolchain=ROOT/'build'/('cmake-'+target+'.cmake');toolchain.parent.mkdir(exist_ok=True)
    toolchain.write_text(f'set(ANDROID_ABI {abi})\nset(ANDROID_PLATFORM android-33)\ninclude("{(ndk/"build/cmake/android.toolchain.cmake").as_posix()}")\n')
    sysroot=compiler.parent/'sysroot'
    if not (sysroot/'usr/include/android/api-level.h').is_file() or not (sysroot/'usr/include'/target).is_dir():
        raise ValueError('Exact NDK target system headers missing')
    bindgen_args=shlex.join(['--sysroot='+str(sysroot),'--target='+target+'33'])
    key=target.replace('-','_');result=dict(environment)
    result.update({f'CARGO_TARGET_{key.upper()}_LINKER':str(compiler/(target+'33-clang')),
        f'CARGO_TARGET_{key.upper()}_RUSTFLAGS':'-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-Bstatic -C link-arg=-lc++ -C link-arg=-Wl,-Bdynamic -C link-arg=-Wl,--no-undefined',
        f'CC_{key}':str(compiler/(target+'33-clang')),f'CXX_{key}':str(compiler/(target+'33-clang++')),
        f'AR_{key}':str(compiler/'llvm-ar'),f'CMAKE_TOOLCHAIN_FILE_{target}':str(toolchain),
        'ANDROID_NDK_HOME':str(ndk),'ANDROID_NDK_ROOT':str(ndk),'BORING_BSSL_RUST_CPPLIB':'c++_static','CMAKE_GENERATOR':'Ninja','CLANG_PATH':str(compiler/(target+'33-clang')),
        'BINDGEN_EXTRA_CLANG_ARGS_'+target:bindgen_args,'BINDGEN_EXTRA_CLANG_ARGS_'+key:bindgen_args})
    return result,compiler/'llvm-readelf'

def verify_elf(path,machine,readelf,required_symbols=(),diagnostics=None):
    header=Path(path).read_bytes()[:20]
    if len(header)!=20 or header[:6]!=b'\x7fELF\x02\x01' or int.from_bytes(header[16:18],'little')!=3 or int.from_bytes(header[18:20],'little')!=machine:
        raise ValueError('Meow bridge ELF ABI mismatch')
    output=subprocess.check_output([str(readelf),'-lW',str(path)],text=True)
    if diagnostics is not None:Path(diagnostics).write_text(output)
    loads=[line.split() for line in output.splitlines() if line.strip().startswith('LOAD ')]
    if not loads or any(int(row[-1],16)<16384 for row in loads):raise ValueError('Meow bridge lacks 16KiB PT_LOAD alignment')
    dynamic=subprocess.check_output([str(readelf),'-dW',str(path)],text=True)
    if diagnostics is not None:Path(diagnostics).write_text(output+'\n'+dynamic)
    if 'libc++_shared.so' in dynamic:raise ValueError('Bridge requires an unpackaged shared C++ runtime')
    symbols=subprocess.check_output([str(readelf),'--dyn-syms','-W',str(path)],text=True)
    if diagnostics is not None:Path(diagnostics).write_text(output+'\n'+dynamic+'\n'+symbols)
    # These C++ ABI symbols must be supplied by our static runtime, not the OS.
    bionic_cxa={'__cxa_atexit','__cxa_finalize','__cxa_thread_atexit_impl'}
    unresolved=set()
    for line in symbols.splitlines():
        row=line.split()
        if len(row)>=8 and row[4]=='GLOBAL' and row[6]=='UND':
            name=row[7].split('@',1)[0]
            if name.startswith(('__gxx','_Z','_Unwind')) or name.startswith('__cxa_') and name not in bionic_cxa:
                unresolved.add(name)
    if unresolved:raise ValueError('Meow bridge has unresolved C++ runtime symbols: '+','.join(sorted(unresolved)))
    if required_symbols:
        exported={row.split()[-1] for row in symbols.splitlines() if re.search(r'\bFUNC\s+(?:GLOBAL|WEAK)\s+(?:DEFAULT|PROTECTED)\s+(?!UND\b)\S+\s+\S+',row)}
        missing=set(required_symbols)-exported
        if missing:raise ValueError('Required script JNI symbols missing from ELF dynamic exports: '+','.join(sorted(missing)))
    return output+'\n'+dynamic+'\n'+symbols

def android_bindgen_requested(manifest):
    target=manifest.get('target',{}).get('cfg(target_os = "android")',{}).get('dependencies',{}).get('rquickjs')
    if target is None:return False
    base=manifest['dependencies'].get('rquickjs',{})
    if target.get('version')!='=0.12.2' or target.get('default-features') is not False or target.get('features')!=['bindgen'] or base.get('version')!='=0.12.2' or base.get('default-features') is not False or 'bindgen' in base.get('features',[]):
        raise ValueError('Android bindgen must remain target-only on the approved rquickjs version')
    return True


def verify_bindgen_graph(metadata, lock, manifest):
    if not android_bindgen_requested(manifest):return None
    packages={p['id']:p for p in metadata['packages']}
    systems=[p for p in packages.values() if p['name']=='rquickjs-sys' and p['version']=='0.12.2']
    if len(systems)!=1:raise ValueError('Resolved rquickjs-sys identity is ambiguous or missing')
    system=systems[0];node=next(n for n in metadata['resolve']['nodes'] if n['id']==system['id'])
    bindings=[packages[d['pkg']] for d in node['deps'] if packages[d['pkg']]['name']=='bindgen']
    if 'bindgen' not in node['features'] or not bindings:raise ValueError('Actual metadata did not activate the Android bindgen dependency')
    locked=[p for p in lock['package'] if p['name']=='rquickjs-sys' and p['version']=='0.12.2']
    if len(locked)!=1 or not any(d.split()[0]=='bindgen' for d in locked[0].get('dependencies',[])):
        raise ValueError('Actual Cargo.lock lacks the rquickjs-sys to bindgen edge')
    return dict(targetOnly=True,rquickjsSysVersion=system['version'],metadataFeatures=node['features'],bindgenVersions=[p['version'] for p in bindings],lockDependencyEdgeVerified=True)


def resolve_graph(environment, build, receipt):
    lock=ROOT/'Cargo.lock'
    previous=lock.read_bytes();receipt['metadataInputLockSha256']=digest(previous)
    if receipt.get('resolveLockRequested') and receipt['metadataInputLockSha256']!=receipt['lockSha256Before']:
        raise ValueError('Previous lock changed before explicit resolution')
    (build/'lock-before-resolution.lock').write_bytes(previous)
    receipt['metadataCommand']=['cargo','metadata','--format-version','1']
    result=subprocess.run(receipt['metadataCommand'],cwd=ROOT,env=environment,capture_output=True)
    (build/'metadata.stdout.json').write_bytes(result.stdout)
    (build/'metadata.stderr.log').write_bytes(result.stderr)
    receipt['metadataExit']=result.returncode
    if result.returncode:raise subprocess.CalledProcessError(result.returncode,receipt['metadataCommand'])
    if sha(ROOT/'Cargo.toml')!=receipt['bridgeManifestSha256']:raise ValueError('Manifest changed during explicit graph resolution')
    manifest=tomllib.loads((ROOT/'Cargo.toml').read_text())
    if android_bindgen_requested(manifest):
        receipt['androidBindgenGraph']=verify_bindgen_graph(json.loads(result.stdout),tomllib.loads(lock.read_text()),manifest)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--archive',type=Path)
    modes=parser.add_mutually_exclusive_group();modes.add_argument('--initialize-lock',action='store_true');modes.add_argument('--resolve-lock',action='store_true')
    parser.add_argument('--target',choices=TARGETS);parser.add_argument('--ndk',type=Path);args=parser.parse_args()
    policy=load_policy(ROOT)
    if full_native_suite(policy):verify_resource_jni_source(ROOT)
    if args.resolve_lock and (policy is None or not policy['resolveLock']):raise ValueError('Lock resolution requires an explicit approved build policy')
    if args.target and (args.initialize_lock or args.resolve_lock or policy is not None and policy['resolveLock'] or not args.ndk or sys.platform!='linux'):
        raise ValueError('Android link requires committed lock/policy and Linux NDK; resolution is host-only')
    upstream=prepare(args.archive,allow_archive_bootstrap=args.initialize_lock);manifest=tomllib.loads((ROOT/'Cargo.toml').read_text())
    android_bindgen_requested(manifest)
    if manifest['package']['name']!='aerove-meow-core' or manifest['lib']['name']!='aerove_meow_core':raise ValueError('Unexpected bridge crate identity')
    for name,dependency in manifest['dependencies'].items():
        if name.startswith('meow-') and (ROOT/dependency['path']).resolve()!=upstream/'crates'/name:raise ValueError('Meow dependency bypasses fixed source')
    build=ROOT/'build';build.mkdir(exist_ok=True)
    environment=dict(os.environ,CARGO_TARGET_DIR=str(ROOT/'target'),RUSTUP_TOOLCHAIN='1.98.1')
    libclang=probe_libclang(build/'libclang-probe.json',environment)
    environment['LIBCLANG_PATH']=libclang['library']['path']
    print('Actual libclang gate: '+json.dumps(libclang['library'],sort_keys=True),flush=True)
    version=run(['rustc','--version','--verbose'],environment,True)
    if not version.startswith('rustc 1.98.1 '):raise ValueError('Pinned Rust 1.98.1 required')
    lock=ROOT/'Cargo.lock';targets=policy['targets'] if policy else ['lib','proxy_runtime']
    receipt=dict(schema=1,source=json.loads((upstream/'AEROVE_SOURCE_PROVENANCE.json').read_text()),declaredSourceSpecSha256=sha(ROOT/'source.json'),bridgeManifestSha256=sha(ROOT/'Cargo.toml'),toolchain=version,
        workflowSourceSha=os.environ.get('GITHUB_SHA'),workflowRun=os.environ.get('GITHUB_RUN_ID'),workflowAttempt=os.environ.get('GITHUB_RUN_ATTEMPT'),
        bootstrapRequested=args.initialize_lock,resolveLockRequested=args.resolve_lock,buildPolicySha256=policy['policySha256'] if policy else None,
        lockPresentBefore=lock.is_file(),lockSha256Before=sha(lock) if lock.is_file() else None,
        representativeTargets=targets,androidTarget=args.target,commandExit=None,acceptedLock=False,libclang=libclang)
    command=['cargo','test','--locked','--no-fail-fast']
    for target in targets:command+=['--lib'] if target=='lib' else ['--test',target]
    command+=['--','--test-threads=1','--nocapture']
    build=ROOT/'build';build.mkdir(exist_ok=True);resolved=None
    try:
        if args.initialize_lock:
            if lock.exists():raise ValueError('Bootstrap is only permitted when the bridge lock is absent')
            shutil.copyfile(upstream/'Cargo.lock',lock)
            resolve_graph(environment,build,receipt)
        elif args.resolve_lock:
            if not lock.is_file() or receipt['lockSha256Before']!=policy['expectedLockBeforeSha256']:raise ValueError('Previous lock identity mismatch')
            resolve_graph(environment,build,receipt)
        elif not lock.is_file():raise ValueError('Bridge Cargo.lock missing; explicit CI bootstrap is required')
        resolved=sha(lock);receipt['resolvedLockSha256']=resolved
        if args.target:
            environment,readelf=android_environment(args.ndk,args.target,environment)
            receipt['ndkSourceProperties']=(args.ndk/'source.properties').read_text()
            receipt['bindgenEnvironment']={key:environment[key] for key in ['LIBCLANG_PATH','CLANG_PATH','BINDGEN_EXTRA_CLANG_ARGS_'+args.target]}
            command=['cargo','build','--locked','--release','--target',args.target]
        output=run(command,environment,capture=True) if not args.target and full_native_suite(policy) else run(command,environment)
        if not args.target and full_native_suite(policy):
            print(output,end="",flush=True)
            (build/"native-tests.stdout.log").write_text(output)
            receipt["addedNativeTests"]=added_native_test_proof(output)
        if sha(lock)!=resolved:raise ValueError('Locked build mutated Cargo.lock')
        if not args.target and feature_boundary_command(policy):
            receipt['featureBoundaryCommand']=feature_boundary_command(policy)
            receipt['featureBoundaryExit']=None
            try:
                run(receipt['featureBoundaryCommand'],environment)
                receipt['featureBoundaryExit']=0
            except BaseException as error:
                receipt['featureBoundaryExit']=getattr(error,'returncode',1);raise
            if sha(lock)!=resolved:raise ValueError('Feature boundary test mutated Cargo.lock')
        if not args.target and policy is not None:
            execute_core_tests(ROOT,policy,environment,receipt)
            if sha(lock)!=resolved:raise ValueError('Core unit tests changed the bridge Cargo.lock')
        if args.target:
            abi,machine=TARGETS[args.target];library=ROOT/'target'/args.target/'release/libaerove_meow_core.so'
            output=verify_elf(library,machine,readelf,SCRIPT_JNI_SYMBOLS+RUNTIME_JNI_SYMBOLS+list(RESOURCE_JNI_SYMBOLS),build/(abi+'-readelf.txt'));receipt['scriptJniSymbols']=SCRIPT_JNI_SYMBOLS;receipt['runtimeJniSymbols']=RUNTIME_JNI_SYMBOLS;receipt['resourceJniDescriptors']=RESOURCE_JNI_DESCRIPTORS;(build/(abi+'-readelf.txt')).write_text(output)
            destination=build/abi;destination.mkdir(exist_ok=True);shutil.copyfile(library,destination/library.name)
            receipt['output']=dict(abi=abi,file=str(destination/library.name),sha256=sha(library),bytes=library.stat().st_size,minPageAlignment=16384)
        receipt['commandExit']=0;receipt['acceptedLock']=True
    except BaseException as error:
        if isinstance(error,subprocess.CalledProcessError) and error.stdout is not None:
            print(error.stdout,end='',flush=True)
            try:
                failure_output=build/'command-failure.stdout.log';failure_output.write_text(error.stdout,encoding='utf-8',newline='')
                receipt['failureStdoutSha256']=sha(failure_output)
            except OSError as log_error:print('Could not save captured failure stdout: '+str(log_error),file=sys.stderr,flush=True)
        receipt['commandExit']=getattr(error,'returncode',1);receipt['failureType']=type(error).__name__;raise
    finally:
        receipt['command']=command;receipt['lockSha256After']=sha(lock) if lock.exists() else None
        receipt['lockUnchangedDuringLockedCommand']=resolved is not None and receipt['lockSha256After']==resolved
        (build/('host-receipt.json' if not args.target else TARGETS[args.target][0]+'-receipt.json')).write_text(json.dumps(receipt,indent=2)+'\n')
if __name__=='__main__':main()
