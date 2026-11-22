#!/usr/bin/env sh
# SPDX-License-Identifier: GPL-3.0-or-later
set -eu

REPO="${NECTARLINK_REPO:-thtbee/nectarlink}"
TRUSTED_CERT_SHA256="c7c09a125ca5320b1670e710e798486de08b318127243b2790f3ff13511924f1"
SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
CHANNEL="stable"
DEST_DIR="${SCRIPT_DIR}/prebuilt"
VERIFY_CERT="true"

usage() {
  cat <<EOF
Usage: $(basename "$0") [--channel stable|nightly|canary] [--dest DIR] [--no-verify-cert]

Downloads a signed NectarLink client APK from ${REPO} releases and stages
Nectarlink.apk + Android.bp under DIR (default: ${SCRIPT_DIR}/prebuilt)
so AOSP builds can include Nectarlink in PRODUCT_PACKAGES.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --channel)
      [ "$#" -ge 2 ] || { echo "Missing value for --channel" >&2; exit 1; }
      CHANNEL="$2"
      shift 2
      ;;
    --dest)
      [ "$#" -ge 2 ] || { echo "Missing value for --dest" >&2; exit 1; }
      DEST_DIR="$2"
      shift 2
      ;;
    --no-verify-cert)
      VERIFY_CERT="false"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

case "$CHANNEL" in
  stable|nightly|canary) ;;
  *)
    echo "Unsupported channel '$CHANNEL' (expected stable, nightly, or canary)" >&2
    exit 1
    ;;
esac

mkdir -p "$DEST_DIR"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM

curl_gh() {
  url="$1"
  out="$2"
  token="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
  if [ -n "$token" ]; then
    curl -fsSL -H "Accept: application/vnd.github+json" -H "Authorization: Bearer $token" "$url" -o "$out"
  else
    curl -fsSL -H "Accept: application/vnd.github+json" "$url" -o "$out"
  fi
}

if [ "$CHANNEL" = "stable" ]; then
  API_URL="https://api.github.com/repos/${REPO}/releases/latest"
elif [ "$CHANNEL" = "canary" ]; then
  API_URL="https://api.github.com/repos/${REPO}/releases/tags/canary"
else
  API_URL="https://api.github.com/repos/${REPO}/releases?per_page=100"
fi

RELEASES_JSON="${TMP_DIR}/releases.json"
curl_gh "$API_URL" "$RELEASES_JSON"

META="$(python3 - "$RELEASES_JSON" "$CHANNEL" <<'PY'
import json
import re
import sys

with open(sys.argv[1], "r", encoding="utf-8") as fh:
    payload = json.load(fh)

channel = sys.argv[2]
releases = payload if isinstance(payload, list) else [payload]

target = None
for rel in releases:
    if rel.get("draft"):
        continue
    tag = rel.get("tag_name", "")
    if channel == "nightly" and rel.get("prerelease") and re.match(r"^v[0-9]+\.[0-9]+\.[0-9]+-nightly\.[0-9]+$", tag):
        target = rel
        break
    if channel in ("stable", "canary"):
        target = rel
        break

if not target:
    sys.stderr.write(f"No release found for channel '{channel}'\n")
    sys.exit(1)

apk_asset = None
sums_asset = None
for asset in target.get("assets", []):
    name = asset.get("name", "")
    if re.match(r"^Nectarlink-.*-android\.apk$", name):
        apk_asset = asset
    elif name == "SHA256SUMS.txt":
        sums_asset = asset

if not apk_asset:
    sys.stderr.write(f"No Android APK asset found in release {target.get('tag_name')}\n")
    sys.exit(1)

sums_url = sums_asset["browser_download_url"] if sums_asset else ""
print(target["tag_name"])
print(apk_asset["name"])
print(apk_asset["browser_download_url"])
print(sums_url)
PY
)"

TAG_NAME="$(printf '%s\n' "$META" | sed -n '1p')"
ASSET_NAME="$(printf '%s\n' "$META" | sed -n '2p')"
APK_URL="$(printf '%s\n' "$META" | sed -n '3p')"
SUMS_URL="$(printf '%s\n' "$META" | sed -n '4p')"

