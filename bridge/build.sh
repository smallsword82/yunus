#!/usr/bin/env bash
# Build the HAL bridge to a dex. Needs JDK 17 and Android build-tools' d8.
# Override d8 location with: D8=/path/to/d8 ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build && mkdir -p build

javac -source 11 -target 11 -XDstringConcat=inline -d build YunusHalBridge.java

D8="${D8:-}"
if [ -z "$D8" ]; then
  for base in "${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}" "${ANDROID_HOME:-}"; do
    [ -n "$base" ] || continue
    cand="$(ls -d "$base"/build-tools/*/d8 2>/dev/null | sort -V | tail -1 || true)"
    [ -n "$cand" ] && D8="$cand" && break
  done
fi
if [ -z "$D8" ] || [ ! -x "$D8" ]; then
  echo "d8 not found. Set D8=/path/to/build-tools/<ver>/d8 and re-run." >&2
  exit 1
fi

"$D8" --output build build/*.class
echo "Built: build/classes.dex"
