#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/.." && pwd)
native_code=$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]+([0-9]+)[[:space:]]*$/\1/p' "$repo/app/build.gradle")
[[ $native_code =~ ^[0-9]+$ ]] || { echo 'Cannot read native application versionCode' >&2; exit 1; }
legacy=${1:-"$repo"}
output=${2:-"$repo/build/TokenSeek-legacy-migration.apk"}
work=$(mktemp -d "${TMPDIR:-/tmp}/tokenseek-exporter.XXXXXXXX")
trap 'rm -rf "$work"' EXIT
cp "$legacy/settings.gradle" "$legacy/build.gradle" "$legacy/gradle.properties" "$work/"
mkdir -p "$work/app/src/main" "$(dirname "$output")"
cp -R "$here/legacy-exporter/." "$work/app/src/main/"
cat > "$work/app/build.gradle" <<'GRADLE'
plugins { id 'com.android.application' }
android {
    namespace 'io.tokenseek.app'
    compileSdk 35
    buildToolsVersion '35.0.0'
    defaultConfig {
        applicationId 'io.tokenseek.app'
        minSdk 26
        targetSdk 35
        versionCode project.property('nativeVersionCode').toInteger()
        versionName '0.9.3-migration'
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}
GRADLE
"$legacy/gradlew" -p "$work" "-PnativeVersionCode=$native_code" :app:assembleDebug
cp "$work/app/build/outputs/apk/debug/app-debug.apk" "$output"
