"""Strict JNI ABI gate for the Meow bridge: Rust exports, Kotlin externals and the approved tables agree exactly.

There is no wildcard. Every JObject parameter and Kotlin reference type names its exact Java type, receivers are
checked (object member with @JvmStatic against an instance method), export sets are compared in both directions and
every Rust callback signature must belong to a declared Kotlin interface or the JDK allowlist. An internal external is
rejected because Kotlin mangles the JVM name of internal members, so JNI could not bind it by symbol. Source mode needs no
compiled classes, so build.py runs it before cargo; classfile mode repeats the comparison on compiled Kotlin output.

Usage: jni_abi.py [--classes DIR]   (DIR holds dev/aerove/lite/... classfiles from the same source tree)
"""
import importlib.util
import re
import struct
import sys
from pathlib import Path

CLASSES = {
    'MeowNativeCore': ('dev.aerove.lite.core', 'app/src/main/java/dev/aerove/lite/core/MeowNativeCore.kt', True),
    'JniResourceFetch': ('dev.aerove.lite.core', 'app/src/main/java/dev/aerove/lite/core/JniResourceFetch.kt', True),
    'NativeScriptEngine': ('dev.aerove.lite.overrides', 'app/src/main/java/dev/aerove/lite/overrides/NativeScriptEngine.kt', False),
}
RUST_SOURCES = ('src/meow_jni.rs', 'src/script_jni.rs')
CALLBACK_INTERFACES = {
    'dev/aerove/lite/core/SocketProtector': 'app/src/main/java/dev/aerove/lite/core/NativeCore.kt',
    'dev/aerove/lite/core/ConnectionOwnerLookup': 'app/src/main/java/dev/aerove/lite/core/NativeCore.kt',
}
OBJECT_PARAMETERS = {
    ('Java_dev_aerove_lite_core_MeowNativeCore_startOwned', 'protector'): 'Ldev/aerove/lite/core/SocketProtector;',
    ('Java_dev_aerove_lite_core_MeowNativeCore_startOwned', 'owner'): 'Ldev/aerove/lite/core/ConnectionOwnerLookup;',
}
JDK_CALLBACKS = {('addSuppressed', '(Ljava/lang/Throwable;)V')}
RUST_TYPES = {"JString<'local>": 'Ljava/lang/String;', 'i64': 'J', 'i32': 'I', 'bool': 'Z'}
RUST_RESULTS = dict(RUST_TYPES, **{'': 'V'})
KOTLIN_TYPES = {'String': 'Ljava/lang/String;', 'String?': 'Ljava/lang/String;', 'Long': 'J', 'Int': 'I', 'Boolean': 'Z'}
KOTLIN_RESULTS = dict(KOTLIN_TYPES, **{'': 'V', 'Unit': 'V'})
RUST_EXPORT = re.compile(r'#\[unsafe\(no_mangle\)\]\s*pub extern "system" fn (Java_\w+)<\'local>\(([^)]*)\)\s*(?:->\s*([^{]+?))?\s*\{')
KOTLIN_EXTERNAL = re.compile(r'^\s*(@JvmStatic\s+)?(?:(private|internal)\s+)?external fun (\w+)\(([^()]*)\)(?:\s*:\s*([\w?]+))?\s*(?://.*)?$')
CALLBACK_USE = re.compile(r'jni_str!\("(\w+)"\),\s*jni_sig!\("([^"]+)"\)')


def symbol(cls, name):
    package, _, _ = CLASSES[cls]
    return 'Java_' + package.replace('.', '_') + '_' + cls + '_' + name


