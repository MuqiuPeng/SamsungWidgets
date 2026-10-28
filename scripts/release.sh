#!/usr/bin/env bash
# 发布新版本：先在 app/build.gradle.kts 里把 versionCode +1、改 versionName，提交后运行
#   scripts/release.sh            # 用 versionName 作为 tag，例如 v0.2.0
#   scripts/release.sh "更新说明"
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)
tag="v$version"
notes="${1:-}"

if [ ! -f keystore.properties ]; then
    echo "缺少 keystore.properties（发布签名），无法生成可覆盖升级的 APK" >&2
    exit 1
fi
if [ -n "$(git status --porcelain)" ]; then
    echo "工作区有未提交的改动，请先提交" >&2
    exit 1
fi
if gh release view "$tag" >/dev/null 2>&1; then
    echo "$tag 已经发布过，请先在 app/build.gradle.kts 里提升 versionCode / versionName" >&2
    exit 1
fi

./gradlew assembleRelease --console=plain -q
apk="app/build/outputs/apk/release/Toolbox-$tag.apk"
cp app/build/outputs/apk/release/app-release.apk "$apk"

git push origin HEAD
gh release create "$tag" "$apk" --title "$tag" --notes "${notes:-$tag}"
