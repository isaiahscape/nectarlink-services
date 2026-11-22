#!/usr/bin/env sh
# SPDX-License-Identifier: GPL-3.0-or-later
set -eu

REPO="${NECTARLINK_REPO:-thtbee/nectarlink}"
SERVICE_REPO="${NECTARLINK_SERVICES_MODULE_REPO:-isaiahscape/nectarlink-module}"
TRUSTED_CLIENT_CERTS="c7c09a125ca5320b1670e710e798486de08b318127243b2790f3ff13511924f1"
TRUSTED_SERVICE_CERTS="e055b7e6a622168da36e7b9f730369c8f140e5ba87df1e16f31fd754434eea0d,c7c09a125ca5320b1670e710e798486de08b318127243b2790f3ff13511924f1"
SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
CHANNEL="stable"
DEST_DIR="${SCRIPT_DIR}/prebuilt"
VERIFY_CERT="true"
WITH_SERVICE="false"
SERVICE_APK=""

usage() {
  cat <<EOF
Usage: $(basename "$0") [--channel stable|nightly|canary] [--with-service] [--service-apk PATH] [--service-repo OWNER/REPO] [--dest DIR] [--no-verify-cert]

Downloads a signed NectarLink client APK from ${REPO} releases and stages
Nectarlink.apk + Android.bp under DIR (default: ${SCRIPT_DIR}/prebuilt)
so AOSP builds can include Nectarlink in PRODUCT_PACKAGES.

With --with-service (or --service-apk), also stages NectarlinkServicesPrebuilt
(presigned with an upstream-trusted certificate in TRUSTED_SERVICE_CERT_SHA256)
for ROMs bundling unmodified ${REPO} nightly/stable/canary APKs.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --channel)
      [ "$#" -ge 2 ] || { echo "Missing value for --channel" >&2; exit 1; }
      CHANNEL="$2"
      shift 2
      ;;
    --with-service)
      WITH_SERVICE="true"
      shift
      ;;
    --service-apk)
      [ "$#" -ge 2 ] || { echo "Missing value for --service-apk" >&2; exit 1; }
      WITH_SERVICE="true"
      SERVICE_APK="$2"
      shift 2
      ;;
    --service-repo)
      [ "$#" -ge 2 ] || { echo "Missing value for --service-repo" >&2; exit 1; }
      SERVICE_REPO="$2"
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

verify_sha256() {
  apk_path="$1"
  asset_name="$2"
  sums_path="$3"
  python3 - "$apk_path" "$asset_name" "$sums_path" <<'PY'
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
print(f"Verified SHA-256 ({asset_name}): {actual}")
PY
}

verify_cert() {
  apk_path="$1"
  allowed_csv="$2"
  python3 - "$apk_path" "$allowed_csv" <<'PY'
import hashlib
import struct
import sys

apk_path, allowed_csv = sys.argv[1:3]
allowed = {c.strip().lower() for c in allowed_csv.split(",") if c.strip()}
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

matched = [c for c in certs_found if c in allowed]
if not matched:
    sys.stderr.write(f"Signing cert mismatch: expected one of {sorted(allowed)}, found {certs_found}\n")
    sys.exit(1)
print(f"Verified signing cert SHA-256: {matched[0]}")
PY
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
  verify_sha256 "$APK_TMP" "$ASSET_NAME" "$SUMS_TMP"
fi

if [ "$VERIFY_CERT" = "true" ]; then
  verify_cert "$APK_TMP" "$TRUSTED_CLIENT_CERTS"
fi

mv "$APK_TMP" "${DEST_DIR}/Nectarlink.apk"
chmod 0644 "${DEST_DIR}/Nectarlink.apk"

if [ "$WITH_SERVICE" = "true" ]; then
  SVC_TMP="${TMP_DIR}/NectarlinkServices.apk"
  if [ -n "$SERVICE_APK" ]; then
    [ -f "$SERVICE_APK" ] || { echo "Service APK not found: $SERVICE_APK" >&2; exit 1; }
    cp "$SERVICE_APK" "$SVC_TMP"
    echo "Using local service APK: $SERVICE_APK"
  else
    SVC_JSON="${TMP_DIR}/service-releases.json"
    curl_gh "https://api.github.com/repos/${SERVICE_REPO}/releases?per_page=30" "$SVC_JSON"
    SVC_META="$(python3 - "$SVC_JSON" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as fh:
    releases = json.load(fh)

target = None
apk_asset = None
sums_asset = None
for rel in releases:
    if rel.get("draft"):
        continue
    assets = rel.get("assets", [])
    cand = next((a for a in assets if a.get("name") == "NectarlinkServices.apk"), None)
    if cand:
        target = rel
        apk_asset = cand
        sums_asset = next((a for a in assets if a.get("name") == "SHA256SUMS.txt"), None)
        break

if not apk_asset:
    sys.stderr.write("No release with NectarlinkServices.apk found\n")
    sys.exit(1)

print(target["tag_name"])
print(apk_asset["browser_download_url"])
print(sums_asset["browser_download_url"] if sums_asset else "")
PY
)"
    SVC_TAG="$(printf '%s\n' "$SVC_META" | sed -n '1p')"
    SVC_URL="$(printf '%s\n' "$SVC_META" | sed -n '2p')"
    SVC_SUMS_URL="$(printf '%s\n' "$SVC_META" | sed -n '3p')"
    echo "Resolved ${SERVICE_REPO}: ${SVC_TAG} -> NectarlinkServices.apk"
    curl -fsSL "$SVC_URL" -o "$SVC_TMP"
    if [ -n "$SVC_SUMS_URL" ]; then
      SVC_SUMS_TMP="${TMP_DIR}/SERVICE_SHA256SUMS.txt"
      curl -fsSL "$SVC_SUMS_URL" -o "$SVC_SUMS_TMP"
      verify_sha256 "$SVC_TMP" "NectarlinkServices.apk" "$SVC_SUMS_TMP"
    fi
  fi

  if [ "$VERIFY_CERT" = "true" ]; then
    verify_cert "$SVC_TMP" "$TRUSTED_SERVICE_CERTS"
  fi

  mv "$SVC_TMP" "${DEST_DIR}/NectarlinkServices.apk"
  chmod 0644 "${DEST_DIR}/NectarlinkServices.apk"
fi

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

if [ "$WITH_SERVICE" = "true" ]; then
  cat >> "${DEST_DIR}/Android.bp" <<EOF

android_app_import {
    name: "NectarlinkServicesPrebuilt",
    stem: "NectarlinkServices",
    apk: "NectarlinkServices.apk",
    presigned: true,
    privileged: true,
    system_ext_specific: true,
    overrides: ["NectarlinkServices"],
    dex_preopt: {
        enabled: false,
    },
    required: [
        "privapp-permissions-sn.nectarlink.services.xml",
        "default-permissions-sn.nectarlink.services.xml",
        "sysconfig-sn.nectarlink.services.xml",
    ],
}
EOF
fi

echo "Staged ${DEST_DIR}/Android.bp"