def _callback_types(lite, problems):
    """Kotlin fun interface methods by JVM interface name, read from their declaring sources."""
    methods = {}
    for interface, relative in CALLBACK_INTERFACES.items():
        package, simple = interface.rsplit('/', 1)
        source = (lite / relative).read_text(encoding='utf-8')
        if not re.search(r'^package ' + re.escape(package.replace('/', '.')) + r'\s*$', source, re.M):
            problems.append(f'{relative}: package is not {package}')
        body = re.search(r'^fun interface ' + simple + r'\s*\{(.*?)\}', source, re.M | re.S)
        if body is None:
            problems.append(f'{relative}: fun interface {simple} not declared'); continue
        declared = re.findall(r'fun (\w+)\(([^()]*)\)(?:\s*:\s*([\w?]+))?', body.group(1))
        if len(declared) != 1: problems.append(f'{simple}: expected one abstract method'); continue
        name, params, result = declared[0]
        descriptor = _kotlin_descriptor(params, result or '', {}, f'{simple}.{name}', problems)
        methods[(name, descriptor)] = interface
    return methods


def _kotlin_descriptor(params, result, references, where, problems):
    encoded = []
    for part in [p.strip() for p in params.split(',') if p.strip()]:
        if ':' not in part or '=' in part:
            problems.append(f'{where}: parameter {part!r} must be name: Type without default'); encoded.append('?'); continue
        kind = part.split(':', 1)[1].strip()
        mapped = KOTLIN_TYPES.get(kind) or references.get(kind.rstrip('?'))
        if mapped is None: problems.append(f'{where}: Kotlin type {kind} has no strict JNI mapping'); mapped = '?'
        encoded.append(mapped)
    if result not in KOTLIN_RESULTS: problems.append(f'{where}: Kotlin result {result} has no strict JNI mapping')
    return '(' + ''.join(encoded) + ')' + KOTLIN_RESULTS.get(result, '?')


def kotlin_natives(lite, problems):
    """Every Kotlin external of the three bridge classes, as {symbol: (descriptor, static)}."""
    result = {}
    for cls, (package, relative, static) in CLASSES.items():
        source = (lite / relative).read_text(encoding='utf-8')
        if not re.search(r'^package ' + re.escape(package) + r'\s*$', source, re.M): problems.append(f'{relative}: package is not {package}')
        if not re.search(r'^(?:internal\s+)?' + ('object ' if static else 'class ') + cls + r'\b', source, re.M):
            problems.append(f'{relative}: {cls} must be declared as ' + ('an object' if static else 'a class'))
        references = {path.rsplit('/', 1)[1]: 'L' + path + ';' for path in CALLBACK_INTERFACES if path.rsplit('/', 1)[0] == package.replace('.', '/')}
        for simple in references:
            if re.search(r'^import\s+\S+(?:\.' + simple + r'|\s+as\s+' + simple + r')\s*$', source, re.M):
                problems.append(f'{relative}: import shadows callback type {simple}')
        lines = [line for line in source.splitlines() if re.search(r'\bexternal\b', line)]
        if len(re.findall(r'\bexternal\b', source)) != len(lines):
            problems.append(f'{relative}: external must appear once per declaration line')
        for line in lines:
            match = KOTLIN_EXTERNAL.fullmatch(line)
            if match is None: problems.append(f'{relative}: unsupported external declaration {line.strip()!r}'); continue
            jvm_static, visibility, name, params, kind = match.groups()
            name_symbol = symbol(cls, name)
            if visibility == 'internal': problems.append(f'{name_symbol}: internal external has a mangled JVM name and cannot bind')
            if name_symbol in result: problems.append(f'{name_symbol}: overloaded Kotlin external')
            if bool(jvm_static) != static: problems.append(f'{name_symbol}: @JvmStatic={bool(jvm_static)}, expected {static}')
            result[name_symbol] = (_kotlin_descriptor(params, kind or '', references, name_symbol, problems), bool(jvm_static))
    return result


