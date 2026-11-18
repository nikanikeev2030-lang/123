#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
umask 077
key=/root/arena-android-release.p12
input=Arena-Android-1.4.2-unsigned.apk
output=Pomoshchnik-na-Arene-Hearthstone-1.4.2.apk
[[ -f "$key" ]] || { echo "Signing key not found: $key" >&2; exit 1; }
command -v apksigner >/dev/null || { echo "Install first: apt install -y apksigner" >&2; exit 1; }
sha256sum --check SHA256SUMS
# Password is requested interactively by apksigner, never passed on the command line.
apksigner sign --ks "$key" --ks-key-alias arena-android --out "$output" "$input"
apksigner verify --verbose --print-certs "$output"
sha256sum "$output" > "$output.sha256"
echo "Ready: $PWD/$output"
