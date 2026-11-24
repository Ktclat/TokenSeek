#!/usr/bin/env bash
# Build the bundled, MIT-licensed DeepSeek conversation client.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
revision=cdf9617c5f265d58e0e1805635b2c10a3d41284d
upstream_version=v0.1.4
manager_version=12.6.0
mkdir -p "$root/build"
cache=${TOKENSEEK_UPSTREAM:-"$root/build/upstream.git"}

if [[ ! -d "$cache" ]]; then
    git clone --bare --depth 1 --branch "$upstream_version" \
        https://github.com/deepseek-ai/chat-demo.git "$cache"
fi
if ! git -C "$cache" cat-file -e "$revision^{commit}"; then
    echo "The chat-demo checkout must contain the pinned $upstream_version commit $revision." >&2
    exit 1
fi

source_dir=$(mktemp -d "$root/build/chat-demo.XXXXXX")
trap 'rm -rf "$source_dir"' EXIT
git -C "$cache" archive "$revision" | tar -xf - -C "$source_dir"
cd "$source_dir"
git init -q
shopt -s nullglob
for patch in "$root"/web/patches/*.patch; do
    git apply --whitespace=error "$patch"
done
if [[ -d "$root/web/overlay" ]]; then
    cp -R "$root/web/overlay/." .
fi

# Match the package manager used by the pinned upstream release.
if command -v pnpm >/dev/null && [[ "$(pnpm --version)" == "$manager_version" ]]; then
    manager=(pnpm)
else
    manager=(npx --yes --package="pnpm@$manager_version" -- pnpm)
fi
"${manager[@]}" install --frozen-lockfile
"${manager[@]}" run type
"${manager[@]}" run build

# Keep the previous bundle usable through failed downloads, patches, and compiles.
test -f dist/index.html
test -f dist/LICENSE
asset_dir="$root/app/src/main/assets"
mkdir -p "$asset_dir"
rm -rf "$asset_dir/site"
mv dist "$asset_dir/site"
