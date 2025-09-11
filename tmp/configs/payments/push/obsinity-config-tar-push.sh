#!/usr/bin/env bash
set -euo pipefail
# Usage: ./push/obsinity-config-tar-push.sh services/payments [/tmp/payments.tar.gz]
BASE_URL="${BASE_URL:-http://localhost:8086}"
TOKEN="${TOKEN:-}"
SERVICE_DIR="${1:?Pass service dir (e.g., services/payments)}"
TAR="${2:-/tmp/obsinity-config.tar.gz}"
if [[ ! -d "$SERVICE_DIR" ]]; then
  echo "Directory not found: $SERVICE_DIR" >&2
  exit 1
fi
echo "Packing $SERVICE_DIR into $TAR ..."
tar -C "$(dirname "$SERVICE_DIR")" -czf "$TAR" "$(basename "$SERVICE_DIR")"
header_auth=()
if [[ -n "$TOKEN" ]]; then
  header_auth=(-H "Authorization: Bearer $TOKEN")
fi
echo "Uploading tarball to ${BASE_URL}/api/admin/configs/import?mode=upsert"
curl -sS -X POST "${BASE_URL}/api/admin/configs/import?mode=upsert"       -H "Content-Type: application/gzip" "${header_auth[@]}"       --data-binary "@${TAR}"
echo
echo "Done."
