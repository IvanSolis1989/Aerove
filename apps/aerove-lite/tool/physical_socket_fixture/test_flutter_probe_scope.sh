#!/usr/bin/env bash
set -euo pipefail
umask 077
gradle_bin=${1:-gradle}
fixture=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
probe=${2:-"$fixture/flutter_only.init.gradle"}
temp=$(mktemp -d)
trap 'rm -rf -- "$temp"' EXIT
mkdir -p "$temp/root/app" "$temp/root/build-logic" "$temp/no-app" "$temp/home"
cat > "$temp/root/settings.gradle" <<'EOF'
rootProject.name = 'ProbeRoot'
include(':app')
includeBuild('build-logic') { name = 'fixture-logic' }
EOF
printf "rootProject.name = 'IncludedLogic'\n" > "$temp/root/build-logic/settings.gradle"
cat > "$temp/root/build-logic/build.gradle" <<'EOF'
tasks.register('childProbe') {
    doLast {
        file('child-ran').text = 'verified'
    }
}
EOF
cat > "$temp/root/app/build.gradle" <<'EOF'
tasks.register('compileFlutterBuildRelease') {
    dependsOn gradle.includedBuild('fixture-logic').task(':childProbe')
}
EOF
printf "rootProject.name = 'NoApp'\n" > "$temp/no-app/settings.gradle"
export GRADLE_USER_HOME="$temp/home"
export AEROVE_FLUTTER_PHASES="$temp/phases"
export AEROVE_FLUTTER_PROJECT_DIR="$temp/root"
: > "$AEROVE_FLUTTER_PHASES"
run() {
    timeout 120s "$gradle_bin" --offline --no-daemon --console=plain --max-workers=2 --init-script "$probe" "$@"
}
run -p "$temp/root" :app:compileFlutterBuildRelease > "$temp/build.log" 2>&1 || {
    if grep -Fq 'Missing expected Flutter app project' "$temp/build.log"; then
        echo 'Composite regression: the included build was incorrectly required to contain :app.'
    else
        echo 'Offline composite scope test did not complete.'
    fi
    exit 1
}
test "$(cat "$temp/root/build-logic/child-ran")" = verified
printf 'flutter_task_started\nflutter_task_completed\n' | cmp -s - "$AEROVE_FLUTTER_PHASES"
export AEROVE_FLUTTER_PROJECT_DIR="$temp/no-app"
: > "$AEROVE_FLUTTER_PHASES"
if run -p "$temp/no-app" help > "$temp/no-app.log" 2>&1; then
    echo 'A missing :app in the explicitly targeted root was not rejected.'
    exit 1
fi
grep -Fq 'Missing expected Flutter app project' "$temp/no-app.log"
test ! -s "$AEROVE_FLUTTER_PHASES"
echo 'Composite child, Flutter task phases and missing root :app verified.'