echo "Resolved ${REPO} (${CHANNEL}): ${TAG_NAME} -> ${ASSET_NAME}"

APK_TMP="${TMP_DIR}/${ASSET_NAME}"
curl -fsSL "$APK_URL" -o "$APK_TMP"

if [ -n "$SUMS_URL" ]; then
  SUMS_TMP="${TMP_DIR}/SHA256SUMS.txt"
  curl -fsSL "$SUMS_URL" -o "$SUMS_TMP"
  python3 - "$APK_TMP" "$ASSET_NAME" "$SUMS_TMP" <<'PY'
import hashlib
import sys

apk_path, asset_name, sums_path = sys.argv[1:4]
expected = None
with open(sums_path, "r", encoding="utf-8") as fh:
    for line in fh:
        parts = line.strip().split()
        if len(parts) >= 2 and parts[-1].lstrip("*") == asset_name:
            expected = parts[0].lower()
            break

if not expected:
    sys.stderr.write(f"{asset_name} not listed in SHA256SUMS.txt\n")
    sys.exit(1)

h = hashlib.sha256()
with open(apk_path, "rb") as fh:
    for chunk in iter(lambda: fh.read(65536), b""):
        h.update(chunk)

actual = h.hexdigest().lower()
if actual != expected:
    sys.stderr.write(f"SHA-256 mismatch for {asset_name}: expected {expected}, got {actual}\n")
    sys.exit(1)
print(f"Verified SHA-256: {actual}")
PY
fi

if [ "$VERIFY_CERT" = "true" ]; then
  python3 - "$APK_TMP" "$TRUSTED_CERT_SHA256" <<'PY'
import hashlib
import struct
import sys

apk_path, expected_cert = sys.argv[1:3]
with open(apk_path, "rb") as fh:
    data = fh.read()

eocd = data.rfind(b"PK\x05\x06")
if eocd < 24:
    sys.stderr.write("Invalid APK: EOCD not found\n")
    sys.exit(1)

cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
if cd_offset < 24 or data[cd_offset - 16:cd_offset] != b"APK Sig Block 42":
    sys.stderr.write("APK Signing Block v2/v3 not present\n")
    sys.exit(1)

blk_size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
pos = cd_offset - blk_size - 8 + 8
end = cd_offset - 24

def lp(buf, off):
    length = struct.unpack_from("<I", buf, off)[0]
    return buf[off + 4:off + 4 + length], off + 4 + length

certs_found = []
while pos < end:
    pair_len = struct.unpack_from("<Q", data, pos)[0]
    pair_id = struct.unpack_from("<I", data, pos + 8)[0]
    if pair_id in (0x7109871a, 0xf05368c0):
        val = data[pos + 12:pos + 8 + pair_len]
        signers, _ = lp(val, 0)
        signer, _ = lp(signers, 0)
        signed_data, _ = lp(signer, 0)
        _, off2 = lp(signed_data, 0)
        certs, _ = lp(signed_data, off2)
        cert, _ = lp(certs, 0)
        certs_found.append(hashlib.sha256(cert).hexdigest().lower())
    pos += 8 + pair_len

if expected_cert.lower() not in certs_found:
    sys.stderr.write(f"Signing cert mismatch: expected {expected_cert}, found {certs_found}\n")
    sys.exit(1)
print(f"Verified signing cert SHA-256: {expected_cert}")
PY
fi

mv "$APK_TMP" "${DEST_DIR}/Nectarlink.apk"
chmod 0644 "${DEST_DIR}/Nectarlink.apk"

cat > "${DEST_DIR}/Android.bp" <<EOF
// SPDX-License-Identifier: GPL-3.0-or-later
// Generated by aosp/fetch-client-apk.sh for ${REPO} (${CHANNEL}: ${TAG_NAME})

android_app_import {
    name: "Nectarlink",
    apk: "Nectarlink.apk",
    presigned: true,
    product_specific: true,
    dex_preopt: {
        enabled: false,
    },
    required: [
        "default-permissions-app.nectarlink.xml",
        "sysconfig-app.nectarlink.xml",
    ],
}
EOF

echo "Staged ${DEST_DIR}/Nectarlink.apk and ${DEST_DIR}/Android.bp"
