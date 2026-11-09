#!/usr/bin/env bash
# Builds libkodex_vtracer.so (the Image tracer's native core) for every shipped ABI
# and drops it into app/src/main/jniLibs/<abi>/, where Gradle packages it.
#
# Needs: Rust (rustup) with the android targets, cargo-ndk, and an Android NDK
# (ANDROID_NDK_HOME / ANDROID_NDK_ROOT, as set on GitHub's ubuntu runners).
#   rustup target add aarch64-linux-android armv7-linux-androideabi
#   cargo install cargo-ndk --locked
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs"

if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "cargo-ndk is not installed (cargo install cargo-ndk --locked)" >&2
  exit 1
fi

# 16 KB page alignment: required for Android 15+ devices and Play (targetSdk 35+).
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"

rm -rf "$OUT"
cd "$ROOT/native"
# --platform 30 matches the app's minSdk.
cargo ndk --platform 30 -t arm64-v8a -t armeabi-v7a -o "$OUT" build --release -p kodex-vtracer

ls -l "$OUT"/*/libkodex_vtracer.so
