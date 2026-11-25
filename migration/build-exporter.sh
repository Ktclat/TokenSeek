#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/.." && pwd)
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
        versionCode 13
        versionName '0.9.3-migration'
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}
GRADLE
"$legacy/gradlew" -p "$work" :app:assembleDebug
cp "$work/app/build/outputs/apk/debug/app-debug.apk" "$output"
