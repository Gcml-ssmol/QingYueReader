#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "$(uname -s)" != "Darwin" ]; then
  echo '此脚本需要安装 Xcode 26+ 的 Mac。' >&2
  exit 1
fi
xcodebuild -version
pnpm install --frozen-lockfile
pnpm run sync
pnpm test
xcodebuild -resolvePackageDependencies -project ios/App/App.xcodeproj -scheme App
xcodebuild -project ios/App/App.xcodeproj -scheme App -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath ios/App/build/DerivedData CODE_SIGNING_ALLOWED=NO build
echo '模拟器编译通过；真机、签名和分发尚需单独验收。'
