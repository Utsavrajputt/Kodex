#!/usr/bin/env bash
# Signs every unsigned release APK with apksigner.
#
#   usage: sign-apks.sh [--require-secrets]
#
# Reads from the environment: KEYSTORE_BASE64, KEY_ALIAS, KEYSTORE_PASSWORD, KEY_PASSWORD.
# Input : app/build/outputs/apk/release/app-<abi>-release-unsigned.apk
# Output: app/build/outputs/apk/signed/kodex-<abi>.apk   (...-unsigned.apk if unsigned)
#
# Without --require-secrets (normal CI builds, fork PRs) missing secrets just leave the APKs
# unsigned. With it (release / pre-release) any missing secret fails the job.
set -euo pipefail

require=false
[[ "${1:-}" == "--require-secrets" ]] && require=true

in_dirs=(app/build/outputs/apk/release)
out_dir=app/build/outputs/apk/signed
mkdir -p "$out_dir"

shopt -s nullglob
unsigned=()
for d in "${in_dirs[@]}"; do unsigned+=("$d"/*-unsigned.apk); done
if [[ ${#unsigned[@]} -eq 0 ]]; then
  echo "Missing expected unsigned release APKs in ${in_dirs[*]}" >&2
  find app/build/outputs/apk -type f -name '*.apk' -print || true
  exit 1
fi

# app-arm64-v8a-release-unsigned.apk -> arm64-v8a ; app-universal-... -> universal
abi_of() { basename "$1" | sed -E 's/^app-(.*)-release-unsigned\.apk$/\1/'; }

if [[ -z "${KEYSTORE_BASE64:-}" ]]; then
  if $require; then
    echo "Signing secrets are required here but KEYSTORE_BASE64 is not set." >&2
    exit 1
  fi
  echo "KEYSTORE_BASE64 is not set (expected for fork PRs); leaving APKs unsigned."
  for apk in "${unsigned[@]}"; do
    cp "$apk" "$out_dir/kodex-$(abi_of "$apk")-unsigned.apk"
  done
  ls -l "$out_dir"
  exit 0
fi

if [[ -z "${KEY_ALIAS:-}" || -z "${KEYSTORE_PASSWORD:-}" || -z "${KEY_PASSWORD:-}" ]]; then
  echo "KEYSTORE_BASE64 is set but KEY_ALIAS / KEYSTORE_PASSWORD / KEY_PASSWORD are missing." >&2
  exit 1
fi

build_tools_dir=$(ls -1 "$ANDROID_HOME/build-tools" | sort -V | tail -n1)
apksigner="$ANDROID_HOME/build-tools/$build_tools_dir/apksigner"
test -x "$apksigner"

echo "$KEYSTORE_BASE64" | base64 -d > release.jks
trap 'rm -f release.jks' EXIT

for apk in "${unsigned[@]}"; do
  signed="$out_dir/kodex-$(abi_of "$apk").apk"
  "$apksigner" sign \
    --ks release.jks \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEYSTORE_PASSWORD" \
    --key-pass "pass:$KEY_PASSWORD" \
    --v4-signing-enabled false \
    --out "$signed" \
    "$apk"
  "$apksigner" verify --verbose --print-certs "$signed"
done
ls -l "$out_dir"