def rust_exports(root, problems, sources=None):
    """Every Rust Java_ export of the bridge, as {symbol: (descriptor, static)}."""
    texts = sources if sources is not None else [(Path(root) / name).read_text(encoding='utf-8') for name in RUST_SOURCES]
    joined = '\n'.join(texts)
    if len(re.findall(r'\bfn Java_\w+', joined)) != len(RUST_EXPORT.findall(joined)):
        problems.append('a Rust Java_ function lacks the strict #[unsafe(no_mangle)] pub extern "system" form')
    result = {}
    for match in RUST_EXPORT.finditer(joined):
        name, args, returned = match.group(1), match.group(2), (match.group(3) or '').strip()
        if name in result: problems.append(f'{name}: multiple Rust definitions'); continue
        owner = next((cls for cls in CLASSES if name.startswith(symbol(cls, ''))), None)
        if owner is None: problems.append(f'{name}: Rust export outside the bridge classes'); continue
        static = CLASSES[owner][2]
        params = [part.strip() for part in args.split(',') if part.strip()]
        names = [part.split(':', 1)[0].replace('mut ', '').strip() for part in params]
        types = [part.split(':', 1)[1].strip() if ':' in part else '?' for part in params]
        receiver = "JClass<'local>" if static else "JObject<'local>"
        if types[:2] != ["EnvUnowned<'local>", receiver]: problems.append(f'{name}: receiver {types[:2]}, expected env + {receiver}')
        encoded = []
        for parameter, kind in zip(names[2:], types[2:]):
            if kind == "JObject<'local>":
                mapped = OBJECT_PARAMETERS.get((name, parameter))
                if mapped is None: problems.append(f'{name}.{parameter}: JObject has no strict Java type'); mapped = '?'
            else:
                mapped = RUST_TYPES.get(kind)
                if mapped is None: problems.append(f'{name}.{parameter}: unsupported Rust JNI type {kind}'); mapped = '?'
            encoded.append(mapped)
        if returned not in RUST_RESULTS: problems.append(f'{name}: unsupported Rust result {returned}')
        result[name] = ('(' + ''.join(encoded) + ')' + RUST_RESULTS.get(returned, '?'), types[1:2] == ["JClass<'local>"])
    return result


def class_methods(path):
    """(name, descriptor, native, static) for each method of one classfile; no JVM or javap needed."""
    data = Path(path).read_bytes()
    if data[:4] != b'\xca\xfe\xba\xbe': raise ValueError(f'{path}: not a classfile')
    position = 8; count = struct.unpack('>H', data[position:position + 2])[0]; position += 2
    pool = [None] * count; index = 1
    while index < count:
        tag = data[position]; position += 1
        if tag == 1:
            size = struct.unpack('>H', data[position:position + 2])[0]; pool[index] = data[position + 2:position + 2 + size].decode('utf-8', 'replace'); position += 2 + size
        elif tag in (3, 4): position += 4
        elif tag in (5, 6): position += 8; index += 1
        elif tag in (7, 8, 16, 19, 20): position += 2
        elif tag in (9, 10, 11, 12, 17, 18): position += 4
        elif tag == 15: position += 3
        else: raise ValueError(f'{path}: constant tag {tag}')
        index += 1
    position += 6; interfaces = struct.unpack('>H', data[position:position + 2])[0]; position += 2 + 2 * interfaces
    def skip_attributes(position):
        total = struct.unpack('>H', data[position:position + 2])[0]; position += 2
        for _ in range(total): position += 6 + struct.unpack('>I', data[position + 2:position + 6])[0]
        return position
    fields = struct.unpack('>H', data[position:position + 2])[0]; position += 2
    for _ in range(fields): position = skip_attributes(position + 6)
    methods = struct.unpack('>H', data[position:position + 2])[0]; position += 2; result = []
    for _ in range(methods):
        access, name, descriptor = struct.unpack('>HHH', data[position:position + 6]); position = skip_attributes(position + 6)
        result.append((pool[name], pool[descriptor], bool(access & 0x0100), bool(access & 0x0008)))
    return result


def compiled_natives(classes_dir, problems):
    result = {}
    for cls, (package, _, _) in CLASSES.items():
        for name, descriptor, native, static in class_methods(Path(classes_dir) / package.replace('.', '/') / (cls + '.class')):
            if not native: continue
            if symbol(cls, name) in result: problems.append(f'{symbol(cls, name)}: overloaded compiled native')
            result[symbol(cls, name)] = (descriptor, static)
    callbacks = {}
    for interface in CALLBACK_INTERFACES:
        for name, descriptor, _, _ in class_methods(Path(classes_dir) / (interface + '.class')): callbacks[(name, descriptor)] = interface
    return result, callbacks


