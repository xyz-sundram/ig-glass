#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: ./build.sh [--release|--debug] [--lint] [-- GRADLE_OPTIONS...]

Build the module APK (release by default). Requires JDK 21+ and Android SDK 37.
Set JAVA_HOME if needed, and ANDROID_HOME or sdk.dir in local.properties.
ANDROID_SDK_ROOT is also accepted when ANDROID_HOME is unset.

Examples:
  ./build.sh
  ./build.sh --debug --lint
  ./build.sh --lint -- --no-daemon --max-workers=2

Release builds use the debug signing key configured in app/build.gradle.kts.
Keep the same signing key when updating an installed module.
EOF
}

variant=Release
lint=false
gradle_options=()
while (($#)); do
    case "$1" in
        --release) variant=Release ;;
        --debug) variant=Debug ;;
        --lint) lint=true ;;
        -h|--help) usage; exit 0 ;;
        --) shift; gradle_options=("$@"); break ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
    shift
done

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "$repo_dir"

java_bin=java
if [[ -n ${JAVA_HOME:-} ]]; then
    java_bin="$JAVA_HOME/bin/java"
fi
if ! java_version="$("$java_bin" -version 2>&1)"; then
    printf 'Java is unavailable. Set JAVA_HOME to a JDK 21+ installation.\n' >&2
    exit 1
fi
java_major="$(sed -nE 's/.*version "([0-9]+).*/\1/p' <<< "$java_version" | head -n 1)"
if [[ ! $java_major =~ ^[0-9]+$ ]] || ((java_major < 21)); then
    printf 'JDK 21+ is required. Set JAVA_HOME to the correct JDK.\n%s\n' "$java_version" >&2
    exit 1
fi

if [[ -z ${ANDROID_HOME:-} && -n ${ANDROID_SDK_ROOT:-} ]]; then
    export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi
if [[ -n ${ANDROID_HOME:-} ]]; then
    if [[ ! -d $ANDROID_HOME ]]; then
        printf 'Android SDK directory does not exist: %s\n' "$ANDROID_HOME" >&2
        exit 1
    fi
elif [[ ! -f local.properties ]] || ! grep -qE '^sdk\.dir[[:space:]]*=' local.properties; then
    printf 'Set ANDROID_HOME to your Android SDK, or set sdk.dir in local.properties.\n' >&2
    exit 1
fi

tasks=(":app:assemble$variant")
if $lint; then
    tasks+=(":app:lint$variant")
fi
bash ./gradlew "${tasks[@]}" "${gradle_options[@]}"

variant_dir=release
if [[ $variant == Debug ]]; then
    variant_dir=debug
fi
apk="$repo_dir/app/build/outputs/apk/$variant_dir/app-$variant_dir.apk"
if [[ ! -s $apk ]]; then
    printf 'Build finished, but the expected APK is missing: %s\n' "$apk" >&2
    exit 1
fi
printf '\nModule APK: %s\n' "$apk"