def _apk_tables(lite):
    spec = importlib.util.spec_from_file_location('aerove_lite_verify_apk_jni_tables', lite / 'tool/verify_apk.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return set(module.CORE_JNI_SYMBOLS), dict(module.RESOURCE_JNI_DESCRIPTORS)


def check(root, tables, *, rust_sources=None, classes_dir=None):
    """Return problems; tables maps every approved symbol to its descriptor (the union of build.py's three tables)."""
    root = Path(root); lite = root.parent; problems = []
    rust = rust_exports(root, problems, rust_sources)
    kotlin = kotlin_natives(lite, problems)
    sides = [('Rust', rust), ('Kotlin', kotlin)]
    callbacks = _callback_types(lite, problems)
    if classes_dir is not None:
        compiled, compiled_callbacks = compiled_natives(classes_dir, problems)
        sides.append(('compiled', compiled))
        if compiled_callbacks != callbacks: problems.append(f'compiled callback interfaces differ from Kotlin source: {sorted(set(compiled_callbacks) ^ set(callbacks))}')
    approved = set(tables)
    for label, side in sides:
        for missing in sorted(approved - set(side)): problems.append(f'{label} lacks approved JNI export {missing}')
        for extra in sorted(set(side) - approved): problems.append(f'{label} has unapproved JNI export {extra}')
        for name in sorted(approved & set(side)):
            descriptor, static = side[name]
            if descriptor != tables[name]: problems.append(f'{name}: {label} {descriptor} != approved {tables[name]}')
            expected = next(CLASSES[cls][2] for cls in CLASSES if name.startswith(symbol(cls, '')))
            if static != expected: problems.append(f'{name}: {label} static={static}, expected {expected}')
    used = sorted(set(CALLBACK_USE.findall('\n'.join(rust_sources) if rust_sources is not None else
        '\n'.join((root / name).read_text(encoding='utf-8') for name in RUST_SOURCES))))
    for name, signature in used:
        if (name, signature) not in JDK_CALLBACKS and (name, signature) not in callbacks:
            problems.append(f'Rust callback {name}{signature} is not declared by a Kotlin callback interface')
    for (export, parameter), java_type in OBJECT_PARAMETERS.items():
        if java_type[1:-1] not in CALLBACK_INTERFACES: problems.append(f'{export}.{parameter}: {java_type} is not a checked callback interface')
    apk_symbols, apk_resource = _apk_tables(lite)
    if apk_symbols != approved: problems.append(f'verify_apk.py CORE_JNI_SYMBOLS differs from the approved table: {sorted(apk_symbols ^ approved)}')
    resource = {name: value for name, value in tables.items() if '_JniResourceFetch_' in name}
    if apk_resource != resource: problems.append(f'verify_apk.py RESOURCE_JNI_DESCRIPTORS differs: {sorted(set(apk_resource.items()) ^ set(resource.items()))}')
    return problems


def verify(root, tables, **options):
    problems = check(root, tables, **options)
    if problems: raise ValueError('Strict JNI ABI mismatch: ' + '; '.join(problems))
    return dict(tables)


if __name__ == '__main__':
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import build
    arguments = sys.argv[1:]
    classes = arguments[arguments.index('--classes') + 1] if '--classes' in arguments else None
    approved = {**build.RUNTIME_JNI_DESCRIPTORS, **build.SCRIPT_JNI_DESCRIPTORS, **build.RESOURCE_JNI_DESCRIPTORS}
    found = check(build.ROOT, approved, classes_dir=classes)
    print('\n'.join(found) if found else f'{len(approved)} JNI exports match Rust, Kotlin' + (' and compiled classes' if classes else ''))
    sys.exit(1 if found else 0)
